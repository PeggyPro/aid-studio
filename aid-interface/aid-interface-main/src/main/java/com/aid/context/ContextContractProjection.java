package com.aid.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Lossless catalog partitioning; storage and current access checks belong to the calling scope. */
public final class ContextContractProjection {
    public static final String VERSION = "context-contract/v1";
    public static final String REFERENCE = "contractRef";
    private static final List<String> HEAVY_FIELDS =
            List.of("inputSchema", "outputSchema", "outputReferencePolicy");

    private ContextContractProjection() { }

    public record Projection(JSONObject providerContext, Map<String, String> documents,
                             int originalBytes, int providerBytes) { }

    /** Call only when the consumer has an authorized contract reader; old consumers keep full data. */
    public static Projection project(JSONObject authoritative, Predicate<JSONObject> retainInline) {
        Objects.requireNonNull(authoritative);
        Objects.requireNonNull(retainInline);
        JSONObject visible = copy(authoritative);
        Map<String, String> documents = new LinkedHashMap<>();
        partition(visible, "toolCatalog", "TOOL", retainInline, documents);
        partition(visible, "skillCatalog", "SKILL", retainInline, documents);
        return new Projection(visible, Map.copyOf(documents), bytes(authoritative), bytes(visible));
    }

    private static void partition(JSONObject root, String field, String kind,
                                  Predicate<JSONObject> retainInline, Map<String, String> documents) {
        if (!root.containsKey(field)) return;
        if (!(root.get(field) instanceof JSONArray entries)) throw invalid();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof JSONObject entry) || entry.containsKey(REFERENCE))
                throw invalid();
            requireIdentity(entry, kind);
            JSONObject metadata = withoutHeavyFields(entry);
            if (canonical(metadata).equals(canonical(entry)) || retainInline.test(copy(entry))) continue;
            String serialized = canonical(document(kind, entry));
            String digest = digest(serialized);
            metadata.put(REFERENCE, digest);
            // Small contracts stay inline instead of adding a reference and an unnecessary read.
            if (bytes(metadata) >= bytes(entry)) continue;
            documents.putIfAbsent(digest, serialized);
            entries.set(index, metadata);
        }
    }

    /** Content identity only. It does not grant access to the corresponding contract. */
    public static String referenceFor(String kind, JSONObject entry) {
        return digest(canonical(document(kind, entry)));
    }

    private static JSONObject document(String kind, JSONObject entry) {
        if (!"TOOL".equals(kind) && !"SKILL".equals(kind)) throw invalid();
        requireIdentity(entry, kind);
        JSONObject result = new JSONObject();
        result.put("schemaVersion", VERSION);
        result.put("kind", kind);
        result.put("entry", copy(entry));
        return result;
    }

    /** Used for authoritative reconstruction and verification, never as permission to read a document. */
    public static JSONObject restore(JSONObject visible, Map<String, String> documents) {
        Objects.requireNonNull(visible);
        Objects.requireNonNull(documents);
        JSONObject restored = copy(visible);
        restoreCatalog(restored, "toolCatalog", "TOOL", documents);
        restoreCatalog(restored, "skillCatalog", "SKILL", documents);
        return restored;
    }

    private static void restoreCatalog(JSONObject root, String field, String kind,
                                       Map<String, String> documents) {
        if (!root.containsKey(field)) return;
        if (!(root.get(field) instanceof JSONArray entries)) throw invalid();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof JSONObject entry)) throw invalid();
            requireIdentity(entry, kind);
            if (!entry.containsKey(REFERENCE)) continue;
            if (!(entry.get(REFERENCE) instanceof String reference)
                    || !reference.matches("[a-f0-9]{64}")) throw invalid();
            String serialized = documents.get(reference);
            if (serialized == null) throw invalid();
            JSONObject document;
            try { document = JSON.parseObject(serialized); }
            catch (RuntimeException malformed) { throw invalid(); }
            if (document == null || !reference.equals(digest(canonical(document)))
                    || !VERSION.equals(document.getString("schemaVersion"))
                    || !kind.equals(document.getString("kind"))
                    || !(document.get("entry") instanceof JSONObject original)) throw invalid();
            requireIdentity(original, kind);
            JSONObject expected = withoutHeavyFields(original);
            expected.put(REFERENCE, reference);
            if (!canonical(expected).equals(canonical(entry))) throw invalid();
            entries.set(index, copy(original));
        }
    }

    private static JSONObject withoutHeavyFields(JSONObject entry) {
        JSONObject metadata = copy(entry);
        HEAVY_FIELDS.forEach(metadata::remove);
        if (metadata.get("executionOptions") instanceof JSONObject options) options.remove("modelChoices");
        return metadata;
    }

    private static void requireIdentity(JSONObject entry, String kind) {
        List<String> fields = "TOOL".equals(kind)
                ? List.of("toolCode", "contractVersion", "schemaDigest")
                : List.of("skillCode", "skillVersionId", "packageDigest");
        for (String field : fields)
            if (!(entry.get(field) instanceof String value) || value.isBlank()) throw invalid();
    }

    public static String canonical(Object value) {
        return JSON.toJSONString(ordered(value), JSONWriter.Feature.WriteNulls);
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) throw invalid();
                result.put(key, ordered(item.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(ordered(item));
            return result;
        }
        return value;
    }

    private static JSONObject copy(JSONObject value) { return JSON.parseObject(canonical(value)); }
    private static int bytes(JSONObject value) { return canonical(value).getBytes(StandardCharsets.UTF_8).length; }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_CONTRACT_REFERENCE_INVALID");
    }
}
