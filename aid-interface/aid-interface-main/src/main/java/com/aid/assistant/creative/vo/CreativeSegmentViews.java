package com.aid.assistant.creative.vo;

import lombok.Data;

public final class CreativeSegmentViews {
    private CreativeSegmentViews() {
    }

    @Data
    public static class Cursor {
        private Integer chunkIndex;
        private Integer charOffset;
    }

    @Data
    public static class Segment {
        private Integer parentChunkIndex;
        private Integer parentCharStart;
        private Integer parentCharEnd;
        private Integer parentCharCount;
        private String parentContentHash;
        private Integer charStart;
        private Integer charEnd;
        private Integer charCount;
        private Integer utf8ByteCount;
        private String contentHash;
        private String content;
    }

    @Data
    public static class SourcePage {
        private String schemaVersion;
        private String sourceRevisionId;
        private String sourceKind;
        private String sourceObjectId;
        private String sourceRevision;
        private String contentHash;
        private Integer chunkCount;
        private Cursor cursor;
        private Cursor nextCursor;
        private boolean complete;
        private Segment segment;
    }

    @Data
    public static class ArtifactPage {
        private String schemaVersion;
        private String artifactId;
        private String artifactRef;
        private String artifactType;
        private Long revision;
        private String contentHash;
        private Integer chunkCount;
        private Cursor cursor;
        private Cursor nextCursor;
        private boolean complete;
        private Segment segment;
    }
}
