package com.aid.runtime.assistant;

import com.aid.billing.admission.ReleaseEvidence;
import com.aid.billing.admission.ReleaseOutcome;
import com.aid.billing.admission.ReservationIdentity;
import com.aid.billing.admission.UnsubmittedReservationReleaseGuard;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeBudgetPolicyMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Persistent proof for releasing a reservation that never reached media-task creation. */
@Component
public class AssistantRuntimeUnsubmittedReservationReleaseGuard
        implements UnsubmittedReservationReleaseGuard
{
    private static final List<String> OPERATION_KINDS = List.of(
            "ASSISTANT_DECISION", "ASSISTANT_TOOL", "ASSISTANT_SKILL");
    private static final List<String> PRETASK_TERMINAL = List.of("FAILED_PRETASK", "CANCELLED_PRETASK");

    @Resource private AidRuntimeRunMapper runs;
    @Resource private com.aid.runtime.mapper.AidRuntimeReplanIntentMapper replanIntents;
    @Resource private AidRuntimeBudgetPolicyMapper policies;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeRunStepMapper steps;

    @Override
    public boolean supports(String persistedOperationKind)
    {
        return OPERATION_KINDS.contains(persistedOperationKind);
    }

    @Override
    public ReleaseEvidence lockAndVerify(BillingBudgetRequests.ReleaseUnsubmittedReservationRequest request,
                                         ReservationIdentity persisted)
    {
        if (request == null || persisted == null || !sameRequest(request, persisted))
            throw new ServiceException("Assistant reservation release identity changed");
        AidRuntimeRun run = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, persisted.runtimeRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, persisted.runtimeRunId())
                .eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, persisted.approvalId())
                .eq(AidRuntimeApproval::getRunId, persisted.runtimeRunId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRunStep step = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, persisted.runtimeStepId())
                .eq(AidRuntimeRunStep::getRunId, persisted.runtimeRunId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (run == null || policy == null || approval == null || step == null
                || !Objects.equals(run.getUserId(), request.getPayerUserId())
                || !Objects.equals(policy.getAuthorizationId(), persisted.authorizationId())
                || !Objects.equals(approval.getBillingAuthorizationId(), persisted.authorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(), persisted.authorizationRevision())
                || !Objects.equals(step.getApprovalId(), persisted.approvalId())
                || !Objects.equals(step.getStableStepId(), persisted.stableStepKey())
                || !Objects.equals(step.getAttemptNo(), persisted.attemptNo())
                || !Objects.equals(step.getOperationKind(), persisted.operationKind())
                || !Objects.equals(step.getIdempotencyKey(), persisted.logicalCallKey())
                || !Objects.equals(step.getReservationId(), persisted.reservationId())
                || step.getMediaTaskId() != null
                || !releasableState(step))
            throw new ServiceException("Assistant reservation is not releasable");
        return new ReleaseEvidence(run.getUserId(), run.getId(), step.getId(), step.getStableStepId(),
                approval.getId(), step.getAttemptNo(), step.getOperationKind(), step.getIdempotencyKey(),
                AssistantRuntimeSubmissionAdmissionHandler.BIZ_TASK_TYPE, step.getId());
    }

    @Override
    public void recordOutcome(ReleaseEvidence evidence, ReleaseOutcome outcome)
    {
        AidRuntimeRunStep step = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, evidence.runtimeStepId())
                .eq(AidRuntimeRunStep::getRunId, evidence.runtimeRunId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (step == null || !Objects.equals(step.getStableStepId(), evidence.stableStepKey())
                || !Objects.equals(step.getApprovalId(), evidence.approvalId())
                || !Objects.equals(step.getAttemptNo(), evidence.attemptNo())
                || !Objects.equals(step.getOperationKind(), evidence.operationKind())
                || !Objects.equals(step.getIdempotencyKey(), evidence.logicalCallKey())
                || step.getMediaTaskId() != null)
            throw new ServiceException("Assistant release outcome identity changed");
        Date now = new Date();
        if (outcome == ReleaseOutcome.RELEASED)
        {
            // Billing has verified the original trace and no-task proof again. Restore the exact
            // REPLAN terminal after an uncertain wallet compensation; never treat Provider UNKNOWN as this proof.
            if ("RECONCILING".equals(step.getStatus()) && "UNKNOWN".equals(step.getProviderAcceptanceState())
                    && "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN".equals(step.getErrorMessage())
                    && replanIntents.selectActiveByRunIdForUpdate(evidence.runtimeRunId()) != null)
            {
                step.setStatus("CANCELLED_PRETASK");step.setProviderAcceptanceState("NOT_SUBMITTED");
                step.setErrorMessage("RUNTIME_REPLAN_CANCELLED_BEFORE_TASK_INTENT");
            }
            if (!PRETASK_TERMINAL.contains(step.getStatus())
                    || !List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState()))
                throw new ServiceException("Assistant release outcome conflicts with runtime state");
            step.setProviderAcceptanceState("RELEASED");
        }
        else
        {
            step.setStatus("RECONCILING");
            step.setProviderAcceptanceState("UNKNOWN");
            step.setErrorMessage("UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN");
            AidRuntimeRun run = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                    .eq(AidRuntimeRun::getId, evidence.runtimeRunId())
                    .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
            if (run == null) throw new ServiceException("Assistant runtime is unavailable");
            if (!List.of("SUCCEEDED", "FAILED", "STOPPED").contains(run.getStatus()))
            {
                boolean replan = replanIntents.selectActiveByRunIdForUpdate(run.getId()) != null;
                run.setStatus(replan ? "PAUSING" : "RECONCILING");
                // Keep the run on a route that the durable recovery dispatcher actually owns.
                // Child Tool/Skill reservations are reconciled by plan execution; the main-Agent
                // reservation is reconciled by the loop coordinator.  A private budget-only
                // action would strand the run because no coordinator consumes it.
                run.setNextAction(replan ? "WAIT_REPLAN_RECONCILIATION" : "ASSISTANT_DECISION".equals(evidence.operationKind())
                        ? "WAIT_TASK_RECONCILIATION" : "RECONCILE_OPERATION");
                run.setStateRevision(value(run.getStateRevision()) + 1L);
                run.setUpdateBy(String.valueOf(evidence.payerUserId()));
                run.setUpdateTime(now);
                runs.updateById(run);
            }
        }
        step.setUpdateBy(String.valueOf(evidence.payerUserId()));
        step.setUpdateTime(now);
        steps.updateById(step);
    }

    private boolean sameRequest(BillingBudgetRequests.ReleaseUnsubmittedReservationRequest request,
                                ReservationIdentity persisted)
    {
        return Objects.equals(request.getAuthorizationId(), persisted.authorizationId())
                && Objects.equals(request.getAuthorizationRevision(), persisted.authorizationRevision())
                && Objects.equals(request.getReservationId(), persisted.reservationId())
                && Objects.equals(request.getRuntimeRunId(), persisted.runtimeRunId())
                && Objects.equals(request.getRuntimeStepId(), persisted.runtimeStepId())
                && Objects.equals(request.getStableStepKey(), persisted.stableStepKey())
                && Objects.equals(request.getApprovalId(), persisted.approvalId())
                && Objects.equals(request.getAttemptNo(), persisted.attemptNo())
                && Objects.equals(request.getOperationKind(), persisted.operationKind())
                && Objects.equals(request.getLogicalCallKey(), persisted.logicalCallKey());
    }

    private boolean releasableState(AidRuntimeRunStep step)
    {
        return PRETASK_TERMINAL.contains(step.getStatus())
                && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState())
                || "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState());
    }

    private long value(Long value) { return value == null ? 0L : value; }
}
