package com.aid.canvas.runtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Compares persisted node content while excluding only presentation and derived task projections. */
final class CanvasAssistantPresentationMutation {
    private CanvasAssistantPresentationMutation() { }

    static boolean sameContent(String beforeJson, JSONObject after) {
        if (beforeJson == null || after == null) return false;
        try {
            JSONObject before = JSON.parseObject(beforeJson);
            return before != null && before.getString("nodeType") != null
                    && before.getString("nodeType").equals(after.getString("nodeType"))
                    && content(before).equals(content(after));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Only a resource proven to be this run's selected media result may be projected. */
    static boolean sameContentWithSelectedResource(String beforeJson, JSONObject after, Long resourceId) {
        if (beforeJson == null || after == null || resourceId == null || resourceId <= 0) return false;
        try {
            JSONObject before = JSON.parseObject(beforeJson);
            if (before == null || mediaType(before.getString("nodeType")) == null) return false;
            JSONObject normalized = JSON.parseObject(after.toJSONString());
            JSONObject beforeMetadata = before.getJSONObject("metadata");
            JSONObject beforeData = beforeMetadata == null ? null : beforeMetadata.getJSONObject("studioData");
            JSONObject afterMetadata = normalized.getJSONObject("metadata");
            JSONObject afterData = afterMetadata == null ? null : afterMetadata.getJSONObject("studioData");
            if (afterData == null || !String.valueOf(resourceId).equals(afterData.getString("canvasPrimaryResourceId"))
                    || !onlyProjectsSelectedResource(before.get("resourceIds"), normalized.get("resourceIds"), resourceId)
                    || !onlyProjectsSelectedResource(beforeData == null ? null : beforeData.get("canvasResourceIds"),
                            afterData.get("canvasResourceIds"), resourceId)) return false;
            restore(normalized, before, "resourceIds");
            restore(afterData, beforeData, "canvasResourceIds");
            restore(afterData, beforeData, "canvasPrimaryResourceId");
            return sameContent(beforeJson, normalized);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static String mediaType(String nodeType) {
        if ("IMAGE".equals(nodeType) || "VIDEO".equals(nodeType)) return nodeType;
        return "VOICE".equals(nodeType) || "AUDIO".equals(nodeType) || "MUSIC".equals(nodeType) ? "AUDIO" : null;
    }

    private static boolean onlyProjectsSelectedResource(Object before, Object after, Long resourceId) {
        List<String> previous = resourceIds(before), current = resourceIds(after);
        if (previous == null || current == null || !(after instanceof JSONArray)) return false;
        List<String> expected = new ArrayList<>(previous);
        String selected = String.valueOf(resourceId);
        if (!expected.contains(selected)) expected.add(selected);
        if (expected.equals(current)) return true;
        // The Web projection puts the selected result first. Preserve every other resource
        // and its relative order; this never permits arbitrary reordering or replacement.
        List<String> primaryFirst = new ArrayList<>(previous);
        primaryFirst.remove(selected);
        primaryFirst.add(0, selected);
        return primaryFirst.equals(current);
    }

    private static List<String> resourceIds(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof JSONArray values) || values.size() > 2000) return null;
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String || value instanceof Number)) return null;
            String id = String.valueOf(value);
            if (!id.matches("[1-9][0-9]{0,18}") || result.contains(id)) return null;
            result.add(id);
        }
        return result;
    }

    private static void restore(JSONObject target, JSONObject source, String key) {
        if (source != null && source.containsKey(key)) target.put(key, source.get(key));
        else target.remove(key);
    }

    private static JSONObject content(JSONObject stored) {
        JSONObject value = JSON.parseObject(stored.toJSONString());
        value.remove("position"); value.remove("size");
        JSONObject metadata = value.getJSONObject("metadata");
        if (metadata == null) return value;
        metadata.remove("hidden"); metadata.remove("draggable");
        JSONObject data = metadata.getJSONObject("studioData");
        if (data != null) {
            for (String key : new String[]{"displayWidth", "displayHeight", "modelName",
                    "canvasCurrentTaskId", "canvasCurrentResultId", "canvasLatestTaskStatus"}) data.remove(key);
            // Audio playback metadata is a projection of the independently verified selected result.
            // Business duration/voice/model options remain in the generation parameter contract.
            if ("AUDIO".equals(mediaType(value.getString("nodeType")))) {
                data.remove("canvasAudioSelectionVersion");
                data.remove("audioDurationSeconds");
                if ("audio".equals(data.getString("mediaKind"))) data.remove("mediaKind");
            }
            // Text is read/written as content. Its model is independently pinned by the runtime quote.
            if ("TEXT".equals(value.getString("nodeType"))) data.remove("model");
            if (Boolean.FALSE.equals(data.get("canvasTransient"))) data.remove("canvasTransient");
            JSONArray resources = data.getJSONArray("canvasResourceIds");
            if (resources != null && resources.isEmpty()) data.remove("canvasResourceIds");
            if (data.isEmpty()) metadata.remove("studioData");
        }
        if (metadata.isEmpty()) value.remove("metadata");
        return value;
    }
}
