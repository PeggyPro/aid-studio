package com.aid.assistant.creative.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Trusted internal commands used after an authorized creative media task succeeds. */
public final class CreativeMaterializationRequests {
    private CreativeMaterializationRequests() {
    }

    @Data
    public static class MaterializeText {
        private Long runId;
        private Long stepId;
        private Long mediaTaskId;
        private String logicalArtifactId;
        private Long parentRevision;
        private String artifactType;
        private String schemaVersion = "aid-creative-text/v1";
        private String contextManifestJson;
        private List<SourceUse> sources = new ArrayList<>();
        private Integer maxChunkChars = 12000;
        private String summary;
        private String metadataJson;
    }

    @Data
    public static class SourceUse {
        private Long sourceRevisionId;
        private String purpose;
    }

    @Data
    public static class MaterializeReview {
        private MaterializeText text;
        private Long targetArtifactId;
        /** Values copied from the digest-bound prepared payload, not from Agent arguments. */
        private String subjectArtifactRef;
        private String subjectContentHash;
        private String submissionPromptDigest;
    }

    @Data
    public static class MaterializeMedia {
        private Long runId;
        private Long stepId;
        private Long mediaTaskId;
        private String logicalArtifactId;
        private Long parentRevision;
        private String contextManifestJson;
        private List<SourceUse> sources = new ArrayList<>();
        private String summary;
        private String metadataJson;
    }
}
