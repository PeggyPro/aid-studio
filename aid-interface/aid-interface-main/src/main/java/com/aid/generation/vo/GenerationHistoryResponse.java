package com.aid.generation.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 通用生成会话的游标历史页。 */
@Data
@Builder
public class GenerationHistoryResponse {
    private Object sessionId;
    private Object workspaceId;
    private String nodeObjectId;
    private Object projectId;
    private Object episodeId;
    private String title;
    private List<Message> items;
    private Object nextCursor;
    private boolean hasMore;

    @Data
    @Builder
    public static class Message {
        private Object messageId;
        private Object seqNo;
        private String role;
        private String status;
        private String mediaType;
        private String modelCode;
        private String content;
        private String toolCallId;
        private String errorMessage;
        private Object mediaTaskId;
        private List<Part> parts;
    }

    @Data
    @Builder
    public static class Part {
        private Object partId;
        private Integer partSeq;
        private String type;
        private String text;
        private Object resourceId;
        private String resourceRole;
        private String resourceUrl;
        private String mimeType;
        private Map<String, Object> metadata;
    }
}
