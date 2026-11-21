package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;
import java.util.Set;

/** Recognizes persisted pre-submission failures for history only; never authorizes a retry. */
public final class AssistantPreTaskHistoryReceipt {
    private AssistantPreTaskHistoryReceipt() { }

    public static boolean verified(AidRuntimeRunStep main, AidRuntimeRunStep execution, JSONObject operation) {
        return verified(main, execution, operation, null);
    }

    public static boolean verified(AidRuntimeRunStep main, AidRuntimeRunStep execution, JSONObject operation,
                                   AidRuntimeApproval approval) {
        if (main == null || execution == null || operation == null
                || main.getId() == null || main.getId() <= 0 || execution.getId() == null
                || execution.getId() <= main.getId() || main.getRunId() == null
                || !Objects.equals(main.getRunId(), execution.getRunId())
                || !Objects.equals(execution.getStableStepId(), operation.getString("stableStepId"))
                || !Objects.equals(execution.getAttemptNo(), operation.getInteger("attemptNo"))
                || !Set.of("FAILED_PRETASK", "CANCELLED_PRETASK").contains(Objects.toString(execution.getStatus(), ""))
                || !Set.of("NOT_SUBMITTED", "RELEASED").contains(Objects.toString(execution.getProviderAcceptanceState(), ""))
                || execution.getMediaTaskId() != null || execution.getChildRunId() != null)
            return false;
        String digest = SecureUtil.sha256(AssistantPlanExecutionJson.canonical(operation));
        try {
            String inputDigest = digest;
            if (execution.getApprovalId() != null && "SKILL".equals(execution.getStepKind())) {
                // After approval a Skill step stores the approved business input digest.
                if (execution.getSkillVersionId() == null || execution.getSkillVersionId() <= 0
                        || !Objects.equals(execution.getSkillVersionId(), operation.getLong("skillVersionId"))
                        || !(operation.get("input") instanceof JSONObject input)) return false;
                inputDigest = SecureUtil.sha256(AssistantPlanExecutionJson.canonical(input));
            }
            if (execution.getOutputArtifactRef() != null
                    && !JSON.parseArray(execution.getOutputArtifactRef()).isEmpty()) return false;
            if (!Objects.equals(inputDigest, execution.getInputDigest()))
                return verifiedApprovedTool(main, execution, operation, approval, digest);
            // Before an approval exists the preparation writer persists a parent/digest proof.
            if (execution.getApprovalId() == null) {
                if (execution.getReservationId() != null) return false;
                JSONObject proof = JSON.parseObject(execution.getOutputJson());
                String kind = "FAILED_PRETASK".equals(execution.getStatus())
                        ? "PREPARATION_REJECTED" : "PREPARATION_CANCELLED";
                return proof != null && kind.equals(proof.getString("kind"))
                        && Objects.equals(main.getId(), proof.getLong("mainAgentStepId"))
                        && Objects.equals(digest, proof.getString("decisionDigest"));
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean verifiedApprovedTool(AidRuntimeRunStep main, AidRuntimeRunStep execution,
                                                JSONObject operation, AidRuntimeApproval approval, String decisionDigest) {
        // A prepared tool stores the request digest; its immutable quote links it to the original decision.
        boolean consumed = approval != null && "CONSUMED".equals(approval.getStatus())
                && approval.getAcceptedAt() != null;
        boolean retiredQuote = approval != null && "INVALIDATED".equals(approval.getStatus())
                && approval.getAcceptedAt() == null && execution.getReservationId() == null
                && "NOT_SUBMITTED".equals(execution.getProviderAcceptanceState())
                && "CANCELLED_PRETASK".equals(execution.getStatus())
                && "EXECUTION_MODE_AUTO_REPLAN".equals(execution.getErrorMessage());
        if (approval == null || !"TOOL".equals(execution.getStepKind())
                || !Objects.equals(execution.getToolCode(), operation.getString("toolCode"))
                || execution.getApprovalId() == null || !Objects.equals(execution.getApprovalId(), approval.getId())
                || !Objects.equals(execution.getRunId(), approval.getRunId())
                || !Objects.equals(execution.getPlanRevision(), approval.getPlanRevision())
                || !Objects.equals(execution.getAttemptNo(), approval.getPlanningAttemptNo())
                || !"0".equals(approval.getDelFlag()) || !"PLAN_EXECUTION".equals(approval.getScopeKind())
                || !(consumed || retiredQuote)
                || !Objects.equals(decisionDigest, approval.getAnswerDigest())
                || !Objects.equals(execution.getInputDigest(), approval.getRequestDigest())) return false;
        var scope = new AssistantPlanExecutionScopeCodec().decode(approval.getScopeJson());
        return scope.isTool() && Objects.equals(scope.digest(), approval.getScopeDigest())
                && Objects.equals(scope.requestDigest(), approval.getRequestDigest())
                && Objects.equals(scope.decision().getLong("mainAgentStepId"), main.getId())
                && Objects.equals(scope.plan().getString("stableStepId"), execution.getStableStepId())
                && Objects.equals(scope.plan().getString("bindingCode"), execution.getToolCode());
    }
}
