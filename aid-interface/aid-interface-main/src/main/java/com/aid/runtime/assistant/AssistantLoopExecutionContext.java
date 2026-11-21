package com.aid.runtime.assistant;

import lombok.Data;

/** Persisted inputs for one main-agent iteration. */
@Data
public class AssistantLoopExecutionContext
{
    private Long runId;
    private Long stateRevision;
    private Long planRevision;
    private Integer iteration;
    private String protocolVersion;
    private String requestJson;
    private AssistantLoopBinding binding;
}
