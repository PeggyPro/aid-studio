package com.aid.runtime.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantPlanningPreparationRejectedException;
import com.aid.runtime.assistant.AssistantModuleExtensionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.aid.assistant.creative.service.CreativeContextAuthorizer.SourceAuthorizationRejectedException;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolBudgetOffer;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;
import com.aid.runtime.assistant.AssistantDecisionValidationRejectedException;
import com.aid.runtime.assistant.SpatialPrevisGuidancePolicy;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Unique Java-owned Tool registry. It cannot register or execute uploaded scripts. */
@Component
public class RuntimeToolRegistry
{
    private static final int MAX_TEXT_LENGTH = 100_000;
    private static final String TEXT_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":100000}},\"required\":[\"text\"],\"additionalProperties\":false}";
    private static final Set<String> RESULT_STATES = Set.of("SUCCEEDED", "WAITING_TASK", "WAITING_RESOURCE", "FAILED", "UNKNOWN");
    private static final Set<String> SIDE_EFFECT_LEVELS = Set.of("PURE", "READ", "DATABASE_WRITE", "BILLABLE");

    private final Map<String, Descriptor> descriptors;
    private final Map<String, RuntimeToolProvider> providers;
    private final Map<String, JSONSchema> inputSchemas;
    private final Map<String, JSONSchema> outputSchemas;
    private final Map<String, Set<String>> businessPromptTextFields;
    @Autowired
    private ObjectProvider<AssistantModuleExtensionRegistry> assistantModules;

    @Autowired
    public RuntimeToolRegistry(@Autowired(required = false) List<RuntimeToolProvider> providerBeans)
    {
        Map<String, Descriptor> descriptorValues = new LinkedHashMap<>();
        Map<String, RuntimeToolProvider> providerValues = new LinkedHashMap<>();
        registerBuiltin(descriptorValues, "text.extract_json", "Extract JSON",
                "Extract the first complete JSON object from text.", "{\"type\":\"object\"}");
        registerBuiltin(descriptorValues, "text.statistics", "Text statistics",
                "Count characters, lines, and non-empty lines.",
                "{\"type\":\"object\",\"required\":[\"characters\",\"lines\",\"nonEmptyLines\"]}");

        for (RuntimeToolProvider provider : providerBeans == null ? List.<RuntimeToolProvider>of() : providerBeans)
        {
            List<Descriptor> offered = provider.descriptors();
            if (offered == null || offered.isEmpty())
            {
                throw new IllegalStateException("Runtime Tool provider has no descriptors: "
                        + provider.getClass().getName());
            }
            for (Descriptor descriptor : offered)
            {
                if (descriptorValues.putIfAbsent(descriptor.code(), descriptor) != null
                        || providerValues.putIfAbsent(descriptor.code(), provider) != null)
                {
                    throw new IllegalStateException("Duplicate Runtime Tool code: " + descriptor.code());
                }
            }
        }
        Map<String, JSONSchema> compiledInputs = new LinkedHashMap<>();
        Map<String, JSONSchema> compiledOutputs = new LinkedHashMap<>();
        Map<String, Set<String>> promptTextFields = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptorValues.values())
        {
            validateDescriptor(descriptor);
            compiledInputs.put(descriptor.code(), compileSchema(descriptor.inputSchema(), descriptor.code()));
            compiledOutputs.put(descriptor.code(), compileSchema(descriptor.outputSchema(), descriptor.code()));
            promptTextFields.put(descriptor.code(), declaredPromptTextFields(descriptor.inputSchema()));
        }
        descriptors = Map.copyOf(descriptorValues);
        providers = Map.copyOf(providerValues);
        inputSchemas = Map.copyOf(compiledInputs);
        outputSchemas = Map.copyOf(compiledOutputs);
        businessPromptTextFields = Map.copyOf(promptTextFields);
    }

    /** Java-owned schema metadata, never an instruction or execution authorization. */
    public Set<String> decisionBusinessPromptTextFields(String toolCode, String contractVersion)
    {
        Descriptor descriptor = require(toolCode);
        if (!descriptor.contractVersion().equals(contractVersion))
            throw new ServiceException("Tool契约冲突");
        return businessPromptTextFields.getOrDefault(toolCode, Set.of());
    }

    private Set<String> declaredPromptTextFields(String inputSchema)
    {
        JSONObject schema = JSON.parseObject(inputSchema);
        if (!"object".equals(schema.get("type"))
                || !Boolean.FALSE.equals(schema.get("additionalProperties"))) return Set.of();
        JSONObject properties = schema.getJSONObject("properties");
        if (properties == null) return Set.of();
        Set<String> fields = new HashSet<>();
        for (String name : properties.keySet())
        {
            String normalized = name.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
            Object raw = properties.get(name);
            if (("systemprompt".equals(normalized) || "instruction".equals(normalized))
                    && raw instanceof JSONObject property && "string".equals(property.get("type")))
                fields.add(name);
        }
        return Set.copyOf(fields);
    }

    public List<Descriptor> options()
    {
        return new ArrayList<>(descriptors.values());
    }

    public boolean supports(String code)
    {
        return descriptors.containsKey(code);
    }

    /** Discovery can omit an explicitly optional provider that is absent from this deployment. */
    public Descriptor discoverPinned(String moduleCode, String toolCode)
    {
        Descriptor descriptor = toolCode == null ? null : descriptors.get(toolCode);
        if (descriptor != null) return descriptor;
        var modules = assistantModules == null ? null : assistantModules.getIfAvailable();
        if (toolCode != null && modules != null && modules.isOptionalDiscoveryTool(moduleCode, toolCode))
            return null;
        throw new AssistantPlanningPreparationRejectedException(
                AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_BOUND_TOOL_UNAVAILABLE);
    }

    /** Classification only; binding, availability and execution authorization remain separate. */
    public boolean isUnbilledRead(String toolCode)
    {
        Descriptor descriptor = require(toolCode);
        return "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable();
    }

    /** Read-only provider metadata; it does not resolve references or authorize execution. */
    public RuntimeToolProvider.OutputReferenceKind planningOutputReferenceKind(String toolCode)
    {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        if (provider == null) return RuntimeToolProvider.OutputReferenceKind.UNKNOWN;
        RuntimeToolProvider.OutputReferenceKind kind = provider.planningOutputReferenceKind(descriptor);
        if (kind == null) throw new ServiceException("TOOL_OUTPUT_REFERENCE_KIND_INVALID");
        return kind;
    }

    public boolean requiresExecutionCheckpoint(String toolCode) {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        return provider != null && provider.requiresExecutionCheckpoint(descriptor);
    }

    public String activitySubject(String toolCode) {
        if (!supports(toolCode)) return null;
        RuntimeToolProvider provider = providers.get(toolCode);
        String subject = provider == null ? null : provider.activitySubject(require(toolCode));
        return subject != null && !subject.isBlank() && subject.length() <= 80 && !subject.matches("(?s).*\\p{Cntrl}.*") ? subject : null;
    }

    public String planningArtifactType(String toolCode) {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        String type = provider == null ? null : provider.planningArtifactType(descriptor);
        if (type != null && (!type.matches("[A-Z][A-Z0-9_]{0,63}")
                || planningOutputReferenceKind(toolCode) != RuntimeToolProvider.OutputReferenceKind.RUNTIME_ARTIFACT))
            throw new ServiceException("TOOL_OUTPUT_ARTIFACT_TYPE_INVALID");
        return type;
    }

    public Map<String, Object> hostOwnedInputDefaults(String toolCode) {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        if (provider == null) return Map.of();
        Map<String, Object> defaults = provider.hostOwnedInputDefaults(descriptor);
        if (defaults == null || !defaults.isEmpty() && (descriptor.billable()
                || !"READ".equals(descriptor.sideEffectLevel()))) throw new ServiceException("TOOL_HOST_DEFAULTS_INVALID");
        return Map.copyOf(defaults);
    }

    public Set<String> capabilityIndependentInputFields(String toolCode) {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        Set<String> fields = provider == null ? Set.of() : provider.capabilityIndependentInputFields(descriptor);
        JSONObject properties = JSON.parseObject(descriptor.inputSchema()).getJSONObject("properties");
        if (fields == null || fields.stream().anyMatch(field -> field == null
                || !field.matches("[A-Za-z_][A-Za-z0-9_]*")
                || Set.of("modelCode", "capabilityCode").contains(field)
                || properties == null || !properties.containsKey(field)))
            throw new ServiceException("TOOL_CAPABILITY_INDEPENDENT_FIELDS_INVALID");
        return Set.copyOf(fields);
    }

    /** Authoritative provider-declared media kind for billable adapter Tools. */
    public RuntimeToolProvider.BillingMediaType billingMediaType(String toolCode)
    {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        if (provider == null || !descriptor.billable() || descriptor.requiredCapabilities() == null) return null;
        RuntimeToolProvider.BillingMediaType declared = provider.billingMediaType(descriptor);
        if (declared == null || !descriptor.requiredCapabilities().contains(declared.name())) return null;
        return declared;
    }

    public String persistedMediaType(String toolCode) {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        if (provider == null || billingMediaType(toolCode) == null) return null;
        String value = provider.persistedMediaType(descriptor);
        if (value == null || !Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "COMPOSE").contains(value))
            throw new ServiceException("Tool persisted media declaration is invalid");
        return value;
    }

    /** Pure registered metadata, without discovery, model queries or execution. */
    public RuntimeToolProvider.BillingMediaType modelCatalogMediaType(String toolCode)
    {
        Descriptor descriptor = require(toolCode);
        RuntimeToolProvider provider = providers.get(toolCode);
        if (provider == null || !descriptor.billable() || descriptor.requiredCapabilities() == null) return null;
        RuntimeToolProvider.BillingMediaType declared = provider.modelCatalogMediaType(descriptor);
        return declared != null && descriptor.requiredCapabilities().contains(declared.name()) ? declared : null;
    }

    /** Reuses the caller's loaded metadata; never queries models or executes a tool. */
    public com.aid.model.vo.AiModelVO projectModelCatalog(String toolCode, com.aid.model.vo.AiModelVO model) {
        Descriptor descriptor=require(toolCode);
        RuntimeToolProvider provider=providers.get(toolCode);
        return provider==null ? model : provider.projectModelCatalog(descriptor,model);
    }

    public java.util.Map<String,Object> modelCatalogInputSupport(String toolCode, com.aid.model.vo.AiModelVO model) {
        Descriptor descriptor=require(toolCode);RuntimeToolProvider provider=providers.get(toolCode);
        return provider==null ? java.util.Map.of() : provider.modelCatalogInputSupport(descriptor,model);
    }

    public List<Descriptor> availableOptions(RuntimeToolAvailabilityContext context,
                                             List<PinnedTool> pinnedTools)
    {
        if (context == null) throw new ServiceException("Tool discovery context is required");
        return context.withDiscoverySnapshot(() -> availableOptionsInDiscovery(context, pinnedTools));
    }

    private List<Descriptor> availableOptionsInDiscovery(
            RuntimeToolAvailabilityContext context, List<PinnedTool> pinnedTools)
    {
        if (context == null || context.getUserId() == null || pinnedTools == null)
            throw new ServiceException("Tool\u53ef\u7528\u6027\u4e0a\u4e0b\u6587\u65e0\u6548");
        if (SpatialPrevisGuidancePolicy.appliesToModule(context.getModuleCode()))
        {
            Set<String> pinnedCodes = new HashSet<>();
            for (PinnedTool pin : pinnedTools)
            {
                Descriptor descriptor = require(pin.code());
                if (!pinnedCodes.add(pin.code())
                        || !SpatialPrevisGuidancePolicy.allowsReadTool(
                        descriptor.code(), descriptor.sideEffectLevel(), descriptor.billable()))
                    throw new ServiceException("空间预演方案助手工具范围无效");
            }
            if (!SpatialPrevisGuidancePolicy.READ_TOOL_CODES.equals(pinnedCodes))
                throw new ServiceException("空间预演方案助手工具范围无效");
        }
        List<Descriptor> result = new ArrayList<>();
        for (PinnedTool pin : pinnedTools)
        {
            Descriptor descriptor = discoverPinned(context.getModuleCode(), pin.code());
            if (descriptor == null) continue;
            if (!Objects.equals(descriptor.contractVersion(), pin.contractVersion())
                    || !Objects.equals(descriptor.schemaDigest(), pin.schemaDigest()))
                throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                        com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
            RuntimeToolProvider provider = providers.get(descriptor.code());
            RuntimeToolAvailability availability = provider == null
                    ? RuntimeToolAvailability.enabled() : provider.availability(context, descriptor);
            if (availability == null) throw new ServiceException("Tool\u53ef\u7528\u6027\u7ed3\u679c\u65e0\u6548");
            if (availability.available()) result.add(descriptor);
        }
        return result;
    }

    /** Discover tool contracts without enumerating candidate models or calculating budget offers. */
    public List<BudgetOption> availablePlanningOptions(RuntimeToolAvailabilityContext context,
                                                      List<PinnedTool> pinnedTools)
    {
        boolean previous = context.isMetadataDiscovery();
        context.setMetadataDiscovery(true);
        try {
            return context.withDiscoverySnapshot(() -> availableOptions(context, pinnedTools).stream()
                    .map(descriptor -> new BudgetOption(descriptor,
                            new RuntimeToolBudgetOffer(List.of(), descriptor.requiredCapabilities(), List.of())))
                    .toList());
        } finally {
            context.setMetadataDiscovery(previous);
        }
    }

    public List<BudgetOption> availableBudgetOptions(RuntimeToolAvailabilityContext context,
                                                     List<PinnedTool> pinnedTools)
    {
        if (context == null) throw new ServiceException("Tool discovery context is required");
        return context.withDiscoverySnapshot(() -> availableBudgetOptionsInDiscovery(context, pinnedTools));
    }

    private List<BudgetOption> availableBudgetOptionsInDiscovery(
            RuntimeToolAvailabilityContext context, List<PinnedTool> pinnedTools)
    {
        List<Descriptor> available = availableOptions(context, pinnedTools);
        List<BudgetOption> result = new ArrayList<>();
        for (Descriptor descriptor : available)
        {
            RuntimeToolProvider provider = providers.get(descriptor.code());
            RuntimeToolBudgetOffer raw = provider == null
                    ? new RuntimeToolBudgetOffer(List.of(), descriptor.requiredCapabilities(), List.of())
                    : provider.budgetOffer(context, descriptor);
            if (raw == null) throw new ServiceException("Tool\u9884\u7b97\u9009\u9879\u65e0\u6548");
            RuntimeToolBudgetOffer offer = new RuntimeToolBudgetOffer(
                    normalizeStrings(raw.getModelCodes(), "Tool\u6a21\u578b\u9009\u9879\u65e0\u6548"),
                    normalizeStrings(raw.getCapabilityCodes(), "Tool\u80fd\u529b\u9009\u9879\u65e0\u6548"),
                    normalizeStrings(raw.getMediaTypes(), "Tool\u5a92\u4f53\u9009\u9879\u65e0\u6548"));
            offer.setModelChoices(normalizeModelChoices(raw.getModelChoices(), offer));
            if (!Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "MUSIC").containsAll(offer.getMediaTypes()))
                throw new ServiceException("Tool\u5a92\u4f53\u9009\u9879\u65e0\u6548");
            if (descriptor.billable() && (offer.getModelCodes().isEmpty()
                    || offer.getCapabilityCodes().isEmpty() || offer.getMediaTypes().isEmpty())) continue;
            result.add(new BudgetOption(descriptor, offer));
        }
        return result;
    }

    private List<RuntimeToolBudgetOffer.ModelChoice> normalizeModelChoices(
            List<RuntimeToolBudgetOffer.ModelChoice> choices, RuntimeToolBudgetOffer offer)
    {
        if (choices == null || choices.isEmpty()) return List.of();
        Map<List<String>, RuntimeToolBudgetOffer.ModelChoice> normalized = new LinkedHashMap<>();
        for (RuntimeToolBudgetOffer.ModelChoice choice : choices)
        {
            if (choice == null || StrUtil.isBlank(choice.modelCode()) || StrUtil.isBlank(choice.capabilityCode()))
                throw new ServiceException("Tool模型参数目录无效");
            String model = choice.modelCode().trim();
            String capability = choice.capabilityCode().trim();
            if (!offer.getModelCodes().contains(model) || !offer.getCapabilityCodes().contains(capability))
                throw new ServiceException("Tool模型参数目录无效");
            normalized.putIfAbsent(List.of(model, capability), new RuntimeToolBudgetOffer.ModelChoice(
                    model, capability, choice.generateMode(), choice.free(),
                    choice.parameterSchema() == null ? List.of() : choice.parameterSchema(),
                    choice.parameterRules()));
        }
        return List.copyOf(normalized.values());
    }

    public Descriptor require(String code)
    {
        Descriptor descriptor = descriptors.get(code);
        if (descriptor == null)
        {
            throw new ServiceException("Tool\u4e0d\u53ef\u7528");
        }
        return descriptor;
    }

    public String completedOperationIdentity(String code, String argumentsJson, String preparedPayloadJson) {
        Descriptor descriptor = require(code);
        RuntimeToolProvider provider = providers.get(code);
        if (!descriptor.billable() || provider == null || StrUtil.isBlank(argumentsJson)
                || StrUtil.isBlank(preparedPayloadJson)) throw new ServiceException("OPERATION_HISTORY_IDENTITY_INVALID");
        String digest = provider.completedOperationIdentity(descriptor, argumentsJson, preparedPayloadJson);
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new ServiceException("OPERATION_HISTORY_IDENTITY_INVALID");
        return digest;
    }

    /** Revalidate an immutable persisted Tool receipt against its exact registered contract. */
    public Descriptor requirePinnedOutput(String code, String contractVersion,
                                          String schemaDigest, String outputJson)
    {
        Descriptor descriptor = require(code);
        if (!Objects.equals(descriptor.contractVersion(), contractVersion)
                || !Objects.equals(descriptor.schemaDigest(), schemaDigest))
            throw new ServiceException("Tool\u56fa\u5b9a\u5951\u7ea6\u5df2\u53d8\u66f4");
        Object output;
        try
        {
            output = JSON.parse(outputJson);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("Tool\u6301\u4e45\u8f93\u51fa\u4e0d\u662fJSON");
        }
        validate(outputSchemas.get(code), output, "Tool\u6301\u4e45\u8f93\u51fa\u4e0d\u7b26\u5408\u5951\u7ea6");
        return descriptor;
    }

    /** Execute a coordinator-reconstructed invocation against one uniquely registered provider. */
    public RuntimeToolResult execute(RuntimeToolInvocation invocation)
    {
        requireInvocation(invocation);
        RuntimeToolPreparedInvocation prepared = prepare(invocation);
        if (prepared.getDescriptor().billable())
            throw new ServiceException("\u8ba1\u8d39Tool\u5fc5\u987b\u4f7f\u7528\u4e24\u9636\u6bb5\u63d0\u4ea4");
        return executePrepared(prepared, null);
    }

    public RuntimeToolPreparedInvocation prepare(RuntimeToolInvocation invocation)
    {
        requirePreparationInput(invocation);
        Descriptor descriptor;
        try
        {
            descriptor = require(invocation.getToolCode());
        }
        catch (ServiceException changed)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED,
                    changed);
        }
        if (!descriptor.contractVersion().equals(invocation.getContractVersion())
                || !descriptor.schemaDigest().equals(invocation.getSchemaDigest()))
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
        }
        RuntimeToolProvider provider = providers.get(invocation.getToolCode());
        JSONObject arguments;
        try
        {
            arguments = JSON.parseObject(invocation.getArgumentsJson());
        }
        catch (RuntimeException error)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    error);
        }
        validatePreparationArguments(inputSchemas.get(invocation.getToolCode()), arguments);
        RuntimeToolPreparation preparation;
        if (provider == null)
        {
            preparation = new RuntimeToolPreparation(invocation.getArgumentsJson(), null, invocation.getOperationScope());
        }
        else
        {
            try
            {
                preparation = provider.prepare(invocation);
            }
            catch (SourceAuthorizationRejectedException denied)
            {
                // Only the typed, side-effect-free manifest guard proves a definite preparation rejection.
                // Infrastructure failures and errors after executePrepared retain their existing handling.
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID,
                        denied);
            }
        }
        if (preparation == null || StrUtil.isBlank(preparation.getPreparedPayloadJson())
                || descriptor.billable() && preparation.getBillingIntent() == null
                || !descriptor.billable() && preparation.getBillingIntent() != null)
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_INVALID);
        try
        {
            JSON.parse(preparation.getPreparedPayloadJson());
        }
        catch (RuntimeException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_INVALID,
                    invalid);
        }
        try
        {
            requirePreparedScope(invocation, descriptor, preparation.getOperationScope(),
                    preparation.getBillingIntent());
        }
        catch (RuntimeToolPreparationRejectedException rejected)
        {
            throw rejected;
        }
        catch (RuntimeException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID,
                    invalid);
        }
        RuntimeToolPreparedInvocation prepared = new RuntimeToolPreparedInvocation();
        prepared.setDescriptor(descriptor);
        prepared.setRunId(invocation.getRunId());
        prepared.setUserId(invocation.getUserId());
        prepared.setRuntimeStepId(invocation.getRuntimeStepId());
        prepared.setPlanRevision(invocation.getPlanRevision());
        prepared.setStableStepId(invocation.getStableStepId());
        prepared.setAttemptNo(invocation.getAttemptNo());
        prepared.setOperationKind(invocation.getOperationKind());
        prepared.setToolCode(invocation.getToolCode());
        prepared.setContractVersion(invocation.getContractVersion());
        prepared.setSchemaDigest(invocation.getSchemaDigest());
        prepared.setArgumentsJson(invocation.getArgumentsJson());
        prepared.setArgumentsDigest(invocation.getArgumentsDigest());
        prepared.setContextManifestJson(invocation.getContextManifestJson());
        prepared.setApprovalId(invocation.getApprovalId());
        prepared.setApprovedScopeDigest(invocation.getApprovedScopeDigest());
        prepared.setIdempotencyKey(invocation.getIdempotencyKey());
        prepared.setRunLease(invocation.getLease());
        prepared.setOperationScope(preparation.getOperationScope());
        prepared.setPreparedPayloadJson(preparation.getPreparedPayloadJson());
        prepared.setPreparedPayloadDigest(SecureUtil.sha256(preparation.getPreparedPayloadJson()));
        prepared.setBillingIntent(preparation.getBillingIntent());
        return prepared;
    }

    /** Validate the complete persisted submission snapshot before any provider call is possible. */
    public void validatePreparedSubmission(RuntimeToolPreparedInvocation prepared,
                                           BillingBudgetContext billingBudgetContext)
    {
        try
        {
            requirePrepared(prepared);
            Object preparedPayload = JSON.parse(prepared.getPreparedPayloadJson());
            if (preparedPayload == null
                    || !providers.containsKey(prepared.getToolCode()) && !(preparedPayload instanceof JSONObject))
                throw new ServiceException("Tool\u51c6\u5907\u8d1f\u8f7d\u5feb\u7167\u65e0\u6548");
            Descriptor descriptor = require(prepared.getToolCode());
            if (!descriptor.equals(prepared.getDescriptor())
                    || descriptor.billable() != (billingBudgetContext != null))
                throw new ServiceException("Tool\u51c6\u5907\u5feb\u7167\u5df2\u53d8\u66f4");
            if (descriptor.billable()) requireBudgetIdentity(prepared, billingBudgetContext);
        }
        catch (RuntimeToolPreparationRejectedException rejected)
        {
            throw rejected;
        }
        catch (RuntimeException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IDENTITY_CHANGED,
                    invalid);
        }
    }

    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext)
    {
        validatePreparedSubmission(prepared, billingBudgetContext);
        RuntimeToolProvider provider = providers.get(prepared.getToolCode());
        RuntimeToolResult result;
        if (provider == null)
        {
            JSONObject arguments = JSON.parseObject(prepared.getPreparedPayloadJson());
            result = RuntimeToolResult.succeeded(executeBuiltin(prepared.getToolCode(),
                    arguments == null ? null : arguments.getString("text")));
        }
        else
        {
            try
            {
                result = provider.executePrepared(prepared, billingBudgetContext);
            }
            catch (SourceAuthorizationRejectedException denied)
            {
                Descriptor descriptor = prepared.getDescriptor();
                if (!"READ".equals(descriptor.sideEffectLevel()) || descriptor.billable()
                        || !"REPLAY_SAFE".equals(descriptor.recoveryPolicy())) throw denied;
                // This typed manifest guard rejects before reading or mutating the source.
                // A replay-safe read has no uncertain write to reconcile; infrastructure and
                // mutating-provider errors retain their existing conservative handling.
                result = new RuntimeToolResult();
                result.setStatus("FAILED");
                result.setErrorCode(denied.getErrorCode());
                result.setSafeSummary("当前请求未获准读取该来源；请重新选择可访问的既有来源后继续，原文与历史记录保留。");
                result.setRetryable(false);
            }
        }
        if (result == null || !RESULT_STATES.contains(result.getStatus()))
        {
            throw new ServiceException("Tool\u7ed3\u679c\u65e0\u6548");
        }
        if ("SUCCEEDED".equals(result.getStatus()))
        {
            validateCompletedOutput(prepared, result);
        }
        return result;
    }

    private void validateCompletedOutput(RuntimeToolPreparedInvocation prepared, RuntimeToolResult result)
    {
        JSONSchema schema = outputSchemas.get(prepared.getToolCode());
        if (schema == null) throw new ServiceException("Tool\u5951\u7ea6\u4e0d\u53ef\u7528");
        Object output;
        try { output = JSON.parse(result.getOutputJson()); }
        catch (RuntimeException invalid) { throw outputRejected(prepared, result); }
        // Schema/runtime failures are still uncertain; only a completed validation rejection is typed.
        if (!schema.validate(output).isSuccess()) throw outputRejected(prepared, result);
    }

    private RuntimeToolOutputValidationException outputRejected(RuntimeToolPreparedInvocation prepared,
                                                               RuntimeToolResult result)
    {
        Descriptor descriptor = prepared.getDescriptor();
        boolean definiteRead = "READ".equals(descriptor.sideEffectLevel())
                && !descriptor.billable() && "REPLAY_SAFE".equals(descriptor.recoveryPolicy())
                && result.getMediaTaskId() == null
                && (result.getOutputArtifactRefs() == null || result.getOutputArtifactRefs().isEmpty())
                && (result.getContextRevisionUpdates() == null || result.getContextRevisionUpdates().isEmpty());
        return new RuntimeToolOutputValidationException(prepared.getToolCode(), definiteRead);
    }

    /** Server-owned negative entry evidence; never a substitute for a provider receipt. */
    public RuntimeToolProvider.SubmissionEntryEvidence inspectSubmissionEntry(
            RuntimeToolPreparedInvocation prepared, BillingBudgetContext budget)
    {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new ServiceException("TOOL_ENTRY_EVIDENCE_TRANSACTION_REQUIRED");
        validatePreparedSubmission(prepared, budget);
        Descriptor descriptor = require(prepared.getToolCode());
        RuntimeToolProvider provider = providers.get(prepared.getToolCode());
        if (provider == null || !descriptor.billable()
                || !"RECONCILE_TASK".equals(descriptor.recoveryPolicy()))
            return RuntimeToolProvider.SubmissionEntryEvidence.UNKNOWN;
        RuntimeToolProvider.BillingMediaType media = provider.billingMediaType(descriptor);
        if (media != RuntimeToolProvider.BillingMediaType.IMAGE
                && media != RuntimeToolProvider.BillingMediaType.VIDEO
                && media != RuntimeToolProvider.BillingMediaType.AUDIO
                && media != RuntimeToolProvider.BillingMediaType.MUSIC)
            return RuntimeToolProvider.SubmissionEntryEvidence.UNKNOWN;
        RuntimeToolProvider.SubmissionEntryEvidence evidence = provider.inspectSubmissionEntry(prepared, budget);
        return evidence == null ? RuntimeToolProvider.SubmissionEntryEvidence.UNKNOWN : evidence;
    }

    public void releaseUnsubmitted(RuntimeToolPreparedInvocation prepared) {
        require(prepared.getToolCode());
        RuntimeToolProvider provider = providers.get(prepared.getToolCode());
        if (provider != null) provider.releaseUnsubmitted(prepared);
    }

    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared)
    {
        requirePrepared(prepared);
        Descriptor descriptor = require(prepared.getToolCode());
        if (!descriptor.equals(prepared.getDescriptor()))
            throw new ServiceException("Tool\u51c6\u5907\u5feb\u7167\u5df2\u53d8\u66f4");
        RuntimeToolProvider provider = providers.get(prepared.getToolCode());
        RuntimeToolResult result = provider == null
                ? RuntimeToolResult.unknown("TOOL_RECONCILIATION_UNSUPPORTED", "\u8be5Tool\u65e0\u6301\u4e45\u56de\u6267")
                : provider.reconcilePrepared(prepared);
        if (result == null || !RESULT_STATES.contains(result.getStatus()))
            throw new ServiceException("Tool\u5bf9\u8d26\u7ed3\u679c\u65e0\u6548");
        if (result.getOutputJson() != null)
        {
            JSONObject output = JSON.parseObject(result.getOutputJson());
            validate(outputSchemas.get(prepared.getToolCode()), output, "Tool\u5bf9\u8d26\u8f93\u51fa\u4e0d\u7b26\u5408\u5951\u7ea6");
        }
        return result;
    }

    /** Compatibility entry for existing internal pure-text callers. */
    public String execute(String code, String input)
    {
        if (providers.containsKey(code))
        {
            throw new ServiceException("Tool\u9700\u8fd0\u884c\u4e0a\u4e0b\u6587");
        }
        require(code);
        return executeBuiltin(code, input);
    }

    private String executeBuiltin(String code, String input)
    {
        String value = input == null ? "" : input;
        if (value.length() > MAX_TEXT_LENGTH)
        {
            throw new ServiceException("Tool\u8f93\u5165\u8fc7\u957f");
        }
        if ("text.statistics".equals(code))
        {
            String[] lines = value.split("\\n", -1);
            long nonEmpty = Arrays.stream(lines).filter(line -> !line.isBlank()).count();
            return JSON.toJSONString(Map.of("characters", value.codePointCount(0, value.length()),
                    "lines", lines.length, "nonEmptyLines", nonEmpty));
        }
        int start = value.indexOf('{');
        if (start < 0) throw new ServiceException("\u672a\u627e\u5230JSON");
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < value.length(); i++)
        {
            char current = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && current == '\\') { escaped = true; continue; }
            if (current == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (current == '{') depth++;
            if (current == '}' && --depth == 0)
            {
                String selected = value.substring(start, i + 1);
                try { return JSON.parseObject(selected).toJSONString(); }
                catch (RuntimeException error) { throw new ServiceException("JSON\u5185\u5bb9\u65e0\u6548"); }
            }
        }
        throw new ServiceException("JSON\u5bf9\u8c61\u672a\u95ed\u5408");
    }

    private void requireInvocation(RuntimeToolInvocation invocation)
    {
        requirePreparationInput(invocation);
        if (invocation.getRuntimeStepId() == null || invocation.getApprovalId() == null
                || StrUtil.isBlank(invocation.getApprovedScopeDigest()) || invocation.getOperationScope() == null)
        {
            throw new ServiceException("Tool\u8c03\u7528\u65e0\u6548");
        }
    }

    private void requirePreparationInput(RuntimeToolInvocation invocation)
    {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null
                || invocation.getPlanRevision() == null || StrUtil.isBlank(invocation.getStableStepId())
                || invocation.getAttemptNo() == null || invocation.getAttemptNo() < 1
                || StrUtil.isBlank(invocation.getOperationKind()) || StrUtil.isBlank(invocation.getToolCode())
                || StrUtil.isBlank(invocation.getContractVersion()) || StrUtil.isBlank(invocation.getSchemaDigest())
                || StrUtil.isBlank(invocation.getArgumentsJson()) || StrUtil.isBlank(invocation.getArgumentsDigest())
                || StrUtil.isBlank(invocation.getContextManifestJson())
                || invocation.getOperationScope() == null
                || StrUtil.isBlank(invocation.getIdempotencyKey()) || invocation.getLease() == null
                || StrUtil.isBlank(invocation.getLease().getOwner()) || invocation.getLease().getFencingToken() == null)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REQUEST_INVALID);
        }
        if (!SecureUtil.sha256(invocation.getArgumentsJson()).equals(invocation.getArgumentsDigest()))
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID);
        }
    }

    private void validatePreparationArguments(JSONSchema schema, Object value)
    {
        if (schema == null)
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
        ValidateResult result;
        try
        {
            result = schema.validate(value);
        }
        catch (RuntimeException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        if (!result.isSuccess())
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID);
    }

    private RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason)
    {
        return new RuntimeToolPreparationRejectedException(reason);
    }

    private RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason, Throwable cause)
    {
        return new RuntimeToolPreparationRejectedException(reason, cause);
    }

    private void requirePreparedScope(RuntimeToolInvocation invocation, Descriptor descriptor,
                                      AssistantBudgetOperationScope prepared,
                                      com.aid.runtime.tool.RuntimeToolBillingIntent billingIntent)
    {
        AssistantBudgetOperationScope requested = invocation.getOperationScope();
        if (requested == null || prepared == null
                || !Objects.equals(requested.getOperationKind(), invocation.getOperationKind())
                || !Objects.equals(requested.getToolCode(), invocation.getToolCode())
                || !Objects.equals(prepared.getOperationKind(), requested.getOperationKind())
                || !Objects.equals(prepared.getToolCode(), requested.getToolCode())
                || prepared.isCountsAsIteration() != requested.isCountsAsIteration())
            throw new ServiceException("Tool\u6388\u6743\u8303\u56f4\u65e0\u6548");
        if (requested.getModelCode() != null || requested.getQuotedUpperBound() != null
                || requested.getCapabilityCodes() != null && !requested.getCapabilityCodes().isEmpty()
                || requested.getMediaTypes() != null && !requested.getMediaTypes().isEmpty()
                || requested.getTargetRefs() != null && !requested.getTargetRefs().isEmpty())
            throw new ServiceException("Tool\u51c6\u5907\u524d\u4e0d\u5f97\u9884\u6d4b\u6267\u884c\u8303\u56f4");

        prepared.setCapabilityCodes(normalizeStrings(prepared.getCapabilityCodes(), "Tool\u80fd\u529b\u8303\u56f4\u65e0\u6548"));
        prepared.setMediaTypes(normalizeStrings(prepared.getMediaTypes(), "Tool\u5a92\u4f53\u8303\u56f4\u65e0\u6548"));
        prepared.setTargetRefs(normalizeTargets(prepared.getTargetRefs(), invocation.getContextManifestJson()));
        if (descriptor.billable())
        {
            if (billingIntent == null || StrUtil.isBlank(prepared.getModelCode())
                    || prepared.getCapabilityCodes().isEmpty() || prepared.getMediaTypes().isEmpty()
                    || prepared.getQuotedUpperBound() == null || prepared.getQuotedUpperBound().signum() < 0
                    || !Objects.equals(prepared.getQuotedUpperBound(), billingIntent.getQuotedUpperBound())
                    || !mediaScopeMatches(invocation.getToolCode(), prepared.getMediaTypes(), billingIntent.getMediaType()))
                throw new ServiceException("Tool\u8ba1\u8d39\u8303\u56f4\u65e0\u6548");
        }
        else
        {
            if (billingIntent != null) throw new ServiceException("\u975e\u8ba1\u8d39Tool\u4e0d\u5f97\u8fd4\u56de\u8ba1\u8d39\u610f\u56fe");
            if (prepared.getQuotedUpperBound() == null) prepared.setQuotedUpperBound(java.math.BigDecimal.ZERO);
            if (prepared.getQuotedUpperBound().signum() != 0)
                throw new ServiceException("\u975e\u8ba1\u8d39Tool\u4e0a\u9650\u5fc5\u987b\u4e3a0");
        }
    }

    private List<String> normalizeStrings(List<String> values, String error)
    {
        if (values == null) return new ArrayList<>();
        List<String> normalized = values.stream().filter(StrUtil::isNotBlank).map(String::trim)
                .distinct().sorted().toList();
        if (normalized.size() != values.size()) throw new ServiceException(error);
        return new ArrayList<>(normalized);
    }

    private List<AssistantRequests.ContextRef> normalizeTargets(List<AssistantRequests.ContextRef> values,
                                                                 String manifestJson)
    {
        if (values == null || values.isEmpty()) return new ArrayList<>();
        JSONObject manifest;
        try { manifest = JSON.parseObject(manifestJson); }
        catch (RuntimeException invalid) { throw new ServiceException("Tool\u4e0a\u4e0b\u6587\u6e05\u5355\u65e0\u6548"); }
        Set<String> permitted = new HashSet<>();
        if (manifest == null || !"assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                || manifest.getJSONArray("refs") == null)
            throw new ServiceException("Tool\u4e0a\u4e0b\u6587\u6e05\u5355\u65e0\u6548");
        for (Object raw : manifest.getJSONArray("refs"))
        {
            if (!(raw instanceof JSONObject item) || item.getJSONObject("canonicalRef") == null) continue;
            JSONObject target = new JSONObject();
            target.put("kind", item.getString("kind"));
            target.putAll(item.getJSONObject("canonicalRef"));
            permitted.add(canonicalTarget(target));
        }
        Map<String, AssistantRequests.ContextRef> normalized = new LinkedHashMap<>();
        for (AssistantRequests.ContextRef value : values)
        {
            if (value == null) throw new ServiceException("Tool\u76ee\u6807\u8303\u56f4\u65e0\u6548");
            String canonical = canonicalTarget(JSON.parseObject(JSON.toJSONString(value)));
            if (!permitted.contains(canonical) || normalized.putIfAbsent(canonical, value) != null)
                throw new ServiceException("Tool\u76ee\u6807\u4e0d\u5728\u5f53\u524d\u4e0a\u4e0b\u6587\u5185");
        }
        return normalized.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private String canonicalTarget(JSONObject value)
    {
        if (value == null) return "null";
        JSONObject ordered = new JSONObject();
        value.keySet().stream().sorted().forEach(key ->
        {
            Object field = value.get(key);
            if (field != null)
            {
                if (Set.of("workspaceId", "projectId", "episodeId", "scriptId").contains(key))
                    ordered.put(key, String.valueOf(field));
                else ordered.put(key, field);
            }
        });
        return ordered.toJSONString();
    }

    private boolean mediaScopeMatches(String toolCode, List<String> mediaTypes, String persistedMediaType)
    {
        if (StrUtil.isBlank(toolCode) || StrUtil.isBlank(persistedMediaType)) return false;
        return switch (toolCode)
        {
            case "creative.text.submit" -> mediaTypes.equals(List.of("TEXT"))
                    && "TEXT".equals(persistedMediaType);
            case "media.image.generate" -> mediaTypes.equals(List.of("IMAGE"))
                    && "IMAGE".equals(persistedMediaType);
            case "media.video.generate" -> mediaTypes.equals(List.of("VIDEO"))
                    && "VIDEO".equals(persistedMediaType);
            case "media.audio.generate" -> mediaTypes.equals(List.of("AUDIO"))
                    && "AUDIO".equals(persistedMediaType);
            case "media.music.generate" -> mediaTypes.equals(List.of("MUSIC"))
                    && "AUDIO".equals(persistedMediaType);
            default -> adapterMediaScopeMatches(toolCode, mediaTypes, persistedMediaType);
        };
    }

    private boolean adapterMediaScopeMatches(String toolCode, List<String> mediaTypes, String persistedMediaType)
    {
        RuntimeToolProvider.BillingMediaType declared;
        try { declared = billingMediaType(toolCode); }
        catch (RuntimeException unsupported) { return false; }
        if (declared == null) return false;
        String stored = persistedMediaType(toolCode);
        return List.of(declared.name()).equals(mediaTypes) && stored.equals(persistedMediaType);
    }

    private void requirePrepared(RuntimeToolPreparedInvocation prepared)
    {
        if (prepared == null || prepared.getDescriptor() == null || prepared.getRuntimeStepId() == null
                || StrUtil.isBlank(prepared.getPreparedPayloadJson())
                || StrUtil.isBlank(prepared.getPreparedPayloadDigest())
                || !SecureUtil.sha256(prepared.getPreparedPayloadJson()).equals(prepared.getPreparedPayloadDigest())
                || !Objects.equals(prepared.getToolCode(), prepared.getDescriptor().code())
                || !Objects.equals(prepared.getContractVersion(), prepared.getDescriptor().contractVersion())
                || !Objects.equals(prepared.getSchemaDigest(), prepared.getDescriptor().schemaDigest())
                || prepared.getRunLease() == null || prepared.getStepLease() == null
                || StrUtil.isBlank(prepared.getRunLease().getOwner())
                || !Objects.equals(prepared.getRunLease().getOwner(), prepared.getStepLease().getOwner())
                || prepared.getRunLease().getFencingToken() == null
                || prepared.getStepLease().getFencingToken() == null)
            throw new ServiceException("Tool\u51c6\u5907\u5feb\u7167\u65e0\u6548");
        if (prepared.getDescriptor().billable())
        {
            com.aid.runtime.tool.RuntimeToolBillingIntent intent = prepared.getBillingIntent();
            AssistantBudgetOperationScope scope = prepared.getOperationScope();
            if (intent == null || StrUtil.isBlank(intent.getMediaType())
                    || StrUtil.isBlank(intent.getNormalizedRequestJson())
                    || StrUtil.isBlank(intent.getRequestDigest())
                    || StrUtil.isBlank(intent.getQuoteId())
                    || StrUtil.isBlank(intent.getQuoteDigest())
                    || intent.getQuoteExpiresAt() == null || intent.getQuotedUpperBound() == null
                    || scope == null || !Objects.equals(scope.getOperationKind(), prepared.getOperationKind())
                    || !Objects.equals(scope.getToolCode(), prepared.getToolCode())
                    || StrUtil.isBlank(scope.getModelCode()) || scope.getQuotedUpperBound() == null
                    || scope.getQuotedUpperBound().signum() < 0
                    || !Objects.equals(scope.getQuotedUpperBound(), intent.getQuotedUpperBound()))
                throw new ServiceException("Tool\u8ba1\u8d39\u610f\u56fe\u65e0\u6548");
            List<String> capabilities = normalizeStrings(scope.getCapabilityCodes(),
                    "Tool\u80fd\u529b\u8303\u56f4\u65e0\u6548");
            List<String> mediaTypes = normalizeStrings(scope.getMediaTypes(),
                    "Tool\u5a92\u4f53\u8303\u56f4\u65e0\u6548");
            List<AssistantRequests.ContextRef> targets = normalizeTargets(
                    scope.getTargetRefs(), prepared.getContextManifestJson());
            if (capabilities.isEmpty() || mediaTypes.isEmpty()
                    || !Objects.equals(capabilities, scope.getCapabilityCodes())
                    || !Objects.equals(mediaTypes, scope.getMediaTypes())
                    || !Objects.equals(targets, scope.getTargetRefs())
                    || !mediaScopeMatches(prepared.getToolCode(), mediaTypes, intent.getMediaType()))
                throw new ServiceException("Tool\u8ba1\u8d39\u8303\u56f4\u65e0\u6548");
        }
        RuntimeToolInvocation identity = new RuntimeToolInvocation();
        identity.setRunId(prepared.getRunId());
        identity.setUserId(prepared.getUserId());
        identity.setRuntimeStepId(prepared.getRuntimeStepId());
        identity.setPlanRevision(prepared.getPlanRevision());
        identity.setStableStepId(prepared.getStableStepId());
        identity.setAttemptNo(prepared.getAttemptNo());
        identity.setOperationKind(prepared.getOperationKind());
        identity.setToolCode(prepared.getToolCode());
        identity.setContractVersion(prepared.getContractVersion());
        identity.setSchemaDigest(prepared.getSchemaDigest());
        identity.setArgumentsJson(prepared.getArgumentsJson());
        identity.setArgumentsDigest(prepared.getArgumentsDigest());
        identity.setContextManifestJson(prepared.getContextManifestJson());
        identity.setApprovalId(prepared.getApprovalId());
        identity.setApprovedScopeDigest(prepared.getApprovedScopeDigest());
        identity.setOperationScope(prepared.getOperationScope());
        identity.setIdempotencyKey(prepared.getIdempotencyKey());
        identity.setLease(prepared.getStepLease());
        requireInvocation(identity);
    }

    private void requireBudgetIdentity(RuntimeToolPreparedInvocation prepared, BillingBudgetContext budget)
    {
        com.aid.runtime.tool.RuntimeToolBillingIntent intent = prepared.getBillingIntent();
        if (budget == null || intent == null || !budget.isRunAuthorizationMode()
                || !Objects.equals(budget.getRuntimeRunId(), prepared.getRunId())
                || !Objects.equals(budget.getRuntimeStepId(), prepared.getRuntimeStepId())
                || !Objects.equals(budget.getStableStepKey(), prepared.getStableStepId())
                || !Objects.equals(budget.getAttemptNo(), prepared.getAttemptNo())
                || !Objects.equals(budget.getLogicalCallKey(), prepared.getIdempotencyKey())
                || !Objects.equals(budget.getRequestDigest(), intent.getRequestDigest())
                || !Objects.equals(budget.getQuoteId(), intent.getQuoteId())
                || !Objects.equals(budget.getQuoteDigest(), intent.getQuoteDigest())
                || !Objects.equals(budget.getQuoteExpiresAt(), intent.getQuoteExpiresAt()))
            throw new ServiceException("Tool\u9884\u7b97\u8eab\u4efd\u65e0\u6548");
    }

    private void registerBuiltin(Map<String, Descriptor> values, String code, String name,
                                 String description, String outputSchema)
    {
        String contractVersion = "1.0.0";
        String digest = SecureUtil.sha256(TEXT_INPUT_SCHEMA + "\n" + outputSchema);
        values.put(code, new Descriptor(code, contractVersion, digest, name, description,
                TEXT_INPUT_SCHEMA, outputSchema, "PURE", false, "REQUEST_DIGEST",
                false, "REPLAY_SAFE", List.of("TEXT")));
    }

    private void validateDescriptor(Descriptor descriptor)
    {
        if (descriptor == null || StrUtil.isBlank(descriptor.code())
                || StrUtil.isBlank(descriptor.contractVersion()) || StrUtil.isBlank(descriptor.schemaDigest())
                || StrUtil.isBlank(descriptor.inputSchema()) || StrUtil.isBlank(descriptor.outputSchema())
                || descriptor.schemaDigest().length() != 64
                || !SIDE_EFFECT_LEVELS.contains(descriptor.sideEffectLevel())
                || !descriptor.schemaDigest().equals(SecureUtil.sha256(
                descriptor.inputSchema() + "\n" + descriptor.outputSchema())))
        {
            throw new IllegalStateException("Invalid Runtime Tool descriptor");
        }
    }

    /** Shared strict validation used by the loop parser before it persists an invocation. */
    public void validateArguments(String toolCode, String contractVersion, JSONObject arguments)
    {
        Descriptor descriptor = require(toolCode);
        if (!descriptor.contractVersion().equals(contractVersion))
            throw new ServiceException("Tool\u5951\u7ea6\u51b2\u7a81");
        validate(inputSchemas.get(toolCode), arguments, "Tool\u53c2\u6570\u4e0d\u7b26\u5408\u5951\u7ea6");
    }

    /** Decision-parser validation that classifies only a trusted schema's definite mismatch. */
    public void validateDecisionArguments(String toolCode, String contractVersion, JSONObject arguments)
    {
        Descriptor descriptor = require(toolCode);
        if (!descriptor.contractVersion().equals(contractVersion))
            throw new ServiceException("Tool\u5951\u7ea6\u51b2\u7a81");
        JSONSchema schema = inputSchemas.get(toolCode);
        if (schema == null) throw new ServiceException("Tool\u5951\u7ea6\u4e0d\u53ef\u7528");
        ValidateResult result = schema.validate(arguments);
        if (!result.isSuccess())
        {
            com.aid.runtime.tool.RuntimeInputDiagnostics.record(descriptor.code(), descriptor.contractVersion(),
                    descriptor.schemaDigest(), JSON.parseObject(descriptor.inputSchema()), arguments);
            throw new AssistantDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.argumentSchemaMismatch(descriptor.schemaDigest()));
        }
    }

    private JSONSchema compileSchema(String schemaJson, String code)
    {
        try
        {
            JSONObject schema = JSON.parseObject(schemaJson);
            if (schema == null) throw new IllegalArgumentException();
            return JSONSchema.of(schema);
        }
        catch (RuntimeException invalid)
        {
            throw new IllegalStateException("Invalid Runtime Tool schema: " + code, invalid);
        }
    }

    private void validate(JSONSchema schema, Object value, String message)
    {
        if (schema == null) throw new ServiceException("Tool\u5951\u7ea6\u4e0d\u53ef\u7528");
        ValidateResult result = schema.validate(value);
        if (!result.isSuccess()) throw new ServiceException(message);
    }

    public record Descriptor(String code, String contractVersion, String schemaDigest,
                             String name, String description, String inputSchema,
                             String outputSchema, String sideEffectLevel, boolean billable,
                             String idempotencyLevel, boolean cancellable, String recoveryPolicy,
                             List<String> requiredCapabilities)
    {
    }

    public record PinnedTool(String code, String contractVersion, String schemaDigest)
    {
    }

    public record BudgetOption(Descriptor descriptor, RuntimeToolBudgetOffer offer)
    {
    }
    /** Narrow quote capability: an untrusted HTTP body can never supply this server snapshot. */
    public void validateQuoteReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest request) {
        if (request == null || request.runId() == null || request.userId() == null
                || request.runtimeStepId() == null || request.attemptNo() == null || request.attemptNo() < 1
                || StrUtil.isBlank(request.idempotencyKey()) || StrUtil.isBlank(request.contextManifestJson())
                || request.argumentsJson() == null || request.preparedPayloadJson() == null
                || !Objects.equals(SecureUtil.sha256(request.argumentsJson()), request.argumentsDigest())
                || !Objects.equals(SecureUtil.sha256(request.preparedPayloadJson()), request.preparedPayloadDigest()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        Descriptor descriptor = require(request.toolCode());
        if (!descriptor.billable() || !Objects.equals(descriptor.contractVersion(), request.contractVersion())
                || !Objects.equals(descriptor.schemaDigest(), request.schemaDigest()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        validatePreparationArguments(inputSchemas.get(request.toolCode()), JSON.parseObject(request.argumentsJson()));
        RuntimeToolProvider provider = providers.get(request.toolCode());
        if (provider == null) throw new ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
        provider.validateQuoteReadOnly(request);
    }

    public RuntimeToolPreparation repricePreparedQuote(com.aid.runtime.tool.RuntimeToolQuoteRequest request) {
        validateQuoteReadOnly(request);
        RuntimeToolPreparation value = providers.get(request.toolCode()).repricePreparedQuote(request);
        var before = JSON.parseObject(request.billingIntentJson(), com.aid.runtime.tool.RuntimeToolBillingIntent.class);
        var originalScope = JSON.parseObject(request.operationScopeJson(), AssistantBudgetOperationScope.class);
        if (value == null || value.getBillingIntent() == null || value.getOperationScope() == null
                || before == null || originalScope == null || value.getPreparedPayloadJson() == null
                || !Objects.equals(before.getRequestDigest(), value.getBillingIntent().getRequestDigest())
                || !Objects.equals(before.getNormalizedRequestJson(), value.getBillingIntent().getNormalizedRequestJson())
                || !Objects.equals(before.getMediaType(), value.getBillingIntent().getMediaType())
                || value.getBillingIntent().getQuoteId() == null
                || Objects.equals(before.getQuoteId(), value.getBillingIntent().getQuoteId())
                || value.getBillingIntent().getQuoteDigest() == null
                || !value.getBillingIntent().getQuoteDigest().matches("[0-9a-f]{64}")
                || value.getBillingIntent().getQuoteExpiresAt() == null
                || !value.getBillingIntent().getQuoteExpiresAt().after(new java.util.Date())
                || value.getBillingIntent().getQuotedUpperBound() == null
                || value.getBillingIntent().getQuotedUpperBound().signum() < 0)
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        JSONObject oldScope = JSON.parseObject(request.operationScopeJson());
        JSONObject newScope = JSON.parseObject(JSON.toJSONString(value.getOperationScope()));
        oldScope.remove("quotedUpperBound");newScope.remove("quotedUpperBound");
        if (!oldScope.equals(newScope) || value.getOperationScope().getQuotedUpperBound()==null
                || value.getOperationScope().getQuotedUpperBound().compareTo(value.getBillingIntent().getQuotedUpperBound()) != 0)
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        JSONObject oldPayload = JSON.parseObject(request.preparedPayloadJson());
        JSONObject newPayload = JSON.parseObject(value.getPreparedPayloadJson());
        if(request.referenceRevalidationJson()!=null) {
            if(!Objects.equals(JSON.parseObject(request.referenceRevalidationJson()),newPayload.getJSONObject("serverResourceEvidence")))
                throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
            newPayload.remove("serverResourceEvidence");oldPayload.remove("serverResourceEvidence");
        }
        for (String key : List.of("quoteId", "quoteDigest")) {oldPayload.remove(key);newPayload.remove(key);}
        if (!oldPayload.equals(newPayload)) throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        return value;
    }


    public void validatePreparedReferencesReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest request) {
        if (request == null || request.runId() == null || request.userId() == null
                || request.runtimeStepId() == null || request.attemptNo() == null || request.attemptNo() < 1
                || StrUtil.isBlank(request.idempotencyKey()) || StrUtil.isBlank(request.contextManifestJson())
                || request.argumentsJson() == null || request.preparedPayloadJson() == null
                || !Objects.equals(SecureUtil.sha256(request.argumentsJson()), request.argumentsDigest())
                || !Objects.equals(SecureUtil.sha256(request.preparedPayloadJson()), request.preparedPayloadDigest()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        Descriptor descriptor = require(request.toolCode());
        if (!descriptor.billable() || !Objects.equals(descriptor.contractVersion(), request.contractVersion())
                || !Objects.equals(descriptor.schemaDigest(), request.schemaDigest()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        validatePreparationArguments(inputSchemas.get(request.toolCode()), JSON.parseObject(request.argumentsJson()));
        RuntimeToolProvider provider = providers.get(request.toolCode());
        if (provider == null) throw new ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
        provider.validatePreparedReferencesReadOnly(request);
    }
}
