package com.aid.media.admission;

/**
 * Trusted cancellation ticket. REPLAN fields are issued by the run-lease owner and are verified
 * against the locked run and active intent; they are never accepted from a public request.
 */
public record MediaTaskCancellationRequest(Long taskId,
                                           MediaTaskCancellationPurpose purpose,
                                           Long expectedRuntimeRunId,
                                           Long expectedRuntimeRunFencingToken,
                                           String leaseOwner,
                                           Long replanIntentId,
                                           Long replanIntentStateRevision,
                                           Long replanCommandId) {
    public static MediaTaskCancellationRequest stop(Long taskId) {
        return new MediaTaskCancellationRequest(taskId, MediaTaskCancellationPurpose.STOP,
                null, null, null, null, null, null);
    }

    public boolean isReplan() {
        return purpose == MediaTaskCancellationPurpose.REPLAN;
    }

    public MediaTaskCancellationRequest withTaskId(Long replacementTaskId) {
        return new MediaTaskCancellationRequest(replacementTaskId, purpose, expectedRuntimeRunId,
                expectedRuntimeRunFencingToken, leaseOwner, replanIntentId,
                replanIntentStateRevision, replanCommandId);
    }
}
