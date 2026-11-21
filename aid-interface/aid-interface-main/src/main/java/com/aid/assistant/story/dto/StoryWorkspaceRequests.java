package com.aid.assistant.story.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed inputs and server-frozen payloads for the screenplay workspace tools. */
public final class StoryWorkspaceRequests {
    private StoryWorkspaceRequests() {
    }

    @Data
    public static class Initialize {
        private String mode;
    }

    @Data
    public static class ArtifactRead {
        private String path;
    }

    @Data
    public static class SourceRead {
        private Long sourceRevisionId;
        private Integer afterChunkIndex = -1;
        private Integer limit = 20;
    }

    @Data
    public static class SourceUse {
        private Long sourceRevisionId;
        private String purpose;
    }

    @Data
    public static class EpisodePlan {
        private Integer episode;
        private String title;
        private String mustKeep;
        private String ending;
    }

    @Data
    public static class SkeletonPlan {
        private String title;
        private Integer totalEpisodes;
        private Integer episodeDurationSeconds;
        private List<EpisodePlan> episodes = new ArrayList<>();
    }

    @Data
    public static class ArtifactWrite {
        private String path;
        private Long expectedRevision;
        private String expectedContentHash;
        private String disposition = "FINAL";
        private String content;
        private String summary;
        private List<SourceUse> sources = new ArrayList<>();
        private SkeletonPlan skeletonPlan;
    }

    @Data
    public static class ReviewRecord {
        private String path;
        private Long expectedRevision;
        private String expectedContentHash;
        private String verdict;
        private Map<String, Integer> severityCounts;
        private String report;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ReviewCorrection extends ReviewRecord {
        private String expectedReviewArtifactRef;
        private String expectedReviewContentHash;
        private String correctionReason;
    }

    @Data
    public static class ReviewCorrectionLink {
        private String expectedReviewArtifactRef;
        private String expectedReviewContentHash;
        private String correctionReason;
    }

    @Data
    public static class WorkspaceCas {
        private Long expectedWorkspaceRevision;
        private String expectedWorkspaceContentHash;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PipelineInitialize extends WorkspaceCas {
        private Long sourceRevisionId;
    }

    @Data
    public static class EvidenceFact {
        private String key;
        private String value;
        private String quote;
    }

    @Data
    public static class PipelineFacts {
        private List<EvidenceFact> events = new ArrayList<>();
        private List<EvidenceFact> primaryCharacters = new ArrayList<>();
        private List<EvidenceFact> supportingCharacters = new ArrayList<>();
        private List<EvidenceFact> minorCharacters = new ArrayList<>();
        private List<EvidenceFact> locations = new ArrayList<>();
        private List<EvidenceFact> props = new ArrayList<>();
        private List<EvidenceFact> redlines = new ArrayList<>();
        private String chapterDraft;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PipelineAdvance extends WorkspaceCas {
        private Integer charStart;
        private Integer charEnd;
        private String contentHash;
        private PipelineFacts facts;
    }

    @Data
    public static class PipelineInspect {
        private Integer afterItemIndex = -1;
        private Integer limit = 50;
    }

    @Data
    public static class SourcePage {
        private Integer number;
        private Integer charOffset = 0;
        private Integer maxUtf8Bytes = 8192;
    }

    @Data
    public static class EpisodeBoundary {
        private Integer episode;
        private String title;
        private Integer charStart;
        private Integer charEnd;
        private String startAnchor;
        private String startAnchorSide;
        private String endAnchor;
        private String endAnchorSide;
        private String mustKeep;
        private String ending;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BoundariesWrite extends WorkspaceCas {
        private Integer targetEpisodes;
        private List<EpisodeBoundary> episodes = new ArrayList<>();
    }

    @Data
    public static class ArtifactLinesRead {
        private String path;
        private String source;
        private Integer startLine = 1;
        private Integer maxLines = 200;
        private Integer maxUtf8Bytes = 8192;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ArtifactPatch extends WorkspaceCas {
        private String path;
        private String source = "CURRENT";
        private Long expectedRevision;
        private String expectedContentHash;
        private Integer startLine;
        private Integer endLine;
        private String expectedBlockHash;
        private String replacement;
        private String summary;
        private List<SourceUse> sources = new ArrayList<>();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UploadBegin extends WorkspaceCas {
        private String uploadKey;
        private String path;
        private Long expectedRevision;
        private String expectedContentHash;
        private String disposition = "FINAL";
        private Integer expectedFragmentCount;
        private Integer expectedCharCount;
        private String finalContentHash;
        private String summary;
        private List<SourceUse> sources = new ArrayList<>();
        private SkeletonPlan skeletonPlan;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UploadAppend extends WorkspaceCas {
        private String uploadKey;
        private Integer fragmentIndex;
        private Integer charStart;
        private String content;
        private String contentHash;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UploadFinalize extends WorkspaceCas {
        private String uploadKey;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DraftResolve extends WorkspaceCas {
        private String path;
        private String draftArtifactRef;
        private String draftContentHash;
        private String action;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class CheckpointRecord extends WorkspaceCas {
        private String checkpoint;
        private String summary;
        private List<String> paths = new ArrayList<>();
        private List<String> draftPaths = new ArrayList<>();
    }

    @Data
    public static class FrozenScope {
        private Long runId;
        private Long userId;
        private Long runtimeStepId;
        private Long sessionId;
        private Long projectId;
        private Long agentVersionId;
        private Long planRevision;
        private String contextDigest;
        private String mode;
        private Long workspaceArtifactId;
        private Long workspaceRevision;
        private String workspaceContentHash;
    }

    @Data
    public static class PreparedInitialize {
        private FrozenScope scope;
        private Initialize request;
    }

    @Data
    public static class PreparedWrite {
        private FrozenScope scope;
        private ArtifactWrite request;
        private String logicalArtifactId;
        private String artifactType;
        private String sourceRefsJson;
        private Long currentArtifactId;
        private String currentArtifactRef;
    }

    @Data
    public static class PreparedReview {
        private FrozenScope scope;
        private ReviewRecord request;
        private Long targetArtifactId;
        private String targetArtifactRef;
        private String reviewLogicalArtifactId;
        private ReviewCorrectionLink correction;
    }

    @Data
    public static class PreparedOperation {
        private FrozenScope scope;
        private String operation;
        private String canonicalRequestJson;
        private String sourceRefsJson;
        private String frozenSourceJson;
    }
}
