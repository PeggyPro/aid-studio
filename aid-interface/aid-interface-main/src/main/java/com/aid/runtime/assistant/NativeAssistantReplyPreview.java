package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Projects visible message text from trusted native controls, without altering their arguments. */
public final class NativeAssistantReplyPreview {
    private final Map<String, String> names = new HashMap<>();
    private final Map<Integer, String> callNames = new HashMap<>();
    private AssistantMessageContentPreview content;
    private Integer selected;
    private boolean blocked;

    public NativeAssistantReplyPreview(String bindingJson) {
        if (bindingJson == null || bindingJson.isBlank()) return;
        try {
            JSONObject binding = JSON.parseObject(bindingJson);
            if (!binding.getString("schemaVersion").startsWith("assistant-native-bindings/")) return;
            JSONArray tools = binding.getJSONArray("tools");
            if (tools == null) return;
            for (Object raw : tools) if (raw instanceof JSONObject tool
                    && Set.of("CHAT", "ASK", "CHECKPOINT", "COMPLETE").contains(tool.getString("action"))
                    && tool.getString("name") != null) names.put(tool.getString("name"), tool.getString("action"));
        } catch (RuntimeException invalid) { names.clear(); }
    }

    public String accept(String eventJson) {
        if (names.isEmpty() || blocked || eventJson == null || eventJson.isBlank() || "[DONE]".equals(eventJson)) return "";
        try {
            JSONObject event = JSON.parseObject(eventJson);
            StringBuilder visible = new StringBuilder();
            JSONArray choices = event.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                JSONArray calls = delta == null ? null : delta.getJSONArray("tool_calls");
                if (calls != null) for (Object raw : calls) if (raw instanceof JSONObject call) {
                    JSONObject function = call.getJSONObject("function");
                    if (function != null) visible.append(part(call.getInteger("index"), function.getString("name"), function.getString("arguments")));
                }
            } else {
                String type = event.getString("type");
                if ("response.output_item.added".equals(type)) {
                    JSONObject item = event.getJSONObject("item");
                    if (item != null && "function_call".equals(item.getString("type")))
                        visible.append(part(event.getInteger("output_index"), item.getString("name"), item.getString("arguments")));
                } else if ("response.function_call_arguments.delta".equals(type)) {
                    visible.append(part(event.getInteger("output_index"), null, event.getString("delta")));
                } else if ("content_block_start".equals(type)) {
                    JSONObject block = event.getJSONObject("content_block");
                    if (block != null && "tool_use".equals(block.getString("type"))) {
                        JSONObject input = block.getJSONObject("input");
                        visible.append(part(event.getInteger("index"), block.getString("name"),
                                input == null || input.isEmpty() ? null : input.toJSONString()));
                    }
                } else if ("content_block_delta".equals(type)) {
                    JSONObject delta = event.getJSONObject("delta");
                    if (delta != null && "input_json_delta".equals(delta.getString("type")))
                        visible.append(part(event.getInteger("index"), null, delta.getString("partial_json")));
                }
            }
            return visible.toString();
        } catch (RuntimeException malformed) { blocked = true; return ""; }
    }

    private String part(Integer index, String name, String arguments) {
        if (index == null || index < 0 || index >= 128) { blocked = true; return ""; }
        if (name != null && !name.isEmpty()) {
            String old = callNames.get(index);
            if (old != null && !old.equals(name)) { blocked = true; return ""; }
            callNames.put(index, name);
        }
        String action = names.get(callNames.get(index));
        if (action == null || arguments == null || arguments.isEmpty()) return "";
        if (selected != null && !selected.equals(index)) { blocked = true; return ""; }
        selected = index;
        if (content == null) content = new AssistantMessageContentPreview(
                "CHECKPOINT".equals(action) ? "summary" : "message");
        return content.accept(arguments);
    }
}
