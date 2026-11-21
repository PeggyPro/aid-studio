package com.aid.assistant.realtime;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTextStreamEvent;
import com.aid.runtime.assistant.recovery.AssistantRuntimeTaskIdentityResolver;
import com.aid.runtime.assistant.NativeAssistantVisibleTextPolicy;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Streams public main-Agent text and provider-declared public reasoning. */
@Component
@RequiredArgsConstructor
public class AssistantMainRealtimeBridge
{
    private static final int FLUSH_CHARS = 512;
    private static final int MAX_CHUNK_CHARS = 4096;
    private static final int MAX_ACTIVE_TASK_STREAMS = 4096;
    private static final long FLUSH_MILLIS = 50L;

    private final AssistantRealtimeEventHub eventHub;
    private final AidMediaTaskMapper mediaTasks;
    private final AssistantRuntimeTaskIdentityResolver identities;
    private final ConcurrentHashMap<Long, Stream> activeByTask = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flushExecutor = Executors.newScheduledThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "assistant-main-stream");
        thread.setDaemon(true);
        return thread;
    });

    public Stream open(Long sessionId, Long runId, Long stepId)
    {
        return open(sessionId, runId, stepId, "MAIN_AGENT");
    }

    public Stream open(Long sessionId, Long runId, Long stepId, String stepKind)
    {
        if (sessionId == null || runId == null || stepId == null)
        {
            throw new IllegalArgumentException("Main assistant stream identity is required");
        }
        return new Stream(sessionId, runId, stepId, StrUtil.blankToDefault(stepKind, "MAIN_AGENT"));
    }

    @EventListener
    public void onMediaTextStream(MediaTextStreamEvent event)
    {
        if (event == null || event.getTaskId() == null || event.getUserId() == null) return;
        if ("STARTED".equals(event.getPhase()))
        {
            var identity = identities.resolve(event.getTaskId(), event.getUserId());
            if (identity == null || !acceptsStartedTask(Boolean.TRUE.equals(identity.run().getStopRequested()),
                    identity.run().getStatus(), identity.task().getStatus(), event.getTaskId(),
                    identity.run().getMediaTaskId(), identity.step().getStatus())) return;
            if (!activeByTask.containsKey(event.getTaskId()))
            {
                Stream stream = open(identity.session().getId(), identity.run().getId(),
                        identity.step().getId(), identity.step().getStepKind());
                stream.ownerUserId = event.getUserId();
                stream.nativeTextBoundary = hasNativeBindings(identity.task());
                stream.allowReasoning = Boolean.TRUE.equals(identity.session().getReasoningEnabled())
                        && Boolean.TRUE.equals(identity.session().getShowReasoning());
                stream.taskPrepared(event.getTaskId());
            }
            taskChanged(identity.session().getId(), identity.run().getId(), identity.step().getId(),
                    identity.step().getStepKind(), event.getTaskId());
        }
        else if ("REASONING".equals(event.getPhase()))
        {
            Stream stream = activeByTask.get(event.getTaskId());
            if (stream != null && Objects.equals(event.getUserId(), stream.ownerUserId)
                    && (isPublicSummary(event) || "PROVIDER_REASONING".equals(event.getReasoningKind()))) {
                stream.reasoningDelta(event.getContent(), event.getReasoningKind());
            }
        }
        else if ("TEXT".equals(event.getPhase()))
        {
            Stream stream = activeByTask.get(event.getTaskId());
            if (stream != null && Objects.equals(event.getUserId(), stream.ownerUserId)) stream.textDelta(event.getContent());
        }
    }

    static boolean acceptsStartedTask(boolean stopRequested, String runStatus, String taskStatus,
                                      Long eventTaskId, Long activeTaskId, String stepStatus)
    {
        return !stopRequested && "RUNNING".equals(runStatus)
                && !List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED").contains(taskStatus)
                && Objects.equals(eventTaskId, activeTaskId)
                && List.of("READY", "SUBMITTING", "INFLIGHT", "RECONCILING").contains(stepStatus);
    }

    static boolean isPublicSummary(MediaTextStreamEvent event)
    {
        return event != null && "REASONING".equals(event.getPhase())
                && "PUBLIC_SUMMARY".equals(event.getReasoningKind());
    }

    static boolean hasNativeBindings(AidMediaTask task)
    {
        if (task == null || StrUtil.isBlank(task.getRequestJson())) return false;
        try
        {
            var request = JSON.parseObject(task.getRequestJson());
            return request != null && request.get("assistantNativeToolBindingsJson") instanceof String bindings
                    && StrUtil.isNotBlank(bindings);
        }
        catch (RuntimeException invalid) { return false; }
    }

    private void taskChanged(Long sessionId, Long runId, Long stepId, String stepKind, Long taskId)
    {
        AssistantRealtimeSignal signal = new AssistantRealtimeSignal();
        signal.setType("TASK_CHANGED");
        signal.setSessionId(sessionId);
        signal.setRunId(runId);
        signal.setStepId(stepId);
        signal.setOuterStepId(stepId);
        signal.setStepKind(stepKind);
        signal.setActivityKind("AGENT_RESPONDING");
        signal.setSafeSummary("正在整理回复");
        signal.setMediaTaskId(taskId);
        signal.setCreateTime(new Date());
        eventHub.publish(signal);
    }

    @EventListener
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event)
    {
        if (event == null || event.getTaskId() == null)
        {
            return;
        }
        Stream stream = activeByTask.get(event.getTaskId());
        try
        {
            AidMediaTask task = mediaTasks.selectById(event.getTaskId());
            if (task == null || !Objects.equals(event.getUserId(), task.getUserId())
                    || !List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED").contains(task.getStatus())) return;
            String status = task != null && "SUCCEEDED".equals(task.getStatus()) ? "COMPLETED"
                    : task != null && List.of("CANCELLED", "CANCELED").contains(task.getStatus())
                    ? "STOPPED" : "FAILED";
            if (stream != null) stream.end(status);
            var identity = identities.resolve(event.getTaskId(), event.getUserId());
            if (identity != null) taskChanged(identity.session().getId(), identity.run().getId(),
                    identity.step().getId(), identity.step().getStepKind(), event.getTaskId());
        }
        catch (RuntimeException ignored)
        {
            // Completion listeners must not interfere with the media task's committed terminal state.
            // A read failure cannot invent a failed provider outcome. Durable recovery will retry.
        }
    }

    @PreDestroy
    void shutdown()
    {
        activeByTask.clear();
        flushExecutor.shutdownNow();
    }

    public final class Stream
    {
        private final Long sessionId;
        private final Long runId;
        private final Long stepId;
        private final String stepKind;
        private final StringBuilder pendingText = new StringBuilder();
        private final NativeAssistantVisibleTextPolicy textPolicy = new NativeAssistantVisibleTextPolicy();
        private final StringBuilder pendingReasoning = new StringBuilder();
        private ScheduledFuture<?> scheduledFlush;
        private boolean firstText = true;
        private boolean firstReasoning = true;
        private boolean textSeen;
        private boolean reasoningSeen;
        private String reasoningKind = "PUBLIC_SUMMARY";
        private boolean allowReasoning;
        private boolean nativeTextBoundary;
        private boolean ended;
        private Long mediaTaskId;
        private Long ownerUserId;

        private Stream(Long sessionId, Long runId, Long stepId, String stepKind)
        {
            this.sessionId = sessionId;
            this.runId = runId;
            this.stepId = stepId;
            this.stepKind = stepKind;
        }

        public synchronized void taskPrepared(long taskId)
        {
            if (taskId > 0)
            {
                mediaTaskId = taskId;
                boolean overCapacity;
                synchronized (activeByTask)
                {
                    overCapacity = activeByTask.size() >= MAX_ACTIVE_TASK_STREAMS
                            && !activeByTask.containsKey(taskId);
                    if (!overCapacity)
                    {
                        activeByTask.putIfAbsent(taskId, this);
                    }
                }
                if (overCapacity)
                {
                    // Preserve existing stream lifecycles at capacity. This stream terminates
                    // before any provider delta can be forwarded.
                    detach();
                }
            }
        }

        public synchronized void detach()
        {
            ended = true;
            cancelScheduledLocked();
            pendingText.setLength(0);
            pendingReasoning.setLength(0);
            if (mediaTaskId != null)
            {
                activeByTask.remove(mediaTaskId, this);
            }
        }

        public synchronized void reasoningDelta(String content) { reasoningDelta(content, "PUBLIC_SUMMARY"); }

        public synchronized void reasoningDelta(String content, String kind)
        {
            if (!allowReasoning || !("PUBLIC_SUMMARY".equals(kind) || "PROVIDER_REASONING".equals(kind))) return;
            if (!reasoningKind.equals(kind)) flushLocked();
            reasoningKind = kind;
            appendDelta("REASONING", content);
        }

        public synchronized void textDelta(String content)
        {
            appendDelta("TEXT", nativeTextBoundary ? textPolicy.accept(content) : content);
        }

        private void appendDelta(String streamType, String content)
        {
            if (ended || StrUtil.isEmpty(content))
            {
                return;
            }
            StringBuilder pending = "TEXT".equals(streamType) ? pendingText : pendingReasoning;
            if ("TEXT".equals(streamType)) textSeen = true;
            else reasoningSeen = true;
            int offset = 0;
            while (offset < content.length())
            {
                int end = Math.min(content.length(), offset + MAX_CHUNK_CHARS);
                pending.append(content, offset, end);
                offset = end;
                if (pending.length() >= FLUSH_CHARS)
                {
                    flushChannelLocked(streamType, pending);
                }
            }
            if ((pendingText.length() > 0 || pendingReasoning.length() > 0) && scheduledFlush == null)
            {
                try
                {
                    scheduledFlush = flushExecutor.schedule(this::flushScheduled,
                            FLUSH_MILLIS, TimeUnit.MILLISECONDS);
                }
                catch (RuntimeException ignored)
                {
                    flushLocked();
                }
            }
        }

        public synchronized void end(String status)
        {
            if (ended)
            {
                return;
            }
            appendDelta("TEXT", textPolicy.finish());
            ended = true;
            cancelScheduledLocked();
            flushLocked();
            if (mediaTaskId != null)
            {
                activeByTask.remove(mediaTaskId, this);
            }
            if (textSeen) publishEnded("TEXT", status);
            if (allowReasoning || reasoningSeen) publishEnded("REASONING", status);
        }

        private synchronized void flushScheduled()
        {
            scheduledFlush = null;
            if (!ended)
            {
                flushLocked();
            }
        }

        private void flushLocked()
        {
            flushChannelLocked("TEXT", pendingText);
            flushChannelLocked("REASONING", pendingReasoning);
        }

        private void flushChannelLocked(String streamType, StringBuilder pending)
        {
            if (pending.length() == 0) return;
            String content = pending.toString();
            pending.setLength(0);
            boolean text = "TEXT".equals(streamType);
            AssistantRealtimeSignal signal = base(text ? "TEXT_DELTA" : "REASONING_DELTA",
                    text ? "text_delta" : "reasoning_delta");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactType", text ? "MAIN_TEXT" : "MAIN_REASONING");
            payload.put("content", content);
            payload.put("reset", text ? firstText : firstReasoning);
            if (!text) payload.put("reasoningKind", reasoningKind);
            signal.setPayloadJson(JSON.toJSONString(payload));
            eventHub.publish(signal);
            if (text) firstText = false;
            else firstReasoning = false;
        }

        private void publishEnded(String streamType, String status)
        {
            boolean text = "TEXT".equals(streamType);
            AssistantRealtimeSignal signal = base("STREAM_ENDED", "stream_ended");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactType", text ? "MAIN_TEXT" : "MAIN_REASONING");
            payload.put("streamType", streamType);
            String normalizedStatus = normalizeStatus(status);
            payload.put("status", normalizedStatus);
            // A stream ending closes transport output only. The durable step/task state is the
            // authority for completion, failure, and billing reconciliation.
            signal.setActivityKind(null);
            signal.setSafeSummary(null);
            signal.setPayloadJson(JSON.toJSONString(payload));
            eventHub.publish(signal);
        }

        private AssistantRealtimeSignal base(String type, String eventType)
        {
            AssistantRealtimeSignal signal = new AssistantRealtimeSignal();
            signal.setType(type);
            signal.setSessionId(sessionId);
            signal.setRunId(runId);
            signal.setStepId(stepId);
            signal.setOuterStepId(stepId);
            signal.setStepKind(stepKind);
            signal.setActivityKind("AGENT_RESPONDING");
            signal.setSafeSummary("正在整理回复");
            signal.setMediaTaskId(mediaTaskId);
            signal.setEventType(eventType);
            signal.setStage("MAIN_AGENT");
            signal.setCreateTime(new Date());
            return signal;
        }

        private void cancelScheduledLocked()
        {
            ScheduledFuture<?> current = scheduledFlush;
            scheduledFlush = null;
            if (current != null)
            {
                current.cancel(false);
            }
        }

        private String normalizeStatus(String status)
        {
            return switch (StrUtil.blankToDefault(status, "FAILED"))
            {
                case "COMPLETED", "STOPPED" -> status;
                default -> "FAILED";
            };
        }
    }
}
