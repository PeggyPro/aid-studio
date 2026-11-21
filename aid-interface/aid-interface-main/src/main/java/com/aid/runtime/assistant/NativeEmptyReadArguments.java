package com.aid.runtime.assistant;

import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.Set;

/** Explicit native transport shape; the published business Tool still accepts exactly {}. */
final class NativeEmptyReadArguments
{
    static final String FIELD = "nativeArgumentEncoding";
    static final String LEGACY_ENCODING = "EMPTY_READ_INPUT_V1";
    static final String ENCODING = "EMPTY_READ_OPTIONAL_INPUT_V2";
    private static final String PARAMETERS = """
            {"type":"object","properties":{"input":{"type":"object","properties":{},"additionalProperties":false}},"required":["input"],"additionalProperties":false}
            """;
    private static final JSONSchema SCHEMA = JSONSchema.of(JSON.parseObject(PARAMETERS));
    private static final String OPTIONAL_PARAMETERS = """
            {"type":"object","properties":{"input":{"type":"object","properties":{},"additionalProperties":false}},"additionalProperties":false}
            """;
    private static final JSONSchema OPTIONAL_SCHEMA = JSONSchema.of(JSON.parseObject(OPTIONAL_PARAMETERS));

    private NativeEmptyReadArguments() { }

    static boolean eligible(RuntimeToolRegistry.Descriptor descriptor)
    {
        return "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable()
                && "REPLAY_SAFE".equals(descriptor.recoveryPolicy())
                && plainEmptyObject(JSON.parseObject(descriptor.inputSchema()));
    }

    static boolean plainEmptyObject(JSONObject schema)
    {
        if (schema == null || !"object".equals(schema.get("type"))) return false;
        JSONObject properties = schema.getJSONObject("properties");
        JSONArray required = schema.getJSONArray("required");
        return Boolean.FALSE.equals(schema.get("additionalProperties"))
                && (properties == null || properties.isEmpty())
                && (required == null || required.isEmpty())
                && Set.of("$schema", "type", "properties", "required", "additionalProperties", "title", "description")
                    .containsAll(schema.keySet());
    }

    static JSONObject parameters() { return JSON.parseObject(OPTIONAL_PARAMETERS); }

    static boolean supported(Object encoding)
    {
        return ENCODING.equals(encoding) || LEGACY_ENCODING.equals(encoding);
    }

    static JSONObject decode(JSONObject arguments, String encoding, String contractDigest,
                             NativeRejectedSourceIdentity source)
    {
        JSONSchema schema = ENCODING.equals(encoding) ? OPTIONAL_SCHEMA : SCHEMA;
        if (!supported(encoding) || !schema.validate(arguments).isSuccess())
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.argumentSchemaMismatch(contractDigest), source);
        // Both V2 forms are declared in the archived function schema. V1 archives still
        // require input; this does not reinterpret or repair an older invalid response.
        return arguments.containsKey("input") ? arguments.getJSONObject("input") : new JSONObject();
    }
}
