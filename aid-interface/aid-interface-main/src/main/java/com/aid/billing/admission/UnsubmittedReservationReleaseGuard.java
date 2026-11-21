package com.aid.billing.admission;

import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;

/**
 * Trusted persistent proof that a runtime child reached a pre-task terminal state.
 * Implementations lock their runtime rows in the global runtime lock order. The billing
 * service then locks authorization and reservation rows before recording the outcome.
 */
public interface UnsubmittedReservationReleaseGuard {

    boolean supports(String persistedOperationKind);

    ReleaseEvidence lockAndVerify(ReleaseUnsubmittedReservationRequest request,
                                  ReservationIdentity persistedIdentity);

    void recordOutcome(ReleaseEvidence evidence, ReleaseOutcome outcome);
}
