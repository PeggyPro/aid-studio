package com.aid.runtime.assistant.execution;

import com.aid.runtime.assistant.RuntimeLease;

/** Exact persisted main-Agent decision plus the current durable run lease. */
public record AssistantPlanExecutionCommand(Long runId,
                                            Long mainAgentStepId,
                                            String mainDecisionJson,
                                            String operationDecisionJson,
                                            int batchIndex,
                                            int batchSize,
                                            RuntimeLease lease)
{
    public AssistantPlanExecutionCommand(Long runId, Long mainAgentStepId,
                                         String decisionJson, RuntimeLease lease)
    {
        this(runId, mainAgentStepId, decisionJson, decisionJson, 0, 1, lease);
    }

    public boolean batchMember()
    {
        return batchSize > 1;
    }
}
