package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/** Opt-in visible confirmation context. Historic ASK archives keep their exact contract. */
final class NativeAskVisibleContextContract {
    private static final NativeAssistantControlContractV1 CANONICAL = new NativeAssistantControlContractV1();
    private NativeAskVisibleContextContract() { }
    static boolean applies(String version, String action) {
        return (NativeAssistantBindingPolicy.V7.equals(version) || (NativeAssistantBindingPolicy.V8.equals(version) || NativeAssistantBindingPolicy.methodResults(version))) && "ASK".equals(action);
    }
    static JSONObject schema() {
        JSONObject result = JSON.parseObject(NativeAssistantControlContractV1.nativePayloadSchema("ASK")
                .toJSONString(JSONWriter.Feature.WriteMapNullValue));
        result.put("description", "Provide the actual visible message, business reason and questions. The host owns request identity, context identity and run-control options.");
        result.getJSONArray("required").add("message");
        result.getJSONObject("properties").put("message", JSONObject.of("type", "string", "minLength", 1,
                "maxLength", 100000, "description", "Actual user-visible Markdown context or proposal to review before answering. Include the concrete facts, values and alternatives the questions refer to; do not merely claim a proposal was already shown. This text grants no permission."));
        result.getJSONObject("$defs").getJSONObject("question").getJSONObject("properties")
                .getJSONObject("minSelections").put("description", "For SINGLE with allowFreeText=true, set 0 so a user can give an alternative instead of approving an option; required=true still requires an option or nonblank answer. Preserve genuine MULTI minimum selection counts.");
        result.getJSONObject("$defs").getJSONObject("question").put("anyOf", JSONArray.of(
                JSONObject.of("type", "object", "properties", JSONObject.of("type", JSONObject.of("enum", JSONArray.of("MULTI", "TEXT")))),
                JSONObject.of("type", "object", "properties", JSONObject.of("allowFreeText", JSONObject.of("enum", JSONArray.of(false)))),
                JSONObject.of("type", "object", "properties", JSONObject.of("minSelections", JSONObject.of("enum", JSONArray.of(0))))));
        return result;
    }
    static JSONObject schema(String version) {
        if (NativeAssistantBindingPolicy.hostInputs(version)) return NativeHostAskInputContract.schema(version);
        return (NativeAssistantBindingPolicy.V8.equals(version) || NativeAssistantBindingPolicy.methodResults(version)) ? NativeAskSelectionContract.schema() : schema();
    }
    static String digest(String version) {
        return SecureUtil.sha256(JSON.toJSONString(schema(version), JSONWriter.Feature.MapSortField,
                JSONWriter.Feature.WriteMapNullValue));
    }
    static boolean inputMatches(JSONObject arguments, String version) {
        if (NativeAssistantBindingPolicy.hostInputs(version)) return NativeHostAskInputContract.inputMatches(arguments, version);
        return (NativeAssistantBindingPolicy.V8.equals(version) || NativeAssistantBindingPolicy.methodResults(version))
                ? NativeAskSelectionContract.inputMatches(arguments) : inputMatches(arguments);
    }
    static String digest() {
        return SecureUtil.sha256(JSON.toJSONString(schema(), JSONWriter.Feature.MapSortField,
                JSONWriter.Feature.WriteMapNullValue));
    }
    static String message(JSONObject arguments) {
        Object value = arguments == null ? null : arguments.get("message");
        if (!(value instanceof String text) || StrUtil.isBlank(text) || text.length() > 100000)
            throw new ServiceException("原生提问必须携带实际可展示正文，且不能超过100000字符");
        return text;
    }
    static JSONObject canonicalArguments(JSONObject arguments, String version) {
        return NativeAssistantBindingPolicy.hostInputs(version)
                ? NativeHostAskInputContract.canonicalArguments(arguments, version) : canonicalArguments(arguments);
    }
    static JSONObject canonicalArguments(JSONObject arguments) {
        message(arguments);
        JSONObject result = new JSONObject(arguments);
        result.remove("message");
        return result;
    }
    static boolean inputMatches(JSONObject arguments) {
        JSONObject canonical;
        try { canonical = canonicalArguments(arguments); }
        catch (ServiceException invalid) { return false; }
        if (!CANONICAL.askInputMatches(canonical)) return false;
        JSONArray questions = canonical.getJSONArray("questions");
        for (Object raw : questions) {
            JSONObject question = (JSONObject) raw;
            if ("SINGLE".equals(question.getString("type")) && Boolean.TRUE.equals(question.getBoolean("allowFreeText"))
                    && !Integer.valueOf(0).equals(question.getInteger("minSelections"))) return false;
        }
        return true;
    }
}
