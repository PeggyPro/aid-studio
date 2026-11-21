package com.aid.runtime.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.runtime.assistant.AssistantApprovedSkillBindingProofService;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts one explicit, bounded handoff from the universal Agent to a bound specialist Agent. */
@Component
public class AssistantAgentHandoffRuntimeToolProvider implements RuntimeToolProvider
{
    public static final String HANDOFF = "aid.agent.handoff";
    private static final String VERSION = "1.0.0";
    private static final String INPUT = """
            {"type":"object","required":["targetModule","mode","objective"],"additionalProperties":false,"properties":{
              "targetModule":{"const":"SCREENWRITER"},
              "mode":{"enum":["CRAFT","ADAPT"]},
              "objective":{"type":"string","minLength":1,"maxLength":10000}
            }}""";
    private static final String OUTPUT = """
            {"type":"object","required":["targetModule","targetAgentCode","targetAgentVersionId","targetPackageDigest","selectedSkillCode","sessionId","runId","status","nextAction","sourceRunId","sourceContextDigest"],"additionalProperties":false,"properties":{
              "targetModule":{"type":"string"},"targetAgentCode":{"type":"string"},
              "targetAgentVersionId":{"type":"integer"},"targetPackageDigest":{"type":"string"},
              "selectedSkillCode":{"type":"string"},"sessionId":{"type":"integer"},
              "runId":{"type":"integer"},"status":{"type":"string"},"nextAction":{"type":["string","null"]},
              "sourceRunId":{"type":"integer"},"sourceContextDigest":{"type":"string"}
            }}""";

    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;
    private final AssistantModuleAgentResolver moduleAgents;
    private final AssistantApprovedSkillBindingProofService approvedPlans;
    private final ObjectProvider<AssistantCoreService> assistantCore;
    private final RuntimeOperationSubmissionGuard submissionGuard;

    public AssistantAgentHandoffRuntimeToolProvider(
            AidRuntimeRunMapper runs,
            AidAssistantSessionMapper sessions,
            AssistantModuleAgentResolver moduleAgents,
            AssistantApprovedSkillBindingProofService approvedPlans,
            ObjectProvider<AssistantCoreService> assistantCore,
            RuntimeOperationSubmissionGuard submissionGuard)
    {
        this.runs = runs;
        this.sessions = sessions;
        this.moduleAgents = moduleAgents;
        this.approvedPlans = approvedPlans;
        this.assistantCore = assistantCore;
        this.submissionGuard = submissionGuard;
    }

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors()
    {
        return List.of(new RuntimeToolRegistry.Descriptor(HANDOFF, VERSION,
                SecureUtil.sha256(INPUT + "\n" + OUTPUT), "Handoff to specialist Agent",
                "Create one durable screenwriter conversation from this universal run. The target is an administrator-bound immutable Agent version; the handoff preserves the authorized project, Canvas and reference scope, creates no media call, and cannot recursively hand off again. Spatial previs is not advertised until its runtime engine is available.",
                INPUT, OUTPUT, "DATABASE_WRITE", false, "REQUEST_DIGEST", true, "RECONCILE_RECEIPT",
                List.of("agent.handoff")));
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor)
    {
        if (context == null || context.getUserId() == null || context.getUserId() <= 0
                || !"UNIVERSAL".equals(context.getModuleCode()))
            return RuntimeToolAvailability.unavailable("UNIVERSAL_AGENT_HANDOFF_REQUIRED");
        try
        {
            if (moduleAgents.findAvailableActive("SCREENWRITER").isEmpty())
                return RuntimeToolAvailability.unavailable("SCREENWRITER_AGENT_UNAVAILABLE");
        }
        catch (RuntimeException unavailable)
        {
            return RuntimeToolAvailability.unavailable("SCREENWRITER_AGENT_UNAVAILABLE");
        }
        return RuntimeToolAvailability.enabled();
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor)
    {
        return OutputReferenceKind.OBSERVATION;
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation)
    {
        try
        {
            requireInvocation(invocation);
        }
        catch (ServiceException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IDENTITY_CHANGED,
                    invalid);
        }
        JSONObject arguments;
        try
        {
            arguments = object(invocation.getArgumentsJson(), "Agent交接参数无效");
        }
        catch (ServiceException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        String targetModule = arguments.getString("targetModule");
        String selectedSkillCode;
        try
        {
            selectedSkillCode = selectedSkillCode(targetModule, StrUtil.trim(arguments.getString("mode")));
        }
        catch (ServiceException invalid)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        AssistantLoopBinding target;
        try
        {
            target = moduleAgents.requireActiveForToolPreparation(targetModule);
        }
        catch (AssistantModuleAgentResolver.ActiveBindingRejectedException unavailable)
        {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED,
                    unavailable);
        }
        if (target == null || target.getAgentVersionId() == null
                || StrUtil.isBlank(target.getAgentCode()) || StrUtil.isBlank(target.getPackageDigest()))
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
        JSONObject snapshot = new JSONObject();
        snapshot.put("targetAgentCode", target.getAgentCode());
        snapshot.put("targetAgentVersionId", target.getAgentVersionId());
        snapshot.put("targetPackageDigest", target.getPackageDigest());
        snapshot.put("selectedSkillCode", selectedSkillCode);
        arguments.put("_handoffTarget", snapshot);
        return new RuntimeToolPreparation(arguments.toJSONString(), null, invocation.getOperationScope());
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

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation)
    {
        throw new ServiceException("AGENT_HANDOFF_PREPARED_EXECUTION_REQUIRED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext)
    {
        if (prepared == null || billingBudgetContext != null)
            throw new ServiceException("AGENT_HANDOFF_PREPARED_IDENTITY_INVALID");
        requirePreparedIdentity(prepared);
        // Claim failures are intentionally not relabelled as NOT_ENTERED. The guard may reject
        // an identity that already belongs to a durable operation; only a confirmed new claim
        // lets this transaction prove that a later local failure rolled every child write back.
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim(prepared));
        // An earlier accepted claim may already own a durable child session/run. Receipt lookup
        // failures must never be relabelled as NOT_ENTERED, which would authorize a fresh write.
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY)
            return durableReceipt(prepared);
        try
        {
            return executeClaimed(invocation(prepared));
        }
        catch (MediaSubmissionNotEnteredException failure)
        {
            throw failure;
        }
        catch (RuntimeException failure)
        {
            // The new claim and all child writes share this local transaction; rollback proves
            // that no child session/run was durably accepted for this first attempt.
            throw new MediaSubmissionNotEnteredException("AGENT_HANDOFF_NOT_ENTERED", failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared)
    {
        return durableReceipt(prepared);
    }

    private RuntimeToolResult executeClaimed(RuntimeToolInvocation invocation)
    {
        requireInvocation(invocation);
        AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved =
                approvedPlans.requirePersistedApproved(invocation.getRunId(), invocation.getUserId(),
                        invocation.getPlanRevision());
        AidRuntimeRun sourceRun = approved.run();
        AidAssistantSession sourceSession = sessions.selectOne(
                new LambdaQueryWrapper<AidAssistantSession>()
                        .eq(AidAssistantSession::getId, sourceRun.getSessionId())
                        .eq(AidAssistantSession::getUserId, invocation.getUserId())
                        .eq(AidAssistantSession::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
        if (sourceSession == null || !"UNIVERSAL".equals(sourceSession.getModuleCode())
                || !Objects.equals(sourceRun.getVersionId(), approved.plan().getAgentVersionId())
                || !Objects.equals(sourceRun.getContextDigest(), approved.plan().getInputContextDigest()))
            throw new ServiceException("Agent交接来源无效");
        AssistantLoopBinding sourceBinding = moduleAgents.requirePinned(sourceSession.getModuleCode(),
                sourceRun.getVersionId(), sourceRun.getVersionDigest());
        if (!"universal_canvas_agent".equals(sourceBinding.getAgentCode()))
            throw new ServiceException("旧Agent运行不能创建新交接");

        JSONObject arguments = object(invocation.getArgumentsJson(), "Agent交接参数无效");
        String targetModule = arguments.getString("targetModule");
        String objective = StrUtil.trim(arguments.getString("objective"));
        String mode = StrUtil.trim(arguments.getString("mode"));
        String selectedSkillCode;
        if ("SCREENWRITER".equals(targetModule))
        {
            if (!List.of("CRAFT", "ADAPT").contains(mode))
                throw new ServiceException("编剧交接模式无效");
            selectedSkillCode = "CRAFT".equals(mode) ? "story_creation_flow" : "story_adaptation_flow";
        }
        else throw new ServiceException("Agent交接目标无效");
        if (StrUtil.isBlank(objective) || objective.length() > 10000)
            throw new ServiceException("Agent交接目标无效");

        JSONObject targetSnapshot = arguments.getJSONObject("_handoffTarget");
        AssistantLoopBinding targetBinding = moduleAgents.requireActive(targetModule);
        if (targetBinding == null || targetBinding.getAgentVersionId() == null
                || StrUtil.isBlank(targetBinding.getPackageDigest()) || targetSnapshot == null
                || !Objects.equals(targetBinding.getAgentCode(), targetSnapshot.getString("targetAgentCode"))
                || !Objects.equals(targetBinding.getAgentVersionId(),
                targetSnapshot.getLong("targetAgentVersionId"))
                || !Objects.equals(targetBinding.getPackageDigest(),
                targetSnapshot.getString("targetPackageDigest"))
                || !Objects.equals(selectedSkillCode, targetSnapshot.getString("selectedSkillCode")))
            throw new ServiceException("目标Agent当前不可用");

        String operationIdentity = operationIdentity(invocation.getUserId(), invocation.getRunId(),
                invocation.getPlanRevision(), invocation.getStableStepId(), invocation.getIdempotencyKey(),
                invocation.getArgumentsDigest());
        AssistantRequests.SessionOpen open = new AssistantRequests.SessionOpen();
        open.setClientSessionId("asst:handoff-" + operationIdentity.substring(0, 40));
        open.setModuleCode(targetModule); open.setMode(mode);
        open.setProjectId(zeroToNull(sourceSession.getProjectId()));
        open.setEpisodeId(zeroToNull(sourceSession.getEpisodeId()));
        open.setWorkspaceId(sourceSession.getWorkspaceId());
        open.setNodeObjectId(sourceSession.getNodeObjectId());
        open.setTitle("编剧智能体交接");
        open.setReasoningEnabled(sourceSession.getReasoningEnabled());
        open.setReasoningLevel(sourceSession.getReasoningLevel());
        open.setShowReasoning(sourceSession.getShowReasoning());
        AssistantCoreService core = assistantCore.getObject();
        AssistantViews.Session child = core.openSession(invocation.getUserId(), open);
        Long childSessionId = positiveLong(child.getSessionId(), "Agent交接会话无效");
        bindParentIdentity(childSessionId, invocation, sourceRun, targetBinding, operationIdentity);

        AssistantRequests.MessageSend send = new AssistantRequests.MessageSend();
        send.setSessionId(childSessionId);
        send.setClientRequestId("handoff-" + operationIdentity.substring(0, 48));
        // A handoff owns a dedicated idempotent session. The first message always targets revision zero;
        // replay is resolved by the same command identity before revision validation.
        send.setExpectedSessionRevision(0L);
        send.setExecutionPreference("MANUAL");
        send.setSelectedSkillCode(selectedSkillCode);
        AssistantRequests.MessagePart part = new AssistantRequests.MessagePart();
        part.setType("TEXT");
        part.setText("来自全能画布助手的正式交接。请基于随消息固定的授权上下文完成以下目标：\n\n" + objective);
        send.setParts(List.of(part));
        send.setContextRefs(sourceContextRefs(sourceRun));
        AssistantViews.MessageSent sent = core.sendMessage(invocation.getUserId(), send);
        Long childRunId = positiveLong(sent.getRunId(), "Agent交接运行无效");
        AidRuntimeRun childRun = runs.selectById(childRunId);
        if (childRun == null || !Objects.equals(childRun.getUserId(), invocation.getUserId())
                || !Objects.equals(childRun.getSessionId(), childSessionId)
                || !Objects.equals(childRun.getVersionId(), targetBinding.getAgentVersionId())
                || !Objects.equals(childRun.getVersionDigest(), targetBinding.getPackageDigest()))
            throw new ServiceException("目标Agent固定版本已变化");

        JSONObject output = new JSONObject();
        output.put("targetModule", targetModule);
        output.put("targetAgentCode", targetBinding.getAgentCode());
        output.put("targetAgentVersionId", targetBinding.getAgentVersionId());
        output.put("targetPackageDigest", targetBinding.getPackageDigest());
        output.put("selectedSkillCode", selectedSkillCode);
        output.put("sessionId", childSessionId); output.put("runId", childRunId);
        output.put("status", sent.getStatus()); output.put("nextAction", sent.getNextAction());
        output.put("sourceRunId", sourceRun.getId());
        output.put("sourceContextDigest", sourceRun.getContextDigest());
        return RuntimeToolResult.succeeded(output.toJSONString());
    }

    private RuntimeToolResult durableReceipt(RuntimeToolPreparedInvocation prepared)
    {
        requirePreparedIdentity(prepared);
        String identity = operationIdentity(prepared.getUserId(), prepared.getRunId(),
                prepared.getPlanRevision(), prepared.getStableStepId(), prepared.getIdempotencyKey(),
                prepared.getPreparedPayloadDigest());
        JSONObject preparedArguments = object(prepared.getPreparedPayloadJson(), "Agent交接准备快照无效");
        JSONObject preparedTarget = preparedArguments.getJSONObject("_handoffTarget");
        AidAssistantSession child = sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getUserId, prepared.getUserId())
                .eq(AidAssistantSession::getClientSessionId, "asst:handoff-" + identity.substring(0, 40))
                .eq(AidAssistantSession::getSessionType, "ASSISTANT")
                .eq(AidAssistantSession::getDelFlag, "0").last("LIMIT 1"));
        if (child == null)
            return RuntimeToolResult.unknown("AGENT_HANDOFF_RECEIPT_NOT_FOUND", "Agent交接回执待核实");
        JSONObject base = object(child.getBaseInputJson(), "Agent交接回执无效");
        JSONObject handoff = base.getJSONObject("handoff");
        if (handoff == null || !Objects.equals(identity, handoff.getString("operationIdentity"))
                || preparedTarget == null
                || !Objects.equals(prepared.getRunId(), handoff.getLong("sourceRunId"))
                || !Objects.equals(preparedArguments.getString("targetModule"), child.getModuleCode())
                || !Objects.equals(preparedTarget.getString("targetAgentCode"),
                handoff.getString("targetAgentCode"))
                || !Objects.equals(preparedTarget.getLong("targetAgentVersionId"),
                handoff.getLong("targetAgentVersionId"))
                || !Objects.equals(preparedTarget.getString("targetPackageDigest"),
                handoff.getString("targetPackageDigest")))
            throw new ServiceException("AGENT_HANDOFF_RECEIPT_CONFLICT");
        AidRuntimeRun sourceRun = runs.selectById(prepared.getRunId());
        if (sourceRun == null || !Objects.equals(sourceRun.getUserId(), prepared.getUserId())
                || !Objects.equals(sourceRun.getSessionId(), handoff.getLong("sourceSessionId"))
                || !Objects.equals(sourceRun.getContextDigest(), handoff.getString("sourceContextDigest")))
            throw new ServiceException("AGENT_HANDOFF_SOURCE_CHANGED");
        AidRuntimeRun childRun = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getUserId, prepared.getUserId())
                .eq(AidRuntimeRun::getSessionId, child.getId())
                .eq(AidRuntimeRun::getClientRequestId, "handoff-" + identity.substring(0, 48))
                .eq(AidRuntimeRun::getDelFlag, "0").last("LIMIT 1"));
        if (childRun == null)
            return RuntimeToolResult.unknown("AGENT_HANDOFF_RUN_RECEIPT_NOT_FOUND", "Agent交接运行回执待核实");
        if (!Objects.equals(childRun.getVersionId(), handoff.getLong("targetAgentVersionId"))
                || !Objects.equals(childRun.getVersionDigest(), handoff.getString("targetPackageDigest")))
            throw new ServiceException("AGENT_HANDOFF_TARGET_CHANGED");
        JSONObject childRequest = object(childRun.getRequestJson(), "Agent交接运行回执无效");
        JSONObject selected = childRequest.getJSONObject("_selectedSkill");
        String selectedSkillCode = selected == null ? null : selected.getString("code");
        String expectedSkillCode = selectedSkillCode(child.getModuleCode(), child.getMode());
        if (!Objects.equals(expectedSkillCode, selectedSkillCode)
                || !Objects.equals(preparedTarget.getString("selectedSkillCode"), selectedSkillCode))
            throw new ServiceException("AGENT_HANDOFF_SKILL_CHANGED");
        JSONObject output = new JSONObject();
        output.put("targetModule", child.getModuleCode());
        output.put("targetAgentCode", handoff.getString("targetAgentCode"));
        output.put("targetAgentVersionId", handoff.getLong("targetAgentVersionId"));
        output.put("targetPackageDigest", handoff.getString("targetPackageDigest"));
        output.put("selectedSkillCode", selectedSkillCode);
        output.put("sessionId", child.getId()); output.put("runId", childRun.getId());
        output.put("status", childRun.getStatus()); output.put("nextAction", childRun.getNextAction());
        output.put("sourceRunId", sourceRun.getId());
        output.put("sourceContextDigest", sourceRun.getContextDigest());
        return RuntimeToolResult.succeeded(output.toJSONString());
    }

    private RuntimeOperationSubmissionClaim claim(RuntimeToolPreparedInvocation prepared)
    {
        RuntimeOperationSubmissionClaim claim = new RuntimeOperationSubmissionClaim();
        claim.setRunId(prepared.getRunId()); claim.setUserId(prepared.getUserId());
        claim.setStepId(prepared.getRuntimeStepId()); claim.setApprovalId(prepared.getApprovalId());
        // Submission guards compare the outer step kind; the prepared business operation
        // remains independently frozen in the approved plan and operation scope.
        claim.setOperationKind("ASSISTANT_TOOL"); claim.setIdempotencyKey(prepared.getIdempotencyKey());
        claim.setRunLease(prepared.getRunLease()); claim.setStepLease(prepared.getStepLease());
        return claim;
    }

    private RuntimeToolInvocation invocation(RuntimeToolPreparedInvocation prepared)
    {
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId()); invocation.setUserId(prepared.getUserId());
        invocation.setRuntimeStepId(prepared.getRuntimeStepId());
        invocation.setPlanRevision(prepared.getPlanRevision());
        invocation.setStableStepId(prepared.getStableStepId()); invocation.setAttemptNo(prepared.getAttemptNo());
        invocation.setOperationKind(prepared.getOperationKind()); invocation.setToolCode(prepared.getToolCode());
        invocation.setContractVersion(prepared.getContractVersion()); invocation.setSchemaDigest(prepared.getSchemaDigest());
        invocation.setArgumentsJson(prepared.getPreparedPayloadJson());
        invocation.setArgumentsDigest(prepared.getPreparedPayloadDigest());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        invocation.setApprovalId(prepared.getApprovalId()); invocation.setApprovedScopeDigest(prepared.getApprovedScopeDigest());
        invocation.setIdempotencyKey(prepared.getIdempotencyKey()); invocation.setLease(prepared.getStepLease());
        invocation.setOperationScope(prepared.getOperationScope());
        return invocation;
    }

    private String operationIdentity(Long userId, Long runId, Long planRevision, String stableStepId,
                                     String idempotencyKey, String argumentsDigest)
    {
        return SecureUtil.sha256(userId + "\n" + runId + "\n" + planRevision + "\n"
                + stableStepId + "\n" + idempotencyKey + "\n" + argumentsDigest);
    }

    private String selectedSkillCode(String targetModule, String mode)
    {
        if ("SCREENWRITER".equals(targetModule) && "CRAFT".equals(mode)) return "story_creation_flow";
        if ("SCREENWRITER".equals(targetModule) && "ADAPT".equals(mode)) return "story_adaptation_flow";
        throw new ServiceException("AGENT_HANDOFF_TARGET_MODE_INVALID");
    }

    private void bindParentIdentity(Long childSessionId, RuntimeToolInvocation invocation,
                                    AidRuntimeRun sourceRun, AssistantLoopBinding target,
                                    String operationIdentity)
    {
        AidAssistantSession child = sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, childSessionId)
                .eq(AidAssistantSession::getUserId, invocation.getUserId())
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"));
        if (child == null) throw new ServiceException("Agent交接会话不存在");
        JSONObject base = object(child.getBaseInputJson(), "Agent交接会话无效");
        JSONObject handoff = base.getJSONObject("handoff");
        JSONObject expected = new JSONObject();
        expected.put("schemaVersion", "assistant-agent-handoff/v1");
        expected.put("operationIdentity", operationIdentity);
        expected.put("sourceSessionId", sourceRun.getSessionId());
        expected.put("sourceRunId", sourceRun.getId());
        expected.put("sourceContextDigest", sourceRun.getContextDigest());
        expected.put("targetAgentCode", target.getAgentCode());
        expected.put("targetAgentVersionId", target.getAgentVersionId());
        expected.put("targetPackageDigest", target.getPackageDigest());
        if (handoff != null && !Objects.equals(handoff, expected))
            throw new ServiceException("Agent交接幂等身份冲突");
        if (handoff == null)
        {
            base.put("handoff", expected);
            child.setBaseInputJson(base.toJSONString());
            if (sessions.updateById(child) != 1) throw new ServiceException("Agent交接会话已变化");
        }
    }

    private List<AssistantRequests.ContextRef> sourceContextRefs(AidRuntimeRun run)
    {
        JSONObject request = object(run.getRequestJson(), "Agent交接上下文无效");
        JSONArray values = request.getJSONArray("contextRefs");
        if (values == null) return List.of();
        List<AssistantRequests.ContextRef> result = values.toJavaList(AssistantRequests.ContextRef.class);
        if (result == null || result.size() > 20 || result.stream().anyMatch(Objects::isNull))
            throw new ServiceException("Agent交接上下文无效");
        return List.copyOf(new ArrayList<>(result));
    }

    private void requireInvocation(RuntimeToolInvocation invocation)
    {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null
                || invocation.getPlanRevision() == null || invocation.getRuntimeStepId() == null
                || StrUtil.isBlank(invocation.getStableStepId())
                || StrUtil.isBlank(invocation.getIdempotencyKey())
                || StrUtil.isBlank(invocation.getArgumentsJson())
                || !Objects.equals(invocation.getArgumentsDigest(), SecureUtil.sha256(invocation.getArgumentsJson()))
                || invocation.getApprovalId() == null)
            throw new ServiceException("Agent交接调用身份无效");
    }

    private void requirePreparedIdentity(RuntimeToolPreparedInvocation prepared)
    {
        if (prepared == null || prepared.getRunId() == null || prepared.getUserId() == null
                || prepared.getPlanRevision() == null || StrUtil.isBlank(prepared.getStableStepId())
                || !HANDOFF.equals(prepared.getToolCode()) || StrUtil.isBlank(prepared.getIdempotencyKey())
                || StrUtil.isBlank(prepared.getArgumentsJson())
                || !Objects.equals(prepared.getArgumentsDigest(),
                SecureUtil.sha256(prepared.getArgumentsJson()))
                || StrUtil.isBlank(prepared.getPreparedPayloadJson())
                || !Objects.equals(prepared.getPreparedPayloadDigest(),
                SecureUtil.sha256(prepared.getPreparedPayloadJson())))
            throw new ServiceException("AGENT_HANDOFF_RECEIPT_IDENTITY_INVALID");
    }

    private JSONObject object(String value, String message)
    {
        try
        {
            JSONObject result = JSON.parseObject(value);
            if (result == null) throw new IllegalArgumentException();
            return result;
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException(message);
        }
    }

    private Long positiveLong(String value, String message)
    {
        try
        {
            long result = Long.parseLong(value);
            if (result <= 0) throw new IllegalArgumentException();
            return result;
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException(message);
        }
    }

    private Long zeroToNull(Long value)
    {
        return value == null || value == 0L ? null : value;
    }
}
