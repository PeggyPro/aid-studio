package com.aid.generation.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/** 本人通用生成会话分页。 */
@Data
@Builder
public class GenerationSessionPage {
    private List<Item> items;
    private long total;
    private int pageNum;
    private int pageSize;

    @Data
    @Builder
    public static class Item {
        private Object sessionId;
        private Object workspaceId;
        private String nodeObjectId;
        private Object projectId;
        private Object episodeId;
        private String title;
        private Object lastMessageSeq;
        private Date lastActiveTime;
    }
}
