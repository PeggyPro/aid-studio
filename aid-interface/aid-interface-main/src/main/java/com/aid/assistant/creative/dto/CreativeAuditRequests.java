package com.aid.assistant.creative.dto;

import lombok.Data;

public final class CreativeAuditRequests {
    private CreativeAuditRequests() {
    }

    @Data
    public static class CoverageMark {
        private Long runId;
        private Long stepId;
        private Long sourceRevisionId;
        private Integer chunkIndex;
        private String purpose;
        private String coverageStatus;
        private Long evidenceArtifactId;
        private Long expectedRevision;
        private String notes;
    }

    @Data
    public static class PreparedCoverageMark {
        private CoverageMark request;
        private String sourceKind;
        private String sourceObjectId;
        private String sourceContentHash;
        private String evidenceArtifactRef;
        private String evidenceContentHash;
        private Long expectedResultRevision;
        private boolean alreadyApplied;
    }

    @Data
    public static class ArtifactChunkRead {
        private Long artifactId;
        private Integer afterChunkIndex;
        private Integer limit;
    }

    @Data
    public static class ReviewRecord {
        private Long runId;
        private Long stepId;
        private Long artifactId;
        private Long reviewArtifactId;
        private String verdict;
        private String severityCountsJson;
    }
}
