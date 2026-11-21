package com.aid.context;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Frozen derived-memory wire contract; this validation grants no permission or source authenticity. */
public final class PreparedConversationContextContract {
    private PreparedConversationContextContract() { }
    public static void require(JSONObject context) {
        boolean onDemand = context != null && PreparedOnDemandContext.VERSION.equals(context.getString("schemaVersion"));
        boolean withCoverage = onDemand || context != null && PreparedSourceCoverage.VERSION.equals(context.getString("schemaVersion"));
        Set<String> fields = new java.util.HashSet<>(Set.of("schemaVersion", "semanticAuthority",
                "sourceSetDigest", "checkpointRevision", "notes"));
        if (withCoverage) fields.addAll(Set.of("sourceCoverage", "interpretationPolicy"));
        if (onDemand) fields.add("deferredNotes");
        if (context != null && context.containsKey("duplicateNotesOmitted")) fields.add("duplicateNotesOmitted");
        if (context == null || !context.keySet().equals(fields)
                || !(withCoverage || "conversation-prepared-context/v1".equals(context.getString("schemaVersion")))
                || !"DERIVED_ONLY".equals(context.getString("semanticAuthority"))
                || !hash(context.getString("sourceSetDigest")) || context.getLongValue("checkpointRevision") < 1
                || !(context.get("notes") instanceof JSONArray notes)
                || ContextContractProjection.canonical(context).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw invalid();
        if (context.containsKey("duplicateNotesOmitted")
                && (!(context.get("duplicateNotesOmitted") instanceof Integer count) || count < 0)) throw invalid();
        for (Object raw : notes) {
            if (!(raw instanceof JSONObject note) || !note.keySet().equals(Set.of("noteId", "kind", "text", "references"))
                    || !hash(note.getString("noteId")) || !(note.get("text") instanceof String text) || text.isBlank()
                    || !Set.of("USER_REQUIREMENT", "CONFIRMED_INPUT", "SOURCE_NOTE", "ASSISTANT_NOTE").contains(note.getString("kind"))
                    || !(note.get("references") instanceof JSONArray refs) || refs.isEmpty() || refs.size() > 16)
                throw invalid();
            ConversationMemoryPreparationPlanner.requireUnicode(text);
            requireReferences(note.getString("kind"), refs);
        }
        if (onDemand) PreparedOnDemandContext.require(context);
        else if (withCoverage) PreparedSourceCoverage.require(context);
    }
    static void requireReferences(String kind, JSONArray refs) {
        if (refs == null || refs.isEmpty() || refs.size() > 16) throw invalid();
            for (Object value : refs) {
                if (!(value instanceof JSONObject ref)
                        || !ref.keySet().equals(Set.of("sourceId", "sequence", "sourceKind", "contentHash", "startOffset", "endOffset"))
                        || !(ref.get("sourceId") instanceof String id) || id.isBlank()
                        || ref.getLong("sequence") == null || ref.getLongValue("sequence") < 0
                        || !Set.of("USER", "CONFIRMED_INPUT", "ASSISTANT").contains(ref.getString("sourceKind"))
                        || !hash(ref.getString("contentHash")) || ref.getInteger("startOffset") == null
                        || ref.getInteger("endOffset") == null || ref.getIntValue("startOffset") < 0
                        || ref.getIntValue("endOffset") <= ref.getIntValue("startOffset")) throw invalid();
                String source = ref.getString("sourceKind");
                if ("USER_REQUIREMENT".equals(kind) && !"USER".equals(source)
                        || "CONFIRMED_INPUT".equals(kind) && !"CONFIRMED_INPUT".equals(source)
                        || "ASSISTANT_NOTE".equals(kind) && !"ASSISTANT".equals(source)) throw invalid();
            }
    }

    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CONTEXT_PREPARED_CONTRACT_INVALID"); }
}
