package com.aid.skill.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enforces literal keywords independently from the structural schema engine. */
final class SkillSchemaLiteralConstraints {
    private SkillSchemaLiteralConstraints() { }
    static JSONObject structural(JSONObject schema) {
        JSONObject result = new JSONObject(schema);
        for (String key : List.of("enum", "const", "title", "description")) result.remove(key);
        if (schema.get("properties") instanceof JSONObject properties) {
            JSONObject nested = new JSONObject();
            properties.forEach((key, value) -> nested.put(key, structural((JSONObject) value)));
            result.put("properties", nested);
        }
        if (schema.get("items") instanceof JSONObject items) result.put("items", structural(items));
        return result;
    }
    static void requirePossible(JSONObject schema, String path) {
        if (schema.get("properties") instanceof JSONObject properties)
            properties.forEach((key, value) -> requirePossible((JSONObject) value, path + ".properties." + key));
        if (schema.get("items") instanceof JSONObject items) requirePossible(items, path + ".items");
        JSONSchema shape = JSONSchema.of(structural(schema));
        if (schema.containsKey("const") && !(shape.validate(schema.get("const")).isSuccess() && literalsMatch(schema, schema.get("const"))))
            throw new IllegalArgumentException("outputSchemaJson " + path + ".const 与类型或约束冲突");
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(value -> shape.validate(value).isSuccess() && literalsMatch(schema, value)))
            throw new IllegalArgumentException("outputSchemaJson " + path + ".enum 没有满足声明约束的值");
    }
    static boolean matches(JSONObject schema, Object value) {
        return JSONSchema.of(structural(schema)).validate(value).isSuccess() && literalsMatch(schema, value);
    }
    private static boolean literalsMatch(JSONObject schema, Object value) {
        if (schema.containsKey("const") && !equal(schema.get("const"), value)) return false;
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(item -> equal(item, value))) return false;
        if (value instanceof JSONObject object && schema.get("properties") instanceof JSONObject properties)
            for (Map.Entry<String, Object> property : properties.entrySet())
                if (object.containsKey(property.getKey()) && !literalsMatch((JSONObject) property.getValue(), object.get(property.getKey()))) return false;
        if (value instanceof JSONArray array && schema.get("items") instanceof JSONObject items)
            for (Object item : array) if (!literalsMatch(items, item)) return false;
        return true;
    }
    private static boolean equal(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn)
            return new BigDecimal(an.toString()).compareTo(new BigDecimal(bn.toString())) == 0;
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
            if (!am.keySet().equals(bm.keySet())) return false;
            for (Object key : am.keySet()) if (!equal(am.get(key), bm.get(key))) return false;
            return true;
        }
        if (a instanceof List<?> al && b instanceof List<?> bl) {
            if (al.size() != bl.size()) return false;
            for (int i = 0; i < al.size(); i++) if (!equal(al.get(i), bl.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }
}
