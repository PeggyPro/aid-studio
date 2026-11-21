package com.aid.previz.workspace;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;

/** A planning display, never authority for task completion, billing or project mutation. */
public final class PrevizProgressList {
    private PrevizProgressList() { }
    public static JSONObject validate(JSONObject input) {
        if (input == null || !input.keySet().equals(Set.of("todos")) || !(input.get("todos") instanceof JSONArray items))
            throw new IllegalArgumentException("进度清单必须包含完整todos数组");
        JSONArray result = new JSONArray();
        boolean active = false;
        for (Object raw : items) {
            if (!(raw instanceof JSONObject item) || !item.keySet().equals(Set.of("title", "status"))
                    || !(item.get("title") instanceof String title) || title.isBlank()
                    || !(item.get("status") instanceof String status) || !Set.of("pending", "in_progress", "done").contains(status))
                throw new IllegalArgumentException("进度项标题或状态无效");
            if ("in_progress".equals(status)) {
                if (active) status = "pending";
                else active = true;
            }
            result.add(JSONObject.of("title", title, "status", status));
        }
        return JSONObject.of("todos", result, "authoritativeCompletion", false);
    }
}
