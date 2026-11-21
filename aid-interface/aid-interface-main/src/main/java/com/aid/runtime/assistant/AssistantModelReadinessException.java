package com.aid.runtime.assistant;

import lombok.Getter;

/**
 * Carries only a rejection produced by the shared Assistant Agent model-readiness gate.
 * Callers may expose {@link #getReason()}; the underlying exception message is deliberately generic.
 */
@Getter
public final class AssistantModelReadinessException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final AssistantAgentModelReadinessService.Failure failure;
    private final String reason;

    AssistantModelReadinessException(AssistantAgentModelReadinessService.Verification verification)
    {
        super("Assistant Agent model readiness rejected");
        if (verification == null || verification.ready()
                || verification.failure() == null
                || AssistantAgentModelReadinessService.Failure.NONE.equals(verification.failure())
                || verification.reason() == null || verification.reason().isBlank())
        {
            throw new IllegalArgumentException("A rejected model-readiness verification is required");
        }
        this.failure = verification.failure();
        this.reason = verification.reason();
    }
}
