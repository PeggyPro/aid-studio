package com.aid.runtime.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Explicit authoring inputs for new calls; the archived v1 schema is never rewritten. */
public final class SkillDraftProposalInputContract {
    private SkillDraftProposalInputContract() { }

    public static String schema(String legacySchema) {
        JSONObject root = JSON.parseObject(legacySchema);
        root.getJSONArray("required").add("definition");
        JSONObject outputKind = JSONObject.of("type", "string", "enum", JSONArray.of("TEXT", "IMAGE"));
        JSONObject capability = JSONObject.of("type", "string", "enum", JSONArray.of("text_to_image", "image_to_image"));
        JSONObject text = JSONObject.of("properties", JSONObject.of("outputKind", JSONObject.of("enum", JSONArray.of("TEXT"))));
        JSONObject image = JSONObject.of("required", JSONArray.of("capabilityCode"),
                "properties", JSONObject.of("outputKind", JSONObject.of("enum", JSONArray.of("IMAGE"))));
        JSONObject definition = JSONObject.of("type", "object", "required", JSONArray.of("outputKind"),
                "description", "Explicit execution type; do not infer it from creative text.",
                "properties", JSONObject.of("outputKind", outputKind, "capabilityCode", capability),
                "anyOf", JSONArray.of(text, image));
        root.getJSONObject("properties").put("definition", definition);
        return root.toJSONString();
    }
}
