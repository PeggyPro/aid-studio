package com.aid.assistant.realtime;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.vo.SkillInvocationVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Projects actual Assistant-owned Skill execution signals onto the assistant channel. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantSkillRealtimeBridge
{
    private static final String ASSISTANT_INVOKE_SOURCE = "ASSISTANT_RUNTIME";
    private static final int MAX_BINDINGS = 4096;
    private static final int MAX_ACTIVE_STREAMS_PER_RUN = 32;

    private final AidSkillRunMapper skillRunMapper;
    private final AidRuntimeRunMapper runtimeRunMapper;
    private final AidRuntimeRunStepMapper runtimeRunStepMapper;
    private final AidAssistantSessionMapper assistantSessionMapper;
    private final AssistantRealtimeEventHub eventHub;
    private final ConcurrentHashMap<Long, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<StreamIdentity, StreamState>> activeStreams =
            new ConcurrentHashMap<>();

    public void publish(Long skillRunId, SkillInvocationVO.EventView event)
    {
        if (skillRunId == null || event == null)
        {
            return;
        }
        Binding binding = binding(skillRunId);
        if (binding == null)
        {
            return;
        }
        JSONObject payload = parsePayload(event.getPayloadJson());
        AssistantRealtimeSignal signal = signal(binding, skillRunId, event, payload);
        if (signal != null && "REASONING_DELTA".equals(signal.getType())
                && !binding.showReasoning())
        {
            signal = null;
        }
        if (signal != null && "STREAM_ENDED".equals(signal.getType())
                && payload != null && "REASONING".equals(payload.getString("streamType"))
                && !binding.showReasoning())
        {
            signal = null;
        }
        if (signal != null && ("TEXT_DELTA".equals(signal.getType())
                || "REASONING_DELTA".equals(signal.getType())))
        {
            if (!trackStream(skillRunId, signal, payload))
            {
                signal = null;
            }
        }
        else if (signal != null && "STREAM_ENDED".equals(signal.getType()))
        {
            removeStream(skillRunId, signal, payload);
        }
        else if ("terminal".equals(event.getEventType()))
        {
            endTrackedStreams(binding, skillRunId, event, payload);
        }
        if (signal != null)
        {
            eventHub.publish(signal);
        }
        if ("terminal".equals(event.getEventType()))
        {
            bindings.remove(skillRunId);
            activeStreams.remove(skillRunId);
        }
    }

    private Binding binding(Long skillRunId)
    {
        Binding cached = bindings.get(skillRunId);
        if (cached != null)
        {
            return cached;
        }
        AidSkillRun skillRun = skillRunMapper.selectById(skillRunId);
        if (skillRun == null || !ASSISTANT_INVOKE_SOURCE.equals(skillRun.getInvokeSource())
                || StrUtil.isBlank(skillRun.getInputJson()))
        {
            return null;
        }
        try
        {
            JSONObject input = JSON.parseObject(skillRun.getInputJson());
            AssistantSkillBudgetEnvelope envelope = input == null ? null
                    : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
            if (envelope == null || envelope.getAssistantRuntimeRunId() == null
                    || envelope.getAssistantParentStepId() == null)
            {
                return null;
            }
            AidRuntimeRun outer = runtimeRunMapper.selectById(envelope.getAssistantRuntimeRunId());
            if (outer == null || outer.getSessionId() == null
                    || !Objects.equals(outer.getUserId(), skillRun.getUserId())
                    || !Objects.equals(envelope.getPayerUserId(), skillRun.getUserId()))
            {
                return null;
            }
            AidRuntimeRunStep outerStep = runtimeRunStepMapper.selectById(
                    envelope.getAssistantParentStepId());
            if (outerStep == null || !"0".equals(outerStep.getDelFlag())
                    || !Objects.equals(outerStep.getRunId(), outer.getId())
                    || !Objects.equals(outerStep.getChildRunId(), skillRunId)
                    || !"ASSISTANT_SKILL".equals(outerStep.getOperationKind()))
            {
                return null;
            }
            AidAssistantSession session = assistantSessionMapper.selectById(outer.getSessionId());
            if (session == null || !"0".equals(session.getDelFlag())
                    || !"ASSISTANT".equals(session.getSessionType())
                    || !Objects.equals(session.getUserId(), outer.getUserId()))
            {
                return null;
            }
            Binding resolved = new Binding(outer.getSessionId(), outer.getId(), outerStep.getId(),
                    outerStep.getStepKind(),
                    showReasoning(session) && Boolean.TRUE.equals(skillRun.getShowReasoning()));
            synchronized (bindings)
            {
                Binding concurrent = bindings.get(skillRunId);
                if (concurrent != null)
                {
                    return concurrent;
                }
                if (bindings.size() >= MAX_BINDINGS)
                {
                    // Do not evict an active binding: its already delivered stream must retain a
                    // terminal marker. A new over-capacity run simply has no realtime projection.
                    return null;
                }
                bindings.put(skillRunId, resolved);
                return resolved;
            }
        }
        catch (RuntimeException error)
        {
            log.warn("助手Skill实时绑定失败, skillRunId={}, errorType={}", skillRunId,
                    error.getClass().getSimpleName());
            return null;
        }
    }

    private AssistantRealtimeSignal signal(Binding binding, Long skillRunId,
                                            SkillInvocationVO.EventView event, JSONObject payload)
    {
        AssistantRealtimeSignal signal = new AssistantRealtimeSignal();
        signal.setSessionId(binding.sessionId());
        signal.setRunId(binding.runtimeRunId());
        signal.setOuterStepId(binding.outerStepId());
        signal.setInnerStepId(event.getStepId());
        signal.setSkillRunId(skillRunId);
        signal.setStepKind(binding.stepKind());
        signal.setSourceEventSeq(event.getSeq());
        signal.setEventType(event.getEventType());
        signal.setStage(event.getStage());
        signal.setStepId(event.getStepId());
        signal.setMediaTaskId(event.getMediaTaskId());
        signal.setCreateTime(event.getCreateTime());
        signal.setPayloadJson(event.getPayloadJson());
        if ("output_delta".equals(event.getEventType()))
        {
            signal.setType("TEXT_DELTA");
        }
        else if ("reasoning_delta".equals(event.getEventType()))
        {
            if (payload == null || !("PUBLIC_SUMMARY".equals(payload.getString("reasoningKind")) || "PROVIDER_REASONING".equals(payload.getString("reasoningKind")))) {
                return null;
            }
            signal.setType("REASONING_DELTA");
        }
        else if ("reconnect_required".equals(event.getEventType()))
        {
            signal.setType("SNAPSHOT_REQUIRED");
        }
        else if ("stream_ended".equals(event.getEventType()))
        {
            signal.setType("STREAM_ENDED");
        }
        else if (event.getEventType() != null && List.of("stage", "task_linked", "artifact", "progress", "terminal")
                .contains(event.getEventType()))
        {
            signal.setType("EXECUTION_PROGRESS");
            signal.setPayloadJson(projectProgressPayload(event.getEventType(), payload));
            signal.setStatus(publicStatus(payload));
        }
        else
        {
            // Future Skill events are private by default. Only explicit public projections may
            // cross the Assistant transport boundary.
            return null;
        }
        if (("TEXT_DELTA".equals(signal.getType()) || "REASONING_DELTA".equals(signal.getType()))
                && (payload == null || StrUtil.isBlank(payload.getString("content"))))
        {
            return null;
        }
        if (!"STREAM_ENDED".equals(signal.getType()) && !"SNAPSHOT_REQUIRED".equals(signal.getType()))
        {
            applyActivityPresentation(signal, event.getEventType(), event.getStage(), payload);
        }
        return signal;
    }

    private void applyActivityPresentation(AssistantRealtimeSignal signal, String eventType,
                                           String stage, JSONObject payload)
    {
        String status = publicStatus(payload);
        ActivityPresentation presentation = skillActivityPresentation(eventType, stage, status);
        if (presentation == null)
        {
            return;
        }
        signal.setActivityKind(presentation.kind());
        signal.setSafeSummary(presentation.summary());
        signal.setStatus(status);
    }

    private ActivityPresentation skillActivityPresentation(String eventType, String stage, String status)
    {
        if ("terminal".equals(eventType))
        {
            if (status != null && List.of("SUCCEEDED", "COMPLETED").contains(status))
            {
                return new ActivityPresentation("COMPLETED", "创作方法处理完成");
            }
            if (status != null && List.of("CANCELLED", "CANCELED", "STOPPED").contains(status))
            {
                return new ActivityPresentation("STOPPED", "创作方法处理已停止");
            }
            return status != null && List.of("FAILED", "FAILED_PRETASK").contains(status)
                    ? new ActivityPresentation("FAILED", "创作方法处理失败") : null;
        }
        if ("output_delta".equals(eventType) || "reasoning_delta".equals(eventType))
        {
            return new ActivityPresentation("TOOL_RUNNING", "正在应用创作方法");
        }
        if ("task_linked".equals(eventType))
        {
            return new ActivityPresentation("TOOL_RUNNING", "创作任务已受理，等待结果");
        }
        if ("artifact".equals(eventType))
        {
            return new ActivityPresentation("TOOL_RUNNING", "正在整理创作结果");
        }
        if ("UNKNOWN".equals(status) || "RECONCILING".equals(status))
        {
            return new ActivityPresentation("TOOL_RECONCILING", "正在核实创作任务与费用状态");
        }
        if ("CANCELING".equals(status) || "STOPPING".equals(status))
        {
            return new ActivityPresentation("TOOL_RECONCILING", "正在停止创作方法");
        }
        if ("SUBMITTING".equals(status))
        {
            return new ActivityPresentation("TOOL_SUBMITTING", "正在提交创作任务");
        }
        if ((stage != null && List.of("VALIDATING", "PLANNING", "PREPARING").contains(stage))
                || (status != null && List.of("CREATED", "PREPARING", "READY").contains(status)))
        {
            return new ActivityPresentation("TOOL_PREPARING", "正在准备创作方法");
        }
        if ("FINALIZING".equals(stage))
        {
            return new ActivityPresentation("TOOL_RUNNING", "正在整理创作结果");
        }
        return eventType != null && List.of("stage", "progress").contains(eventType)
                ? new ActivityPresentation("TOOL_RUNNING", "正在应用创作方法") : null;
    }

    private String projectProgressPayload(String eventType, JSONObject source)
    {
        JSONObject result = new JSONObject();
        if (source == null)
        {
            return result.toJSONString();
        }
        List<String> allowed = switch (eventType)
        {
            case "stage" -> List.of("stage", "mode", "stepKey", "operation");
            case "task_linked" -> List.of("stepExecutionId");
            case "artifact" -> List.of("artifactType", "mediaTaskId", "contentDigest");
            case "progress" -> List.of("stage", "message");
            case "terminal" -> List.of("status", "message", "responseMode");
            default -> List.of();
        };
        for (String field : allowed)
        {
            Object value = source.get(field);
            if (value instanceof String || value instanceof Number || value instanceof Boolean)
            {
                result.put(field, value);
            }
        }
        return result.toJSONString();
    }

    private boolean trackStream(Long skillRunId, AssistantRealtimeSignal signal, JSONObject payload)
    {
        if (payload == null)
        {
            return false;
        }
        ConcurrentHashMap<StreamIdentity, StreamState> streams;
        synchronized (activeStreams)
        {
            streams = activeStreams.get(skillRunId);
            if (streams == null)
            {
                if (activeStreams.size() >= MAX_BINDINGS)
                {
                    return false;
                }
                streams = new ConcurrentHashMap<>();
                activeStreams.put(skillRunId, streams);
            }
        }
        StreamIdentity identity = streamIdentity(signal, payload,
                "REASONING_DELTA".equals(signal.getType()) ? "REASONING" : "TEXT");
        synchronized (streams)
        {
            if (streams.size() >= MAX_ACTIVE_STREAMS_PER_RUN && !streams.containsKey(identity))
            {
                return false;
            }
            streams.put(identity, new StreamState(signal.getStage(), signal.getMediaTaskId()));
            return true;
        }
    }

    private void removeStream(Long skillRunId, AssistantRealtimeSignal signal, JSONObject payload)
    {
        ConcurrentHashMap<StreamIdentity, StreamState> streams = activeStreams.get(skillRunId);
        if (streams == null || payload == null)
        {
            return;
        }
        streams.remove(streamIdentity(signal, payload, payload.getString("streamType")));
        if (streams.isEmpty())
        {
            activeStreams.remove(skillRunId, streams);
        }
    }

    private void endTrackedStreams(Binding binding, Long skillRunId, SkillInvocationVO.EventView terminal,
                                   JSONObject terminalPayload)
    {
        ConcurrentHashMap<StreamIdentity, StreamState> streams = activeStreams.remove(skillRunId);
        if (streams == null || streams.isEmpty())
        {
            return;
        }
        String status = terminalStatus(terminalPayload == null ? null : terminalPayload.getString("status"));
        for (Map.Entry<StreamIdentity, StreamState> entry : streams.entrySet())
        {
            StreamIdentity identity = entry.getKey();
            StreamState state = entry.getValue();
            AssistantRealtimeSignal ended = new AssistantRealtimeSignal();
            ended.setType("STREAM_ENDED");
            ended.setSessionId(binding.sessionId());
            ended.setRunId(binding.runtimeRunId());
            ended.setOuterStepId(binding.outerStepId());
            ended.setInnerStepId(identity.stepId());
            ended.setSkillRunId(skillRunId);
            ended.setStepKind(binding.stepKind());
            ended.setSourceEventSeq(terminal.getSeq());
            ended.setEventType("stream_ended");
            ended.setStage(StrUtil.blankToDefault(state.stage(), terminal.getStage()));
            ended.setStepId(identity.stepId());
            ended.setMediaTaskId(state.mediaTaskId());
            ended.setCreateTime(terminal.getCreateTime());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactType", identity.artifactType());
            payload.put("streamType", identity.streamType());
            payload.put("status", status);
            ended.setPayloadJson(JSON.toJSONString(payload));
            eventHub.publish(ended);
        }
    }

    private StreamIdentity streamIdentity(AssistantRealtimeSignal signal, JSONObject payload,
                                          String streamType)
    {
        return new StreamIdentity(signal.getStepId(),
                StrUtil.blankToDefault(payload.getString("artifactType"), "TEXT"),
                StrUtil.blankToDefault(streamType, "TEXT"));
    }

    private String terminalStatus(String status)
    {
        if ("SUCCEEDED".equals(status))
        {
            return "COMPLETED";
        }
        if ("CANCELED".equals(status) || "CANCELLED".equals(status))
        {
            return "STOPPED";
        }
        return "FAILED";
    }

    private String publicStatus(JSONObject payload)
    {
        String status = payload == null ? null : payload.getString("status");
        return status != null && List.of("CREATED", "QUEUED", "PENDING", "PREPARING", "READY", "RUNNING",
                "SUBMITTING", "SUBMITTED", "ACCEPTED", "INFLIGHT", "PROCESSING", "RECONCILING",
                "UNKNOWN", "CANCELING", "STOPPING",
                "SUCCEEDED", "COMPLETED", "FAILED", "FAILED_PRETASK", "STOPPED", "CANCELLED",
                "CANCELED").contains(status) ? status : null;
    }

    private record ActivityPresentation(String kind, String summary) { }

    private boolean showReasoning(AidAssistantSession session)
    {
        try
        {
            JSONObject identity = StrUtil.isBlank(session.getBaseInputJson())
                    ? null : JSON.parseObject(session.getBaseInputJson());
            if (identity == null || !identity.containsKey("reasoningPreferencesVersion"))
            {
                return true;
            }
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
        return !Boolean.FALSE.equals(session.getReasoningEnabled())
                && !Boolean.FALSE.equals(session.getShowReasoning());
    }

    private JSONObject parsePayload(String payloadJson)
    {
        if (StrUtil.isBlank(payloadJson))
        {
            return null;
        }
        try
        {
            return JSON.parseObject(payloadJson);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private record Binding(Long sessionId, Long runtimeRunId, Long outerStepId, String stepKind,
                           boolean showReasoning) { }
    private record StreamIdentity(Long stepId, String artifactType, String streamType) { }
    private record StreamState(String stage, Long mediaTaskId) { }
}
