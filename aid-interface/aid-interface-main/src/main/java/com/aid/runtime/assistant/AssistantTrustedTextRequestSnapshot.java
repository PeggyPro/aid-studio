package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;

/**
 * Durable server-only snapshot of a trusted text request.
 *
 * <p>The normal request JSON intentionally omits several internal fields. Those fields must not be
 * inferred from a runtime idempotency key when an approved iteration is restored. This envelope is
 * stored inside the approval scope, whose digest is checked before execution, and keeps the model
 * invocation identity separate from the runtime call identity.</p>
 */
public final class AssistantTrustedTextRequestSnapshot
{
    public static final String SCHEMA_VERSION = "assistant-trusted-text-request/v1";
    private static final int MAX_NATIVE_BINDINGS_CHARS = 1_000_000;

    private AssistantTrustedTextRequestSnapshot() { }

    public static JSONObject capture(MediaTextGenerateRequest request, String expectedRequestDigest)
    {
        if (request == null || StrUtil.isBlank(expectedRequestDigest))
            throw new ServiceException("Assistant normalized request is unavailable");
        String payloadJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        String actualDigest = AssistantMediaQuoteDigest.requestDigestFromJson(payloadJson);
        if (!Objects.equals(expectedRequestDigest, actualDigest))
            throw new ServiceException("Assistant normalized request digest changed");

        JSONObject identity = new JSONObject();
        identity.put("businessFuncCode", request.getBusinessFuncCode());
        identity.put("invocationIdentity", request.getInvocationIdentity());
        identity.put("callId", request.getCallId());
        identity.put("callIdentity", request.getCallIdentity());
        identity.put("billingAttemptId", request.getBillingAttemptId());
        identity.put("strictModelSelection", request.isStrictModelSelection());
        identity.put("inputImageTokenEstimate", request.getInputImageTokenEstimate());
        String nativeBindings = request.getAssistantNativeToolBindingsJson();
        identity.put("assistantNativeToolBindingsJson", nativeBindings);
        identity.put("assistantNativeToolBindingsDigest", StrUtil.isBlank(nativeBindings)
                ? null : cn.hutool.crypto.SecureUtil.sha256(nativeBindings));
        requireIdentity(identity);

        JSONObject result = new JSONObject();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("payloadJson", payloadJson);
        result.put("requestDigest", expectedRequestDigest);
        result.put("serverIdentity", identity);
        return result;
    }

    public static MediaTextGenerateRequest restore(JSONObject snapshot, String expectedRequestDigest)
    {
        if (snapshot == null || !SCHEMA_VERSION.equals(snapshot.getString("schemaVersion"))
                || StrUtil.isBlank(expectedRequestDigest)
                || !Objects.equals(expectedRequestDigest, snapshot.getString("requestDigest")))
            throw new ServiceException("Assistant normalized request snapshot changed");
        String payloadJson = snapshot.getString("payloadJson");
        if (StrUtil.isBlank(payloadJson)
                || !Objects.equals(expectedRequestDigest,
                AssistantMediaQuoteDigest.requestDigestFromJson(payloadJson)))
            throw new ServiceException("Assistant normalized request digest changed");
        JSONObject identity = snapshot.getJSONObject("serverIdentity");
        requireIdentity(identity);

        MediaTextGenerateRequest request;
        try
        {
            request = JSON.parseObject(payloadJson, MediaTextGenerateRequest.class);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("Assistant normalized request is invalid");
        }
        if (request == null) throw new ServiceException("Assistant normalized request is unavailable");
        request.setBusinessFuncCode(identity.getString("businessFuncCode"));
        request.setInvocationIdentity(identity.getString("invocationIdentity"));
        request.setCallId(identity.getString("callId"));
        request.setCallIdentity(identity.getString("callIdentity"));
        request.setBillingAttemptId(identity.getString("billingAttemptId"));
        request.setStrictModelSelection(Boolean.TRUE.equals(identity.getBoolean("strictModelSelection")));
        request.setInputImageTokenEstimate(identity.getInteger("inputImageTokenEstimate"));
        request.setAssistantNativeToolBindingsJson(identity.getString("assistantNativeToolBindingsJson"));
        if (!Objects.equals(expectedRequestDigest, AssistantMediaQuoteDigest.requestDigest(request)))
            throw new ServiceException("Assistant restored request digest changed");
        return request;
    }

    private static void requireIdentity(JSONObject identity)
    {
        if (identity == null || StrUtil.isBlank(identity.getString("invocationIdentity"))
                || StrUtil.isBlank(identity.getString("callId"))
                || StrUtil.isBlank(identity.getString("callIdentity"))
                || identity.getBoolean("strictModelSelection") == null)
            throw new ServiceException("Assistant normalized request identity is incomplete");
        String bindings = identity.getString("assistantNativeToolBindingsJson");
        String digest = identity.getString("assistantNativeToolBindingsDigest");
        if (StrUtil.isBlank(bindings) != StrUtil.isBlank(digest))
            throw new ServiceException("Assistant native tool identity is incomplete");
        if (StrUtil.isNotBlank(bindings) && (bindings.length() > MAX_NATIVE_BINDINGS_CHARS
                || !Objects.equals(digest, cn.hutool.crypto.SecureUtil.sha256(bindings))))
            throw new ServiceException("Assistant native tool identity changed");
    }
}
