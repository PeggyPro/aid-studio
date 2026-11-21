package com.aid.notification.domain;

import lombok.Data;

import java.util.Date;

/** 通知列表与详情的精简查询结果。 */
@Data
public class NotificationQueryRow
{
    private Long id;
    private String title;
    private String summary;
    private String content;
    private String contentFormat;
    private String messageType;
    private String recipientType;
    private Long recipientCount;
    private String status;
    private Date scheduledTime;
    private Date publishTime;
    private Long publishSeq;
    private Long version;
    private Long creatorId;
    private String creatorName;
    private Long updaterId;
    private String updaterName;
    private Date createTime;
    private Date updateTime;
    private Boolean read;
    private Date readTime;
}
