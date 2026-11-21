package com.aid.assistant.websocket;

import com.aid.assistant.realtime.AssistantRealtimeService;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles the ticket-authorized assistant realtime protocol. */
@Component
@RequiredArgsConstructor
public class AssistantRealtimeWebSocketHandler extends TextWebSocketHandler
{
    private static final String CONNECTION_ID = "assistantRealtimeConnectionId";
    private static final String OUTBOUND_SESSION = "assistantRealtimeOutboundSession";
    private static final int OUTBOUND_STRIPES = 4;
    private static final int MAX_CLIENT_MESSAGE_BYTES = 4096;
    private final AssistantRealtimeService realtimeService;
    @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}")
    private String contextPath;
    private final ThreadPoolExecutor[] outboundExecutors = createOutboundExecutors();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception
    {
        String ticket = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst("ticket");
        InetSocketAddress remote = session.getRemoteAddress();
        String clientIp = remote == null || remote.getAddress() == null
                ? null : remote.getAddress().getHostAddress();
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(session, 10_000, 256 * 1024);
        AtomicBoolean connected = new AtomicBoolean(false);
        try
        {
            AssistantRealtimeService.ConnectionView view = realtimeService.consumeTicket(ticket, clientIp,
                    payload -> {
                        if (connected.get())
                        {
                            sendAsync(outbound, payload);
                        }
                        else
                        {
                            // Replay is bounded but may be larger than the live outbound queue. Keep
                            // handshake replay ordered and backpressured on this request thread.
                            sendNow(outbound, payload);
                        }
                    }, endpointPath(session));
            session.getAttributes().put(CONNECTION_ID, view.connectionId());
            session.getAttributes().put(OUTBOUND_SESSION, outbound);
            connected.set(true);
            if (!session.isOpen())
            {
                realtimeService.closeConnection(view.connectionId(), "连接已关闭");
            }
        }
        catch (ServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
        }
        catch (AssistantServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
        }
        catch (RuntimeException error)
        {
            session.close(CloseStatus.SERVER_ERROR.withReason("实时连接失败"));
        }
    }

    private String endpointPath(WebSocketSession session) {
        String path = session.getUri() == null ? null : session.getUri().getPath();
        return path != null && contextPath != null && !contextPath.isBlank() && !"/".equals(contextPath)
                && path.startsWith(contextPath + "/") ? path.substring(contextPath.length()) : path;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception
    {
        if (message.getPayloadLength() > MAX_CLIENT_MESSAGE_BYTES)
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
        JSONObject data;
        try
        {
            data = JSON.parseObject(message.getPayload());
        }
        catch (RuntimeException error)
        {
            session.close(CloseStatus.BAD_DATA.withReason("消息格式错误"));
            return;
        }
        String type = data == null ? null : data.getString("type");
        if (!"HEARTBEAT".equals(type) && !"ACK".equals(type))
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("消息类型错误"));
            return;
        }
        try
        {
            AssistantRealtimeService.HeartbeatView heartbeat = realtimeService.heartbeat(
                    connectionId, data.getLong("acknowledgedSeq"));
            if ("HEARTBEAT".equals(type))
            {
                JSONObject ack = new JSONObject();
                ack.put("type", "HEARTBEAT_ACK");
                ack.put("nonce", data.getString("nonce"));
                ack.put("serverTime", new java.util.Date());
                ack.put("lastEventSeq", heartbeat.lastEventSeq());
                WebSocketSession outbound = (WebSocketSession) session.getAttributes()
                        .getOrDefault(OUTBOUND_SESSION, session);
                sendAsync(outbound, ack.toJSONString());
            }
        }
        catch (ServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
        }
        catch (AssistantServiceException error)
        {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(closeReason(error.getMessage())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
    {
        String connectionId = (String) session.getAttributes().get(CONNECTION_ID);
        if (connectionId != null)
        {
            realtimeService.closeConnection(connectionId, "连接已关闭");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception
    {
        String connectionId = (String) session.getAttributes().get(CONNECTION_ID);
        if (connectionId != null)
        {
            realtimeService.closeConnection(connectionId, "连接异常");
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
            outboundExecutor(session).execute(() -> sendNow(session, payload));
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

    private ThreadPoolExecutor outboundExecutor(WebSocketSession session)
    {
        return outboundExecutors[Math.floorMod(session.getId().hashCode(), outboundExecutors.length)];
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
                if (event != null && "SESSION_CLOSED".equals(event.getString("type")) && session.isOpen())
                {
                    session.close(CloseStatus.NORMAL.withReason(closeReason(event.getString("reason"))));
                }
                else if (event != null && "SNAPSHOT_REQUIRED".equals(event.getString("type"))
                        && session.isOpen())
                {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                }
            }
        }
        catch (Exception ignored)
        {
            try
            {
                if (session.isOpen())
                {
                    session.close(CloseStatus.SERVER_ERROR);
                }
            }
            catch (Exception closeIgnored) { }
        }
    }

    private static ThreadPoolExecutor[] createOutboundExecutors()
    {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[OUTBOUND_STRIPES];
        for (int index = 0; index < executors.length; index++)
        {
            executors[index] = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
        }
        return executors;
    }

    private static ThreadFactory daemonFactory()
    {
        return runnable ->
        {
            Thread thread = new Thread(runnable, "assistant-ws-outbound");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String closeReason(String reason)
    {
        if (reason == null || reason.isBlank())
        {
            return "连接已关闭";
        }
        return reason.length() <= 30 ? reason : reason.substring(0, 30);
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
