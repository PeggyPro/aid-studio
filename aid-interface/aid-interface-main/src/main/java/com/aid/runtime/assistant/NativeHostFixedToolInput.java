package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Hide only identities uniquely fixed by the host's verified capability schema. */
final class NativeHostFixedToolInput {
    private static final String OWNED = "x-host-owned-default";
    private NativeHostFixedToolInput() { }
    static JSONObject withDefaults(JSONObject canonical, Map<String, Object> defaults) {
        if (defaults.isEmpty()) return canonical;
        JSONObject result = JSON.parseObject(canonical.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONArray branches = result.getJSONArray("oneOf");
        if (branches != null) {
            for (int i = 0; i < branches.size(); i++) branches.set(i, withDefaults(branches.getJSONObject(i), defaults));
            return result;
        }
        JSONObject properties = result.getJSONObject("properties");
        JSONArray required = result.getJSONArray("required");
        for (var entry : defaults.entrySet()) {
            JSONObject property = properties == null ? null : properties.getJSONObject(entry.getKey());
            Object value = entry.getValue();
            if (property == null || required != null && required.contains(entry.getKey())
                    || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                    || !com.alibaba.fastjson2.schema.JSONSchema.of(property).validate(value).isSuccess())
                throw new ServiceException("工具宿主缺省字段与固定契约不符");
            property.put("enum", JSONArray.of(value));
            property.put(OWNED, true);
        }
        return result;
    }
    static JSONObject schema(JSONObject canonical) {
        JSONObject result = JSON.parseObject(canonical.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        strip(result, fixed(canonical).keySet());
        return result;
    }
    static String instruction(JSONObject canonical) {
        if (canonical == null) return "";
        Set<String> fields = fixed(canonical).keySet();
        if (fields.isEmpty()) return "";
        String names = JSON.toJSONString(new java.util.TreeSet<>(fields));
        return names.length() <= 120
                ? "The server supplies " + names + "; do not send these fields. Use only the published parameters.\n"
                : "The server supplies fields omitted from these parameters; do not add undeclared fields.\n";
    }
    static JSONObject arguments(JSONObject supplied, JSONObject canonical) {
        JSONObject result = new JSONObject(supplied);
        fixed(canonical).forEach((field, value) -> {
            if (result.containsKey(field) && !Objects.equals(result.get(field), value))
                throw new ServiceException("调用参数与当前宿主固定绑定冲突");
            result.put(field, value);
        });
        return result;
    }
    private static Map<String,Object> fixed(JSONObject schema) {
        JSONArray alternatives = schema.getJSONArray("oneOf");
        if (alternatives != null) {
            Map<String,Object> common = null;
            for (Object item : alternatives) {
                Map<String,Object> values = fixed((JSONObject) item);
                if (common == null) common = new LinkedHashMap<>(values);
                else common.entrySet().removeIf(entry -> !values.containsKey(entry.getKey())
                        || !Objects.equals(values.get(entry.getKey()), entry.getValue()));
            }
            return common == null ? Map.of() : common;
        }
        Map<String,Object> result = new LinkedHashMap<>();
        JSONObject properties = schema.getJSONObject("properties");
        if (properties != null) for (String field : properties.keySet()) {
            JSONObject property = properties.getJSONObject(field);
            boolean owned = property != null && Boolean.TRUE.equals(property.get(OWNED));
            if (!owned && !Set.of("modelCode", "capabilityCode").contains(field)) continue;
            JSONArray values = property == null ? null : property.getJSONArray("enum");
            if (values != null && values.size() == 1) {
                Object value = values.get(0);
                if (owned || value instanceof String text && !text.isBlank()) result.put(field, value);
            }
        }
        return result;
    }
    private static void strip(JSONObject schema, Set<String> fields) {
        if (schema.getJSONArray("oneOf") != null)
            for (Object item : schema.getJSONArray("oneOf")) strip((JSONObject) item, fields);
        if (schema.getJSONObject("properties") != null) schema.getJSONObject("properties").keySet().removeAll(fields);
        if (schema.getJSONArray("required") != null) schema.getJSONArray("required").removeIf(fields::contains);
    }
}
