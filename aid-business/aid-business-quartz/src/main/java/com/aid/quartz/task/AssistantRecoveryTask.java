package com.aid.quartz.task;

import com.aid.runtime.assistant.recovery.AssistantRuntimeRecoveryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Quartz entry point for database-backed assistant runtime recovery. */
@Slf4j
@Component("assistantRecoveryTask")
public class AssistantRecoveryTask
{
    private static final int DEFAULT_BATCH_SIZE = 20;

    @Resource
    private AssistantRuntimeRecoveryService recoveryService;

    public void reconcile()
    {
        reconcile(DEFAULT_BATCH_SIZE);
    }

    public void reconcile(Integer batchSize)
    {
        int limit = batchSize == null || batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
        int handled = recoveryService.reconcileDueRuns(limit);
        if (handled > 0)
        {
            log.info("Assistant runtime recovery completed, handled={}", handled);
        }
    }
}
