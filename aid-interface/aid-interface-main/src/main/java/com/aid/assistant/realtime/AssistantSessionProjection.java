package com.aid.assistant.realtime;

import com.aid.assistant.vo.AssistantViews;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import cn.hutool.crypto.SecureUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

/** A bounded, authorized read model. Null fields explicitly clear previous client state. */
final class AssistantSessionProjection
{
    static final int MAX_FRAME_BYTES = 524_288;
    static final List<String> FIELDS = List.of("session", "messages", "hasEarlierMessages",
            "activeRun", "inputRequest", "inputHistory", "inputHistoryHasMore",
            "conversationArtifacts", "conversationArtifactsHasMore", "plan", "steps",
            "stepsHasMore", "tasks", "artifacts", "artifactsHasMore", "approval",
            "activities", "activitiesHasMore", "lastEventSeq");

    private AssistantSessionProjection() { }

    static boolean hasActiveMedia(JSONObject current)
    {
        var tasks = current.getJSONArray("tasks");
        if (tasks == null) return false;
        for (int index = 0; index < tasks.size(); index++)
        {
            JSONObject task = tasks.getJSONObject(index);
            if (task != null && task.getString("mediaType") != null && task.getString("status") != null
                    && List.of("IMAGE", "VIDEO", "AUDIO").contains(task.getString("mediaType"))
                    && List.of("PENDING", "QUEUED", "SUBMITTED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK")
                    .contains(task.getString("status"))) return true;
        }
        return false;
    }

    static JSONObject snapshot(AssistantViews.SessionDetail detail)
    {
        JSONObject serialized = JSON.parseObject(JSON.toJSONString(detail, "iso8601",
                JSONWriter.Feature.WriteNulls));
        JSONObject result = new JSONObject();
        for (String field : FIELDS)
        {
            result.put(field, serialized.get(field));
        }
        return result;
    }

    static JSONObject frame(String sessionId, String subscriptionId, long fromSeq,
                            Map<String, String> previous, JSONObject current)
    {
        JSONObject frame = new JSONObject();
        frame.put("type", "SESSION_PROJECTION");
        frame.put("projectionVersion", 1);
        frame.put("sessionId", sessionId);
        frame.put("subscriptionId", subscriptionId);
        frame.put("fromSeq", fromSeq);
        frame.put("toSeq", current.getLongValue("lastEventSeq"));
        frame.put("kind", previous == null ? "SNAPSHOT" : "DELTA");
        if (previous == null)
        {
            frame.put("snapshot", current);
        }
        else
        {
            JSONObject changes = new JSONObject();
            Map<String, String> currentFingerprint = fingerprint(current);
            for (String field : FIELDS)
            {
                if (!Objects.equals(previous.get(field), currentFingerprint.get(field)))
                {
                    changes.put(field, current.get(field));
                }
            }
            frame.put("changes", changes);
        }
        return frame;
    }

    static Map<String, String> fingerprint(JSONObject current)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : FIELDS)
        {
            result.put(field, SecureUtil.sha256(JSON.toJSONString(current.get(field), JSONWriter.Feature.WriteNulls)));
        }
        return Map.copyOf(result);
    }

    static String encode(JSONObject frame)
    {
        return JSON.toJSONString(frame, JSONWriter.Feature.WriteNulls);
    }

    static boolean fits(JSONObject frame)
    {
        return encode(frame).getBytes(StandardCharsets.UTF_8).length <= MAX_FRAME_BYTES;
    }
}
