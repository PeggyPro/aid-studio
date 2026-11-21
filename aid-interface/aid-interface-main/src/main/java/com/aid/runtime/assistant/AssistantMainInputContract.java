package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** The pinned MAIN input contract, separate from tool parameters and billing canonicalization. */
public final class AssistantMainInputContract
{
    public static final String OBSERVATION_KIND = "SERVER_CONTEXT";
    public static final String AUTHORITY = "SERVER_OWNED_CONTEXT";
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "conversationHistory", "conversationHistoryPolicy", "catalogPolicy", "priorResultsPolicy");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "observationKind", "authority", "schemaVersion", "context");
    private static final String DRAFT_2020 = "https://json-schema.org/draft/2020-12/schema";
    private static final String DRAFT_7 = "http://json-schema.org/draft-07/schema#";
    // Only exact standard meta-schema resources bundled in the engine can be dereferenced.
    private static final Set<String> META_RESOURCES = Set.of(
            DRAFT_2020, "http://json-schema.org/draft-07/schema", DRAFT_7,
            "https://json-schema.org/draft/2020-12/meta/core",
            "https://json-schema.org/draft/2020-12/meta/applicator",
            "https://json-schema.org/draft/2020-12/meta/unevaluated",
            "https://json-schema.org/draft/2020-12/meta/validation",
            "https://json-schema.org/draft/2020-12/meta/meta-data",
            "https://json-schema.org/draft/2020-12/meta/format-annotation",
            "https://json-schema.org/draft/2020-12/meta/format-assertion",
            "https://json-schema.org/draft/2020-12/meta/content");
    private static final Set<String> VOCABULARIES = Set.of(
            "https://json-schema.org/draft/2020-12/vocab/core",
            "https://json-schema.org/draft/2020-12/vocab/applicator",
            "https://json-schema.org/draft/2020-12/vocab/unevaluated",
            "https://json-schema.org/draft/2020-12/vocab/validation",
            "https://json-schema.org/draft/2020-12/vocab/meta-data",
            "https://json-schema.org/draft/2020-12/vocab/format-annotation",
            "https://json-schema.org/draft/2020-12/vocab/format-assertion",
            "https://json-schema.org/draft/2020-12/vocab/content");
    private static final ObjectMapper SCHEMA_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDialects(
            List.of(Dialects.getDraft202012(), Dialects.getDraft7()), builder -> builder
                    .defaultDialectId(DRAFT_2020).schemaCacheEnabled(false)
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .typeLoose(false).losslessNarrowing(false)
                            .regularExpressionFactory(com.networknt.schema.regex.JoniRegularExpressionFactory.getInstance()).build())
                    .schemaLoader(loader -> loader.allow(iri -> META_RESOURCES.contains(iri.toString()))
                            .fetchRemoteResources(false).resourceLoaders(loaders -> loaders.values(List::clear))));
    private static final int MAX_SCHEMA_BYTES = 262_144;
    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final int MAX_CACHED_SCHEMAS = 64;
    private static final Logger LOG = LoggerFactory.getLogger(AssistantMainInputContract.class);
    private static final Map<String, Schema> SCHEMAS = new LinkedHashMap<>();

    private AssistantMainInputContract() { }

    /** Only MAIN serialization changes: retain required nullable fields at every copy boundary. */
    public static String serialize(Object value)
    {
        return JSON.toJSONString(value, JSONWriter.Feature.WriteMapNullValue);
    }

    public static JSONObject copy(JSONObject value)
    {
        return JSON.parseObject(serialize(value));
    }

    /** Preserve the complete server context, using the representation allowed by the pinned Schema. */
    public static JSONObject project(JSONObject assembled, String schemaJson)
    {
        if (assembled == null) throw rejected("DOCUMENT_INVALID", false);
        JSONObject legacy = copy(assembled);
        validateContextValues(legacy);
        if (serverContext(legacy) != null) throw rejected("CONTEXT_CONFLICT", false);
        JSONObject canonical = canonical(legacy);
        try
        {
            validate(canonical, schemaJson);
            return canonical;
        }
        catch (AssistantServiceException mismatch)
        {
            if (!"ASSISTANT_MAIN_INPUT_INVALID".equals(mismatch.getErrorCode())
                    || !(mismatch.getDetails() instanceof Map<?, ?> details)
                    || !"SCHEMA_MISMATCH".equals(details.get("reason"))) throw mismatch;
            // A fixed contract may require the original roots even when observations are allowed.
            // Retain the entire original representation only if its complete Schema validates.
            validate(legacy, schemaJson);
            return legacy;
        }
    }

    /** Budget both lossless representations before observation projection; never undercount carrier overhead. */
    public static int contextBytes(JSONObject assembled)
    {
        int legacyBytes = serialize(assembled).getBytes(StandardCharsets.UTF_8).length;
        if (serverContext(assembled) != null) return legacyBytes;
        validateContextValues(assembled);
        return Math.max(legacyBytes, serialize(canonical(assembled)).getBytes(StandardCharsets.UTF_8).length);
    }

    private static JSONObject canonical(JSONObject legacy)
    {
        JSONObject canonical = copy(legacy);
        Object raw = canonical.get("observations");
        if (raw != null && !(raw instanceof JSONArray)) throw rejected("OBSERVATIONS_INVALID", false);
        JSONArray observations = raw == null ? new JSONArray() : (JSONArray) raw;
        JSONObject context = new JSONObject();
        for (String field : CONTEXT_FIELDS) context.put(field, canonical.remove(field));
        JSONObject record = new JSONObject();
        record.put("observationKind", OBSERVATION_KIND);
        record.put("authority", AUTHORITY);
        record.put("schemaVersion", 1);
        record.put("context", context);
        observations.add(record);
        canonical.put("observations", observations);
        return canonical;
    }

    public static void validate(JSONObject document, String schemaJson)
    {
        Schema schema = schema(schemaJson);
        if (document == null) throw rejected("DOCUMENT_INVALID", false);
        JSONObject context = serverContext(document);
        if (context != null)
        {
            for (String field : CONTEXT_FIELDS)
                if (document.containsKey(field)) throw rejected("CONTEXT_CONFLICT", false);
        }
        else
        {
            validateContextValues(document);
        }
        try
        {
            if (!schema.validate(SCHEMA_JSON.readTree(serialize(document))).isEmpty())
                throw rejected("SCHEMA_MISMATCH", false);
        }
        catch (AssistantServiceException known) { throw known; }
        catch (StackOverflowError recursion) { throw rejected("SCHEMA_REFERENCE_CYCLE", true); }
        catch (Exception engineFailure) { throw rejected("SCHEMA_ENGINE_FAILURE", true); }
    }

    public static String validatedJson(JSONObject document, String schemaJson)
    {
        String json = serialize(document);
        validate(JSON.parseObject(json), schemaJson);
        return json;
    }

    /** Agent output is a model envelope and must not use the input context projection. */
    static boolean matchesConfiguredOutput(JSONObject document, String schemaJson)
    {
        if (StrUtil.isBlank(schemaJson)) return true;
        try {
            return schema(schemaJson).validate(SCHEMA_JSON.readTree(serialize(document))).isEmpty();
        } catch (StackOverflowError | Exception invalid) {
            return false;
        }
    }

    /** Returns the owned context only after validating its namespace and exact shape. */
    public static JSONObject serverContext(JSONObject document)
    {
        Object raw = document == null ? null : document.get("observations");
        if (raw == null) return null;
        if (!(raw instanceof JSONArray observations)) throw rejected("OBSERVATIONS_INVALID", false);
        JSONObject found = null;
        for (Object item : observations)
        {
            if (!(item instanceof JSONObject record))
            {
                if (item instanceof Map<?, ?> value && (OBSERVATION_KIND.equals(value.get("observationKind"))
                        || AUTHORITY.equals(value.get("authority")))) throw rejected("CONTEXT_INVALID", false);
                continue;
            }
            boolean kind = OBSERVATION_KIND.equals(record.get("observationKind"));
            boolean authority = AUTHORITY.equals(record.get("authority"));
            if (!kind && !authority) continue;
            if (!kind || !authority || found != null || !RECORD_FIELDS.equals(record.keySet())
                    || !Integer.valueOf(1).equals(record.get("schemaVersion"))
                    || !(record.get("context") instanceof JSONObject value)
                    || !CONTEXT_FIELDS.equals(value.keySet())) throw rejected("CONTEXT_INVALID", false);
            validateContextValues(value);
            found = value;
        }
        return found;
    }

    /** Native provider-view code must update the same policy seen by the model, not a second copy. */
    public static JSONObject policy(JSONObject document, String name)
    {
        if (!CONTEXT_FIELDS.contains(name)) throw rejected("CONTEXT_FIELD_INVALID", false);
        JSONObject owned = serverContext(document);
        return (owned == null ? document : owned).getJSONObject(name);
    }

    /** Validate the actual user envelope without changing its archived bytes or request digest. */
    public static void validateRequest(MediaTextGenerateRequest request, String schemaJson)
    {
        JSONObject input = null;
        if (request == null || request.getMessages() == null) throw rejected("ENVELOPE_MISSING", false);
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages())
        {
            if (message == null || !"user".equals(message.getRole()) || StrUtil.isBlank(message.getContent())) continue;
            JSONObject envelope;
            try { envelope = JSON.parseObject(message.getContent()); }
            catch (RuntimeException malformed) { throw rejected("ENVELOPE_INVALID", false); }
            if (envelope == null || !(envelope.get("input") instanceof JSONObject value) || input != null)
                throw rejected("ENVELOPE_INVALID", false);
            input = value;
        }
        validate(input, schemaJson);
    }

    private static void validateContextValues(JSONObject value)
    {
        if (value == null) throw rejected("CONTEXT_INVALID", false);
        for (String field : CONTEXT_FIELDS)
            if (!(value.get(field) instanceof Map)) throw rejected("CONTEXT_INVALID", false);
    }

    private static Schema schema(String json)
    {
        if (StrUtil.isBlank(json) || json.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES)
            throw rejected("SCHEMA_INVALID", true);
        synchronized (SCHEMAS)
        {
            Schema existing = SCHEMAS.get(json);
            if (existing != null) return existing;
        }
        Schema compiled;
        try
        {
            JsonNode definition = SCHEMA_JSON.readTree(json);
            // Admin packages define a JSON object Schema; its root need not declare type=object.
            if (definition == null || !definition.isObject()) throw rejected("SCHEMA_INVALID", true);
            String dialect = schemaDialect(definition, DRAFT_2020);
            checkSchema(definition, 0, dialect);
            Schema meta = REGISTRY.getSchema(SchemaLocation.of(dialect));
            if (!meta.validate(definition).isEmpty()) throw rejected("SCHEMA_INVALID", true);
            compiled = REGISTRY.getSchema(definition);
        }
        catch (AssistantServiceException known) { throw known; }
        catch (StackOverflowError recursion) { throw rejected("SCHEMA_REFERENCE_CYCLE", true); }
        catch (Exception invalid) { throw rejected("SCHEMA_INVALID", true); }
        synchronized (SCHEMAS)
        {
            if (SCHEMAS.size() >= MAX_CACHED_SCHEMAS) SCHEMAS.remove(SCHEMAS.keySet().iterator().next());
            SCHEMAS.put(json, compiled);
        }
        return compiled;
    }

    /** Inspect Schema positions only; annotation, const and enum data are not schemas. */
    private static void checkSchema(JsonNode node, int depth, String inheritedDialect)
    {
        if (depth > MAX_SCHEMA_DEPTH) throw rejected("SCHEMA_INVALID", true);
        if (node.isBoolean()) return;
        if (!node.isObject()) throw rejected("SCHEMA_INVALID", true);
        String dialect = schemaDialect(node, inheritedDialect);
        boolean draft2020 = DRAFT_2020.equals(dialect);
        // Keywords from another dialect are annotations, not additional Schema positions.
        // The 2020 meta-schema explicitly retains definitions/dependencies for compatibility.
        JsonNode vocabularies = node.get("$vocabulary");
        if (draft2020 && vocabularies != null && vocabularies.isObject())
            vocabularies.fields().forEachRemaining(entry -> {
                if (entry.getValue().isBoolean() && entry.getValue().booleanValue()
                        && !VOCABULARIES.contains(entry.getKey())) throw rejected("SCHEMA_VOCABULARY_UNSUPPORTED", true);
            });
        for (String keyword : draft2020 ? List.of("$ref", "$dynamicRef") : List.of("$ref"))
            if (node.has(keyword) && (!node.get(keyword).isTextual()
                    || !node.get(keyword).asText().startsWith("#")))
                throw rejected("SCHEMA_REFERENCE_UNSUPPORTED", true);
        for (String keyword : draft2020
                ? List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")
                : List.of("properties", "patternProperties", "definitions"))
        {
            JsonNode children = node.get(keyword);
            if (children == null) continue;
            if (!children.isObject()) throw rejected("SCHEMA_INVALID", true);
            children.forEach(child -> checkSchema(child, depth + 1, dialect));
        }
        JsonNode dependencies = node.get("dependencies");
        if (dependencies != null && dependencies.isObject())
            dependencies.forEach(child -> { if (!child.isArray()) checkSchema(child, depth + 1, dialect); });
        for (String keyword : draft2020
                ? List.of("additionalProperties", "propertyNames", "items", "contains", "not", "if", "then", "else",
                        "unevaluatedItems", "unevaluatedProperties", "contentSchema")
                : List.of("additionalProperties", "propertyNames", "items", "additionalItems",
                        "contains", "not", "if", "then", "else"))
        {
            JsonNode child = node.get(keyword);
            if (child == null) continue;
            if (!draft2020 && "items".equals(keyword) && child.isArray()) child.forEach(item -> checkSchema(item, depth + 1, dialect));
            else checkSchema(child, depth + 1, dialect);
        }
        for (String keyword : draft2020 ? List.of("allOf", "anyOf", "oneOf", "prefixItems")
                : List.of("allOf", "anyOf", "oneOf"))
        {
            JsonNode children = node.get(keyword);
            if (children == null) continue;
            if (!children.isArray()) throw rejected("SCHEMA_INVALID", true);
            children.forEach(child -> checkSchema(child, depth + 1, dialect));
        }
    }

    private static String schemaDialect(JsonNode node, String inheritedDialect)
    {
        String dialect = node.has("$schema") ? node.path("$schema").asText() : inheritedDialect;
        if (!Set.of(DRAFT_2020, DRAFT_7).contains(dialect)) throw rejected("SCHEMA_UNSUPPORTED", true);
        return dialect;
    }

    private static AssistantServiceException rejected(String reason, boolean schema)
    {
        String code = schema ? "ASSISTANT_MAIN_INPUT_SCHEMA_INVALID" : "ASSISTANT_MAIN_INPUT_INVALID";
        LOG.info("Assistant MAIN input rejected: code={}, reason={}", code, reason);
        return new AssistantServiceException(422, code, "助手输入暂不可用",
                Map.of("reason", reason, "planningSubmitted", false, "retryMessage", false));
    }
}
