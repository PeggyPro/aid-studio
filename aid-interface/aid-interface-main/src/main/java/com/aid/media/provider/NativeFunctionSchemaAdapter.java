package com.aid.media.provider;

import com.aid.assistant.error.AssistantPlanningPreparationRejectedException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.Map;
import java.util.Collection;
import java.util.List;

/** Adapts an object parameter schema without changing the canonical input contract. */
public final class NativeFunctionSchemaAdapter
{
    private NativeFunctionSchemaAdapter() { }

    public static AdaptedSchema adapt(TextNativeToolProtocol.Shape shape, JSONObject canonicalSchema)
    {
        if (shape == null)
            throw new AssistantPlanningPreparationRejectedException(
                    AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_PROTOCOL_UNAVAILABLE);
        if (canonicalSchema == null || !"object".equals(canonicalSchema.get("type"))
                || (canonicalSchema.containsKey("properties")
                && !(canonicalSchema.get("properties") instanceof Map<?, ?>)))
            throw invalidDefinition();
        JSONObject providerSchema;
        try
        {
            providerSchema = JSON.parseObject(canonicalSchema.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        }
        catch (RuntimeException invalid)
        {
            throw invalidDefinition();
        }
        if (!providerSchema.containsKey("properties")) providerSchema.put("properties", new JSONObject());
        NativeFunctionSchemaReferences.prune(providerSchema);
        describeNullValues(providerSchema);
        // Responses may otherwise normalize optional/open properties into a stricter contract.
        Boolean strict = TextNativeToolProtocol.Shape.OPENAI_RESPONSES.equals(shape) ? Boolean.FALSE : null;
        return new AdaptedSchema(providerSchema, strict);
    }

    private static final String NULL_VALUE_GUIDANCE =
            "Where this schema permits null, use the JSON null value (without quotes); "
            + "the string \"null\" is not a null value. Preserve the declared type for non-null values.";
    private static final String REQUIRED_GUIDANCE =
            "Include every property listed in required, including nested required properties.";
    private static final String REQUIRED_BOOLEAN_GUIDANCE =
            "This property is required; include false explicitly instead of omitting it.";
    private static final String OPTIONAL_NON_NULL_GUIDANCE =
            "Omit this optional property when unused. JSON null is not allowed.";
    private static final String EMPTY_OBJECT_GUIDANCE =
            "This object accepts no fields. Supply exactly {} as its value. "
            + "Do not invent placeholder fields or copy schema keywords such as additionalProperties "
            + "into the argument value.";

    /** Provider-facing guidance only: preserve the canonical schema and every validation constraint. */
    private static void describeNullValues(JSONObject schema)
    {
        describeObjectFields(schema);
        if (declaresNull(schema)) appendDescription(schema, NULL_VALUE_GUIDANCE);
        for (String key : List.of("properties", "$defs", "definitions", "patternProperties", "dependentSchemas"))
        {
            if (schema.get(key) instanceof JSONObject children)
                for (Object child : children.values())
                    if (child instanceof JSONObject object) describeNullValues(object);
        }
        for (String key : List.of("items", "additionalProperties", "contains", "not", "if", "then", "else",
                "propertyNames", "unevaluatedProperties", "unevaluatedItems"))
            if (schema.get(key) instanceof JSONObject child) describeNullValues(child);
        for (String key : List.of("anyOf", "oneOf", "allOf", "prefixItems"))
            if (schema.get(key) instanceof Collection<?> children)
                for (Object child : children)
                    if (child instanceof JSONObject object) describeNullValues(object);
    }

    private static void describeObjectFields(JSONObject schema)
    {
        if (!(schema.get("properties") instanceof JSONObject properties)) return;
        Collection<?> required = schema.get("required") instanceof Collection<?> fields ? fields : List.of();
        if ("object".equals(schema.get("type")) && properties.isEmpty() && required.isEmpty()
                && Boolean.FALSE.equals(schema.get("additionalProperties"))
                && (!schema.containsKey("patternProperties")
                || schema.get("patternProperties") instanceof Map<?, ?> patterns && patterns.isEmpty())
                && List.of("$ref", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
                        "const", "enum", "minProperties")
                .stream().noneMatch(schema::containsKey))
            appendDescription(schema, EMPTY_OBJECT_GUIDANCE);
        if (!required.isEmpty()) appendDescription(schema, REQUIRED_GUIDANCE);
        for (Map.Entry<String, Object> entry : properties.entrySet())
        {
            if (!(entry.getValue() instanceof JSONObject property)) continue;
            Object type = property.get("type");
            if (required.contains(entry.getKey()))
            {
                if ("boolean".equals(type) || type instanceof Collection<?> types && types.contains("boolean"))
                    appendDescription(property, REQUIRED_BOOLEAN_GUIDANCE);
            }
            else if (type instanceof String name && !"null".equals(name)
                    || type instanceof Collection<?> types && !types.isEmpty() && !types.contains("null"))
            {
                appendDescription(property, OPTIONAL_NON_NULL_GUIDANCE);
            }
        }
    }

    private static void appendDescription(JSONObject schema, String guidance)
    {
        String description = schema.getString("description");
        if (description == null || !description.contains(guidance))
            schema.put("description", description == null || description.isBlank()
                    ? guidance : description + " " + guidance);
    }

    private static boolean declaresNull(JSONObject schema)
    {
        Object type = schema.get("type");
        if ("null".equals(type) || type instanceof Collection<?> types && types.contains("null")) return true;
        for (String key : List.of("anyOf", "oneOf"))
            if (schema.get(key) instanceof Collection<?> branches)
                for (Object branch : branches)
                    if (branch instanceof JSONObject object && declaresNull(object)) return true;
        return false;
    }

    private static AssistantPlanningPreparationRejectedException invalidDefinition()
    {
        return new AssistantPlanningPreparationRejectedException(
                AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
    }

    /** strict is absent for protocols without an explicit non-strict function flag. */
    public record AdaptedSchema(JSONObject schema, Boolean strict) { }
}
