package com.aid.context;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Wire validation only. Authority is established by the host before freezing an approval. */
public final class ParentToolObservationContract {
    private ParentToolObservationContract() { }
    public static final String VERSION = "assistant-parent-observations/v1";
    public static final int MAX_BYTES = 100_000;
    private static final Set<String> KEYS = Set.of("stepId", "toolCode", "contractVersion",
            "schemaDigest", "argumentsDigest", "outputDigest", "observation");

    public static void require(JSONObject value) {
        if (value == null || !value.keySet().equals(Set.of("schemaVersion", "sourceRunId",
                "planRevision", "observations")) || !VERSION.equals(value.getString("schemaVersion"))
                || !positiveId(value.get("sourceRunId"))
                || !(value.get("planRevision") instanceof Integer || value.get("planRevision") instanceof Long)
                || value.getLongValue("planRevision") < 1
                || !(value.get("observations") instanceof JSONArray rows) || rows.isEmpty() || rows.size() > 200
                || value.toJSONString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid parent tool observations");
        Set<String> ids = new HashSet<>();
        for (Object raw : rows) {
            if (!(raw instanceof JSONObject row) || !row.keySet().equals(KEYS)
                    || !positiveId(row.get("stepId")) || !ids.add(row.getString("stepId"))
                    || !(row.get("toolCode") instanceof String code) || code.isBlank() || code.length() > 100
                    || !(row.get("contractVersion") instanceof String version) || version.isBlank() || version.length() > 32
                    || !(row.get("observation") instanceof JSONObject || row.get("observation") instanceof JSONArray))
                throw new IllegalArgumentException("Invalid parent tool observation");
            for (String field : Set.of("schemaDigest", "argumentsDigest", "outputDigest"))
                if (!(row.get(field) instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid parent tool observation digest");
        }
    }

    private static boolean positiveId(Object value) {
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(text) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }
}
