package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.model.definition.ModelConfiguredRequestBody;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TextQuotedOutputPreparationRegressionTest {
    private AiModelConfigVo model(String field, Object maximum) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setCapabilityCode("text");
        model.setModelCode("deepseek-flash");
        model.setRealModelCode("deepseek-flash");
        model.setModelExtraBodyJson(JSON.toJSONString(Map.of(field, maximum)));
        return model;
    }
    private MediaTextGenerateRequest request(String field, int cap) {
        MediaTextGenerateRequest request = new MediaTextGenerateRequest();
        request.setPrompt("制作15秒中文咖啡广告");
        request.setModelName("deepseek-flash");
        request.setOptions(new LinkedHashMap<>(Map.of(field, cap)));
        TextOutputLimitResolver.normalize(request, model(field, 65536));
        return request;
    }
    @Test void lowerQuotedCapDoesNotBecomeConfigured65536() {
        for (String field : new String[]{"max_tokens", "max_completion_tokens"}) {
            var request = request(field, 8192);
            assertEquals(8192, ModelConfiguredRequestBody.apply(model(field, 65536), Map.of(field, 8192), request).get(field));
            assertEquals(8208, request.getOptions().get(TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY));
        }
    }
    @Test void equalLegacyFixedCapStillWorks() {
        assertEquals(8192, ModelConfiguredRequestBody.apply(model("max_tokens", 8192),
                Map.of("max_tokens", 8192), Map.of()).get("max_tokens"));
    }
    @Test void cannotRaiseConfiguredMaximum() {
        assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("max_tokens", 4096),
                Map.of("max_tokens", 8192), request("max_tokens", 8192)));
    }
    @Test void missingOrDriftingQuotedEvidenceIsRejected() {
        for (String key : new String[]{TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY,
                TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY, TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
                TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY}) {
            var request = request("max_tokens", 8192);
            request.getOptions().remove(key);
            assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("max_tokens", 65536),
                    Map.of("max_tokens", 8192), request));
        }
        var request = request("max_tokens", 8192);
        request.getOptions().put(TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY, 9000);
        assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("max_tokens", 65536),
                Map.of("max_tokens", 8192), request));
    }
    @Test void arbitraryRequestObjectCannotEnableOutputOverride() {
        assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("max_tokens", 65536),
                Map.of("max_tokens", 8192), Map.of("options", request("max_tokens", 8192).getOptions())));
    }
    @Test void fractionalNegativeAndStringFixedCapsCannotBypassConflict() {
        for (Object cap : new Object[]{8192.5, -1, "65536"}) {
            assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("max_tokens", cap),
                    Map.of("max_tokens", 8192), request("max_tokens", 8192)));
        }
    }
    @Test void unrelatedAndNestedFixedConflictsRemainStrict() {
        assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model("temperature", 0.8),
                Map.of("temperature", 0.4), request("max_tokens", 8192)));
        var model = model("other", Map.of("max_tokens", 65536));
        assertThrows(ServiceException.class, () -> ModelConfiguredRequestBody.apply(model,
                Map.of("other", Map.of("max_tokens", 8192)), request("max_tokens", 8192)));
    }
    @Test void providerPreflightUsesActualWireBuilderAndDoesNotSend() {
        class CapturingClient extends GenericOpenAiCompatibleTextProviderClient {
            String body;
            @Override protected String buildBody(String model, java.util.List<Map<String,Object>> messages,
                    boolean stream, Map<String,Object> options, MediaTextGenerateRequest request) {
                body = super.buildBody(model, messages, stream, options, request);
                return body;
            }
        }
        CapturingClient client = new CapturingClient();
        var request = request("max_tokens", 8192);
        request.setStream(true);
        client.validateRequest(model("max_tokens", 65536), request);
        var wire = JSON.parseObject(client.body);
        assertEquals(8192, wire.getIntValue("max_tokens"));
        assertTrue(wire.getBooleanValue("stream"));
        assertFalse(client.body.contains("_aid_"));
        assertFalse(client.body.contains("apiKey"));
        request.setStream(false);
        client.validateRequest(model("max_tokens", 65536), request);
        assertFalse(JSON.parseObject(client.body).getBooleanValue("stream"));
    }
    @Test void preflightFindsFixedWireCollisionWithoutProviderOrCredentials() {
        var model = model("stream", false);
        var request = request("max_tokens", 8192);
        request.setStream(true);
        assertThrows(ServiceException.class, () -> new GenericOpenAiCompatibleTextProviderClient()
                .validateRequest(model, request));
    }
    @Test void nullRequestCannotReachWirePreparation() {
        assertThrows(IllegalArgumentException.class, () -> new GenericOpenAiCompatibleTextProviderClient()
                .validateRequest(model("max_tokens", 65536), null));
    }
}
