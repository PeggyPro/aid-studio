package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;

/** Identifies a definitive completion-contract rejection without classifying infrastructure failures. */
public final class AssistantCompletionDecisionRejectedException extends RuntimeException
{
    private final Reason reason;

    public AssistantCompletionDecisionRejectedException(Reason reason, ServiceException cause)
    {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason()
    {
        return reason;
    }

    public enum Reason
    {
        COMPLETION_EVIDENCE_REQUIRED,
        COMPLETION_ARTIFACT_REQUIRED,
        COMPLETION_PLAN_CRITERIA_MISSING,
        COMPLETION_PLAN_CRITERIA_INVALID,
        COMPLETION_PLAN_STEPS_INCOMPLETE,
        COMPLETION_ARTIFACT_INVALID,
        COMPLETION_ARTIFACT_STALE_OR_UNREVIEWED,
        COMPLETION_CRITERION_FAILED,
        COMPLETION_CRITERION_NOT_IN_PLAN,
        COMPLETION_CRITERION_EVIDENCE_REQUIRED,
        COMPLETION_TARGET_EVIDENCE_INVALID,
        COMPLETION_METHOD_EVIDENCE_INVALID,
        COMPLETION_CRITERION_EVIDENCE_INVALID,
        COMPLETION_REVIEW_EVIDENCE_REQUIRED,
        COMPLETION_CRITERIA_INCOMPLETE,
        COMPLETION_TECHNICAL_EVIDENCE_INVALID;

        public static Reason fromCode(String code)
        {
            if (code == null) return null;
            try { return valueOf(code); }
            catch (IllegalArgumentException unknown) { return null; }
        }
    }
}
