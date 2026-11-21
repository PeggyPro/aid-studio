package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashSet;

/** Shared selection semantics for new Native question contracts; does not change historical schemas. */
final class NativeAskSelectionContract {
    private NativeAskSelectionContract() { }

    static JSONObject schema() {
        JSONObject schema = NativeAskVisibleContextContract.schema();
        JSONObject question = schema.getJSONObject("$defs").getJSONObject("question");
        JSONObject single = branch("SINGLE");
        single.getJSONObject("properties").put("minSelections", JSONObject.of("type", "integer", "minimum", 0, "maximum", 1));
        single.getJSONObject("properties").put("maxSelections", JSONObject.of("enum", JSONArray.of(1),
                "description", "A single choice always has maximum 1, never null."));
        single.getJSONObject("properties").put("options", JSONObject.of("type", "array", "minItems", 1));
        single.put("anyOf", JSONArray.of(
                JSONObject.of("type", "object", "properties", JSONObject.of("allowFreeText", JSONObject.of("enum", JSONArray.of(false)))),
                JSONObject.of("type", "object", "properties", JSONObject.of("minSelections", JSONObject.of("enum", JSONArray.of(0))))));
        JSONObject multi = branch("MULTI");
        multi.getJSONObject("properties").put("minSelections", JSONObject.of("type", "integer", "minimum", 0));
        multi.getJSONObject("properties").put("maxSelections", JSONObject.of("type", "integer", "minimum", 1,
                "description", "Explicit maximum, at least minSelections and no larger than the number of business options; never null."));
        multi.getJSONObject("properties").put("options", JSONObject.of("type", "array", "minItems", 1));
        JSONObject text = branch("TEXT");
        text.getJSONObject("properties").put("allowFreeText", JSONObject.of("enum", JSONArray.of(true)));
        text.getJSONObject("properties").put("minSelections", JSONObject.of("enum", JSONArray.of(null, 0)));
        text.getJSONObject("properties").put("maxSelections", JSONObject.of("enum", JSONArray.of(null, 0)));
        text.getJSONObject("properties").put("options", JSONObject.of("type", "array", "maxItems", 0));
        question.put("anyOf", JSONArray.of(single, multi, text));
        question.put("description", "SINGLE: maxSelections=1, minSelections=0 when free alternatives are allowed. MULTI: explicit nonnegative minimum and positive maximum within option count. TEXT: free text enabled, empty options, bounds null or zero. Question IDs and option IDs within each question must be unique.");
        return schema;
    }

    private static JSONObject branch(String type) {
        return JSONObject.of("type", "object", "properties", JSONObject.of("type", JSONObject.of("enum", JSONArray.of(type))));
    }

    static boolean inputMatches(JSONObject arguments) {
        if (!NativeAskVisibleContextContract.inputMatches(arguments)) return false;
        var questionIds = new HashSet<String>();
        for (Object value : arguments.getJSONArray("questions")) {
            JSONObject question = (JSONObject) value;
            if (!questionIds.add(question.getString("questionId"))) return false;
            JSONArray options = question.getJSONArray("options");
            Integer min = question.getInteger("minSelections"), max = question.getInteger("maxSelections");
            String type = question.getString("type");
            if ("TEXT".equals(type)) {
                if (!Boolean.TRUE.equals(question.getBoolean("allowFreeText")) || !options.isEmpty()
                        || min != null && min != 0 || max != null && max != 0) return false;
            } else {
                if (options.isEmpty() || min == null || max == null || min < 0 || max < 1
                        || min > max || max > options.size()
                        || "SINGLE".equals(type) && (min > 1 || max != 1)) return false;
            }
            var optionIds = new HashSet<String>();
            for (Object option : options)
                if (!optionIds.add(((JSONObject) option).getString("optionId"))) return false;
        }
        return true;
    }
}
