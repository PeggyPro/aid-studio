package com.aid.runtime.assistant.recovery;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reconstructs queue-worker ownership from committed rows, never client identifiers or URLs. */
@Component
@RequiredArgsConstructor
public class AssistantRuntimeTaskIdentityResolver {
    private final AidMediaTaskMapper tasks;
    private final AidRuntimeRunStepMapper steps;
    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;

    public Identity resolve(Long taskId, Long userId) {
        return resolveForWakeup(taskId, userId).identity();
    }

    public Resolution resolveForWakeup(Long taskId, Long userId) {
        if (taskId == null || userId == null) return Resolution.ineligible();
        AidMediaTask task = tasks.selectById(taskId);
        if (task == null || !Objects.equals(userId, task.getUserId())
                || !"ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                || !"TEXT".equals(task.getMediaType()) || task.getBizTaskId() == null)
            return Resolution.ineligible();
        AidRuntimeRunStep step = steps.selectById(task.getBizTaskId());
        if (step == null || !"0".equals(step.getDelFlag()) || !"MAIN_AGENT".equals(step.getStepKind())
                || step.getMediaTaskId() != null && !Objects.equals(taskId, step.getMediaTaskId())
                || task.getIdempotencyKey() == null
                || !Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey()))
            return Resolution.ineligible();
        AidRuntimeRun run = runs.selectById(step.getRunId());
        if (run == null || !"0".equals(run.getDelFlag()) || !Objects.equals(userId, run.getUserId()))
            return Resolution.ineligible();
        AidAssistantSession session = sessions.selectById(run.getSessionId());
        if (session == null || !"0".equals(session.getDelFlag())
                || !"ASSISTANT".equals(session.getSessionType()) || !Objects.equals(userId, session.getUserId())
                || !Objects.equals(run.getId(), session.getActiveRuntimeRunId()))
            return Resolution.ineligible();
        AidRuntimeRunStep latest = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getMediaTaskId)
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 1"));
        if (latest == null || !Objects.equals(step.getId(), latest.getId())) return Resolution.ineligible();
        if (Objects.equals(taskId, step.getMediaTaskId()) && Objects.equals(taskId, run.getMediaTaskId()))
            return Resolution.resolved(new Identity(task, step, run, session));
        if (!isPendingCoordinatorLink(taskId, step, run)) return Resolution.ineligible();
        return Resolution.pendingLink();
    }

    private boolean isPendingCoordinatorLink(Long taskId, AidRuntimeRunStep step, AidRuntimeRun run) {
        if (!"RUNNING".equals(run.getStatus()) || !"WAIT_AGENT_TASK".equals(run.getNextAction())) return false;
        boolean intentLinkPending = step.getMediaTaskId() == null
                && "READY".equals(step.getStatus())
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState());
        boolean runProjectionPending = Objects.equals(taskId, step.getMediaTaskId())
                && (("READY".equals(step.getStatus())
                        && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
                    || ("SUBMITTING".equals(step.getStatus())
                        && "SUBMITTING".equals(step.getProviderAcceptanceState())));
        if (!intentLinkPending && !runProjectionPending) return false;
        if (run.getMediaTaskId() == null) return true;
        AidRuntimeRunStep previous = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getMediaTaskId)
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .isNotNull(AidRuntimeRunStep::getMediaTaskId)
                .lt(AidRuntimeRunStep::getId, step.getId())
                .orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 1"));
        return previous != null && Objects.equals(run.getMediaTaskId(), previous.getMediaTaskId());
    }

    public enum ResolutionState { RESOLVED, PENDING_LINK, INELIGIBLE }

    public record Resolution(ResolutionState state, Identity identity) {
        static Resolution resolved(Identity identity) {
            return new Resolution(ResolutionState.RESOLVED, identity);
        }

        static Resolution pendingLink() {
            return new Resolution(ResolutionState.PENDING_LINK, null);
        }

        static Resolution ineligible() {
            return new Resolution(ResolutionState.INELIGIBLE, null);
        }
    }

    public record Identity(AidMediaTask task, AidRuntimeRunStep step,
                           AidRuntimeRun run, AidAssistantSession session) {}
}
