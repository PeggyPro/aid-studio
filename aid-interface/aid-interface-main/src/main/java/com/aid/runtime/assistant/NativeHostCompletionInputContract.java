package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import java.util.*;

/** Host-owned criterion metadata; actual completion still requires the existing authoritative verifiers. */
final class NativeHostCompletionInputContract {
    static final String FIELD = "hostCompletionCriteria";
    private static final Set<String> TECHNICAL = Set.of("TARGET_WRITE", "TECHNICAL_ARCHIVE", "METHOD_RESULT");
    private static final Set<String> KINDS = Set.of("TARGET_WRITE", "TECHNICAL_ARCHIVE", "METHOD_RESULT", "CREATIVE_QUALITY", "CONTENT_REQUIREMENT");
    private NativeHostCompletionInputContract() { }

    static JSONObject bindings(JSONObject plan) {
        JSONObject result = new JSONObject();
        JSONArray criteria = plan == null ? null : plan.getJSONArray("completionCriteria");
        if (criteria == null || criteria.isEmpty() || criteria.size() > 100) throw invalid();
        for (Object raw : criteria) {
            if (!(raw instanceof JSONObject row)) throw invalid();
            String id = row.getString("criterionId"), kind = row.getString("criterionKind");
            if (id == null || id.isBlank() || id.length() > 64 || !KINDS.contains(Objects.toString(kind, ""))
                    || result.putIfAbsent(id, kind) != null) throw invalid();
        }
        return result;
    }

    static boolean validBindings(JSONObject bindings) {
        if (bindings == null || bindings.isEmpty() || bindings.size() > 100) return false;
        return bindings.entrySet().stream().allMatch(entry -> !entry.getKey().isBlank() && entry.getKey().length() <= 64
                && entry.getValue() instanceof String value && KINDS.contains(value));
    }

    static JSONObject schema() {
        JSONObject schema = NativeMethodResultInputContract.schema("COMPLETE");
        schema.getJSONObject("$defs").keySet().retainAll(Set.of("completionCriterion"));
        JSONObject criterion = schema.getJSONObject("$defs").getJSONObject("completionCriterion");
        criterion.getJSONObject("properties").getJSONObject("passed").put("type", "boolean");
        criterion.getJSONArray("required").remove("criterionKind");
        criterion.getJSONArray("required").remove("passed");
        criterion.put("description", "Reference the approved criterionId and actual evidenceRefs. Host binds criterionKind from the frozen plan. For technical archive, target write or method result omit passed: the server verifies the real receipt/archive. Creative quality and content requirement still require your explicit passed assessment and the actual review evidence. Supplying false cannot complete a criterion.");
        return schema;
    }

    static String digest() {
        return cn.hutool.crypto.SecureUtil.sha256(JSON.toJSONString(schema(), JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    /** Expose only current approved criterion IDs, without duplicating per-criterion schemas. */
    static JSONObject schema(JSONObject bindings) {
        if (!validBindings(bindings)) throw invalid();
        JSONObject schema = schema();
        schema.getJSONObject("$defs").getJSONObject("completionCriterion")
                .getJSONObject("properties").getJSONObject("criterionId")
                .put("enum", new JSONArray(bindings.keySet()));
        JSONObject results = schema.getJSONObject("properties").getJSONObject("completionCriteriaResults");
        results.put("minItems", bindings.size());
        results.put("maxItems", bindings.size());
        return schema;
    }

    static JSONObject canonicalArguments(JSONObject input, JSONObject bindings) {
        if (!validBindings(bindings) || input == null || !com.alibaba.fastjson2.schema.JSONSchema.of(schema()).validate(input).isSuccess()) throw invalid();
        JSONObject result = JSON.parseObject(input.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        Set<String> seen = new HashSet<>();
        for (Object raw : result.getJSONArray("completionCriteriaResults")) {
            JSONObject criterion = (JSONObject) raw;
            String id = criterion.getString("criterionId"), kind = bindings.getString(id);
            if (kind == null || !seen.add(id)
                    || criterion.containsKey("criterionKind") && !kind.equals(criterion.getString("criterionKind"))) throw invalid();
            criterion.put("criterionKind", kind);
            if (TECHNICAL.contains(kind) && !criterion.containsKey("passed")) criterion.put("passed", true);
            // This is only a completion candidate. Existing task/archive/write verifiers must
            // establish success under the current plan, ownership and receipt before any terminal transition.
            if (!Boolean.TRUE.equals(criterion.get("passed"))) throw invalid();
        }
        if (!seen.equals(bindings.keySet())) throw invalid();
        return NativeCompletionInputContract.canonicalArguments(result, true);
    }

    private static ServiceException invalid() { return new ServiceException("完成条件与当前批准计划或实际证据不一致"); }
}
