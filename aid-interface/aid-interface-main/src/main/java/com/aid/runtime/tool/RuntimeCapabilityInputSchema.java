package com.aid.runtime.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Projects a server-owned canonical Tool contract onto one selected model capability. */
public final class RuntimeCapabilityInputSchema {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.networknt.schema.SchemaRegistry REGISTRY = com.networknt.schema.SchemaRegistry.withDialects(
            java.util.List.of(com.networknt.schema.dialect.Dialects.getDraft202012()), builder -> builder
                    .defaultDialectId("https://json-schema.org/draft/2020-12/schema").schemaCacheEnabled(false)
                    .schemaRegistryConfig(com.networknt.schema.SchemaRegistryConfig.builder()
                            .typeLoose(false).losslessNarrowing(false).build())
                    .schemaLoader(loader -> loader.fetchRemoteResources(false)
                            .resourceLoaders(resources -> resources.values(java.util.List::clear))));
    private RuntimeCapabilityInputSchema() { }

    /** Validate the frozen server contract without coercing, dropping or repairing arguments. */
    public static boolean matches(JSONObject schema, JSONObject arguments) {
        if (schema == null || arguments == null) return false;
        try {
            return REGISTRY.getSchema(MAPPER.readTree(schema.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue)))
                    .validate(MAPPER.readTree(arguments.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue))).isEmpty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Capability contract cannot be parsed", invalid);
        }
    }

    public static JSONObject bind(JSONObject source, String modelCode, String capabilityCode,
                                  Map<String, Object> support) {
        return bind(source, modelCode, capabilityCode, support, Set.of());
    }

    public static JSONObject bind(JSONObject source, String modelCode, String capabilityCode,
                                  Map<String, Object> support, Set<String> independentFields) {
        if (source == null || modelCode == null || modelCode.isBlank()
                || capabilityCode == null || capabilityCode.isBlank() || support == null)
            throw new IllegalArgumentException("Selected capability contract is incomplete");
        Object raw = support.get("supportedFields");
        if (!(raw instanceof Iterable<?> fields))
            throw new IllegalArgumentException("Supported canonical fields are missing");
        Set<String> allowed = new LinkedHashSet<>();
        for (Object field : fields) {
            if (!(field instanceof String name) || name.isBlank())
                throw new IllegalArgumentException("Invalid canonical field");
            allowed.add(name);
        }
        JSONObject result = JSON.parseObject(source.toJSONString());
        JSONObject properties = result.getJSONObject("properties");
        if (independentFields == null || independentFields.stream().anyMatch(field -> field == null
                || Set.of("modelCode", "capabilityCode").contains(field)
                || properties == null || !properties.containsKey(field)))
            throw new IllegalArgumentException("Independent Tool fields are invalid");
        allowed.addAll(independentFields);
        if (properties == null || !properties.keySet().containsAll(allowed)
                || !allowed.containsAll(Set.of("modelCode", "capabilityCode")))
            throw new IllegalArgumentException("Capability fields do not match the Tool contract");
        JSONArray required = result.getJSONArray("required");
        if (required != null && required.stream().anyMatch(field -> !allowed.contains(field)))
            throw new IllegalArgumentException("Capability cannot supply required Tool fields");
        properties.keySet().removeIf(field -> !allowed.contains(field));
        JSONArray boundRequired = required == null ? new JSONArray() : new JSONArray(required);
        if (support.get("requiredFields") instanceof Iterable<?> requiredFields) {
            for (Object field : requiredFields) {
                if (!(field instanceof String name) || !allowed.contains(name) || independentFields.contains(name))
                    throw new IllegalArgumentException("Invalid required capability field");
                if (!boundRequired.contains(name)) boundRequired.add(name);
                JSONObject definition = properties.getJSONObject(name);
                if (definition.get("type") instanceof JSONArray types) types.remove("null");
            }
        }
        for (String identity : Set.of("modelCode", "capabilityCode"))
            if (!boundRequired.contains(identity)) boundRequired.add(identity);
        result.put("required", boundRequired);
        if (support.get("fieldConstraints") instanceof Map<?, ?> constraints) {
            for (var entry : constraints.entrySet()) {
                JSONObject field = properties.getJSONObject(String.valueOf(entry.getKey()));
                if (field == null || independentFields.contains(String.valueOf(entry.getKey()))
                        || !(entry.getValue() instanceof Map<?, ?> limits))
                    throw new IllegalArgumentException("Invalid capability field constraints");
                for (String bound : Set.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
                    if (limits.get(bound) instanceof Number number) {
                        var value = new java.math.BigDecimal(number.toString());
                        var prior = field.getBigDecimal(bound);
                        field.put(bound, prior == null ? value : bound.startsWith("min") ? prior.max(value) : prior.min(value));
                    }
                }
                for (var pair : java.util.List.of(java.util.List.of("minimum", "maximum"),
                        java.util.List.of("minLength", "maxLength"), java.util.List.of("minItems", "maxItems")))
                    if (field.getBigDecimal(pair.get(0)) != null && field.getBigDecimal(pair.get(1)) != null
                            && field.getBigDecimal(pair.get(0)).compareTo(field.getBigDecimal(pair.get(1))) > 0)
                        throw new IllegalArgumentException("Capability bounds have no intersection");
                if (limits.get("enum") instanceof java.util.List<?> choices) {
                    JSONArray values = new JSONArray();
                    JSONArray existing = field.getJSONArray("enum");
                    for (Object choice : choices) if (existing == null || existing.contains(choice)) values.add(choice);
                    if (values.isEmpty()) throw new IllegalArgumentException("Capability choices have no intersection");
                    if (field.get("type") instanceof JSONArray types && types.contains("null")) values.add(null);
                    field.put("enum", values);
                }
            }
        }
        constrain(properties, "modelCode", modelCode);
        constrain(properties, "capabilityCode", capabilityCode);
        if (support.get("fixedValuesWhenProvided") instanceof Map<?, ?> fixed) {
            for (var entry : fixed.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !allowed.contains(name) || independentFields.contains(name)
                        || entry.getValue() == null)
                    throw new IllegalArgumentException("Invalid fixed capability field");
                constrain(properties, name, entry.getValue());
            }
        }
        // The model only sees supported fields. No generated value is silently removed or repaired.
        result.put("additionalProperties", false);
        return result;
    }

    private static void constrain(JSONObject properties, String name, Object value) {
        JSONObject field = properties.getJSONObject(name);
        if (field == null) throw new IllegalArgumentException("Missing capability identity field");
        if (field.getJSONArray("enum") != null && !field.getJSONArray("enum").contains(value))
            throw new IllegalArgumentException("Fixed value conflicts with the Tool contract");
        JSONArray values = new JSONArray(); values.add(value);
        // Identity is fixed; nullable optional fields preserve omission semantics.
        Object type = field.get("type");
        if (!Set.of("modelCode", "capabilityCode").contains(name)
                && type instanceof JSONArray types && types.contains("null")) values.add(null);
        field.put("enum", values);
    }
}
