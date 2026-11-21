package com.aid.runtime.assistant;

/** Model-facing adapter for one assistant-loop protocol version. */
public interface AssistantLoopExecutor
{
    String supportedProtocolVersion();

    /** Opt in only when the adapter enforces preparation-only decisions and resumes paid execution. */
    default boolean supportsContextPreparation() { return false; }

    AssistantLoopSubmission prepareIteration(AssistantLoopExecutionContext context);

    AssistantLoopDecision parseDecision(AssistantLoopModelResult persistedResult,
                                        AssistantLoopBinding binding);
}
