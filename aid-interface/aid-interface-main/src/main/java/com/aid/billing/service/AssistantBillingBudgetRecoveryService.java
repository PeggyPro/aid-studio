package com.aid.billing.service;

/** Replays authoritative terminal media billing into the wallet and run-budget projections. */
public interface AssistantBillingBudgetRecoveryService {
    int reconcileTerminalMediaTasks(int batchSize);
}
