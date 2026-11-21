package com.aid.assistant.skill;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.service.TaskCompletionService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
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

/** Drives STOP reconciliation for durable media tasks owned by an Assistant fixed Skill invocation. */
@Service
@RequiredArgsConstructor
public class AssistantSkillStopReconciliationService
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

    public void reconcile(Long assistantRuntimeRunId, Long assistantParentStepId,
                          String assistantOuterStableStepKey, Integer assistantAttemptNo,
                          Long userId, String operator)
    {
        AidSkillRun run = requireInvocation(assistantRuntimeRunId, assistantParentStepId,
                assistantOuterStableStepKey, assistantAttemptNo, userId);
        if (TERMINAL_RUN.contains(run.getStatus())) return;
        SkillTaskGraph graph = taskGraph(run);
        if (graph.linked().isEmpty() && !graph.hasUnlinkedActiveStep()) return;
        for (LinkedTask value : graph.linked())
        {
            AidMediaTask task = requireTask(run, value.link());
            if (TERMINAL_TASK.contains(task.getStatus())) continue;
            if (Set.of("WAITING_TASK", "RECONCILING").contains(value.step().getOrchestrationStatus())
                    && Set.of(MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name()).contains(task.getStatus()))
            {
                try
                {
                    taskCompletionService.cancelBudgetManagedUnsubmittedTask(task.getId());
                }
                catch (RuntimeException uncertain)
                {
                    // An incomplete refund or ambiguous submission stays on the reconciliation path below.
                }
            }
        }
        SkillTaskGraph current = taskGraph(run);
        boolean allTerminal = !current.hasUnlinkedActiveStep() && !current.linked().isEmpty()
                && current.linked().stream().allMatch(value ->
                TERMINAL_TASK.contains(requireTask(run, value.link()).getStatus()));
        if (allTerminal) settleTerminal(run.getId(), current.linked(), operator);
        else markReconciliationRequired(run.getId(), operator);
    }

    private AidSkillRun requireInvocation(Long assistantRuntimeRunId, Long assistantParentStepId,
                                          String stableStepKey, Integer attemptNo, Long userId)
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
        AidRuntimeRun outer = assistantRuns.selectById(assistantRuntimeRunId);
        if (outer == null || !NORMAL.equals(outer.getDelFlag())
                || !Objects.equals(userId, outer.getUserId())
                || !Boolean.TRUE.equals(outer.getStopRequested()))
            throw new ServiceException("Assistant run has not accepted STOP");
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
        boolean unlinkedActive = false;
        for (AidSkillRunStep step : persistedSteps)
        {
            List<AidSkillRunTaskLink> links = persistedLinks.stream()
                    .filter(value -> Objects.equals(value.getStepId(), step.getId())).toList();
            if (links.size() > 1) throw new ServiceException("Assistant Skill task identity is ambiguous");
            if (links.isEmpty())
            {
                if (ACTIVE_STEP.contains(step.getOrchestrationStatus())) unlinkedActive = true;
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
        return new SkillTaskGraph(result, unlinkedActive);
    }

    private void settleTerminal(Long runId, List<LinkedTask> expected, String operator)
    {
        transactions.executeWithoutResult(status -> {
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

    private void markReconciliationRequired(Long runId, String operator)
    {
        transactions.executeWithoutResult(status -> {
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

    private AidSkillRun lockRun(Long runId)
    {
        AidSkillRun run = runs.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).eq(AidSkillRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (run == null) throw new ServiceException("Assistant Skill invocation is unavailable");
        return run;
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

    private record LinkedTask(AidSkillRunStep step, AidSkillRunTaskLink link) { }
    private record SkillTaskGraph(List<LinkedTask> linked, boolean hasUnlinkedActiveStep) { }
}
