package com.aid.canvas.websocket;

import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class CanvasWebSocketHandler extends TextWebSocketHandler {
    private static final String SESSION_ID = "canvasSessionId";
    private static final String OUTBOUND_SESSION = "canvasOutboundSession";
    private static final int OUTBOUND_STRIPES = 4;
    private final CanvasCollaborationService collaborationService;
    private final ThreadPoolExecutor[] outboundExecutors = createOutboundExecutors();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams().getFirst("ticket");
        InetSocketAddress remote = session.getRemoteAddress();
        String clientIp = remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
        try {
            WebSocketSession outboundSession = new ConcurrentWebSocketSessionDecorator(session, 10_000, 256 * 1024);
            CanvasCollaborationService.SessionView view = collaborationService.consumeTicket(ticket, clientIp,
                    payload -> sendAsync(outboundSession, payload));
            session.getAttributes().put(SESSION_ID, view.sessionId());
            session.getAttributes().put(OUTBOUND_SESSION, outboundSession);
            sendAsync(outboundSession, JSON.toJSONString(Map.of("type", "CONNECTED", "sessionId", view.sessionId(),
                    "workspaceId", JsonSafeId.value(view.workspaceId()), "taskEventsVersion", 1)));
        } catch (ServiceException e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getMessage()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > 4096) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        String sessionId = (String) session.getAttributes().get(SESSION_ID);
        if (sessionId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        JSONObject data;
        try { data = JSON.parseObject(message.getPayload()); }
        catch (Exception e) {
            session.close(CloseStatus.BAD_DATA.withReason("消息不是合法JSON"));
            return;
        }
        String type = data.getString("type");
        if (!"HEARTBEAT".equals(type) && !"ACK".equals(type)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("WebSocket只接受心跳与确认消息"));
            return;
        }
        Long acknowledgedSeq = data.getLong("acknowledgedSeq");
        collaborationService.heartbeat(sessionId, acknowledgedSeq);
        if ("HEARTBEAT".equals(type)) {
            WebSocketSession outbound = (WebSocketSession) session.getAttributes().getOrDefault(OUTBOUND_SESSION, session);
            JSONObject ack = new JSONObject();
            ack.put("type", "HEARTBEAT_ACK");
            ack.put("nonce", data.getString("nonce"));
            sendAsync(outbound, JSON.toJSONString(ack));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID);
        if (sessionId != null) collaborationService.closeSession(sessionId, "连接已关闭");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = (String) session.getAttributes().get(SESSION_ID);
        if (sessionId != null) collaborationService.closeSession(sessionId, "连接异常");
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private void sendAsync(WebSocketSession session, String payload) {
        try {
            outboundExecutor(session).execute(() -> sendNow(session, payload));
        } catch (RuntimeException e) {
            try { if (session.isOpen()) session.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) { }
        }
    }

    private ThreadPoolExecutor outboundExecutor(WebSocketSession session) {
        return outboundExecutors[Math.floorMod(session.getId().hashCode(), outboundExecutors.length)];
    }

    private static void sendNow(WebSocketSession session, String payload) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
                JSONObject event = JSON.parseObject(payload);
                if ("SESSION_CLOSED".equals(event.getString("type")) && session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason(event.getString("reason")));
                }
            }
        } catch (Exception e) {
            try { if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
        }
    }

    @PreDestroy
    void shutdown() {
        for (ThreadPoolExecutor executor : outboundExecutors) executor.shutdown();
    }

    private static ThreadPoolExecutor[] createOutboundExecutors() {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[OUTBOUND_STRIPES];
        for (int i = 0; i < executors.length; i++) {
            executors[i] = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
        }
        return executors;
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "canvas-ws-outbound");
            thread.setDaemon(true);
            return thread;
        };
    }
}
