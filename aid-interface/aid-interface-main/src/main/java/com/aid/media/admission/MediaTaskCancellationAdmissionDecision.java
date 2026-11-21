package com.aid.media.admission;

/** Authoritative business decision for cancelling a durable task before Provider entry. */
public enum MediaTaskCancellationAdmissionDecision {
    CANCEL_NOT_SUBMITTED,
    RECONCILE_ONLY
}
