package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/** Direct visible text for newly opted-in Native bindings; canonical decisions stay unchanged. */
final class NativeMessageInputContract
{
    private NativeMessageInputContract() { }

    static boolean applies(String bindingSchemaVersion, String action)
    {
        return (NativeAssistantBindingPolicy.V5.equals(bindingSchemaVersion)
                || NativeAssistantBindingPolicy.V6.equals(bindingSchemaVersion)
                || NativeAssistantBindingPolicy.V7.equals(bindingSchemaVersion) || (NativeAssistantBindingPolicy.V8.equals(bindingSchemaVersion) || NativeAssistantBindingPolicy.methodResults(bindingSchemaVersion)))
                && ("CHAT".equals(action) || "COMPLETE".equals(action));
    }

    static JSONObject schema(String action)
    {
        if (!"CHAT".equals(action) && !"COMPLETE".equals(action))
            throw new IllegalArgumentException("Visible message contract action is invalid");
        JSONObject schema = NativeAssistantControlContractV1.nativePayloadSchema(action);
        schema = JSON.parseObject(schema.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        schema.getJSONObject("properties").put("message", JSONObject.of(
                "type", "string", "minLength", 1, "maxLength", 100000,
                "description", "Write the user-visible Markdown text directly. Do not serialize a message object or add a format/content wrapper. Completion evidence remains separate and required."));
        return schema;
    }

    static String digest(String action)
    {
        return SecureUtil.sha256(JSON.toJSONString(schema(action),
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject canonicalArguments(JSONObject arguments)
    {
        Object raw = arguments == null ? null : arguments.get("message");
        if (!(raw instanceof String text) || StrUtil.isBlank(text) || text.length() > 100000)
            throw new ServiceException("原生回答正文必须是非空文本，且不能超过100000字符");
        // Keep every other field for the existing strict canonical validator. Never parse text as JSON.
        JSONObject result = new JSONObject(arguments);
        result.put("message", JSONObject.of("format", "MARKDOWN", "content", text));
        return result;
    }
}
