package com.aid.runtime.assistant.recovery;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Accelerates a committed, immediately-due run through the existing fenced recovery path. */
@Component
@Slf4j
public class AssistantRuntimeDueRunWakeup {
    private static final int MAX_PENDING = 256;
    private static final long MAX_WAKE_MILLIS = 30_000L;
    private static final long LEASE_RETRY_MILLIS = 1_000L;

    private final ObjectProvider<AssistantRuntimeRecoveryService> recoveryProvider;
    private final ConcurrentHashMap<Long, Wake> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "assistant-due-run-wakeup");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    public AssistantRuntimeDueRunWakeup(ObjectProvider<AssistantRuntimeRecoveryService> recoveryProvider) {
        this.recoveryProvider = recoveryProvider;
    }

    /** Register only the run identity in the transaction; recovery starts after a successful commit. */
    public void wakeAfterCommit(Long runId) {
        if (runId == null || runId <= 0 || closed) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                // Quartz remains the durable fallback. Never expose uncommitted state to recovery.
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal(runId);
                }
            });
            return;
        }
        signal(runId);
    }

    private void signal(Long runId) {
        Wake wake;
        synchronized (pending) {
            if (closed) return;
            wake = pending.get(runId);
            if (wake != null) {
                wake.resignalled = true;
                return;
            }
            if (pending.size() >= MAX_PENDING) return;
            wake = new Wake(runId, System.currentTimeMillis() + MAX_WAKE_MILLIS);
            pending.put(runId, wake);
        }
        schedule(wake, 0L);
    }

    private void schedule(Wake wake, long delayMillis) {
        try {
            executor.schedule(() -> advance(wake), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            pending.remove(wake.runId, wake);
        }
    }

    private void advance(Wake wake) {
        boolean claimed = false;
        boolean failed = false;
        try {
            if (closed || System.currentTimeMillis() >= wake.deadline) return;
            AssistantRuntimeRecoveryService recovery = recoveryProvider.getIfAvailable();
            if (recovery == null) return;
            claimed = recovery.reconcileDueRun(wake.runId);
        } catch (RuntimeException error) {
            failed = true;
            // Quartz owns persistent retry/backoff. Do not include request, provider or reasoning content.
            log.warn("Assistant due-run wake unavailable, runId={}, errorType={}",
                    wake.runId, error.getClass().getSimpleName());
        } finally {
            boolean repeat;
            long delayMillis;
            synchronized (pending) {
                if (wake.resignalled && !closed && System.currentTimeMillis() < wake.deadline) {
                    wake.resignalled = false;
                    repeat = true;
                    delayMillis = 0L;
                } else if (!claimed && !failed && !closed && System.currentTimeMillis() < wake.deadline) {
                    repeat = true;
                    delayMillis = LEASE_RETRY_MILLIS;
                } else {
                    repeat = false;
                    delayMillis = 0L;
                    pending.remove(wake.runId, wake);
                }
            }
            if (repeat) schedule(wake, delayMillis);
        }
    }

    @PreDestroy
    void shutdown() {
        closed = true;
        executor.shutdownNow();
        pending.clear();
    }

    private static final class Wake {
        final Long runId;
        final long deadline;
        boolean resignalled;

        Wake(Long runId, long deadline) {
            this.runId = runId;
            this.deadline = deadline;
        }
    }
}
