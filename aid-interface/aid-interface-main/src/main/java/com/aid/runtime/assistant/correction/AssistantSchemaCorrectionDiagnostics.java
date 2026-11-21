package com.aid.runtime.assistant.correction;

import cn.hutool.crypto.SecureUtil;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;
import com.aid.runtime.assistant.TrustedSchemaDiagnostics;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Derived hints accompanying, but never changing, an immutable correction receipt. */
public final class AssistantSchemaCorrectionDiagnostics
{
    public static final String KIND = "SCHEMA_VALIDATION_DETAIL";
    public static final String AUTHORITY = "SERVER_VERIFIED_SCHEMA_DIAGNOSTIC";
    private static final String VERSION = "assistant-schema-diagnostics/v2";
    private static final String LEGACY_VERSION = "assistant-schema-diagnostics/v1";
    private static final int MAX_BYTES = 4096;
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "boolean", "integer", "number", "null");

    private AssistantSchemaCorrectionDiagnostics() { }

    /** Caller must first verify source ownership, approval identity, receipt and settlement. */
    public static String fromVerifiedSource(String slotDigest,
            AssistantDecisionValidationFeedback feedback, MediaTextGenerateRequest request,
            MediaTextGenerateRequest.TextMessageItem message, String responseJson,
            String expectedMessageDigest, String expectedBindingsDigest)
    {
        if (feedback == null || !Set.of(
                AssistantDecisionValidationFeedback.Reason.NATIVE_CONTROL_SCHEMA_MISMATCH,
                AssistantDecisionValidationFeedback.Reason.ARGUMENT_SCHEMA_MISMATCH).contains(feedback.reason()))
            return null;
        if (request == null || message == null || !"assistant".equals(message.getRole())
                || message.getToolCalls() == null || message.getToolCalls().size() != 1
                || request.getAssistantNativeToolBindingsJson() == null || request.getOptions() == null)
            return null;
        String bindingsJson = request.getAssistantNativeToolBindingsJson();
        boolean nativeIdentity = Objects.equals(expectedMessageDigest, messageDigest(message))
                && Objects.equals(expectedBindingsDigest, SecureUtil.sha256(bindingsJson));
        boolean parsedIdentity = responseJson != null
                && Objects.equals(expectedMessageDigest,
                SecureUtil.sha256("assistant-parsed-native-response/v1\n" + responseJson))
                && Objects.equals(expectedBindingsDigest,
                SecureUtil.sha256("assistant-parsed-native-bindings/v1\n" + bindingsJson));
        if (!nativeIdentity && !parsedIdentity) return null;
        MediaTextGenerateRequest.TextToolCall call = message.getToolCalls().get(0);
        if (call == null || call.getName() == null || !call.getName().matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                || call.getArguments() == null || call.getArguments().length() > 262144) return null;
        JSONObject bindings = JSON.parseObject(bindingsJson);
        JSONArray entries = bindings == null ? null : bindings.getJSONArray("tools");
        if (entries == null || entries.size() > 256) return null;
        JSONObject binding = null;
        for (Object raw : entries)
        {
            if (!(raw instanceof JSONObject item) || !call.getName().equals(item.getString("name"))) continue;
            if (binding != null) return null;
            binding = item;
        }
        if (binding == null || !feedback.actionFamily().name().equals(binding.getString("action"))) return null;
        if (feedback.actionFamily() == AssistantDecisionValidationFeedback.ActionFamily.ASK
                && !feedback.trustedContractDigest().equals(binding.getString("controlInputSchemaDigest")))
            return null;
        Object rawTools = request.getOptions().get("tools");
        if (!(rawTools instanceof java.util.List<?> tools) || tools.size() > 256) return null;
        JSONObject schema = null;
        int matchedFunctions = 0;
        for (Object raw : tools)
        {
            JSONObject tool = raw instanceof JSONObject item ? item : JSON.parseObject(JSON.toJSONString(raw));
            JSONObject function = tool == null ? null : tool.getJSONObject("function");
            String name = function != null ? function.getString("name")
                    : tool == null ? null : tool.getString("name");
            if (!call.getName().equals(name)) continue;
            if (++matchedFunctions > 1) return null;
            if (function != null)
            {
                if (tool.containsKey("name") || tool.containsKey("parameters") || tool.containsKey("input_schema"))
                    return null;
                schema = function.getJSONObject("parameters");
            }
            else
            {
                // Responses places parameters beside name; Messages uses input_schema.
                // Require one unambiguous schema from the exact approved provider request.
                if (tool.containsKey("parameters") == tool.containsKey("input_schema")) return null;
                schema = tool.getJSONObject(tool.containsKey("parameters") ? "parameters" : "input_schema");
            }
        }
        if (schema == null) return null;
        JSONObject arguments;
        try { arguments = JSON.parseObject(call.getArguments()); }
        catch (RuntimeException invalidArguments) { return null; }
        JSONArray issues = TrustedSchemaDiagnostics.describe(schema, arguments);
        if (issues.isEmpty()) return null;
        JSONObject result = JSONObject.of("schemaVersion", VERSION, "observationKind", KIND,
                "authority", AUTHORITY, "slotDigest", slotDigest,
                "trustedContractDigest", feedback.trustedContractDigest(),
                "functionName", call.getName(), "issues", issues);
        String json = canonical(result);
        validate(json, slotDigest, feedback.trustedContractDigest());
        return json;
    }

    /** Exact, bounded shape; no arbitrary error message or rejected values are accepted. */
    public static JSONObject validate(String json, String slotDigest, String contractDigest)
    {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid schema correction diagnostics");
        JSONObject value = JSON.parseObject(json);
        Set<String> fields = Set.of("schemaVersion", "observationKind", "authority", "slotDigest",
                "trustedContractDigest", "functionName", "issues");
        if (value == null || !fields.equals(value.keySet())
                || !(VERSION.equals(value.getString("schemaVersion")) || LEGACY_VERSION.equals(value.getString("schemaVersion")))
                || !KIND.equals(value.getString("observationKind"))
                || !AUTHORITY.equals(value.getString("authority"))
                || slotDigest == null || !slotDigest.matches("[0-9a-f]{64}")
                || !slotDigest.equals(value.getString("slotDigest"))
                || contractDigest == null || !contractDigest.matches("[0-9a-f]{64}")
                || !contractDigest.equals(value.getString("trustedContractDigest"))
                || !(value.get("functionName") instanceof String name)
                || !name.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                || !(value.get("issues") instanceof JSONArray issues) || issues.isEmpty() || issues.size() > 8)
            throw new IllegalArgumentException("Invalid schema correction diagnostics");
        for (Object raw : issues)
        {
            if (!(raw instanceof JSONObject issue)
                    || !Set.of("code", "path", "expected").equals(issue.keySet())
                    || !(issue.get("code") instanceof String code)
                    || !(issue.get("path") instanceof String path) || path.length() > 240
                    || !path.matches("(?:/[A-Za-z0-9_.-]{1,64}){0,32}")
                    || !(issue.get("expected") instanceof String expected)
                    || !("REQUIRED_FIELD_MISSING".equals(code) && "present".equals(expected)
                    || "JSON_TYPE_MISMATCH".equals(code) && validTypes(expected)
                    || VERSION.equals(value.getString("schemaVersion")) && "QUOTED_NULL_NOT_ALLOWED".equals(code)
                        && "JSON_NULL_LITERAL_OR_DECLARED_ENUM".equals(expected)))
                throw new IllegalArgumentException("Invalid schema correction diagnostic issue");
        }
        if (!canonical(value).equals(json))
            throw new IllegalArgumentException("Noncanonical schema correction diagnostics");
        return value;
    }

    private static boolean validTypes(String value)
    {
        String[] types = value.split("\\|", -1);
        if (types.length == 0 || types.length > TYPES.size()) return false;
        for (String type : types) if (!TYPES.contains(type)) return false;
        return true;
    }

    private static String messageDigest(MediaTextGenerateRequest.TextMessageItem message)
    {
        JSONArray calls = new JSONArray();
        for (MediaTextGenerateRequest.TextToolCall call : message.getToolCalls())
        {
            if (call == null) return "";
            calls.add(JSONObject.of("id", call.getId(), "name", call.getName(), "arguments", call.getArguments()));
        }
        return SecureUtil.sha256(canonical(JSONObject.of("role", message.getRole(), "prefix", message.getPrefix(),
                "toolCalls", calls, "toolCallId", message.getToolCallId(), "responseId", message.getResponseId(),
                "toolError", message.getToolError())));
    }

    private static String canonical(JSONObject value)
    {
        return JSON.toJSONString(value, JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue);
    }
}
