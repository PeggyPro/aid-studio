package com.aid.assistant.realtime;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRealtimeRequests;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.service.AssistantEntryTransport;
import com.aid.assistant.vo.AssistantRealtimeViews;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Authorizes tickets, replays committed events, and owns live assistant connections. */
@Service
@Slf4j
public class AssistantRealtimeServiceImpl implements AssistantRealtimeService
{
    private static final String PROTOCOL = "assistant-realtime/v1";
    private static final String TICKET_PREFIX = "assistant:realtime:ticket:";
    private static final String CONNECTION_MAP = "assistant:realtime:connections";
    private static final String ADMISSION_LOCK = "assistant:realtime:admission";
    private static final int TICKET_TTL_SECONDS = 60;
    private static final int HEARTBEAT_SECONDS = 15;
    private static final int IDLE_TIMEOUT_SECONDS = 45;
    private static final int RECONNECT_BASE_MILLIS = 1000;
    private static final int RECONNECT_MAX_MILLIS = 30_000;
    private static final long AUTHORIZATION_RECHECK_MILLIS = 15_000L;
    private static final int PENDING_SIGNAL_LIMIT = 256;
    private static final int TICKET_MAX_LENGTH = 160;
    private static final int ABSOLUTE_USER_CONNECTION_LIMIT = 32;
    private static final int ABSOLUTE_SESSION_CONNECTION_LIMIT = 8;
    private static final int ABSOLUTE_NODE_CONNECTION_LIMIT = 10_000;
    private static final int ABSOLUTE_REPLAY_EVENT_LIMIT = 5_000;

    private final AssistantCoreService assistantCoreService;
    private final AidRuntimeEventMapper eventMapper;
    private final RedisCache redisCache;
    private final RedissonClient redissonClient;
    private final AssistantRealtimeEventHub eventHub;
    private final ConcurrentHashMap<String, LiveConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AuthorizationKey, AuthorizationLease> authorizationLeases =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AuthorizationKey, ProjectionRefresh> projectionRefreshes =
            new ConcurrentHashMap<>();
    // Reads must not run in Redis dispatch, provider callbacks, or under a connection lock.
    private final ThreadPoolExecutor projectionReaders = new ThreadPoolExecutor(4, 4, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable ->
    {
        Thread thread = new Thread(runnable, "assistant-projection-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService idleCleaner = Executors.newSingleThreadScheduledExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "assistant-realtime-idle-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${assistant.realtime.max-connections-per-user:3}")
    private int maxConnectionsPerUser;
    @Value("${assistant.realtime.max-connections-per-session:2}")
    private int maxConnectionsPerSession;
    @Value("${assistant.realtime.max-connections-per-node:1000}")
    private int maxConnectionsPerNode;
    @Value("${assistant.realtime.max-replay-events:500}")
    private int maxReplayEvents;
    @Value("${assistant.realtime.enabled:true}")
    private boolean realtimeEnabled = true;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleExtensions;

    public AssistantRealtimeServiceImpl(AssistantCoreService assistantCoreService,
                                        AidRuntimeEventMapper eventMapper,
                                        RedisCache redisCache,
                                        RedissonClient redissonClient,
                                        AssistantRealtimeEventHub eventHub)
    {
        this.assistantCoreService = assistantCoreService;
        this.eventMapper = eventMapper;
        this.redisCache = redisCache;
        this.redissonClient = redissonClient;
        this.eventHub = eventHub;
        idleCleaner.scheduleWithFixedDelay(this::cleanupIdleConnections,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public AssistantRealtimeViews.Ticket createTicket(Long userId, AssistantRealtimeRequests.Ticket request)
    {
        AidAssistantSession session = assistantCoreService.authorizeSession(userId, request.getSessionId(), false);
        String endpointPath = requireEnabled(session);
        long lastEventSeq = value(session.getLastEventSeq());
        if (request.getAfterSeq() > lastEventSeq)
        {
            throw failure(HttpStatus.CONFLICT, "ASSISTANT_REALTIME_CURSOR_INVALID", "事件序号无效",
                    Map.of("afterSeq", request.getAfterSeq(), "lastEventSeq", lastEventSeq));
        }
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String ticket = UUID.randomUUID() + "." + UUID.randomUUID();
        TicketPayload payload = new TicketPayload();
        payload.setConnectionId(connectionId);
        payload.setSessionId(session.getId());
        payload.setUserId(userId);
        payload.setAfterSeq(request.getAfterSeq());
        payload.setEndpointPath(endpointPath);
        payload.setProjectionVersion(Integer.valueOf(1).equals(request.getProjectionVersion()) ? 1 : 0);
        redisCache.setCacheObject(ticketKey(ticket), payload, TICKET_TTL_SECONDS, TimeUnit.SECONDS);

        AssistantRealtimeViews.Ticket result = new AssistantRealtimeViews.Ticket();
        result.setSessionId(String.valueOf(session.getId()));
        result.setTicket(ticket);
        result.setExpireAt(Date.from(Instant.now().plusSeconds(TICKET_TTL_SECONDS)));
        result.setWebsocketPath(endpointPath + "?ticket=" + ticket);
        result.setHeartbeatSeconds(HEARTBEAT_SECONDS);
        result.setIdleTimeoutSeconds(IDLE_TIMEOUT_SECONDS);
        result.setReconnectBaseMillis(RECONNECT_BASE_MILLIS);
        result.setReconnectMaxMillis(RECONNECT_MAX_MILLIS);
        result.setLastEventSeq(lastEventSeq);
        result.setProjectionVersion(payload.getProjectionVersion());
        return result;
    }

    @Override
    public ConnectionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound)
    {
        return consumeTicket(ticket, clientIp, outbound, "/ws/canvas/assistant");
    }
    @Override
    public ConnectionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound, String endpointPath)
    {
        if (StrUtil.isBlank(ticket) || ticket.length() > TICKET_MAX_LENGTH)
        {
            throw failure(HttpStatus.UNAUTHORIZED, "ASSISTANT_REALTIME_TICKET_INVALID", "实时票据无效", null);
        }
        String key = ticketKey(ticket);
        TicketPayload payload = redisCache.getCacheObject(key, TicketPayload.class);
        if (payload == null || !redisCache.deleteObject(key))
        {
            throw failure(HttpStatus.UNAUTHORIZED, "ASSISTANT_REALTIME_TICKET_INVALID", "实时票据已失效", null);
        }
        AidAssistantSession session = assistantCoreService.authorizeSession(
                payload.getUserId(), payload.getSessionId(), false);
        String authorizedPath = requireEnabled(session);
        String ticketPath = payload.getEndpointPath() == null ? "/ws/canvas/assistant" : payload.getEndpointPath();
        if (!authorizedPath.equals(endpointPath) || !ticketPath.equals(endpointPath))
            throw failure(HttpStatus.UNAUTHORIZED, "ASSISTANT_REALTIME_ENDPOINT_MISMATCH", "实时票据与入口不匹配", null);
        LiveConnection live = new LiveConnection(payload.getConnectionId(), payload.getSessionId(),
                payload.getUserId(), payload.getAfterSeq(), outbound);
        live.projectionVersion = payload.getProjectionVersion();
        try
        {
            admit(payload, clientIp, live);
            try
            {
                live.subscription = eventHub.subscribe(live.sessionId, signal -> deliver(live, signal));
            }
            catch (ServiceException error)
            {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_REALTIME_SUBSCRIPTION_UNAVAILABLE",
                        "实时订阅暂不可用", null);
            }
            // Read the authoritative cursor only after the live subscription exists. Any event that
            // commits during replay is then either inside this boundary or buffered by deliver().
            AidAssistantSession replaySession = authorizeRealtimeSession(payload.getUserId(), payload.getSessionId());
            replayAndActivate(live, replaySession);
            rememberAuthorization(live.userId, live.sessionId, live.lastAuthorizedEventSeq);
            return new ConnectionView(live.connectionId, live.sessionId, live.userId,
                    live.requestedAfterSeq, live.lastDeliveredSeq);
        }
        catch (RuntimeException error)
        {
            removeConnection(live);
            throw error;
        }
    }

    @Override
    public HeartbeatView heartbeat(String connectionId, Long acknowledgedSeq)
    {
        LiveConnection live = connections.get(connectionId);
        if (live == null)
        {
            throw failure(HttpStatus.GONE, "ASSISTANT_REALTIME_CONNECTION_EXPIRED", "实时连接已失效", null);
        }
        long now = System.currentTimeMillis();
        live.lastHeartbeatAt = now;
        if (acknowledgedSeq != null && acknowledgedSeq >= 0)
        {
            live.acknowledgedSeq = Math.max(live.acknowledgedSeq,
                    Math.min(acknowledgedSeq, live.lastDeliveredSeq));
        }
        try
        {
            long authorizedLastSeq = authorizedLastSeq(live, now);
            live.lastAuthorizedEventSeq = Math.max(live.lastAuthorizedEventSeq, authorizedLastSeq);
            if (authorizedLastSeq > live.lastDeliveredSeq)
            {
                if (live.projectionVersion == 1) requestProjection(live, authorizedLastSeq);
                else snapshotRequired(live, "DURABLE_GAP", authorizedLastSeq);
            }
            else if (live.projectionVersion == 1 && live.hasActiveMedia)
            {
                // Media acceptance/progress does not advance the conversation journal.
                // Reuse the authorized, coalesced projection reader on existing heartbeats.
                // ACK frames and multiple sockets must not create an unbounded read loop.
                AuthorizationKey key = new AuthorizationKey(live.userId, live.sessionId);
                ProjectionRefresh refresh = projectionRefreshes.computeIfAbsent(key, ignored -> new ProjectionRefresh());
                long previous = refresh.lastMediaRefreshAt.get();
                if (now - previous >= 15_000L && refresh.lastMediaRefreshAt.compareAndSet(previous, now))
                    requestProjection(live, authorizedLastSeq);
            }
        }
        catch (RuntimeException denied)
        {
            revokeLocalConnections(live.userId, live.sessionId, "会话权限已失效");
            throw denied;
        }
        storePresence(live);
        return new HeartbeatView(live.sessionId, live.lastAuthorizedEventSeq);
    }

    @Override
    public void closeConnection(String connectionId, String reason)
    {
        LiveConnection live = connections.remove(connectionId);
        if (live == null)
        {
            return;
        }
        removeConnection(live);
        synchronized (live.signalLock)
        {
            if (live.closed)
            {
                return;
            }
            live.closed = true;
            JSONObject closed = new JSONObject();
            closed.put("type", "SESSION_CLOSED");
            closed.put("reason", safeReason(reason));
            safelySend(live, closed);
        }
    }

    private void admit(TicketPayload payload, String clientIp, LiveConnection live)
    {
        RLock lock = redissonClient.getLock(ADMISSION_LOCK);
        boolean locked;
        try
        {
            // Use Redisson's watchdog-backed lease while admission scans the distributed map.
            // A fixed lease could expire mid-scan on a slow or large cluster and admit two
            // connections concurrently past the configured bounds.
            locked = lock.tryLock(2L, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw admissionUnavailable();
        }
        if (!locked)
        {
            throw admissionUnavailable();
        }
        try
        {
            RMapCache<String, String> distributed = connectionMap();
            boolean localAdded = false;
            try
            {
                if (connections.size() >= nodeConnectionLimit())
                {
                    throw capacityFailure();
                }
                int userCount = 0;
                int sessionCount = 0;
                for (String value : distributed.values())
                {
                    try
                    {
                        ConnectionMetadata current = JSON.parseObject(value, ConnectionMetadata.class);
                        if (current == null)
                        {
                            continue;
                        }
                        if (Objects.equals(current.getUserId(), payload.getUserId()))
                        {
                            userCount++;
                        }
                        if (Objects.equals(current.getSessionId(), payload.getSessionId()))
                        {
                            sessionCount++;
                        }
                    }
                    catch (RuntimeException ignored)
                    {
                        // A malformed distributed presence must not undercount user/session capacity.
                        throw admissionUnavailable();
                    }
                }
                if (userCount >= userConnectionLimit() || sessionCount >= sessionConnectionLimit())
                {
                    throw capacityFailure();
                }
                ConnectionMetadata metadata = new ConnectionMetadata();
                metadata.setConnectionId(payload.getConnectionId());
                metadata.setSessionId(payload.getSessionId());
                metadata.setUserId(payload.getUserId());
                metadata.setClientIp(clientIp);
                connections.put(live.connectionId, live);
                localAdded = true;
                distributed.put(payload.getConnectionId(), JSON.toJSONString(metadata),
                        IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            catch (AssistantServiceException known)
            {
                throw known;
            }
            catch (RuntimeException unavailable)
            {
                if (localAdded)
                {
                    connections.remove(live.connectionId, live);
                    try
                    {
                        distributed.remove(live.connectionId);
                    }
                    catch (RuntimeException ignored) { }
                }
                throw admissionUnavailable();
            }
        }
        finally
        {
            if (lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
    }

    private void replayAndActivate(LiveConnection live, AidAssistantSession session)
    {
        long authoritativeLastSeq = value(session.getLastEventSeq());
        JSONObject initialProjection = null;
        if (live.projectionVersion == 1)
        {
            initialProjection = readProjection(live.userId, live.sessionId);
            JSONObject frame = AssistantSessionProjection.frame(String.valueOf(live.sessionId),
                    live.connectionId, live.requestedAfterSeq, null, initialProjection);
            // Large histories use the existing paged HTTP/replay protocol, without truncation.
            if (!AssistantSessionProjection.fits(frame))
            {
                live.projectionVersion = 0;
                initialProjection = null;
            }
            else authoritativeLastSeq = initialProjection.getLongValue("lastEventSeq");
        }
        live.lastAuthorizedEventSeq = authoritativeLastSeq;
        JSONObject connected = new JSONObject();
        connected.put("type", "CONNECTED");
        connected.put("protocolVersion", 1);
        connected.put("protocol", PROTOCOL);
        connected.put("sessionId", String.valueOf(live.sessionId));
        connected.put("resumeAfterSeq", live.requestedAfterSeq);
        connected.put("lastEventSeq", authoritativeLastSeq);
        connected.put("heartbeatSeconds", HEARTBEAT_SECONDS);
        connected.put("projectionVersion", live.projectionVersion);
        connected.put("subscriptionId", live.connectionId);
        safelySend(live, connected);

        if (initialProjection != null)
        {
            sendProjection(live, initialProjection);
        }
        else
        {
            List<AidRuntimeEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AidRuntimeEvent>()
                    .eq(AidRuntimeEvent::getSessionId, live.sessionId)
                    .gt(AidRuntimeEvent::getSeq, live.requestedAfterSeq)
                    .le(AidRuntimeEvent::getSeq, authoritativeLastSeq)
                    .orderByAsc(AidRuntimeEvent::getSeq)
                    .last("LIMIT " + (replayEventLimit() + 1)));
            if (events.size() > replayEventLimit()
                    || !contiguous(events, live.requestedAfterSeq, authoritativeLastSeq))
            {
                snapshotRequired(live, "REPLAY_UNAVAILABLE", authoritativeLastSeq);
                return;
            }
            for (AidRuntimeEvent event : events)
            {
                sendDurable(live, eventSignal(event));
            }
        }
        synchronized (live.signalLock)
        {
            if (live.pendingOverflow)
            {
                live.pendingSignals.clear();
                live.initializing = false;
                snapshotRequired(live, "REALTIME_OVERFLOW", authoritativeLastSeq);
                return;
            }
            AssistantRealtimeSignal signal;
            while ((signal = live.pendingSignals.pollFirst()) != null)
            {
                sendSignal(live, signal);
                if (live.snapshotRequired)
                {
                    live.pendingSignals.clear();
                    break;
                }
            }
            live.initializing = false;
        }
    }

    private void deliver(LiveConnection live, AssistantRealtimeSignal signal)
    {
        if (connections.get(live.connectionId) != live || live.snapshotRequired)
        {
            return;
        }
        synchronized (live.signalLock)
        {
            if (live.closed || live.snapshotRequired
                    || connections.get(live.connectionId) != live)
            {
                return;
            }
            if (live.initializing)
            {
                if (live.pendingSignals.size() >= PENDING_SIGNAL_LIMIT)
                {
                    live.pendingOverflow = true;
                    live.pendingSignals.clear();
                }
                else if (!live.pendingOverflow)
                {
                    live.pendingSignals.addLast(signal);
                }
                return;
            }
            sendSignal(live, signal);
        }
    }

    private void sendSignal(LiveConnection live, AssistantRealtimeSignal signal)
    {
        if ("TASK_CHANGED".equals(signal.getType()))
        {
            // Media rows can change without advancing the runtime-event cursor.
            // Read the coherent authorized projection even when fromSeq == toSeq.
            if (live.projectionVersion == 1) requestProjection(live, live.lastAuthorizedEventSeq);
            else snapshotRequired(live, "UPSTREAM_GAP", live.lastAuthorizedEventSeq);
            return;
        }
        if ("RUNTIME_EVENT".equals(signal.getType()))
        {
            if (live.projectionVersion == 1)
            {
                if (signal.getSeq() != null && signal.getSeq() > live.lastDeliveredSeq)
                    requestProjection(live, signal.getSeq());
            }
            else sendDurable(live, signal);
            return;
        }
        JSONObject message = baseSignal(signal);
        message.put("transient", true);
        JSONObject data = parsePayload(signal.getPayloadJson());
        if ("TEXT_DELTA".equals(signal.getType()) || "REASONING_DELTA".equals(signal.getType()))
        {
            if (data == null || StrUtil.isBlank(data.getString("content")))
            {
                return;
            }
            if ("REASONING_DELTA".equals(signal.getType())
                    && !("PUBLIC_SUMMARY".equals(data.getString("reasoningKind")) || "PROVIDER_REASONING".equals(data.getString("reasoningKind"))))
            {
                return;
            }
            message.put("content", data.getString("content"));
            message.put("reset", data.getBooleanValue("reset"));
            message.put("streamId", StrUtil.blankToDefault(signal.getStreamId(), streamId(signal, data)));
            if ("REASONING_DELTA".equals(signal.getType()))
            {
                message.put("reasoningKind", data.getString("reasoningKind"));
            }
        }
        else if ("STREAM_ENDED".equals(signal.getType()))
        {
            if (data == null || !List.of("TEXT", "REASONING").contains(data.getString("streamType"))
                    || !List.of("COMPLETED", "FAILED", "STOPPED").contains(data.getString("status")))
            {
                return;
            }
            message.put("streamId", StrUtil.blankToDefault(signal.getStreamId(), streamId(signal, data)));
            message.put("streamType", data.getString("streamType"));
            message.put("status", data.getString("status"));
        }
        else if ("SNAPSHOT_REQUIRED".equals(signal.getType()))
        {
            snapshotRequired(live, "UPSTREAM_GAP", live.lastAuthorizedEventSeq);
            return;
        }
        else
        {
            message.put("data", data);
        }
        safelySend(live, message);
    }

    private void sendDurable(LiveConnection live, AssistantRealtimeSignal signal)
    {
        Long seq = signal.getSeq();
        if (seq == null || seq <= live.lastDeliveredSeq)
        {
            return;
        }
        if (seq != live.lastDeliveredSeq + 1)
        {
            snapshotRequired(live, "DURABLE_GAP", Math.max(live.lastAuthorizedEventSeq, seq));
            return;
        }
        JSONObject message = new JSONObject();
        message.put("type", "RUNTIME_EVENT");
        message.put("sessionId", String.valueOf(live.sessionId));
        message.put("seq", seq);
        message.put("event", runtimeEvent(signal));
        safelySend(live, message);
        live.lastDeliveredSeq = seq;
        live.lastAuthorizedEventSeq = Math.max(live.lastAuthorizedEventSeq, seq);
    }

    private JSONObject readProjection(Long userId, Long sessionId)
    {
        AssistantRequests.SessionDetail request = new AssistantRequests.SessionDetail();
        request.setSessionId(sessionId);
        return AssistantSessionProjection.snapshot(assistantCoreService.sessionDetail(userId, request));
    }

    private void sendProjection(LiveConnection live, JSONObject current)
    {
        synchronized (live.signalLock)
        {
            if (live.closed || live.snapshotRequired || connections.get(live.connectionId) != live
                    || current.getLongValue("lastEventSeq") < live.lastDeliveredSeq) return;
            JSONObject frame = AssistantSessionProjection.frame(String.valueOf(live.sessionId),
                    live.connectionId, live.lastDeliveredSeq, live.projection, current);
            if (!AssistantSessionProjection.fits(frame))
            {
                snapshotRequired(live, "PROJECTION_TOO_LARGE", current.getLongValue("lastEventSeq"));
                return;
            }
            // Commit the local base only after successful delivery; outbound failure closes it.
            try { live.outbound.accept(AssistantSessionProjection.encode(frame)); }
            catch (RuntimeException error) { closeConnection(live.connectionId, "消息发送失败"); return; }
            // Retain field fingerprints, not a copy of the whole conversation for every socket.
            live.projection = AssistantSessionProjection.fingerprint(current);
            live.hasActiveMedia = AssistantSessionProjection.hasActiveMedia(current);
            live.lastDeliveredSeq = current.getLongValue("lastEventSeq");
            live.lastAuthorizedEventSeq = Math.max(live.lastAuthorizedEventSeq, live.lastDeliveredSeq);
        }
    }

    private List<LiveConnection> projectionMembers(AuthorizationKey key)
    {
        return connections.values().stream().filter(live -> live.projectionVersion == 1
                && !live.closed && !live.snapshotRequired && !live.initializing
                && Objects.equals(live.userId, key.userId())
                && Objects.equals(live.sessionId, key.sessionId())).toList();
    }

    private void requestProjection(LiveConnection live, long requiredSeq)
    {
        AuthorizationKey key = new AuthorizationKey(live.userId, live.sessionId);
        ProjectionRefresh refresh = projectionRefreshes.computeIfAbsent(key, ignored -> new ProjectionRefresh());
        refresh.requiredSeq.accumulateAndGet(requiredSeq, Math::max);
        refresh.revision.incrementAndGet();
        scheduleProjection(key, refresh);
    }

    private void scheduleProjection(AuthorizationKey key, ProjectionRefresh refresh)
    {
        if (!refresh.queued.compareAndSet(false, true)) return;
        try
        {
            idleCleaner.schedule(() ->
            {
                try { projectionReaders.execute(() -> refreshProjection(key, refresh)); }
                catch (RejectedExecutionException rejected) { failProjection(key, refresh, "PROJECTION_BUSY"); }
            }, 100, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException stopped)
        {
            // The scheduler only rejects during shutdown. shutdown() closes all sockets;
            // do not acquire other connection locks while the caller may hold one.
            refresh.queued.set(false);
            projectionRefreshes.remove(key, refresh);
        }
    }

    private void refreshProjection(AuthorizationKey key, ProjectionRefresh refresh)
    {
        long observed = refresh.revision.get();
        try
        {
            if (projectionMembers(key).isEmpty()) return;
            JSONObject current = readProjection(key.userId(), key.sessionId());
            if (current.getLongValue("lastEventSeq") < refresh.requiredSeq.get())
            {
                // A commit during the read needs another coherent read, not an inconsistent patch.
                if (observed != refresh.revision.get()) return;
                failProjection(key, refresh, "PROJECTION_CURSOR_BEHIND");
                return;
            }
            projectionMembers(key).forEach(live -> sendProjection(live, current));
        }
        catch (RuntimeException failure)
        {
            // Read failures never issue generation commands, and never become fictional completion.
            failProjection(key, refresh, "PROJECTION_READ_FAILED");
        }
        finally
        {
            refresh.queued.set(false);
            if (projectionMembers(key).isEmpty()) projectionRefreshes.remove(key, refresh);
            else if (observed != refresh.revision.get()) scheduleProjection(key, refresh);
        }
    }

    private void failProjection(AuthorizationKey key, ProjectionRefresh refresh, String reason)
    {
        projectionMembers(key).forEach(live -> snapshotRequired(live, reason, refresh.requiredSeq.get()));
        refresh.queued.set(false);
        projectionRefreshes.remove(key, refresh);
    }

    private JSONObject runtimeEvent(AssistantRealtimeSignal signal)
    {
        JSONObject event = new JSONObject();
        event.put("eventId", id(signal.getEventId()));
        event.put("sessionId", String.valueOf(signal.getSessionId()));
        event.put("seq", signal.getSeq());
        event.put("type", signal.getEventType());
        event.put("runId", id(signal.getRunId()));
        event.put("entityId", signal.getEntityId());
        event.put("entityRevision", signal.getEntityRevision());
        event.put("causationId", signal.getCausationId());
        event.put("occurredAt", format(signal.getCreateTime()));
        event.put("data", parseData(signal.getPayloadJson()));
        return event;
    }

    private JSONObject baseSignal(AssistantRealtimeSignal signal)
    {
        JSONObject message = new JSONObject();
        message.put("type", signal.getType());
        message.put("sessionId", String.valueOf(signal.getSessionId()));
        message.put("runId", id(signal.getRunId()));
        message.put("activityId", signal.getActivityId());
        message.put("outerStepId", id(signal.getOuterStepId()));
        message.put("innerStepId", id(signal.getInnerStepId()));
        message.put("skillRunId", id(signal.getSkillRunId()));
        message.put("activityKind", signal.getActivityKind());
        message.put("safeSummary", signal.getSafeSummary());
        message.put("status", signal.getStatus());
        message.put("sourceEventSeq", signal.getSourceEventSeq());
        message.put("stepKind", signal.getStepKind());
        message.put("eventType", signal.getEventType());
        message.put("stage", signal.getStage());
        message.put("stepId", id(signal.getStepId()));
        message.put("mediaTaskId", id(signal.getMediaTaskId()));
        message.put("occurredAt", format(signal.getCreateTime()));
        return message;
    }

    private void snapshotRequired(LiveConnection live, String reason, long lastEventSeq)
    {
        synchronized (live.signalLock)
        {
            if (live.closed || live.snapshotRequired)
            {
                return;
            }
            live.snapshotRequired = true;
            JSONObject message = new JSONObject();
            message.put("type", "SNAPSHOT_REQUIRED");
            message.put("sessionId", String.valueOf(live.sessionId));
            message.put("reason", reason);
            message.put("requestedAfterSeq", live.requestedAfterSeq);
            message.put("lastEventSeq", lastEventSeq);
            safelySend(live, message);
        }
    }

    private void safelySend(LiveConnection live, JSONObject message)
    {
        try
        {
            live.outbound.accept(message.toJSONString());
        }
        catch (RuntimeException error)
        {
            closeConnection(live.connectionId, "消息发送失败");
        }
    }

    private void storePresence(LiveConnection live)
    {
        try
        {
            String current = connectionMap().get(live.connectionId);
            if (current == null)
            {
                closeConnection(live.connectionId, "连接状态已失效");
                throw failure(HttpStatus.GONE, "ASSISTANT_REALTIME_CONNECTION_EXPIRED",
                        "实时连接已失效", null);
            }
            connectionMap().put(live.connectionId, current, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (AssistantServiceException known)
        {
            throw known;
        }
        catch (RuntimeException unavailable)
        {
            closeConnection(live.connectionId, "连接续期失败");
            throw admissionUnavailable();
        }
    }

    private void removeConnection(LiveConnection live)
    {
        connections.remove(live.connectionId, live);
        try
        {
            connectionMap().remove(live.connectionId);
        }
        catch (RuntimeException error)
        {
            log.warn("助手实时连接状态删除失败, connectionId={}, errorType={}",
                    live.connectionId, error.getClass().getSimpleName());
        }
        AutoCloseable subscription = live.subscription;
        live.subscription = null;
        if (subscription != null)
        {
            try
            {
                subscription.close();
            }
            catch (Exception ignored)
            {
                // The expiring distributed presence remains the crash-recovery safety net.
            }
        }
        AuthorizationKey key = new AuthorizationKey(live.userId, live.sessionId);
        if (connections.values().stream().noneMatch(current -> Objects.equals(current.userId, live.userId)
                && Objects.equals(current.sessionId, live.sessionId)))
        {
            authorizationLeases.remove(key);
            ProjectionRefresh refresh = projectionRefreshes.get(key);
            if (refresh != null && !refresh.queued.get()) projectionRefreshes.remove(key, refresh);
        }
    }

    private long authorizedLastSeq(LiveConnection live, long now)
    {
        requireEnabled();
        AuthorizationKey key = new AuthorizationKey(live.userId, live.sessionId);
        AuthorizationLease lease = authorizationLeases.computeIfAbsent(key,
                ignored -> new AuthorizationLease());
        synchronized (lease)
        {
            if (lease.validUntil > now)
            {
                return lease.lastEventSeq;
            }
            AidAssistantSession session = authorizeRealtimeSession(live.userId, live.sessionId);
            lease.lastEventSeq = value(session.getLastEventSeq());
            lease.validUntil = now + AUTHORIZATION_RECHECK_MILLIS;
            return lease.lastEventSeq;
        }
    }

    private void rememberAuthorization(Long userId, Long sessionId, long lastEventSeq)
    {
        AuthorizationLease lease = authorizationLeases.computeIfAbsent(
                new AuthorizationKey(userId, sessionId), ignored -> new AuthorizationLease());
        synchronized (lease)
        {
            lease.lastEventSeq = Math.max(lease.lastEventSeq, lastEventSeq);
            lease.validUntil = System.currentTimeMillis() + AUTHORIZATION_RECHECK_MILLIS;
        }
    }

    private void revokeLocalConnections(Long userId, Long sessionId, String reason)
    {
        authorizationLeases.remove(new AuthorizationKey(userId, sessionId));
        new ArrayList<>(connections.values()).stream()
                .filter(live -> Objects.equals(live.userId, userId)
                        && Objects.equals(live.sessionId, sessionId))
                .forEach(live -> closeConnection(live.connectionId, reason));
    }

    private void cleanupIdleConnections()
    {
        try
        {
            long cutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(IDLE_TIMEOUT_SECONDS);
            connections.values().stream().filter(live -> live.lastHeartbeatAt < cutoff)
                    .forEach(live -> closeConnection(live.connectionId, "连接心跳超时"));
        }
        catch (RuntimeException error)
        {
            log.warn("助手实时连接清理失败, errorType={}", error.getClass().getSimpleName());
        }
    }

    private boolean contiguous(List<AidRuntimeEvent> events, long afterSeq, long authoritativeLastSeq)
    {
        long expected = afterSeq + 1;
        for (AidRuntimeEvent event : events)
        {
            if (event.getSeq() == null || event.getSeq() != expected++)
            {
                return false;
            }
        }
        return expected - 1 == authoritativeLastSeq;
    }

    private AssistantRealtimeSignal eventSignal(AidRuntimeEvent event)
    {
        AssistantRealtimeSignal signal = new AssistantRealtimeSignal();
        signal.setType("RUNTIME_EVENT");
        signal.setEventId(event.getId());
        signal.setSessionId(event.getSessionId());
        signal.setSeq(event.getSeq());
        signal.setRunId(event.getRunId());
        signal.setEventType(event.getEventType());
        signal.setEntityId(event.getEntityId());
        signal.setEntityRevision(event.getEntityRevision());
        signal.setCausationId(event.getCausationId());
        signal.setPayloadJson(event.getPayloadJson());
        signal.setCreateTime(event.getCreateTime());
        return signal;
    }

    private JSONObject parsePayload(String payloadJson)
    {
        if (StrUtil.isBlank(payloadJson))
        {
            return null;
        }
        try
        {
            Object value = JSON.parse(payloadJson);
            if (value instanceof JSONObject object)
            {
                return object;
            }
            JSONObject wrapped = new JSONObject();
            wrapped.put("value", value instanceof JSONArray ? value : String.valueOf(value));
            return wrapped;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private Object parseData(String payloadJson)
    {
        if (StrUtil.isBlank(payloadJson))
        {
            return null;
        }
        try
        {
            return JSON.parse(payloadJson);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private String streamId(AssistantRealtimeSignal signal, JSONObject data)
    {
        return AssistantRealtimeStreamIdentity.resolve(signal, data);
    }

    private String ticketKey(String ticket)
    {
        return TICKET_PREFIX + ticket;
    }

    private String requireEnabled(AidAssistantSession session)
    {
        requireEnabled();
        String transport = AssistantEntryTransport.of(session);
        if (moduleExtensions != null && !moduleExtensions.moduleCodes(transport).contains(session.getModuleCode()))
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_REALTIME_UNAVAILABLE", "当前会话不支持此入口", null);
        if ("CANVAS".equals(transport)) return "/ws/canvas/assistant";
        if (com.aid.assistant.service.AssistantAutomaticTextExecution.supportsFlow(session))
            return com.aid.assistant.creative.series.FlowScreenplaySourcePolicy.REALTIME_PATH;
        String path = moduleExtensions == null ? null : moduleExtensions.realtimeWebSocketPath(session.getModuleCode(), transport);
        if (path != null) return path;
        throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_REALTIME_UNAVAILABLE", "实时通道未启用", null);
    }

    private void requireEnabled()
    {
        if (!realtimeEnabled)
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_REALTIME_UNAVAILABLE", "实时通道未启用", null);
    }

    private AidAssistantSession authorizeRealtimeSession(Long userId, Long sessionId)
    {
        AidAssistantSession session = assistantCoreService.authorizeSession(userId, sessionId, false);
        requireEnabled(session);
        return session;
    }

    private RMapCache<String, String> connectionMap()
    {
        return redissonClient.getMapCache(CONNECTION_MAP);
    }

    private int userConnectionLimit()
    {
        return boundedLimit(maxConnectionsPerUser, 3, ABSOLUTE_USER_CONNECTION_LIMIT);
    }

    private int sessionConnectionLimit()
    {
        return boundedLimit(maxConnectionsPerSession, 2, ABSOLUTE_SESSION_CONNECTION_LIMIT);
    }

    private int nodeConnectionLimit()
    {
        return boundedLimit(maxConnectionsPerNode, 1000, ABSOLUTE_NODE_CONNECTION_LIMIT);
    }

    private int replayEventLimit()
    {
        return boundedLimit(maxReplayEvents, 500, ABSOLUTE_REPLAY_EVENT_LIMIT);
    }

    private AssistantServiceException capacityFailure()
    {
        return failure(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_REALTIME_CAPACITY",
                "实时连接已达上限", null);
    }

    private AssistantServiceException admissionUnavailable()
    {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_REALTIME_ADMISSION_UNAVAILABLE",
                "实时连接暂不可用", null);
    }

    private static AssistantServiceException failure(HttpStatus status, String code, String message,
                                                     Object details)
    {
        return new AssistantServiceException(status.value(), code, message, details);
    }

    private static int boundedLimit(int configured, int fallback, int maximum)
    {
        return Math.min(configured > 0 ? configured : fallback, maximum);
    }

    private static long value(Long value)
    {
        return value == null ? 0L : value;
    }

    private static String id(Long value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeReason(String reason)
    {
        String value = StrUtil.blankToDefault(reason, "连接已关闭");
        return value.length() <= 40 ? value : value.substring(0, 40);
    }

    private static String format(Date value)
    {
        return value == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
    }

    @PreDestroy
    void shutdown()
    {
        idleCleaner.shutdownNow();
        projectionReaders.shutdownNow();
        projectionRefreshes.clear();
        new ArrayList<>(connections.values()).forEach(live -> closeConnection(live.connectionId, "服务已停止"));
    }

    @Data
    private static class TicketPayload implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private String connectionId;
        private String endpointPath;
        private Long sessionId;
        private Long userId;
        private Long afterSeq;
        private int projectionVersion;
    }

    @Data
    private static class ConnectionMetadata implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private String connectionId;
        private Long sessionId;
        private Long userId;
        private String clientIp;
    }

    private static final class LiveConnection
    {
        private final String connectionId;
        private final Long sessionId;
        private final Long userId;
        private final long requestedAfterSeq;
        private final Consumer<String> outbound;
        private final Object signalLock = new Object();
        private final ArrayDeque<AssistantRealtimeSignal> pendingSignals = new ArrayDeque<>();
        private volatile AutoCloseable subscription;
        private volatile boolean initializing = true;
        private volatile boolean pendingOverflow;
        private volatile boolean snapshotRequired;
        private volatile boolean closed;
        private volatile long lastDeliveredSeq;
        private volatile long lastAuthorizedEventSeq;
        private volatile long acknowledgedSeq;
        private volatile long lastHeartbeatAt = System.currentTimeMillis();
        private volatile int projectionVersion;
        private Map<String, String> projection;
        private volatile boolean hasActiveMedia;

        private LiveConnection(String connectionId, Long sessionId, Long userId, long requestedAfterSeq,
                               Consumer<String> outbound)
        {
            this.connectionId = connectionId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.requestedAfterSeq = requestedAfterSeq;
            this.lastDeliveredSeq = requestedAfterSeq;
            this.acknowledgedSeq = requestedAfterSeq;
            this.outbound = outbound;
        }
    }

    private record AuthorizationKey(Long userId, Long sessionId) { }

    private static final class ProjectionRefresh
    {
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicLong revision = new AtomicLong();
        private final AtomicLong requiredSeq = new AtomicLong();
        private final AtomicLong lastMediaRefreshAt = new AtomicLong();
    }

    private static final class AuthorizationLease
    {
        private long validUntil;
        private long lastEventSeq;
    }
}
