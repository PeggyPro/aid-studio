package com.aid.assistant.artifact.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class ArtifactRequests {
    private ArtifactRequests() {
    }

    @Data
    public static class Page {
        @Positive private Long runId;
        @Size(max = 64) private String logicalArtifactId;
        @Size(max = 40) private String artifactType;
        @Size(max = 20) private String reviewStatus;
        @Positive private Long cursor;
        @Min(1) @Max(100) private Integer limit = 20;
    }

    @Data
    public static class Detail {
        @NotNull @Positive private Long artifactId;
    }

    @Data
    public static class Media {
        @NotNull @Positive private Long artifactId;
    }

    @Data
    public static class Content {
        @NotNull @Positive private Long artifactId;
        @Min(-1) private Integer afterChunkIndex = -1;
        @Min(1) @Max(100) private Integer limit = 20;
    }

    @Data
    public static class ReviewPage {
        @NotNull @Positive private Long artifactId;
        @Positive private Long cursor;
        @Min(1) @Max(100) private Integer limit = 20;
    }

    @Data
    public static class CoveragePage {
        @NotNull @Positive private Long runId;
        @NotNull @Positive private Long sourceRevisionId;
        @Positive private Long cursor;
        @Min(1) @Max(100) private Integer limit = 50;
    }

    @Data
    public static class SourcePage {
        @NotNull @Positive private Long runId;
        @Positive private Long cursor;
        @Min(1) @Max(100) private Integer limit = 20;
    }

    @Data
    public static class SourceManifest {
        @NotNull @Positive private Long runId;
    }

    @Data
    public static class SourceContent {
        @NotNull @Positive private Long runId;
        @NotNull @Positive private Long sourceRevisionId;
        @Min(-1) private Integer afterChunkIndex = -1;
        @Min(1) @Max(100) private Integer limit = 20;
    }

    @Data
    public static class AdoptionCapabilities {
        @NotNull @Positive private Long sessionId;
        @NotNull @Positive private Long artifactId;
        @Size(max = 40) private String targetKind;
        @Size(max = 128) private String targetObjectId;
        @Positive private Long workspaceId;
    }

    @Data
    public static class Adopt {
        @NotNull @Positive private Long sessionId;
        @NotNull @Positive private Long artifactId;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String contentHash;
        @NotBlank @Size(max = 40) private String targetKind;
        @NotBlank @Size(max = 128) private String targetObjectId;
        @NotBlank @Size(max = 128) private String expectedTargetRevision;
        @Positive private Long workspaceId;
        @Size(max = 128) private String editSessionId;
        @Size(max = 128) private String runtimeLeaseId;
        @NotBlank @Size(max = 64) private String clientRequestId;
    }
}
