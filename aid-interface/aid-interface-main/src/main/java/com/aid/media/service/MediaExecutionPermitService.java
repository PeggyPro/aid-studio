package com.aid.media.service;

import com.aid.aid.domain.media.MediaExecutionPermit;
import com.aid.aid.mapper.MediaExecutionPermitMapper;
import com.aid.common.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Execution admissions are independent of media outcomes, account freezes and target locks. */
@Service
public class MediaExecutionPermitService {
    public enum Mode { SYNC_TEXT, SYNC_IMAGE, SYNC_AUDIO, SUBMITTING, ASYNC }
    public enum ReleaseReason { LOCAL_TEXT_CONNECTION_CLOSED, LOCAL_SYNC_CONNECTION_CLOSED, TASK_TERMINAL, RESERVATION_ABORTED }
    public record Limits(int global, int user, int model, int provider, int reconciliationAllowance) {
        public Limits {
            if (global < 1 || user < 1 || model < 1 || provider < 1 || reconciliationAllowance < 0)
                throw new IllegalArgumentException("Invalid execution admission limits");
        }
    }

    private final MediaExecutionPermitMapper permits;
    private final TransactionTemplate transactions;
    private final String ownerId = UUID.randomUUID().toString().replace("-", "");
    private final ConcurrentHashMap<String, Runnable> localText = new ConcurrentHashMap<>();
    private final int leaseSeconds;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-execution-permit-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public MediaExecutionPermitService(MediaExecutionPermitMapper permits, PlatformTransactionManager manager,
            @Value("${aid.media.execution-lease-seconds:180}") int leaseSeconds) {
        if (leaseSeconds < 60 || leaseSeconds > 3600)
            throw new IllegalArgumentException("Execution lease must be between 60 and 3600 seconds");
        this.permits = permits;
        this.leaseSeconds = leaseSeconds;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(10);
    }

    /** Old in-flight tasks remain held; migration never infers their outcome from their age. */
    @PostConstruct
    public void initialize() {
        transactions.executeWithoutResult(status -> {
            if (!Objects.equals(permits.lockMigration(), 1))
                throw new ServiceException("MEDIA_EXECUTION_MIGRATION_REQUIRED");
            permits.importLegacyActiveTasks();
            permits.bindLegacyTaskTokens();
            permits.markReady();
        });
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        for (String token : new ArrayList<>(localText.keySet())) closeLostTransport(token);
    }

    /** The task id is allocated before admission; a failed reservation creates no provider call. */
    public String reserve(Long taskId, Long userId, String userBucket, String modelCode,
                          Long providerId, Limits limits) {
        if (taskId == null || taskId <= 0 || userBucket == null || userBucket.isBlank()
                || userBucket.length() > 160 || modelCode == null || modelCode.isBlank()
                || modelCode.length() > 160 || limits == null)
            throw new ServiceException("MEDIA_EXECUTION_PERMIT_IDENTITY_INVALID");
        return locked(() -> {
            permits.expireLocalLeases();
            if (permits.selectActiveTokenForTask(taskId) != null || !MediaQueueWaitPolicy.canReserve(
                    permits.admissionSnapshot(List.of(taskId), userBucket, modelCode, providerId), limits))
                return null;
            MediaExecutionPermit permit = new MediaExecutionPermit();
            permit.setToken(UUID.randomUUID().toString().replace("-", ""));
            permit.setTaskId(taskId);
            permit.setUserId(userId);
            permit.setUserBucket(userBucket);
            permit.setModelCode(modelCode);
            permit.setProviderId(providerId);
            permit.setOwnerId(ownerId);
            if (permits.insertReserved(permit, leaseSeconds) != 1)
                throw new ServiceException("MEDIA_EXECUTION_PERMIT_RESERVATION_FAILED");
            return permit.getToken();
        });
    }

    /** Must succeed immediately before crossing the provider boundary. */
    public void activate(String token, Mode mode, Runnable closeTransport) {
        requireToken(token);
        if (mode == null || mode != Mode.ASYNC && closeTransport == null)
            throw new ServiceException("MEDIA_EXECUTION_PERMIT_MODE_INVALID");
        boolean activated = locked(() -> permits.activate(token, ownerId, mode.name(), leaseSeconds) == 1);
        if (!activated) throw new ServiceException("MEDIA_EXECUTION_PERMIT_LOST");
        if (mode != Mode.ASYNC) localText.put(token, closeTransport);
    }

    public void requireOwned(String token) {
        requireToken(token);
        if (!Objects.equals(permits.ownedActive(token, ownerId), 1))
            throw new ServiceException("MEDIA_EXECUTION_PERMIT_LOST");
    }

    /** A stale token cannot release a later admission for the same task. */
    public boolean release(String token, ReleaseReason reason) {
        requireToken(token);
        if (reason == null) throw new ServiceException("MEDIA_EXECUTION_PERMIT_REASON_INVALID");
        boolean changed = locked(() -> permits.release(token, reason.name()) == 1);
        localText.remove(token);
        return changed;
    }

    /** The upstream ID and this phase transition commit with the local task update. */
    public void acceptUpstreamTask(String token) {
        requireToken(token);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { promoteAcceptedTask(token); }
                    });
        } else {
            promoteAcceptedTask(token);
        }
    }

    private void promoteAcceptedTask(String token) {
        if (locked(() -> permits.promoteAcceptedTask(token)) == 1) localText.remove(token);
    }

    /** Close only the local request phase; an accepted asynchronous task retains its slot. */
    public void finishLocalCall(String token) {
        requireToken(token);
        locked(() -> {
            permits.promoteAcceptedTask(token);
            var permit = permits.selectByToken(token);
            if (permit != null && "ACTIVE".equals(permit.getState())
                    && java.util.Set.of("SYNC_TEXT", "SYNC_IMAGE", "SYNC_AUDIO", "SUBMITTING")
                            .contains(permit.getExecutionMode()))
                permits.release(token, ReleaseReason.LOCAL_SYNC_CONNECTION_CLOSED.name());
            return null;
        });
        localText.remove(token);
    }

    /** Caller has verified a persisted returned response and the frozen synchronous adapter contract. */
    public boolean releaseReturnedSynchronous(String token, Long taskId, Mode mode) {
        requireToken(token);
        if (taskId == null || (mode != Mode.SYNC_TEXT && mode != Mode.SYNC_IMAGE && mode != Mode.SYNC_AUDIO))
            throw new ServiceException("MEDIA_EXECUTION_PERMIT_MODE_INVALID");
        return locked(() -> permits.releaseReturnedSynchronous(token, taskId, mode.name()) == 1);
    }

    public long count(String userBucket, String modelCode, Long providerId) {
        return permits.countActive(userBucket, modelCode, providerId);
    }

    public String queueWaitReason(List<Long> taskIds, String userBucket, String modelCode,
                                  Long providerId, Limits limits) {
        return MediaQueueWaitPolicy.reason(
                permits.admissionSnapshot(taskIds, userBucket, modelCode, providerId), limits);
    }

    public String capacityWaitReason(Long taskId, String userBucket, String modelCode, Long providerId, Limits limits) {
        return MediaQueueWaitPolicy.capacityReason(
                permits.admissionSnapshot(List.of(taskId), userBucket, modelCode, providerId), limits);
    }

    public Map<String, Integer> countModels(List<String> codes) {
        Map<String, Integer> result = new HashMap<>();
        if (codes == null || codes.isEmpty()) return result;
        codes.forEach(code -> result.put(code, 0));
        permits.countModels(codes).forEach(row -> result.put(row.getModelCode(), boundedCount(row.getActiveCount())));
        return result;
    }

    public Map<Long, Integer> countProviders(List<Long> ids) {
        Map<Long, Integer> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) return result;
        ids.forEach(id -> result.put(id, 0));
        permits.countProviders(ids).forEach(row -> result.put(row.getProviderId(), boundedCount(row.getActiveCount())));
        return result;
    }

    private static int boundedCount(Long count) {
        return count == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, count));
    }

    public void heartbeat() {
        var tokens = new ArrayList<>(localText.keySet());
        try {
            locked(() -> {
                if (!tokens.isEmpty()) permits.renew(ownerId, tokens, leaseSeconds);
                permits.promoteAcceptedTasks();
                permits.expireLocalLeases();
                // A terminal commit may outlive its after-commit callback or the process itself.
                // Only observe confirmed terminal tasks; never manufacture their outcome.
                for (String token : permits.selectTerminalTokens())
                    permits.release(token, ReleaseReason.TASK_TERMINAL.name());
                return null;
            });
            for (String token : tokens)
                if (!Objects.equals(permits.ownedActive(token, ownerId), 1)) closeLostTransport(token);
        } catch (RuntimeException unavailable) {
            // Without a renewable execution lease, close local I/O. Outcome handling remains in
            // the media worker; this service cannot settle, refund, replay or unlock a task.
            for (String token : tokens) closeLostTransport(token);
        }
    }

    private void closeLostTransport(String token) {
        Runnable close = localText.remove(token);
        if (close != null) {
            try { close.run(); } catch (RuntimeException ignored) { }
        }
    }

    private <T> T locked(Supplier<T> action) {
        return transactions.execute(status -> {
            if (!Objects.equals(permits.lockAdmission(), 1))
                throw new ServiceException("MEDIA_EXECUTION_ADMISSION_UNAVAILABLE");
            return action.get();
        });
    }

    private static void requireToken(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}"))
            throw new ServiceException("MEDIA_EXECUTION_PERMIT_IDENTITY_INVALID");
    }
}
