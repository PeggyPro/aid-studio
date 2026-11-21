package com.aid.director;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 有界信令事件的准确端点投递。 */
@Component
@RequiredArgsConstructor
public class DirectorSignalBus {
    private final DirectorRoomStore store;
    private final DirectorProperties properties;
    private final String instanceId = DirectorIds.token();
    private final Map<String, Consumer<String>> connections = new ConcurrentHashMap<>();
    private RedisMessageListenerContainer container;
    private java.util.concurrent.ThreadPoolExecutor listenerExecutor;

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        container = new RedisMessageListenerContainer();
        listenerExecutor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(256), task -> {
                    Thread thread = new Thread(task, "director-signal-route"); thread.setDaemon(true); return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        container.setTaskExecutor(listenerExecutor);
        container.setConnectionFactory(java.util.Objects.requireNonNull(store.redis().getConnectionFactory()));
        container.addMessageListener((message, pattern) -> {
            if (message.getBody().length > 262144) return;
            try {
                JSONObject envelope = JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8));
                deliver(envelope.getString("connectionId"), envelope.getString("payload"));
            } catch (RuntimeException ignored) { /* 发布通知可重发，不记录包含SDP的原文。 */ }
        }, new ChannelTopic(topic(instanceId)));
        container.afterPropertiesSet(); container.start();
    }

    public String instanceId() { return instanceId; }

    public void register(String connectionId, Consumer<String> sender) {
        if (connections.size() >= Math.max(2, properties.getMaxRooms() * 2 + 10))
            throw new DirectorException("CAPACITY_REACHED", 429, "连接数量已达上限");
        connections.put(connectionId, sender);
    }

    public void unregister(String connectionId) { connections.remove(connectionId); }

    public void send(String instance, String connection, JSONObject value) {
        if (instance == null || connection == null) return;
        String payload = value.toJSONString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 131072) throw DirectorException.invalid();
        DirectorRoomStore.afterCommit(() -> {
            if (instanceId.equals(instance)) deliver(connection, payload);
            else store.redis().convertAndSend(topic(instance), JSONObject.of("connectionId", connection, "payload", payload).toJSONString());
        });
    }

    public void both(DirectorRoom room, JSONObject value) {
        send(room.getHostInstance(), room.getHostConnection(), value);
        send(room.getGuestInstance(), room.getGuestConnection(), value);
    }

    private void deliver(String id, String payload) {
        Consumer<String> sender = connections.get(id);
        if (sender != null) sender.accept(payload);
    }

    private String topic(String instance) { return "aid:director:signal:" + instance; }

    @PreDestroy
    public void close() { if (container != null) container.stop(); if (listenerExecutor != null) listenerExecutor.shutdown(); connections.clear(); }
}
