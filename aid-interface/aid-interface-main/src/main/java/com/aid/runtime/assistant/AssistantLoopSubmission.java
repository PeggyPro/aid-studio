package com.aid.runtime.assistant;

import lombok.Data;

/** Validated model submission prepared by an assistant executor. */
@Data
public class AssistantLoopSubmission
{
    private String protocolVersion;
    private String operationKind;
    private String systemPrompt;
    private String modelConfigJson;
    private String inputSchemaJson;
    private String requestJson;
    private String outputSchemaJson;
    /** Canonical provider-independent native function definitions. */
    private String nativeToolsJson;
    /** Server-only alias bindings captured in the approval snapshot. */
    private String nativeToolBindingsJson;
}
