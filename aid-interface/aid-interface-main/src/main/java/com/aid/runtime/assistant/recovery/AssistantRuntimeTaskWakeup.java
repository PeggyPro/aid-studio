package com.aid.runtime.assistant.recovery;

import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Completion signals accelerate the existing fenced recovery loop; Quartz remains the durable fallback. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantRuntimeTaskWakeup {
    private static final int MAX_PENDING = 256;
    private static final long MAX_WAKE_MILLIS = 120_000L;
    private static final long MAX_LINK_RETRY_MILLIS = 1_000L;
    private static final List<String> RECOVERABLE = List.of("READY", "RUNNING", "PAUSING", "RECONCILING");
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED");
    private final AssistantRuntimeTaskIdentityResolver identities;
    private final AssistantRuntimeRecoveryService recovery;
    private final AidRuntimeRunMapper runs;
    private final ConcurrentHashMap<Long, Wake> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "assistant-task-wakeup");
        thread.setDaemon(true);
        return thread;
    });

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onCompleted(MediaTaskCompletedEvent event) {
        if (event == null || event.getTaskId() == null || event.getUserId() == null || closed) return;
        // No database or provider work on the media completion thread. Duplicate signals coalesce.
        Wake wake = new Wake(event.getTaskId(), event.getUserId(), System.currentTimeMillis() + MAX_WAKE_MILLIS);
        synchronized (pending) {
            if (closed || pending.size() >= MAX_PENDING || pending.putIfAbsent(wake.taskId, wake) != null) return;
        }
        schedule(wake, 0);
    }

    private void schedule(Wake wake, long delayMillis) {
        try { executor.schedule(() -> advance(wake), delayMillis, TimeUnit.MILLISECONDS); }
        catch (RuntimeException ignored) { pending.remove(wake.taskId, wake); }
    }

    private void advance(Wake wake) {
        boolean repeat = false;
        long delay = 100L;
        try {
            if (closed || System.currentTimeMillis() >= wake.deadline) return;
            var resolution = identities.resolveForWakeup(wake.taskId, wake.userId);
            if (resolution.state() == AssistantRuntimeTaskIdentityResolver.ResolutionState.PENDING_LINK) {
                repeat = true;
                delay = wake.nextLinkDelay();
                return;
            }
            var identity = resolution.identity();
            if (identity == null || !TERMINAL.contains(identity.task().getStatus())) return;
            AidRuntimeRun run = identity.run();
            if (!RECOVERABLE.contains(run.getStatus())) return;
            Date now = new Date();
            if (run.getLeaseUntil() != null && run.getLeaseUntil().after(now)) {
                repeat = true;
                delay = 1000L;
                return;
            }
            // Move only this completed attempt's wait forward. Never clear/steal another worker's lease,
            // change revisions, accept an approval or alter a provider-unknown recovery/backoff.
            if (!wake.signalled && Objects.equals(wake.taskId, run.getMediaTaskId())
                    && List.of("WAIT_AGENT_TASK", "WAIT_TASK_RECONCILIATION").contains(run.getNextAction())) {
                int updated = runs.update(null, new LambdaUpdateWrapper<AidRuntimeRun>()
                        .set(AidRuntimeRun::getNextRetryAt, now)
                        .eq(AidRuntimeRun::getId, run.getId()).eq(AidRuntimeRun::getUserId, wake.userId)
                        .eq(AidRuntimeRun::getDelFlag, "0").eq(AidRuntimeRun::getStateRevision, run.getStateRevision())
                        .eq(AidRuntimeRun::getMediaTaskId, wake.taskId)
                        .in(AidRuntimeRun::getStatus, RECOVERABLE).eq(AidRuntimeRun::getNextAction, run.getNextAction())
                        .and(query -> query.isNull(AidRuntimeRun::getLeaseUntil).or().le(AidRuntimeRun::getLeaseUntil, now)));
                if (updated != 1) { repeat = true; delay = 1000L; return; }
                wake.signalled = true;
            }
            recovery.reconcileDueRun(run.getId());
            AidRuntimeRun after = runs.selectById(run.getId());
            repeat = after != null && RECOVERABLE.contains(after.getStatus())
                    && (after.getNextRetryAt() == null || !after.getNextRetryAt().after(new Date()));
            if (after != null && after.getLeaseUntil() != null && after.getLeaseUntil().after(new Date())) delay = 1000L;
        } catch (RuntimeException error) {
            // Persistent Quartz retry owns failure backoff. The optional accelerator never retries a provider itself.
            log.warn("Assistant completion wake unavailable, taskId={}, errorType={}", wake.taskId, error.getClass().getSimpleName());
        } finally {
            if (repeat && !closed && System.currentTimeMillis() < wake.deadline) schedule(wake, delay);
            else pending.remove(wake.taskId, wake);
        }
    }

    @PreDestroy
    void shutdown() {
        closed = true;
        executor.shutdownNow();
        pending.clear();
    }

    private static final class Wake {
        final Long taskId;
        final Long userId;
        final long deadline;
        long linkRetryDelayMillis = 100L;
        boolean signalled;
        Wake(Long taskId, Long userId, long deadline) { this.taskId = taskId; this.userId = userId; this.deadline = deadline; }

        long nextLinkDelay() {
            long current = linkRetryDelayMillis;
            linkRetryDelayMillis = Math.min(MAX_LINK_RETRY_MILLIS, current * 2L);
            return current;
        }
    }
}
