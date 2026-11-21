package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.service.AssistantQuestionControlPolicy;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.assistant.execution.AssistantPlanDecisionRejectedException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native function-call adapter for new Assistant runs. */
@Component
@RequiredArgsConstructor
public class NativeAssistantLoopExecutor implements AssistantLoopExecutor
{
    public static final String PROTOCOL_VERSION = RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1;
    public static final String RESPONSE_MODE = RuntimeAgentRequests.NATIVE_TOOL_STREAM;
    static final String PLATFORM_PAUSE_OPTION_ID = AssistantQuestionControlPolicy.PLATFORM_PAUSE_OPTION_ID;
    static final String PLATFORM_PAUSE_LABEL = AssistantQuestionControlPolicy.PLATFORM_PAUSE_LABEL;
    static final String PLATFORM_PAUSE_DESCRIPTION = AssistantQuestionControlPolicy.PLATFORM_PAUSE_DESCRIPTION;
    private static final String TOOL_SCHEMA = "assistant-native-tools/v1";
    private static final String BINDING_SCHEMA_V2 = "assistant-native-bindings/v2";
    private static final String BINDING_SCHEMA_V3 = "assistant-native-bindings/v3";
    private static final NativeAssistantControlContractV1 CONTROL_V1 = new NativeAssistantControlContractV1();
    private static final int MAX_BINDINGS = 64;
    private static final String CAPABILITY_SCHEMA = "capabilityInputSchema";
    private static final int MAX_SAFE_READ_INVOCATIONS = 1_000;
    private static final int MAX_RESULT_CHARS = 100_000;
    private static final Set<String> RETRYABLE_TERMINAL = Set.of(
            "FAILED", "FAILED_PRETASK", "CANCELLED_PRETASK");
    private static final Map<String, String> CONTROL_ACTIONS = Map.of(
            "assistant_answer", "CHAT",
            "assistant_propose_plan", "PLAN",
            "assistant_ask_user", "ASK",
            "assistant_checkpoint", "CHECKPOINT",
            "assistant_complete", "COMPLETE");
    private static final Set<String> BINDING_ENVELOPE_FIELDS = Set.of(
            "schemaVersion", "protocolVersion", "runId", "observedRunRevision",
            "observedPlanRevision", "tools");
    private static final Set<String> BINDING_FIELDS = Set.of("name", "action", "payloadTemplate");
    private static final Set<String> TOOL_PAYLOAD_FIELDS = Set.of(
            "invocationId", "stableStepId", "attemptNo", "toolCode", "contractVersion",
            "operationKind", "inputArtifactRefs", "expectedContextRevisions");
    private static final Set<String> SKILL_PAYLOAD_FIELDS = Set.of(
            "invocationId", "stableStepId", "attemptNo", "skillCode", "skillVersionId",
            "packageDigest", "inputSchemaDigest");

    private final DefaultAssistantLoopExecutor decisionValidator;
    private final RuntimeToolRegistry toolRegistry;
    @org.springframework.beans.factory.annotation.Value("${aid.assistant.native-plan.read-max-attempts:3}")
    private int hostReadMaxAttempts = 3;
    @org.springframework.beans.factory.annotation.Value("${aid.assistant.native-plan.operation-max-attempts:3}")
    private int hostOperationMaxAttempts = 3;
    @jakarta.annotation.Resource
    private AssistantRemainingPlanPolicy remainingPlanPolicy;

    @jakarta.annotation.Resource
    private com.aid.context.ContextContractPublisher contextContracts;
    @jakarta.annotation.Resource
    private com.aid.runtime.mapper.AidRuntimeRunMapper contextRuns;
    @jakarta.annotation.Resource
    private com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionService targetWriteCompletion;

    @Override
    public String supportedProtocolVersion()
    {
        return PROTOCOL_VERSION;
    }

    @Override public boolean supportsContextPreparation() { return true; }

    @Override
    public AssistantLoopSubmission prepareIteration(AssistantLoopExecutionContext context)
    {
        requireContext(context);
        JSONObject request = object(context.getRequestJson(), "原生循环输入无效");
        AssistantMainInputContract.validate(request, context.getBinding().getInputSchemaJson());
        NativeAssistantContextProjection.validate(request);
        JSONObject run = request.getJSONObject("run");
        JSONObject turn = request.getJSONObject("turn");
        if (run == null || turn == null
                || !Objects.equals(String.valueOf(context.getRunId()), run.getString("runId"))
                || !Objects.equals(context.getStateRevision(), run.getLong("stateRevision"))
                || !Objects.equals(context.getPlanRevision(), run.getLong("planRevision"))
                || !Objects.equals(context.getIteration(), turn.getInteger("iteration")))
            fail("原生循环版本冲突");

        NativeCatalog catalog = nativeCatalog(request, context);
        Set<com.aid.context.ExecutionCatalogDemand.Binding> executable = new java.util.HashSet<>();
        for (Object raw : catalog.bindings().getJSONArray("tools")) {
            JSONObject nativeBinding = (JSONObject) raw;
            String action = nativeBinding.getString("action");
            if (!"TOOL".equals(action) && !"SKILL".equals(action)) continue;
            JSONObject payload = nativeBinding.getJSONObject("payloadTemplate");
            executable.add(new com.aid.context.ExecutionCatalogDemand.Binding(action,
                    payload.getString("TOOL".equals(action) ? "toolCode" : "skillCode"),
                    payload.getString("TOOL".equals(action) ? "contractVersion" : "skillVersionId")));
        }
        JSONObject providerRequest = NativeAssistantContextProjection.providerViewAfterNativeTools(
                request, catalog.planningInstruction() != null, executable);
        NativeToolContractReceiptProjection.context(providerRequest, catalog.bindings());
        if (NativeAssistantBindingPolicy.methodResults(NativeAssistantBindingPolicy.schemaVersion(context.getBinding())))
            NativeMethodResultInputContract.project(providerRequest);
        if (NativeAssistantBindingPolicy.strictInputs(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))) {
            providerRequest = com.aid.context.ExecutionCatalogDemand.project(providerRequest,
                    executable, catalog.planningInstruction() != null
                            && !com.aid.context.ContextPreparationExecutionGate.pending(request));
        }
        String executableContextReader = NativeContextDemandProjection.executableReaderCode(request, catalog.bindings());
        if (executableContextReader != null) {
            var owner = contextRuns.selectById(context.getRunId());
            if (owner == null || owner.getUserId() == null || !"0".equals(owner.getDelFlag())
                    || !Objects.equals(context.getRunId(), owner.getId())
                    || !Objects.equals(context.getBinding().getAgentVersionId(), owner.getVersionId())
                    || !Objects.equals(context.getBinding().getPackageDigest(), owner.getVersionDigest()))
                fail("上下文运行身份失配");
            Set<String> completedContractReads = com.aid.context.ContextContractReadCoverage.completedReferences(
                    request.getJSONArray("priorResults"), String.valueOf(context.getRunId()));
            providerRequest = contextContracts.publish(new com.aid.context.ConversationContextMemoryStore.Key(
                            owner.getUserId(), "ASSISTANT_RUN", String.valueOf(owner.getId())),
                    NativeContextDemandProjection.materialize(providerRequest), true,
                    entry -> catalog.planningInstruction() != null
                            && NativeContextDemandProjection.retainInline(request, entry, completedContractReads),
                    executableContextReader);
        }
        AssistantLoopSubmission submission = new AssistantLoopSubmission();
        boolean plainAnswerAvailable = catalog.bindings().getJSONArray("tools").stream()
                .anyMatch(raw -> raw instanceof JSONObject item && "assistant_answer".equals(item.getString("name")));
        submission.setProtocolVersion(PROTOCOL_VERSION);
        submission.setOperationKind("ASSISTANT_NATIVE_TURN");
        boolean flowDesign = catalog.bindings().containsKey(FlowScreenplayDecisionContract.DESIGN_DELIVERY);
        submission.setSystemPrompt(flowDesign ? FlowScreenplayDecisionContract.designInstruction()
                : (com.aid.context.ContextPreparationExecutionGate.pending(request)
                ? com.aid.context.ContextPreparationExecutionGate.SYSTEM_INSTRUCTION
                : FlowScreenplayDecisionContract.applies(request) ? FlowScreenplayDecisionContract.instruction()
                : context.getBinding().getInstruction())
                + (com.aid.context.ContextPreparationExecutionGate.pending(request)
                ? "\n\n当前处于历史上下文准备阶段，只能调用本轮提供的准备校验函数。必须保留用户要求和原文引用，不能通过普通回复、提问或业务计划跳过准备。原始用户请求在准备完成后继续执行。"
                :
                "\n\n每轮必须通过本轮目录中的原生函数提交明确决策。"
                + (plainAnswerAvailable
                    ? "普通回答使用assistant_answer；它只用于已经完成的问答，不得用准备读取、稍后执行或继续创作的承诺代替工具调用。"
                    : "根据本轮开放的决策推进已获批的计划。只有全部必需操作成功后，才可使用assistant_complete交付。进度正文可伴随实际决策，不另调用普通问答或检查点。")
                + "需要读取时调用实际读取工具，需要执行时提交计划或调用已批准工具；"
                + "进度说明、提问和最终回复使用用户最新请求的语言；用户明确指定输出语言时按其要求，"
                + "工具编码和原始引用保持原样，不因工具描述是英文就把说明切换为英文。"
                + "批准计划的最终完成必须使用assistant_complete并提供真实证据。正文只作说明，不能结束运行。")
                + (catalog.planningInstruction() == null ? "" : "\n\n" + catalog.planningInstruction())
                + (com.aid.context.ContextPreparationExecutionGate.pending(request)
                ? "" : planningPrerequisiteInstruction(request)
                + NativeAssistantContinuationPolicy.instruction(request,
                        NativeAssistantBindingPolicy.schemaVersion(context.getBinding()), plainAnswerAvailable)));
        if (!com.aid.context.ContextPreparationExecutionGate.pending(request))
            submission.setSystemPrompt(submission.getSystemPrompt() + com.aid.skill.service.SkillContractExamples.instruction(
                    context.getBinding().getInputSchemaJson(), context.getBinding().getOutputSchemaJson()));
        submission.setModelConfigJson(context.getBinding().getModelConfigJson());
        submission.setInputSchemaJson(context.getBinding().getInputSchemaJson());
        submission.setRequestJson(AssistantMainInputContract.validatedJson(providerRequest,
                context.getBinding().getInputSchemaJson()));
        NativeSubmittedAnswerReceipt.enable(catalog.bindings(), providerRequest);
        submission.setOutputSchemaJson(null);
        submission.setNativeToolsJson(catalog.tools().toJSONString(JSONWriter.Feature.WriteMapNullValue));
        submission.setNativeToolBindingsJson(catalog.bindings().toJSONString(JSONWriter.Feature.WriteMapNullValue));
        return submission;
    }

    @Override
    public AssistantLoopDecision parseDecision(AssistantLoopModelResult result,
                                                AssistantLoopBinding binding)
    {
        requireBinding(binding);
        if (result == null || result.getMediaTaskId() == null || result.getRunId() == null
                || result.getObservedRunRevision() == null || result.getObservedPlanRevision() == null
                || !"SUCCEEDED".equals(result.getStatus()))
            fail("原生模型结果无效");
        String visible = StrUtil.nullToEmpty(result.getResultJson());
        if (visible.length() > MAX_RESULT_CHARS) fail("助手正文过长");
        if (StrUtil.isNotBlank(result.getResultDigest())
                && !Objects.equals(result.getResultDigest(), SecureUtil.sha256(visible)))
            fail("原生结果摘要冲突");
        if (StrUtil.isNotBlank(result.getNativeToolBindingsJson())
                || NativeAssistantBindingPolicy.strictInputs(NativeAssistantBindingPolicy.schemaVersion(binding)))
            NativeAssistantBindingPolicy.requireArchivedCompatible(binding, result.getNativeToolBindingsJson());

        MediaTextGenerateRequest.TextMessageItem toolMessage = result.getToolMessage();
        NativeAssistantVisibleTextPolicy.Inspection presentation =
                NativeAssistantVisibleTextPolicy.inspect(visible);
        if (toolMessage == null || toolMessage.getToolCalls() == null
                || toolMessage.getToolCalls().isEmpty())
        {
            if (presentation.blocked())
            {
                TrustedNativeBindings trusted = requireNativeBindings(result.getNativeToolBindingsJson(), result);
                NativeRejectedSourceIdentity source = new NativeRejectedSourceIdentity(
                        result.getRunId(), result.getMediaTaskId(), result.getObservedRunRevision(),
                        result.getObservedPlanRevision(),
                        SecureUtil.sha256("assistant-text-control-source/v1\n" + visible),
                        trusted.digest(), SecureUtil.sha256("assistant-text-control-contract/v1"));
                throw new NativeDecisionValidationRejectedException(
                        AssistantDecisionValidationFeedback.nativeControlText(trusted.digest()), source);
            }
            // Preparation turns require structured evidence. Ordinary successfully completed
            // text turns can only create a CHAT decision and cannot execute or complete a tool.
            boolean flowScreenplay = false;
            boolean flowDesign = false;
            if (StrUtil.isNotBlank(result.getNativeToolBindingsJson()))
            {
                TrustedNativeBindings trusted = requireNativeBindings(result.getNativeToolBindingsJson(), result);
                if (com.aid.context.ContextPreparationExecutionGate.requiresExplicitDecision(trusted.values().values())
                        || ScreenwriterCanvasDeliveryPolicy.requiresExplicitDecision(binding.getModuleCode(), trusted.values().values()))
                {
                    NativeRejectedSourceIdentity source = new NativeRejectedSourceIdentity(
                            result.getRunId(), result.getMediaTaskId(), result.getObservedRunRevision(),
                            result.getObservedPlanRevision(),
                            SecureUtil.sha256("assistant-explicit-decision-source/v1\n" + visible),
                            trusted.digest(), SecureUtil.sha256("assistant-explicit-decision/v1"));
                    throw new NativeDecisionValidationRejectedException(
                            AssistantDecisionValidationFeedback.nativeDecisionCallRequired(trusted.digest()), source);
                }
                JSONObject answer = trusted.values().get("assistant_answer");
                flowScreenplay = answer != null
                        && FlowScreenplayDecisionContract.bound(answer.getJSONObject("payloadTemplate"));
                flowDesign = trusted.envelope().containsKey(FlowScreenplayDecisionContract.DESIGN_DELIVERY);
            }
            if (StrUtil.isBlank(visible)) throw new AssistantEmptyReplyException();
            validateConfiguredOutput(binding, visible, null, null);
            JSONObject payload = flowDesign
                    ? FlowScreenplayDecisionContract.canonical(JSONObject.of("outcome", "DESIGN"), visible)
                    : flowScreenplay ? FlowScreenplayDecisionContract.completedPlainDraft(visible) : null;
            if (payload == null) payload = JSONObject.of("message", JSONObject.of("format", "MARKDOWN", "content", visible),
                    "suggestedNextActions", new JSONArray());
            JSONObject envelope = envelope(result, decisionId(result, "content", visible), "CHAT", payload);
            AssistantLoopDecision decision = decisionValidator.validateEnvelope(envelope, PROTOCOL_VERSION);
            decision.setVisibleText(visible);
            return decision;
        }
        // Native calls retain their original archive identity; only their presentation text is filtered.
        visible = presentation.visible();
        if (!"assistant".equals(toolMessage.getRole())
                || toolMessage.getToolCalls().size() > NativeAssistantToolBatch.MAX_CALLS)
            fail("原生工具调用数量无效");
        if (toolMessage.getToolCalls().size() > 1)
            return parseSafeReadBatch(result, toolMessage, visible, binding);
        MediaTextGenerateRequest.TextToolCall call = toolMessage.getToolCalls().get(0);
        if (!validCall(call))
            fail("原生工具调用无效");
        NativeAssistantBindingPolicy.requireArchivedCompatible(binding, result.getNativeToolBindingsJson());
        TrustedNativeBindings trustedBindings = requireNativeBindings(
                result.getNativeToolBindingsJson(), result);
        if (!trustedBindings.values().containsKey(call.getName()))
        {
            // Do not infer an alias, unwrap arguments, or execute an unexposed function.
            // A locked reparse of this exact archive may claim the existing one-correction slot.
            String absentNameDigest = SecureUtil.sha256("UNEXPOSED_NATIVE_FUNCTION\n"
                    + call.getName() + "\n" + trustedBindings.digest());
            NativeRejectedSourceIdentity source = new NativeRejectedSourceIdentity(
                    result.getRunId(), result.getMediaTaskId(), result.getObservedRunRevision(),
                    result.getObservedPlanRevision(), toolMessageDigest(toolMessage),
                    trustedBindings.digest(), absentNameDigest);
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.nativeFunctionNameNotExposed(
                            trustedBindings.digest()), source);
        }
        VerifiedNativeCall verifiedCall = requireNativeCall(trustedBindings, call);
        NativeRejectedSourceIdentity sourceIdentity = rejectedSourceIdentity(
                result, toolMessage, trustedBindings, verifiedCall);
        JSONObject arguments = decisionArguments(call.getArguments(), verifiedCall.action(),
                verifiedCall.trustedContractDigest(), sourceIdentity);
        JSONObject bindingValue = verifiedCall.binding();
        String action = verifiedCall.action();
        JSONObject template = bindingValue.getJSONObject("payloadTemplate");
        if ("CHAT".equals(action) && StrUtil.isBlank(visible)
                && NativeMessageInputContract.applies(trustedBindings.envelope().getString("schemaVersion"), action)
                && arguments.get("message") instanceof String answer && answer.length() <= 100000
                && StrUtil.isBlank(answer)) {
            throw new AssistantEmptyReplyException();
        }
        if ("TOOL".equals(action))
            arguments = canonicalToolArguments(arguments, template,
                    trustedBindings.envelope().getString("schemaVersion"), verifiedCall.trustedContractDigest(), sourceIdentity);
        boolean visibleAsk = NativeAskVisibleContextContract.applies(trustedBindings.envelope().getString("schemaVersion"), action);
        if (visibleAsk) {
            CONTROL_V1.requireAskSecurity(arguments);
            if (!NativeAskVisibleContextContract.inputMatches(arguments, trustedBindings.envelope().getString("schemaVersion")))
                throw new NativeDecisionValidationRejectedException(
                        AssistantDecisionValidationFeedback.nativeAskSchemaMismatch(NativeAssistantBindingPolicy.askInputSchemaDigest(trustedBindings.envelope().getString("schemaVersion"))), sourceIdentity);
        }
        JSONObject controlArguments;
        validateConfiguredOutput(binding, visible, call, arguments);
        try {
        controlArguments = "CHAT".equals(action) && FlowScreenplayDecisionContract.bound(template)
                ? FlowScreenplayDecisionContract.canonical(arguments, visible)
                : "PLAN".equals(action) && NativeAssistantBindingPolicy.hostInputs(trustedBindings.envelope().getString("schemaVersion"))
                ? NativeHostPlanInputContract.canonicalArguments(arguments, template.getJSONObject(NativeHostPlanInputContract.FIELD), trustedBindings.envelope().getString("schemaVersion"))
                : visibleAsk ? NativeAskVisibleContextContract.canonicalArguments(arguments, trustedBindings.envelope().getString("schemaVersion"))
                : "COMPLETE".equals(action) && NativeAssistantBindingPolicy.V11.equals(trustedBindings.envelope().getString("schemaVersion"))
                ? NativeHostCompletionInputContract.canonicalArguments(arguments, template.getJSONObject(NativeHostCompletionInputContract.FIELD))
                : NativeCompletionInputContract.applies(
                trustedBindings.envelope().getString("schemaVersion"), action)
                ? NativeCompletionInputContract.canonicalArguments(arguments,
                    NativeAssistantBindingPolicy.methodResults(trustedBindings.envelope().getString("schemaVersion"))) : NativeMessageInputContract.applies(
                trustedBindings.envelope().getString("schemaVersion"), action)
                ? NativeMessageInputContract.canonicalArguments(arguments) : arguments;
        } catch (RuntimeException rejected) {
            String version = trustedBindings.envelope().getString("schemaVersion");
            if ("PLAN".equals(action) && NativeAssistantBindingPolicy.hostInputs(version)) {
                JSONObject planSchema = NativeHostPlanInputContract.schema(version,template.getJSONObject(NativeHostPlanInputContract.FIELD));
                if (!JSONSchema.of(planSchema).validate(arguments).isSuccess())
                    com.aid.runtime.tool.RuntimeInputDiagnostics.record(action, version,
                            verifiedCall.trustedContractDigest(), planSchema, arguments);
                else com.aid.runtime.tool.RuntimeInputDiagnostics.recordStage(action, version,
                        verifiedCall.trustedContractDigest(), "HOST_CANONICALIZATION", "BINDING_OR_PLAN_CONSTRAINT_REJECTED");
            } else com.aid.runtime.tool.RuntimeInputDiagnostics.recordStage(action, version,
                    verifiedCall.trustedContractDigest(), "CONTROL_CANONICALIZATION", "CONTRACT_REJECTED");
            throw rejected;
        }
        JSONObject payload;
        if ("CHAT".equals(action))
        {
            payload = controlArguments;
        }
        else if (CONTROL_ACTIONS.containsValue(action))
        {
            if ("ASK".equals(action) && NativeAssistantBindingPolicy.strictInputs(trustedBindings.envelope().getString("schemaVersion")))
            {
                CONTROL_V1.requireAskSecurity(arguments);
                if (!CONTROL_V1.askInputMatches(controlArguments))
                    throw NativeDecisionValidationAdapter.askSchemaMismatch(sourceIdentity);
            }
            JSONObject canonicalBinding = bindingValue;
            if ("PLAN".equals(action) && NativeAssistantBindingPolicy.hostInputs(trustedBindings.envelope().getString("schemaVersion"))) {
                canonicalBinding = new JSONObject(bindingValue);
                canonicalBinding.put("payloadTemplate", JSONObject.of("parentRevision", template.get("parentRevision")));
            }
            if ("COMPLETE".equals(action) && NativeAssistantBindingPolicy.V11.equals(trustedBindings.envelope().getString("schemaVersion"))) {
                canonicalBinding = new JSONObject(bindingValue);
                canonicalBinding.put("payloadTemplate", null);
            }
            payload = CONTROL_V1.normalizeControlArguments(action, controlArguments, canonicalBinding, result);
        }
        else if ("TOOL".equals(action))
        {
            payload = executionToolPayload(template);
            payload.put("arguments", arguments);
        }
        else if ("SKILL".equals(action))
        {
            if (template == null) fail("原生Skill绑定无效");
            payload = new JSONObject();
            for (String field : List.of("invocationId", "stableStepId", "attemptNo", "skillCode",
                    "skillVersionId", "packageDigest")) payload.put(field, template.get(field));
            payload.put("input", arguments);
        }
        else
        {
            fail("原生工具动作无效");
            return null;
        }
        if (visibleAsk) visible = NativeAskVisibleContextContract.message(arguments);
        if ("PLAN".equals(action))
            NativeDecisionValidationAdapter.requirePlanArrays(payload,
                    verifiedCall.trustedContractDigest(), sourceIdentity);
        if ("PLAN".equals(action) && Set.of(NativeAssistantBindingPolicy.V8, NativeAssistantBindingPolicy.V9, NativeAssistantBindingPolicy.V10, NativeAssistantBindingPolicy.V11)
                .contains(trustedBindings.envelope().getString("schemaVersion")))
            NativeDecisionValidationAdapter.requireCurrentPlanDependencies(payload,
                    verifiedCall.trustedContractDigest(), sourceIdentity);
        String identity = call.getId() + "\n" + call.getName() + "\n" + arguments.toJSONString()
                + "\n" + visible;
        JSONObject envelope = envelope(result, decisionId(result, "call", identity), action, payload);
        AssistantLoopDecision decision;
        try
        {
            decision = CONTROL_ACTIONS.containsValue(action) && !"CHAT".equals(action)
                    ? CONTROL_V1.validateEnvelope(envelope, PROTOCOL_VERSION,
                        NativeAssistantBindingPolicy.methodResults(trustedBindings.envelope().getString("schemaVersion")))
                    : decisionValidator.validateEnvelope(envelope, PROTOCOL_VERSION);
            if ("COMPLETE".equals(action))
                AssistantCompletionEvidenceReferences.requireDeclared(payload, verifiedCall.trustedContractDigest(),
                        NativeAssistantBindingPolicy.methodResults(trustedBindings.envelope().getString("schemaVersion")));
        }
        catch (AssistantDecisionValidationRejectedException rejected)
        {
            throw NativeDecisionValidationAdapter.wrap(rejected, sourceIdentity);
        }
        if ("PLAN".equals(action))
        {
            CONTROL_V1.requireNativePlan(payload);
            if (remainingPlanPolicy.repeatsCompletedOperation(result.getRunId(),
                    result.getObservedPlanRevision(), payload))
                throw new NativeDecisionValidationRejectedException(
                        AssistantDecisionValidationFeedback.planRepeatsCompletedOperation(
                                verifiedCall.trustedContractDigest()), sourceIdentity);
            NativeDecisionValidationAdapter.requireArtifactOutputs(payload,toolRegistry::planningOutputReferenceKind,
                    verifiedCall.trustedContractDigest(),sourceIdentity);
            NativeDecisionValidationAdapter.requireBillableOutputs(payload,
                    code -> toolRegistry.require(code).billable(),
                    verifiedCall.trustedContractDigest(), sourceIdentity);
            if (NativeAssistantBindingPolicy.methodResults(trustedBindings.envelope().getString("schemaVersion")))
                NativeDecisionValidationAdapter.requirePlanCompletionEvidence(payload,
                        toolRegistry::planningOutputReferenceKind, targetWriteCompletion.supportedToolCodes(),
                        verifiedCall.trustedContractDigest(), sourceIdentity);
            try
            {
                NativeAssistantPlanReadPolicy.requireDraftPlanPrerequisites(payload);
            }
            catch (AssistantPlanDecisionRejectedException rejected)
            {
                if (rejected.getReason() != AssistantPlanDecisionRejectedException.Reason.NATIVE_PLAN_SAFE_READ_REQUIRED
                        && rejected.getReason() != AssistantPlanDecisionRejectedException.Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_REQUIRED)
                    throw rejected;
                // No decision or operation has been persisted. The existing locked Native claim
                // reparses this complete archive and enforces settlement, full-graph and one-slot guards.
                throw new NativeDecisionValidationRejectedException(
                        AssistantDecisionValidationFeedback.planReadPrerequisitesRequired(
                                NativeAssistantPlanReadPolicy.contractDigest()), sourceIdentity);
            }
        }
        decision.setVisibleText(StrUtil.isBlank(visible) ? null : visible);
        return decision;
    }

    private static void validateConfiguredOutput(AssistantLoopBinding binding, String visible,
            MediaTextGenerateRequest.TextToolCall call, JSONObject arguments)
    {
        String configured = binding.getOutputSchemaJson();
        if (StrUtil.isBlank(configured)) return;
        JSONObject schema = JSON.parseObject(configured);
        JSONObject properties = schema == null ? null : schema.getJSONObject("properties");
        if (properties == null || !properties.containsKey("visibleText") || !properties.containsKey("toolCall")) return;
        JSONObject tool = call == null ? null : JSONObject.of(
                "id", call.getId(), "name", call.getName(), "arguments", arguments);
        JSONObject actual = JSONObject.of("visibleText", StrUtil.isBlank(visible) ? null : visible, "toolCall", tool);
        if (!AssistantMainInputContract.matchesConfiguredOutput(actual, configured))
            throw new AssistantOutputContractException();
    }

    private AssistantLoopDecision parseSafeReadBatch(AssistantLoopModelResult result,
                                                       MediaTextGenerateRequest.TextMessageItem toolMessage,
                                                       String visible, AssistantLoopBinding frozenAgentBinding)
    {
        List<MediaTextGenerateRequest.TextToolCall> calls = toolMessage.getToolCalls();
        NativeAssistantBindingPolicy.requireArchivedCompatible(frozenAgentBinding, result.getNativeToolBindingsJson());
        TrustedNativeBindings trustedBindings = requireNativeBindings(
                result.getNativeToolBindingsJson(), result);
        List<VerifiedNativeCall> verifiedCalls = new ArrayList<>(calls.size());
        Set<String> callIds = new HashSet<>();
        for (MediaTextGenerateRequest.TextToolCall call : calls)
        {
            if (!validCall(call) || !callIds.add(call.getId()))
                fail("原生工具批次调用无效");
            VerifiedNativeCall verified = requireNativeCall(trustedBindings, call);
            if (!"TOOL".equals(verified.action())) fail("原生工具批次仅支持只读工具");
            JSONObject payload = verified.binding().getJSONObject("payloadTemplate");
            RuntimeToolRegistry.Descriptor descriptor = verified.descriptor();
            if (payload == null || descriptor == null
                    || !"READ".equals(descriptor.sideEffectLevel()) || descriptor.billable()
                    || !"REPLAY_SAFE".equals(descriptor.recoveryPolicy()))
                fail("原生工具批次仅支持可恢复只读工具");
            verifiedCalls.add(verified);
        }

        List<NativeAssistantToolBatch.Call> normalized = new ArrayList<>(calls.size());
        Map<String, Integer> attemptsByStep = new HashMap<>();
        StringBuilder identity = new StringBuilder(visible);
        for (int index = 0; index < calls.size(); index++)
        {
            MediaTextGenerateRequest.TextToolCall call = calls.get(index);
            VerifiedNativeCall verified = verifiedCalls.get(index);
            NativeRejectedSourceIdentity sourceIdentity = rejectedSourceIdentity(
                    result, toolMessage, trustedBindings, verified);
            JSONObject arguments = decisionArguments(call.getArguments(), verified.action(),
                    verified.trustedContractDigest(), sourceIdentity);
            JSONObject binding = verified.binding();
            JSONObject template = binding.getJSONObject("payloadTemplate");
            arguments = canonicalToolArguments(arguments, template,
                    trustedBindings.envelope().getString("schemaVersion"), verified.trustedContractDigest(), sourceIdentity);
            validateConfiguredOutput(frozenAgentBinding, visible, call, arguments);
            JSONObject payload = executionToolPayload(template);
            String stableStepId = payload.getString("stableStepId");
            int baseAttempt = payload.getIntValue("attemptNo");
            int offset = attemptsByStep.getOrDefault(stableStepId, 0);
            int attempt = baseAttempt + offset;
            if (StrUtil.isBlank(stableStepId) || attempt < 1 || attempt > MAX_SAFE_READ_INVOCATIONS)
                fail("原生工具批次身份无效");
            attemptsByStep.put(stableStepId, offset + 1);
            payload.put("attemptNo", attempt);
            payload.put("invocationId", "native:batch:"
                    + SecureUtil.sha256(result.getRunId() + "\n" + result.getMediaTaskId()
                    + "\n" + call.getId() + "\n" + call.getName()).substring(0, 48));
            payload.put("arguments", arguments);
            String operationIdentity = call.getId() + "\n" + call.getName() + "\n"
                    + arguments.toJSONString() + "\n" + index;
            JSONObject operationEnvelope = envelope(result,
                    decisionId(result, "call", operationIdentity), "TOOL", payload);
            AssistantLoopDecision operation;
            try
            {
                operation = decisionValidator.validateEnvelope(operationEnvelope, PROTOCOL_VERSION);
            }
            catch (AssistantDecisionValidationRejectedException rejected)
            {
                throw NativeDecisionValidationAdapter.wrap(rejected, sourceIdentity);
            }
            String operationJson = operation.getDecisionJson();
            normalized.add(new NativeAssistantToolBatch.Call(index, call.getId(), call.getName(),
                    operationJson, SecureUtil.sha256(operationJson)));
            identity.append('\n').append(operationJson);
        }
        String batchDecisionId = decisionId(result, "batch", identity.toString());
        JSONObject batchEnvelope = NativeAssistantToolBatch.envelope(result, batchDecisionId, normalized);
        AssistantLoopDecision decision = new AssistantLoopDecision();
        decision.setProtocolVersion(PROTOCOL_VERSION);
        decision.setDecisionId(batchDecisionId);
        decision.setObservedRunRevision(result.getObservedRunRevision());
        decision.setObservedPlanRevision(result.getObservedPlanRevision());
        decision.setAction(NativeAssistantToolBatch.ACTION);
        String batchJson = batchEnvelope.toJSONString(JSONWriter.Feature.WriteMapNullValue);
        NativeAssistantToolBatch.parse(batchJson);
        decision.setDecisionJson(batchJson);
        decision.setVisibleText(StrUtil.isBlank(visible) ? null : visible);
        return decision;
    }

    /** Single and batched reads compile the same trusted bindings before strict execution validation. */
    private static JSONObject canonicalToolArguments(JSONObject arguments, JSONObject template, String schemaVersion,
                                                     String contractDigest, NativeRejectedSourceIdentity sourceIdentity)
    {
        if (NativeAssistantBindingPolicy.hostInputs(schemaVersion) && template.containsKey(CAPABILITY_SCHEMA))
            arguments = NativeHostFixedToolInput.arguments(arguments, template.getJSONObject(CAPABILITY_SCHEMA));
        if (template.containsKey(NativeHostNodeInput.FIELD))
            arguments = NativeHostNodeInput.arguments(arguments, template.getJSONObject(NativeHostNodeInput.FIELD),
                    NativeHostNodeInput.DEFAULTS_VERSION.equals(template.getString(NativeHostNodeInput.DEFAULTS)),
                    template.getJSONObject(CAPABILITY_SCHEMA));
        if (template.containsKey(NativeHostTargetInput.FIELD)) {
            JSONObject targetBindings = template.getJSONObject(NativeHostTargetInput.FIELD);
            arguments = NativeHostTargetInput.arguments(arguments, targetBindings, targetBindings.getBooleanValue("bindWorkspace"));
        }
        if (template.containsKey(CAPABILITY_SCHEMA)
                && !com.aid.runtime.tool.RuntimeCapabilityInputSchema.matches(template.getJSONObject(CAPABILITY_SCHEMA), arguments)) {
            com.aid.runtime.tool.RuntimeInputDiagnostics.record(template.getString("toolCode"), template.getString("contractVersion"),
                    contractDigest, template.getJSONObject(CAPABILITY_SCHEMA), arguments);
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.argumentSchemaMismatch(contractDigest), sourceIdentity);
        }
        if (template.containsKey(NativeEmptyReadArguments.FIELD))
            arguments = NativeEmptyReadArguments.decode(arguments, template.getString(NativeEmptyReadArguments.FIELD),
                    SecureUtil.sha256(template.getString(NativeEmptyReadArguments.FIELD) + "\n" + contractDigest), sourceIdentity);
        if (template.containsKey(CAPABILITY_SCHEMA))
            NativeNodeGenerationSettingsProjection.bindStoredSettings(template.getString("toolCode"), arguments);
        return arguments;
    }

    /** Host schema metadata authorizes compilation; it is never an execution payload field. */
    private static JSONObject executionToolPayload(JSONObject template)
    {
        if (template == null) fail("原生工具绑定无效");
        JSONObject payload = new JSONObject(template);
        payload.remove(NativeEmptyReadArguments.FIELD);
        payload.remove(CAPABILITY_SCHEMA);
        payload.remove(NativeHostNodeInput.FIELD);
        payload.remove(NativeHostNodeInput.DEFAULTS);
        payload.remove(NativeHostTargetInput.FIELD);
        return payload;
    }

    static JSONObject addServerPauseOptions(JSONObject source)
    {
        return NativeAssistantControlContractV1.addServerPauseOptions(source);
    }

    private NativeCatalog nativeCatalog(JSONObject request, AssistantLoopExecutionContext context)
    {
        boolean automatic = "AUTO".equals(request.getJSONObject("turn")
                .getJSONObject("originalRequest").getString("executionPreference"));
        JSONArray definitions = new JSONArray();
        JSONArray bindings = new JSONArray();
        JSONObject toolsEnvelope = new JSONObject();
        toolsEnvelope.put("schemaVersion", TOOL_SCHEMA);
        toolsEnvelope.put("tools", definitions);
        JSONObject bindingsEnvelope = new JSONObject();
        String bindingSchemaVersion = NativeAssistantBindingPolicy.schemaVersion(context.getBinding());
        bindingsEnvelope.put("schemaVersion", bindingSchemaVersion);
        bindingsEnvelope.put("protocolVersion", PROTOCOL_VERSION);
        bindingsEnvelope.put("runId", String.valueOf(context.getRunId()));
        bindingsEnvelope.put("observedRunRevision", context.getStateRevision());
        bindingsEnvelope.put("observedPlanRevision", context.getPlanRevision());
        bindingsEnvelope.put("tools", bindings);
        boolean flowDesign = FlowScreenplayDecisionContract.designMethodRead(request)
                && !com.aid.context.ContextPreparationExecutionGate.pending(request);
        if (flowDesign) bindingsEnvelope.put(FlowScreenplayDecisionContract.DESIGN_DELIVERY, true);
        // Current-turn authority is a protocol invariant, not a business-module feature.
        // Old archived requests retain their opt-in marker and original names unchanged.
        if (NativeAssistantBindingPolicy.V11.equals(bindingSchemaVersion))
        {
            bindingsEnvelope.put("functionNameVersion", NativeExecutableNames.VERSION);
            bindingsEnvelope.put(NativeAssistantTurnState.BINDING_FIELD, NativeAssistantTurnState.VERSION);
            bindingsEnvelope.put(NativeToolContractReceiptProjection.FIELD, NativeToolContractReceiptProjection.VERSION);
        }

        boolean scopedControls = NativeAssistantTurnState.VERSION.equals(bindingsEnvelope.get(NativeAssistantTurnState.BINDING_FIELD));
        JSONObject plan = request.getJSONObject("currentPlan");
        boolean discovery = plan != null
                && NativeAssistantDiscoveryPlanService.DEFINITION_KIND.equals(
                plan.getString("serverPlanKind"));
        Map<String, JSONObject> priorResults = indexedPriorResults(
                request.getJSONArray("priorResults"), context.getPlanRevision());
        boolean completedExecutionPlan = plan != null && !discovery
                && NativeAssistantPlanContinuationPolicy.isCompletedContext(plan, priorResults);
        boolean recoverableExecutionPlan = plan != null && !discovery
                && NativeAssistantPlanContinuationPolicy.isRecoverableContext(plan, priorResults);
        boolean selectedToolRouteReady = SelectedSkillToolRoutePolicy.planningReady(request);
        String planningInstruction = null;
        if (!flowDesign && selectedToolRouteReady
                && (((plan == null || discovery) && (selectedSkillMasterRead(request)
                        || NativeSkillResultReusePolicy.referencesReady(request)))
                || completedExecutionPlan || recoverableExecutionPlan))
        {
            planningInstruction = plannerRules(request, NativeAssistantBindingPolicy.schemaVersion(context.getBinding()), !scopedControls || !completedExecutionPlan);
            addControl(definitions, bindings, "assistant_propose_plan", "PLAN",
                    (recoverableExecutionPlan
                            ? "当前计划存在已确认终态的失败步骤。提交新的有界恢复计划，"
                                + (automatic ? "系统校验后自动执行；" : "重新确认后才可执行；")
                                + "保留旧失败和费用记录，不将失败称为完成，不重做无关成功步骤。"
                            : completedExecutionPlan
                            ? "当前批准计划已全部完成。需求已满足时使用assistant_complete交付，不要重新规划。只有确有尚未完成的用户需求时，才提交仅含剩余操作的下一阶段计划。"
                            : automatic ? "提交执行计划，系统校验后自动执行，无需用户确认。" : "提交一份供用户确认的执行计划。")
                            + "计划必须包含能够形成其完成判据真实证据的执行步骤，只可使用目录中真实存在的工具或不可变方法型 TEXT Skill。"
                            + (!scopedControls || !completedExecutionPlan
                                ? "单纯只读查询及补查不使用此入口，信息齐全后使用assistant_answer回答。" : ""),
                    NativeAssistantControlContractV1.nativePayloadSchema("PLAN"),
                    serverControlPayload("PLAN", request, context), bindingSchemaVersion);
        }
        boolean screenwriterDelivery = ScreenwriterCanvasDeliveryPolicy.applies(request)
                && ScreenwriterCanvasDeliveryPolicy.includesWrite(plan);
        if (!flowDesign && (!completedExecutionPlan || !scopedControls && !screenwriterDelivery)) {
            boolean flowScreenplay = FlowScreenplayDecisionContract.applies(request);
            addControl(definitions, bindings, "assistant_answer", "CHAT",
                flowScreenplay ? "在 content 已输出完整 Markdown 正文后结束本轮。此函数只提交 outcome。"
                        : "提交已经完成的普通问答。message必须是包含format（MARKDOWN）和content（正文）的对象，不得直接传字符串。"
                        + "不得只承诺将读取或执行；需要操作时调用对应工具或提交计划。已有执行计划的交付使用assistant_complete及真实证据。",
                NativeAssistantControlContractV1.nativePayloadSchema("CHAT"),
                flowScreenplay ? FlowScreenplayDecisionContract.template() : null,
                bindingSchemaVersion);
        }
        if (!automatic)
            addControl(definitions, bindings, "assistant_ask_user", "ASK",
                "向用户提出完成当前任务所必需的结构化问题。",
                NativeAssistantControlContractV1.nativePayloadSchema("ASK"),
                serverControlPayload("ASK", request, context), bindingSchemaVersion);
        if (plan != null)
        {
            if (!discovery)
            {
                if (!scopedControls || !completedExecutionPlan)
                    addControl(definitions, bindings, "assistant_checkpoint", "CHECKPOINT",
                        "保存当前批准计划的进度检查点。",
                        NativeAssistantControlContractV1.nativePayloadSchema("CHECKPOINT"), null, bindingSchemaVersion);
                if (!NativeAssistantBindingPolicy.V11.equals(bindingSchemaVersion) || completedExecutionPlan)
                    addControl(definitions, bindings, "assistant_complete", "COMPLETE",
                        "仅在批准计划和完成证据全部满足时提交最终完成结果。artifactRefs仅接受artifact:引用；已注册目标写入回执放入targetWriteEvidenceRefs并对应TARGET_WRITE判据。",
                        NativeAssistantControlContractV1.nativePayloadSchema("COMPLETE"),
                        NativeAssistantBindingPolicy.V11.equals(bindingSchemaVersion)
                                ? JSONObject.of(NativeHostCompletionInputContract.FIELD, NativeHostCompletionInputContract.bindings(plan)) : null,
                        bindingSchemaVersion);
            }
            addExecutableTools(request, plan, definitions, bindings, context,
                    NativeExecutableNames.VERSION.equals(bindingsEnvelope.getString("functionNameVersion")));
        }
        if (bindings.size() > MAX_BINDINGS) fail("原生工具数量超限");
        if (com.aid.context.ContextPreparationExecutionGate.restrict(request, definitions, bindings))
            planningInstruction = "先完成当前持久上下文批次的原文引用校验；只有宿主确认全部来源覆盖后才恢复原请求。准备结果只属于派生上下文，不是业务完成证据或执行权限。";
        else if (!flowDesign && screenwriterDelivery && !discovery)
        {
            String requiredWrite = ScreenwriterCanvasDeliveryPolicy.readyWrite(bindings);
            if (requiredWrite != null)
            {
                definitions.removeIf(raw -> !requiredWrite.equals(((JSONObject) raw).getString("name")));
                bindings.removeIf(raw -> !requiredWrite.equals(((JSONObject) raw).getString("name")));
            }
        }
        return new NativeCatalog(toolsEnvelope, bindingsEnvelope, planningInstruction);
    }

    /** Full execution policy belongs to the system instruction, not a bounded tool description. */
    private String plannerRules(JSONObject request, String version, boolean plainAnswerAvailable)
    {
        return AssistantRemainingPlanPolicy.instruction()
                + NativeAssistantPlanReadPolicy.plannerInstruction()
                + (plainAnswerAvailable ? NativePlanningEvidenceGuidance.readOnlyInstruction() : "")
                + "inputArtifactBindings的每个sourceStableStepId必须直接列入本步骤dependsOn，不能只依靠传递依赖。"
                + "logicalArtifactId与artifactType必须是工具契约实际支持的产物身份和类型，不能把文件格式当成产物类型，"
                + "不能自行给服务端产物起别名。遵循各工具的规划输出ID约定，后续工具从真实完成回执读取引用；不要猜测ID或版本。"
                + "已注册目标写入（文本节点或私人Skill草稿）使用TARGET_WRITE及runtime-step证据，不得把目标引用填入artifactRefs，"
                + "也不得声明当前步骤无法产出的归档或评审判据。"
                + SelectedSkillToolRoutePolicy.plannerInstruction(request)
                + (NativeAssistantBindingPolicy.V11.equals(version)
                    ? "创建Skill时，若当前计划的bindingCode枚举尚未开放保存工具，先直接调用aid.skill.directory，keyword使用skill_authoring_flow。取得固定版本后再提交计划。保存步骤声明authoringSourceMode，宿主会展开固定主文件、对应分支及依赖。已有显式读取计划仍受同样核验。固定归档类型的inputArtifactBindings可省略artifactType；自定义或动态类型须明确。" : "")
                + (NativeSkillResultReusePolicy.referencesReady(request) ? NativeSkillResultReusePolicy.instruction() : "");
    }

    private JSONObject serverControlPayload(String action, JSONObject request,
                                            AssistantLoopExecutionContext context)
    {
        JSONObject payload = new JSONObject();
        if ("PLAN".equals(action))
        {
            payload.put("parentRevision", context.getPlanRevision());
            if (NativeAssistantBindingPolicy.hostInputs(NativeAssistantBindingPolicy.schemaVersion(context.getBinding())))
            {
                String version = NativeAssistantBindingPolicy.schemaVersion(context.getBinding());
                JSONObject hostBindings = NativeHostPlanInputContract.bindings(request, toolRegistry,
                        hostReadMaxAttempts, hostOperationMaxAttempts, version);
                if (NativeAssistantBindingPolicy.V11.equals(version))
                    hostBindings = NativeHostPlanInputContract.withStepIdentityScope(hostBindings, context.getPlanRevision());
                payload.put(NativeHostPlanInputContract.FIELD, hostBindings);
            }
            return payload;
        }
        if (!"ASK".equals(action)) return null;
        JSONObject run = request.getJSONObject("run");
        JSONObject executionContext = request.getJSONObject("executionContext");
        String contextDigest = executionContext == null ? null : executionContext.getString("contextDigest");
        if (run == null || contextDigest == null || !contextDigest.matches("[0-9a-f]{64}")
                || !Objects.equals(contextDigest, run.getString("contextDigest")))
            fail("Native ASK context identity is invalid");
        String requestIdentity = context.getRunId() + "\n" + context.getStateRevision() + "\n"
                + context.getPlanRevision() + "\n" + context.getIteration();
        payload.put("requestKey", "native-ask-" + SecureUtil.sha256(requestIdentity).substring(0, 48));
        payload.put("contextDigest", contextDigest);
        return payload;
    }

    /** A user-selected Skill must be read from its fixed package before it can shape an execution plan. */
    /** Explain a temporary discovery gate without granting any additional execution capability. */
    private String planningPrerequisiteInstruction(JSONObject request)
    {
        if (NativeSkillResultReusePolicy.referencesReady(request)) return NativeSkillResultReusePolicy.instruction();
        JSONObject plan = request.getJSONObject("currentPlan");
        if (plan != null && !NativeAssistantDiscoveryPlanService.DEFINITION_KIND.equals(
                plan.getString("serverPlanKind"))) return "";
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        if (!NativeAssistantPlanReadPolicy.requiresSelectedSkillMaster(selected)
                || selectedSkillMasterRead(request)) return "";
        JSONObject identity = new JSONObject();
        identity.put("skillCode", selected.getString("code"));
        identity.put("skillVersionId", selected.getString("versionId"));
        identity.put("packageDigest", selected.getString("packageDigest"));
        identity.put("path", "SKILL.md");
        return "\n\n当前处于所选方法的读取准备阶段，尚未取得本轮固定版本主文件的有效读取回执，"
                + "因此暂不提供规划入口和后续执行工具。这不是这些能力永久缺失的结论。"
                + "如果本轮请求需要执行所选方法，请先调用本轮已提供的aid.skill.read-resource原生函数，"
                + "按其真实输入Schema读取以下固定资源，再根据下一轮实际目录判断可执行能力："
                + identity.toJSONString() + "。历史对话中的读取说明不能替代本轮已验证回执。"
                + "若该读取函数确实不可用或返回失败，只报告该具体前置条件，不虚构更大的能力缺口。"
                + "单纯回答问题仍可使用普通问答；不要把未执行的创作请求当作已完成。"
                + "读取完成不授予节点、媒体或计费权限，后续仍遵循目标引用、真实目录、计划与费用确认。";
    }

    private boolean selectedSkillMasterRead(JSONObject request)
    {
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        if (!NativeAssistantPlanReadPolicy.requiresSelectedSkillMaster(selected)) return true;
        JSONArray observations = request.getJSONArray("observations");
        if (observations != null)
        {
            for (int index = 0; index < observations.size(); index++)
            {
                JSONObject item = observations.getJSONObject(index);
                JSONObject observation = item == null ? null : item.getJSONObject("observation");
                if (item != null && "PACKAGE_RESOURCE".equals(item.getString("observationKind"))
                        && "SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(item.getString("authority"))
                        && "aid.skill.read-resource".equals(item.getString("toolCode"))
                        && matchesSelectedSkillMaster(selected, observation)) return true;
            }
        }
        JSONArray prior = request.getJSONArray("priorResults");
        if (prior == null) return false;
        for (int index = 0; index < prior.size(); index++)
        {
            JSONObject item = prior.getJSONObject(index);
            JSONObject observation = item == null ? null : item.getJSONObject("observation");
            if (item != null && "SUCCEEDED".equals(item.getString("status"))
                    && "aid.skill.read-resource".equals(item.getString("toolCode"))
                    && matchesSelectedSkillMaster(selected, observation)) return true;
        }
        return false;
    }

    static boolean matchesSelectedSkillMaster(JSONObject selected, JSONObject observation)
    {
        if (!NativeAssistantPlanReadPolicy.requiresSelectedSkillMaster(selected)) return true;
        String code = selected.getString("code");
        String versionId = selected.getString("versionId");
        String packageDigest = selected.getString("packageDigest");
        if (StrUtil.isBlank(code) || StrUtil.isBlank(versionId)
                || !versionId.matches("^[1-9][0-9]{0,18}$")
                || StrUtil.isBlank(packageDigest) || !packageDigest.matches("^[0-9a-f]{64}$"))
            throw new ServiceException("所选Skill固定版本无效");
        return observation != null
                && Objects.equals(code, observation.getString("skillCode"))
                && Objects.equals(versionId, observation.getString("skillVersionId"))
                && Objects.equals(packageDigest, observation.getString("packageDigest"))
                && "SKILL.md".equals(observation.getString("path"))
                && StrUtil.isNotBlank(observation.getString("content"))
                && Objects.equals(observation.getString("resourceDigest"),
                SecureUtil.sha256(observation.getString("content")));
    }

    private void addExecutableTools(JSONObject request, JSONObject plan, JSONArray definitions,
                                    JSONArray bindings, AssistantLoopExecutionContext context, boolean descriptiveNames)
    {
        JSONArray planSteps = plan.getJSONArray("steps");
        if (planSteps == null || planSteps.isEmpty()) fail("批准计划无效");
        Map<String, JSONObject> prior = indexedPriorResults(
                request.getJSONArray("priorResults"), context.getPlanRevision());
        Map<String, JSONObject> toolCatalog = indexed(request.getJSONArray("toolCatalog"), "toolCode");
        Map<String, JSONObject> skillCatalog = indexed(request.getJSONArray("skillCatalog"), "skillCode");
        JSONArray expectedRevisions = request.getJSONObject("executionContext") == null ? null
                : request.getJSONObject("executionContext").getJSONArray("expectedContextRevisions");
        if (expectedRevisions == null) fail("原生上下文版本缺失");
        Set<String> names = new HashSet<>();
        boolean discovery = NativeAssistantDiscoveryPlanService.DEFINITION_KIND.equals(
                plan.getString("serverPlanKind"));
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        boolean selectedMasterRequired = !discovery && !NativeSkillResultReusePolicy.isReuseOnlyPlan(plan)
                && NativeAssistantPlanReadPolicy.requiresSelectedSkillMaster(selected);
        boolean selectedMasterReady = !selectedMasterRequired || selectedSkillMasterRead(request);
        List<ExecutableCandidate> ready = new ArrayList<>();
        List<ExecutableCandidate> reusableReads = new ArrayList<>();
        for (int index = 0; index < planSteps.size(); index++)
        {
            JSONObject step = planSteps.getJSONObject(index);
            String action = step == null ? null : step.getString("executorKind");
            if (step == null || !("TOOL".equals(action) || "SKILL".equals(action)))
                fail("原生计划执行器无效");
            String stableStepId = step.getString("stableStepId");
            if (StrUtil.isBlank(stableStepId) || stableStepId.length() > 64) fail("原生计划步骤标识无效");
            if (com.aid.runtime.tool.ContextPreparationRuntimeToolProvider.CODE.equals(step.getString("bindingCode"))
                    && !com.aid.context.ContextPreparationExecutionGate.pending(request)) continue;
            JSONObject previous = prior.get(stableStepId);
            String previousStatus = previous == null ? null : previous.getString("status");
            boolean safeRead = "TOOL".equals(action)
                    && NativeAssistantDiscoveryPlanService.isSafeRead(step.getString("bindingCode"), plan);
            if (!selectedMasterReady && !("TOOL".equals(action)
                    && "aid.skill.read-resource".equals(step.getString("bindingCode"))))
                continue;
            boolean reusableRead = "SUCCEEDED".equals(previousStatus) && safeRead;
            if ("SUCCEEDED".equals(previousStatus) && !reusableRead) continue;
            if (previousStatus != null && !reusableRead
                    && !RETRYABLE_TERMINAL.contains(previousStatus)) continue;
            if (!dependenciesComplete(step.getJSONArray("dependsOn"), prior)) continue;
            if (!step.getBooleanValue("availableNow") || !step.getBooleanValue("inputsResolved")) continue;
            int attempt = previous == null ? 1 : previous.getIntValue("attemptNo") + 1;
            int maximum = step.getIntValue("maxAttempts");
            int consecutiveFailures = previous == null ? 0
                    : previous.getIntValue("consecutiveFailureCount");
            if (attempt < 1 || maximum < 1 || attempt > MAX_SAFE_READ_INVOCATIONS
                    || !safeRead && attempt > maximum
                    || safeRead && previousStatus != null && RETRYABLE_TERMINAL.contains(previousStatus)
                    && consecutiveFailures >= maximum) continue;
            String code = step.getString("bindingCode");
            String version = step.getString("bindingVersion");
            JSONObject descriptor;
            JSONObject schema;
            String alias;
            JSONObject payload = new JSONObject();
            if ("TOOL".equals(action))
            {
                descriptor = toolCatalog.get(code);
                if (descriptor == null || !Objects.equals(version, descriptor.getString("contractVersion"))
                        || !descriptor.getBooleanValue("availableNow"))
                    fail("原生工具目录已变化");
                schema = NativeAssistantContextProjection.resolveObject(request, descriptor, "inputSchema");
                if (schema == null) fail("原生工具Schema无效");
                if (NativeAssistantBindingPolicy.methodResults(NativeAssistantBindingPolicy.schemaVersion(context.getBinding())))
                {
                    var mediaType = NativeAssistantBindingPolicy.hostInputs(
                            NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))
                            ? toolRegistry.billingMediaType(code) : null;
                    JSONObject projected = NativeCapabilityInputProjection.project(request, code, schema,
                            mediaType == null ? null : mediaType.name(),
                            NativeAssistantBindingPolicy.V11.equals(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))
                                    ? toolRegistry.capabilityIndependentInputFields(code) : Set.of());
                    projected = NativeNodeGenerationSettingsProjection.project(request, code, projected);
                    if (!projected.equals(schema)) payload.put(CAPABILITY_SCHEMA, projected);
                    schema = projected;
                    if (NativeAssistantBindingPolicy.hostInputs(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))) {
                        if (NativeAssistantBindingPolicy.V11.equals(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))) {
                            projected = NativeHostFixedToolInput.withDefaults(projected, toolRegistry.hostOwnedInputDefaults(code));
                            projected = NativeHostSourceInput.schema(request,code,projected);
                            projected = NativeHostSkillPrerequisites.projectRead(request, code, stableStepId, projected);
                            projected = NativeHostSkillPrerequisites.projectProposal(request, code, step, plan, projected);
                            if ("PREVIZ_3DBOX".equals(context.getBinding().getModuleCode())) {
                                JSONObject beforePreviewProjection = projected;
                                projected = NativeHostPreviewInput.schema(request, step, projected);
                                if (!projected.equals(beforePreviewProjection)) payload.put(CAPABILITY_SCHEMA, projected);
                            }
                        }
                        JSONObject business = NativeHostFixedToolInput.schema(projected);
                        if (!business.equals(projected)) payload.put(CAPABILITY_SCHEMA, projected);
                        schema = business;
                    }
                }
                boolean encodedEmptyRead = NativeAssistantBindingPolicy.methodResults(
                        NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))
                        && NativeEmptyReadArguments.eligible(toolRegistry.require(code));
                if (encodedEmptyRead)
                {
                    schema = NativeEmptyReadArguments.parameters();
                    payload.put(NativeEmptyReadArguments.FIELD, NativeEmptyReadArguments.ENCODING);
                }
                if (NativeAssistantBindingPolicy.hostInputs(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))) {
                    JSONObject nodeSources = NativeHostNodeInput.sources(request, code);
                    if (!nodeSources.isEmpty()) {
                        payload.put(NativeHostNodeInput.FIELD, nodeSources);
                        boolean defaults = NativeAssistantBindingPolicy.V11.equals(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()));
                        if (defaults) payload.put(NativeHostNodeInput.DEFAULTS, NativeHostNodeInput.DEFAULTS_VERSION);
                        schema = NativeHostNodeInput.schema(schema, defaults, nodeSources);
                    }
                }
                if (NativeAssistantBindingPolicy.V11.equals(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))) {
                    JSONObject targets = NativeHostTargetInput.bindings(request);
                    targets.put("bindWorkspace", NativeHostTargetInput.bindsWorkspace(schema));
                    JSONObject projected = NativeHostTargetInput.schema(schema, targets);
                    if (!projected.equals(schema)) {
                        payload.put(NativeHostTargetInput.FIELD, targets);
                        schema = projected;
                    }
                }
                alias = "tool_" + SecureUtil.sha256(code + "\n" + version + "\n"
                        + descriptor.getString("schemaDigest") + "\n" + stableStepId
                        + (payload.containsKey(CAPABILITY_SCHEMA) ? "\n" + inputSchemaDigest(payload.getJSONObject(CAPABILITY_SCHEMA)) : "")
                        + (encodedEmptyRead ? "\n" + NativeEmptyReadArguments.ENCODING : "")
                        + (payload.containsKey(NativeHostNodeInput.FIELD) ? "\n" + NativeHostNodeInput.digest(payload.getJSONObject(NativeHostNodeInput.FIELD)) : "")
                        + (payload.containsKey(NativeHostNodeInput.DEFAULTS) ? "\n" + NativeHostNodeInput.DEFAULTS_VERSION : "")
                        + (payload.containsKey(NativeHostTargetInput.FIELD) ? "\n" + NativeHostTargetInput.digest(payload.getJSONObject(NativeHostTargetInput.FIELD)) : "")).substring(0, 32);
                payload.put("invocationId", "native:" + context.getRunId() + ":" + context.getPlanRevision()
                        + ":" + stableStepId + ":" + attempt);
                payload.put("stableStepId", stableStepId);
                payload.put("attemptNo", attempt);
                payload.put("toolCode", code);
                payload.put("contractVersion", version);
                payload.put("operationKind", step.getString("operationKind"));
                payload.put("inputArtifactRefs", copy(step.getJSONArray("resolvedInputArtifactRefs")));
                payload.put("expectedContextRevisions", copy(expectedRevisions));
            }
            else
            {
                descriptor = skillCatalog.get(code);
                Long skillVersionId = positiveId(version);
                String packageDigest = descriptor == null ? null : descriptor.getString("packageDigest");
                if (descriptor == null || skillVersionId == null
                        || !Objects.equals(version, descriptor.getString("skillVersionId"))
                        || packageDigest == null || !packageDigest.matches("^[0-9a-f]{64}$"))
                    fail("原生Skill目录已变化");
                Object declaredSchema = NativeAssistantContextProjection.resolveValue(request, descriptor.get("inputSchema"));
                if (declaredSchema != null && !(declaredSchema instanceof JSONObject))
                    fail("原生Skill输入Schema无效");
                schema = declaredSchema == null ? new JSONObject() : (JSONObject) declaredSchema;
                String inputSchemaDigest = inputSchemaDigest(schema);
                alias = "skill_" + SecureUtil.sha256(code + "\n" + version + "\n"
                        + packageDigest + "\n" + inputSchemaDigest + "\n" + stableStepId).substring(0, 32);
                payload.put("invocationId", "native:" + context.getRunId() + ":" + context.getPlanRevision()
                        + ":" + stableStepId + ":" + attempt);
                payload.put("stableStepId", stableStepId);
                payload.put("attemptNo", attempt);
                payload.put("skillCode", code);
                payload.put("skillVersionId", version);
                payload.put("packageDigest", packageDigest);
                payload.put("inputSchemaDigest", inputSchemaDigest);
                if (NativeAssistantBindingPolicy.V8.equals(NativeAssistantBindingPolicy.schemaVersion(context.getBinding()))
                        || NativeAssistantBindingPolicy.methodResults(NativeAssistantBindingPolicy.schemaVersion(context.getBinding())))
                    schema = com.aid.skill.service.SkillMethodInputContract.objectParameters(schema);
            }
            alias = NativeExecutableNames.name(action, code, stableStepId, alias, descriptiveNames);
            if (!names.add(alias)) fail("原生工具别名冲突");
            String description = StrUtil.blankToDefault(descriptor.getString("description"),
                    descriptor.getString("name"));
            // Multiple approved steps can bind the same tool/schema. Their opaque aliases are
            // not meaningful to the model; expose the exact step without changing its binding.
            String stepIdentity = "bindingCode=" + JSON.toJSONString(code)
                    + "; stableStepId=" + JSON.toJSONString(stableStepId)
                    + "; state=" + (reusableRead ? "SUCCEEDED_READ_REFRESH"
                    : previousStatus == null ? "PENDING" : previousStatus)
                    + ". Match this plan step before calling.\n";
            String guidance = stepIdentity + NativeHostFixedToolInput.instruction(payload.getJSONObject(CAPABILITY_SCHEMA))
                    + (payload.containsKey(NativeEmptyReadArguments.FIELD)
                            ? "This read has no business parameters. Both {} and {\"input\":{}} are declared valid transport forms; input, when supplied, must be empty.\n" : "")
                            + argumentGuidance(schema);
            ExecutableCandidate candidate = new ExecutableCandidate(alias, action,
                    executableDescription(guidance, description),
                    schema, payload);
            (reusableRead ? reusableReads : ready).add(candidate);
        }
        int remaining = MAX_BINDINGS - bindings.size();
        remaining = addCandidates(ready, remaining, definitions, bindings);
        addCandidates(reusableReads, remaining, definitions, bindings);
    }

    /** Keep the full step/parameter guidance; budget optional business prose by the wire limit. */
    static String executableDescription(String guidance, String description)
    {
        int remaining = NativeTextToolRequestAdapter.MAX_DESCRIPTION_CHARACTERS - guidance.length();
        if (remaining < 0) fail("原生工具必要说明超出长度限制");
        return guidance + (description == null ? "" : truncate(description, remaining));
    }

    /** Guidance changes neither the published schema nor the model's returned arguments. */
    static String argumentGuidance(JSONObject schema)
    {
        String direct = "Supply the parameter object directly. Do not wrap it in an arguments field unless the schema declares that field. ";
        if (NativeEmptyReadArguments.plainEmptyObject(schema))
            return direct + "This closed parameter object has no fields: send exactly {}.\n";
        return direct + "Include every required property at every nested object level, including an empty array when the schema requires it and the source has no items; do not invent items. Copy opaque identifiers, revisions and hashes exactly from authoritative receipts; never reconstruct, normalize or guess them. Re-read a missing receipt before acting.\n";
    }

    /** Pending and retryable work is visible before refreshable reads, while the native catalog stays bounded. */
    private int addCandidates(List<ExecutableCandidate> candidates, int remaining,
                              JSONArray definitions, JSONArray bindings)
    {
        for (ExecutableCandidate candidate : candidates)
        {
            if (remaining <= 0) break;
            addDefinition(definitions, candidate.alias(), candidate.description(), candidate.schema());
            JSONObject binding = new JSONObject();
            binding.put("name", candidate.alias());
            binding.put("action", candidate.action());
            binding.put("payloadTemplate", candidate.payload());
            bindings.add(binding);
            remaining--;
        }
        return remaining;
    }

    private String inputSchemaDigest(JSONObject schema)
    {
        if (schema == null) fail("原生Skill输入Schema无效");
        try
        {
            if (JSONSchema.of(schema) == null) fail("原生Skill输入Schema无效");
        }
        catch (RuntimeException invalid)
        {
            fail("原生Skill输入Schema无效");
        }
        return SecureUtil.sha256(JSON.toJSONString(schema,
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    private TrustedNativeBindings requireNativeBindings(String json, AssistantLoopModelResult result)
    {
        JSONObject envelope = object(json, "原生工具绑定缺失");
        Long observedRunRevision = exactLong(envelope.get("observedRunRevision"));
        Long observedPlanRevision = exactLong(envelope.get("observedPlanRevision"));
        String schemaVersion = envelope.getString("schemaVersion");
        boolean bindingV2 = BINDING_SCHEMA_V2.equals(schemaVersion);
        boolean bindingV3 = BINDING_SCHEMA_V3.equals(schemaVersion);
        boolean bindingStrictInputs = NativeAssistantBindingPolicy.strictInputs(schemaVersion);
        Set<String> envelopeFields = new HashSet<>(BINDING_ENVELOPE_FIELDS);
        if (envelope.containsKey(NativeSubmittedAnswerReceipt.BINDING_FIELD)) {
            if (!NativeSubmittedAnswerReceipt.VERSION.equals(envelope.get(NativeSubmittedAnswerReceipt.BINDING_FIELD)))
                fail("原生已提交回答回执协议无效");
            envelopeFields.add(NativeSubmittedAnswerReceipt.BINDING_FIELD);
        }
        if (envelope.containsKey(FlowScreenplayDecisionContract.DESIGN_DELIVERY)) {
            if (!Boolean.TRUE.equals(envelope.get(FlowScreenplayDecisionContract.DESIGN_DELIVERY)))
                fail("流程剧本设计交付协议无效");
            envelopeFields.add(FlowScreenplayDecisionContract.DESIGN_DELIVERY);
        }
        if (envelope.containsKey(NativeAssistantTurnState.BINDING_FIELD)) {
            if (!NativeAssistantBindingPolicy.V11.equals(schemaVersion)
                    || !NativeAssistantTurnState.VERSION.equals(envelope.get(NativeAssistantTurnState.BINDING_FIELD)))
                fail("原生当前轮次状态协议无效");
            envelopeFields.add(NativeAssistantTurnState.BINDING_FIELD);
        }
        if (envelope.containsKey(NativeToolContractReceiptProjection.FIELD)) {
            if (!NativeAssistantBindingPolicy.V11.equals(schemaVersion)
                    || !NativeToolContractReceiptProjection.VERSION.equals(envelope.get(NativeToolContractReceiptProjection.FIELD)))
                fail("原生目录回执投影协议无效");
            envelopeFields.add(NativeToolContractReceiptProjection.FIELD);
        }
        if (envelope.containsKey("functionNameVersion")) {
            if (!NativeAssistantBindingPolicy.V11.equals(schemaVersion)
                    || !NativeExecutableNames.VERSION.equals(envelope.get("functionNameVersion")))
                fail("原生工具名称协议无效");
            envelopeFields.add("functionNameVersion");
        }
        if (envelope.size() != envelopeFields.size()
                || !envelopeFields.containsAll(envelope.keySet())
                || !(envelope.get("schemaVersion") instanceof String)
                || !(envelope.get("protocolVersion") instanceof String)
                || !(bindingV2 || bindingV3 || bindingStrictInputs)
                || !PROTOCOL_VERSION.equals(envelope.getString("protocolVersion"))
                || !(envelope.get("runId") instanceof String)
                || !Objects.equals(String.valueOf(result.getRunId()), envelope.getString("runId"))
                || !Objects.equals(result.getObservedRunRevision(), observedRunRevision)
                || !Objects.equals(result.getObservedPlanRevision(), observedPlanRevision))
            fail("原生工具绑定已变化");
        Object rawValues = envelope.get("tools");
        if (!(rawValues instanceof JSONArray)) fail("原生工具绑定无效");
        JSONArray values = (JSONArray) rawValues;
        if (values.isEmpty() || values.size() > MAX_BINDINGS) fail("原生工具绑定无效");
        Map<String, JSONObject> bindings = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++)
        {
            Object rawValue = values.get(index);
            if (!(rawValue instanceof JSONObject)) fail("原生工具绑定无效");
            JSONObject value = (JSONObject) rawValue;
            String candidate = value.getString("name");
            String action = value.getString("action");
            Object rawTemplate = value.get("payloadTemplate");
            if (rawTemplate != null && !(rawTemplate instanceof JSONObject)) fail("原生工具绑定无效");
            Set<String> entryFields = bindingStrictInputs && "ASK".equals(action)
                    ? Set.of("name", "action", "payloadTemplate", "controlInputSchemaDigest") : BINDING_FIELDS;
            if (bindingStrictInputs && "ASK".equals(action)
                    && !NativeAssistantBindingPolicy.askInputSchemaDigest(schemaVersion).equals(value.get("controlInputSchemaDigest")))
                fail("原生问题输入契约已变化");
            if (value.size() != entryFields.size()
                    || !entryFields.containsAll(value.keySet())
                    || !(value.get("name") instanceof String)
                    || !(value.get("action") instanceof String)
                    || StrUtil.isBlank(candidate) || !candidate.matches("[A-Za-z0-9_-]{1,64}")
                    || bindings.putIfAbsent(candidate, value) != null
                    || !("TOOL".equals(action) || (bindingV3 || bindingStrictInputs) && "SKILL".equals(action)
                    || CONTROL_ACTIONS.containsValue(action)))
                fail("原生工具绑定无效");
            requireBindingTemplate(candidate, action, (JSONObject) rawTemplate, result, schemaVersion);
        }
        return new TrustedNativeBindings(envelope, Map.copyOf(bindings), SecureUtil.sha256(json));
    }

    private void requireBindingTemplate(String name, String action, JSONObject template,
                                         AssistantLoopModelResult result, String schemaVersion)
    {
        if (CONTROL_ACTIONS.containsValue(action))
        {
            if (!Objects.equals(CONTROL_ACTIONS.get(name), action)) fail("原生工具绑定无效");
            if ("CHAT".equals(action) && FlowScreenplayDecisionContract.bound(template)) return;
            if ("PLAN".equals(action))
            {
                Long parentRevision = template == null ? null
                        : exactLong(template.get("parentRevision"));
                boolean hostPlan = NativeAssistantBindingPolicy.hostInputs(schemaVersion);
                if (template == null || template.size() != (hostPlan ? 2 : 1) || parentRevision == null
                        || hostPlan && !(template.get(NativeHostPlanInputContract.FIELD) instanceof JSONObject)
                        || parentRevision < 0
                        || !Objects.equals(parentRevision, result.getObservedPlanRevision()))
                    fail("原生工具绑定无效");
                if (hostPlan)
                    NativeHostPlanInputContract.requireStepIdentityScope(template.getJSONObject(NativeHostPlanInputContract.FIELD), parentRevision);
                return;
            }
            if ("ASK".equals(action))
            {
                String requestKey = template == null ? null : template.getString("requestKey");
                String contextDigest = template == null ? null : template.getString("contextDigest");
                if (template == null || template.size() != 2
                        || !(template.get("requestKey") instanceof String)
                        || !(template.get("contextDigest") instanceof String)
                        || StrUtil.isBlank(requestKey)
                        || !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
                        || contextDigest == null || !contextDigest.matches("[0-9a-f]{64}"))
                    fail("原生工具绑定无效");
                return;
            }
            if ("COMPLETE".equals(action) && NativeAssistantBindingPolicy.V11.equals(schemaVersion)) {
                if (template == null || template.size() != 1
                        || !NativeHostCompletionInputContract.validBindings(template.getJSONObject(NativeHostCompletionInputContract.FIELD)))
                    fail("完成条件绑定无效");
                return;
            }
            if (template != null) fail("原生工具绑定无效");
            return;
        }
        Set<String> expectedFields;
        if ("TOOL".equals(action))
        {
            expectedFields = TOOL_PAYLOAD_FIELDS;
            if (template != null && template.containsKey(CAPABILITY_SCHEMA))
            {
                if (!NativeAssistantBindingPolicy.methodResults(schemaVersion)
                        || !(template.get(CAPABILITY_SCHEMA) instanceof JSONObject)
                        || template.containsKey(NativeEmptyReadArguments.FIELD))
                    fail("原生能力契约绑定无效");
                inputSchemaDigest(template.getJSONObject(CAPABILITY_SCHEMA));
                Set<String> capabilityFields = new HashSet<>(TOOL_PAYLOAD_FIELDS);
                capabilityFields.add(CAPABILITY_SCHEMA);
                expectedFields = Set.copyOf(capabilityFields);
            }
            if (template != null && template.containsKey(NativeEmptyReadArguments.FIELD))
            {
                if (!NativeAssistantBindingPolicy.methodResults(schemaVersion)
                        || !NativeEmptyReadArguments.supported(template.get(NativeEmptyReadArguments.FIELD))
                        || !NativeEmptyReadArguments.eligible(toolRegistry.require(template.getString("toolCode"))))
                    fail("原生参数传输契约无效");
                Set<String> encodedFields = new HashSet<>(TOOL_PAYLOAD_FIELDS);
                encodedFields.add(NativeEmptyReadArguments.FIELD);
                expectedFields = Set.copyOf(encodedFields);
            }
            if (template != null && template.containsKey(NativeHostNodeInput.FIELD)) {
                if (!NativeAssistantBindingPolicy.hostInputs(schemaVersion)
                        || !(template.get(NativeHostNodeInput.FIELD) instanceof JSONObject nodeSources)
                        || nodeSources.isEmpty() || nodeSources.size() > 100
                        || template.containsKey(NativeEmptyReadArguments.FIELD)) fail("节点复用绑定无效");
                Set<String> nodeFields = new HashSet<>(expectedFields);
                nodeFields.add(NativeHostNodeInput.FIELD);
                if (template.containsKey(NativeHostNodeInput.DEFAULTS)) {
                    if (!NativeAssistantBindingPolicy.V11.equals(schemaVersion)
                            || !NativeHostNodeInput.DEFAULTS_VERSION.equals(template.get(NativeHostNodeInput.DEFAULTS)))
                        fail("节点默认输入绑定无效");
                    nodeFields.add(NativeHostNodeInput.DEFAULTS);
                }
                expectedFields = Set.copyOf(nodeFields);
            }
            if (template != null && template.containsKey(NativeHostTargetInput.FIELD)) {
                if (!NativeAssistantBindingPolicy.V11.equals(schemaVersion)
                        || !(template.get(NativeHostTargetInput.FIELD) instanceof JSONObject targets)
                        || !NativeHostTargetInput.valid(targets)
                        || template.containsKey(NativeEmptyReadArguments.FIELD)) fail("目标观察绑定无效");
                Set<String> targetFields = new HashSet<>(expectedFields);
                targetFields.add(NativeHostTargetInput.FIELD);
                expectedFields = Set.copyOf(targetFields);
            }
        }
        else if ("SKILL".equals(action) && (BINDING_SCHEMA_V3.equals(schemaVersion) || NativeAssistantBindingPolicy.strictInputs(schemaVersion)))
            expectedFields = SKILL_PAYLOAD_FIELDS;
        else
        {
            fail("原生工具绑定无效");
            return;
        }
        if (template == null || template.size() != expectedFields.size()
                || !expectedFields.containsAll(template.keySet())) fail("原生工具绑定无效");
        Object attempt = template.get("attemptNo");
        if (!boundedText(template.get("invocationId"), 100)
                || !boundedText(template.get("stableStepId"), 64)
                || !(attempt instanceof Integer attemptNo) || attemptNo < 1 || attemptNo > 100)
            fail("原生工具绑定无效");
        if ("TOOL".equals(action))
        {
            if (!boundedText(template.get("toolCode"), 100)
                    || !boundedText(template.get("contractVersion"), 100)
                    || !boundedText(template.get("operationKind"), 64)
                    || !(template.get("inputArtifactRefs") instanceof JSONArray)
                    || !(template.get("expectedContextRevisions") instanceof JSONArray))
                fail("原生工具绑定无效");
            return;
        }
        if (!boundedText(template.get("skillCode"), 64)
                || !(template.get("skillVersionId") instanceof String skillVersionId)
                || positiveId(skillVersionId) == null
                || !(template.get("packageDigest") instanceof String packageDigest)
                || !packageDigest.matches("^[0-9a-f]{64}$")
                || !(template.get("inputSchemaDigest") instanceof String inputSchemaDigest)
                || !inputSchemaDigest.matches("^[0-9a-f]{64}$"))
            fail("原生Skill绑定无效");
    }

    private VerifiedNativeCall requireNativeCall(TrustedNativeBindings bindings,
                                                  MediaTextGenerateRequest.TextToolCall call)
    {
        JSONObject binding = bindings.values().get(call.getName());
        if (binding == null) fail("原生工具未获授权");
        String action = binding.getString("action");
        RuntimeToolRegistry.Descriptor descriptor = null;
        String trustedContractDigest;
        if ("TOOL".equals(action))
        {
            JSONObject template = binding.getJSONObject("payloadTemplate");
            String toolCode = template == null ? null : template.getString("toolCode");
            String contractVersion = template == null ? null : template.getString("contractVersion");
            descriptor = toolRegistry.require(toolCode);
            if (!Objects.equals(contractVersion, descriptor.contractVersion()))
                fail("原生工具绑定已变化");
            String expectedAlias = "tool_" + SecureUtil.sha256(toolCode + "\n" + contractVersion
                    + "\n" + descriptor.schemaDigest() + "\n"
                    + template.getString("stableStepId")
                    + (template.containsKey(CAPABILITY_SCHEMA) ? "\n" + inputSchemaDigest(template.getJSONObject(CAPABILITY_SCHEMA)) : "")
                    + (template.containsKey(NativeEmptyReadArguments.FIELD) ? "\n" + template.getString(NativeEmptyReadArguments.FIELD) : "")
                    + (template.containsKey(NativeHostNodeInput.FIELD) ? "\n" + NativeHostNodeInput.digest(template.getJSONObject(NativeHostNodeInput.FIELD)) : "")
                    + (template.containsKey(NativeHostNodeInput.DEFAULTS) ? "\n" + NativeHostNodeInput.DEFAULTS_VERSION : "")
                    + (template.containsKey(NativeHostTargetInput.FIELD) ? "\n" + NativeHostTargetInput.digest(template.getJSONObject(NativeHostTargetInput.FIELD)) : "")).substring(0, 32);
            expectedAlias = NativeExecutableNames.name(action, toolCode, template.getString("stableStepId"),
                    expectedAlias, NativeExecutableNames.VERSION.equals(bindings.envelope().getString("functionNameVersion")));
            if (!Objects.equals(call.getName(), expectedAlias)) fail("原生工具绑定已变化");
            trustedContractDigest = template.containsKey(CAPABILITY_SCHEMA)
                    ? inputSchemaDigest(template.getJSONObject(CAPABILITY_SCHEMA)) : descriptor.schemaDigest();
        }
        else if ("SKILL".equals(action))
        {
            JSONObject template = binding.getJSONObject("payloadTemplate");
            String skillCode = template == null ? null : template.getString("skillCode");
            String skillVersionId = template == null ? null : template.getString("skillVersionId");
            String packageDigest = template == null ? null : template.getString("packageDigest");
            String inputSchemaDigest = template == null ? null : template.getString("inputSchemaDigest");
            String stableStepId = template == null ? null : template.getString("stableStepId");
            String expectedAlias = "skill_" + SecureUtil.sha256(skillCode + "\n" + skillVersionId
                    + "\n" + packageDigest + "\n" + inputSchemaDigest + "\n" + stableStepId)
                    .substring(0, 32);
            expectedAlias = NativeExecutableNames.name(action, skillCode, stableStepId, expectedAlias,
                    NativeExecutableNames.VERSION.equals(bindings.envelope().getString("functionNameVersion")));
            if (!Objects.equals(call.getName(), expectedAlias)) fail("原生Skill绑定已变化");
            trustedContractDigest = inputSchemaDigest;
        }
        else
        {
            JSONObject template = binding.getJSONObject("payloadTemplate");
            trustedContractDigest = "CHAT".equals(action) && FlowScreenplayDecisionContract.bound(template)
                    ? FlowScreenplayDecisionContract.digest()
                    : "COMPLETE".equals(action) && NativeAssistantBindingPolicy.V11.equals(bindings.envelope().getString("schemaVersion"))
                    ? NativeHostCompletionInputContract.digest() : "PLAN".equals(action) && NativeAssistantBindingPolicy.hostInputs(bindings.envelope().getString("schemaVersion"))
                    ? NativeHostPlanInputContract.digest(bindings.envelope().getString("schemaVersion"),template.getJSONObject(NativeHostPlanInputContract.FIELD)) : NativeMethodResultInputContract.applies(bindings.envelope().getString("schemaVersion"), action)
                    ? NativeMethodResultInputContract.digest(action) : NativeCompletionInputContract.applies(
                    bindings.envelope().getString("schemaVersion"), action)
                    ? NativeCompletionInputContract.digest() : NativeMessageInputContract.applies(
                    bindings.envelope().getString("schemaVersion"), action)
                    ? NativeMessageInputContract.digest(action)
                    : "CHAT".equals(action)
                    ? SecureUtil.sha256(JSON.toJSONString(NativeAssistantControlContractV1.nativePayloadSchema("CHAT"),
                            JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue))
                    : "ASK".equals(action) && NativeAssistantBindingPolicy.strictInputs(bindings.envelope().getString("schemaVersion"))
                    ? NativeAssistantBindingPolicy.askInputSchemaDigest(bindings.envelope().getString("schemaVersion"))
                    : NativeAssistantControlContractV1.VALIDATION_CONTRACT_DIGEST;
        }
        String aliasDigest = SecureUtil.sha256(call.getName() + "\n" + JSON.toJSONString(binding,
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
        return new VerifiedNativeCall(binding, action, descriptor,
                trustedContractDigest, aliasDigest);
    }

    private NativeRejectedSourceIdentity rejectedSourceIdentity(
            AssistantLoopModelResult result,
            MediaTextGenerateRequest.TextMessageItem toolMessage,
            TrustedNativeBindings bindings,
            VerifiedNativeCall call)
    {
        return new NativeRejectedSourceIdentity(result.getRunId(), result.getMediaTaskId(),
                result.getObservedRunRevision(), result.getObservedPlanRevision(),
                toolMessageDigest(toolMessage), bindings.digest(), call.aliasDigest());
    }

    private String toolMessageDigest(MediaTextGenerateRequest.TextMessageItem message)
    {
        JSONObject identity = new JSONObject();
        identity.put("role", message.getRole());
        identity.put("prefix", message.getPrefix());
        JSONArray calls = new JSONArray();
        for (MediaTextGenerateRequest.TextToolCall call : message.getToolCalls())
        {
            JSONObject value = new JSONObject();
            value.put("id", call.getId());
            value.put("name", call.getName());
            value.put("arguments", call.getArguments());
            calls.add(value);
        }
        identity.put("toolCalls", calls);
        identity.put("toolCallId", message.getToolCallId());
        identity.put("responseId", message.getResponseId());
        identity.put("toolError", message.getToolError());
        // This identity is deliberately narrower than the live turn: visible content has its own
        // persisted digest, while reasoning, parts and provider response items never authorize a
        // correction. Explicit nulls keep every durable archive field in the same fixed shape.
        return SecureUtil.sha256(JSON.toJSONString(identity,
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    private JSONObject decisionArguments(String value, String action, String trustedContractDigest,
                                         NativeRejectedSourceIdentity sourceIdentity)
    {
        Object parsed;
        try
        {
            if (value.isBlank()) throw new IllegalArgumentException("Empty arguments");
            parsed = JSON.parse(value);
        }
        catch (RuntimeException invalid)
        {
            if ("SKILL".equals(action) || "FAIL".equals(action))
            {
                fail("SKILL".equals(action) ? "原生Skill参数无效" : "原生工具参数无效");
                return null;
            }
            throw NativeDecisionValidationAdapter.argumentsJsonInvalid(
                    action, trustedContractDigest, sourceIdentity);
        }
        if (parsed instanceof JSONObject object) return object;
        if ("SKILL".equals(action))
        {
            fail("原生Skill参数无效");
            return null;
        }
        throw NativeDecisionValidationAdapter.argumentsObjectRequired(
                action, trustedContractDigest, parsed, sourceIdentity);
    }

    private boolean validCall(MediaTextGenerateRequest.TextToolCall call)
    {
        return call != null && call.getId() != null
                && call.getId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && call.getName() != null && call.getName().matches("[A-Za-z0-9_-]{1,64}")
                && call.getArguments() != null && call.getArguments().length() <= 100_000;
    }

    private boolean boundedText(Object value, int maximum)
    {
        return value instanceof String text && !text.isBlank() && text.length() <= maximum;
    }

    private Long exactLong(Object value)
    {
        if (value instanceof Integer integer) return integer.longValue();
        return value instanceof Long number ? number : null;
    }

    private JSONObject envelope(AssistantLoopModelResult result, String decisionId,
                                String action, JSONObject payload)
    {
        JSONObject value = new JSONObject();
        value.put("protocolVersion", PROTOCOL_VERSION);
        value.put("decisionId", decisionId);
        value.put("observedRunRevision", result.getObservedRunRevision());
        value.put("observedPlanRevision", result.getObservedPlanRevision());
        value.put("action", action);
        for (String field : List.of("chat", "ask", "plan", "tool", "skill",
                "checkpoint", "complete", "fail")) value.put(field, null);
        value.put(action.toLowerCase(), payload);
        return value;
    }

    private void addControl(JSONArray definitions, JSONArray bindings, String name,
                            String action, String description, JSONObject schema,
                            JSONObject payloadTemplate, String bindingSchemaVersion)
    {
        if ("CHAT".equals(action) && FlowScreenplayDecisionContract.bound(payloadTemplate)) {
            schema = FlowScreenplayDecisionContract.schema();
            description = "结束当前单集剧本回复。先在正文流中写出完整内容，再以 outcome 标明回复、设计或草稿；正文不放在函数参数中。";
        }
        else if (NativeAskVisibleContextContract.applies(bindingSchemaVersion, action)) {
            schema = NativeAskVisibleContextContract.schema(bindingSchemaVersion);
            description = "向用户提问或请求确认。message必须直接包含实际供审阅的Markdown正文；问题引用的方案、数值、条件应在正文完整展示，不能只声称已展示。"
                    + "SINGLE且allowFreeText=true时minSelections设为0，required=true仍要求选项或非空自由回答；多选的真实最少选择数继续保留。"
                    + "模型不得代替用户选择答案或授权执行；服务端在显示问题前持久化正文。";
        }
        else if ("PLAN".equals(action) && NativeAssistantBindingPolicy.hostInputs(bindingSchemaVersion))
        {
            schema = NativeHostPlanInputContract.schema(bindingSchemaVersion,payloadTemplate.getJSONObject(NativeHostPlanInputContract.FIELD));
            description += "只提供业务步骤、真实依赖和交付要求；位置、执行器、绑定版本、操作类型、重试与审批范围由宿主从固定目录生成。";
        }
        else if ("COMPLETE".equals(action) && NativeAssistantBindingPolicy.V11.equals(bindingSchemaVersion))
        {
            schema = NativeHostCompletionInputContract.schema(
                    payloadTemplate.getJSONObject(NativeHostCompletionInputContract.FIELD));
            description = "交付完整正文及当前批准条件对应的真实证据。条件类别由程序从计划绑定，技术完成由原任务、归档与保存回执核验；创作质量和内容满足度仍须明确判断并提供评审证据。";
        }
        else if (NativeMethodResultInputContract.applies(bindingSchemaVersion, action))
        {
            schema = NativeMethodResultInputContract.schema(action);
            description = NativeMethodResultInputContract.instruction();
        }
        else if (NativeCompletionInputContract.applies(bindingSchemaVersion, action))
        {
            schema = NativeCompletionInputContract.schema();
            description = "完成当前任务。message直接填写Markdown正文；每项completionCriteriaResults只声明一次真实evidenceRefs。"
                    + "TARGET_WRITE使用runtime-step:*，其他条件使用artifact:*。通常省略顶层artifactRefs与targetWriteEvidenceRefs，"
                    + "服务端按条件推导汇总并核对真实权限、当前计划与完成证据；不能把保存成功当作媒体归档或创作质量通过。";
        }
        else if (NativeMessageInputContract.applies(bindingSchemaVersion, action))
        {
            schema = NativeMessageInputContract.schema(action);
            description = description.replace("message必须是包含format（MARKDOWN）和content（正文）的对象，不得直接传字符串。",
                    "message直接填写Markdown正文字符串，不要构造或序列化format/content对象。");
            if ("COMPLETE".equals(action)) description += " message直接填写Markdown正文字符串；完成证据字段仍须真实且完整。";
        }
        addDefinition(definitions, name, description, schema);
        JSONObject binding = new JSONObject();
        binding.put("name", name);
        binding.put("action", action);
        binding.put("payloadTemplate", payloadTemplate);
        if ("ASK".equals(action) && NativeAssistantBindingPolicy.strictInputs(bindingSchemaVersion))
            binding.put("controlInputSchemaDigest", NativeAssistantBindingPolicy.askInputSchemaDigest(bindingSchemaVersion));
        bindings.add(binding);
    }

    private void addDefinition(JSONArray definitions, String name, String description, JSONObject schema)
    {
        JSONObject definition = new JSONObject();
        definition.put("name", name);
        definition.put("description", description);
        definition.put("inputSchema", schema);
        definitions.add(definition);
    }

    private boolean dependenciesComplete(JSONArray dependencies, Map<String, JSONObject> prior)
    {
        if (dependencies == null) return true;
        for (int index = 0; index < dependencies.size(); index++)
        {
            String dependency = dependencies.getString(index);
            JSONObject receipt = prior.get(dependency);
            if (receipt == null || !"SUCCEEDED".equals(receipt.getString("status"))) return false;
        }
        return true;
    }

    private Map<String, JSONObject> indexed(JSONArray values, String field)
    {
        if (values == null) return Map.of();
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++)
        {
            JSONObject value = values.getJSONObject(index);
            String key = value == null ? null : value.getString(field);
            if (StrUtil.isBlank(key) || result.putIfAbsent(key, value) != null)
                fail("原生目录身份无效");
        }
        return result;
    }

    /** Prior READ observations may contain several attempts for one stable step; execution uses the newest receipt. */
    private Map<String, JSONObject> indexedPriorResults(JSONArray values, Long expectedPlanRevision)
    {
        if (values == null) return Map.of();
        Map<String, JSONObject> result = new LinkedHashMap<>();
        Map<String, Integer> attempts = new HashMap<>();
        Map<String, Long> stepIdsByStable = new HashMap<>();
        Set<Long> stepIds = new HashSet<>();
        for (int index = 0; index < values.size(); index++)
        {
            JSONObject value = values.getJSONObject(index);
            String stableStepId = value == null ? null : value.getString("stableStepId");
            Integer attemptNo = value == null ? null : value.getInteger("attemptNo");
            Long planRevision = value == null ? null : value.getLong("planRevision");
            Long stepId = positiveId(value == null ? null : value.getString("stepId"));
            Integer previous = stableStepId == null ? null : attempts.get(stableStepId);
            if (StrUtil.isBlank(stableStepId) || stableStepId.length() > 64
                    || attemptNo == null || attemptNo < 1
                    || !Objects.equals(expectedPlanRevision, planRevision) || stepId == null
                    || !stepIds.add(stepId)
                    || previous != null && (attemptNo <= previous
                    || stepId <= stepIdsByStable.get(stableStepId)))
                fail("原生历史步骤身份无效");
            JSONObject previousValue = result.get(stableStepId);
            if (previousValue != null && (!repeatableRead(previousValue) || !repeatableRead(value)
                    || !Objects.equals(previousValue.getString("toolCode"), value.getString("toolCode"))))
                fail("原生历史步骤重复无效");
            attempts.put(stableStepId, attemptNo);
            stepIdsByStable.put(stableStepId, stepId);
            result.put(stableStepId, value);
        }
        return result;
    }

    private boolean repeatableRead(JSONObject value)
    {
        if (value == null || !"TOOL".equals(value.getString("executorKind"))
                || StrUtil.isBlank(value.getString("toolCode"))) return false;
        RuntimeToolRegistry.Descriptor descriptor = toolRegistry.require(value.getString("toolCode"));
        return "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable();
    }

    private Long positiveId(String value)
    {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private JSONArray copy(JSONArray value)
    {
        return value == null ? new JSONArray() : new JSONArray(value);
    }

    private void requireContext(AssistantLoopExecutionContext context)
    {
        if (context == null || context.getRunId() == null || context.getStateRevision() == null
                || context.getPlanRevision() == null || context.getIteration() == null
                || context.getIteration() < 1 || !PROTOCOL_VERSION.equals(context.getProtocolVersion())
                || StrUtil.isBlank(context.getRequestJson())) fail("原生循环输入无效");
        requireBinding(context.getBinding());
    }

    private void requireBinding(AssistantLoopBinding binding)
    {
        if (binding == null || binding.getAgentId() == null || binding.getAgentVersionId() == null
                || !PROTOCOL_VERSION.equals(binding.getProtocolVersion())
                || StrUtil.isBlank(binding.getVersionCode()) || StrUtil.isBlank(binding.getPackageDigest())
                || StrUtil.isBlank(binding.getInstruction()) || StrUtil.isBlank(binding.getDefinitionJson()))
            fail("原生Agent绑定无效");
        JSONObject definition = object(binding.getDefinitionJson(), "原生Agent定义无效");
        JSONObject policy = definition.getJSONObject("responsePolicy");
        if (policy == null || !RESPONSE_MODE.equals(policy.getString("mode"))
                || !Objects.equals(0, policy.getInteger("maxRepairAttempts")))
            fail("原生响应策略无效");
        NativeAssistantBindingPolicy.schemaVersion(binding);
    }

    private String decisionId(AssistantLoopModelResult result, String kind, String identity)
    {
        return "native-" + SecureUtil.sha256(result.getMediaTaskId() + "\n" + kind + "\n" + identity)
                .substring(0, 56);
    }

    private JSONObject object(String json, String message)
    {
        try
        {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        }
        catch (RuntimeException invalid)
        {
            fail(message);
            return null;
        }
    }

    private static String truncate(String value, int maximum)
    {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static void fail(String message)
    {
        throw new ServiceException(message);
    }

    private record NativeCatalog(JSONObject tools, JSONObject bindings, String planningInstruction) { }

    private record TrustedNativeBindings(JSONObject envelope, Map<String, JSONObject> values,
                                         String digest) { }

    private record VerifiedNativeCall(JSONObject binding, String action,
                                      RuntimeToolRegistry.Descriptor descriptor,
                                      String trustedContractDigest, String aliasDigest) { }

    private record ExecutableCandidate(String alias, String action, String description, JSONObject schema,
                                       JSONObject payload) { }
}
