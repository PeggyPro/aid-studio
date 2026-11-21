package com.aid.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Read-only requests for durable command receipts; neither operation replays a command. */
public final class AssistantCommandRecoveryRequests
{
    private AssistantCommandRecoveryRequests() { }

    @Data
    public static class Page
    {
        @NotNull @Min(1)
        private Long sessionId;
        @Pattern(regexp = "[0-9]{1,19}")
        private String afterCommandId;
        @Pattern(regexp = "[0-9]{1,19}")
        private String throughCommandId;
        @Min(0)
        private Long expectedSessionRevision;
        @Min(0)
        private Long expectedLastEventSeq;
        @Min(1) @Max(50)
        private Integer limit = 20;
    }

    @Data
    public static class Lookup
    {
        @NotNull @Min(1)
        private Long sessionId;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}")
        private String clientRequestId;
    }
}
