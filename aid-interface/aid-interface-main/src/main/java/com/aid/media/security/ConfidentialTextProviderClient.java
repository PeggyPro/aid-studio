package com.aid.media.security;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.TextProviderFailureEvidence;
import com.aid.media.provider.TextStreamCallbacks;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Request instructions enforce confidentiality; this adapter only separates public transport data. */
public final class ConfidentialTextProviderClient implements TextProviderClient {
    private final TextProviderClient delegate;
    private ConfidentialTextProviderClient(TextProviderClient delegate) { this.delegate = delegate; }
    public static TextProviderClient wrap(TextProviderClient client) {
        return client instanceof ConfidentialTextProviderClient ? client : new ConfidentialTextProviderClient(client);
    }
    @Override public ProviderTaskResult query(AiModelConfigVo c, String id) { return delegate.query(c, id); }
    @Override public String protocol() { return delegate.protocol(); }
    @Override public boolean supportsProtocol(String p) { return delegate.supportsProtocol(p); }
    @Override public boolean supportsModel(String p) { return delegate.supportsModel(p); }
    @Override public boolean supportsNativeNonStream(MediaTextGenerateRequest r) { return delegate.supportsNativeNonStream(r); }
    @Override public boolean supportsNativeJsonSchema() { return delegate.supportsNativeJsonSchema(); }
    @Override public boolean supportsNativeToolCalling() { return delegate.supportsNativeToolCalling(); }
    @Override public void validateRequest(AiModelConfigVo c, MediaTextGenerateRequest r) { delegate.validateRequest(c, r); }
    @Override public void validateProviderConfiguration(AiModelConfigVo c, MediaTextGenerateRequest r) { delegate.validateProviderConfiguration(c, r); }
    @Override public void validateNativeNonStream(MediaTextGenerateRequest r) { delegate.validateNativeNonStream(r); }

    @Override public ProviderSubmitResult chatSync(AiModelConfigVo c, MediaTextGenerateRequest r) {
        ProviderSubmitResult result = delegate.chatSync(c, r);
        if (result == null) return null;
        if (!Boolean.TRUE.equals(r.getIncludeReasoning())) result.setDirectReasoning(null);
        if (result.getDirectText() != null || result.getToolMessage() != null)
            result.setRawResponse(frameReceipt(result.getRawResponse()));
        return result;
    }

    @Override public void streamChat(AiModelConfigVo config, MediaTextGenerateRequest request,
                                     TextStreamCallbacks downstream) throws IOException {
        delegate.streamChat(config, request, new TextStreamCallbacks() {
            private boolean ended;
            @Override public void onResponseBody(AutoCloseable body) { downstream.onResponseBody(body); }
            @Override public void onProtocolFrame(com.fasterxml.jackson.databind.JsonNode frame) { downstream.onProtocolFrame(frame); }
            @Override public void onProtocolEndMarker() { downstream.onProtocolEndMarker(); }
            @Override public void onDelta(String delta) { downstream.onDelta(delta); }
            @Override public void onProviderReasoningDelta(String delta) {
                if (Boolean.TRUE.equals(request.getIncludeReasoning())) downstream.onProviderReasoningDelta(delta);
            }
            @Override public void onPublicReasoningSummaryDelta(String delta) {
                if (Boolean.TRUE.equals(request.getIncludeReasoning())) downstream.onPublicReasoningSummaryDelta(delta);
            }
            @Override public void onToolMessage(MediaTextGenerateRequest.TextMessageItem message) {
                downstream.onToolMessage(message);
            }
            @Override public void onSseDataLine(String line) {
                String preview = downstream.projectPublicTextPreview(line);
                if (preview != null && !preview.isEmpty()) downstream.onPublicTextPreviewDelta(preview);
                downstream.onSseDataLine(frameReceipt(line));
            }
            @Override public void onUsage(java.util.Map<String, Object> usage) { downstream.onUsage(usage); }
            @Override public void onError(String error, Throwable cause) {
                if (ended) return; ended = true; downstream.onError(error, cause);
            }
            @Override public void onFailure(TextProviderFailureEvidence failure) {
                if (ended) return; ended = true; downstream.onFailure(failure);
            }
            @Override public void onComplete() {
                if (ended) return; ended = true; downstream.onComplete();
            }
        });
    }

    private static String frameReceipt(String line) {
        if (line == null) return "";
        try {
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(line.getBytes(StandardCharsets.UTF_8)));
            return JSONObject.of("protectionVersion", PromptConfidentialityPolicy.VERSION, "frameSha256", digest).toJSONString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
