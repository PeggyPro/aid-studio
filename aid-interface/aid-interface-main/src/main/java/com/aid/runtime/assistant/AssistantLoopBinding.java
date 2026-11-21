package com.aid.runtime.assistant;

import lombok.Data;

/** Frozen main-agent version binding. */
@Data
public class AssistantLoopBinding
{
    private String moduleCode;
    private String agentCode;
    private Long bindingRevision;
    private Long agentId;
    private Long agentVersionId;
    private String versionCode;
    private String packageDigest;
    private String protocolVersion;
    private String instruction;
    private String modelConfigJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String definitionJson;
}
