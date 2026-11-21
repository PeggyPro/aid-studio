package com.aid.media.provider;

/** A request may already have been accepted upstream. Preserve the task and its frozen balance for reconciliation. */
public class ProviderSubmissionOutcomeUnknownException extends RuntimeException {
    public ProviderSubmissionOutcomeUnknownException() {
        this(null);
    }
    public ProviderSubmissionOutcomeUnknownException(String diagnostic) {
        super(ProviderErrorSanitizer.safeMessage(diagnostic, "上游提交状态待核")
                + "；结果待核实，请勿重复提交");
    }
}
