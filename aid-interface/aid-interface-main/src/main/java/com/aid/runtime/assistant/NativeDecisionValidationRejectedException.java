package com.aid.runtime.assistant;

import java.util.Objects;

/** Native-only wrapper emitted after the persisted source and selected alias were verified. */
public final class NativeDecisionValidationRejectedException extends RuntimeException
{
    private final AssistantDecisionValidationFeedback feedback;
    private final NativeRejectedSourceIdentity sourceIdentity;

    public NativeDecisionValidationRejectedException(AssistantDecisionValidationFeedback feedback,
                                                       NativeRejectedSourceIdentity sourceIdentity)
    {
        super("NATIVE_DECISION_VALIDATION_REJECTED");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
    }

    public AssistantDecisionValidationFeedback feedback()
    {
        return feedback;
    }

    public NativeRejectedSourceIdentity sourceIdentity()
    {
        return sourceIdentity;
    }
}
