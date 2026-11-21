package com.aid.billing.admission;

/** Immutable persisted reservation identity supplied to a trusted release guard. */
public record ReservationIdentity(
        Long reservationId,
        Long authorizationId,
        Long authorizationRevision,
        Long runtimeRunId,
        Long runtimeStepId,
        String stableStepKey,
        Long approvalId,
        Integer attemptNo,
        String operationKind,
        String logicalCallKey) {
}
