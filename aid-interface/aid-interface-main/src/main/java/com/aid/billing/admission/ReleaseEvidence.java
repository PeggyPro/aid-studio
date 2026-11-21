package com.aid.billing.admission;

/** Canonical identity read while the guard holds the runtime operation rows. */
public record ReleaseEvidence(
        Long payerUserId,
        Long runtimeRunId,
        Long runtimeStepId,
        String stableStepKey,
        Long approvalId,
        Integer attemptNo,
        String operationKind,
        String logicalCallKey,
        String bizTaskType,
        Long bizTaskId) {
}
