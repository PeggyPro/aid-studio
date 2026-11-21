package com.aid.assistant.creative.dto;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.media.dto.MediaTextGenerateRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class CreativeRequests {
    private CreativeRequests() {
    }

    @Data
    public static class SourceIngest {
        private String sourceKind;
        private String sourceObjectId;
        private String sourceRevision;
        private String contentHash;
        private Integer maxChunkChars;
    }

    @Data
    public static class SourceChunkRead {
        private Long sourceRevisionId;
        private Integer afterChunkIndex = -1;
        private Integer limit = 20;
    }

    @Data
    public static class Strategy {
        private String mode;
        private String targetFormat;
        private Integer targetWordCount;
        private Integer episodeCount;
        private Integer sourceCharCount;
        private List<String> constraints = new ArrayList<>();
    }

    @Data
    public static class TextSubmit {
        private String modelCode;
        /** Server-resolved model-catalog capability; it is not part of Agent Tool input. */
        private String resolvedCapabilityCode;
        private String prompt;
        private String logicalArtifactId;
        private String artifactType;
        private Long targetArtifactId;
        private List<CreativeMaterializationRequests.SourceUse> sources = new ArrayList<>();
        private Integer maxOutputTokens;
        private Double temperature;
    }

    @Data
    public static class PreparedTextSubmit {
        private TextSubmit creativeRequest;
        private MediaTextGenerateRequest mediaRequest;
        private List<AssistantRequests.ContextRef> targetRefs = new ArrayList<>();
        /** Exact immutable review subject authorized by the approved plan inputArtifactRefs. */
        private String subjectArtifactRef;
        private String subjectContentHash;
        /** SHA-256 of the final server-composed prompt submitted for quote and execution. */
        private String submissionPromptDigest;
        /** Model/config snapshot identity returned by trusted billing normalization. */
        private String modelInvocationIdentity;
        /** Digest of the exact normalized request frozen in the billing intent. */
        private String normalizedRequestDigest;
        /** Digest binding the server-only model identity to the Runtime logical call and quote. */
        private String serverIdentityDigest;
    }
}
