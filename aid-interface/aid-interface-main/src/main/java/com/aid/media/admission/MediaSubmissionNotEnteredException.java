package com.aid.media.admission;

import com.aid.billing.dto.BillingBudgetContext;

/**
 * Typed evidence that this invocation failed before it persisted a new media task intent.
 * Callers must still lock the runtime identity, verify the same fences, and prove that no exact
 * task exists before releasing a reservation. This exception alone is never refund evidence.
 */
public class MediaSubmissionNotEnteredException extends RuntimeException
{
    private final String evidenceCode;
    private final BillingBudgetContext budgetContext;

    public MediaSubmissionNotEnteredException(String evidenceCode, Throwable cause)
    {
        this(evidenceCode, cause, null);
    }

    public MediaSubmissionNotEnteredException(String evidenceCode, Throwable cause,
                                              BillingBudgetContext budgetContext)
    {
        super(evidenceCode == null ? "MEDIA_SUBMISSION_NOT_ENTERED" : evidenceCode, cause);
        this.evidenceCode = evidenceCode == null ? "MEDIA_SUBMISSION_NOT_ENTERED" : evidenceCode;
        this.budgetContext = budgetContext;
    }

    public String getEvidenceCode()
    {
        return evidenceCode;
    }

    public BillingBudgetContext getBudgetContext()
    {
        return budgetContext;
    }
}
