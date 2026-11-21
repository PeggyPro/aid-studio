package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded hints from an already trusted schema, never a replacement for its validator. */
public final class TrustedSchemaDiagnostics
{
    private static final int MAX_ISSUES = 8;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VISITS = 512;
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "boolean", "integer", "number", "null");

    private TrustedSchemaDiagnostics() { }

    public static JSONArray describe(JSONObject trustedSchema, Object arguments)
    {
        JSONArray issues = new JSONArray();
        if (trustedSchema != null)
            walk(trustedSchema, trustedSchema, arguments, "", 0, new int[] {0}, issues);
        return issues;
    }

    private static void walk(JSONObject root, JSONObject schema, Object value, String path,
                             int depth, int[] visits, JSONArray issues)
    {
        if (depth > MAX_DEPTH || ++visits[0] > MAX_VISITS || issues.size() >= MAX_ISSUES) return;
        Object reference = schema.get("$ref");
        if (reference != null)
        {
            JSONObject resolved = resolve(root, reference);
            if (resolved != null) walk(root, resolved, value, path, depth + 1, visits, issues);
            return;
        }
        // A partial branch can give misleading advice. Leave composition failures to the
        // authoritative validator rather than guessing which branch the caller intended.
        if (schema.containsKey("oneOf") || schema.containsKey("anyOf")
                || schema.containsKey("not") || schema.containsKey("if")) return;
        List<String> types = types(schema.get("type"));
        if (!types.isEmpty() && types.stream().noneMatch(type -> matches(type, value)))
        {
            add(issues, "JSON_TYPE_MISMATCH", path, String.join("|", types));
            return;
        }
        // A quoted sentinel is not JSON null. Only diagnose this when the declared
        // enum admits the null literal and explicitly excludes the string itself.
        // Free string fields may legitimately contain "null" and must not be coerced.
        if ("null".equals(value) && types.contains("null")
                && schema.get("enum") instanceof List<?> allowed
                && allowed.size() <= 32 && allowed.contains(null) && !allowed.contains("null"))
            add(issues, "QUOTED_NULL_NOT_ALLOWED", path, "JSON_NULL_LITERAL_OR_DECLARED_ENUM");
        if (value instanceof Map<?, ?> object)
        {
            Object required = schema.get("required");
            if (required instanceof List<?> names)
            {
                int inspected = 0;
                for (Object raw : names)
                {
                    if (++inspected > MAX_VISITS || issues.size() >= MAX_ISSUES) break;
                    if (raw instanceof String name && !object.containsKey(name))
                        add(issues, "REQUIRED_FIELD_MISSING", child(path, name), "present");
                }
            }
            Object properties = schema.get("properties");
            if (properties instanceof JSONObject fields)
            {
                for (String name : fields.keySet().stream().sorted().limit(MAX_VISITS).toList())
                {
                    if (visits[0] >= MAX_VISITS || issues.size() >= MAX_ISSUES) break;
                    String next = child(path, name);
                    if (next != null && object.containsKey(name)
                            && fields.get(name) instanceof JSONObject fieldSchema)
                        walk(root, fieldSchema, object.get(name), next, depth + 1, visits, issues);
                }
            }
        }
        else if (value instanceof List<?> values && schema.get("items") instanceof JSONObject itemSchema)
        {
            for (int i = 0; i < values.size() && visits[0] < MAX_VISITS
                    && issues.size() < MAX_ISSUES; i++)
                walk(root, itemSchema, values.get(i), child(path, String.valueOf(i)),
                        depth + 1, visits, issues);
        }
        if (schema.get("allOf") instanceof List<?> branches)
        {
            int inspected = 0;
            for (Object raw : branches)
            {
                if (++inspected > MAX_VISITS || visits[0] >= MAX_VISITS || issues.size() >= MAX_ISSUES) break;
                if (raw instanceof JSONObject branch)
                    walk(root, branch, value, path, depth + 1, visits, issues);
            }
        }
    }

    private static JSONObject resolve(JSONObject root, Object reference)
    {
        if (!(reference instanceof String ref) || !ref.startsWith("#/") || ref.length() > 512)
            return null;
        Object current = root;
        String[] parts = ref.substring(2).split("/", -1);
        if (parts.length > MAX_DEPTH) return null;
        for (String part : parts)
        {
            if (!(current instanceof JSONObject object)) return null;
            current = object.get(part.replace("~1", "/").replace("~0", "~"));
        }
        return current instanceof JSONObject object ? object : null;
    }

    private static List<String> types(Object raw)
    {
        if (raw instanceof String value) return TYPES.contains(value) ? List.of(value) : List.of();
        if (!(raw instanceof List<?> values) || values.size() > TYPES.size()) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values)
        {
            if (!(value instanceof String text) || !TYPES.contains(text)) return List.of();
            if (!result.contains(text)) result.add(text);
        }
        return result;
    }

    private static boolean matches(String type, Object value)
    {
        return switch (type)
        {
            case "null" -> value == null;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && integer(number);
            default -> false;
        };
    }

    private static boolean integer(Number value)
    {
        try { return new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static String child(String path, String field)
    {
        // Only schema-owned structural names and array indices are exposed. Do not quote
        // arbitrary property names from a rejected payload or copy its values/error text.
        if (path == null || field == null || !field.matches("[A-Za-z0-9_.-]{1,64}")) return null;
        String result = path + "/" + field;
        return result.length() <= 240 ? result : null;
    }

    private static void add(JSONArray issues, String code, String path, String expected)
    {
        if (path == null || issues.size() >= MAX_ISSUES) return;
        JSONObject issue = JSONObject.of("code", code, "path", path, "expected", expected);
        if (!issues.contains(issue)) issues.add(issue);
    }
}
