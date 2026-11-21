package com.aid.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 站内系统通知正文与发布状态。 */
@Data
@TableName("aid_system_notification")
public class AidSystemNotification
{
    @TableId(type = IdType.AUTO)
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
}
