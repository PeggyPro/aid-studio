package com.aid.context;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Objects;

/** Checks cached contracts against a freshly authorized, fixed-version catalog; never authorizes execution. */
public final class ContextContractAccessPolicy {
    private static final List<String> CONTRACT_FIELDS = List.of(
            "inputSchema", "outputSchema", "outputReferencePolicy", "outputReferenceKind",
            "sideEffectLevel", "billable", "executionOptions", "outputKind", "executorType");

    private ContextContractAccessPolicy() { }

    public static void requireCurrent(JSONObject document, JSONObject currentCatalog) {
        if (document == null || currentCatalog == null
                || !ContextContractProjection.VERSION.equals(document.getString("schemaVersion"))
                || !(document.get("entry") instanceof JSONObject original)) throw invalid();
        String kind = document.getString("kind");
        List<String> identity;
        String field;
        if ("TOOL".equals(kind)) {
            identity = List.of("toolCode", "contractVersion", "schemaDigest");
            field = "toolCatalog";
        } else if ("SKILL".equals(kind)) {
            identity = List.of("skillCode", "skillVersionId", "packageDigest");
            field = "skillCatalog";
        } else throw invalid();
        for (String key : identity)
            if (!(original.get(key) instanceof String value) || value.isBlank()) throw invalid();
        if (!(currentCatalog.get(field) instanceof JSONArray entries)) throw invalid();
        JSONObject matching = null;
        for (Object raw : entries) {
            if (!(raw instanceof JSONObject entry)) throw invalid();
            if (identity.stream().allMatch(key -> Objects.equals(original.get(key), entry.get(key)))) {
                if (matching != null) throw invalid();
                matching = entry;
            }
        }
        if (matching == null) throw invalid();
        for (String key : CONTRACT_FIELDS) {
            if (original.containsKey(key) != matching.containsKey(key)
                    || !ContextContractProjection.canonical(original.get(key))
                    .equals(ContextContractProjection.canonical(matching.get(key)))) throw invalid();
        }
        // availableNow may change with targets. Catalog membership permits contract discovery only;
        // the normal preparation, target, version and billing gates still decide actual execution.
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_CONTRACT_ACCESS_CHANGED");
    }
}
