package com.aid.runtime.assistant.recovery;

import com.aid.runtime.assistant.RuntimeLease;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Database-authoritative leases used by assistant runtime workers. */
public interface AssistantRuntimeLeaseService
{
    List<Long> findDueRunIds(Date now, int limit);

    Optional<RuntimeLease> tryClaimRun(Long runId, String owner, Date now, Date leaseUntil);

    boolean renewRun(Long runId, RuntimeLease lease, Date now, Date leaseUntil);

    boolean releaseRun(Long runId, RuntimeLease lease, Date now);

    boolean deferAndReleaseRun(Long runId, RuntimeLease lease, Date now, Date nextRetryAt);

    boolean isRunLeaseCurrent(Long runId, RuntimeLease lease, Date now);

    Optional<RuntimeLease> tryClaimStep(Long runId, Long stepId, RuntimeLease runLease,
                                        Date now, Date leaseUntil);

    boolean renewStep(Long runId, Long stepId, RuntimeLease runLease, RuntimeLease stepLease,
                      Date now, Date leaseUntil);

    boolean releaseStep(Long runId, Long stepId, RuntimeLease lease, Date now);
}
