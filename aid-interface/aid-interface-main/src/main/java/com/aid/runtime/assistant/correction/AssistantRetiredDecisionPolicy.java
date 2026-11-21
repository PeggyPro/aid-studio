package com.aid.runtime.assistant.correction;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.runtime.domain.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;
import java.util.Set;

/** Historical execution evidence only; never reopens an invalidated authorization. */
public final class AssistantRetiredDecisionPolicy {
    private AssistantRetiredDecisionPolicy() { }

    public static boolean verifies(AidRuntimeRun run, AidRuntimeRunStep step, AidRuntimeApproval approval,
                                   AidMediaTask task, AidRuntimePlan plan, AidRuntimeReplanIntent intent,
                                   AidRuntimeCommand command) {
        if (run == null || step == null || approval == null || task == null || plan == null
                || intent == null || command == null || run.getId() == null || run.getUserId() == null
                || run.getSessionId() == null || run.getPlanRevision() == null || step.getPlanRevision() == null
                || run.getId() <= 0 || run.getUserId() <= 0 || step.getId() == null || step.getId() <= 0
                || task.getId() == null || task.getId() <= 0 || approval.getId() == null || approval.getId() <= 0
                || intent.getId() == null || intent.getId() <= 0 || command.getId() == null || command.getId() <= 0
                || step.getPlanRevision() <= 0 || step.getInputDigest() == null
                || !step.getInputDigest().matches("^[0-9a-f]{64}$")
                || step.getIdempotencyKey() == null || step.getIdempotencyKey().isBlank()
                || !"REPLAN_SUPERSEDED_DECISION".equals(step.getErrorMessage())
                || !"MAIN_AGENT".equals(step.getStepKind()) || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                || !"0".equals(step.getDelFlag()) || !"ACCEPTED".equals(step.getProviderAcceptanceState())
                || !Objects.equals(run.getId(), step.getRunId()) || !Objects.equals(step.getMediaTaskId(), task.getId())
                || !Objects.equals(step.getApprovalId(), approval.getId()) || !Objects.equals(task.getUserId(), run.getUserId())
                || !Objects.equals(task.getBizTaskId(), step.getId()) || !"ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(step.getIdempotencyKey(), task.getIdempotencyKey())
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(Objects.toString(task.getStatus(), ""))
                || !Objects.equals(step.getStatus(), task.getStatus())
                || !Set.of("CONSUMED", "INVALIDATED").contains(Objects.toString(approval.getStatus(), ""))
                || !Objects.equals(approval.getRunId(), run.getId()) || !Objects.equals(approval.getUserId(), run.getUserId())
                || !"0".equals(approval.getDelFlag()) || approval.getAcceptedAt() == null
                || !Objects.equals(approval.getPlanRevision(), step.getPlanRevision())
                || !Objects.equals(approval.getRequestDigest(), step.getInputDigest())
                || !Objects.equals(plan.getRunId(), run.getId()) || !Objects.equals(plan.getRevision(), step.getPlanRevision())
                || !Objects.equals(plan.getAgentVersionId(), run.getVersionId()) || !"0".equals(plan.getDelFlag())
                || !"SUPERSEDED".equals(plan.getStatus()) || plan.getDefinitionJson() == null
                || !Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson()))
                || run.getPlanRevision() <= step.getPlanRevision()
                || !"APPLIED".equals(intent.getStatus()) || !"0".equals(intent.getDelFlag())
                || intent.getActiveSlot() != null || intent.getAppliedAt() == null
                || !Objects.equals(intent.getRunId(), run.getId()) || !Objects.equals(intent.getSessionId(), run.getSessionId())
                || !Objects.equals(intent.getActorId(), run.getUserId())
                || !Objects.equals(intent.getBasePlanRevision(), step.getPlanRevision())
                || !Objects.equals(intent.getCommandId(), command.getId()) || !"COMPLETED".equals(command.getStatus())
                || !"0".equals(command.getDelFlag()) || !Objects.equals(command.getActorId(), run.getUserId())
                || !Objects.equals(command.getSessionId(), run.getSessionId())
                || !Objects.equals(command.getClientRequestId(), intent.getClientRequestId())
                || intent.getCommandPayloadDigest() == null
                || !intent.getCommandPayloadDigest().matches("^[0-9a-f]{64}$")
                || !Objects.equals(command.getPayloadDigest(), intent.getCommandPayloadDigest())
                || !("INTERJECTION".equals(intent.getIntentType()) && "MESSAGE_INTERJECTION".equals(command.getCommandType())
                    || "CONTEXT_REFRESH".equals(intent.getIntentType()) && "CONTEXT_REFRESH".equals(command.getCommandType()))) return false;
        try {
            JSONObject receipt = JSON.parseObject(step.getOutputJson());
            JSONObject applied = JSON.parseObject(command.getResultJson());
            return receipt != null && applied != null
                    && (receipt.keySet().equals(Set.of("mediaTaskId", "status", "obsoleteDecisionDiscarded", "resultDigest"))
                        || task.getResultText() == null && receipt.keySet().equals(Set.of("mediaTaskId", "status", "obsoleteDecisionDiscarded")))
                    && Boolean.TRUE.equals(receipt.get("obsoleteDecisionDiscarded"))
                    && Objects.equals(receipt.getString("mediaTaskId"), String.valueOf(task.getId()))
                    && Objects.equals(receipt.getString("status"), task.getStatus())
                    && Objects.equals(receipt.getString("resultDigest"), task.getResultText() == null ? null : SecureUtil.sha256(task.getResultText()))
                    && Objects.equals(applied.getLong("runId"), run.getId())
                    && "REPLANNING_REQUIRED".equals(applied.getString("nextAction"));
        } catch (RuntimeException invalid) { return false; }
    }
}
