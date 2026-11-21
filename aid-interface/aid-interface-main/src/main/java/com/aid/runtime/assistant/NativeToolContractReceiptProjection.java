package com.aid.runtime.assistant;

import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Provider-only catalog references; archived source chunks and business receipts stay unchanged. */
public final class NativeToolContractReceiptProjection {
    public static final String FIELD = "catalogReceiptProjectionVersion";
    public static final String VERSION = "native-tool-contract-references/v1";
    private NativeToolContractReceiptProjection() { }

    static void context(JSONObject request, JSONObject bindings) {
        if (!VERSION.equals(bindings.get(FIELD))) return;
        for (String field : Set.of("observations", "priorResults")) {
            JSONArray rows = request.getJSONArray(field);
            if (rows == null) continue;
            for (Object raw : rows) {
                if (!(raw instanceof JSONObject row) || !reader(row.getString("toolCode"))) continue;
                JSONObject observation = row.getJSONObject("observation");
                if (observation != null) row.put("observation", observation(observation, bindings));
            }
        }
    }

    static List<TextMessageItem> history(List<TextMessageItem> history, JSONObject bindings) {
        if (!VERSION.equals(bindings.get(FIELD))) return history;
        List<TextMessageItem> result = new ArrayList<>(history.size());
        for (TextMessageItem message : history) {
            if (!"tool".equals(message.getRole())) { result.add(message); continue; }
            JSONObject receipt = JSON.parseObject(message.getContent());
            JSONObject output = receipt != null && receipt.get("output") instanceof JSONObject object ? object : null;
            JSONObject projected = output == null ? null : observation(output, bindings);
            if (projected == null || projected.equals(output)) { result.add(message); continue; }
            TextMessageItem copy = JSON.parseObject(JSON.toJSONString(message), TextMessageItem.class);
            receipt.put("output", projected);
            copy.setContent(AssistantMainInputContract.serialize(receipt));
            result.add(copy);
        }
        return List.copyOf(result);
    }

    static JSONObject observation(JSONObject source, JSONObject bindings) {
        if (!"context-read-observation/v1".equals(source.getString("schemaVersion"))
                || !reader(source.getString("toolCode"))) return source;
        JSONObject page = source.getJSONObject("page");
        JSONArray items = page == null ? null : page.getJSONArray("items");
        if (items == null || !"CONTRACT".equals(page.getString("operation"))) return source;
        JSONArray retained = new JSONArray(), references = new JSONArray();
        for (Object raw : items) {
            if (!(raw instanceof JSONObject item) || !"CONTRACT".equals(item.getString("role"))
                    || Boolean.TRUE.equals(item.get("hasMoreText")) || item.getString("text") == null) {
                retained.add(raw); continue;
            }
            JSONObject contract;
            try { contract = JSON.parseObject(item.getString("text")); }
            catch (RuntimeException notContractJson) { retained.add(raw); continue; }
            JSONObject entry = contract == null ? null : contract.getJSONObject("entry");
            JSONArray names = new JSONArray();
            if (entry != null && "context-contract/v1".equals(contract.getString("schemaVersion"))
                    && "TOOL".equals(contract.getString("kind"))) {
                for (Object binding : bindings.getJSONArray("tools")) {
                    JSONObject value = (JSONObject) binding;
                    JSONObject payload = value.getJSONObject("payloadTemplate");
                    if ("TOOL".equals(value.getString("action")) && payload != null
                            && java.util.Objects.equals(entry.getString("toolCode"), payload.getString("toolCode"))
                            && java.util.Objects.equals(entry.getString("contractVersion"), payload.getString("contractVersion")))
                        names.add(value.getString("name"));
                }
            }
            if (names.isEmpty()) { retained.add(raw); continue; }
            // A new explicit reference view replaces the duplicate schema chunk, never its text/hash.
            JSONObject metadata = JSON.parseObject(entry.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            metadata.remove("inputSchema");
            references.add(JSONObject.of("entry", metadata, "functionNames", names,
                    "parametersSource", "CURRENT_NATIVE_FUNCTION_PARAMETERS", "sourceId", item.get("sourceId"),
                    "sourceContentHash", item.get("contentHash")));
        }
        if (references.isEmpty()) return source;
        JSONObject result = JSON.parseObject(source.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        result.getJSONObject("page").put("items", retained);
        result.put("nativeToolContractReferences", references);
        result.put("catalogReceiptProjection", VERSION);
        return result;
    }

    private static boolean reader(String code) {
        return "aid.context.read-v2".equals(code) || "aid.context.read".equals(code);
    }
}
