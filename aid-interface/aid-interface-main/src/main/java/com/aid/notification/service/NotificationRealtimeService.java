package com.aid.notification.service;

import com.aid.notification.vo.NotificationViews;

import java.util.function.Consumer;

/** 账户级通知实时连接与跨实例信号服务。 */
public interface NotificationRealtimeService
{
    String signalChannel();

    NotificationViews.Ticket createTicket(Long userId, String loginToken, String knownStateVersion);

    ConnectionView consumeTicket(String ticket, Consumer<String> outbound);

    boolean heartbeat(String connectionId);

    void closeConnection(String connectionId);

    void publishNotificationChanged(Long notificationId, String eventType);

    void publishUserStateChanged(Long userId);

    void acceptClusterSignal(String payload);

    void synchronizeOnlineUsers();

    void cleanupIdleConnections();

    record ConnectionView(String connectionId, Long userId) { }
}
