package com.aid.media.admission;

/** Result of a trusted cancellation attempt for a durable budget-managed media task. */
public enum MediaTaskCancellationResult {
    CANCELLED,
    ALREADY_TERMINAL,
    RECONCILE_ONLY
}
