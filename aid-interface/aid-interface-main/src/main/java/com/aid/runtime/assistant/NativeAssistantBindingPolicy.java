package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Reads only the immutable Agent definition pinned to this run. */
public final class NativeAssistantBindingPolicy
{
    public static final String V3 = "assistant-native-bindings/v3";
    public static final String V4 = "assistant-native-bindings/v4";
    public static final String V5 = "assistant-native-bindings/v5";
    public static final String V6 = "assistant-native-bindings/v6";
    public static final String V7 = "assistant-native-bindings/v7";
    public static final String V8 = "assistant-native-bindings/v8";
    public static final String V9 = "assistant-native-bindings/v9";
    public static final String V10 = "assistant-native-bindings/v10";

    public static final String V11 = "assistant-native-bindings/v11";

    public static boolean hostInputs(String version) { return V10.equals(version) || V11.equals(version); }

    public static boolean methodResults(String version) { return V9.equals(version) || hostInputs(version); }

    private static final String KEY = "nativeToolBindingsSchemaVersion";

    private NativeAssistantBindingPolicy() { }

    public static String explicitSelector(JSONObject definition)
    {
        if (definition == null) throw new ServiceException("固定 Agent 定义无效");
        JSONObject policy = definition.getJSONObject("responsePolicy");
        if (policy == null || !policy.containsKey(KEY)) return null;
        Object value = policy.get(KEY);
        if (!(value instanceof String text) || !supportedSelector(text))
            throw new ServiceException("固定 Agent 原生绑定策略无效");
        return text;
    }

    public static boolean supportedSelector(String value)
    {
        return V3.equals(value) || V4.equals(value) || V5.equals(value) || V6.equals(value) || V7.equals(value) || V8.equals(value) || V9.equals(value) || hostInputs(value);
    }

    public static boolean strictInputs(String value)
    {
        return V4.equals(value) || V5.equals(value) || V6.equals(value) || V7.equals(value) || V8.equals(value) || V9.equals(value) || hostInputs(value);
    }

    static String schemaVersion(AssistantLoopBinding binding)
    {
        JSONObject definition = JSON.parseObject(binding.getDefinitionJson());
        String explicit = explicitSelector(definition);
        return explicit == null ? V3 : explicit;
    }

    public static void requireArchivedCompatible(AssistantLoopBinding binding, String bindingsJson)
    {
        JSONObject archived = JSON.parseObject(bindingsJson);
        if (archived == null) throw new ServiceException("原生工具绑定缺失");
        Object version = archived.get("schemaVersion");
        String selected = schemaVersion(binding);
        boolean compatible = strictInputs(selected) ? selected.equals(version)
                : V3.equals(version) || "assistant-native-bindings/v2".equals(version);
        if (!compatible) throw new ServiceException("原生工具绑定与固定 Agent 策略不一致");
    }

    public static String askInputSchemaDigest(String version)
    {
        return (V7.equals(version) || V8.equals(version) || methodResults(version)) ? NativeAskVisibleContextContract.digest(version)
                : NativeAssistantControlContractV1.ASK_INPUT_SCHEMA_DIGEST;
    }

    public static String askInputSchemaDigest()
    {
        return NativeAssistantControlContractV1.ASK_INPUT_SCHEMA_DIGEST;
    }
}
