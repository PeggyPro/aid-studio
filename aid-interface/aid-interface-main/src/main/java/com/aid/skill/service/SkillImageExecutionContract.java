package com.aid.skill.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.Set;

/** Shared image Skill protocol; independent of package name, ownership and prompt content. */
public final class SkillImageExecutionContract {
    private static final Set<String> CAPABILITIES = Set.of("text_to_image", "image_to_image");
    public static final String INPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","additionalProperties":false,"properties":{"prompt":{"maxLength":65536,"minLength":1,"type":"string"}},"required":["prompt"],"type":"object"}
            """;
    public static final String OUTPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","oneOf":[{"additionalProperties":false,"properties":{"mediaTaskId":{"pattern":"^[1-9][0-9]{0,18}$","type":"string"},"mediaType":{"const":"IMAGE"},"modelCode":{"maxLength":100,"minLength":1,"type":"string"},"status":{"minLength":1,"type":"string"}},"required":["mediaTaskId","status","mediaType","modelCode"],"type":"object"},{"additionalProperties":false,"properties":{"artifacts":{"items":{"additionalProperties":true,"properties":{"ref":{"maxLength":200,"minLength":1,"type":"string"},"status":{"const":"FINALIZED"}},"required":["ref","status"],"type":"object"},"maxItems":16,"minItems":1,"type":"array"},"mediaTaskId":{"pattern":"^[1-9][0-9]{0,18}$","type":"string"},"status":{"const":"FINALIZED"}},"required":["status","mediaTaskId","artifacts"],"type":"object"}]}
            """;

    private SkillImageExecutionContract() { }

    public static boolean supportsCapability(String capability) {
        return capability != null && CAPABILITIES.contains(capability);
    }

    public static boolean supportsDefinition(String definitionJson) {
        try {
            JSONObject definition = JSON.parseObject(definitionJson);
            return definition != null && "IMAGE".equals(definition.getString("outputKind"))
                    && supportsCapability(definition.getString("capabilityCode"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static String withExamples(String platformSchema, JSONObject suppliedSchema) {
        if (!suppliedSchema.containsKey("examples")) return platformSchema;
        JSONObject schema = JSON.parseObject(platformSchema);
        schema.put("examples", suppliedSchema.get("examples"));
        return schema.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }

    /** JSON formatting and object key order are not part of the execution contract. */
    public static boolean supportsSchemas(String inputSchema, String outputSchema) {
        try {
            return JSON.parseObject(INPUT_SCHEMA).equals(SkillContractExamples.constraints(JSON.parseObject(inputSchema)))
                    && JSON.parseObject(OUTPUT_SCHEMA).equals(SkillContractExamples.constraints(JSON.parseObject(outputSchema)));
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
