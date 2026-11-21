package com.aid.runtime.assistant;

import com.aid.assistant.story.tool.StoryWorkspaceRuntimeToolProvider;
import com.aid.canvas.runtime.CanvasAssistantToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collection;

/** Keeps a confirmed Canvas screenplay delivery on its authorized execution step. */
public final class ScreenwriterCanvasDeliveryPolicy {
    private ScreenwriterCanvasDeliveryPolicy() { }

    static boolean applies(JSONObject request) {
        JSONObject session = request.getJSONObject("session");
        JSONObject run = request.getJSONObject("run");
        return session != null && run != null && "SCREENWRITER".equals(run.getString("moduleCode"))
                && session.getString("workspaceId") != null;
    }

    static String readyWrite(JSONArray bindings) {
        String ready = null;
        for (Object raw : bindings) {
            JSONObject binding = (JSONObject) raw;
            if ("SKILL".equals(binding.getString("action"))) return null;
            if (!"TOOL".equals(binding.getString("action"))) continue;
            JSONObject payload = binding.getJSONObject("payloadTemplate");
            if (!isWriter(binding) || payload.getIntValue("attemptNo") != 1 || ready != null) return null;
            ready = binding.getString("name");
        }
        return ready;
    }

    public static boolean isRequiredWrite(String module, String bindingsJson) {
        if (!"SCREENWRITER".equals(module) || bindingsJson == null) return false;
        JSONObject envelope = JSON.parseObject(bindingsJson);
        JSONArray bindings = envelope == null ? null : envelope.getJSONArray("tools");
        return bindings != null && bindings.size() == 1 && isWriter(bindings.getJSONObject(0));
    }

    static boolean requiresExplicitDecision(String module, Collection<JSONObject> bindings) {
        return "SCREENWRITER".equals(module) && bindings.size() == 1
                && isWriter(bindings.iterator().next());
    }

    private static boolean isWriter(JSONObject binding) {
        JSONObject payload = binding.getJSONObject("payloadTemplate");
        return "TOOL".equals(binding.getString("action")) && payload != null
                && writesScreenplay(payload.getString("toolCode"));
    }

    private static boolean writesScreenplay(String bindingCode) {
        return CanvasAssistantToolProvider.NODE_CREATE_TEXT.equals(bindingCode)
                || StoryWorkspaceRuntimeToolProvider.ARTIFACT_WRITE.equals(bindingCode);
    }

    static boolean includesWrite(JSONObject plan) {
        JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
        return steps != null && steps.stream().anyMatch(raw -> raw instanceof JSONObject step
                && writesScreenplay(step.getString("bindingCode")));
    }
}
