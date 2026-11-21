package com.aid.media.reconciliation;

import com.aid.media.provider.ProviderSubmitResult;

/** Internal completion hook. Implementations apply a persisted receipt to the original task without resubmitting it. */
public interface ProviderSubmitReceiptApplier {
    boolean applyRecordedProviderReceipt(Long taskId, ProviderSubmitResult receipt);

    /** Legacy execution classification may be repaired only from a durable returned synchronous response. */
    default boolean recoverReturnedSynchronousExecution(com.aid.aid.domain.media.AidMediaTask task,
                                                       ProviderSubmitResult receipt) { return false; }
}
