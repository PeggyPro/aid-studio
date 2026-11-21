package com.aid.runtime.assistant;

/** Deterministic, server-owned decision validation failure with no rejected payload attached. */
public final class AssistantDecisionValidationRejectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    private final AssistantDecisionValidationFeedback feedback;

    public AssistantDecisionValidationRejectedException(AssistantDecisionValidationFeedback feedback)
    {
        super("Assistant decision does not match the trusted contract");
        if (feedback == null) throw new IllegalArgumentException("Assistant decision feedback is required");
        this.feedback = feedback;
    }

    public AssistantDecisionValidationFeedback feedback()
    {
        return feedback;
    }
}
