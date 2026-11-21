package com.aid.assistant.artifact.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable external reference: artifact:&lt;logicalArtifactId&gt;:&lt;revision&gt;. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactRef {
    private Long artifactId;
    private String logicalArtifactId;
    private Long revision;
    private String ref;
    private String contentHash;
    private String status;
}
