package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;
import java.util.Set;

/** A reply cannot make progress when its frozen iteration offered no executable action. */
public final class NativeAssistantReplyContinuationPolicy
{
    private NativeAssistantReplyContinuationPolicy() { }

    /** CHAT is a reply, not an instruction to buy another automatic iteration. */
    public static boolean mustPauseUnfinishedReply(String requestJson, String approvalScopeJson,
                                                  Long runId, Long planRevision)
    {
        JSONObject request = JSON.parseObject(requestJson);
        return com.aid.assistant.service.AssistantAutomaticTextExecution.enabled(request)
                || hasNoExecutableAction(approvalScopeJson, runId, planRevision);
    }

    public static boolean hasNoExecutableAction(String approvalScopeJson, Long runId, Long planRevision)
    {
        try
        {
            JSONObject scope = JSON.parseObject(approvalScopeJson);
            JSONObject normalized = scope == null ? null : scope.getJSONObject("normalizedRequest");
            if (normalized == null) return false;
            JSONObject payload = JSON.parseObject(normalized.getString("payloadJson"));
            JSONObject bindings = payload == null ? null
                    : JSON.parseObject(payload.getString("assistantNativeToolBindingsJson"));
            if (bindings == null || !Set.of(NativeAssistantBindingPolicy.V8, NativeAssistantBindingPolicy.V9, NativeAssistantBindingPolicy.V10, NativeAssistantBindingPolicy.V11)
                    .contains(bindings.getString("schemaVersion"))
                    || !NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(bindings.getString("protocolVersion"))
                    || !Objects.equals(runId, bindings.getLong("runId"))
                    || !Objects.equals(planRevision, bindings.getLong("observedPlanRevision"))) return false;
            JSONArray tools = bindings.getJSONArray("tools");
            if (tools == null || tools.isEmpty()) return false;
            boolean replyOffered = false;
            for (Object raw : tools)
            {
                if (!(raw instanceof JSONObject tool)) return false;
                String action = tool.getString("action");
                if (action == null || !Set.of("CHAT", "ASK", "CHECKPOINT", "COMPLETE").contains(action))
                    return false;
                replyOffered |= "CHAT".equals(action);
            }
            return replyOffered;
        }
        catch (RuntimeException invalid)
        {
            return false;
        }
    }
}
