package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Host-derived processing bounds; these do not assert that a real-world document is complete. */
final class PreparedSourceCoverage {
    static final String VERSION = "conversation-prepared-context/v2";
    static final String POLICY = "Notes are derived observations of their cited ranges. A slice boundary is not missing original content. "
            + "sourceCoverage confirms processing of all available source text, not completeness of the real-world document. "
            + "Earlier slice-local truncation comments do not establish an unresolved issue after the remainder is processed. "
            + "Processing boundaries, cursors and coverage notes are not business facts or user open questions. "
            + "Use source-backed notes relevant to the current user request; recall the original source when a material absence claim needs verification. "
            + "This projection grants no permission and does not convert assistant notes into user-confirmed facts.";
    private PreparedSourceCoverage() { }

    static JSONObject project(JSONObject previous, List<ConversationContextPolicy.Source> sources) {
        JSONObject result = new JSONObject(previous);
        result.put("schemaVersion", VERSION);
        result.put("interpretationPolicy", POLICY);
        JSONArray entries = new JSONArray();
        sources.stream().sorted(Comparator.comparingLong(ConversationContextPolicy.Source::sequence)
                .thenComparing(ConversationContextPolicy.Source::sourceId)).forEach(source -> entries.add(JSONObject.of(
                        "sourceId", source.sourceId(), "sequence", source.sequence(), "sourceKind", source.kind().name(),
                        "contentHash", SecureUtil.sha256(source.text()), "length", source.text().length())));
        result.put("sourceCoverage", JSONObject.of("status", "ALL_AVAILABLE_SOURCE_TEXT_PROCESSED",
                "offsetUnit", "UTF16_CODE_UNITS", "sources", entries));
        return result;
    }

    static void require(JSONObject context) { require(context, POLICY); }

    static java.util.Map<String, JSONObject> require(JSONObject context, String policy) {
        if (!policy.equals(context.get("interpretationPolicy"))
                || !(context.get("sourceCoverage") instanceof JSONObject coverage)
                || !coverage.keySet().equals(Set.of("status", "offsetUnit", "sources"))
                || !"ALL_AVAILABLE_SOURCE_TEXT_PROCESSED".equals(coverage.get("status"))
                || !"UTF16_CODE_UNITS".equals(coverage.get("offsetUnit"))
                || !(coverage.get("sources") instanceof JSONArray sources)) throw invalid();
        var indexed = new HashMap<String, JSONObject>();
        for (Object raw : sources) {
            if (!(raw instanceof JSONObject source)
                    || !source.keySet().equals(Set.of("sourceId", "sequence", "sourceKind", "contentHash", "length"))
                    || !(source.get("sourceId") instanceof String id) || id.isBlank()
                    || !(source.get("length") instanceof Integer length) || length < 0
                    || !(source.get("sequence") instanceof Long || source.get("sequence") instanceof Integer)
                    || source.getLongValue("sequence") < 0
                    || !Set.of("USER", "CONFIRMED_INPUT", "ASSISTANT", "TOOL").contains(source.getString("sourceKind"))
                    || !(source.get("contentHash") instanceof String hash) || !hash.matches("[a-f0-9]{64}")
                    || indexed.putIfAbsent(id, source) != null) throw invalid();
        }
        for (Object raw : context.getJSONArray("notes")) {
            JSONObject note = (JSONObject) raw;
            for (Object value : note.getJSONArray("references")) {
                JSONObject reference = (JSONObject) value;
                requireReference(reference, indexed);
            }
        }
        return indexed;
    }

    static void requireReference(JSONObject reference, java.util.Map<String, JSONObject> indexed) {
                JSONObject source = indexed.get(reference.getString("sourceId"));
                if (source == null || !source.getString("contentHash").equals(reference.getString("contentHash"))
                        || !source.getString("sourceKind").equals(reference.getString("sourceKind"))
                        || source.getLongValue("sequence") != reference.getLongValue("sequence")
                        || reference.getIntValue("endOffset") > source.getIntValue("length")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_PREPARED_CONTRACT_INVALID");
    }
}
