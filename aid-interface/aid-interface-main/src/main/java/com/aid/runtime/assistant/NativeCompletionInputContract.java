package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.LinkedHashSet;
import java.util.Set;

/** A completion declares evidence per criterion; canonical aggregate lists are derived, never invented. */
final class NativeCompletionInputContract {
    private NativeCompletionInputContract() { }

    static boolean applies(String version, String action) {
        return (NativeAssistantBindingPolicy.V6.equals(version) || NativeAssistantBindingPolicy.V7.equals(version) || (NativeAssistantBindingPolicy.V8.equals(version) || NativeAssistantBindingPolicy.methodResults(version)))
                && "COMPLETE".equals(action);
    }

    static JSONObject schema() {
        JSONObject schema = NativeMessageInputContract.schema("COMPLETE");
        schema.getJSONArray("required").remove("artifactRefs");
        JSONObject properties = schema.getJSONObject("properties");
        properties.getJSONObject("artifactRefs").put("description",
                "Optional compatibility aggregate. Normally omit: the server derives this from non-TARGET_WRITE criterion evidenceRefs. If supplied, it must match that exact set; no unrelated refs.");
        properties.getJSONObject("targetWriteEvidenceRefs").put("description",
                "Optional compatibility aggregate. Normally omit: the server derives this from TARGET_WRITE criterion evidenceRefs. If supplied, it must match that exact set.");
        JSONObject definitions = schema.getJSONObject("$defs");
        if (definitions != null && definitions.getJSONObject("completionCriterion") != null)
            definitions.getJSONObject("completionCriterion").getJSONObject("properties")
                    .getJSONObject("evidenceRefs").put("description",
                            "Declare actual evidence here once. TARGET_WRITE uses runtime-step:* receipts; other kinds use artifact:* refs. Server verifies ownership, current plan, successful write/archive and criterion coverage.");
        return schema;
    }

    static String digest() {
        return SecureUtil.sha256(JSON.toJSONString(schema(),
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject canonicalArguments(JSONObject arguments) {
        return canonicalArguments(arguments, false);
    }

    static JSONObject canonicalArguments(JSONObject arguments, boolean methodResults) {
        JSONObject result = NativeMessageInputContract.canonicalArguments(arguments);
        if (!(arguments.get("completionCriteriaResults") instanceof JSONArray criteria)
                || criteria.isEmpty() || criteria.size() > 100) throw invalid();
        Set<String> artifacts = new LinkedHashSet<>(), targets = new LinkedHashSet<>();
        for (Object item : criteria) {
            if (!(item instanceof JSONObject criterion) || !Boolean.TRUE.equals(criterion.get("passed")))
                throw invalid();
            Object kind = criterion.get("criterionKind");
            if (!(kind instanceof String) || !Set.of("TARGET_WRITE", "TECHNICAL_ARCHIVE",
                    "CREATIVE_QUALITY", "CONTENT_REQUIREMENT").contains(kind)
                    && !(methodResults && "METHOD_RESULT".equals(kind))) throw invalid();
            Set<String> values = references(criterion.get("evidenceRefs"), false);
            boolean target = "TARGET_WRITE".equals(kind);
            boolean method = "METHOD_RESULT".equals(kind);
            if (values.stream().anyMatch(ref -> target || method ? !ref.matches("runtime-step:[1-9][0-9]{0,18}")
                    : !ref.startsWith("artifact:"))) throw invalid();
            if (!method) (target ? targets : artifacts).addAll(values);
        }
        putAggregate(arguments, result, "artifactRefs", artifacts);
        putAggregate(arguments, result, "targetWriteEvidenceRefs", targets);
        return result;
    }

    private static void putAggregate(JSONObject original, JSONObject canonical, String field, Set<String> derived) {
        if (derived.size() > 100) throw invalid();
        if (original.containsKey(field) && !references(original.get(field), true).equals(derived)) throw invalid();
        canonical.put(field, new JSONArray(derived));
    }

    private static Set<String> references(Object raw, boolean emptyAllowed) {
        if (!(raw instanceof JSONArray array) || array.size() > 100 || (!emptyAllowed && array.isEmpty()))
            throw invalid();
        Set<String> refs = new LinkedHashSet<>();
        for (Object item : array) {
            if (!(item instanceof String ref) || ref.isBlank() || ref.length() > 100 || !refs.add(ref)) throw invalid();
        }
        return refs;
    }

    private static ServiceException invalid() {
        return new ServiceException("完成条件证据缺失、类型错误或汇总引用与条件不一致");
    }
}
