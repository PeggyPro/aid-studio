package com.aid.media.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 文本模型结构化输出（JSON Mode）统一注入工具。
 * <p>
 * 业务显式开启、模型在 {@code capability_json} 声明 {@code "supportsJsonObject": true}，且本次请求的
 * system / user 消息中包含 "JSON" 关键词（官方硬性前置条件，不区分大小写）时，注入
 * {@code response_format={"type":"json_object"}}，让上游直接返回可解析的标准 JSON，
 * 避免 ```json 包裹等多余文本导致下游解析失败。
 * <p>
 * 规则：
 * <ol>
 *   <li>业务显式关闭时移除模型级 response_format；未配置开关且请求已显式传入时尊重原配置；</li>
 *   <li>capability 未打标的模型完全不受影响（方舟 Seed 2.x Pro 等官方无此能力的模型不得打标）；</li>
 *   <li>已要求JSON输出但消息不含 "JSON" 关键词时前置拒绝——官方会直接报错
 *       {@code 'messages' must contain the word 'json' in some form}；</li>
 *   <li>JSON Mode 仍保留统一输出上限；达到上限时由业务按 finish_reason=length 处理。</li>
 * </ol>
 */
@Slf4j
public final class StructuredOutputSupport {

    private StructuredOutputSupport() {
    }

    /** capability_json 中的 JSON Mode 支持标记键 */
    public static final String CAPABILITY_SUPPORTS_JSON_OBJECT = "supportsJsonObject";
    public static final String CAPABILITY_SUPPORTS_JSON_SCHEMA = "supportsJsonSchema";
    public static final String ENABLED_KEY = "_aid_structured_output_enabled";
    public static final String NATIVE_SCHEMA_KEY = "_aid_structured_output_schema";

    /** OpenAI 兼容请求体字段：返回内容格式 */
    private static final String KEY_RESPONSE_FORMAT = "response_format";

    /** response_format.type 取值：JSON 模式 */
    private static final String TYPE_JSON_OBJECT = "json_object";

    /** 统一输出长度限制字段。 */
    private static final String KEY_MAX_TOKENS = "max_tokens";
    private static final String KEY_MAX_COMPLETION_TOKENS = "max_completion_tokens";

    /** 官方前置条件关键词：system/user 消息需包含（不区分大小写） */
    private static final String KEYWORD_JSON = "json";

    /**
     * 按模型能力与官方前置条件注入 JSON Mode。
     *
     * @param modelConfig   模型聚合配置（读 capability_json.supportsJsonObject）
     * @param messages      已组装的 OpenAI 兼容 messages（检测 system/user 是否含 JSON 关键词）
     * @param mergedOptions extra_body 与业务 options 合并后的请求参数；业务须通过
     *                      {@link #ENABLED_KEY} 显式开启；显式关闭会移除配置层遗留的 response_format
     * @return 注入后的参数 Map；无需注入时原样返回入参
     */
    public static Map<String, Object> applyJsonModeIfSupported(AiModelConfigVo modelConfig,
                                                               List<Map<String, Object>> messages,
                                                               Map<String, Object> mergedOptions) {
        return applyJsonModeIfSupported(modelConfig, messages, mergedOptions, false);
    }

    public static Map<String, Object> applyJsonModeIfSupported(AiModelConfigVo modelConfig,
            List<Map<String, Object>> messages, Map<String, Object> mergedOptions, boolean streaming) {
        Object nativeSchema = mergedOptions == null ? null : mergedOptions.remove(NATIVE_SCHEMA_KEY);
        boolean configured = mergedOptions != null && mergedOptions.containsKey(ENABLED_KEY);
        boolean enabled = configured
                && Boolean.parseBoolean(String.valueOf(mergedOptions.remove(ENABLED_KEY)));
        if (configured && !enabled && mergedOptions != null) {
            // Skill/对话显式关闭时，必须压过供应商或模型 extra_body 中遗留的 JSON Mode。
            mergedOptions.remove(KEY_RESPONSE_FORMAT);
        }
        // A declared schema is a stronger contract than JSON syntax alone. Never claim
        // strict output by sending json_object, or forward unsupported format options.
        if (mergedOptions != null && mergedOptions.get(KEY_RESPONSE_FORMAT) instanceof Map<?, ?> format
                && "json_schema".equals(format.get("type"))
                && !supportsCapability(modelConfig, CAPABILITY_SUPPORTS_JSON_SCHEMA)) {
            throw new IllegalArgumentException("当前模型未核验严格JSON Schema能力");
        }
        if (enabled && nativeSchema instanceof Map<?, ?> schema
                && supportsCapability(modelConfig, CAPABILITY_SUPPORTS_JSON_SCHEMA)
                && !mergedOptions.containsKey(KEY_RESPONSE_FORMAT)) {
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", "aid_structured_output");
            declaration.put("strict", true);
            declaration.put("schema", schema);
            mergedOptions.put(KEY_RESPONSE_FORMAT, Map.of("type", "json_schema", "json_schema", declaration));
        }
        if (streaming && mergedOptions != null && mergedOptions.get(KEY_RESPONSE_FORMAT) instanceof Map<?, ?> format
                && "json_schema".equals(format.get("type"))
                && !supportsCapability(modelConfig, "supportsJsonSchemaStreaming"))
            throw new IllegalArgumentException("当前模型未核验严格JSON Schema与流式组合能力");
        // 业务显式配置 response_format：尊重业务，不覆盖
        if (mergedOptions != null && mergedOptions.containsKey(KEY_RESPONSE_FORMAT)) {
            if (mergedOptions.get(KEY_RESPONSE_FORMAT) instanceof Map<?, ?> format
                    && TYPE_JSON_OBJECT.equals(format.get("type"))) requireJsonInstruction(messages);
            return mergedOptions;
        }
        if (!enabled) {
            return mergedOptions == null || mergedOptions.isEmpty() ? null : mergedOptions;
        }
        if (!supportsJsonObject(modelConfig)) {
            return mergedOptions;
        }
        requireJsonInstruction(messages);
        Map<String, Object> out = mergedOptions == null ? new LinkedHashMap<>() : mergedOptions;
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", TYPE_JSON_OBJECT);
        out.put(KEY_RESPONSE_FORMAT, responseFormat);
        // JSON Mode 保留统一硬上限，避免实际请求与预冻结失去成本边界。
        log.info("结构化输出注入 JSON Mode: modelCode={}, max_tokens={}, max_completion_tokens={}",
                modelConfig.getModelCode(), out.get(KEY_MAX_TOKENS), out.get(KEY_MAX_COMPLETION_TOKENS));
        return out;
    }

    /**
     * 读 capability_json.supportsJsonObject 标记；缺失、解析失败均视为不支持。
     */
    private static boolean supportsJsonObject(AiModelConfigVo modelConfig) {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getCapabilityJson())) {
            return false;
        }
        try {
            JSONObject capability = JSONUtil.parseObj(modelConfig.getCapabilityJson());
            return Boolean.TRUE.equals(capability.getBool(CAPABILITY_SUPPORTS_JSON_OBJECT, false));
        } catch (Exception e) {
            // capability 解析失败按不支持处理，不阻断文本主链路
            log.warn("capability_json 解析失败，JSON Mode 按不支持处理: modelCode={}, err={}",
                    modelConfig.getModelCode(), e.getMessage());
            return false;
        }
    }

    private static void requireJsonInstruction(List<Map<String, Object>> messages) {
        if (messagesContainJsonKeyword(messages)) return;
        log.info("JSON输出约束拒绝: 请求缺少JSON格式指令");
        throw new IllegalArgumentException("缺少JSON格式指令");
    }

    private static boolean supportsCapability(AiModelConfigVo config, String key) {
        if (config == null || StrUtil.isBlank(config.getCapabilityJson())) return false;
        try { return Boolean.TRUE.equals(JSONUtil.parseObj(config.getCapabilityJson()).getBool(key, false)); }
        catch (RuntimeException invalid) { return false; }
    }

    /**
     * 检测 system / user 消息内容是否包含 "JSON" 关键词（不区分大小写）。
     */
    private static boolean messagesContainJsonKeyword(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            Object role = message.get("role");
            String roleStr = role == null ? "" : String.valueOf(role);
            if (!"system".equals(roleStr) && !"user".equals(roleStr)) {
                continue;
            }
            Object content = message.get("content");
            if (content instanceof String text
                    && text.toLowerCase(Locale.ROOT).contains(KEYWORD_JSON)) {
                return true;
            }
            if (content instanceof List<?> parts) for (Object part : parts) {
                if (part instanceof Map<?, ?> value && "text".equals(value.get("type"))
                        && value.get("text") instanceof String text
                        && text.toLowerCase(Locale.ROOT).contains(KEYWORD_JSON)) return true;
            }
        }
        return false;
    }

    /** Gemini generationConfig 字段：输出 MIME 类型（application/json = JSON Mode） */
    private static final String KEY_RESPONSE_MIME_TYPE = "responseMimeType";

    /** Gemini JSON Mode 的 MIME 取值 */
    private static final String MIME_APPLICATION_JSON = "application/json";

    /**
     * Gemini 原生形态的 JSON Mode 注入：模型打标 {@code supportsJsonObject} 且本次请求文本含
     * "JSON" 关键词（与 OpenAI 形态同一启发口径：业务要 JSON 的提示词均含该词）时，
     * 在 generationConfig 注入 {@code responseMimeType=application/json}，
     * 让上游直接返回标准 JSON（官方 Structured Outputs 的 JSON 模式）。
     * 调用方已显式配置 responseMimeType / responseSchema 时不覆盖。
     *
     * @param modelConfig      模型聚合配置（读 capability_json.supportsJsonObject）
     * @param hasJsonKeyword   请求文本（system/user/prompt）是否含 JSON 关键词
     * @param generationConfig Gemini generationConfig（原地注入）
     */
    public static void applyGeminiJsonModeIfSupported(AiModelConfigVo modelConfig,
                                                      boolean hasJsonKeyword,
                                                      Map<String, Object> generationConfig) {
        if (generationConfig == null || !hasJsonKeyword) {
            return;
        }
        if (generationConfig.containsKey(KEY_RESPONSE_MIME_TYPE)
                || generationConfig.containsKey("responseSchema")
                || generationConfig.containsKey("responseJsonSchema")) {
            return;
        }
        if (!supportsJsonObject(modelConfig)) {
            return;
        }
        generationConfig.put(KEY_RESPONSE_MIME_TYPE, MIME_APPLICATION_JSON);
        log.info("Gemini 结构化输出注入 responseMimeType=application/json: modelCode={}",
                modelConfig.getModelCode());
    }

    /**
     * 判断纯文本是否含 "JSON" 关键词（不区分大小写），供非 OpenAI messages 形态的 Provider 复用。
     */
    public static boolean textContainsJsonKeyword(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(KEYWORD_JSON);
    }
}
