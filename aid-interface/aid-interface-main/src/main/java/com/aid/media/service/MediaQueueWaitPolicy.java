package com.aid.media.service;

import com.aid.aid.domain.media.MediaAdmissionSnapshot;

/** Admission accounting and the corresponding wait reason use the same observation. */
public final class MediaQueueWaitPolicy {
    public static final String UPSTREAM_RECONCILIATION = "UPSTREAM_RECONCILIATION";

    private MediaQueueWaitPolicy() { }
    static boolean canReserve(MediaAdmissionSnapshot counts, MediaExecutionPermitService.Limits limits) {
        return counts != null && !globalFull(counts, limits) && !userFull(counts, limits)
                && counts.getModelActive() < limits.model() && counts.getProviderActive() < limits.provider();
    }

    public static String reason(MediaAdmissionSnapshot counts, MediaExecutionPermitService.Limits limits) {
        if (counts == null) return null;
        if (positive(counts.getOwnUncertain())) return "SUBMISSION_RESULT_UNKNOWN";
        if (globalFull(counts, limits) && positive(counts.getGlobalUncertain())
                || userFull(counts, limits) && positive(counts.getUserUncertain())
                || blocked(counts.getModelActive(), counts.getModelUncertain(), limits.model())
                || blocked(counts.getProviderActive(), counts.getProviderUncertain(), limits.provider()))
            return UPSTREAM_RECONCILIATION;
        return null;
    }

    static String capacityReason(MediaAdmissionSnapshot counts, MediaExecutionPermitService.Limits limits) {
        if (counts == null) return null;
        if (counts.getModelActive() >= limits.model()) return "MODEL_CAPACITY";
        if (counts.getProviderActive() >= limits.provider()) return "PROVIDER_CAPACITY";
        if (userFull(counts, limits)) return "ACCOUNT_CAPACITY";
        if (globalFull(counts, limits)) return "PLATFORM_CAPACITY";
        return null;
    }

    private static boolean globalFull(MediaAdmissionSnapshot counts, MediaExecutionPermitService.Limits limits) {
        return counts.getGlobalActive() - Math.min(counts.getGlobalUncertain(), limits.reconciliationAllowance())
                >= limits.global();
    }

    private static boolean userFull(MediaAdmissionSnapshot counts, MediaExecutionPermitService.Limits limits) {
        return counts.getUserActive() - Math.min(counts.getUserUncertain(), limits.reconciliationAllowance())
                >= limits.user();
    }
    private static boolean blocked(Long active, Long uncertain, long limit) {
        return limit > 0 && active != null && active >= limit && positive(uncertain);
    }
    private static boolean positive(Long value) { return value != null && value > 0; }
}
