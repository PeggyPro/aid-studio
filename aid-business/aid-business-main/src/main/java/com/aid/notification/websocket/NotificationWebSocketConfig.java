package com.aid.notification.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;


/** 注册账户级站内通知WebSocket端点。 */
@Configuration
@RequiredArgsConstructor
public class NotificationWebSocketConfig implements WebSocketConfigurer
{
    private final NotificationWebSocketHandler handler;
    private final NotificationHandshakeInterceptor handshakeInterceptor;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.common.aid.core.service.RealtimeOriginSettings realtimeOrigins;

    @Value("${notification.websocket.allowed-origins:${canvas.websocket.allowed-origins:}}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        var registration = registry.addHandler(handler, "/ws/notification")
                .addInterceptors(handshakeInterceptor);

        registration.setAllowedOrigins("*").addInterceptors(new com.aid.common.realtime.DynamicOriginHandshakeInterceptor(realtimeOrigins, allowedOrigins));

    }
}
