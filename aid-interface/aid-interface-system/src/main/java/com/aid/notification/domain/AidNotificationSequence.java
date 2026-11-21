package com.aid.notification.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 通知发布顺序与全局可见性修订。 */
@Data
@TableName("aid_notification_sequence")
public class AidNotificationSequence
{
    @TableId
    private Integer id;
    private Long publishSeq;
    private Long visibilityRevision;
}
