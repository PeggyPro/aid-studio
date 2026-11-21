package com.aid.model.definition;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将已校验的能力映射应用到协议请求体，禁止在报价后改写适配器已确定的参数。 */
public final class ModelConfiguredRequestBody {
    private ModelConfiguredRequestBody() { }

    public static String configuredRequestKey(AiModelConfigVo config, Object request) {
        if (config == null || config.getCapabilityCode() == null) return null;
        Object key = apply(config, Map.of(), request == null ? Map.of() : request).get("req_key");
        if (key == null) return null;
        if (!(key instanceof String text) || text.isBlank()) throw new ServiceException("req_key 配置无效");
        return (String) key;
    }

    public static Map<String, Object> apply(AiModelConfigVo config, Map<String, Object> body, Object request) {
        Map<String, Object> result = new LinkedHashMap<>(body);
        if (config != null && config.getCapabilityCode() != null) {
            merge(result, JSON.parseObject(config.getModelExtraBodyJson()), "", request);
            applyRequestMappings(config, result, request);
        }
        return com.aid.media.security.PromptConfidentialityPolicy.protectMediaBody(result, request);
    }

    private static Object convert(Object value, String type, String field) {
        if (type == null || "preserve".equals(type)) return value;
        try {
            return switch (type) {
                case "string" -> {
                    if (value instanceof Map<?, ?> || value instanceof List<?>) throw new IllegalArgumentException();
                    yield value.toString();
                }
                case "number" -> new BigDecimal(value.toString());
                case "integer" -> new BigDecimal(value.toString()).toBigIntegerExact();
                case "boolean" -> {
                    if (value instanceof Boolean) yield value;
                    if ("true".equals(value) || "false".equals(value)) yield Boolean.valueOf(value.toString());
                    throw new IllegalArgumentException();
                }
                case "object" -> { if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(); yield value; }
                case "array" -> { if (!(value instanceof List<?>)) throw new IllegalArgumentException(); yield value; }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException ex) { throw new ServiceException("参数无法转换为目标类型：" + field); }
    }

    /** SDK 请求先使用其原有序列化器，保留协议字段名及空值策略。 */
    public static String applyJson(AiModelConfigVo config, String json, Object request) {
        return JSON.toJSONString(apply(config, JSON.parseObject(json), request));
    }

    /**
     * Applies mappings after an OpenAI-compatible adapter has already merged fixed defaults and
     * normalized runtime-owned protocol controls. Fixed parameters owned by that normalization are
     * not replayed; every remaining fixed parameter and mapping keeps the ordinary conflict checks.
     */
    public static String applyJsonAfterRuntimeNormalization(AiModelConfigVo config, String json, Object request,
            TextReasoningOptionsResolver.RuntimeNormalization normalization) {
        return JSON.toJSONString(applyAfterRuntimeNormalization(config, JSON.parseObject(json), request, normalization));
    }

    public static Map<String, Object> applyAfterRuntimeNormalization(
            AiModelConfigVo config, Map<String, Object> body, Object request,
            TextReasoningOptionsResolver.RuntimeNormalization normalization) {
        Map<String, Object> result = new LinkedHashMap<>(body);
        if (config != null && config.getCapabilityCode() != null) {
            Map<String, Object> fixed = TextReasoningOptionsResolver.withoutNormalizedRuntimeControls(
                    config, JSON.parseObject(config.getModelExtraBodyJson()), result, normalization);
            merge(result, fixed, "", request);
            applyRequestMappings(config, result, request);
        }
        if (request instanceof MediaTextGenerateRequest text) {
            String wire = com.aid.context.NativeToolCatalogProjection.projectWire(
                    JSON.toJSONString(result), text.getAssistantNativeToolBindingsJson());
            return JSON.parseObject(com.aid.media.security.PromptConfidentialityPolicy.protectWire(wire, text, normalization));
        }
        return result;
    }

    private static void applyRequestMappings(AiModelConfigVo config, Map<String, Object> result, Object request) {
        if (config.getRequestMappings() == null || config.getRequestMappings().isEmpty()) return;
        // Material signatures can change before submit, so mappings always read the current request.
        Map<String, Object> parameters = JSON.parseObject(JSON.toJSONString(request));
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (var mapping : config.getRequestMappings()) {
            Object value = ModelParameterValidator.read(parameters, mapping.getSource());
            if (value != null) {
                ModelParameterValidator.write(mapped, mapping.getTarget(),
                        convert(value, mapping.getType(), mapping.getSource()));
            }
        }
        merge(result, mapped, "", request);
    }

    private static void merge(Map<String, Object> target, Map<String, Object> additions, String prefix, Object request) {
        if (additions == null) return;
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            String key = entry.getKey();
            Object before = target.get(key);
            Object after = entry.getValue();
            if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
                Map<String, Object> nested = new LinkedHashMap<>();
                beforeMap.forEach((k, v) -> nested.put(String.valueOf(k), v));
                Map<String, Object> extension = new LinkedHashMap<>();
                afterMap.forEach((k, v) -> extension.put(String.valueOf(k), v));
                merge(nested, extension, prefix + key + ".", request);
                target.put(key, nested);
            } else if (before != null && !equal(before, after)) {
                // A configured output maximum must not raise the smaller, already quoted text cap.
                // All other fixed parameters retain the original exact-match conflict protection.
                if (isQuotedTextCap(prefix + key, before, after, request)) continue;
                throw new ServiceException("调用配置与业务参数冲突：" + prefix + key);
            } else target.put(key, after);
        }
    }

    private static boolean isQuotedTextCap(String field, Object before, Object configured, Object request) {
        if (!(request instanceof MediaTextGenerateRequest text) || text.getOptions() == null) return false;
        if (!List.of("max_tokens", "max_completion_tokens", "maxOutputTokens",
                "generationConfig.maxOutputTokens").contains(field)) return false;
        Map<String, Object> options = text.getOptions();
        Integer actual = positiveInteger(before);
        Integer maximum = positiveInteger(configured);
        Integer provider = positiveInteger(options.get(TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY));
        Integer billing = positiveInteger(options.get(TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY));
        Integer requested = positiveInteger(options.get(TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY));
        Object apiField = options.get(TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY);
        return actual != null && maximum != null && actual <= maximum
                && actual <= TextOutputLimitResolver.ABSOLUTE_OUTPUT_TOKENS
                && actual.equals(provider) && actual.equals(requested)
                && billing != null && billing == TextOutputLimitResolver.billingCeiling(actual)
                && (field.endsWith("maxOutputTokens") || field.equals(apiField));
    }

    private static Integer positiveInteger(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            int integer = new BigDecimal(value.toString()).intValueExact();
            return integer > 0 ? integer : null;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean equal(Object a, Object b) {
        return a instanceof Number && b instanceof Number
                ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 : Objects.equals(a, b);
    }
}
