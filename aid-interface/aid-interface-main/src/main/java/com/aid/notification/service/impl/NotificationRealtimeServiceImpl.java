package com.aid.notification.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.constant.CacheConstants;
import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.notification.domain.AidSystemNotification;
import com.aid.notification.mapper.AidSystemNotificationMapper;
import com.aid.notification.service.NotificationRealtimeService;
import com.aid.notification.service.NotificationStateQueryService;
import com.aid.notification.vo.NotificationViews;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 站内通知账户级实时连接与Redis轻量信号实现。 */
@Slf4j
@Service
public class NotificationRealtimeServiceImpl implements NotificationRealtimeService
{
    public static final String SIGNAL_CHANNEL_PREFIX = "aid:notification:signal";
    public static final String NEGOTIATED_PROTOCOL = "aid-notification";
    public static final String TICKET_PROTOCOL_PREFIX = "ticket.";
    private static final String TICKET_PREFIX = "notification:realtime:ticket:";
    private static final int TICKET_TTL_SECONDS = 60;
    private static final int HEARTBEAT_SECONDS = 20;
    private static final int IDLE_TIMEOUT_SECONDS = 60;
    private static final int RECONNECT_BASE_MILLIS = 1000;
    private static final int RECONNECT_MAX_MILLIS = 30000;
    private static final int ABSOLUTE_USER_CONNECTION_LIMIT = 20;
    private static final int ABSOLUTE_NODE_CONNECTION_LIMIT = 10000;
    private static final int SYNC_BATCH_LIMIT = 200;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BUSINESS_ZONE);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final DefaultRedisScript<String> CONSUME_TICKET_SCRIPT = new DefaultRedisScript<>(
            "local v=redis.call('get',KEYS[1]); if v then redis.call('del',KEYS[1]); end; return v", String.class);

    private final AidSystemNotificationMapper mapper;
    private final NotificationStateQueryService stateQueryService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisCache redisCache;
    private final String signalChannel;
    private final ConcurrentHashMap<String, LiveConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, LiveConnection>> userConnections =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> userConnectionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingSignal> pendingSignals = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor projectionReaders = new ThreadPoolExecutor(4, 4, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512), daemonFactory("notification-state-reader"),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicInteger syncCursor = new AtomicInteger();
    private final AtomicInteger nodeConnectionCount = new AtomicInteger();

    @Value("${notification.realtime.max-connections-per-user:5}")
    private int maxConnectionsPerUser;

    @Value("${notification.realtime.max-connections-per-node:5000}")
    private int maxConnectionsPerNode;

    public NotificationRealtimeServiceImpl(AidSystemNotificationMapper mapper,
                                           NotificationStateQueryService stateQueryService,
                                           StringRedisTemplate stringRedisTemplate,
                                           RedisCache redisCache,
                                           Environment environment)
    {
        this.mapper = mapper;
        this.stateQueryService = stateQueryService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisCache = redisCache;
        this.signalChannel = resolveSignalChannel(environment);
    }

    @Override
    public String signalChannel()
    {
        return signalChannel;
    }

    @Override
    public NotificationViews.Ticket createTicket(Long userId, String loginToken, String knownStateVersion)
    {
        if (userId == null || StrUtil.isBlank(loginToken))
        {
            throw failure(HttpStatus.UNAUTHORIZED, "NOTIFICATION_LOGIN_REQUIRED", "登录状态失效");
        }
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        TicketPayload payload = new TicketPayload();
        payload.setConnectionId(UUID.randomUUID().toString().replace("-", ""));
        payload.setUserId(userId);
        payload.setLoginToken(loginToken);
        payload.setKnownStateVersion(knownStateVersion);
        stringRedisTemplate.opsForValue().set(ticketKey(ticket), JSON.toJSONString(payload),
                TICKET_TTL_SECONDS, TimeUnit.SECONDS);

        NotificationViews.Ticket result = new NotificationViews.Ticket();
        result.setTicket(ticket);
        result.setWebsocketPath("/ws/notification");
        result.setProtocols(List.of(NEGOTIATED_PROTOCOL, TICKET_PROTOCOL_PREFIX + ticket));
        result.setExpireAt(Date.from(Instant.now().plusSeconds(TICKET_TTL_SECONDS)));
        result.setHeartbeatSeconds(HEARTBEAT_SECONDS);
        result.setIdleTimeoutSeconds(IDLE_TIMEOUT_SECONDS);
        result.setReconnectBaseMillis(RECONNECT_BASE_MILLIS);
        result.setReconnectMaxMillis(RECONNECT_MAX_MILLIS);
        result.setStateVersion(stateQueryService.get(userId).getStateVersion());
        return result;
    }

    @Override
    public ConnectionView consumeTicket(String ticket, Consumer<String> outbound)
    {
        if (StrUtil.isBlank(ticket) || ticket.length() > 128 || outbound == null)
        {
            throw failure(HttpStatus.UNAUTHORIZED, "NOTIFICATION_TICKET_INVALID", "实时票据无效");
        }
        String payloadJson = stringRedisTemplate.execute(CONSUME_TICKET_SCRIPT, List.of(ticketKey(ticket)));
        TicketPayload payload;
        try
        {
            payload = StrUtil.isBlank(payloadJson) ? null : JSON.parseObject(payloadJson, TicketPayload.class);
        }
        catch (RuntimeException error)
        {
            payload = null;
        }
        if (payload == null || !isLoginSessionActive(payload))
        {
            throw failure(HttpStatus.UNAUTHORIZED, "NOTIFICATION_TICKET_EXPIRED", "实时票据已失效");
        }
        admit(payload.getUserId());
        LiveConnection live = new LiveConnection(payload.getConnectionId(), payload.getUserId(),
                payload.getLoginToken(), outbound);
        synchronized (live)
        {
            if (connections.putIfAbsent(live.connectionId, live) != null)
            {
                releaseAdmission(live.userId);
                throw failure(HttpStatus.CONFLICT, "NOTIFICATION_CONNECTION_CONFLICT", "实时连接冲突");
            }
            userConnections.compute(live.userId, (ignored, bucket) ->
            {
                ConcurrentHashMap<String, LiveConnection> target = bucket == null
                        ? new ConcurrentHashMap<>() : bucket;
                target.put(live.connectionId, live);
                return target;
            });
            try
            {
                NotificationViews.State state = stateQueryService.get(live.userId);
                send(live.userId, state, "STATE_SYNC", null, null, Set.of(live.connectionId), true);
                return new ConnectionView(live.connectionId, live.userId);
            }
            catch (RuntimeException error)
            {
                remove(live.connectionId);
                throw error;
            }
        }
    }

    @Override
    public boolean heartbeat(String connectionId)
    {
        LiveConnection live = connections.get(connectionId);
        if (live == null)
        {
            throw failure(HttpStatus.UNAUTHORIZED, "NOTIFICATION_CONNECTION_EXPIRED", "实时连接已失效");
        }
        if (!mapper.isActiveCUser(live.userId) || !isLoginSessionActive(live.userId, live.loginToken))
        {
            closeUnauthorized(live);
            return false;
        }
        live.lastSeenAt = System.currentTimeMillis();
        return true;
    }

    @Override
    public void closeConnection(String connectionId)
    {
        remove(connectionId);
    }

    @Override
    public void publishNotificationChanged(Long notificationId, String eventType)
    {
        AidSystemNotification notification = mapper.selectRealtimeSummary(notificationId);
        if (notification == null)
        {
            return;
        }
        Signal signal = new Signal();
        signal.setEventId(UUID.randomUUID().toString());
        signal.setEventType(eventType);
        signal.setNotificationId(notificationId);
        signal.setRecipientType(notification.getRecipientType());
        signal.setTitle(notification.getTitle());
        signal.setSummary(notification.getSummary());
        signal.setMessageType(notification.getMessageType());
        signal.setPublishTime(formatTime(notification.getPublishTime()));
        stringRedisTemplate.convertAndSend(signalChannel, JSON.toJSONString(signal));
    }

    @Override
    public void publishUserStateChanged(Long userId)
    {
        Signal signal = new Signal();
        signal.setEventId(UUID.randomUUID().toString());
        signal.setEventType("NOTIFICATION_STATE_CHANGED");
        signal.setUserId(userId);
        stringRedisTemplate.convertAndSend(signalChannel, JSON.toJSONString(signal));
    }

    @Override
    public void acceptClusterSignal(String payload)
    {
        Signal signal;
        try
        {
            signal = JSON.parseObject(payload, Signal.class);
        }
        catch (RuntimeException error)
        {
            log.warn("忽略格式错误的通知实时信号");
            return;
        }
        if (signal == null || StrUtil.isBlank(signal.getEventType()))
        {
            return;
        }
        if (signal.getUserId() != null)
        {
            if (userConnections.containsKey(signal.getUserId()))
            {
                enqueueRefresh(signal.getUserId(), signal);
            }
            return;
        }
        if ("USERS".equals(signal.getRecipientType()) && signal.getNotificationId() != null)
        {
            for (Long userId : mapper.selectRecipientIds(signal.getNotificationId()))
            {
                if (userConnections.containsKey(userId))
                {
                    enqueueRefresh(userId, signal);
                }
            }
            return;
        }
        for (Long userId : userConnections.keySet())
        {
            enqueueRefresh(userId, signal);
        }
    }

    @Override
    public void synchronizeOnlineUsers()
    {
        List<Long> users = new ArrayList<>(userConnections.keySet());
        if (users.isEmpty())
        {
            return;
        }
        users.sort(Long::compareTo);
        int start = Math.floorMod(syncCursor.getAndAdd(SYNC_BATCH_LIMIT), users.size());
        int count = Math.min(SYNC_BATCH_LIMIT, users.size());
        for (int index = 0; index < count; index++)
        {
            Long userId = users.get((start + index) % users.size());
            Signal signal = new Signal();
            signal.setEventId(UUID.randomUUID().toString());
            signal.setEventType("STATE_SYNC");
            enqueueRefresh(userId, signal);
        }
    }

    @Override
    public void cleanupIdleConnections()
    {
        long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_SECONDS * 1000L;
        for (LiveConnection live : connections.values())
        {
            if (live.lastSeenAt < cutoff && remove(live.connectionId) != null)
            {
                JSONObject event = envelope(UUID.randomUUID().toString(), "SESSION_CLOSED", null,
                        null, live.lastStateVersion);
                safeOutbound(live, JSON.toJSONString(event, JSONWriter.Feature.WriteMapNullValue));
            }
        }
    }

    private void enqueueRefresh(Long userId, Signal signal)
    {
        AtomicReference<PendingSignal> admitted = new AtomicReference<>();
        userConnections.computeIfPresent(userId, (key, bucket) ->
        {
            if (bucket.isEmpty())
            {
                return bucket;
            }
            PendingSignal pending = pendingSignals.compute(userId, (ignored, current) ->
            {
                PendingSignal value = current == null || current.retired.get() ? new PendingSignal() : current;
                value.latest.set(signal);
                return value;
            });
            admitted.set(pending);
            return bucket;
        });
        PendingSignal pending = admitted.get();
        if (pending != null)
        {
            scheduleRefresh(userId, pending);
        }
    }

    private void scheduleRefresh(Long userId, PendingSignal pending)
    {
        if (pending.retired.get() || !pending.scheduled.compareAndSet(0, 1))
        {
            return;
        }
        try
        {
            projectionReaders.execute(() -> drainRefresh(userId, pending));
        }
        catch (RuntimeException error)
        {
            pending.scheduled.set(0);
            log.warn("通知实时刷新队列已满，将由后续信号或周期同步修复, userId={}", userId);
        }
    }

    private void drainRefresh(Long userId, PendingSignal pending)
    {
        try
        {
            while (!pending.retired.get() && userConnections.containsKey(userId))
            {
                Signal signal = pending.latest.getAndSet(null);
                if (signal == null)
                {
                    break;
                }
                if (!retainAuthorizedConnections(userId))
                {
                    return;
                }
                NotificationViews.State state = stateQueryService.get(userId);
                boolean periodic = "STATE_SYNC".equals(signal.getEventType());
                send(userId, state, signal.getEventType(), realtimeMessage(signal),
                        signal.getEventId(), null, !periodic);
            }
        }
        catch (RuntimeException error)
        {
            log.warn("通知实时状态刷新失败, userId={}", userId);
        }
        finally
        {
            pending.scheduled.set(0);
            if (!pending.retired.get() && pending.latest.get() != null && userConnections.containsKey(userId))
            {
                scheduleRefresh(userId, pending);
            }
        }
    }

    private void send(Long userId, NotificationViews.State state, String eventType,
                      JSONObject message, String eventId, Set<String> onlyConnections, boolean force)
    {
        JSONObject event = envelope(StrUtil.blankToDefault(eventId, UUID.randomUUID().toString()), eventType, message,
                state.getUnreadCount(), state.getStateVersion());
        String encoded = JSON.toJSONString(event, JSONWriter.Feature.WriteMapNullValue);
        Map<String, LiveConnection> bucket = userConnections.get(userId);
        if (bucket == null)
        {
            return;
        }
        for (LiveConnection live : bucket.values())
        {
            if (!Objects.equals(live.userId, userId)
                    || (onlyConnections != null && !onlyConnections.contains(live.connectionId)))
            {
                continue;
            }
            synchronized (live)
            {
                int comparison = compareStateVersion(state.getStateVersion(), live.lastStateVersion);
                if (comparison < 0 || (!force && comparison == 0))
                {
                    continue;
                }
                live.lastStateVersion = state.getStateVersion();
                safeOutbound(live, encoded);
            }
        }
    }

    private static int compareStateVersion(String candidate, String current)
    {
        if (current == null)
        {
            return 1;
        }
        if (candidate == null)
        {
            return -1;
        }
        try
        {
            String[] left = candidate.split(":", -1);
            String[] right = current.split(":", -1);
            if (left.length != 2 || right.length != 2)
            {
                return candidate.equals(current) ? 0 : 1;
            }
            int visibility = Long.compare(Long.parseLong(left[0]), Long.parseLong(right[0]));
            return visibility != 0 ? visibility : Long.compare(Long.parseLong(left[1]), Long.parseLong(right[1]));
        }
        catch (NumberFormatException ignored)
        {
            return candidate.equals(current) ? 0 : 1;
        }
    }

    private JSONObject realtimeMessage(Signal signal)
    {
        if (signal.getNotificationId() == null)
        {
            return null;
        }
        JSONObject message = new JSONObject();
        message.put("id", String.valueOf(signal.getNotificationId()));
        message.put("title", signal.getTitle());
        message.put("summary", signal.getSummary());
        message.put("messageType", signal.getMessageType());
        message.put("publishTime", signal.getPublishTime());
        return message;
    }

    private JSONObject envelope(String eventId, String eventType, JSONObject message,
                                Long unreadCount, String stateVersion)
    {
        JSONObject event = new JSONObject();
        event.put("eventId", eventId);
        event.put("eventType", eventType);
        event.put("message", message);
        event.put("unreadCount", unreadCount);
        event.put("stateVersion", stateVersion);
        event.put("serverTime", formatTime(new Date()));
        return event;
    }

    private boolean isLoginSessionActive(TicketPayload payload)
    {
        if (payload.getUserId() == null || StrUtil.isBlank(payload.getLoginToken()))
        {
            return false;
        }
        return mapper.isActiveCUser(payload.getUserId())
                && isLoginSessionActive(payload.getUserId(), payload.getLoginToken());
    }

    private void admit(Long userId)
    {
        int nodeLimit = Math.max(1, Math.min(maxConnectionsPerNode, ABSOLUTE_NODE_CONNECTION_LIMIT));
        while (true)
        {
            int current = nodeConnectionCount.get();
            if (current >= nodeLimit)
            {
                throw failure(HttpStatus.ERROR, "NOTIFICATION_CONNECTION_LIMIT", "实时连接繁忙");
            }
            if (nodeConnectionCount.compareAndSet(current, current + 1))
            {
                break;
            }
        }
        int userLimit = Math.max(1, Math.min(maxConnectionsPerUser, ABSOLUTE_USER_CONNECTION_LIMIT));
        AtomicBoolean userAdmitted = new AtomicBoolean();
        userConnectionCounts.compute(userId, (ignored, count) ->
        {
            int current = count == null ? 0 : count.get();
            if (current >= userLimit)
            {
                return count;
            }
            userAdmitted.set(true);
            if (count == null)
            {
                return new AtomicInteger(1);
            }
            count.incrementAndGet();
            return count;
        });
        if (!userAdmitted.get())
        {
            nodeConnectionCount.decrementAndGet();
            throw failure(HttpStatus.CONFLICT, "NOTIFICATION_USER_CONNECTION_LIMIT", "实时连接过多");
        }
    }

    private LiveConnection remove(String connectionId)
    {
        LiveConnection removed = connections.remove(connectionId);
        if (removed == null)
        {
            return null;
        }
        userConnections.computeIfPresent(removed.userId, (ignored, bucket) ->
        {
            bucket.remove(connectionId);
            if (bucket.isEmpty())
            {
                retirePending(removed.userId);
                return null;
            }
            return bucket;
        });
        nodeConnectionCount.decrementAndGet();
        userConnectionCounts.computeIfPresent(removed.userId,
                (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
        return removed;
    }

    private void retirePending(Long userId)
    {
        PendingSignal pending = pendingSignals.remove(userId);
        if (pending != null)
        {
            pending.retired.set(true);
            pending.latest.set(null);
        }
    }

    private void releaseAdmission(Long userId)
    {
        nodeConnectionCount.decrementAndGet();
        userConnectionCounts.computeIfPresent(userId,
                (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private boolean retainAuthorizedConnections(Long userId)
    {
        Map<String, LiveConnection> bucket = userConnections.get(userId);
        if (bucket == null || bucket.isEmpty())
        {
            return false;
        }
        if (!mapper.isActiveCUser(userId))
        {
            for (LiveConnection live : List.copyOf(bucket.values()))
            {
                closeUnauthorized(live);
            }
            return false;
        }
        for (LiveConnection live : List.copyOf(bucket.values()))
        {
            if (!isLoginSessionActive(userId, live.loginToken))
            {
                closeUnauthorized(live);
            }
        }
        Map<String, LiveConnection> retained = userConnections.get(userId);
        return retained != null && !retained.isEmpty();
    }

    private boolean isLoginSessionActive(Long userId, String loginToken)
    {
        if (userId == null || StrUtil.isBlank(loginToken))
        {
            return false;
        }
        LoginUser login = redisCache.getCacheObject(CacheConstants.LOGIN_TOKEN_KEY + loginToken, LoginUser.class);
        return login != null && Objects.equals(login.getUserId(), userId)
                && Objects.equals(login.getToken(), loginToken);
    }

    private void closeUnauthorized(LiveConnection live)
    {
        if (remove(live.connectionId) == null)
        {
            return;
        }
        JSONObject event = envelope(UUID.randomUUID().toString(), "SESSION_CLOSED", null,
                null, live.lastStateVersion);
        safeOutbound(live, JSON.toJSONString(event, JSONWriter.Feature.WriteMapNullValue));
    }

    private void safeOutbound(LiveConnection live, String payload)
    {
        try
        {
            live.outbound.accept(payload);
        }
        catch (RuntimeException error)
        {
            remove(live.connectionId);
        }
    }

    private String ticketKey(String ticket)
    {
        return TICKET_PREFIX + sha256(ticket);
    }

    private String formatTime(Date value)
    {
        return value == null ? null : DATE_TIME_FORMATTER.format(value.toInstant());
    }

    private static String resolveSignalChannel(Environment environment)
    {
        String configured = environment.getProperty("notification.realtime.channel");
        String channel;
        if (StrUtil.isNotBlank(configured))
        {
            channel = configured.trim();
        }
        else
        {
            String database = environment.getProperty("spring.data.redis.database");
            if (StrUtil.isBlank(database))
            {
                database = environment.getProperty("spring.redis.database", "0");
            }
            channel = SIGNAL_CHANNEL_PREFIX + ":db" + database.trim();
        }
        if (channel.length() > 128 || !channel.matches("[A-Za-z0-9:_.-]+"))
        {
            throw new IllegalArgumentException("notification.realtime.channel格式无效");
        }
        return channel;
    }

    private String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException error)
        {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private ServiceException failure(int httpCode, String code, String message)
    {
        log.info("notification realtime rejected, code={}", code);
        ApiErrorDetails detail = new ApiErrorDetails();
        detail.setErrorCode(code);
        detail.setReason(message);
        detail.setRetryable(httpCode >= 500);
        return new ServiceException(message, httpCode).setClientErrorData(detail);
    }

    private static ThreadFactory daemonFactory(String name)
    {
        return runnable ->
        {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown()
    {
        projectionReaders.shutdown();
        connections.clear();
        userConnectionCounts.clear();
        userConnections.clear();
        pendingSignals.clear();
    }

    private static final class LiveConnection
    {
        private final String connectionId;
        private final Long userId;
        private final String loginToken;
        private final Consumer<String> outbound;
        private volatile long lastSeenAt = System.currentTimeMillis();
        private volatile String lastStateVersion;

        private LiveConnection(String connectionId, Long userId, String loginToken, Consumer<String> outbound)
        {
            this.connectionId = connectionId;
            this.userId = userId;
            this.loginToken = loginToken;
            this.outbound = outbound;
        }
    }

    private static final class PendingSignal
    {
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicReference<Signal> latest = new AtomicReference<>();
    }

    @Data
    private static class TicketPayload
    {
        private String connectionId;
        private Long userId;
        private String loginToken;
        private String knownStateVersion;
    }

    @Data
    private static class Signal
    {
        private String eventId;
        private String eventType;
        private Long notificationId;
        private Long userId;
        private String recipientType;
        private String title;
        private String summary;
        private String messageType;
        private String publishTime;
    }
}
