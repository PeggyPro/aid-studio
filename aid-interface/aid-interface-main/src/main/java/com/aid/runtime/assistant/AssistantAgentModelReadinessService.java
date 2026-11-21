package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.media.provider.StructuredOutputSupport;
import com.aid.media.provider.TextNativeSchemaTransportSupport;
import com.aid.media.provider.TextNativeToolTransportSupport;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.skill.service.SkillModelService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Validates frozen Assistant Agent model selection and its structured-output transport. */
@Service
@RequiredArgsConstructor
public class AssistantAgentModelReadinessService
{
    private static final String TEXT_CHAT = "text.chat";
    private static final String TEXT_OUTPUT_KIND = "TEXT";
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_MODEL_CODE_LENGTH = 100;
    private static final String NATIVE_CAPABILITY = "nativeJsonSchemaSupported";
    private static final String STREAMING_CAPABILITY = "supportsStreaming";
    private static final String TOOL_CAPABILITY = "supportsToolCalling";

    private final SkillModelService modelService;
    private final TextNativeSchemaTransportSupport nativeTransports;
    private final TextNativeToolTransportSupport nativeToolTransports;

    public Verification verify(String modelCapabilityCode,
                               String defaultModelCode,
                               List<String> selectableModelCodes,
                               String responseMode,
                               Integer maxRepairAttempts)
    {
        return verify(modelCapabilityCode, defaultModelCode, selectableModelCodes,
                responseMode, maxRepairAttempts, null);
    }

    private Verification verify(String modelCapabilityCode,
                                String defaultModelCode,
                                List<String> selectableModelCodes,
                                String responseMode,
                                Integer maxRepairAttempts,
                                Map<String, AidAiModel> preloadedActiveModels)
    {
        if (!TEXT_CHAT.equals(modelCapabilityCode))
            return failure(Failure.MODEL_CAPABILITY, "模型能力必须为text.chat");
        if (selectableModelCodes == null || selectableModelCodes.isEmpty()
                || selectableModelCodes.size() > MAX_CANDIDATES)
            return failure(Failure.MODEL_CONFIG, "候选模型配置无效");

        Set<String> unique = new LinkedHashSet<>();
        for (String code : selectableModelCodes)
        {
            String trimmed = StrUtil.trim(code);
            if (StrUtil.isBlank(code) || code.length() > MAX_MODEL_CODE_LENGTH
                    || !Objects.equals(code, trimmed) || !unique.add(code))
                return failure(Failure.MODEL_CONFIG, "候选模型配置无效");
        }
        if (StrUtil.isBlank(defaultModelCode) || defaultModelCode.length() > MAX_MODEL_CODE_LENGTH
                || !Objects.equals(defaultModelCode, StrUtil.trim(defaultModelCode))
                || !unique.contains(defaultModelCode))
            return failure(Failure.MODEL_CONFIG, "默认模型配置无效");
        if (maxRepairAttempts == null || maxRepairAttempts != 0
                || !(RuntimeAgentRequests.STRICT_LOCAL_JSON.equals(responseMode)
                || RuntimeAgentRequests.NATIVE_JSON_SCHEMA.equals(responseMode)
                || RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(responseMode)))
            return failure(Failure.RESPONSE_POLICY, "响应策略无效");

        Map<String, AidAiModel> active = preloadedActiveModels == null
                ? modelService.activeModels(unique, TEXT_OUTPUT_KIND)
                : unique.stream().filter(preloadedActiveModels::containsKey)
                .collect(java.util.stream.Collectors.toMap(Function.identity(), preloadedActiveModels::get,
                        (left, right) -> left, LinkedHashMap::new));
        if (active.size() != unique.size() || unique.stream().anyMatch(code -> !active.containsKey(code)))
            return failure(Failure.MODEL_UNAVAILABLE, "候选模型包含已停用、不可用或非文本模型");
        AidAiModel verifiedDefault = active.get(defaultModelCode);
        if (verifiedDefault == null)
            return failure(Failure.MODEL_UNAVAILABLE, "默认模型当前不可用");

        if (RuntimeAgentRequests.NATIVE_JSON_SCHEMA.equals(responseMode))
        {
            for (String code : unique)
            {
                AidAiModel model = active.get(code);
                if (!capability(model, NATIVE_CAPABILITY)
                        || !capability(model, StructuredOutputSupport.CAPABILITY_SUPPORTS_JSON_OBJECT)
                        || !nativeTransports.supports(model.getProtocol()))
                    return failure(Failure.NATIVE_UNAVAILABLE, "候选模型不支持原生Schema传输");
            }
        }
        if (RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(responseMode))
        {
            for (String code : unique)
            {
                AidAiModel model = active.get(code);
                if (!capability(model, STREAMING_CAPABILITY)
                        || !capability(model, TOOL_CAPABILITY)
                        || !nativeToolTransports.supports(model.getProtocol()))
                    return failure(Failure.NATIVE_TOOL_UNAVAILABLE, "候选模型不支持原生流式工具调用");
            }
        }

        Map<String, AidAiModel> verified = Collections.unmodifiableMap(new LinkedHashMap<>(active));
        return new Verification(true, Failure.NONE, null, responseMode, verifiedDefault, verified);
    }

    public Verification verifyFrozen(String modelConfigJson, String definitionJson)
    {
        return verifyFrozen(modelConfigJson, definitionJson, null);
    }

    /** Verify one frozen definition against a request-local batch of active TEXT models. */
    public Verification verifyFrozen(String modelConfigJson, String definitionJson,
                                     Map<String, AidAiModel> preloadedActiveModels)
    {
        JSONObject model;
        try
        {
            model = JSON.parseObject(modelConfigJson);
        }
        catch (RuntimeException invalid)
        {
            return failure(Failure.MODEL_CONFIG, "模型配置不可读取");
        }
        if (model == null || !(model.get("modelCapabilityCode") instanceof String capabilityCode)
                || !(model.get("defaultModelCode") instanceof String defaultCode))
            return failure(Failure.MODEL_CONFIG, "模型配置不可读取");

        List<String> candidates = stringList(model.get("selectableModelCodes"));
        if (candidates == null)
            return failure(Failure.MODEL_CONFIG, "候选模型配置无效");

        JSONObject definition;
        try
        {
            definition = JSON.parseObject(definitionJson);
        }
        catch (RuntimeException invalid)
        {
            return failure(Failure.RESPONSE_POLICY, "响应策略不可读取");
        }
        if (definition == null || !(definition.get("responsePolicy") instanceof Map<?, ?> policy)
                || !(policy.get("mode") instanceof String mode)
                || !zeroInteger(policy.get("maxRepairAttempts")))
            return failure(Failure.RESPONSE_POLICY, "响应策略不可读取");

        Verification verified = verify(capabilityCode, defaultCode, candidates, mode, 0,
                preloadedActiveModels);
        if (verified.ready() && RuntimeAgentRequests.NATIVE_JSON_SCHEMA.equals(mode)
                && !Boolean.TRUE.equals(model.get("nativeJsonSchemaSupported")))
            return failure(Failure.NATIVE_UNAVAILABLE, "固定版本未声明原生Schema传输");
        if (verified.ready() && RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(mode)
                && (!Boolean.TRUE.equals(model.get("supportsStreaming"))
                || !Boolean.TRUE.equals(model.get("supportsToolCalling"))
                || !Boolean.TRUE.equals(model.get("nativeToolCallingSupported"))))
            return failure(Failure.NATIVE_TOOL_UNAVAILABLE, "固定版本未声明原生流式工具调用");
        return verified;
    }

    private List<String> stringList(Object value)
    {
        if (!(value instanceof Collection<?> source)) return null;
        List<String> result = new ArrayList<>(source.size());
        for (Object item : source)
        {
            if (!(item instanceof String code)) return null;
            result.add(code);
        }
        return List.copyOf(result);
    }

    private boolean zeroInteger(Object value)
    {
        if (value instanceof BigInteger integer) return BigInteger.ZERO.equals(integer);
        return (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) && ((Number) value).longValue() == 0L;
    }

    private boolean capability(AidAiModel model, String key)
    {
        if (model == null || StrUtil.isBlank(model.getCapabilityJson())) return false;
        try
        {
            JSONObject value = JSON.parseObject(model.getCapabilityJson());
            return value != null && Boolean.TRUE.equals(value.get(key));
        }
        catch (RuntimeException invalid)
        {
            return false;
        }
    }

    private Verification failure(Failure failure, String reason)
    {
        return new Verification(false, failure, reason, null, null, Map.of());
    }

    public enum Failure
    {
        NONE,
        MODEL_CAPABILITY,
        MODEL_CONFIG,
        MODEL_UNAVAILABLE,
        RESPONSE_POLICY,
        NATIVE_UNAVAILABLE,
        NATIVE_TOOL_UNAVAILABLE
    }

    public record Verification(boolean ready,
                               Failure failure,
                               String reason,
                               String responseMode,
                               AidAiModel verifiedDefaultModel,
                               Map<String, AidAiModel> verifiedModels)
    {
    }
}
