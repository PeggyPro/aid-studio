package com.aid.notification.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 站内通知响应模型。 */
public final class NotificationViews
{
    private NotificationViews() { }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Notification
    {
        private String id;
        private String version;
        private String status;
        private String title;
        private String summary;
        private String content;
        private String contentFormat;
        private String messageType;
        private String recipientType;
        private Long recipientCount;
        private List<String> recipientUserIds;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date scheduledTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date publishTime;
        private String creatorId;
        private String creatorName;
        private String updaterId;
        private String updaterName;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date createTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date updateTime;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserNotification
    {
        private String id;
        private String title;
        private String summary;
        private String content;
        private String contentFormat;
        private String messageType;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date publishTime;
        private Boolean isRead;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date readTime;
    }

    @Data
    public static class Recipient
    {
        private String userId;
        private String userName;
        private String nickName;
        private String avatar;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date createTime;
    }

    @Data
    public static class Page<T>
    {
        private List<T> items = new ArrayList<>();
        private long total;
        private int pageNum;
        private int pageSize;
        private String stateVersion;
    }

    @Data
    public static class State
    {
        private long unreadCount;
        private String stateVersion;
    }

    @Data
    public static class Ticket
    {
        private String ticket;
        private String websocketPath;
        private List<String> protocols;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date expireAt;
        private int heartbeatSeconds;
        private int idleTimeoutSeconds;
        private int reconnectBaseMillis;
        private int reconnectMaxMillis;
        private String stateVersion;
    }
}
