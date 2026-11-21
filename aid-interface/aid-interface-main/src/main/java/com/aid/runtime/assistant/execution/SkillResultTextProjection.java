package com.aid.runtime.assistant.execution;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Exact text selection from a business result; never reparses strings or normalizes their contents. */
public final class SkillResultTextProjection {
    private SkillResultTextProjection() { }

    public static String select(Object result, String pointer, int maxChars) {
        if (pointer == null || pointer.length() > 1024 || maxChars < 1
                || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw invalid("POINTER_INVALID");
        }
        Object selected = result;
        if (!pointer.isEmpty()) {
            String[] parts = pointer.substring(1).split("/", -1);
            if (parts.length > 32) throw invalid("POINTER_INVALID");
            for (String part : parts) {
                String key = decode(part);
                if (selected instanceof JSONObject object) {
                    if (!object.containsKey(key)) throw invalid("NOT_FOUND");
                    selected = object.get(key);
                } else if (selected instanceof JSONArray array) {
                    if (!key.matches("0|[1-9][0-9]{0,8}")) throw invalid("POINTER_INVALID");
                    int index = Integer.parseInt(key);
                    if (index >= array.size()) throw invalid("NOT_FOUND");
                    selected = array.get(index);
                } else {
                    throw invalid("NOT_FOUND");
                }
            }
        }
        if (!(selected instanceof String text)) throw invalid("NOT_STRING");
        if (text.length() > maxChars) throw invalid("TOO_LARGE");
        return text;
    }

    private static String decode(String token) {
        StringBuilder value = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '~') {
                value.append(c);
                continue;
            }
            if (++i >= token.length()) throw invalid("POINTER_INVALID");
            switch (token.charAt(i)) {
                case '0' -> value.append('~');
                case '1' -> value.append('/');
                default -> throw invalid("POINTER_INVALID");
            }
        }
        return value.toString();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("SKILL_RESULT_TEXT_" + reason);
    }
}
