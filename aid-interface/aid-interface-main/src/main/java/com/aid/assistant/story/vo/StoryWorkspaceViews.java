package com.aid.assistant.story.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stable receipts exposed by the screenplay workspace Runtime Tools. */
public final class StoryWorkspaceViews {
    private StoryWorkspaceViews() {
    }

    @Data
    public static class ArtifactReceipt {
        private String projectId;
        private String path;
        private String artifactId;
        private String artifactRef;
        private Long revision;
        private String contentHash;
        private String status;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
        private String disposition;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ArtifactContent extends ArtifactReceipt {
        private String artifactType;
        private String content;
    }

    @Data
    public static class Workspace {
        private String projectId;
        private String mode;
        private String workspaceArtifactId;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
        private String workspaceContentHash;
        private String title;
        private Integer totalEpisodes;
        private Integer episodeDurationSeconds;
        private boolean skeletonReady;
        private Integer nextEpisode;
        private int scriptCount;
        private int reviewedScriptCount;
        private int approvedScriptCount;
        private int reviewWorkflowCompletedScriptCount;
        private boolean allEpisodeScriptsPresent;
        private boolean allEpisodeScriptsApproved;
        private boolean allEpisodeReviewWorkflowsComplete;
        private String pipelineStatus;
        private Integer chaptersTotal;
        private boolean ingestDone;
        private Integer boundaryEpisodeCount;
        private int draftCount;
        private int stagedUploadCount;
        private List<FileEntry> files = new ArrayList<>();
        private List<FileEntry> drafts = new ArrayList<>();
        private List<UploadState> uploads = new ArrayList<>();
        private PipelineReceipt sourcePipeline;
    }

    @Data
    public static class FileEntry {
        private String path;
        private String artifactId;
        private String artifactRef;
        private String artifactType;
        private Long revision;
        private String contentHash;
        private String reviewStatus;
        private String reviewWorkflowStatus;
        private LastReview lastReview;
        private String followupArtifactRef;
        private Long followupRevision;
        private String followupContentHash;
    }

    @Data
    public static class LastReview {
        private String reviewedArtifactId;
        private String reviewedArtifactRef;
        private Long reviewedRevision;
        private String reviewedContentHash;
        private String reviewId;
        private String reviewerRunId;
        private String reviewerStepId;
        private String reviewRequestHash;
        private String verdict;
        private Map<String, Integer> severityCounts;
        private String reviewArtifactId;
        private String reviewArtifactRef;
        private Long reviewArtifactRevision;
        private String reviewArtifactContentHash;
    }

    @Data
    public static class ReviewReceipt {
        private String projectId;
        private String path;
        private String targetArtifactRef;
        private String targetContentHash;
        private String reviewId;
        private String reviewArtifactId;
        private String reviewArtifactRef;
        private Long reviewRevision;
        private String reviewContentHash;
        private String verdict;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ReviewContent extends ReviewReceipt {
        private String report;
    }

    @Data
    public static class SourceSegment {
        private Integer charStart;
        private Integer charEnd;
        private Integer charCount;
        private Integer utf8ByteCount;
        private String contentHash;
        private String content;
        private Integer firstLine;
        private Integer lastLine;
        private Integer nextCharOffset;
        private boolean complete;
    }

    @Data
    public static class Chapter {
        private Integer chapter;
        private String title;
        private Integer charStart;
        private Integer charEnd;
        private String contentHash;
    }

    @Data
    public static class PipelineReceipt {
        private String projectId;
        private String sourceRevisionId;
        private String sourceContentHash;
        private String status;
        private String warning;
        private Integer chaptersTotal;
        private boolean done;
        private Integer processedSegments;
        private Integer factCount;
        private List<Chapter> chapters = new ArrayList<>();
        private SourceSegment nextSegment;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
    }

    @Data
    public static class PipelineItem {
        private String kind;
        private String category;
        private String key;
        private String value;
        private Integer chapter;
        private Integer segmentCharStart;
        private Integer segmentCharEnd;
        private String segmentContentHash;
        private Integer quoteCharStart;
        private Integer quoteCharEnd;
        private String quote;
        private String draft;
    }

    @Data
    public static class PipelineInspection {
        private String projectId;
        private String sourceRevisionId;
        private String sourceContentHash;
        private String status;
        private boolean done;
        private Integer processedSegments;
        private Integer totalItems;
        private Integer afterItemIndex;
        private Integer nextAfterItemIndex;
        private boolean complete;
        private Integer reviewWorkflowCompletedScriptCount;
        private boolean allEpisodeReviewWorkflowsComplete;
        private List<PipelineItem> items = new ArrayList<>();
        private String workspaceArtifactRef;
        private Long workspaceRevision;
        private String workspaceContentHash;
    }

    @Data
    public static class SourceRangePage {
        private String projectId;
        private String sourceRevisionId;
        private String sourceContentHash;
        private String rangeKind;
        private Integer number;
        private String title;
        private Integer rangeCharStart;
        private Integer rangeCharEnd;
        private String mustKeep;
        private String ending;
        private SourceSegment segment;
    }

    @Data
    public static class BoundariesReceipt {
        private String projectId;
        private String sourceRevisionId;
        private Integer targetEpisodes;
        private Integer actualEpisodes;
        private boolean completeCoverage;
        private String boundariesDigest;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
    }

    @Data
    public static class LineItem {
        private Integer line;
        private String content;
        private String contentHash;
    }

    @Data
    public static class LinePage {
        private String projectId;
        private String path;
        private String source;
        private String artifactRef;
        private Long revision;
        private String contentHash;
        private Integer startLine;
        private Integer endLine;
        private Integer totalLines;
        private Integer nextLine;
        private boolean complete;
        private String blockHash;
        private List<LineItem> lines = new ArrayList<>();
    }

    @Data
    public static class UploadReceipt {
        private String projectId;
        private String uploadKey;
        private String path;
        private String status;
        private Integer expectedFragmentCount;
        private Integer receivedFragmentCount;
        private Integer nextFragmentIndex;
        private Integer expectedCharCount;
        private Integer receivedCharCount;
        private Integer nextCharStart;
        private String finalContentHash;
        private String fragmentArtifactRef;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
        private ArtifactReceipt artifact;
    }

    @Data
    public static class UploadState {
        private String uploadKey;
        private String path;
        private String disposition;
        private Integer expectedFragmentCount;
        private Integer receivedFragmentCount;
        private Integer nextFragmentIndex;
        private Integer expectedCharCount;
        private Integer receivedCharCount;
        private Integer nextCharStart;
        private String finalContentHash;
    }

    @Data
    public static class DraftResolutionReceipt {
        private String projectId;
        private String path;
        private String action;
        private String draftArtifactRef;
        private String draftContentHash;
        private String status;
        private ArtifactReceipt adoptedArtifact;
        private String workspaceArtifactRef;
        private Long workspaceRevision;
    }

    @Data
    public static class CheckpointReceipt {
        private String projectId;
        private String checkpoint;
        private String checkpointArtifactId;
        private String checkpointArtifactRef;
        private Long checkpointRevision;
        private String checkpointContentHash;
        private List<String> artifactRefs = new ArrayList<>();
        private List<String> draftArtifactRefs = new ArrayList<>();
        private String workspaceArtifactRef;
        private Long workspaceRevision;
    }
}
