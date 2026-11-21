package com.aid.assistant.skill;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.admission.ReleaseEvidence;
import com.aid.billing.admission.ReleaseOutcome;
import com.aid.billing.admission.ReservationIdentity;
import com.aid.billing.admission.UnsubmittedReservationReleaseGuard;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.MediaTaskCancellationPurpose;
import com.aid.media.admission.MediaTaskCancellationRequest;
import com.aid.runtime.assistant.replan.RuntimeReplanCancellationAuthorizationPort;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.skill.executor.SkillExecutionContext;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/** Durable proof and state recording for a Skill call that failed before task-intent persistence. */
@Component
@RequiredArgsConstructor
public class AssistantSkillUnsubmittedReservationReleaseGuard
        implements UnsubmittedReservationReleaseGuard {
    public static final String OPERATION_KIND = "SKILL_TEXT";
    private static final String NORMAL = "0";

    private final AidRuntimeRunMapper runtimeRuns;
    private final AidRuntimeApprovalMapper runtimeApprovals;
    private final AidRuntimeRunStepMapper runtimeSteps;
    private final AidSkillRunMapper skillRuns;
    private final AidSkillRunStepMapper skillSteps;
    private final AidSkillRunTaskLinkMapper taskLinks;
    private final AidMediaTaskMapper mediaTasks;
    private final RuntimeReplanCancellationAuthorizationPort replanCancellationAuthorization;

    @Override
    public boolean supports(String persistedOperationKind) {
        return OPERATION_KIND.equals(persistedOperationKind);
    }

    /** Persist the proof before Billing examines wallet/task evidence. Must share Billing's transaction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markNotEntered(SkillExecutionContext execution, BillingBudgetContext budget,
                               String evidenceCode) {
        requireExecution(execution, budget);
        Locked locked = lockGraph(execution.getRun().getId(), execution.getStep().getId(),
                budget.getRuntimeRunId(),
                execution.getAssistantBudgetEnvelope());
        validateCurrentTicket(locked, execution, budget);
        if (!"PLANNED".equals(locked.skillStep().getOrchestrationStatus())
                || findLink(locked.skillStep().getId(), budget.getLogicalCallKey()) != null
                || findTask(locked.skillRun(), budget.getLogicalCallKey()) != null) {
            throw new ServiceException("Skill submission already has durable intent");
        }
        JSONObject checkpoint = checkpoint(locked.skillStep());
        checkpoint.put("billingAuthorizationId", budget.getAuthorizationId());
        checkpoint.put("billingAuthorizationRevision", budget.getAuthorizationRevision());
        checkpoint.put("billingReservationId", budget.getReservationId());
        checkpoint.put("billingReservationRevision", budget.getReservationRevision());
        checkpoint.put("logicalCallKey", budget.getLogicalCallKey());
        checkpoint.put("submissionEvidenceCode", safeCode(evidenceCode));
        locked.skillStep().setCheckpointJson(JSON.toJSONString(checkpoint));
        locked.skillStep().setOrchestrationStatus("FAILED_PRETASK");
        locked.skillStep().setUpdateBy("skill:" + locked.skillRun().getId());
        locked.skillStep().setUpdateTime(new Date());
        if (skillSteps.updateById(locked.skillStep()) != 1) {
            throw new ServiceException("Skill not-entered evidence changed");
        }
    }

    /** Records exact REPLAN evidence for a reservation that has no durable task or link. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markReplanNotSubmitted(Long skillRunId, Long skillStepId,
                                       ReleaseUnsubmittedReservationRequest release,
                                       MediaTaskCancellationRequest cancellation) {
        if (skillRunId == null || skillStepId == null || release == null || cancellation == null
                || cancellation.purpose() != MediaTaskCancellationPurpose.REPLAN
                || !Objects.equals(release.getRuntimeRunId(), cancellation.expectedRuntimeRunId())
                || !Objects.equals(release.getRuntimeStepId(), skillStepId)
                || !OPERATION_KIND.equals(release.getOperationKind())) {
            throw new ServiceException("Skill REPLAN reservation evidence is invalid");
        }
        AidSkillRun routeRun = skillRuns.selectById(skillRunId);
        if (routeRun == null || !NORMAL.equals(routeRun.getDelFlag())) {
            throw new ServiceException("Skill run is unavailable");
        }
        AssistantSkillBudgetEnvelope envelope = envelope(routeRun);
        authorizeReplan(release.getRuntimeRunId(), routeRun.getUserId(), cancellation);
        Locked locked = lockGraph(skillRunId, skillStepId, release.getRuntimeRunId(), envelope);
        if (Boolean.TRUE.equals(locked.runtimeRun().getStopRequested())
                || !"PAUSING".equals(locked.runtimeRun().getStatus())
                || !"USER_REQUEST".equals(locked.runtimeRun().getPauseReason())
                || !java.util.Set.of("CANCEL_FOR_REPLAN", "WAIT_REPLAN_RECONCILIATION")
                .contains(locked.runtimeRun().getNextAction())
                || !Objects.equals(locked.runtimeRun().getFencingToken(),
                cancellation.expectedRuntimeRunFencingToken())
                || !Objects.equals(locked.runtimeRun().getLeaseOwner(), cancellation.leaseOwner())
                || locked.runtimeRun().getLeaseUntil() == null
                || !locked.runtimeRun().getLeaseUntil().after(new Date())
                || !Objects.equals(locked.skillRun().getUserId(), release.getPayerUserId())
                || !Objects.equals(locked.approval().getId(), envelope.getApprovalId())
                || !Objects.equals(locked.skillStep().getWorkflowAttempt(), release.getAttemptNo())
                || !Objects.equals(AssistantSkillBudgetIdentity.stableStepKey(envelope, locked.skillStep()),
                release.getStableStepKey())
                || !java.util.Set.of("PLANNED", "RECONCILING")
                .contains(locked.skillStep().getOrchestrationStatus())
                || findLink(skillStepId, release.getLogicalCallKey()) != null
                || findTask(locked.skillRun(), release.getLogicalCallKey()) != null) {
            throw new ServiceException("Skill REPLAN reservation is not releasable");
        }
        JSONObject checkpoint = checkpoint(locked.skillStep());
        checkpoint.put("billingAuthorizationId", release.getAuthorizationId());
        checkpoint.put("billingAuthorizationRevision", release.getAuthorizationRevision());
        checkpoint.put("billingReservationId", release.getReservationId());
        checkpoint.put("billingReservationRevision", release.getExpectedReservationRevision());
        checkpoint.put("logicalCallKey", release.getLogicalCallKey());
        checkpoint.put("submissionEvidenceCode", "SKILL_REPLAN_CANCELLED_BEFORE_TASK_INTENT");
        checkpoint.put("cancellationPurpose", MediaTaskCancellationPurpose.REPLAN.name());
        checkpoint.put("replanIntentId", cancellation.replanIntentId());
        checkpoint.put("replanIntentStateRevision", cancellation.replanIntentStateRevision());
        checkpoint.put("replanCommandId", cancellation.replanCommandId());
        checkpoint.put("runFencingToken", cancellation.expectedRuntimeRunFencingToken());
        checkpoint.put("leaseOwner", cancellation.leaseOwner());
        locked.skillStep().setCheckpointJson(JSON.toJSONString(checkpoint));
        locked.skillStep().setOrchestrationStatus("FAILED_PRETASK");
        locked.skillStep().setUpdateBy("replan:" + cancellation.replanIntentId());
        locked.skillStep().setUpdateTime(new Date());
        if (skillSteps.updateById(locked.skillStep()) != 1) {
            throw new ServiceException("Skill REPLAN evidence changed");
        }
    }

    @Override
    public ReleaseEvidence lockAndVerify(ReleaseUnsubmittedReservationRequest request,
                                         ReservationIdentity persisted) {
        if (request == null || persisted == null || !sameRequest(request, persisted)) {
            throw new ServiceException("Skill reservation release identity changed");
        }
        AidSkillRunStep routeStep = skillSteps.selectById(persisted.runtimeStepId());
        if (routeStep == null) throw new ServiceException("Skill step is unavailable");
        AidSkillRun routeRun = skillRuns.selectById(routeStep.getRunId());
        if (routeRun == null) throw new ServiceException("Skill run is unavailable");
        AssistantSkillBudgetEnvelope envelope = envelope(routeRun);
        JSONObject routingCheckpoint = checkpoint(routeStep);
        if (MediaTaskCancellationPurpose.REPLAN.name().equals(
                routingCheckpoint.getString("cancellationPurpose"))) {
            authorizeReplan(persisted.runtimeRunId(), routeRun.getUserId(),
                    new MediaTaskCancellationRequest(null, MediaTaskCancellationPurpose.REPLAN,
                            persisted.runtimeRunId(), routingCheckpoint.getLong("runFencingToken"),
                            routingCheckpoint.getString("leaseOwner"),
                            routingCheckpoint.getLong("replanIntentId"),
                            routingCheckpoint.getLong("replanIntentStateRevision"),
                            routingCheckpoint.getLong("replanCommandId")));
        }
        Locked locked = lockGraph(routeRun.getId(), persisted.runtimeStepId(),
                persisted.runtimeRunId(), envelope);
        JSONObject checkpoint = checkpoint(locked.skillStep());
        if (!Objects.equals(locked.skillRun().getUserId(), request.getPayerUserId())
                || !Objects.equals(locked.skillStep().getId(), persisted.runtimeStepId())
                || !Objects.equals(AssistantSkillBudgetIdentity.stableStepKey(envelope, locked.skillStep()),
                persisted.stableStepKey())
                || !Objects.equals(locked.skillStep().getWorkflowAttempt(), persisted.attemptNo())
                || !Objects.equals(checkpoint.getLong("billingAuthorizationId"), persisted.authorizationId())
                || !Objects.equals(checkpoint.getLong("billingAuthorizationRevision"),
                persisted.authorizationRevision())
                || !Objects.equals(checkpoint.getLong("billingReservationId"), persisted.reservationId())
                || !Objects.equals(checkpoint.getString("logicalCallKey"), persisted.logicalCallKey())
                || !"FAILED_PRETASK".equals(locked.skillStep().getOrchestrationStatus())
                || findLink(locked.skillStep().getId(), persisted.logicalCallKey()) != null
                || findTask(locked.skillRun(), persisted.logicalCallKey()) != null) {
            throw new ServiceException("Skill reservation is not releasable");
        }
        return new ReleaseEvidence(locked.skillRun().getUserId(), persisted.runtimeRunId(),
                locked.skillStep().getId(), persisted.stableStepKey(), envelope.getApprovalId(),
                locked.skillStep().getWorkflowAttempt(), OPERATION_KIND, persisted.logicalCallKey(),
                AssistantSkillBudgetIdentity.BIZ_TASK_TYPE, locked.skillRun().getId());
    }

    @Override
    public void recordOutcome(ReleaseEvidence evidence, ReleaseOutcome outcome) {
        AidSkillRun run = lockSkillRun(evidence.bizTaskId());
        AidSkillRunStep step = lockSkillStep(run.getId(), evidence.runtimeStepId());
        if (!Objects.equals(step.getWorkflowAttempt(), evidence.attemptNo())
                || !Objects.equals(AssistantSkillBudgetIdentity.stableStepKey(envelope(run), step),
                evidence.stableStepKey())
                || findLink(step.getId(), evidence.logicalCallKey()) != null
                || findTask(run, evidence.logicalCallKey()) != null) {
            throw new ServiceException("Skill release outcome identity changed");
        }
        JSONObject checkpoint = checkpoint(step);
        checkpoint.put("billingReleaseState", outcome.name());
        step.setCheckpointJson(JSON.toJSONString(checkpoint));
        boolean replan = MediaTaskCancellationPurpose.REPLAN.name().equals(
                checkpoint.getString("cancellationPurpose"));
        step.setOrchestrationStatus(outcome == ReleaseOutcome.RELEASED
                ? (replan ? "CANCELLED" : "FAILED") : "RECONCILING");
        step.setUpdateBy("skill:" + run.getId());
        step.setUpdateTime(new Date());
        if (skillSteps.updateById(step) != 1) {
            throw new ServiceException("Skill release outcome changed");
        }
        if (outcome == ReleaseOutcome.RECONCILING
                && !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(run.getStatus())) {
            run.setStatus("RECONCILING");
            run.setStage("WAIT_TASK_RECONCILIATION");
            run.setUpdateBy("skill:" + run.getId());
            run.setUpdateTime(new Date());
            if (skillRuns.updateById(run) != 1) {
                throw new ServiceException("Skill reconciliation state changed");
            }
        }
    }

    private Locked lockGraph(Long skillRunId, Long skillStepId, Long runtimeRunId,
                             AssistantSkillBudgetEnvelope envelope) {
        AidRuntimeRun runtimeRun = runtimeRuns.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getId, runtimeRunId).eq(AidRuntimeRun::getDelFlag, NORMAL)
                .last("FOR UPDATE"));
        AidRuntimeApproval approval = runtimeApprovals.selectOne(Wrappers.<AidRuntimeApproval>lambdaQuery()
                .eq(AidRuntimeApproval::getId, envelope.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, runtimeRunId).eq(AidRuntimeApproval::getDelFlag, NORMAL)
                .last("FOR UPDATE"));
        AidRuntimeRunStep runtimeStep = runtimeSteps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getId, envelope.getAssistantParentStepId())
                .eq(AidRuntimeRunStep::getRunId, runtimeRunId).eq(AidRuntimeRunStep::getDelFlag, NORMAL)
                .last("FOR UPDATE"));
        AidSkillRun skillRun = lockSkillRun(skillRunId);
        AidSkillRunStep skillStep = lockSkillStep(skillRun.getId(), skillStepId);
        if (runtimeRun == null || approval == null || runtimeStep == null) {
            throw new ServiceException("Skill release runtime identity is unavailable");
        }
        return new Locked(runtimeRun, approval, runtimeStep, skillRun, skillStep, envelope(skillRun));
    }

    private RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity authorizeReplan(
            Long runtimeRunId, Long userId, MediaTaskCancellationRequest cancellation) {
        if (cancellation == null || cancellation.purpose() != MediaTaskCancellationPurpose.REPLAN
                || !Objects.equals(runtimeRunId, cancellation.expectedRuntimeRunId())
                || cancellation.expectedRuntimeRunFencingToken() == null
                || StrUtil.isBlank(cancellation.leaseOwner())
                || cancellation.replanIntentId() == null
                || cancellation.replanIntentStateRevision() == null
                || cancellation.replanCommandId() == null) {
            throw new ServiceException("Skill REPLAN authorization is invalid");
        }
        AidRuntimeRun discovered = runtimeRuns.selectById(runtimeRunId);
        if (discovered == null || discovered.getSessionId() == null) {
            throw new ServiceException("Assistant REPLAN runtime is unavailable");
        }
        return replanCancellationAuthorization.lockAndVerify(
                new RuntimeReplanCancellationAuthorizationPort.ReplanCancellationAuthorizationRequest(
                        runtimeRunId, discovered.getSessionId(), userId, cancellation.leaseOwner(),
                        cancellation.expectedRuntimeRunFencingToken(), cancellation.replanIntentId(),
                        cancellation.replanIntentStateRevision(), cancellation.replanCommandId()));
    }

    private AidSkillRun lockSkillRun(Long runId) {
        AidSkillRun run = skillRuns.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).eq(AidSkillRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (run == null) throw new ServiceException("Skill run is unavailable");
        return run;
    }

    private AidSkillRunStep lockSkillStep(Long runId, Long stepId) {
        AidSkillRunStep step = skillSteps.selectOne(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getId, stepId).eq(AidSkillRunStep::getRunId, runId)
                .eq(AidSkillRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (step == null) throw new ServiceException("Skill step is unavailable");
        return step;
    }

    private void validateCurrentTicket(Locked locked, SkillExecutionContext execution,
                                       BillingBudgetContext budget) {
        AssistantSkillBudgetEnvelope envelope = locked.envelope();
        if (!Objects.equals(execution.getRun().getId(), locked.skillRun().getId())
                || !Objects.equals(execution.getStep().getId(), locked.skillStep().getId())
                || !Objects.equals(locked.runtimeRun().getUserId(), locked.skillRun().getUserId())
                || Boolean.TRUE.equals(locked.runtimeRun().getStopRequested())
                || !Objects.equals(locked.runtimeRun().getFencingToken(), budget.getRuntimeRunFencingToken())
                || !Objects.equals(locked.runtimeStep().getFencingToken(), budget.getRuntimeStepFencingToken())
                || !Objects.equals(envelope.getAssistantRuntimeRunFencingToken(), budget.getRuntimeRunFencingToken())
                || !Objects.equals(envelope.getAssistantParentStepFencingToken(), budget.getRuntimeStepFencingToken())
                || !Objects.equals(locked.approval().getId(), envelope.getApprovalId())
                || !Objects.equals(locked.approval().getBillingAuthorizationId(), budget.getAuthorizationId())
                || !Objects.equals(locked.approval().getBillingAuthorizationRevision(), budget.getAuthorizationRevision())
                || !"SUBMITTING".equals(locked.runtimeStep().getStatus())
                || !"ACCEPTED".equals(locked.runtimeStep().getProviderAcceptanceState())
                || !Objects.equals(budget.getRuntimeStepId(), locked.skillStep().getId())
                || !Objects.equals(budget.getStableStepKey(),
                AssistantSkillBudgetIdentity.stableStepKey(envelope, locked.skillStep()))
                || !Objects.equals(budget.getAttemptNo(), locked.skillStep().getWorkflowAttempt())
                || !Objects.equals(budget.getLogicalCallKey(), execution.getLogicalCallKey())) {
            throw new ServiceException("Skill not-entered ticket is stale");
        }
    }

    private void requireExecution(SkillExecutionContext execution, BillingBudgetContext budget) {
        if (execution == null || execution.getRun() == null || execution.getStep() == null
                || execution.getAssistantBudgetEnvelope() == null || budget == null
                || !budget.isRunAuthorizationMode() || budget.getReservationId() == null
                || budget.getReservationRevision() == null || StrUtil.isBlank(budget.getLogicalCallKey())) {
            throw new ServiceException("Skill not-entered evidence is invalid");
        }
    }

    private AidSkillRunTaskLink findLink(Long stepId, String logicalCallKey) {
        return taskLinks.selectOne(Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                .eq(AidSkillRunTaskLink::getStepId, stepId)
                .eq(AidSkillRunTaskLink::getLogicalCallKey, logicalCallKey)
                .eq(AidSkillRunTaskLink::getDelFlag, NORMAL).last("LIMIT 1 FOR UPDATE"));
    }

    private AidMediaTask findTask(AidSkillRun run, String logicalCallKey) {
        return mediaTasks.selectByAssistantOperationForUpdate(run.getUserId(),
                AssistantSkillBudgetIdentity.BIZ_TASK_TYPE, run.getId(), logicalCallKey);
    }

    private AssistantSkillBudgetEnvelope envelope(AidSkillRun run) {
        try {
            JSONObject input = JSON.parseObject(run.getInputJson());
            AssistantSkillBudgetEnvelope envelope = input == null ? null
                    : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
            AssistantSkillBudgetIdentity.validate(envelope);
            return envelope;
        } catch (RuntimeException invalid) {
            throw new ServiceException("Skill budget identity is invalid");
        }
    }

    private JSONObject checkpoint(AidSkillRunStep step) {
        try {
            JSONObject value = StrUtil.isBlank(step.getCheckpointJson())
                    ? new JSONObject() : JSON.parseObject(step.getCheckpointJson());
            return value == null ? new JSONObject() : value;
        } catch (RuntimeException invalid) {
            throw new ServiceException("Skill checkpoint is invalid");
        }
    }

    private boolean sameRequest(ReleaseUnsubmittedReservationRequest request, ReservationIdentity persisted) {
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

    private String safeCode(String value) {
        return StrUtil.isBlank(value) ? "SKILL_MEDIA_NOT_ENTERED" : StrUtil.sub(value, 0, 80);
    }

    private record Locked(AidRuntimeRun runtimeRun, AidRuntimeApproval approval,
                          AidRuntimeRunStep runtimeStep, AidSkillRun skillRun,
                          AidSkillRunStep skillStep, AssistantSkillBudgetEnvelope envelope) { }
}
