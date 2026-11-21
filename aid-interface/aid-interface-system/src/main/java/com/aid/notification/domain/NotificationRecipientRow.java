package com.aid.notification.domain;

import lombok.Data;

import java.util.Date;

/** 后台可选C端通知收件人。 */
@Data
public class NotificationRecipientRow
{
    private Long userId;
    private String userName;
    private String nickName;
    private String avatar;
    private String status;
    private Date createTime;
}
