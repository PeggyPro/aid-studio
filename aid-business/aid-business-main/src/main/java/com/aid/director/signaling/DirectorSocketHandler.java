package com.aid.director.signaling;

import com.aid.director.DirectorException;
import com.aid.director.DirectorProperties;
import com.aid.director.DirectorIds;
import com.aid.director.DirectorSignalBus;
import com.aid.director.DirectorSignalingService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 首帧票据认证与有界云端信令连接。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectorSocketHandler extends TextWebSocketHandler {
    private final DirectorSignalingService signaling;
    private final DirectorSignalBus bus;
    private final DirectorProperties properties;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor[] senders = new ThreadPoolExecutor[4];
    private ScheduledExecutorService sweeper;

    private static class Connection {
        final String id = DirectorIds.token();
        final long openedAt = System.currentTimeMillis();
        final WebSocketSession socket;
        volatile long seenAt = System.currentTimeMillis();
        volatile DirectorSignalingService.Endpoint endpoint;
        Connection(WebSocketSession socket) { this.socket = new ConcurrentWebSocketSessionDecorator(socket, 2000, 256 * 1024); }
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        for (int i = 0; i < senders.length; i++) senders[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> { Thread t = new Thread(runnable, "director-signal-send"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> { Thread t = new Thread(runnable, "director-signal-expiry"); t.setDaemon(true); return t; });
        sweeper.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            for (Connection connection : connections.values()) {
                if (connection.endpoint == null ? now - connection.openedAt > 5000 : now - connection.seenAt > 30000)
                    close(connection, CloseStatus.POLICY_VIOLATION);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!properties.isEnabled() || connections.size() >= properties.getMaxRooms() * 2 + 20) {
            session.close(CloseStatus.SERVICE_OVERLOAD); return;
        }
        session.setTextMessageSizeLimit(128 * 1024);
        Connection connection = new Connection(session); connections.put(session.getId(), connection);
        send(connection, JSONObject.of("type", "AUTH_REQUIRED", "timeoutSeconds", 5).toJSONString());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        if (message.getPayloadLength() > 128 * 1024) { close(connection, CloseStatus.TOO_BIG_TO_PROCESS); return; }
        try {
            JSONObject payload = JSON.parseObject(message.getPayload());
            if (payload == null) throw DirectorException.invalid();
            if (connection.endpoint == null) {
                if (!Set.of("type", "ticket").equals(payload.keySet()) || !"AUTH".equals(payload.getString("type"))) throw DirectorException.denied();
                bus.register(connection.id, text -> send(connection, text));
                try { connection.endpoint = signaling.connect(payload.getString("ticket"), connection.id); }
                catch (RuntimeException error) { bus.unregister(connection.id); throw error; }
                send(connection, signaling.connected(connection.endpoint).toJSONString());
            } else signaling.receive(connection.endpoint, payload);
            connection.seenAt = System.currentTimeMillis();
        } catch (DirectorException error) {
            boolean fatal = connection.endpoint == null || error.getStatus() == 401 || error.getStatus() == 403
                    || error.getStatus() == 404 || error.getStatus() == 410;
            send(connection, JSONObject.of("type", "ERROR", "code", error.getErrorCode(), "message", error.getMessage(), "fatal", fatal).toJSONString());
        } catch (RuntimeException error) {
            log.info("虚拟相机信令拒绝 type={}", error.getClass().getSimpleName());
            send(connection, JSONObject.of("type", "ERROR", "code", "INVALID_MESSAGE", "message", "消息不可用", "fatal", true).toJSONString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        if (connection != null) {
            bus.unregister(connection.id);
            try { signaling.disconnect(connection.endpoint); }
            catch (RuntimeException ignored) { log.info("虚拟相机连接清理等待租约回收"); }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }

    private void send(Connection connection, String payload) {
        ThreadPoolExecutor sender = senders[Math.floorMod(connection.id.hashCode(), senders.length)];
        if (sender == null) { close(connection, CloseStatus.SERVICE_RESTARTED); return; }
        try {
            sender.execute(() -> {
                try {
                    if (connection.socket.isOpen()) connection.socket.sendMessage(new TextMessage(payload));
                    JSONObject message = JSON.parseObject(payload);
                    if (Set.of("ENDPOINT_REPLACED", "SESSION_CLOSED").contains(message.getString("type"))) close(connection, CloseStatus.NORMAL);
                    else if ("ERROR".equals(message.getString("type")) && message.getBooleanValue("fatal")) close(connection, CloseStatus.POLICY_VIOLATION);
                } catch (Exception ignored) { close(connection, CloseStatus.SESSION_NOT_RELIABLE); }
            });
        } catch (RuntimeException ignored) { close(connection, CloseStatus.SESSION_NOT_RELIABLE); }
    }

    private void close(Connection connection, CloseStatus status) {
        try { if (connection.socket.isOpen()) connection.socket.close(status); }
        catch (Exception ignored) { }
    }

    @PreDestroy
    public void shutdown() {
        if (sweeper != null) sweeper.shutdownNow();
        for (Connection connection : connections.values()) close(connection, CloseStatus.SERVICE_RESTARTED);
        for (ThreadPoolExecutor sender : senders) if (sender != null) sender.shutdown();
    }
}
