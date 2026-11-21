package com.aid.assistant.artifact.dto;

import lombok.Data;

/** Trusted internal commands. Runtime callers must also supply current run and step leases. */
public final class ArtifactWriteRequests {
    private ArtifactWriteRequests() {
    }

    @Data
    public static class CreateCandidate {
        private Long runId;
        private Long stepId;
        private String logicalArtifactId;
        private Long revision;
        private Long parentRevision;
        private String artifactType;
        private String schemaVersion;
        private Long agentVersionId;
        private Long planRevision;
        private Long resourceId;
        private Long fileId;
        private String resourceRefJson;
        private String sourceContextDigest;
        private String sourceContextRefsJson;
        private String contentHash;
        private Integer expectedChunkCount;
        private String summary;
        private String metadataJson;
    }

    @Data
    public static class AppendChunk {
        private Long runId;
        private Long stepId;
        private Long artifactId;
        private Integer chunkIndex;
        private Integer charStart;
        private String content;
        private String contentHash;
    }

    @Data
    public static class FinalizeCandidate {
        private Long runId;
        private Long stepId;
        private Long artifactId;
        private Integer expectedChunkCount;
        private String contentHash;
    }

    @Data
    public static class FinalizeMediaCandidate {
        private Long runId;
        private Long stepId;
        private Long artifactId;
        private String contentHash;
    }
}
