package com.aid.runtime.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded internal diagnostics. Logs trusted field paths and types, never argument values or schema text. */
public final class RuntimeInputDiagnostics {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RuntimeInputDiagnostics.class);
    private RuntimeInputDiagnostics() { }

    public static void record(String toolCode, String version, String digest, JSONObject schema, JSONObject arguments) {
        LOG.warn("Runtime input rejected: tool={} version={} contract={} issues={}",
                identity(toolCode), identity(version), identity(digest), JSON.toJSONString(inspect(schema, arguments)));
    }

    /** Stage and reason are program-defined codes, not exception messages or business input. */
    public static void recordStage(String operation, String version, String digest, String stage, String reason) {
        LOG.warn("Runtime contract rejected: operation={} version={} contract={} stage={} reason={}",
                identity(operation), identity(version), identity(digest), identity(stage), identity(reason));
    }

    public static List<Map<String, String>> inspect(JSONObject schema, Object arguments) {
        List<Map<String, String>> issues = new ArrayList<>();
        try { collect(schema, arguments, "", issues, 0); }
        catch (RuntimeException ignored) { /* Diagnostic failure must not replace the authoritative rejection. */ }
        if (issues.isEmpty()) issues.add(Map.of("path", "/", "reason", "SCHEMA_MISMATCH", "observedType", type(arguments)));
        return List.copyOf(issues);
    }

    private static void collect(JSONObject schema, Object value, String path, List<Map<String, String>> issues, int depth) {
        if (schema == null || issues.size() >= 4 || depth > 8) return;
        int before = issues.size();
        JSONObject properties = schema.getJSONObject("properties");
        if (value instanceof JSONObject object && properties != null) {
            JSONArray required = schema.getJSONArray("required");
            if (required != null) for (Object raw : required) {
                if (raw instanceof String field && properties.containsKey(field) && !object.containsKey(field))
                    add(issues, path + "/" + field(field), "REQUIRED_FIELD_MISSING", "ABSENT");
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))
                    && object.keySet().stream().anyMatch(key -> !properties.containsKey(key)))
                add(issues, path + "/<undeclared>", "UNDECLARED_FIELD", "PRESENT");
            for (String field : properties.keySet()) if (object.containsKey(field))
                collect(properties.getJSONObject(field), object.get(field), path + "/" + field(field), issues, depth + 1);
        }
        if (value instanceof JSONArray array && schema.get("items") instanceof JSONObject items)
            for (int i = 0; i < Math.min(16, array.size()); i++) collect(items, array.get(i), path + "/" + i, issues, depth + 1);
        if (before == issues.size() && !schema.containsKey("$ref")) {
            try {
                if (!JSONSchema.of(schema).validate(value).isSuccess())
                    add(issues, path.isEmpty() ? "/" : path, value == null ? "NULL_NOT_ALLOWED" : "TYPE_OR_CONSTRAINT_MISMATCH", type(value));
            } catch (RuntimeException ignored) { /* Local references are checked by the owning full validator. */ }
        }
    }

    private static void add(List<Map<String, String>> issues, String path, String reason, String type) {
        if (issues.size() < 4) issues.add(Map.of("path", path, "reason", reason, "observedType", type));
    }
    private static String field(String value) { return value.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") ? value : "<declared>"; }
    private static String identity(String value) { return value != null && value.matches("[A-Za-z0-9._:/-]{1,100}") ? value : "<unavailable>"; }
    private static String type(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Map<?, ?>) return "OBJECT";
        if (value instanceof List<?>) return "ARRAY";
        if (value instanceof String) return "STRING";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Number) return "NUMBER";
        return "OTHER";
    }
}
