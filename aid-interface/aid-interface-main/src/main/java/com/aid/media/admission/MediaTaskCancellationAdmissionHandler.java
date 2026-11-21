package com.aid.media.admission;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.model.BillingSnapshot;

/**
 * Business-owned proof that a durable media task is stopped and has never crossed the Provider boundary.
 * Implementations run inside the caller transaction and must lock their authoritative business graph first.
 */
public interface MediaTaskCancellationAdmissionHandler {
    String supportedBizTaskType();

    MediaTaskCancellationAdmissionDecision claimCancellation(AidMediaTask task,
                                                              BillingSnapshot snapshot,
                                                              MediaTaskCancellationRequest request);
}
