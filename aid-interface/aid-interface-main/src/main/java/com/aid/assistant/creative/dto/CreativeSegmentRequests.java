package com.aid.assistant.creative.dto;

import lombok.Data;

public final class CreativeSegmentRequests {
    private CreativeSegmentRequests() {
    }

    @Data
    public static class SourceRead {
        private Long sourceRevisionId;
        private Integer chunkIndex;
        private Integer charOffset;
        private Integer maxUtf8Bytes;
    }

    @Data
    public static class ArtifactRead {
        private Long artifactId;
        private Integer chunkIndex;
        private Integer charOffset;
        private Integer maxUtf8Bytes;
    }
}
