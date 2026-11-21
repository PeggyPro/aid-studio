package com.aid.notification.websocket;

import com.aid.notification.service.impl.NotificationRealtimeServiceImpl;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 从WebSocket子协议中提取一次性通知票据。 */
@Component
public class NotificationHandshakeInterceptor implements HandshakeInterceptor
{
    public static final String TICKET_ATTRIBUTE = "notificationTicket";
    private static final String WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes)
    {
        List<String> offered = request.getHeaders().getOrEmpty(WEBSOCKET_PROTOCOL_HEADER).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (!offered.contains(NotificationRealtimeServiceImpl.NEGOTIATED_PROTOCOL))
        {
            return false;
        }
        List<String> tickets = offered.stream()
                .filter(value -> value.startsWith(NotificationRealtimeServiceImpl.TICKET_PROTOCOL_PREFIX))
                .toList();
        if (tickets.size() != 1)
        {
            return false;
        }
        String ticket = tickets.get(0).substring(NotificationRealtimeServiceImpl.TICKET_PROTOCOL_PREFIX.length());
        if (!ticket.matches("^[A-Za-z0-9_-]{32,96}$"))
        {
            return false;
        }
        attributes.put(TICKET_ATTRIBUTE, ticket);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception)
    {
    }
}
