package com.aid.assistant.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.aid.assistant.service.AssistantRunBudgetScopeService;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AssistantRunBudgetScopeServiceImpl implements AssistantRunBudgetScopeService
{
    private static final List<String> MEDIA = List.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "MUSIC");
    private static final List<String> AUTO_FORBIDDEN = List.of(
            "ARTIFACT_ADOPT", "CREDENTIAL_WRITE",
            "EXTERNAL_PUBLISH", "TEAM_MEMBERSHIP_WRITE");

    @Value("${assistant.conversation.budget.absolute-max-charge:5000.0000}")
    private BigDecimal conversationAbsoluteMaxCharge;
    @Value("${assistant.conversation.budget.per-call-max-charge:1000.0000}")
    private BigDecimal conversationPerCallMaxCharge;
    @Value("${assistant.conversation.budget.max-iterations:32}") private int conversationMaxIterations;
    @Value("${assistant.conversation.budget.max-operations:100}") private int conversationMaxOperations;
    @Value("${assistant.conversation.budget.ttl-seconds:3600}") private long conversationTtlSeconds;

    @Resource private AssistantModuleAgentResolver agents;
    @Resource private RuntimeToolRegistry tools;
    @Resource private AssistantRuntimeContextManifestService manifests;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleExtensions;

    @Override
    public AssistantExecutionViews.RunBudgetScope discover(Long userId, AidAssistantSession session,
                                                            AidRuntimeRun run)
    {
        AssistantLoopBinding binding = agents.requirePinned(
                session.getModuleCode(), run.getVersionId(), run.getVersionDigest());
        return discover(userId, session, run, binding);
    }

    @Override
    public AssistantExecutionViews.RunBudgetScope discover(Long userId, AidAssistantSession session,
                                                            AidRuntimeRun run, AssistantLoopBinding binding)
    {
        JSONObject definition = JSON.parseObject(binding.getDefinitionJson());
        JSONObject model = JSON.parseObject(binding.getModelConfigJson());
        List<RuntimeToolRegistry.PinnedTool> pins = new ArrayList<>();
        JSONArray toolBindings = definition == null ? null : definition.getJSONArray("toolBindings");
        if (toolBindings != null) for (int index = 0; index < toolBindings.size(); index++)
        {
            JSONObject pin = toolBindings.getJSONObject(index);
            pins.add(new RuntimeToolRegistry.PinnedTool(pin.getString("toolCode"),
                    pin.getString("contractVersion"), pin.getString("schemaDigest")));
        }
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                manifests.requirePinned(userId, session, run);
        RuntimeToolAvailabilityContext availabilityContext = new RuntimeToolAvailabilityContext();
        availabilityContext.setRunId(run.getId());
        availabilityContext.setUserId(userId); availabilityContext.setModuleCode(session.getModuleCode());
        // Zero is the persisted sentinel for an assistant without an episode project.
        // The pinned manifest and provider still validate the actual workspace/project scope.
        Long projectId = session.getProjectId();
        availabilityContext.setProjectId(Long.valueOf(0L).equals(projectId) ? null : projectId);
        availabilityContext.setWorkspaceId(session.getWorkspaceId());
        availabilityContext.setContextManifestJson(manifest.manifestJson());
        availabilityContext.setTargetRefs(JSON.parseArray(manifest.targetRefsJson()));
        availabilityContext.setBudgetDiscovery(true);
        List<RuntimeToolRegistry.BudgetOption> toolOptions = tools.availableBudgetOptions(availabilityContext, pins);
        List<String> toolCodes = toolOptions.stream().map(value -> value.descriptor().code())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.sort(toolCodes);
        List<String> models = model == null ? List.of()
                : model.getList("selectableModelCodes", String.class);
        List<String> modelCodes = models == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(models));
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("text.chat");
        JSONArray skillBindings = definition == null ? null : definition.getJSONArray("skillBindings");
        // A pinned Skill binding exposes its operation category for every agent module.
        // Execution still verifies the exact method, version, permissions and approved budget.
        if (skillBindings != null && !skillBindings.isEmpty())
        {
            capabilities.add("skill.invoke");
        }
        Set<String> media = new LinkedHashSet<>();
        media.add("TEXT");
        for (RuntimeToolRegistry.BudgetOption option : toolOptions)
        {
            modelCodes.addAll(option.offer().getModelCodes());
            capabilities.addAll(option.offer().getCapabilityCodes());
            media.addAll(option.offer().getMediaTypes());
        }
        modelCodes = new ArrayList<>(new LinkedHashSet<>(modelCodes));
        Collections.sort(modelCodes);
        List<String> capabilityCodes = new ArrayList<>(capabilities);
        List<String> mediaTypes = new ArrayList<>(media);
        Collections.sort(capabilityCodes);
        Collections.sort(mediaTypes);
        JSONObject canonical = new JSONObject();
        canonical.put("allowedToolCodes", toolCodes);
        canonical.put("allowedModelCodes", modelCodes);
        canonical.put("allowedCapabilityCodes", capabilityCodes);
        canonical.put("allowedMediaTypes", mediaTypes);
        canonical.put("targetRefs", JSON.parseArray(manifest.targetRefsJson()));
        canonical.put("automaticForbiddenOperationKinds", AUTO_FORBIDDEN);
        AssistantExecutionViews.RunBudgetScope result = new AssistantExecutionViews.RunBudgetScope();
        result.setScopeDigest(SecureUtil.sha256(canonical.toJSONString()));
        result.setAvailableModes(List.of("MANUAL", "AUTO"));
        result.setAllowedToolCodes(toolCodes);
        result.setAllowedModelCodes(modelCodes);
        result.setAllowedCapabilityCodes(capabilityCodes);
        result.setAllowedMediaTypes(mediaTypes);
        result.setTargetRefs(canonical.getJSONArray("targetRefs"));
        result.setAutomaticForbiddenOperationKinds(AUTO_FORBIDDEN);
        result.setMaxTtlSeconds(7L * 24L * 60L * 60L);
        result.setMaxIterations(1000);
        result.setMaxOperations(10000);
        result.setAmountScale(4);
        if (session.getWorkspaceId() != null
                || AssistantAutomaticTextExecution.supports(session) || moduleExtensions != null
                && moduleExtensions.supportsConversationBudget(session.getModuleCode()))
        {
            // An invalid deployment setting must fail closed rather than silently expand consent.
            boolean executionBudget = moduleExtensions == null || moduleExtensions.usesExecutionBudget(session.getModuleCode());
            BigDecimal absolute = (executionBudget ? conversationAbsoluteMaxCharge : BigDecimal.ZERO).setScale(4, java.math.RoundingMode.UNNECESSARY);
            BigDecimal perCall = (executionBudget ? conversationPerCallMaxCharge : BigDecimal.ZERO).setScale(4, java.math.RoundingMode.UNNECESSARY);
            int maxIterations = executionBudget ? conversationMaxIterations : 1;
            int maxOperations = executionBudget ? conversationMaxOperations : 1;
            long ttlSeconds = executionBudget ? conversationTtlSeconds : result.getMaxTtlSeconds();
            if (absolute.signum() < 0 || perCall.signum() < 0 || perCall.compareTo(absolute) > 0
                    || absolute.precision() > 14 || maxIterations < 1
                    || maxIterations > result.getMaxIterations() || maxOperations < 1
                    || maxOperations > result.getMaxOperations() || ttlSeconds < 60
                    || ttlSeconds > result.getMaxTtlSeconds())
                throw new IllegalStateException("Invalid assistant conversation budget configuration");
            AssistantExecutionViews.ConversationBudget defaults = new AssistantExecutionViews.ConversationBudget();
            defaults.setAbsoluteMaxCharge(absolute.toPlainString());
            defaults.setPerCallMaxCharge(perCall.toPlainString());
            defaults.setMaxIterations(maxIterations);
            defaults.setMaxOperations(maxOperations);
            defaults.setTtlSeconds(ttlSeconds);
            result.setConversationBudget(defaults);
        }
        return result;
    }
}
