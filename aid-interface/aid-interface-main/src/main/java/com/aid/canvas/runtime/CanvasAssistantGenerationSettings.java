package com.aid.canvas.runtime;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Editable node settings only; these never authorize a model, provider or billable submission. */
public final class CanvasAssistantGenerationSettings {
    private static final Set<String> KEYS = Set.of("aspectRatio", "quality", "count", "duration",
            "audioEnabled", "videoMode", "capabilityCode");
    private CanvasAssistantGenerationSettings() { }
    public static String model(JSONObject arguments) {
        Object value = arguments.get("modelCode");
        if (value == null) return null;
        if (!(value instanceof String model) || model.isBlank() || model.length() > 100
                || !model.matches("[A-Za-z0-9._:/-]+")) throw invalid();
        return model;
    }
    public static Map<String, Object> settings(JSONObject arguments) {
        Object raw = arguments.get("generationSettings");
        if (raw == null) return Map.of();
        if (!(raw instanceof JSONObject object) || !valid(object)) throw invalid();
        return new LinkedHashMap<>(object);
    }
    public static boolean valid(Map<String, ?> settings) {
        if (!KEYS.containsAll(settings.keySet())) return false;
        for (var entry : settings.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "count" -> { if (!(value instanceof Number n) || n.doubleValue() != n.intValue()
                        || n.intValue() < 1 || n.intValue() > 100) return false; }
                case "duration" -> { if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                        || n.doubleValue() <= 0 || n.doubleValue() > 600) return false; }
                case "audioEnabled" -> { if (!(value instanceof Boolean)) return false; }
                default -> { if (!(value instanceof String text) || text.isBlank() || text.length() > 100
                        || text.chars().anyMatch(Character::isISOControl)) return false; }
            }
        }
        return true;
    }
    private static ServiceException invalid() {
        return new ServiceException("画布生成设置无效").setDetailMessage("CANVAS_MEDIA_NODE_INVALID");
    }
}
