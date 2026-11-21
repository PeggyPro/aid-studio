package com.aid.runtime.assistant;

import lombok.Data;

/** Result of advancing one durable runtime boundary. */
@Data
public class AssistantLoopAdvanceResult
{
    private Long runId;
    private Long stateRevision;
    private String status;
    private String action;
    private Long mediaTaskId;
    private String nextAction;
}
