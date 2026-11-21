package com.aid.runtime.assistant.execution;

import com.aid.runtime.assistant.RuntimeLease;

/** Identifies an unapplied main decision or its failed media task. */
public record AssistantRejectedDecisionEvidenceCommand(Long runId,
                                                       Long mainAgentStepId,
                                                       RuntimeLease lease,
                                                       Long failedTextTaskId)
{
    public AssistantRejectedDecisionEvidenceCommand(Long runId, Long mainAgentStepId, RuntimeLease lease)
    {
        this(runId, mainAgentStepId, lease, null);
    }
}
