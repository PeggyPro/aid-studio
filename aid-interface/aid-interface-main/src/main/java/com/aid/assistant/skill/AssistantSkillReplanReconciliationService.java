package com.aid.assistant.skill;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.admission.MediaTaskCancellationPurpose;
import com.aid.media.admission.MediaTaskCancellationRequest;
import com.aid.media.service.TaskCompletionService;
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
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Drives REPLAN reconciliation for durable media tasks owned by an Assistant fixed Skill invocation. */
@Service
@RequiredArgsConstructor
public class AssistantSkillReplanReconciliationService
{
    private static final String NORMAL = "0";
    private static final Set<String> ACTIVE_STEP = Set.of("WAITING_TASK", "INFLIGHT", "RECONCILING");
    private static final Set<String> TERMINAL_STEP = Set.of("OUTPUT_READY", "FAILED", "CANCELLED");
    private static final Set<String> TERMINAL_TASK = Set.of(
            MediaTaskStatus.SUCCEEDED.name(), MediaTaskStatus.FAILED.name(), MediaTaskStatus.CANCELLED.name());
    private static final Set<String> TERMINAL_RUN = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    private final AidSkillRunMapper runs;
    private final AidSkillRunStepMapper steps;
    private final AidSkillRunTaskLinkMapper taskLinks;
    private final AidMediaTaskMapper mediaTasks;
    private final TaskCompletionService taskCompletionService;
    private final TransactionTemplate transactions;
    private final AidRuntimeRunMapper assistantRuns;
    private final AidRuntimeRunStepMapper assistantSteps;
    private final AidRuntimeApprovalMapper assistantApprovals;
    private final RuntimeReplanCancellationAuthorizationPort replanCancellationAuthorization;
    private final AidBillingAuthorizationReservationMapper reservations;
    private final AssistantBillingBudgetService billingBudgetService;
    private final AssistantSkillUnsubmittedReservationReleaseGuard reservationReleaseGuard;

    public void reconcile(Long assistantRuntimeRunId, Long assistantParentStepId,
                          String assistantOuterStableStepKey, Integer assistantAttemptNo,
                          Long userId, String operator,
                          MediaTaskCancellationRequest cancellation)
    {
        requireReplanTicket(assistantRuntimeRunId, cancellation);
        AidSkillRun run = requireInvocation(assistantRuntimeRunId, assistantParentStepId,
                assistantOuterStableStepKey, assistantAttemptNo, userId, cancellation);
        if (TERMINAL_RUN.contains(run.getStatus())) return;
        SkillTaskGraph graph = taskGraph(run);
        if (graph.linked().isEmpty() && graph.unlinkedActive().isEmpty()) return;
        for (LinkedTask value : graph.linked())
        {
            AidMediaTask task = requireTask(run, value.link());
            if (TERMINAL_TASK.contains(task.getStatus())) continue;
            if (Set.of("WAITING_TASK", "RECONCILING").contains(value.step().getOrchestrationStatus())
                    && Set.of(MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name()).contains(task.getStatus()))
            {
                try
                {
                    taskCompletionService.cancelBudgetManagedUnsubmittedTask(
                            cancellation.withTaskId(task.getId()));
                }
                catch (RuntimeException uncertain)
                {
                    // An incomplete refund or ambiguous submission stays on the reconciliation path below.
                }
            }
        }
        for (AidSkillRunStep step : graph.unlinkedActive())
        {
            reconcileUnlinkedReservation(run, step, operator, cancellation);
        }
        SkillTaskGraph current = taskGraph(run);
        boolean allTerminal = current.unlinkedActive().isEmpty()
                && current.linked().stream().allMatch(value ->
                TERMINAL_TASK.contains(requireTask(run, value.link()).getStatus()));
        if (allTerminal) settleTerminal(run.getId(), current.linked(), operator, cancellation);
        else markReconciliationRequired(run.getId(), operator, cancellation);
    }

    private AidSkillRun requireInvocation(Long assistantRuntimeRunId, Long assistantParentStepId,
                                          String stableStepKey, Integer attemptNo, Long userId,
                                          MediaTaskCancellationRequest cancellation)
    {
        if (assistantRuntimeRunId == null || assistantParentStepId == null || stableStepKey == null
                || attemptNo == null || userId == null) throw new ServiceException("Assistant Skill identity is invalid");
        List<AidSkillRun> matches = runs.selectByAssistantIdentity(assistantRuntimeRunId,
                assistantParentStepId, stableStepKey, attemptNo, userId);
        if (matches == null || matches.size() != 1) {
            throw new ServiceException(matches == null || matches.isEmpty()
                    ? "Assistant Skill invocation is unavailable" : "Assistant Skill identity is ambiguous");
        }
        AidSkillRun run = matches.get(0);
        if (!Objects.equals(userId, run.getUserId()) || !NORMAL.equals(run.getDelFlag()))
            throw new ServiceException("Assistant Skill invocation is unavailable");
        AssistantSkillBudgetEnvelope persistedEnvelope = envelope(run);
        if (!Objects.equals(persistedEnvelope.getAssistantRuntimeRunId(), assistantRuntimeRunId)
                || !Objects.equals(persistedEnvelope.getAssistantParentStepId(), assistantParentStepId)
                || !Objects.equals(persistedEnvelope.getAssistantOuterStableStepKey(), stableStepKey)
                || !Objects.equals(persistedEnvelope.getAssistantAttemptNo(), attemptNo)) {
            throw new ServiceException("Assistant Skill persisted invocation identity changed");
        }
        AidRuntimeRun outer = assistantRuns.selectById(assistantRuntimeRunId);
        if (outer == null || !NORMAL.equals(outer.getDelFlag())
                || !Objects.equals(userId, outer.getUserId())
                || Boolean.TRUE.equals(outer.getStopRequested())
                || !"PAUSING".equals(outer.getStatus())
                || !"USER_REQUEST".equals(outer.getPauseReason())
                || !Set.of("CANCEL_FOR_REPLAN", "WAIT_REPLAN_RECONCILIATION")
                .contains(outer.getNextAction())
                || !Objects.equals(outer.getFencingToken(), cancellation.expectedRuntimeRunFencingToken())
                || !Objects.equals(outer.getLeaseOwner(), cancellation.leaseOwner())
                || outer.getLeaseUntil() == null || !outer.getLeaseUntil().after(new Date()))
            throw new ServiceException("Assistant run has not accepted REPLAN cancellation");
        AidRuntimeRunStep outerStep = assistantSteps.selectById(assistantParentStepId);
        if (outerStep == null || !NORMAL.equals(outerStep.getDelFlag())
                || !Objects.equals(outerStep.getRunId(), assistantRuntimeRunId)
                || !Objects.equals(outerStep.getChildRunId(), run.getId())
                || !"ASSISTANT_SKILL".equals(outerStep.getOperationKind())
                || !Objects.equals(outerStep.getStableStepId(), stableStepKey)
                || !Objects.equals(outerStep.getAttemptNo(), attemptNo))
            throw new ServiceException("Assistant Skill parent identity is invalid");
        return run;
    }

    private SkillTaskGraph taskGraph(AidSkillRun run)
    {
        List<AidSkillRunStep> persistedSteps = steps.selectList(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getRunId, run.getId())
                .eq(AidSkillRunStep::getDelFlag, NORMAL)
                .orderByAsc(AidSkillRunStep::getId));
        List<AidSkillRunTaskLink> persistedLinks = taskLinks.selectList(
                Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                        .eq(AidSkillRunTaskLink::getRunId, run.getId())
                        .eq(AidSkillRunTaskLink::getDelFlag, NORMAL)
                        .orderByAsc(AidSkillRunTaskLink::getId));
        List<LinkedTask> result = new ArrayList<>();
        List<AidSkillRunStep> unlinkedActive = new ArrayList<>();
        for (AidSkillRunStep step : persistedSteps)
        {
            List<AidSkillRunTaskLink> links = persistedLinks.stream()
                    .filter(value -> Objects.equals(value.getStepId(), step.getId())).toList();
            if (links.size() > 1) throw new ServiceException("Assistant Skill task identity is ambiguous");
            if (links.isEmpty())
            {
                if (ACTIVE_STEP.contains(step.getOrchestrationStatus())
                        || "PLANNED".equals(step.getOrchestrationStatus())) {
                    unlinkedActive.add(step);
                }
                continue;
            }
            AidSkillRunTaskLink link = links.get(0);
            if (!Objects.equals(link.getStepExecutionId(), step.getStepExecutionId())
                    || !Objects.equals(link.getWorkflowAttempt(), step.getWorkflowAttempt()))
                throw new ServiceException("Assistant Skill task binding is invalid");
            requireTask(run, link);
            result.add(new LinkedTask(step, link));
        }
        if (result.size() != persistedLinks.size())
            throw new ServiceException("Assistant Skill task identity is ambiguous");
        result.sort(Comparator.comparing(value -> value.step().getId()));
        unlinkedActive.sort(Comparator.comparing(AidSkillRunStep::getId));
        return new SkillTaskGraph(result, unlinkedActive);
    }

    /**
     * Cancels one Skill step that never acquired a durable task intent. The runtime/Skill graph is
     * locked before the logical reservation lookup. The reserve path takes the same graph locks
     * before it can create a reservation, so a missing reservation is authoritative in this tx.
     */
    private void reconcileUnlinkedReservation(AidSkillRun discoveredRun, AidSkillRunStep discoveredStep,
                                               String operator,
                                               MediaTaskCancellationRequest cancellation)
    {
        transactions.executeWithoutResult(status -> {
            lockReplanAuthorization(cancellation);
            lockRuntimeParents(discoveredRun.getId(), cancellation);
            AidSkillRun run = lockRun(discoveredRun.getId());
            AidSkillRunStep step = lockStep(run.getId(), discoveredStep.getId());
            if (TERMINAL_STEP.contains(step.getOrchestrationStatus())) return;
            if (!ACTIVE_STEP.contains(step.getOrchestrationStatus())
                    && !"PLANNED".equals(step.getOrchestrationStatus())) {
                throw new ServiceException("Assistant Skill step is not cancellable for REPLAN");
            }
            if (findAnyLink(step.getId()) != null) {
                throw new ServiceException("Assistant Skill task binding appeared during REPLAN");
            }
            String logicalCallKey = logicalCallKey(run, step);
            AidMediaTask task = mediaTasks.selectByAssistantOperationForUpdate(run.getUserId(),
                    AssistantSkillBudgetIdentity.BIZ_TASK_TYPE, run.getId(), logicalCallKey);
            if (task != null) {
                throw new ServiceException("Assistant Skill durable task appeared during REPLAN");
            }
            AidBillingAuthorizationReservation reservation =
                    reservations.selectByLogicalCallKeyForUpdate(logicalCallKey);
            if (reservation == null) {
                markUnreservedStepCancelled(step, operator, cancellation);
                return;
            }
            ReleaseUnsubmittedReservationRequest release = releaseRequest(run, step, reservation,
                    logicalCallKey, operator);
            reservationReleaseGuard.markReplanNotSubmitted(run.getId(), step.getId(), release,
                    cancellation);
            billingBudgetService.releaseUnsubmittedReservation(release);
        });
    }

    private ReleaseUnsubmittedReservationRequest releaseRequest(
            AidSkillRun run, AidSkillRunStep step,
            AidBillingAuthorizationReservation reservation, String logicalCallKey, String operator)
    {
        AssistantSkillBudgetEnvelope envelope = envelope(run);
        if (!Objects.equals(reservation.getRuntimeRunId(), envelope.getAssistantRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), step.getId())
                || !Objects.equals(reservation.getStableStepKey(),
                AssistantSkillBudgetIdentity.stableStepKey(envelope, step))
                || !Objects.equals(reservation.getApprovalId(), envelope.getApprovalId())
                || !Objects.equals(reservation.getAttemptNo(), step.getWorkflowAttempt())
                || !AssistantSkillUnsubmittedReservationReleaseGuard.OPERATION_KIND.equals(reservation.getOperationKind())
                || !Objects.equals(reservation.getLogicalCallKey(), logicalCallKey)
                || !Objects.equals(reservation.getAuthorizationId(), envelope.getBillingAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(),
                envelope.getBillingAuthorizationRevision())
                || reservation.getMediaTaskId() != null) {
            throw new ServiceException("Assistant Skill reservation identity is invalid");
        }
        ReleaseUnsubmittedReservationRequest request = new ReleaseUnsubmittedReservationRequest();
        request.setAuthorizationId(reservation.getAuthorizationId());
        request.setAuthorizationRevision(reservation.getAuthorizationRevision());
        request.setReservationId(reservation.getId());
        request.setExpectedReservationRevision(reservation.getRevision());
        request.setPayerUserId(run.getUserId());
        request.setRuntimeRunId(reservation.getRuntimeRunId());
        request.setRuntimeStepId(step.getId());
        request.setStableStepKey(reservation.getStableStepKey());
        request.setApprovalId(reservation.getApprovalId());
        request.setAttemptNo(step.getWorkflowAttempt());
        request.setOperationKind(reservation.getOperationKind());
        request.setLogicalCallKey(logicalCallKey);
        request.setOperator(operator);
        return request;
    }

    private void markUnreservedStepCancelled(AidSkillRunStep step, String operator,
                                              MediaTaskCancellationRequest cancellation)
    {
        com.alibaba.fastjson2.JSONObject checkpoint;
        try {
            checkpoint = step.getCheckpointJson() == null || step.getCheckpointJson().isBlank()
                    ? new com.alibaba.fastjson2.JSONObject()
                    : com.alibaba.fastjson2.JSON.parseObject(step.getCheckpointJson());
        } catch (RuntimeException invalid) {
            throw new ServiceException("Assistant Skill checkpoint is invalid");
        }
        if (checkpoint == null) checkpoint = new com.alibaba.fastjson2.JSONObject();
        checkpoint.put("submissionEvidenceCode", "SKILL_REPLAN_CANCELLED_BEFORE_RESERVATION");
        checkpoint.put("cancellationPurpose", MediaTaskCancellationPurpose.REPLAN.name());
        checkpoint.put("replanIntentId", cancellation.replanIntentId());
        checkpoint.put("replanIntentStateRevision", cancellation.replanIntentStateRevision());
        checkpoint.put("replanCommandId", cancellation.replanCommandId());
        checkpoint.put("runFencingToken", cancellation.expectedRuntimeRunFencingToken());
        checkpoint.put("leaseOwner", cancellation.leaseOwner());
        step.setCheckpointJson(com.alibaba.fastjson2.JSON.toJSONString(checkpoint));
        step.setOrchestrationStatus("CANCELLED");
        step.setUpdateBy(operator);
        step.setUpdateTime(new Date());
        if (steps.updateById(step) != 1) {
            throw new ServiceException("Assistant Skill REPLAN step changed");
        }
    }

    private String logicalCallKey(AidSkillRun run, AidSkillRunStep step)
    {
        if (run.getIdempotencyScopeHash() == null || run.getIdempotencyScopeHash().isBlank()
                || run.getGeneration() == null || step.getStepExecutionId() == null
                || step.getStepExecutionId().isBlank() || step.getWorkflowAttempt() == null) {
            throw new ServiceException("Assistant Skill logical call identity is invalid");
        }
        return SecureUtil.sha256(run.getIdempotencyScopeHash() + "|" + run.getGeneration()
                + "|" + step.getStepExecutionId() + "|" + step.getWorkflowAttempt());
    }

    private AidSkillRunTaskLink findAnyLink(Long stepId)
    {
        return taskLinks.selectOne(Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                .eq(AidSkillRunTaskLink::getStepId, stepId)
                .eq(AidSkillRunTaskLink::getDelFlag, NORMAL).last("LIMIT 1 FOR UPDATE"));
    }

    private void settleTerminal(Long runId, List<LinkedTask> expected, String operator,
                                MediaTaskCancellationRequest cancellation)
    {
        transactions.executeWithoutResult(status -> {
            lockReplanAuthorization(cancellation);
            lockRuntimeParents(runId, cancellation);
            AidSkillRun run = lockRun(runId);
            if (TERMINAL_RUN.contains(run.getStatus())) return;
            Date now = new Date();
            for (LinkedTask value : expected)
            {
                AidSkillRunStep step = lockStep(runId, value.step().getId());
                AidSkillRunTaskLink link = lockLink(runId, step.getId(), value.link().getMediaTaskId());
                if (!Objects.equals(link.getStepExecutionId(), step.getStepExecutionId())
                        || !Objects.equals(link.getWorkflowAttempt(), step.getWorkflowAttempt())
                        || !Objects.equals(link.getLogicalCallKey(), value.link().getLogicalCallKey()))
                    throw new ServiceException("Assistant Skill task binding has changed");
                AidMediaTask task = requireTask(run, link);
                if (!TERMINAL_TASK.contains(task.getStatus()))
                    throw new ServiceException("Assistant Skill terminal evidence is incomplete");
                String expectedStatus = MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                        ? "OUTPUT_READY" : MediaTaskStatus.FAILED.name().equals(task.getStatus())
                        ? "FAILED" : "CANCELLED";
                if (TERMINAL_STEP.contains(step.getOrchestrationStatus()))
                {
                    if (!expectedStatus.equals(step.getOrchestrationStatus()))
                        throw new ServiceException("Assistant Skill terminal evidence has changed");
                    continue;
                }
                if (!ACTIVE_STEP.contains(step.getOrchestrationStatus()))
                    throw new ServiceException("Assistant Skill terminal state has changed");
                step.setOrchestrationStatus(expectedStatus);
                step.setUpdateBy(operator);
                step.setUpdateTime(now);
                if (steps.updateById(step) != 1)
                    throw new ServiceException("Assistant Skill terminal state has changed");
            }
            run.setStatus("CANCELED");
            run.setStage("FINALIZED");
            run.setFinishedAt(now);
            run.setUpdateBy(operator);
            run.setUpdateTime(now);
            if (runs.updateById(run) != 1)
                throw new ServiceException("Assistant Skill cancellation state has changed");
        });
    }

    private void markReconciliationRequired(Long runId, String operator,
                                            MediaTaskCancellationRequest cancellation)
    {
        transactions.executeWithoutResult(status -> {
            lockReplanAuthorization(cancellation);
            lockRuntimeParents(runId, cancellation);
            AidSkillRun run = lockRun(runId);
            if (TERMINAL_RUN.contains(run.getStatus())) return;
            Date now = new Date();
            List<AidSkillRunStep> current = steps.selectList(Wrappers.<AidSkillRunStep>lambdaQuery()
                    .eq(AidSkillRunStep::getRunId, runId).eq(AidSkillRunStep::getDelFlag, NORMAL)
                    .orderByAsc(AidSkillRunStep::getId).last("FOR UPDATE"));
            for (AidSkillRunStep step : current)
            {
                if (TERMINAL_STEP.contains(step.getOrchestrationStatus())) continue;
                if (ACTIVE_STEP.contains(step.getOrchestrationStatus())
                        && !"RECONCILING".equals(step.getOrchestrationStatus()))
                {
                    step.setOrchestrationStatus("RECONCILING");
                    step.setUpdateBy(operator);
                    step.setUpdateTime(now);
                    if (steps.updateById(step) != 1)
                        throw new ServiceException("Assistant Skill reconciliation state has changed");
                }
            }
            run.setStatus("RECONCILING");
            run.setStage("WAIT_TASK_RECONCILIATION");
            run.setUpdateBy(operator);
            run.setUpdateTime(now);
            if (runs.updateById(run) != 1)
                throw new ServiceException("Assistant Skill reconciliation state has changed");
        });
    }

    private void requireReplanTicket(Long assistantRuntimeRunId,
                                     MediaTaskCancellationRequest cancellation) {
        if (cancellation == null || cancellation.purpose() != MediaTaskCancellationPurpose.REPLAN
                || !Objects.equals(assistantRuntimeRunId, cancellation.expectedRuntimeRunId())
                || cancellation.expectedRuntimeRunFencingToken() == null
                || cancellation.leaseOwner() == null || cancellation.leaseOwner().isBlank()
                || cancellation.replanIntentId() == null
                || cancellation.replanIntentStateRevision() == null
                || cancellation.replanCommandId() == null) {
            throw new ServiceException("Assistant Skill REPLAN ticket is invalid");
        }
    }

    private RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity
            lockReplanAuthorization(MediaTaskCancellationRequest cancellation) {
        AidRuntimeRun discovered = assistantRuns.selectById(cancellation.expectedRuntimeRunId());
        if (discovered == null || discovered.getSessionId() == null || discovered.getUserId() == null) {
            throw new ServiceException("Assistant REPLAN runtime is unavailable");
        }
        return replanCancellationAuthorization.lockAndVerify(
                new RuntimeReplanCancellationAuthorizationPort.ReplanCancellationAuthorizationRequest(
                        discovered.getId(), discovered.getSessionId(), discovered.getUserId(),
                        cancellation.leaseOwner(), cancellation.expectedRuntimeRunFencingToken(),
                        cancellation.replanIntentId(), cancellation.replanIntentStateRevision(),
                        cancellation.replanCommandId()));
    }

    private AidSkillRun lockRun(Long runId)
    {
        AidSkillRun run = runs.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).eq(AidSkillRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (run == null) throw new ServiceException("Assistant Skill invocation is unavailable");
        return run;
    }

    /** Locks approval and outer step after the Core port has locked session/run/intent. */
    private void lockRuntimeParents(Long skillRunId, MediaTaskCancellationRequest cancellation)
    {
        AidSkillRun route = runs.selectById(skillRunId);
        if (route == null || !NORMAL.equals(route.getDelFlag())) {
            throw new ServiceException("Assistant Skill invocation is unavailable");
        }
        AssistantSkillBudgetEnvelope envelope = envelope(route);
        AidRuntimeApproval approval = assistantApprovals.selectOne(
                Wrappers.<AidRuntimeApproval>lambdaQuery()
                        .eq(AidRuntimeApproval::getId, envelope.getApprovalId())
                        .eq(AidRuntimeApproval::getRunId, cancellation.expectedRuntimeRunId())
                        .eq(AidRuntimeApproval::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidRuntimeRunStep outerStep = assistantSteps.selectOne(
                Wrappers.<AidRuntimeRunStep>lambdaQuery()
                        .eq(AidRuntimeRunStep::getId, envelope.getAssistantParentStepId())
                        .eq(AidRuntimeRunStep::getRunId, cancellation.expectedRuntimeRunId())
                        .eq(AidRuntimeRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (approval == null || outerStep == null
                || !Objects.equals(outerStep.getApprovalId(), approval.getId())
                || !Objects.equals(outerStep.getChildRunId(), skillRunId)
                || !"ASSISTANT_SKILL".equals(outerStep.getOperationKind())) {
            throw new ServiceException("Assistant Skill runtime parent identity is unavailable");
        }
    }

    private AidSkillRunStep lockStep(Long runId, Long stepId)
    {
        AidSkillRunStep step = steps.selectOne(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getId, stepId).eq(AidSkillRunStep::getRunId, runId)
                .eq(AidSkillRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (step == null) throw new ServiceException("Assistant Skill step is unavailable");
        return step;
    }

    private AidSkillRunTaskLink lockLink(Long runId, Long stepId, Long taskId)
    {
        AidSkillRunTaskLink link = taskLinks.selectOne(Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                .eq(AidSkillRunTaskLink::getRunId, runId).eq(AidSkillRunTaskLink::getStepId, stepId)
                .eq(AidSkillRunTaskLink::getMediaTaskId, taskId)
                .eq(AidSkillRunTaskLink::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (link == null) throw new ServiceException("Assistant Skill task binding is unavailable");
        return link;
    }

    private AidMediaTask requireTask(AidSkillRun run, AidSkillRunTaskLink link)
    {
        AidMediaTask task = mediaTasks.selectById(link.getMediaTaskId());
        if (task == null || !Objects.equals(task.getUserId(), run.getUserId())
                || !Objects.equals(task.getBizTaskId(), run.getId())
                || !"SKILL_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(link.getRunId(), run.getId())
                || !Objects.equals(task.getId(), link.getMediaTaskId())
                || !Objects.equals(task.getIdempotencyKey(), link.getLogicalCallKey()))
            throw new ServiceException("Assistant Skill media task identity is invalid");
        return task;
    }

    private AssistantSkillBudgetEnvelope envelope(AidSkillRun run) {
        try {
            com.alibaba.fastjson2.JSONObject input=com.alibaba.fastjson2.JSON.parseObject(run.getInputJson());
            AssistantSkillBudgetEnvelope value=input==null?null:input.getObject("assistantBudgetEnvelope",AssistantSkillBudgetEnvelope.class);
            AssistantSkillBudgetIdentity.validate(value);
            if (!AssistantSkillBudgetIdentity.INVOKE_SOURCE.equals(run.getInvokeSource())
                    || !Objects.equals(value.getSkillVersionId(), run.getSkillVersionId())
                    || !Objects.equals(value.getPayerUserId(),run.getUserId())) throw new ServiceException("Skill budget envelope identity changed");
            return value;
        } catch (RuntimeException invalid) { throw new ServiceException("Skill budget identity is invalid"); }
    }

    private record LinkedTask(AidSkillRunStep step, AidSkillRunTaskLink link) { }
    private record SkillTaskGraph(List<LinkedTask> linked, List<AidSkillRunStep> unlinkedActive) { }
}
