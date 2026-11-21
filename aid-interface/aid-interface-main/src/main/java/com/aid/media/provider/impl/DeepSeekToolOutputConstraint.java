package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.OpenAiCompatiblePayloadResolver;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies DeepSeek tool-argument constraints without changing the business parameter schemas. */
final class DeepSeekToolOutputConstraint {
    private static final Logger LOG = LoggerFactory.getLogger(DeepSeekToolOutputConstraint.class);
    private static final String ENDPOINT_KEY = "nativeToolStrictEndpoint";
    private DeepSeekToolOutputConstraint() { }

    static void apply(AiModelConfigVo model, Map<String, Object> options) {
        if (!hasTools(options)) return;
        JSONArray tools = JSON.parseArray(JSON.toJSONString(options.get("tools"), JSONWriter.Feature.WriteMapNullValue));
        for (Object value : tools) {
            if (!(value instanceof JSONObject tool) || !"function".equals(tool.getString("type"))
                    || !(tool.get("function") instanceof JSONObject function)) throw reject("工具定义无效");
            if (function.containsKey("strict") && !Boolean.TRUE.equals(function.get("strict")))
                throw reject("工具严格模式冲突");
            function.put("strict", true);
        }
        options.put("tools", tools);
        LOG.info("DeepSeek工具参数约束: modelCode={}, strict=true, endpoint={}, tools={}",
                model.getModelCode(), endpointMode(model), tools.size());
    }

    static String apiSuffix(AiModelConfigVo model, MediaTextGenerateRequest request, String configured) {
        Map<String, Object> options = OpenAiCompatiblePayloadResolver.mergeExtraBody(
                model.getExtraBodyJson(), model.getModelExtraBodyJson(), request.getOptions());
        if (!hasTools(options)) return configured;
        if ("configured".equals(endpointMode(model))) return configured;
        String suffix = configured == null ? "/chat/completions" : configured;
        if (suffix.endsWith("/beta/chat/completions")) return suffix;
        for (String ending : new String[]{"/v1/chat/completions", "/chat/completions"})
            if (suffix.endsWith(ending)) return suffix.substring(0, suffix.length() - ending.length()) + "/beta/chat/completions";
        throw reject("严格工具接口无效");
    }

    private static String endpointMode(AiModelConfigVo model) {
        JSONObject capability = JSON.parseObject(model.getCapabilityJson());
        Object value = capability == null ? null : capability.get(ENDPOINT_KEY);
        if (value == null || "configured".equals(value)) return "configured";
        if ("beta".equals(value)) return "beta";
        throw reject("严格工具配置无效");
    }

    private static boolean hasTools(Map<String, Object> options) {
        return options != null && options.get("tools") instanceof Collection<?> tools && !tools.isEmpty();
    }

    private static ServiceException reject(String message) {
        LOG.info("DeepSeek工具参数约束拒绝: {}", message);
        return new ServiceException(message);
    }
}
