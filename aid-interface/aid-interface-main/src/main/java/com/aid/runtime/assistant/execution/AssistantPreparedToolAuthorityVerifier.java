package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.tool.RuntimeToolBillingIntent;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Immutable preparation authority proof. Does not assert task success or permit a new submission. */
@Component
public class AssistantPreparedToolAuthorityVerifier {
    private static final Set<String> STATES = Set.of("READY", "SUBMITTING", "INFLIGHT", "RUNNING",
            "RECONCILING", "SUCCEEDED", "FAILED", "CANCELLED", "CANCELLED_PRETASK");
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AssistantPlanExecutionScopeCodec codec;

    public Authority require(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        return requireAuthority(userId, run, step, false);
    }

    /** Read-only completed history, anchored to the original immutable approval after explicit replanning. */
    public Authority requireHistoricalCompleted(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        check(step != null && "SUCCEEDED".equals(step.getStatus()), "PREPARED_TOOL_HISTORY_NOT_COMPLETED");
        return requireAuthority(userId, run, step, true);
    }

    private Authority requireAuthority(Long userId, AidRuntimeRun run, AidRuntimeRunStep step, boolean historical) {
        check(userId != null && userId > 0 && run != null && run.getId() != null
                && "0".equals(run.getDelFlag()) && Objects.equals(userId, run.getUserId())
                && "ASSISTANT_LOOP".equals(run.getExecutorStrategy())
                && step != null && step.getId() != null && Objects.equals(step.getRunId(), run.getId())
                && "0".equals(step.getDelFlag()) && step.getStatus() != null && STATES.contains(step.getStatus())
                && "TOOL".equals(step.getStepKind()) && "ASSISTANT_TOOL".equals(step.getOperationKind())
                && step.getApprovalId() != null && step.getAttemptNo() != null && step.getAttemptNo() > 0
                && StrUtil.isNotBlank(step.getToolCode()) && StrUtil.isNotBlank(step.getIdempotencyKey()),
                "PREPARED_TOOL_AUTHORITY_INVALID");
        // A real queued media task may be durably cancelled before Provider admission.
        // This historical proof grants no new submission and accepts no taskless pretask outcome.
        check(!"CANCELLED_PRETASK".equals(step.getStatus()) || step.getMediaTaskId() != null
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState()),
                "PREPARED_TOOL_CANCELLED_TASK_MISSING");
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        check(approval != null && "0".equals(approval.getDelFlag())
                && Objects.equals(approval.getRunId(), run.getId()) && Objects.equals(approval.getUserId(), userId)
                && Objects.equals(approval.getPlanRevision(), step.getPlanRevision())
                && (historical || Objects.equals(approval.getContextDigest(), run.getContextDigest()))
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && approval.getStatus() != null && Set.of("ACCEPTED", "CONSUMED").contains(approval.getStatus()),
                "PREPARED_TOOL_APPROVAL_INVALID");
        String expectedContextDigest = historical ? approval.getContextDigest() : run.getContextDigest();
        check(expectedContextDigest != null && expectedContextDigest.matches("[a-f0-9]{64}"),
                "PREPARED_TOOL_CONTEXT_INVALID");
        var scope = codec.decode(approval.getScopeJson());
        check(scope.isTool() && Objects.equals(scope.digest(), approval.getScopeDigest())
                && Objects.equals(scope.requestDigest(), approval.getRequestDigest())
                && Objects.equals(scope.context().getString("digest"), expectedContextDigest)
                && Objects.equals(scope.decision().getLong("observedPlanRevision"), step.getPlanRevision())
                && Objects.equals(scope.plan().getString("definitionDigest"), approval.getPlanDigest())
                && Objects.equals(scope.plan().getString("stableStepId"), step.getStableStepId())
                && Objects.equals(scope.plan().getInteger("position"), step.getPlanPosition())
                && StrUtil.isNotBlank(scope.plan().getString("operationKind"))
                && Objects.equals(scope.plan().getString("operationKind"), scope.operationScope().getOperationKind())
                && "TOOL".equals(scope.plan().getString("executorKind"))
                && Objects.equals(scope.plan().getString("bindingCode"), step.getToolCode())
                && Objects.equals(scope.operationScope().getToolCode(), step.getToolCode())
                && StrUtil.isNotBlank(scope.plan().getString("bindingVersion"))
                && scope.tool().getString("schemaDigest") != null
                && scope.tool().getString("schemaDigest").matches("[a-f0-9]{64}")
                && Objects.equals(scope.argumentsJson(),
                    AssistantPlanExecutionJson.canonicalExplicitNulls(JSON.parseObject(scope.argumentsJson()))),
                "PREPARED_TOOL_AUTHORITY_CHANGED");
        String invocationId = scope.decision().getString("invocationId");
        String key = "asst-op:" + SecureUtil.sha256(run.getId() + "\n" + step.getPlanRevision()
                + "\n" + step.getStableStepId() + "\n" + step.getAttemptNo() + "\n" + invocationId).substring(0, 56);
        check(StrUtil.isNotBlank(invocationId) && Objects.equals(key, step.getIdempotencyKey()),
                "PREPARED_TOOL_INVOCATION_CHANGED");
        return new Authority(scope.executionManifestJson(), scope.argumentsJson(), scope.argumentsDigest(),
                scope.preparedPayloadJson(), scope.preparedPayloadDigest(), scope.operationScope(), scope.billingIntent(),
                scope.plan().getString("definitionDigest"), scope.plan().getString("bindingVersion"));
    }

    public record Authority(String contextManifestJson, String argumentsJson, String argumentsDigest,
                            String preparedPayloadJson, String preparedPayloadDigest,
                            AssistantBudgetOperationScope operationScope, RuntimeToolBillingIntent billingIntent,
                            String planDigest, String contractVersion) { }

    private static void check(boolean condition, String code) {
        if (!condition) throw new ServiceException(code);
    }
}
