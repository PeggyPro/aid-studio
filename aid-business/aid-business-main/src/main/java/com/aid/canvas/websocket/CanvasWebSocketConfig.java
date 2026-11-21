package com.aid.canvas.websocket;

import com.aid.assistant.websocket.AssistantRealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;


@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class CanvasWebSocketConfig implements WebSocketConfigurer {
    private final CanvasWebSocketHandler handler;
    private final AssistantRealtimeWebSocketHandler assistantRealtimeHandler;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.common.aid.core.service.RealtimeOriginSettings realtimeOrigins;

    @Value("${canvas.websocket.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, "/ws/canvas");

        registration.setAllowedOrigins("*").addInterceptors(new com.aid.common.realtime.DynamicOriginHandshakeInterceptor(realtimeOrigins, allowedOrigins));
        var assistantRegistration = registry.addHandler(assistantRealtimeHandler, "/ws/canvas/assistant");
        assistantRegistration.setAllowedOrigins("*").addInterceptors(new com.aid.common.realtime.DynamicOriginHandshakeInterceptor(realtimeOrigins, allowedOrigins));
    }
}
