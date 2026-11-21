package com.aid.assistant.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/** Assistant realtime transport projections. */
public final class AssistantRealtimeViews
{
    private AssistantRealtimeViews() { }

    @Data
    public static class Ticket
    {
        private String sessionId;
        private String ticket;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private Date expireAt;
        private String websocketPath;
        private int heartbeatSeconds;
        private int idleTimeoutSeconds;
        private int reconnectBaseMillis;
        private int reconnectMaxMillis;
        private long lastEventSeq;
        private int projectionVersion;
    }
}
