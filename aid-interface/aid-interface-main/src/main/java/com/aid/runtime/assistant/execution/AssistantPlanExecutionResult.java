package com.aid.runtime.assistant.execution;

import java.util.List;

/** Persisted child-operation projection returned to the run Coordinator. */
public record AssistantPlanExecutionResult(Long childStepId,
                                           String status,
                                           String nextAction,
                                           List<String> outputArtifactRefs,
                                           Long mediaTaskId,
                                           String safeSummary,
                                           String errorCode,
                                           boolean retryable)
{
    public AssistantPlanExecutionResult
    {
        outputArtifactRefs = outputArtifactRefs == null ? List.of() : List.copyOf(outputArtifactRefs);
    }
}
