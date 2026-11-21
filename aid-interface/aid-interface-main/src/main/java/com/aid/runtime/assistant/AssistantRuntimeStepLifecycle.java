package com.aid.runtime.assistant;

import com.aid.runtime.domain.AidRuntimeRunStep;
import java.util.Set;

/** Applies terminal metadata cleanup to a loaded, locked runtime step snapshot. */
public final class AssistantRuntimeStepLifecycle
{
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "FAILED_PRETASK", "CANCELLED_PRETASK");

    private AssistantRuntimeStepLifecycle() { }

    public static void clearTerminalLease(AidRuntimeRunStep step)
    {
        if (step == null || step.getStatus() == null || !TERMINAL.contains(step.getStatus())
                || "UNKNOWN".equals(step.getProviderAcceptanceState())) return;
        step.setLeaseOwner(null);
        step.setLeaseUntil(null);
    }
}
