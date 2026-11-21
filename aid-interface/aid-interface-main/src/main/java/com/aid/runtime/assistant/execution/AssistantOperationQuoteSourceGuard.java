package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.alibaba.fastjson2.JSON;
import java.util.Objects;

/** Pure immutable-source checks shared by quote-only recovery and explicit budget renewal. */
public final class AssistantOperationQuoteSourceGuard {
    private AssistantOperationQuoteSourceGuard() { }

    /** Identity only: callers separately verify statuses, grants, time, submission and orphan evidence. */
    public static void requireBudgetRenewalOperationScope(AidRuntimeRun run,
            AidRuntimeRunStep main, AidRuntimeRunStep child, AidRuntimeApproval approval) {
        requireBudgetRenewalOperationIdentity(run, main, child, approval);
    }

    public static void requireBudgetRenewalOperationIdentity(AidRuntimeRun run,
            AidRuntimeRunStep main, AidRuntimeRunStep child, AidRuntimeApproval approval) {
        require(run != null && run.getId() != null && run.getUserId() != null
                && "0".equals(run.getDelFlag()) && main != null && child != null && approval != null
                && main.getId() != null && main.getId() > 0 && child.getId() != null && child.getId() > 0
                && approval.getId() != null && approval.getId() > 0
                && "0".equals(main.getDelFlag()) && "0".equals(child.getDelFlag()) && "0".equals(approval.getDelFlag())
                && Objects.equals(approval.getUserId(), run.getUserId())
                && Objects.equals(approval.getRunId(), run.getId())
                && Objects.equals(main.getRunId(), run.getId()) && Objects.equals(child.getRunId(), run.getId())
                && Objects.equals(child.getApprovalId(), approval.getId())
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && "MAIN_AGENT".equals(main.getStepKind()) && "ASSISTANT_DECISION".equals(main.getOperationKind())
                && StrUtil.isNotBlank(main.getOutputJson()),
                "OPERATION_SCOPE_INVALID");
        var source = new AssistantPlanExecutionScopeCodec().decode(approval.getScopeJson());
        var decision = decision(main.getOutputJson(), source);
        require(source.requestDigest() != null && source.requestDigest().matches("[0-9a-f]{64}")
                && Objects.equals(source.digest(), approval.getScopeDigest())
                && Objects.equals(source.requestDigest(), approval.getRequestDigest())
                && Objects.equals(child.getInputDigest(), approval.getRequestDigest())
                && Objects.equals(approval.getAnswerDigest(), SecureUtil.sha256(decision.canonicalPayloadJson()))
                && Objects.equals(source.decision().getLong("mainAgentStepId"), main.getId())
                && Objects.equals(source.decision().getString("decisionId"), decision.decisionId())
                && Objects.equals(source.decision().getString("invocationId"), decision.invocationId())
                && Objects.equals(source.decision().getLong("observedRunRevision"), decision.observedRunRevision())
                && Objects.equals(source.decision().getLong("observedPlanRevision"), decision.observedPlanRevision())
                && Objects.equals(run.getPlanRevision(), decision.observedPlanRevision())
                && Objects.equals(main.getPlanRevision(), run.getPlanRevision())
                && Objects.equals(child.getPlanRevision(), run.getPlanRevision())
                && Objects.equals(approval.getPlanRevision(), run.getPlanRevision())
                && Objects.equals(approval.getContextDigest(), run.getContextDigest())
                && Objects.equals(source.context().getString("digest"), run.getContextDigest())
                && Objects.equals(source.plan().getString("definitionDigest"), approval.getPlanDigest())
                && Objects.equals(source.plan().getString("stableStepId"), decision.stableStepId())
                && Objects.equals(source.plan().getInteger("position"), child.getPlanPosition())
                && Objects.equals(child.getStableStepId(), decision.stableStepId())
                && Objects.equals(child.getStepKey(), decision.stableStepId())
                && Objects.equals(child.getAttemptNo(), decision.attemptNo())
                && Objects.equals(approval.getPlanningAttemptNo(), decision.attemptNo())
                && Objects.equals(child.getStepKind(), decision.action())
                && Objects.equals(child.getOperationKind(), decision.isTool() ? "ASSISTANT_TOOL" : "ASSISTANT_SKILL")
                && Objects.equals(child.getRetryClass(), source.plan().getString("retryClass"))
                && Objects.equals(child.getIdempotencyKey(), "asst-op:" + SecureUtil.sha256(run.getId()+"\n"
                    + decision.observedPlanRevision()+"\n"+decision.stableStepId()+"\n"+decision.attemptNo()+"\n"
                    +decision.invocationId()).substring(0,56)), "OPERATION_SCOPE_INVALID");
        if (decision.isTool()) {
            require(source.isTool() && Objects.equals(child.getToolCode(), decision.bindingCode())
                    && Objects.equals(source.plan().getString("bindingCode"), decision.bindingCode())
                    && Objects.equals(source.plan().getString("bindingVersion"), decision.bindingVersion())
                    && "TOOL".equals(source.plan().getString("executorKind"))
                    && Objects.equals(source.argumentsJson(), AssistantPlanExecutionJson.canonicalExplicitNulls(decision.arguments()))
                    && Objects.equals(source.operationScope().getToolCode(), decision.bindingCode())
                    && source.tool().getString("schemaDigest") != null
                    && source.tool().getString("schemaDigest").matches("[0-9a-f]{64}"), "OPERATION_SCOPE_INVALID");
        } else {
            require(!source.isTool() && "SKILL".equals(source.plan().getString("executorKind"))
                    && Objects.equals(child.getSkillVersionId(), decision.skillVersionId())
                    && Objects.equals(source.skill().getLong("skillVersionId"), decision.skillVersionId())
                    && Objects.equals(source.skill().getString("packageDigest"), decision.packageDigest())
                    && Objects.equals(source.skill().getString("inputJson"), AssistantPlanExecutionJson.canonical(decision.arguments())),
                    "OPERATION_SCOPE_INVALID");
        }
    }

    static AssistantPlanExecutionDecision decision(String mainDecisionJson,
            AssistantPlanExecutionScopeCodec.Persisted source) {
        if (com.aid.runtime.assistant.NativeAssistantToolBatch.isBatch(mainDecisionJson)) {
            AssistantPlanExecutionDecision selected = null;
            var batch = com.aid.runtime.assistant.NativeAssistantToolBatch.parse(mainDecisionJson);
            for (var call : batch.calls()) {
                var candidate = new AssistantPlanExecutionDecisionParser(1_001).parse(call.decisionJson());
                if (!Objects.equals(candidate.decisionId(), source.decision().getString("decisionId"))) continue;
                require(selected == null, "OPERATION_SCOPE_INVALID");selected = candidate;
            }
            require(selected != null, "OPERATION_SCOPE_INVALID");return selected;
        }
        return new AssistantPlanExecutionDecisionParser(1_001).parse(mainDecisionJson);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new ServiceException(code);
    }
}
