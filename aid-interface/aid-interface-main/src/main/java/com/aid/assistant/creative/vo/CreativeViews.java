package com.aid.assistant.creative.vo;

import com.aid.assistant.artifact.vo.ArtifactRef;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public final class CreativeViews {
    private CreativeViews() {
    }

    @Data
    public static class SourceRevision {
        private String sourceRevisionId;
        private String sourceKind;
        private String sourceObjectId;
        private String sourceRevision;
        private String contentHash;
        private Integer charCount;
        private Integer wordCount;
        private Integer chunkCount;
        private String status;
    }

    @Data
    public static class SourceChunkPage {
        private String sourceRevisionId;
        private String sourceKind;
        private String sourceObjectId;
        private String sourceRevision;
        private String contentHash;
        private Integer chunkCount;
        private Integer nextAfterChunkIndex;
        private boolean complete;
        private List<SourceChunk> chunks = new ArrayList<>();
    }

    @Data
    public static class SourceChunk {
        private Integer chunkIndex;
        private Integer charStart;
        private Integer charEnd;
        private Integer charCount;
        private Integer wordCount;
        private Object locator;
        private String contentHash;
        private String content;
    }

    @Data
    public static class CoverageReceipt {
        private String coverageId;
        private String sourceRevisionId;
        private Integer chunkIndex;
        private String purpose;
        private String coverageStatus;
        private String evidenceArtifactId;
        private Long revision;
        private String notes;
    }

    @Data
    public static class ArtifactChunkPage {
        private String artifactId;
        private String artifactRef;
        private String artifactType;
        private Long revision;
        private String contentHash;
        private Integer chunkCount;
        private Integer nextAfterChunkIndex;
        private boolean complete;
        private List<ArtifactChunk> chunks = new ArrayList<>();
    }

    @Data
    public static class ArtifactChunk {
        private Integer chunkIndex;
        private Integer charStart;
        private Integer charEnd;
        private Integer charCount;
        private Integer wordCount;
        private String contentHash;
        private String content;
    }

    @Data
    public static class ReviewMaterialization {
        private ArtifactRef artifact;
        private String reviewId;
    }

    @Data
    public static class MediaMaterialization {
        private String status;
        private String mediaTaskId;
        private List<ArtifactRef> artifacts = new ArrayList<>();
    }

    @Data
    public static class Strategy {
        private String mode;
        private String targetFormat;
        private Integer targetWordCount;
        private Integer episodeCount;
        private Integer wordsPerEpisode;
        private List<EpisodeRange> episodeRanges = new ArrayList<>();
        private List<String> checkpoints = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EpisodeRange {
        private Integer episodeNo;
        private Integer targetWords;
        private Integer cumulativeWordStart;
        private Integer cumulativeWordEnd;
    }
}
