package com.aid.media.admission;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.model.BillingSnapshot;

/**
 * 业务模块在Provider出站边界实现的事务型认领SPI。
 * 实现必须从已落库task与snapshot重建状态，并在同一事务内提交业务CAS与预算claim。
 */
public interface MediaSubmissionAdmissionHandler {

    String supportedBizTaskType();

    MediaSubmissionAdmissionDecision claimBeforeProvider(AidMediaTask task, BillingSnapshot snapshot);

    /** Persist the owning business step as reconciliation-only after an ambiguous submission. */
    void markReconciliationRequired(AidMediaTask task, BillingSnapshot snapshot);
}
