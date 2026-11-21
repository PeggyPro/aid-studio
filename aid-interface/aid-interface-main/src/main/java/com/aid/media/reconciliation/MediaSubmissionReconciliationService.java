package com.aid.media.reconciliation;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.media.provider.ProviderSubmitResult;

import java.util.Map;
import java.util.Optional;

/** Durable evidence journal and explicit reconciliation scheduler for uncertain provider submissions. */
public interface MediaSubmissionReconciliationService {
    MediaSubmissionEvidenceVerdict recordProviderReceipt(AidMediaTask task, ProviderSubmitResult result);

    void recordOutcomeUnknown(AidMediaTask task, Throwable cause);

    /** An observed success/failure receipt or accepted task id must be recovered before local failure. */
    boolean hasRecoverableReceipt(AidMediaTask task);

    /**
     * Returns identity-checked, digest-checked terminal provider evidence for billing recovery.
     * No provider request is made by this read path.
     */
    Optional<AuthoritativeTerminalBillingEvidence> loadTerminalBillingEvidence(AidMediaTask task);

    int reconcileDueSubmissions(int batchSize);

    /** Targeted scheduling with the same due-time and lease guards as the batch worker. */
    boolean reconcileDueSubmission(Long taskId);

    /** Explicit operator review may skip backoff, but never an active recovery lease. */
    boolean reconcileSubmissionNow(Long taskId);

    /** Local accounting conflicts must not fall through to provider polling or manual failure. */
    default Optional<String> loadLocalReviewReason(Long taskId) { return Optional.empty(); }

    /** Records an operator terminal decision in the caller's task/billing transaction. */
    void recordOperatorTerminalFailure(AidMediaTask task, Long operatorId, String reason);

    /** Persists a local failure policy decision in the task/refund transaction. */
    void recordLocalTerminalFailure(AidMediaTask task, com.aid.media.model.MediaTaskFailureReason reason);

    record AuthoritativeTerminalBillingEvidence(String taskStatus, Map<String, Object> usageData) { }
}
