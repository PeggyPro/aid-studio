package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Bounded discovery metadata. Original result text remains in its authoritative source. */
public final class SkillResultTextReferenceProjection {
    private SkillResultTextReferenceProjection() { }

    public static JSONObject describe(Object result) {
        JSONArray fields = new JSONArray();
        boolean[] truncated = { false };
        visit(result, "", 0, fields, truncated);
        return JSONObject.of("textFields", fields, "textFieldsTruncated", truncated[0]);
    }

    private static void visit(Object value, String pointer, int depth, JSONArray fields, boolean[] truncated) {
        if (fields.size() >= 64 || depth > 32 || pointer.length() > 1024) {
            truncated[0] = true; return;
        }
        if (value instanceof String text) {
            int end = Math.min(text.length(), 160);
            if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            fields.add(JSONObject.of("resultPointer", pointer, "charCount", text.length(),
                    "textDigest", SecureUtil.sha256(text), "saveable", !text.isBlank() && text.length() <= 65_536,
                    "preview", text.substring(0, end), "previewTruncated", end < text.length()));
        } else if (value instanceof JSONObject object) {
            for (String key : object.keySet()) {
                visit(object.get(key), pointer + "/" + key.replace("~", "~0").replace("/", "~1"), depth + 1, fields, truncated);
                if (fields.size() >= 64) { truncated[0] = true; break; }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.size(); i++) {
                visit(array.get(i), pointer + "/" + i, depth + 1, fields, truncated);
                if (fields.size() >= 64) { truncated[0] = true; break; }
            }
        }
    }
}
