package com.aid.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Typed assistant realtime requests. */
public final class AssistantRealtimeRequests
{
    private AssistantRealtimeRequests() { }

    @Data
    public static class Ticket
    {
        @NotNull
        @Min(1)
        private Long sessionId;

        @NotNull
        @Min(0)
        private Long afterSeq;

        /** Omitted/0 preserves committed-event replay; 1 requests the bounded read model. */
        @Min(0)
        @Max(1)
        private Integer projectionVersion;
    }
}
