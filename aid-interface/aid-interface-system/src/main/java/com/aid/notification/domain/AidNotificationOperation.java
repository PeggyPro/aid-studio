package com.aid.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 管理端通知写操作幂等记录。 */
@Data
@TableName("aid_notification_operation")
public class AidNotificationOperation
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String actionType;
    private String clientRequestId;
    private String requestHash;
    private Long notificationId;
    private Long responseVersion;
    private Date createTime;
    private Date completeTime;
}
