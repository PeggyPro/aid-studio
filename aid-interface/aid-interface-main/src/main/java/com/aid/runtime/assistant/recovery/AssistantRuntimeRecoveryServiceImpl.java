package com.aid.runtime.assistant.recovery;

import cn.hutool.core.util.IdUtil;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantPlanningService;
import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.runtime.assistant.AssistantLoopCoordinator;
import com.aid.runtime.assistant.AssistantLoopReconcileResult;
import com.aid.runtime.assistant.RuntimeLease;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Claims due runs before invoking the durable loop reconciliation boundary. */
@Slf4j
@Service
public class AssistantRuntimeRecoveryServiceImpl implements AssistantRuntimeRecoveryService
{
    private static final int MAX_BATCH_SIZE = 200;
    private static final long MIN_LEASE_MILLIS = 5_000L;
    private static final long MAX_LEASE_MILLIS = 300_000L;

    private final String workerId = "assistant-recovery:" + IdUtil.fastSimpleUUID();

    @Resource
    private AssistantRuntimeLeaseService leaseService;
    @Resource
    private AssistantLoopCoordinator coordinator;
    @Resource
    private AssistantPlanningService planning;
    @Resource
    private AssistantRuntimeDueRunWakeup wakeup;
    @Resource
    private AidRuntimeRunMapper runs;

    @Value("${assistant.recovery.lease-millis:60000}")
    private long configuredLeaseMillis;
    @Value("${assistant.recovery.failure-backoff-millis:30000}")
    private long configuredFailureBackoffMillis;

    @Override
    public int reconcileDueRuns(int limit)
    {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
        Date scanTime = new Date();
        List<Long> candidates = leaseService.findDueRunIds(scanTime, boundedLimit);
        int handled = 0;
        for (Long runId : candidates)
        {
            Date claimTime = new Date();
            Date leaseUntil = plusMillis(claimTime, leaseMillis());
            Optional<RuntimeLease> claimed = leaseService.tryClaimRun(
                    runId, workerId, claimTime, leaseUntil);
            if (claimed.isEmpty())
            {
                continue;
            }
            handled++;
            reconcileClaimed(runId, claimed.get());
        }
        return handled;
    }

    @Override
    public boolean reconcileDueRun(Long runId)
    {
        if (runId == null || runId <= 0) return false;
        Date now = new Date();
        Optional<RuntimeLease> claimed = leaseService.tryClaimRun(
                runId, workerId, now, plusMillis(now, leaseMillis()));
        if (claimed.isEmpty()) return false;
        reconcileClaimed(runId, claimed.get());
        return true;
    }

    private void reconcileClaimed(Long runId, RuntimeLease lease)
    {
        try
        {
            boolean waiting = false;
            if (!planning.advanceAutomaticText(runId, lease))
            {
                AssistantLoopReconcileResult result = coordinator.reconcile(runId, lease);
                if (result == null || !runId.equals(result.getRunId()))
                    throw new IllegalStateException("Assistant reconciliation returned the wrong run");
                waiting = "WAITING_APPROVAL".equals(result.getStatus());
            }
            if (!leaseService.releaseRun(runId, lease, new Date())) {
                log.info("Assistant recovery lease changed before release, runId={}, fence={}",
                        runId, lease.getFencingToken());
            } else if (waiting && AssistantAutomaticTextExecution.isPending(runs.selectById(runId))) {
                // Retain a coalesced wake through model backoff; claims still reject early or stale execution.
                wakeup.wakeAfterCommit(runId);
            }
        }
        catch (RuntimeException error)
        {
            Date failedAt = new Date();
            Date nextRetryAt = plusMillis(failedAt, failureBackoffMillis());
            try
            {
                boolean deferred = leaseService.deferAndReleaseRun(runId, lease, failedAt, nextRetryAt);
                log.warn("Assistant recovery reconcile failed, runId={}, fence={}, deferred={}, errorType={}, errorCode={}, errorOrigin={}",
                        runId, lease.getFencingToken(), deferred, error.getClass().getSimpleName(), safeErrorCode(error), safeErrorOrigin(error));
            }
            catch (RuntimeException deferError)
            {
                error.addSuppressed(deferError);
                log.error("Assistant recovery failed and could not defer the run, runId={}, fence={}, errorType={}, errorCode={}, errorOrigin={}",
                        runId, lease.getFencingToken(), error.getClass().getSimpleName(), safeErrorCode(error), safeErrorOrigin(error));
            }
        }
    }

    /** Log only stable failure codes, never request or provider content. */
    static String safeErrorCode(RuntimeException error)
    {
        if (error instanceof AssistantServiceException assistant && stableCode(assistant.getErrorCode()))
            return assistant.getErrorCode();
        if (error instanceof ServiceException service) {
            var details = TaskErrorSnapshot.read(service.getTaskErrorJson());
            if (details != null && stableCode(details.getErrorCode())) return details.getErrorCode();
            if (stableCode(service.getDetailMessage())) return service.getDetailMessage();
            if (stableCode(service.getMessage())) return service.getMessage();
        }
        return "UNCLASSIFIED";
    }

    private static boolean stableCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,79}");
    }

    /** Code-owned location only; no throwable messages, arguments or source text. */
    static String safeErrorOrigin(RuntimeException error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            if (!frame.getClassName().startsWith("com.aid.")) continue;
            String origin = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            return origin.length() <= 240 && origin.matches("[A-Za-z0-9_.$<>]+:-?[0-9]+") ? origin : "UNAVAILABLE";
        }
        return "UNAVAILABLE";
    }

    private long leaseMillis()
    {
        return Math.max(MIN_LEASE_MILLIS, Math.min(configuredLeaseMillis, MAX_LEASE_MILLIS));
    }

    private long failureBackoffMillis()
    {
        return Math.max(MIN_LEASE_MILLIS, Math.min(configuredFailureBackoffMillis, MAX_LEASE_MILLIS));
    }

    private Date plusMillis(Date base, long millis)
    {
        return new Date(Math.addExact(base.getTime(), millis));
    }
}
