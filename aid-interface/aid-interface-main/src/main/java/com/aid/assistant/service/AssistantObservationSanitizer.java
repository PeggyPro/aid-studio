package com.aid.assistant.service;

import com.aid.assistant.error.AssistantServiceException;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared data projection for untyped tool observations; never carries execution authority. */
public final class AssistantObservationSanitizer {
    private AssistantObservationSanitizer() { }
    public static final Pattern URL = Pattern.compile("(?i)(?:https?://|data:)[^\\s\\]\\[<>{}\"']+");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "url", "uri", "token", "secret", "password", "credential", "authorization", "cookie",
            "apikey", "api_key", "accesskey", "sign", "signature", "billing", "quote", "preparedpayload",
            "normalizedrequest", "requestjson", "metadata", "resourceref", "lease", "fence", "providerraw");

    public static boolean sensitive(String key) {
        String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9_]", "").toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
    }

    public static Object project(Object value, int depth) {
        if (value == null || depth > 8) return null;
        if (value instanceof String text) {
            String safe = URL.matcher(text).replaceAll("[redacted-url]");
            return safe.length() <= 50_000 ? safe : safe.substring(0, 50_000);
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof JSONObject object) {
            if (object.size() > 100) throw tooLarge();
            JSONObject result = new JSONObject();
            for (String key : object.keySet().stream().sorted().toList()) {
                if (sensitive(key)) continue;
                Object child = project(object.get(key), depth + 1);
                if (child != null) result.put(key, child);
            }
            return result;
        }
        if (value instanceof JSONArray array) {
            if (array.size() > 100) throw tooLarge();
            JSONArray result = new JSONArray();
            for (Object child : array) {
                Object safe = project(child, depth + 1);
                if (safe != null) result.add(safe);
            }
            return result;
        }
        return null;
    }

    private static AssistantServiceException tooLarge() {
        return new AssistantServiceException(422, "ASSISTANT_RESULT_OBSERVATION_TOO_LARGE",
                "助手运行上下文不完整", null);
    }
}
