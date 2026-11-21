package com.aid.runtime.domain;

import lombok.Data;

/** Directory metadata only. Never attach version definitions, prompts or resource bodies. */
@Data
public class AssistantSkillDirectoryEntry {
    private String code;
    private String name;
    private String description;
    private String iconUrl;
    private String versionId;
    private String packageDigest;
    private String ownerType;
    private String visibility;
    private String outputKind;
}
