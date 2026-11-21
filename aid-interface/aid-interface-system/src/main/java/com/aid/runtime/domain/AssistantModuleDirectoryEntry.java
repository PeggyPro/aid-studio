package com.aid.runtime.domain;

import lombok.Data;

/** Module capability metadata, without Agent instruction or complete model configuration. */
@Data
public class AssistantModuleDirectoryEntry {
    private String moduleCode;
    private String protocolVersion;
    private String defaultModelCode;
}
