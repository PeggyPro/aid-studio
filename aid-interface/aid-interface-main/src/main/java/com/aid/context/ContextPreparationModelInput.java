package com.aid.context;

import com.aid.runtime.tool.ContextPreparationRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashSet;

/** A strict model-facing subset of the unchanged tool contract. Locations remain host-owned. */
public final class ContextPreparationModelInput {
    private ContextPreparationModelInput() { }

    public static JSONObject schema(String reference, JSONArray slices) {
        if (reference == null || !reference.matches("[a-f0-9]{64}")
                || slices == null || slices.isEmpty() || slices.size() > 32)
            throw new IllegalArgumentException("CONTEXT_PREPARATION_MODEL_INPUT_INVALID");
        var kinds = new LinkedHashSet<String>();
        var ids = new LinkedHashSet<String>();
        for (Object raw : slices) {
            if (!(raw instanceof JSONObject slice) || slice.getJSONObject("source") == null)
                throw new IllegalArgumentException("CONTEXT_PREPARATION_MODEL_INPUT_INVALID");
            JSONObject source = slice.getJSONObject("source");
            String kind = switch (source.getString("kind")) {
                case "USER" -> "USER_REQUIREMENT";
                case "CONFIRMED_INPUT" -> "CONFIRMED_INPUT";
                default -> throw new IllegalArgumentException("CONTEXT_PREPARATION_MODEL_INPUT_INVALID");
            };
            kinds.add(kind);
            String id = source.getString("sourceId");
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("CONTEXT_PREPARATION_MODEL_INPUT_INVALID");
            ids.add(id);
        }
        JSONObject schema = JSON.parseObject(ContextPreparationRuntimeToolProvider.INPUT);
        JSONObject properties = schema.getJSONObject("properties");
        properties.getJSONObject("batchReference").put("enum", new JSONArray(java.util.List.of(reference)));
        JSONObject note = properties.getJSONObject("notes").getJSONObject("items").getJSONObject("properties");
        note.getJSONObject("kind").put("enum", new JSONArray(kinds));
        note.getJSONObject("text").put("description", "Copy the single citation quote exactly. Each distinct excerpt is a separate note; do not add commentary or inferred assistant conclusions.");
        JSONObject citations = note.getJSONObject("citations");
        citations.put("maxItems", 1);
        JSONObject citation = citations.getJSONObject("items").getJSONObject("properties");
        citation.remove("startOffset");
        citation.remove("endOffset");
        citation.getJSONObject("sourceId").put("enum", new JSONArray(ids));
        citation.getJSONObject("quote").put("description", "Copy an exact excerpt occurring once within this source slice; include adjacent original words if needed to distinguish repetition. The host resolves and validates its location. Do not count offsets.");
        return schema;
    }
}
