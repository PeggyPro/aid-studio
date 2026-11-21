package com.aid.notification.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 用户通知全读水位与阅读修订。 */
@Data
@TableName("aid_notification_user_state")
public class AidNotificationUserState
{
    @TableId
    private Long userId;
    private Long readAllPublishSeq;
    private Date readAllTime;
    private Long readRevision;
    private Date updateTime;
}
