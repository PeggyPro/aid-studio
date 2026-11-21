package com.aid.runtime.assistant.execution;

import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;

/** Durable execution boundary for one approved TOOL or SKILL decision. */
public interface AssistantPlanExecutionService
{
    AssistantPlanExecutionResult applyDecision(AssistantPlanExecutionCommand command);

    /**
     * Rechecks under the operation-creation lock order that this immutable main decision has no
     * child-operation or orphan approval evidence. When called inside a surrounding transaction,
     * the proof locks remain held through the caller's terminal receipt mutation.
     */
    boolean hasNoOperationEvidence(AssistantPlanExecutionCommand command);

    /** Same locked child/orphan graph proof for a verified MAIN receipt rejected before DECISION_READY. */
    boolean hasNoOperationEvidence(AssistantRejectedDecisionEvidenceCommand command);

    /**
     * Replays one code-owned parsed-decision rejection under the operation graph locks. Only
     * explicitly supported reasons return bounded feedback; changed or unknown evidence fails.
     */
    AssistantDecisionValidationFeedback requireRejectedDecisionFeedback(
            AssistantPlanExecutionCommand command,
            AssistantPlanDecisionRejectedException.Reason expectedReason);

    AssistantPlanExecutionConfirmation confirm(AssistantPlanExecutionConfirmCommand command);
    /** Quote-only; never schedules or reserves an operation. */
    AssistantPlanExecutionQuoted quote(AssistantPlanExecutionQuoteCommand command);
}
