package com.aid.runtime.assistant;

/** Durable coordinator for main-agent iterations and recovery. */
public interface AssistantLoopCoordinator
{
    AssistantLoopAdvanceResult advance(Long runId, Long expectedStateRevision, RuntimeLease lease);

    AssistantLoopReconcileResult reconcile(Long runId, RuntimeLease lease);
}
