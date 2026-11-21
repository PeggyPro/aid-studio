package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Resolves already-produced business text, never execution authority or a new Skill invocation. */
@Component
public class AssistantSkillResultTextResolver {
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidSkillRunMapper skillRuns;
    @Resource private AssistantPlanExecutionScopeCodec scopeCodec;

    /** Classify only known source validation failures before any target side effect exists. */
    public ResolvedText resolveForPreparation(RuntimeToolInvocation invocation, Long sourceStepId,
                                               String resultPointer, int maxChars) {
        try {
            ResolvedText resolved = resolve(invocation, sourceStepId, resultPointer, maxChars);
            if (resolved.text().isBlank()) throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_TEXT_EMPTY);
            return resolved;
        } catch (ServiceException invalid) {
            var reason = preparationReason(invalid.getMessage());
            if (reason == null) throw invalid;
            throw new RuntimeToolPreparationRejectedException(reason, invalid);
        }
    }

    private static RuntimeToolPreparationRejectedException.Reason preparationReason(String code) {
        if (code == null) return null;
        return switch (code) {
            case "SKILL_RESULT_TEXT_POINTER_INVALID" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_POINTER_INVALID;
            case "SKILL_RESULT_TEXT_NOT_FOUND" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_TEXT_NOT_FOUND;
            case "SKILL_RESULT_TEXT_NOT_STRING" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_NOT_TEXT;
            case "SKILL_RESULT_TEXT_TOO_LARGE" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_TEXT_TOO_LARGE;
            case "SKILL_RESULT_TEXT_SOURCE_FORBIDDEN" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_REFERENCE_FORBIDDEN;
            case "SKILL_RESULT_TEXT_SOURCE_NOT_READY" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_REFERENCE_NOT_READY;
            case "SKILL_RESULT_TEXT_SOURCE_APPROVAL_CHANGED", "SKILL_RESULT_TEXT_SOURCE_CHILD_CHANGED",
                    "SKILL_RESULT_TEXT_SOURCE_RESULT_CHANGED" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_REFERENCE_CHANGED;
            case "SKILL_RESULT_TEXT_SOURCE_INVALID", "SKILL_RESULT_TEXT_SOURCE_APPROVAL_INVALID",
                    "SKILL_RESULT_TEXT_SOURCE_CHILD_INVALID" -> RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_REFERENCE_INVALID;
            default -> null;
        };
    }

    public ResolvedText resolve(RuntimeToolInvocation invocation, Long sourceStepId, String resultPointer,
                                int maxChars) {
        ResolvedResult source = resolveResult(invocation, sourceStepId);
        String text;
        try { text = SkillResultTextProjection.select(source.result(), resultPointer, maxChars); }
        catch (IllegalArgumentException invalid) { throw new ServiceException(invalid.getMessage()); }
        return new ResolvedText(text, source.sourceRunId(), source.sourceStepId(), source.skillRunId(),
                resultPointer, source.sourceOutputDigest(), SecureUtil.sha256(text));
    }

    /** Discovery and saving share the same source proof; discovery grants no new execution authority. */
    public ResolvedResult resolveResult(RuntimeToolInvocation invocation, Long sourceStepId) {
        require(invocation != null && positive(invocation.getRunId()) && positive(invocation.getUserId())
                && positive(invocation.getPlanRevision()) && positive(sourceStepId), "SOURCE_INVALID");
        AidRuntimeRun current = runs.selectById(invocation.getRunId());
        require(current != null && "0".equals(current.getDelFlag())
                && Objects.equals(current.getUserId(), invocation.getUserId())
                && Objects.equals(current.getPlanRevision(), invocation.getPlanRevision())
                && positive(current.getSessionId()), "SOURCE_FORBIDDEN");
        var source = steps.selectById(sourceStepId);
        require(source != null && "0".equals(source.getDelFlag()) && "SUCCEEDED".equals(source.getStatus())
                && "SKILL".equals(source.getStepKind())
                && "SKILL_ORCHESTRATOR".equals(source.getChildRunKind())
                && positive(source.getChildRunId()) && positive(source.getSkillVersionId())
                && positive(source.getApprovalId()) && positive(source.getPlanRevision()), "SOURCE_NOT_READY");
        AidRuntimeRun owner = runs.selectById(source.getRunId());
        require(owner != null && "0".equals(owner.getDelFlag())
                && Objects.equals(owner.getUserId(), current.getUserId())
                && Objects.equals(owner.getSessionId(), current.getSessionId())
                && Objects.equals(owner.getProjectId(), current.getProjectId())
                && Objects.equals(owner.getWorkspaceId(), current.getWorkspaceId())
                && positive(owner.getPlanRevision()) && source.getPlanRevision() <= owner.getPlanRevision(),
                "SOURCE_FORBIDDEN");
        if (Objects.equals(owner.getId(), current.getId())) {
            require(source.getPlanRevision() <= invocation.getPlanRevision(), "SOURCE_FORBIDDEN");
        }
        var approval = approvals.selectById(source.getApprovalId());
        require(approval != null && "0".equals(approval.getDelFlag())
                && Objects.equals(approval.getUserId(), current.getUserId())
                && Objects.equals(approval.getRunId(), owner.getId())
                && Objects.equals(approval.getPlanRevision(), source.getPlanRevision())
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && Set.of("ACCEPTED", "CONSUMED").contains(approval.getStatus()), "SOURCE_APPROVAL_INVALID");
        var scope = scopeCodec.decode(approval.getScopeJson());
        require(!scope.isTool() && scope.skill() != null
                && Objects.equals(scope.digest(), approval.getScopeDigest())
                && Objects.equals(scope.requestDigest(), approval.getRequestDigest())
                && Objects.equals(scope.context().getString("digest"), approval.getContextDigest())
                && Objects.equals(scope.decision().getLong("observedPlanRevision"), source.getPlanRevision())
                && Objects.equals(scope.plan().getString("definitionDigest"), approval.getPlanDigest())
                && Objects.equals(scope.plan().getString("stableStepId"), source.getStableStepId())
                && Objects.equals(scope.plan().getInteger("position"), source.getPlanPosition())
                && "SKILL".equals(scope.plan().getString("executorKind"))
                && Objects.equals(scope.skill().getLong("skillVersionId"), source.getSkillVersionId()),
                "SOURCE_APPROVAL_CHANGED");
        var child = skillRuns.selectById(source.getChildRunId());
        require(child != null && "0".equals(child.getDelFlag()) && "SUCCEEDED".equals(child.getStatus())
                && Objects.equals(child.getUserId(), current.getUserId())
                && Objects.equals(child.getSkillVersionId(), source.getSkillVersionId()), "SOURCE_CHILD_INVALID");
        JSONObject input = object(child.getInputJson());
        JSONObject envelope = input.getJSONObject("assistantBudgetEnvelope");
        require(envelope != null
                && Objects.equals(envelope.getLong("assistantRuntimeRunId"), owner.getId())
                && Objects.equals(envelope.getLong("assistantParentStepId"), source.getId())
                && Objects.equals(envelope.getString("assistantOuterStableStepKey"), source.getStableStepId())
                && Objects.equals(envelope.getInteger("assistantAttemptNo"), source.getAttemptNo())
                && Objects.equals(envelope.getLong("payerUserId"), current.getUserId())
                && Objects.equals(envelope.getLong("approvalId"), approval.getId())
                && Objects.equals(envelope.getLong("skillVersionId"), source.getSkillVersionId())
                && Objects.equals(envelope.getString("packageDigest"), scope.skill().getString("packageDigest"))
                && Objects.equals(envelope.getString("approvedScopeDigest"), approval.getScopeDigest()),
                "SOURCE_CHILD_CHANGED");
        JSONObject output = object(child.getOutputJson());
        JSONObject receipt = object(source.getOutputJson());
        require("METHOD_OBSERVATION".equals(output.getString("responseMode"))
                && "SKILL_METHOD_OBSERVATION".equals(output.getString("responseKind"))
                && "assistant-skill-observation/v1".equals(receipt.getString("schemaVersion"))
                && "SUCCEEDED".equals(receipt.getString("status"))
                && Objects.equals(receipt.getLong("skillRunId"), child.getId())
                && Objects.equals(receipt.getLong("skillVersionId"), child.getSkillVersionId())
                && Objects.equals(receipt.getString("skillCode"), scope.plan().getString("bindingCode"))
                && output.get("assistantMessage") instanceof String
                && Objects.equals(receipt.get("observation"), output.get("assistantMessage"))
                && output.containsKey("result"), "SOURCE_RESULT_CHANGED");
        return new ResolvedResult(output.get("result"), owner.getId(), source.getId(), child.getId(),
                scope.plan().getString("bindingCode"), source.getSkillVersionId(), owner.getStatus(),
                SecureUtil.sha256(child.getOutputJson()));
    }

    private static JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            require(value != null, "SOURCE_INVALID");
            return value;
        } catch (RuntimeException invalid) {
            throw new ServiceException("SKILL_RESULT_TEXT_SOURCE_INVALID");
        }
    }

    private static boolean positive(Long id) { return id != null && id > 0; }
    private static void require(boolean valid, String code) {
        if (!valid) throw new ServiceException("SKILL_RESULT_TEXT_" + code);
    }

    public record ResolvedResult(Object result, Long sourceRunId, Long sourceStepId, Long skillRunId,
                                  String skillCode, Long skillVersionId, String sourceRunStatus,
                                  String sourceOutputDigest) { }

    public record ResolvedText(String text, Long sourceRunId, Long sourceStepId, Long skillRunId,
                               String resultPointer, String sourceOutputDigest, String textDigest) { }
}
