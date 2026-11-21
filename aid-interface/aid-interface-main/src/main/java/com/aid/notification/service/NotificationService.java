package com.aid.notification.service;

import com.aid.notification.dto.NotificationAdminRequests;
import com.aid.notification.dto.NotificationUserRequests;
import com.aid.notification.vo.NotificationViews;

/** 站内系统通知管理与阅读服务。 */
public interface NotificationService
{
    NotificationViews.Page<NotificationViews.Notification> adminList(NotificationAdminRequests.ListRequest request);

    NotificationViews.Notification adminDetail(String id);

    NotificationViews.Notification save(Long operatorId, String operatorName, NotificationAdminRequests.Save request);

    NotificationViews.Notification publish(Long operatorId, String operatorName, NotificationAdminRequests.Action request);

    NotificationViews.Notification withdraw(Long operatorId, String operatorName, NotificationAdminRequests.Action request);

    NotificationViews.Notification cancelSchedule(Long operatorId, String operatorName, NotificationAdminRequests.Action request);

    NotificationViews.Page<NotificationViews.Recipient> recipients(NotificationAdminRequests.Recipients request);

    NotificationViews.Page<NotificationViews.UserNotification> userList(Long userId, NotificationUserRequests.ListRequest request);

    NotificationViews.UserNotification userDetail(Long userId, String id);

    NotificationViews.State unread(Long userId);

    NotificationViews.State read(Long userId, String id);

    NotificationViews.State readAll(Long userId);

    boolean isActiveCUser(Long userId);

    void publishDueScheduled();
}
