package com.aid.skill.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.List;

/** Method arguments are objects; omitted schema type does not remove that transport contract. */
public final class SkillMethodInputContract {
    private SkillMethodInputContract() { }

    public static JSONObject objectParameters(String schemaJson) {
        Object value;
        try { value = JSON.parse(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("inputSchemaJson 必须为 JSON 对象"); }
        if (!(value instanceof JSONObject schema))
            throw new IllegalArgumentException("inputSchemaJson 必须为 JSON 对象");
        return objectParameters(schema);
    }

    /** Return a provider-facing copy. Never rewrite the package or its canonical schema digest. */
    public static JSONObject objectParameters(JSONObject canonical) {
        if (canonical == null) throw new IllegalArgumentException("inputSchemaJson 必须为 JSON 对象");
        JSONObject effective = JSON.parseObject(SkillContractExamples.constraints(canonical)
                .toJSONString(JSONWriter.Feature.WriteMapNullValue));
        if (effective.containsKey("type") && !"object".equals(effective.get("type")))
            throw new IllegalArgumentException("inputSchemaJson 根类型必须为 object，省略时按对象参数处理");
        if (effective.containsKey("properties") && !(effective.get("properties") instanceof JSONObject))
            throw new IllegalArgumentException("inputSchemaJson.properties 必须为对象");
        if (effective.containsKey("required") && (!(effective.get("required") instanceof List<?> fields)
                || fields.stream().anyMatch(field -> !(field instanceof String))))
            throw new IllegalArgumentException("inputSchemaJson.required 必须为字符串数组");
        effective.put("type", "object");
        effective.putIfAbsent("properties", new JSONObject());
        if ("NONE".equals(canonical.getString(SkillContractExamples.INPUT_MODE))
                || "FREE_FORM".equals(canonical.getString(SkillContractExamples.INPUT_MODE))) {
            // These modes use the established prompt and attachment channels, without object parameters.
            effective.put("additionalProperties", false);
        }
        if (Boolean.FALSE.equals(effective.get("additionalProperties"))
                && effective.get("required") instanceof List<?> fields
                && fields.stream().anyMatch(field -> !effective.getJSONObject("properties").containsKey(field)))
            throw new IllegalArgumentException("inputSchemaJson.required 包含未声明且不允许的属性");
        try {
            if (JSONSchema.of(effective) == null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("inputSchemaJson 不是可执行的对象参数契约", invalid);
        }
        return effective;
    }
}
