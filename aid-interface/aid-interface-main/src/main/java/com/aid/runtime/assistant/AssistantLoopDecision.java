package com.aid.runtime.assistant;

import lombok.Data;

/** Parsed and schema-validated one-action model decision. */
@Data
public class AssistantLoopDecision
{
    private String protocolVersion;
    private String decisionId;
    private Long observedRunRevision;
    private Long observedPlanRevision;
    private String action;
    private String decisionJson;
    /** Provider-visible prose emitted alongside a native tool call. */
    private String visibleText;
}
