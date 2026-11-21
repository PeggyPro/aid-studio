package com.aid.runtime.assistant.recovery;

/** Scans durable runtime state and reconciles claimed runs. */
public interface AssistantRuntimeRecoveryService
{
    int reconcileDueRuns(int limit);

    /** Targeted event wakeup uses exactly the same durable lease and coordinator boundary. */
    boolean reconcileDueRun(Long runId);
}
