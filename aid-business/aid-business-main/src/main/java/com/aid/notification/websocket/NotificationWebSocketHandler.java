package com.aid.notification.websocket;

import com.aid.common.exception.ServiceException;
import com.aid.notification.service.NotificationRealtimeService;
import com.aid.notification.service.impl.NotificationRealtimeServiceImpl;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 账户级站内通知WebSocket传输。 */
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable
{
    private static final String CONNECTION_ID = "notificationConnectionId";
    private static final String OUTBOUND_SESSION = "notificationOutboundSession";
    private static final int MAX_CLIENT_MESSAGE_BYTES = 2048;
    private static final int OUTBOUND_STRIPES = 4;
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private final NotificationRealtimeService realtimeService;
    private final ThreadPoolExecutor[] outboundExecutors = createOutboundExecutors();

    @Override
    public List<String> getSubProtocols()
    {
        return List.of(NotificationRealtimeServiceImpl.NEGOTIATED_PROTOCOL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception
    {
        Object ticketValue = session.getAttributes().remove(NotificationHandshakeInterceptor.TICKET_ATTRIBUTE);
        String ticket = ticketValue instanceof String value ? value : null;
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(session, 10000, 256 * 1024);
        try
        {
            NotificationRealtimeService.ConnectionView view = realtimeService.consumeTicket(ticket,
                    payload -> sendAsync(outbound, payload));
            session.getAttributes().put(CONNECTION_ID, view.connectionId());
            session.getAttributes().put(OUTBOUND_SESSION, outbound);
        }
        catch (ServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
        }
        catch (RuntimeException error)
        {
            session.close(CloseStatus.SERVER_ERROR.withReason("实时连接失败"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception
    {
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > MAX_CLIENT_MESSAGE_BYTES)
        {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        String connectionId = (String) session.getAttributes().get(CONNECTION_ID);
        if (connectionId == null)
        {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        JSONObject request;
        try
        {
            request = JSON.parseObject(message.getPayload());
        }
        catch (RuntimeException error)
        {
            session.close(CloseStatus.BAD_DATA.withReason("消息格式错误"));
            return;
        }
        if (request == null || !"HEARTBEAT".equals(request.getString("eventType")))
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("消息类型错误"));
            return;
        }
        try
        {
            if (!realtimeService.heartbeat(connectionId))
            {
                return;
            }
        }
        catch (ServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
            return;
        }
        JSONObject ack = new JSONObject();
        ack.put("eventId", java.util.UUID.randomUUID().toString());
        ack.put("eventType", "HEARTBEAT_ACK");
        JSONObject responseMessage = new JSONObject();
        Object nonce = request.get("nonce");
        if (nonce != null && !(nonce instanceof String))
        {
            session.close(CloseStatus.BAD_DATA.withReason("nonce格式错误"));
            return;
        }
        if (nonce != null)
        {
            responseMessage.put("nonce", nonce);
        }
        ack.put("message", responseMessage);
        ack.put("unreadCount", null);
        ack.put("stateVersion", null);
        ack.put("serverTime", DATE_TIME_FORMATTER.format(Instant.now()));
        WebSocketSession target = (WebSocketSession) session.getAttributes().getOrDefault(OUTBOUND_SESSION, session);
        sendAsync(target, JSON.toJSONString(ack, JSONWriter.Feature.WriteMapNullValue));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
    {
        String connectionId = (String) session.getAttributes().get(CONNECTION_ID);
        if (connectionId != null)
        {
            realtimeService.closeConnection(connectionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception
    {
        String connectionId = (String) session.getAttributes().get(CONNECTION_ID);
        if (connectionId != null)
        {
            realtimeService.closeConnection(connectionId);
        }
        if (session.isOpen())
        {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void sendAsync(WebSocketSession session, String payload)
    {
        try
        {
            outboundExecutors[Math.floorMod(session.getId().hashCode(), outboundExecutors.length)]
                    .execute(() -> sendNow(session, payload));
        }
        catch (RuntimeException error)
        {
            try
            {
                if (session.isOpen())
                {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                }
            }
            catch (Exception ignored) { }
        }
    }

    private static void sendNow(WebSocketSession session, String payload)
    {
        if (!session.isOpen())
        {
            return;
        }
        try
        {
            synchronized (session)
            {
                if (session.isOpen())
                {
                    session.sendMessage(new TextMessage(payload));
                }
                JSONObject event = JSON.parseObject(payload);
                if (event != null && "SESSION_CLOSED".equals(event.getString("eventType")) && session.isOpen())
                {
                    session.close(CloseStatus.NORMAL);
                }
            }
        }
        catch (Exception error)
        {
            try
            {
                if (session.isOpen())
                {
                    session.close(CloseStatus.SERVER_ERROR);
                }
            }
            catch (Exception ignored) { }
        }
    }

    private static ThreadPoolExecutor[] createOutboundExecutors()
    {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[OUTBOUND_STRIPES];
        for (int index = 0; index < executors.length; index++)
        {
            executors[index] = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
        }
        return executors;
    }

    private static ThreadFactory daemonFactory()
    {
        return runnable ->
        {
            Thread thread = new Thread(runnable, "notification-ws-outbound");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String closeReason(String message)
    {
        if (message == null || message.isBlank())
        {
            return "连接已关闭";
        }
        return message.length() <= 30 ? message : message.substring(0, 30);
    }

    @PreDestroy
    void shutdown()
    {
        for (ThreadPoolExecutor executor : outboundExecutors)
        {
            executor.shutdown();
        }
    }
}
