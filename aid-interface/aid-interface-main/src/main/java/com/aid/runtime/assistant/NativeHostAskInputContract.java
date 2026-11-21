package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.HashSet;
import java.util.Set;

/** V10 business questions compiled deterministically into the existing durable ASK contract. */
final class NativeHostAskInputContract {
    private NativeHostAskInputContract() { }

    static JSONObject schema() {
        JSONObject root = NativeAskVisibleContextContract.schema();
        root.getJSONObject("$defs").keySet().retainAll(Set.of("question", "option"));
        JSONObject question = root.getJSONObject("$defs").getJSONObject("question");
        question.remove("anyOf");
        question.put("required", JSONArray.of("type", "title", "required", "allowFreeText"));
        question.put("description", "Provide business questions. IDs and selection bounds may be omitted: the host assigns stable IDs; SINGLE permits at most one, TEXT has no selections, MULTI defaults to all available options as maximum. Supply explicit MULTI limits when the business requires them. Omitted options means an empty list, valid only for TEXT.");
        JSONObject option = root.getJSONObject("$defs").getJSONObject("option");
        option.put("required", JSONArray.of("label"));
        option.getJSONObject("properties").getJSONObject("exclusive").put("description",
                "Optional, defaults to false for an ordinary option. Set true explicitly for an option that must not coexist with other choices. The host never infers exclusivity from its label.");
        return root;
    }

    static JSONObject canonicalArguments(JSONObject input) {
        if (input == null || !JSONSchema.of(schema()).validate(input).isSuccess())
            throw invalid();
        JSONObject result = JSON.parseObject(input.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONArray questions = result.getJSONArray("questions");
        Set<String> questionIds = explicitIds(questions, "questionId");
        for (int i = 0; i < questions.size(); i++) {
            JSONObject q = questions.getJSONObject(i);
            assignId(q, "questionId", "question_" + (i + 1), questionIds);
            if (!q.containsKey("description")) q.put("description", null);
            if (!q.containsKey("options")) q.put("options", new JSONArray());
            JSONArray options = q.getJSONArray("options");
            Set<String> optionIds = explicitIds(options, "optionId");
            String type = q.getString("type");
            boolean free = q.getBooleanValue("allowFreeText");
            boolean required = q.getBooleanValue("required");
            int minimum = "TEXT".equals(type) || !required || free ? 0 : 1;
            int maximum = "TEXT".equals(type) ? 0 : "SINGLE".equals(type) ? 1 : options.size();
            if (!q.containsKey("minSelections")) q.put("minSelections", minimum);
            if (!q.containsKey("maxSelections")) q.put("maxSelections", maximum);
            for (int j = 0; j < options.size(); j++) {
                JSONObject option = options.getJSONObject(j);
                assignId(option, "optionId", "option_" + (j + 1), optionIds);
                if (!option.containsKey("description")) option.put("description", null);
                if (!option.containsKey("exclusive")) option.put("exclusive", false);
            }
        }
        // The same semantic validator checks explicit bounds, business contradictions and duplicates.
        // This is declared default construction, never a retry or a repair of an older archive.
        if (!NativeAskSelectionContract.inputMatches(result)) throw invalid();
        return NativeAskVisibleContextContract.canonicalArguments(result);
    }

    static JSONObject schema(String version) {
        JSONObject result = schema();
        if (!NativeAssistantBindingPolicy.V11.equals(version)) return result;
        JSONObject definitions = result.getJSONObject("$defs");
        for (String kind : Set.of("question", "option")) {
            definitions.getJSONObject(kind).getJSONObject("properties").put(kind + "Ref", JSONObject.of(
                    "type", "string", "pattern", "^[A-Za-z][A-Za-z0-9_-]{0,63}$",
                    "description", "Optional local business reference. The host maps it deterministically to a durable ID; question refs are unique in this request, option refs within their question. Existing explicit IDs remain unchanged. This does not create conditional display or grant approval."));
        }
        return result;
    }

    static JSONObject canonicalArguments(JSONObject input, String version) {
        if (!NativeAssistantBindingPolicy.V11.equals(version)) return canonicalArguments(input);
        if (input == null || !JSONSchema.of(schema(version)).validate(input).isSuccess()) throw invalid();
        JSONObject result = JSON.parseObject(input.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONArray questions = result.getJSONArray("questions");
        Set<String> questionNames = new HashSet<>();
        for (Object item : questions) {
            JSONObject question = (JSONObject) item;
            String local = question.getString("questionRef");
            if (local != null) {
                if (!questionNames.add(local)) throw invalid();
                if (!question.containsKey("questionId")) question.put("questionId", stableId("question", local));
                question.remove("questionRef");
            }
            JSONArray options = question.getJSONArray("options");
            if (options == null) continue;
            Set<String> optionNames = new HashSet<>();
            for (Object raw : options) {
                JSONObject option = (JSONObject) raw;
                String optionRef = option.getString("optionRef");
                if (optionRef == null) continue;
                if (!optionNames.add(optionRef)) throw invalid();
                // Option IDs are scoped by the durable question ID in the existing answer contract.
                if (!option.containsKey("optionId")) option.put("optionId", stableId("option", optionRef));
                option.remove("optionRef");
            }
        }
        return canonicalArguments(result);
    }

    private static String stableId(String kind, String local) {
        return kind + "_" + cn.hutool.crypto.SecureUtil.sha256(local).substring(0, 32);
    }

    static boolean inputMatches(JSONObject arguments, String version) {
        try { canonicalArguments(arguments, version); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    static boolean inputMatches(JSONObject arguments) {
        try { canonicalArguments(arguments); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private static Set<String> explicitIds(JSONArray values, String field) {
        Set<String> ids = new HashSet<>();
        for (Object raw : values) {
            JSONObject value = (JSONObject) raw;
            if (value.containsKey(field) && !ids.add(value.getString(field))) throw invalid();
        }
        return ids;
    }

    private static void assignId(JSONObject value, String field, String base, Set<String> used) {
        if (value.containsKey(field)) return;
        String id = base;
        for (int suffix = 1; !used.add(id); suffix++) id = base + "_" + suffix;
        value.put(field, id);
    }

    private static ServiceException invalid() {
        return new ServiceException("提问业务字段或选择约束无效");
    }
}
