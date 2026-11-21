package com.aid.media.reconciliation;

import com.aid.media.provider.ProviderTaskResult;

/** Result of routing a known provider task id to its one authoritative query adapter. */
public record AuthoritativeProviderTaskQuery(Outcome outcome, ProviderTaskResult taskResult,
                                             String adapterCode, String errorCode) {
    public enum Outcome { EVIDENCE, UNKNOWN, UNSUPPORTED }

    public static AuthoritativeProviderTaskQuery evidence(String adapterCode, ProviderTaskResult result) {
        return new AuthoritativeProviderTaskQuery(Outcome.EVIDENCE, result, adapterCode, null);
    }

    public static AuthoritativeProviderTaskQuery unknown(String adapterCode, String errorCode) {
        return new AuthoritativeProviderTaskQuery(Outcome.UNKNOWN, null, adapterCode, errorCode);
    }

    public static AuthoritativeProviderTaskQuery unsupported(String adapterCode, String errorCode) {
        return new AuthoritativeProviderTaskQuery(Outcome.UNSUPPORTED, null, adapterCode, errorCode);
    }
}
