package com.aid.runtime.assistant;

/** Hash-only identity for one fully verified persisted Native model result. */
public record NativeRejectedSourceIdentity(long runId,
                                           long mediaTaskId,
                                           long observedRunRevision,
                                           long observedPlanRevision,
                                           String toolMessageDigest,
                                           String nativeBindingsDigest,
                                           String trustedAliasDigest)
{
    private static final String SHA256 = "^[0-9a-f]{64}$";

    public NativeRejectedSourceIdentity
    {
        if (runId < 1 || mediaTaskId < 1 || observedRunRevision < 0 || observedPlanRevision < 0
                || !digest(toolMessageDigest) || !digest(nativeBindingsDigest)
                || !digest(trustedAliasDigest))
            throw new IllegalArgumentException("Invalid Native rejected source identity");
    }

    private static boolean digest(String value)
    {
        return value != null && value.matches(SHA256);
    }
}
