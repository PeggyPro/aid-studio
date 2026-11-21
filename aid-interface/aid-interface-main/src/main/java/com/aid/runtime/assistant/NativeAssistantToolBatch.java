package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable server-normalized sequence for one provider response containing multiple safe reads. */
public final class NativeAssistantToolBatch
{
    public static final String ACTION = "TOOL_BATCH";
    public static final String SCHEMA = "assistant-native-tool-batch/v1";
    public static final int MAX_CALLS = 16;
    public static final int MAX_BATCH_CHARS = 1_000_000;
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "protocolVersion", "decisionId", "observedRunRevision", "observedPlanRevision",
            "action", "toolBatch");
    private static final Set<String> BATCH_FIELDS = Set.of("schemaVersion", "calls");
    private static final Set<String> CALL_FIELDS = Set.of(
            "index", "callId", "bindingName", "decisionJson", "decisionDigest");

    private NativeAssistantToolBatch() { }

    public static JSONObject envelope(AssistantLoopModelResult result, String decisionId,
                                      List<Call> calls)
    {
        require(result != null && result.getObservedRunRevision() != null
                && result.getObservedPlanRevision() != null && StrUtil.isNotBlank(decisionId)
                && calls != null && calls.size() >= 2 && calls.size() <= MAX_CALLS,
                "NATIVE_TOOL_BATCH_INVALID");
        JSONArray values = new JSONArray();
        for (int index = 0; index < calls.size(); index++)
        {
            Call call = calls.get(index);
            require(call != null && call.index() == index && StrUtil.isNotBlank(call.callId())
                    && StrUtil.isNotBlank(call.bindingName()) && StrUtil.isNotBlank(call.decisionJson())
                    && Objects.equals(call.decisionDigest(), SecureUtil.sha256(call.decisionJson())),
                    "NATIVE_TOOL_BATCH_INVALID");
            JSONObject value = new JSONObject();
            value.put("index", index);
            value.put("callId", call.callId());
            value.put("bindingName", call.bindingName());
            value.put("decisionJson", call.decisionJson());
            value.put("decisionDigest", call.decisionDigest());
            values.add(value);
        }
        JSONObject batch = new JSONObject();
        batch.put("schemaVersion", SCHEMA);
        batch.put("calls", values);
        JSONObject envelope = new JSONObject();
        envelope.put("protocolVersion", NativeAssistantLoopExecutor.PROTOCOL_VERSION);
        envelope.put("decisionId", decisionId);
        envelope.put("observedRunRevision", result.getObservedRunRevision());
        envelope.put("observedPlanRevision", result.getObservedPlanRevision());
        envelope.put("action", ACTION);
        envelope.put("toolBatch", batch);
        return envelope;
    }

    public static Batch parse(String value)
    {
        try
        {
            require(StrUtil.isNotBlank(value) && value.length() <= MAX_BATCH_CHARS,
                    "NATIVE_TOOL_BATCH_INVALID");
            JSONObject envelope = JSON.parseObject(value);
            require(envelope != null && envelope.size() == ENVELOPE_FIELDS.size()
                            && ENVELOPE_FIELDS.containsAll(envelope.keySet())
                            && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(
                            envelope.getString("protocolVersion"))
                            && ACTION.equals(envelope.getString("action"))
                            && StrUtil.isNotBlank(envelope.getString("decisionId"))
                            && envelope.getLong("observedRunRevision") != null
                            && envelope.getLong("observedRunRevision") >= 0
                            && envelope.getLong("observedPlanRevision") != null
                            && envelope.getLong("observedPlanRevision") >= 0,
                    "NATIVE_TOOL_BATCH_INVALID");
            JSONObject batch = envelope.getJSONObject("toolBatch");
            require(batch != null && batch.size() == BATCH_FIELDS.size()
                            && BATCH_FIELDS.containsAll(batch.keySet())
                            && SCHEMA.equals(batch.getString("schemaVersion")),
                    "NATIVE_TOOL_BATCH_INVALID");
            JSONArray values = batch.getJSONArray("calls");
            require(values != null && values.size() >= 2 && values.size() <= MAX_CALLS,
                    "NATIVE_TOOL_BATCH_INVALID");
            List<Call> calls = new ArrayList<>(values.size());
            Set<String> callIds = new HashSet<>();
            Set<String> decisionIds = new HashSet<>();
            for (int index = 0; index < values.size(); index++)
            {
                Object raw = values.get(index);
                require(raw instanceof JSONObject, "NATIVE_TOOL_BATCH_INVALID");
                JSONObject item = (JSONObject) raw;
                require(item.size() == CALL_FIELDS.size() && CALL_FIELDS.containsAll(item.keySet())
                                && Objects.equals(item.getInteger("index"), index),
                        "NATIVE_TOOL_BATCH_INVALID");
                String callId = item.getString("callId");
                String bindingName = item.getString("bindingName");
                String decisionJson = item.getString("decisionJson");
                String digest = item.getString("decisionDigest");
                require(callId != null && callId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                                && callIds.add(callId)
                                && bindingName != null && bindingName.matches("[A-Za-z0-9_-]{1,64}")
                                && StrUtil.isNotBlank(decisionJson)
                                && Objects.equals(digest, SecureUtil.sha256(decisionJson)),
                        "NATIVE_TOOL_BATCH_INVALID");
                JSONObject operation = JSON.parseObject(decisionJson);
                String operationDecisionId = operation == null ? null : operation.getString("decisionId");
                require(operation != null && "TOOL".equals(operation.getString("action"))
                                && Objects.equals(operation.getString("protocolVersion"),
                                envelope.getString("protocolVersion"))
                                && Objects.equals(operation.getLong("observedRunRevision"),
                                envelope.getLong("observedRunRevision"))
                                && Objects.equals(operation.getLong("observedPlanRevision"),
                                envelope.getLong("observedPlanRevision"))
                                && operationDecisionId != null
                                && operationDecisionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                                && decisionIds.add(operationDecisionId),
                        "NATIVE_TOOL_BATCH_INVALID");
                calls.add(new Call(index, callId, bindingName, decisionJson, digest));
            }
            return new Batch(envelope.getString("decisionId"),
                    envelope.getLong("observedRunRevision"),
                    envelope.getLong("observedPlanRevision"), List.copyOf(calls));
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException("NATIVE_TOOL_BATCH_INVALID");
        }
    }

    public static boolean isBatch(String value)
    {
        if (StrUtil.isBlank(value)) return false;
        try
        {
            JSONObject envelope = JSON.parseObject(value);
            return envelope != null && ACTION.equals(envelope.getString("action"));
        }
        catch (RuntimeException ignored) { return false; }
    }

    private static void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    public record Call(int index, String callId, String bindingName,
                       String decisionJson, String decisionDigest) { }
    public record Batch(String decisionId, long observedRunRevision, long observedPlanRevision,
                        List<Call> calls) { }
}
