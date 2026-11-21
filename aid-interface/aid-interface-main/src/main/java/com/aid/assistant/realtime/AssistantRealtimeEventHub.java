package com.aid.assistant.realtime;

import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Fans out assistant events locally and across application instances. */
@Component
@Slf4j
public class AssistantRealtimeEventHub
{
    private static final String CHANNEL_PREFIX = "aid:assistant:realtime:event:";
    private static final String SOURCE_NODE_FIELD = "_sourceNodeId";

    @jakarta.annotation.Resource
    private AssistantReasoningArchive reasoningArchive;
    private final RedissonClient redissonClient;
    private final String sourceNodeId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<Long, SessionSubscribers> sessions = new ConcurrentHashMap<>();

    @Value("${assistant.realtime.enabled:true}")
    private boolean realtimeEnabled = true;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleExtensions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aid.assistant.mapper.AidAssistantSessionMapper sessionMapper;
    private final ConcurrentHashMap<Long, ModuleGate> moduleGates = new ConcurrentHashMap<>();
    private record ModuleGate(boolean enabled, long expiresAt) { }

    public AssistantRealtimeEventHub(RedissonClient redissonClient)
    {
        this.redissonClient = redissonClient;
    }

    public AutoCloseable subscribe(Long sessionId, Consumer<AssistantRealtimeSignal> consumer)
    {
        AtomicReference<SessionSubscribers> selected = new AtomicReference<>();
        sessions.compute(sessionId, (ignored, current) ->
        {
            SessionSubscribers state = current == null ? new SessionSubscribers() : current;
            state.consumers.add(consumer);
            selected.set(state);
            return state;
        });
        SessionSubscribers state = selected.get();
        if (!ensureRemoteListener(sessionId, state))
        {
            unsubscribe(sessionId, state, consumer);
            throw new ServiceException("实时订阅不可用");
        }
        return () -> unsubscribe(sessionId, state, consumer);
    }

    public void publish(AssistantRealtimeSignal signal)
    {
        if (!realtimeEnabled || signal == null || signal.getSessionId() == null || !moduleEnabled(signal.getSessionId()))
        {
            return;
        }
        archiveReasoning(signal);
        dispatch(signal.getSessionId(), signal);
        try
        {
            JSONObject message = JSONObject.from(signal);
            message.put(SOURCE_NODE_FIELD, sourceNodeId);
            redissonClient.getTopic(channel(signal.getSessionId())).publish(message.toJSONString());
        }
        catch (RuntimeException error)
        {
            log.warn("助手实时事件发布失败, sessionId={}, type={}, errorType={}", signal.getSessionId(),
                    signal.getType(), error.getClass().getSimpleName());
        }
    }

    private boolean moduleEnabled(Long sessionId) {
        if (sessionMapper == null) return false;
        long now = System.currentTimeMillis();
        ModuleGate cached = moduleGates.get(sessionId);
        if (cached != null && cached.expiresAt() > now) return cached.enabled();
        boolean enabled;
        try {
            var session = sessionMapper.selectById(sessionId);
            String transport = session == null ? null : com.aid.assistant.service.AssistantEntryTransport.of(session);
            enabled = session != null && "0".equals(session.getDelFlag())
                    && (moduleExtensions == null || moduleExtensions.moduleCodes(transport).contains(session.getModuleCode()))
                    && ("CANVAS".equals(transport)
                        || AssistantAutomaticTextExecution.supportsFlow(session) || moduleExtensions != null
                        && moduleExtensions.realtimeWebSocketPath(session.getModuleCode(), transport) != null);
        } catch (RuntimeException unavailable) {
            // Realtime delivery must not abort an otherwise valid model execution.
            log.warn("助手实时模块核对暂不可用, sessionId={}, errorType={}", sessionId, unavailable.getClass().getSimpleName());
            enabled = false;
        }
        synchronized (moduleGates) {
            if (moduleGates.size() >= 2048) moduleGates.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            if (moduleGates.containsKey(sessionId) || moduleGates.size() < 2048)
                moduleGates.put(sessionId, new ModuleGate(enabled, now + (enabled ? 60_000 : 5_000)));
        }
        return enabled;
    }

    private void archiveReasoning(AssistantRealtimeSignal signal) {
        if(reasoningArchive==null || signal.getRunId()==null)return;
        Long step=signal.getOuterStepId()!=null?signal.getOuterStepId():signal.getStepId();
        if(step==null)return;
        boolean ended="STREAM_ENDED".equals(signal.getType());
        if(!ended && !"REASONING_DELTA".equals(signal.getType()))return;
        try {
            JSONObject payload=JSON.parseObject(signal.getPayloadJson());
            if(payload==null || ended && !"REASONING".equals(payload.getString("streamType")))return;
            reasoningArchive.accept(new AssistantReasoningArchive.Chunk(signal.getSessionId(),signal.getRunId(),step,
                    AssistantRealtimeStreamIdentity.resolve(signal,payload),signal.getInnerStepId(),signal.getSkillRunId(),payload.getString("content"),
                    payload.getString("reasoningKind"),ended));
        } catch(RuntimeException invalid) {
            log.warn("助手思考归档接收失败, sessionId={}, stepId={}, errorType={}",signal.getSessionId(),step,invalid.getClass().getSimpleName());
        }
    }

    private boolean ensureRemoteListener(Long sessionId, SessionSubscribers state)
    {
        if (state.listenerId.get() != null)
        {
            return true;
        }
        synchronized (state.listenerLock)
        {
            if (state.listenerId.get() != null || sessions.get(sessionId) != state)
            {
                return state.listenerId.get() != null;
            }
            try
            {
                int listenerId = redissonClient.getTopic(channel(sessionId)).addListener(String.class,
                        (channel, message) -> receiveRemote(sessionId, state, message));
                state.listenerId.set(listenerId);
            }
            catch (RuntimeException error)
            {
                log.warn("助手实时事件订阅失败, sessionId={}, errorType={}", sessionId,
                        error.getClass().getSimpleName());
            }
            return state.listenerId.get() != null;
        }
    }

    private void receiveRemote(Long sessionId, SessionSubscribers state, String message)
    {
        try
        {
            JSONObject value = JSON.parseObject(message);
            if (value == null || sourceNodeId.equals(value.getString(SOURCE_NODE_FIELD))
                    || sessions.get(sessionId) != state)
            {
                return;
            }
            value.remove(SOURCE_NODE_FIELD);
            AssistantRealtimeSignal signal = value.toJavaObject(AssistantRealtimeSignal.class);
            if (signal != null && sessionId.equals(signal.getSessionId()))
            {
                dispatch(sessionId, signal);
            }
        }
        catch (RuntimeException error)
        {
            log.warn("助手实时事件解析失败, sessionId={}, errorType={}", sessionId,
                    error.getClass().getSimpleName());
        }
    }

    private void dispatch(Long sessionId, AssistantRealtimeSignal signal)
    {
        SessionSubscribers state = sessions.get(sessionId);
        if (state == null)
        {
            return;
        }
        for (Consumer<AssistantRealtimeSignal> consumer : state.consumers)
        {
            try
            {
                consumer.accept(signal);
            }
            catch (RuntimeException error)
            {
                unsubscribe(sessionId, state, consumer);
            }
        }
    }

    private void unsubscribe(Long sessionId, SessionSubscribers state,
                             Consumer<AssistantRealtimeSignal> consumer)
    {
        state.consumers.remove(consumer);
        if (!state.consumers.isEmpty() || !sessions.remove(sessionId, state))
        {
            return;
        }
        Integer listenerId = state.listenerId.getAndSet(null);
        if (listenerId == null)
        {
            return;
        }
        try
        {
            redissonClient.getTopic(channel(sessionId)).removeListener(listenerId);
        }
        catch (RuntimeException error)
        {
            log.warn("助手实时事件取消订阅失败, sessionId={}, errorType={}", sessionId,
                    error.getClass().getSimpleName());
        }
    }

    private String channel(Long sessionId)
    {
        return CHANNEL_PREFIX + sessionId;
    }

    private static final class SessionSubscribers
    {
        private final Object listenerLock = new Object();
        private final CopyOnWriteArrayList<Consumer<AssistantRealtimeSignal>> consumers =
                new CopyOnWriteArrayList<>();
        private final AtomicReference<Integer> listenerId = new AtomicReference<>();
    }
}
