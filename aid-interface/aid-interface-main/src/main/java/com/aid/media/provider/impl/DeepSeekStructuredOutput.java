package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.StructuredOutputSupport;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit, frozen schema-output strategy for DeepSeek's official Responses API. */
final class DeepSeekStructuredOutput {
    private static final Logger LOG = LoggerFactory.getLogger(DeepSeekStructuredOutput.class);
    private static final String DEFINITIONS_KEY = "$defs";
    private static final String REFERENCE_KEY = "$ref";
    private static final String LOCAL_REFERENCE_PREFIX = "#/$defs/";
    private static final Set<String> UNSUPPORTED_REFERENCE_KEYWORDS = Set.of(
            "$id", "$anchor", "$dynamicAnchor", "$dynamicRef", "$recursiveAnchor", "$recursiveRef");
    private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of(
            "properties", "patternProperties", "dependentSchemas", "definitions");
    private static final Set<String> SCHEMA_ARRAY_KEYWORDS = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SINGLE_SCHEMA_KEYWORDS = Set.of(
            "additionalProperties", "unevaluatedProperties", "propertyNames", "contains", "not",
            "if", "then", "else", "unevaluatedItems", "contentSchema", "additionalItems");
    static final int MAX_SCHEMA_BYTES = 100_000;
    static final int MAX_SCHEMA_DEPTH = 64;
    static final int MAX_SCHEMA_NODES = 20_000;
    private static final int MAX_REQUEST_BYTES = 48 * 1024 * 1024;
    static final String SCHEMA_KEY = "_aid_structured_output_schema";
    private DeepSeekStructuredOutput() { }

    static String apiSuffix(AiModelConfigVo model) {
        JSONObject capability = JSON.parseObject(model.getCapabilityJson());
        Object value = capability == null ? null : capability.get("nativeJsonSchemaApiSuffix");
        String suffix = value == null ? "/responses" : value instanceof String text ? text : null;
        try {
            suffix = ProviderEndpointUtils.normalizeSubmitPath(suffix);
            if (!suffix.endsWith("/responses")) throw new IllegalArgumentException();
            return suffix;
        } catch (IllegalArgumentException invalid) {
            LOG.info("DeepSeek结构化输出拒绝: 接口路径无效");
            throw new ServiceException("结构化接口无效");
        }
    }

    static boolean requested(MediaTextGenerateRequest request) {
        return request != null && request.getOptions() != null && request.getOptions().containsKey(SCHEMA_KEY);
    }

    static String body(String preparedChat, MediaTextGenerateRequest request) {
        Object schema = request.getOptions().get(SCHEMA_KEY);
        if (!Boolean.TRUE.equals(request.getOptions().get(StructuredOutputSupport.ENABLED_KEY))
                || !(schema instanceof Map<?, ?> map) || !"object".equals(map.get("type")))
            throw new ServiceException("结构化输出Schema无效");
        Map<String, Object> providerSchema = expandForProvider(schema);
        JSONObject source = JSON.parseObject(preparedChat);
        if (!"json_object".equals(source.getJSONObject("response_format") == null ? null
                : source.getJSONObject("response_format").getString("type")))
            throw new ServiceException("模型未声明JSON输出能力");
        if (source.containsKey("tools") || source.containsKey("tool_choice")
                || source.containsKey("stop") || source.containsKey("logprobs") || source.containsKey("top_logprobs"))
            throw new ServiceException("结构化输出参数组合不支持");
        List<Map<String, Object>> input = new ArrayList<>();
        StringBuilder instructions = new StringBuilder();
        for (Object item : source.getJSONArray("messages")) {
            if (!(item instanceof Map<?, ?> message)) throw new ServiceException("结构化输出消息无效");
            Object role = message.get("role");
            if (!List.of("system", "user", "assistant").contains(role) || message.containsKey("prefix"))
                throw new ServiceException("结构化输出消息角色不支持");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", role);
            Object content = message.get("content");
            if (content instanceof String text) value.put("content", text);
            else if (content instanceof List<?> parts) {
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (Object part : parts) {
                    if (!(part instanceof Map<?, ?> data)) throw new ServiceException("结构化输出引用无效");
                    if ("text".equals(data.get("type")) && data.get("text") instanceof String text) {
                        mapped.add(Map.of("type", "assistant".equals(role) ? "output_text" : "input_text", "text", text));
                    } else if ("image_url".equals(data.get("type")) && "user".equals(role)
                            && data.get("image_url") instanceof Map<?, ?> image && image.get("url") instanceof String url) {
                        Map<String, Object> mappedImage = new LinkedHashMap<>();
                        mappedImage.put("type", "input_image"); mappedImage.put("image_url", url);
                        if (image.get("detail") != null) mappedImage.put("detail", image.get("detail"));
                        mapped.add(mappedImage);
                    } else throw new ServiceException("结构化输出引用类型不支持");
                }
                value.put("content", mapped);
            } else throw new ServiceException("结构化输出消息内容无效");
            if ("system".equals(role)) {
                if (!instructions.isEmpty()) instructions.append('\n');
                if (content instanceof String text) instructions.append(text);
                else for (Object part : (List<?>) value.get("content"))
                    instructions.append(((Map<?, ?>) part).get("text")).append('\n');
            } else input.add(value);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", source.get("model")); body.put("input", input); body.put("stream", true);
        if (!instructions.isEmpty()) body.put("instructions", instructions.toString());
        body.put("max_output_tokens", source.get("max_tokens"));
        if (source.get("thinking") instanceof Map<?, ?> thinking)
            body.put("reasoning", Map.of("effort", "disabled".equals(thinking.get("type")) ? "none"
                    : source.getOrDefault("reasoning_effort", "high")));
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "aid_structured_output", "strict", true, "schema", providerSchema)));
        for (String field : List.of("temperature", "top_p"))
            if (source.containsKey(field)) body.put(field, source.get(field));
        if (source.containsKey("user_id")) body.put("user", source.get("user_id"));
        String result = JSON.toJSONString(body);
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES)
            throw new ServiceException("请求内容大小超限");
        return result;
    }

    static Map<String, Object> expandForProvider(Object schema) {
        String sourceJson = JSON.toJSONString(schema);
        if (sourceJson.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES)
            throw invalidSchema("source_size");
        JSONObject normalized = JSON.parseObject(sourceJson);
        Object rawDefinitions = normalized.get(DEFINITIONS_KEY);
        if (rawDefinitions != null && !(rawDefinitions instanceof Map<?, ?>))
            throw invalidSchema("definitions_type");
        Map<?, ?> definitions = rawDefinitions instanceof Map<?, ?> map ? map : Map.of();
        validateSchema(normalized, definitions, new LimitState(), 0);
        Object expanded = expandSchema(normalized, definitions, new LimitState(), new HashSet<>(), 0);
        if (!(expanded instanceof Map<?, ?> map)) throw invalidSchema("root_type");
        validateSerializedSize(expanded);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalidSchema("property_name");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void validateSchema(Object value, Map<?, ?> definitions, LimitState limits, int depth) {
        limits.visit(depth);
        if (value instanceof Boolean) return;
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("schema_type");
        for (String keyword : UNSUPPORTED_REFERENCE_KEYWORDS)
            if (map.containsKey(keyword)) throw invalidSchema("reference_keyword");
        if (map.containsKey(REFERENCE_KEY)) {
            if (map.size() != 1 || !(map.get(REFERENCE_KEY) instanceof String reference))
                throw invalidSchema("reference_sibling");
            String name = referenceName(reference);
            if (!definitions.containsKey(name)) throw invalidSchema("reference_missing");
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String keyword)) throw invalidSchema("property_name");
            Object keywordValue = entry.getValue();
            if (DEFINITIONS_KEY.equals(keyword) || SCHEMA_MAP_KEYWORDS.contains(keyword)) {
                validateSchemaMap(keywordValue, definitions, limits, depth + 1);
            } else if (SCHEMA_ARRAY_KEYWORDS.contains(keyword)) {
                validateSchemaArray(keywordValue, definitions, limits, depth + 1);
            } else if (SINGLE_SCHEMA_KEYWORDS.contains(keyword)) {
                validateSchema(keywordValue, definitions, limits, depth + 1);
            } else if ("items".equals(keyword)) {
                if (keywordValue instanceof List<?>) validateSchemaArray(keywordValue, definitions, limits, depth + 1);
                else validateSchema(keywordValue, definitions, limits, depth + 1);
            } else if ("dependencies".equals(keyword)) {
                validateDependencies(keywordValue, definitions, limits, depth + 1);
            } else {
                validateData(keywordValue, limits, depth + 1);
            }
        }
    }

    private static void validateSchemaMap(Object value, Map<?, ?> definitions, LimitState limits, int depth) {
        limits.visit(depth);
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("schema_map");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw invalidSchema("property_name");
            validateSchema(entry.getValue(), definitions, limits, depth + 1);
        }
    }

    private static void validateSchemaArray(Object value, Map<?, ?> definitions, LimitState limits, int depth) {
        limits.visit(depth);
        if (!(value instanceof List<?> list)) throw invalidSchema("schema_array");
        for (Object item : list) validateSchema(item, definitions, limits, depth + 1);
    }

    private static void validateDependencies(Object value, Map<?, ?> definitions, LimitState limits, int depth) {
        limits.visit(depth);
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("dependencies_type");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw invalidSchema("property_name");
            if (entry.getValue() instanceof List<?> list) validateData(list, limits, depth + 1);
            else validateSchema(entry.getValue(), definitions, limits, depth + 1);
        }
    }

    private static void validateData(Object value, LimitState limits, int depth) {
        limits.visit(depth);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw invalidSchema("property_name");
                validateData(entry.getValue(), limits, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) validateData(item, limits, depth + 1);
        }
    }

    private static Object expandSchema(Object value, Map<?, ?> definitions, LimitState limits,
            Set<String> activeReferences, int depth) {
        limits.visit(depth);
        if (value instanceof Boolean) return value;
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("schema_type");
        if (map.containsKey(REFERENCE_KEY)) {
            if (map.size() != 1 || !(map.get(REFERENCE_KEY) instanceof String reference))
                throw invalidSchema("reference_sibling");
            String name = referenceName(reference);
            if (!definitions.containsKey(name)) throw invalidSchema("reference_missing");
            if (!activeReferences.add(name)) throw invalidSchema("reference_cycle");
            try {
                return expandSchema(definitions.get(name), definitions, limits, activeReferences, depth + 1);
            } finally {
                activeReferences.remove(name);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String keyword)) throw invalidSchema("property_name");
            if (DEFINITIONS_KEY.equals(keyword)) continue;
            Object keywordValue = entry.getValue();
            if (SCHEMA_MAP_KEYWORDS.contains(keyword)) {
                result.put(keyword, expandSchemaMap(keywordValue, definitions, limits, activeReferences, depth + 1));
            } else if (SCHEMA_ARRAY_KEYWORDS.contains(keyword)) {
                result.put(keyword, expandSchemaArray(keywordValue, definitions, limits, activeReferences, depth + 1));
            } else if (SINGLE_SCHEMA_KEYWORDS.contains(keyword)) {
                result.put(keyword, expandSchema(keywordValue, definitions, limits, activeReferences, depth + 1));
            } else if ("items".equals(keyword)) {
                result.put(keyword, keywordValue instanceof List<?>
                        ? expandSchemaArray(keywordValue, definitions, limits, activeReferences, depth + 1)
                        : expandSchema(keywordValue, definitions, limits, activeReferences, depth + 1));
            } else if ("dependencies".equals(keyword)) {
                result.put(keyword, expandDependencies(keywordValue, definitions, limits, activeReferences, depth + 1));
            } else {
                result.put(keyword, copyData(keywordValue, limits, depth + 1));
            }
        }
        return result;
    }

    private static Map<String, Object> expandSchemaMap(Object value, Map<?, ?> definitions, LimitState limits,
            Set<String> activeReferences, int depth) {
        limits.visit(depth);
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("schema_map");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw invalidSchema("property_name");
            result.put(name, expandSchema(entry.getValue(), definitions, limits, activeReferences, depth + 1));
        }
        return result;
    }

    private static List<Object> expandSchemaArray(Object value, Map<?, ?> definitions, LimitState limits,
            Set<String> activeReferences, int depth) {
        limits.visit(depth);
        if (!(value instanceof List<?> list)) throw invalidSchema("schema_array");
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list)
            result.add(expandSchema(item, definitions, limits, activeReferences, depth + 1));
        return result;
    }

    private static Map<String, Object> expandDependencies(Object value, Map<?, ?> definitions, LimitState limits,
            Set<String> activeReferences, int depth) {
        limits.visit(depth);
        if (!(value instanceof Map<?, ?> map)) throw invalidSchema("dependencies_type");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw invalidSchema("property_name");
            result.put(name, entry.getValue() instanceof List<?>
                    ? copyData(entry.getValue(), limits, depth + 1)
                    : expandSchema(entry.getValue(), definitions, limits, activeReferences, depth + 1));
        }
        return result;
    }

    private static Object copyData(Object value, LimitState limits, int depth) {
        limits.visit(depth);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalidSchema("property_name");
                result.put(key, copyData(entry.getValue(), limits, depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(copyData(item, limits, depth + 1));
            return result;
        }
        return value;
    }

    private static void validateSerializedSize(Object value) {
        SizeState size = new SizeState();
        countSerializedBytes(value, size);
    }

    private static void countSerializedBytes(Object value, SizeState size) {
        if (value instanceof Map<?, ?> map) {
            size.add(2);
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalidSchema("property_name");
                if (!first) size.add(1);
                first = false;
                size.add(JSON.toJSONString(key).getBytes(StandardCharsets.UTF_8).length + 1L);
                countSerializedBytes(entry.getValue(), size);
            }
            return;
        }
        if (value instanceof List<?> list) {
            size.add(2);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) size.add(1);
                countSerializedBytes(list.get(i), size);
            }
            return;
        }
        size.add(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8).length);
    }

    private static String referenceName(String reference) {
        if (!reference.startsWith(LOCAL_REFERENCE_PREFIX) || reference.length() == LOCAL_REFERENCE_PREFIX.length()
                || reference.indexOf('/', LOCAL_REFERENCE_PREFIX.length()) >= 0)
            throw invalidSchema("reference_scope");
        String token = reference.substring(LOCAL_REFERENCE_PREFIX.length());
        if (token.indexOf('%') >= 0) throw invalidSchema("reference_encoding");
        StringBuilder decoded = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            if (current != '~') {
                decoded.append(current);
                continue;
            }
            if (++i >= token.length()) throw invalidSchema("reference_escape");
            char escaped = token.charAt(i);
            if (escaped == '0') decoded.append('~');
            else if (escaped == '1') decoded.append('/');
            else throw invalidSchema("reference_escape");
        }
        return decoded.toString();
    }

    private static ServiceException invalidSchema(String reason) {
        LOG.info("DeepSeek structured output schema rejected: {}", reason);
        return new ServiceException("结构化输出Schema无效");
    }

    private static final class LimitState {
        private int nodes;

        private void visit(int depth) {
            if (depth > MAX_SCHEMA_DEPTH) throw invalidSchema("depth");
            if (++nodes > MAX_SCHEMA_NODES) throw invalidSchema("nodes");
        }
    }

    private static final class SizeState {
        private long bytes;

        private void add(long count) {
            bytes += count;
            if (bytes > MAX_SCHEMA_BYTES) throw invalidSchema("expanded_size");
        }
    }
}
