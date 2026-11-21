package com.aid.media.provider.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.media.constants.GeminiConstants;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.GeminiFileUploadSupport;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ReasoningContentSanitizer;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.provider.TextFinishReasonSupport;
import com.aid.media.provider.TextFailureBillingPolicy;
import com.aid.media.provider.TextStreamCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Google Gemini 文本 Provider：REST :generateContent 非流式（普通 JSON 请求/响应）。
 */
@Slf4j
@Component
public class GeminiTextProviderClient implements TextProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HTTP_TIMEOUT_MINUTES = 10;
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    private static final int MAX_SYNC_BODY_BYTES = 50 * 1024 * 1024;
    private static final long MAX_STREAM_BODY_CHARS = 50L * 1024L * 1024L;
    private static final int MAX_STREAM_LINE_CHARS = 50 * 1024 * 1024;

    /**
     * 共享 HttpClient 实例，避免每次请求都创建新的连接池/Selector 线程。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
            .build();

    @Override
    public String protocol() {
        return GeminiConstants.PROTOCOL_TEXT;
    }

    @Override
    public boolean supportsModel(String modelName) {
        if (TextProviderClient.super.supportsModel(modelName)) {
            return true;
        }
        String n = StringUtils.defaultString(modelName).toLowerCase();
        return n.contains(GeminiConstants.MODEL_HINT_GEMINI);
    }

    @Override
    public void validateProviderConfiguration(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        if (modelConfig == null) {
            throw TextFailureBillingPolicy.notSent("模型未配置");
        }
        if (StringUtils.isBlank(modelConfig.getApiKey())) {
            throw TextFailureBillingPolicy.notSent(GeminiConstants.ERROR_API_KEY_EMPTY);
        }
        try {
            String url = buildGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(),
                    resolveEffectiveModel(modelConfig, request));
            HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(GeminiConstants.HEADER_API_KEY, modelConfig.getApiKey())
                    .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException invalidUrl) {
            throw TextFailureBillingPolicy.notSent("模型服务地址配置错误");
        }
    }

    @Override
    public void streamChat(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                           TextStreamCallbacks callbacks) throws IOException {
        try (GeminiFileUploadSupport.Session ignored = GeminiFileUploadSupport.prepare(modelConfig, request)) {
            streamChatPrepared(modelConfig, request, callbacks);
        } catch (com.aid.common.exception.ServiceException error) {
            callbacks.onError(error.getMessage(), error);
        }
    }

    private void streamChatPrepared(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                                    TextStreamCallbacks callbacks) throws IOException {
        com.aid.media.provider.TextOutputLimitResolver.normalize(request, modelConfig);
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            callbacks.onFailure(TextFailureBillingPolicy.notSentEvidence(
                    GeminiConstants.ERROR_API_KEY_EMPTY));
            return;
        }
        String model = resolveEffectiveModel(modelConfig, request);
        // 业务含义：完整 URL = {base_url}{api_suffix}{model}:generateContent，与 Gemini 官方文档示例一致
        String url = buildStreamGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(), model);
        Map<String, Object> body = buildRequestBody(modelConfig, request);
        String json = MAPPER.writeValueAsString(
                com.aid.model.definition.ModelConfiguredRequestBody.applyAfterRuntimeNormalization(
                        modelConfig, body, request,
                        TextReasoningOptionsResolver.RuntimeNormalization.GEMINI));
        log.info("Gemini 文本流式提交, url={}, model={}, contentsSize={}", url, model,
                ((List<?>) body.getOrDefault("contents", List.of())).size());
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
                .header(GeminiConstants.HEADER_API_KEY, apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(com.aid.diagnostics.DiagnosticCapture.outboundBody(json), StandardCharsets.UTF_8))
                .build();
        HttpResponse<Object> resp;
        try {
            resp = com.aid.diagnostics.DiagnosticHttp.send(client, req,
                    com.aid.media.provider.BoundedResponseBody.inputStreamOrError(MAX_ERROR_BODY_BYTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbacks.onError("请求被中断", e);
            return;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            com.aid.media.provider.BoundedResponseBody.Utf8Read errorBody =
                    com.aid.media.provider.BoundedResponseBody.requireRead(resp.body());
            captureBoundedDiagnostic(resp, errorBody);
            String response = errorBody.body();
            log.error("Gemini 文本流式 HTTP 失败, url={}, status={}, bodyLen={}",
                    url, resp.statusCode(), response.length());
            String safeMessage = errorBody.complete()
                    ? ProviderErrorSanitizer.fromHttp(resp.statusCode(), response)
                    : "上游错误响应过大";
            Map<String, Object> usage = errorBody.complete()
                    ? parseHttpFailureUsage(response, isReasoningProvablyDisabled(modelConfig, body))
                    : null;
            callbacks.onFailure(TextFailureBillingPolicy.httpFailureEvidence(
                    resp.statusCode(), safeMessage, usage, errorBody.complete()));
            return;
        }
        boolean modelCompleted = false;
        String terminalFailure = null;
        boolean invalidOutput = false;
        Map<String, Object> observedUsage = Map.of();
        try (com.aid.media.provider.BoundedResponseBody.Utf8LineReader lines =
                     new com.aid.media.provider.BoundedResponseBody.Utf8LineReader(
                             com.aid.media.provider.BoundedResponseBody.requireInputStream(resp.body()),
                             MAX_STREAM_BODY_CHARS, MAX_STREAM_LINE_CHARS)) {
            String line;
            while ((line = lines.readLine()) != null) {
                String data = StringUtils.defaultString(line).trim();
                if (data.isEmpty() || data.startsWith(":")) {
                    continue;
                }
                if (data.startsWith("data:")) {
                    data = data.substring(5).trim();
                }
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                callbacks.onSseDataLine(ReasoningContentSanitizer.sanitizeJson(data));
                JsonNode root = MAPPER.readTree(data);
                Map<String, Object> usage = normalizeUsage(root.path("usageMetadata"),
                        isReasoningProvablyDisabled(modelConfig, body));
                if (!usage.isEmpty()) {
                    observedUsage = com.aid.media.provider.ProviderUsageSupport.merge(observedUsage, usage);
                    callbacks.onUsage(usage);
                }
                String finishReason = resolveFinishReason(root);
                if (StringUtils.isNotBlank(finishReason)) {
                    if (!TextFinishReasonSupport.isRecognizedGeminiFinish(finishReason)) {
                        callbacks.onError("上游终止异常", null);
                        return;
                    }
                    modelCompleted = true;
                    String finishError = resolveFinishError(root);
                    if (finishError != null) {
                        terminalFailure = finishError;
                        invalidOutput = TextFinishReasonSupport.isRecoverableGeminiOutputFailure(finishReason);
                    }
                }
                if (containsUnsupportedPart(root) && terminalFailure == null) {
                    terminalFailure = "生成方式不支持";
                    invalidOutput = true;
                }
                if (terminalFailure == null) emitStreamText(root, callbacks);
            }
        } catch (com.aid.media.provider.BoundedResponseBody.LimitExceededException limit) {
            log.error("Gemini 文本流式响应超过大小上限, model={}, message={}",
                    model, limit.getMessage());
            callbacks.onError("响应超限", null);
            return;
        } catch (Exception e) {
            log.error("Gemini 文本流式解析失败, model={}, errorType={}",
                    model, e.getClass().getSimpleName());
            callbacks.onError("解析响应失败", null);
            return;
        }
        if (modelCompleted && terminalFailure != null) callbacks.onFailure(invalidOutput
                ? TextFailureBillingPolicy.completedInvalidResponse(terminalFailure, null, observedUsage)
                : TextFailureBillingPolicy.completedResponseFailure(terminalFailure, observedUsage));
        else if (modelCompleted) callbacks.onComplete();
        else callbacks.onError("上游响应未完整结束", null);
    }

    /**
     * 非流式文本生成：Gemini 本身已是 :generateContent 非流式，直接复用现有逻辑，
     * 返回 ProviderSubmitResult（含 directText / rawResponse / usage），不再走 streamChat→callback 中转。
     */
    @Override
    public boolean supportsNativeNonStream(MediaTextGenerateRequest request) {
        return true;
    }

    @Override
    public ProviderSubmitResult chatSync(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        try (GeminiFileUploadSupport.Session ignored = GeminiFileUploadSupport.prepare(modelConfig, request)) {
            return chatSyncPrepared(modelConfig, request);
        } catch (com.aid.common.exception.ServiceException error) {
            return ProviderSubmitResult.builder()
                    .rawResponse(error.getMessage())
                    .errorDetailJson(error.getTaskErrorJson())
                    .build();
        }
    }

    private ProviderSubmitResult chatSyncPrepared(AiModelConfigVo modelConfig,
                                                   MediaTextGenerateRequest request) {
        com.aid.media.provider.TextOutputLimitResolver.normalize(request, modelConfig);
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            return TextFailureBillingPolicy.notSentEvidence(
                    GeminiConstants.ERROR_API_KEY_EMPTY).toSubmitResult();
        }
        String model = resolveEffectiveModel(modelConfig, request);
        String url = buildGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(), model);
        Map<String, Object> body = buildRequestBody(modelConfig, request);
        String json;
        try {
            json = MAPPER.writeValueAsString(
                    com.aid.model.definition.ModelConfiguredRequestBody.applyAfterRuntimeNormalization(
                            modelConfig, body, request,
                            TextReasoningOptionsResolver.RuntimeNormalization.GEMINI));
        } catch (Exception e) {
            return TextFailureBillingPolicy.notSentEvidence("JSON序列化失败").toSubmitResult();
        }
        log.info("Gemini 非流式文本(NON_STREAM), url={}, model={}", url, model);
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
                .header(GeminiConstants.HEADER_API_KEY, apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(com.aid.diagnostics.DiagnosticCapture.outboundBody(json), StandardCharsets.UTF_8))
                .build();
        HttpResponse<com.aid.media.provider.BoundedResponseBody.Utf8Read> resp;
        try {
            resp = com.aid.diagnostics.DiagnosticHttp.send(client, req,
                    com.aid.media.provider.BoundedResponseBody.utf8(
                            MAX_SYNC_BODY_BYTES, MAX_ERROR_BODY_BYTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderSubmitResult.builder().rawResponse("请求被中断").build();
        } catch (IOException e) {
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        com.aid.media.provider.BoundedResponseBody.Utf8Read responseBody = resp.body();
        captureBoundedDiagnostic(resp, responseBody);
        String respBody = responseBody.body();
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("Gemini chatSync HTTP 失败, status={}, bodyLen={}", resp.statusCode(), respBody.length());
            String safeMessage = responseBody.complete()
                    ? ProviderErrorSanitizer.fromHttp(resp.statusCode(), respBody)
                    : "上游错误响应过大";
            Map<String, Object> usage = responseBody.complete()
                    ? parseHttpFailureUsage(respBody, isReasoningProvablyDisabled(modelConfig, body))
                    : null;
            return TextFailureBillingPolicy.httpFailureEvidence(
                    resp.statusCode(), safeMessage, usage,
                    responseBody.complete()).toSubmitResult();
        }
        if (!responseBody.complete()) {
            log.error("Gemini chatSync 响应超过大小上限, bodyLen={}, limit={}",
                    respBody.length(), MAX_SYNC_BODY_BYTES);
            return ProviderSubmitResult.builder().rawResponse("响应超限").build();
        }
        Map<String, Object> usage = Map.of();
        try {
            JsonNode root = MAPPER.readTree(respBody);
            usage = normalizeUsage(root.path("usageMetadata"),
                    isReasoningProvablyDisabled(modelConfig, body));
            String finishError = containsUnsupportedPart(root)
                    ? "生成方式不支持" : resolveFinishError(root);
            if (finishError != null) {
                log.info("Gemini chatSync 未完整终止: finishReason={}, usage={}",
                        resolveFinishReason(root), usage);
                if (TextFinishReasonSupport.isRecognizedGeminiFinish(resolveFinishReason(root)))
                    return (TextFinishReasonSupport.isRecoverableGeminiOutputFailure(resolveFinishReason(root))
                            ? TextFailureBillingPolicy.completedInvalidResponse(finishError, null, usage)
                            : TextFailureBillingPolicy.completedResponseFailure(finishError, usage)).toSubmitResult();
                return ProviderSubmitResult.builder().rawResponse(finishError).usage(usage).build();
            }
            // 解析文本
            StringBuilder fullText = new StringBuilder();
            StringBuilder thoughtSummary = new StringBuilder();
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.path("thought").asBoolean(false)) {
                            if (Boolean.TRUE.equals(request.getIncludeReasoning()))
                                thoughtSummary.append(part.path("text").asText(""));
                            continue;
                        }
                        String t = part.path("text").asText(null);
                        if (StringUtils.isNotEmpty(t)) {
                            fullText.append(t);
                        }
                    }
                }
            }
            log.info("Gemini chatSync 响应解析: hasText={}, usage={}", StringUtils.isNotBlank(fullText.toString()), usage);
            if (StringUtils.isBlank(fullText.toString())) {
                if ("STOP".equalsIgnoreCase(StringUtils.trimToEmpty(resolveFinishReason(root))))
                    return TextFailureBillingPolicy.completedEmptyResponse(usage).toSubmitResult();
                return ProviderSubmitResult.builder()
                        .rawResponse("响应内容为空")
                        .usage(usage.isEmpty() ? null : usage)
                        .build();
            }
            String sanitizedRaw = ReasoningContentSanitizer.sanitizeJson(respBody);
            String rawTruncated = sanitizedRaw.length() > 100_000
                    ? sanitizedRaw.substring(0, 100_000) + "\n...[truncated]" : sanitizedRaw;
            return ProviderSubmitResult.builder()
                    .directText(fullText.length() > 0 ? fullText.toString() : null)
                    .directReasoning(thoughtSummary.length() > 0 ? thoughtSummary.toString() : null)
                    .rawResponse(rawTruncated)
                    .usage(usage.isEmpty() ? null : usage)
                    .build();
        } catch (Exception e) {
            log.error("Gemini chatSync 响应解析失败, bodyLen={}, errorType={}",
                    respBody.length(), e.getClass().getSimpleName());
            return ProviderSubmitResult.builder()
                    .rawResponse("解析响应失败")
                    .usage(usage.isEmpty() ? null : usage)
                    .build();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderTaskResult.builder()
                .status("PROCESSING")
                .errorMessage("同步模型无上游查询状态")
                .querySuccessful(Boolean.FALSE)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    /**
     * 解析 Gemini :generateContent 响应：
     *   - candidates[0].content.parts[*].text：thought=true 的API思考摘要独立回调，正文 onDelta(fullText)
     *   - totalTokenCount - promptTokenCount 确定父级输出，与可见/思考子桶分别校验
     */
    private boolean parseAndEmit(String json, TextStreamCallbacks callbacks,
                                 boolean reasoningProvablyDisabled) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode usageNode = root.path("usageMetadata");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            Map<String, Object> usage = normalizeUsage(usageNode, reasoningProvablyDisabled);
            if (!usage.isEmpty()) {
                callbacks.onUsage(usage);
            }
        }
        String finishError = containsUnsupportedPart(root)
                ? "生成方式不支持" : resolveFinishError(root);
        if (finishError != null) {
            log.info("Gemini 文本未完整终止: finishReason={}", resolveFinishReason(root));
            callbacks.onError(finishError, null);
            return false;
        }
        JsonNode candidates = root.path("candidates");
        StringBuilder fullText = new StringBuilder();
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.path("thought").asBoolean(false)) {
                        String summary = part.path("text").asText("");
                        if (!summary.isEmpty()) callbacks.onPublicReasoningSummaryDelta(summary);
                        continue;
                    }
                    String t = part.path("text").asText(null);
                    if (StringUtils.isNotEmpty(t)) {
                        fullText.append(t);
                    }
                }
            }
        }
        if (fullText.length() > 0) {
            callbacks.onDelta(fullText.toString());
        }
        return true;
    }

    private String resolveFinishError(JsonNode root) {
        return TextFinishReasonSupport.geminiFailureMessage(resolveFinishReason(root));
    }

    private String resolveFinishReason(JsonNode root) {
        JsonNode candidates = root == null ? null : root.path("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode value = candidates.get(0).get("finishReason");
            return value != null && value.isTextual() ? value.asText() : null;
        }
        JsonNode blockReason = root == null ? null : root.path("promptFeedback").get("blockReason");
        return blockReason != null && blockReason.isTextual() ? blockReason.asText() : null;
    }

    private boolean containsUnsupportedPart(JsonNode root) {
        JsonNode candidates = root == null ? null : root.path("candidates");
        if (candidates == null || !candidates.isArray()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray()) {
                continue;
            }
            for (JsonNode part : parts) {
                if (part.hasNonNull("functionCall") || part.hasNonNull("executableCode")
                        || part.hasNonNull("codeExecutionResult")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 组装 Gemini 请求体：
     *   - 历史 messages 中 role=system 合并到 systemInstruction
     *   - role=user/assistant 分别映射为 contents[i].role=user/model
     *   - request.prompt 末尾追加为一条 user content
     *   - 思考参数只在模型配置或调用方明确指定时下发
     *   - 合并厂商级/模型级 extra_body 与 options（模型级覆盖厂商级、options 覆盖配置），
     *     使运营可按模型配置 thinking_level（如 Flash 系 minimal、Pro 系 low）
     *   - 模型打标 supportsJsonObject 且请求文本含 JSON 关键词时注入 responseMimeType=application/json
     */
    private Map<String, Object> buildRequestBody(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder systemBuf = new StringBuilder();
        if (request != null && request.getMessages() != null) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item == null || StringUtils.isBlank(item.getContent())
                        && (item.getParts() == null || item.getParts().isEmpty())) {
                    continue;
                }
                String role = StringUtils.defaultString(item.getRole()).trim().toLowerCase();
                if (Objects.equals("system", role)) {
                    if (systemBuf.length() > 0) {
                        systemBuf.append("\n");
                    }
                    if (StringUtils.isNotBlank(item.getContent())) {
                        systemBuf.append(item.getContent());
                    }
                    if (item.getParts() != null) {
                        item.getParts().stream().filter(Objects::nonNull)
                                .filter(part -> "text".equalsIgnoreCase(part.getType()))
                                .filter(part -> StringUtils.isNotBlank(part.getText()))
                                .forEach(part -> systemBuf.append('\n').append(part.getText()));
                    }
                    continue;
                }
                contents.add(geminiContent(Objects.equals("assistant", role) ? "model" : "user", item));
            }
        }
        if (request != null && StringUtils.isNotBlank(request.getPrompt())) {
            contents.add(geminiContent("user", request.getPrompt()));
        }
        // 业务含义：Gemini 强校验 contents 必须非空，否则返回
        // "GenerateContentRequest.contents: contents is not specified"。
        // 上游 validateTextRequest 允许"只有 system 没有 user"的请求通过（qwen/doubao 接受），
        // 这里在 Gemini provider 内做兜底：若拼完仍为空且 system 有内容，
        // 把 system 文本降级为一条 user content 同时不再下发 systemInstruction，避免重复。
        boolean systemDemoted = false;
        if (contents.isEmpty() && systemBuf.length() > 0) {
            contents.add(geminiContent("user", systemBuf.toString()));
            systemDemoted = true;
            log.info("Gemini contents 为空且仅含 system，降级为 user content 避免 400 contents is not specified");
        }
        body.put("contents", contents);
        if (systemBuf.length() > 0 && !systemDemoted) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("parts", List.of(Map.of("text", systemBuf.toString())));
            body.put("systemInstruction", sys);
        }
        Map<String, Object> generationConfig = buildGenerationConfig(modelConfig, request);
        // 结构化输出（JSON Mode）：模型打标且请求文本含 JSON 关键词时注入 responseMimeType，
        // 让上游直接返回标准 JSON，避免 ```json 包裹导致下游解析失败
        boolean structuredOutputEnabled = request != null && request.getOptions() != null
                && Boolean.parseBoolean(String.valueOf(request.getOptions().get(
                        com.aid.media.provider.StructuredOutputSupport.ENABLED_KEY)));
        boolean structuredOutputConfigured = request != null && request.getOptions() != null
                && request.getOptions().containsKey(
                        com.aid.media.provider.StructuredOutputSupport.ENABLED_KEY);
        if (structuredOutputConfigured && !structuredOutputEnabled) {
            generationConfig.remove("responseMimeType");
            generationConfig.remove("responseSchema");
            generationConfig.remove("responseJsonSchema");
        }
        if (structuredOutputEnabled) {
            com.aid.media.provider.StructuredOutputSupport.applyGeminiJsonModeIfSupported(
                    modelConfig, requestTextContainsJsonKeyword(request, systemBuf), generationConfig);
        }
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }
        return body;
    }

    /**
     * 检测本次请求全部文本（system / messages / prompt）是否含 "JSON" 关键词，
     * 作为 JSON Mode 注入的启发条件（要求 JSON 输出的业务提示词均含该词）。
     */
    private boolean requestTextContainsJsonKeyword(MediaTextGenerateRequest request, StringBuilder systemBuf) {
        if (com.aid.media.provider.StructuredOutputSupport.textContainsJsonKeyword(
                systemBuf == null ? null : systemBuf.toString())) {
            return true;
        }
        if (request == null) {
            return false;
        }
        if (com.aid.media.provider.StructuredOutputSupport.textContainsJsonKeyword(request.getPrompt())) {
            return true;
        }
        if (request.getMessages() != null) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item != null && com.aid.media.provider.StructuredOutputSupport
                        .textContainsJsonKeyword(item.getContent())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 组装 generationConfig：厂商级/模型级 extra_body 与 options 合并后白名单透传。
     */
    private Map<String, Object> buildGenerationConfig(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // 合并优先级：厂商 extra_body < 模型 extra_body < 请求 options
        Map<String, Object> options = com.aid.media.provider.OpenAiCompatiblePayloadResolver.mergeExtraBody(
                modelConfig == null ? null : modelConfig.getExtraBodyJson(),
                modelConfig == null ? null : modelConfig.getModelExtraBodyJson(),
                request != null ? request.getOptions() : null);
        options = TextReasoningOptionsResolver.resolveGemini(modelConfig, request, options);
        if (options != null) {
            for (String key : new String[]{"temperature", "topP", "topK", "maxOutputTokens",
                    "candidateCount", "stopSequences", "responseMimeType", "responseSchema"}) {
                Object v = options.get(key);
                if (v != null) {
                    generationConfig.put(key, v);
                }
            }
            // 字段名兼容：业务层统一下发 OpenAI 风格的 max_tokens（snake_case）；
            // Gemini 用 maxOutputTokens（camelCase），这里做单向映射。
            // 调用方若同时传两个键，maxOutputTokens 优先（已在上面循环里写入）。
            if (!generationConfig.containsKey("maxOutputTokens")) {
                Object snakeMaxTokens = options.get("max_tokens");
                if (snakeMaxTokens != null) {
                    generationConfig.put("maxOutputTokens", snakeMaxTokens);
                }
            }
        }
        // 思考配置只接受模型配置或内部白名单解析后的明确值；缺省不下发并沿用模型自身行为。
        Object explicitThinking = options == null ? null : options.get("thinkingConfig");
        Object explicitLevel = options == null ? null : options.get("thinking_level");
        if (explicitThinking instanceof Map) {
            generationConfig.put("thinkingConfig", explicitThinking);
        } else if (explicitLevel != null) {
            String levelStr = String.valueOf(explicitLevel).trim().toLowerCase();
            Map<String, Object> tc = new HashMap<>();
            // 业务层 disabled / off / none / 0 全部映射成 Gemini 的 thinkingBudget=0（关闭思考）
            // 其他取值（low / medium / high）直接作为 thinkingLevel 透传
            if ("disabled".equals(levelStr) || "off".equals(levelStr)
                    || "none".equals(levelStr) || "0".equals(levelStr)) {
                tc.put("thinkingBudget", 0);
            } else {
                tc.put("thinkingLevel", String.valueOf(explicitLevel));
            }
            generationConfig.put("thinkingConfig", tc);
        }
        return generationConfig;
    }

    private Map<String, Object> normalizeUsage(JsonNode usageNode, boolean reasoningProvablyDisabled) {
        Map<String, Object> usage = new HashMap<>();
        if (usageNode == null || !usageNode.isObject()) {
            return usage;
        }
        Integer input = nonNegativeInt(usageNode, "promptTokenCount");
        Integer visible = nonNegativeInt(usageNode, "candidatesTokenCount");
        Integer reasoning = nonNegativeInt(usageNode, "thoughtsTokenCount");
        Integer cached = nonNegativeInt(usageNode, "cachedContentTokenCount");
        Integer total = nonNegativeInt(usageNode, "totalTokenCount");
        boolean visiblePresent = usageNode.has("candidatesTokenCount");
        boolean reasoningPresent = usageNode.has("thoughtsTokenCount");
        boolean totalPresent = usageNode.has("totalTokenCount");
        boolean inputBucketsComplete = input != null && cached != null && cached <= input;
        Integer output = null;
        Integer normalizedVisible = visible;
        Integer normalizedReasoning = reasoning;
        boolean outputComplete = false;
        boolean outputBucketsComplete = false;
        if (totalPresent) {
            // 显式总量是权威父口径；任何非法值或子桶矛盾都禁止改用子桶回建。
            if (input != null && total != null && total >= input) {
                int outputFromTotal = total - input;
                boolean explicitBucketsConsistent = (!visiblePresent
                        || visible != null && visible <= outputFromTotal)
                        && (!reasoningPresent || reasoning != null && reasoning <= outputFromTotal)
                        && (!visiblePresent || !reasoningPresent
                        || (long) visible + reasoning == outputFromTotal);
                if (explicitBucketsConsistent) {
                    output = outputFromTotal;
                    outputComplete = true;
                    if (visiblePresent && reasoningPresent) {
                        outputBucketsComplete = true;
                    } else if (visiblePresent) {
                        normalizedReasoning = outputFromTotal - visible;
                        outputBucketsComplete = true;
                    } else if (reasoningPresent) {
                        normalizedVisible = outputFromTotal - reasoning;
                        outputBucketsComplete = true;
                    } else if (outputFromTotal == 0) {
                        normalizedVisible = 0;
                        normalizedReasoning = 0;
                        outputBucketsComplete = true;
                    }
                }
            }
        } else if (visible != null && reasoning != null
                && (long) visible + reasoning <= Integer.MAX_VALUE) {
            output = visible + reasoning;
            outputComplete = true;
            outputBucketsComplete = true;
        } else if (visible != null && !reasoningPresent && reasoningProvablyDisabled) {
            output = visible;
            normalizedReasoning = 0;
            outputComplete = true;
            outputBucketsComplete = true;
        }
        if (input != null) {
            usage.put("prompt_tokens", input);
            usage.put("input_tokens", input);
        }
        if (cached != null) {
            usage.put("cached_input_tokens", cached);
            usage.put("cache_read_input_tokens", cached);
            usage.put("cache_write_input_tokens", 0);
        }
        if (inputBucketsComplete) {
            usage.put("uncached_input_tokens", input - cached);
        }
        if (normalizedVisible != null) {
            usage.put("visible_output_tokens", normalizedVisible);
        }
        if (normalizedReasoning != null) {
            usage.put("reasoning_tokens", normalizedReasoning);
        }
        if (outputComplete) {
            usage.put("completion_tokens", output);
            usage.put("output_tokens", output);
        }
        if (total != null) {
            usage.put("total_tokens", total);
        } else if (input != null && outputComplete
                && (long) input + output <= Integer.MAX_VALUE) {
            usage.put("total_tokens", input + output);
        }
        if (usage.isEmpty()) {
            return usage;
        }
        usage.put("provider_usage_captured", input != null && outputComplete);
        usage.put("input_usage_complete", input != null);
        usage.put("output_usage_complete", outputComplete);
        usage.put("input_token_buckets_complete", inputBucketsComplete);
        usage.put("output_token_buckets_complete", outputBucketsComplete);
        return usage;
    }

    private Map<String, Object> parseHttpFailureUsage(String response,
                                                      boolean reasoningProvablyDisabled) {
        if (StringUtils.isBlank(response)) {
            return null;
        }
        try {
            Map<String, Object> usage = normalizeUsage(MAPPER.readTree(response).path("usageMetadata"),
                    reasoningProvablyDisabled);
            return usage.isEmpty() ? null : usage;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isReasoningProvablyDisabled(AiModelConfigVo modelConfig,
                                                 Map<String, Object> requestBody) {
        Map<?, ?> generationConfig = requestBody != null
                && requestBody.get("generationConfig") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> thinkingConfig = generationConfig.get("thinkingConfig") instanceof Map<?, ?> map
                ? map : Map.of();
        Object budget = thinkingConfig.get("thinkingBudget");
        if (budget != null) {
            Long parsedBudget = nonNegativeLong(budget);
            if (parsedBudget == null || parsedBudget > 0L) {
                return false;
            }
            return capabilityExplicitlyDisablesReasoning(modelConfig)
                    || !capabilityExplicitlyDisallowsReasoningDisable(modelConfig);
        }
        if (thinkingConfig.get("thinkingLevel") != null) {
            return false;
        }
        return capabilityExplicitlyDisablesReasoning(modelConfig);
    }

    private boolean capabilityExplicitlyDisablesReasoning(AiModelConfigVo modelConfig) {
        JsonNode capability = capability(modelConfig);
        JsonNode supportsReasoning = capability == null ? null : capability.get("supportsReasoning");
        return supportsReasoning != null && supportsReasoning.isBoolean()
                && !supportsReasoning.asBoolean();
    }

    private boolean capabilityExplicitlyDisallowsReasoningDisable(AiModelConfigVo modelConfig) {
        JsonNode capability = capability(modelConfig);
        JsonNode supportsDisable = capability == null ? null : capability.get("supportsReasoningDisable");
        return supportsDisable != null && supportsDisable.isBoolean() && !supportsDisable.asBoolean();
    }

    private JsonNode capability(AiModelConfigVo modelConfig) {
        if (modelConfig == null || StringUtils.isBlank(modelConfig.getCapabilityJson())) {
            return null;
        }
        try {
            JsonNode capability = MAPPER.readTree(modelConfig.getCapabilityJson());
            return capability.isObject() ? capability : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long nonNegativeLong(Object value) {
        try {
            long parsed;
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                parsed = ((Number) value).longValue();
            } else {
                String raw = String.valueOf(value).trim();
                parsed = Long.parseLong(raw);
            }
            return parsed >= 0L ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer nonNegativeInt(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long tokens = value.asLong();
        if (tokens < 0L) {
            return null;
        }
        return tokens > Integer.MAX_VALUE ? null : (int) tokens;
    }

    private void emitStreamText(JsonNode root, TextStreamCallbacks callbacks) {
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String value = part.path("text").asText(null);
                    if (StringUtils.isEmpty(value)) {
                        continue;
                    }
                    if (part.path("thought").asBoolean(false)) {
                        // Gemini thought parts are private reasoning, not public summaries.
                    } else {
                        callbacks.onDelta(value);
                    }
                }
            }
        }
    }

    private static void captureBoundedDiagnostic(HttpResponse<?> response,
                                                 com.aid.media.provider.BoundedResponseBody.Utf8Read body) {
        com.aid.diagnostics.DiagnosticCapture.transportResponse(response.statusCode(), body.body(),
                response.headers().firstValue("x-request-id").orElse(null));
        com.aid.diagnostics.DiagnosticCapture.transportComplete(body.complete());
    }

    private static Map<String, Object> geminiContent(String role, String text) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("role", role);
        c.put("parts", List.of(Map.of("text", text)));
        return c;
    }

    private static Map<String, Object> geminiContent(String role,
                                                     MediaTextGenerateRequest.TextMessageItem message) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(message.getContent())) {
            parts.add(Map.of("text", message.getContent()));
        }
        if (message.getParts() != null) {
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                if (part == null) {
                    continue;
                }
                String type = StringUtils.defaultString(part.getType()).trim().toLowerCase();
                if (Objects.equals("text", type)) {
                    parts.add(Map.of("text", part.getText()));
                    continue;
                }
                Map<String, Object> fileData = new LinkedHashMap<>();
                fileData.put("fileUri", part.getUrl());
                if (StringUtils.isNotBlank(part.getMimeType())) {
                    fileData.put("mimeType", part.getMimeType());
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("fileData", fileData);
                if (StringUtils.isNotBlank(part.getMediaResolution())) {
                    value.put("mediaResolution", Map.of("level", part.getMediaResolution()));
                }
                parts.add(value);
            }
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", role);
        content.put("parts", parts);
        return content;
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        return GeminiConstants.DEFAULT_TEXT_MODEL;
    }

    /** 构建包含受控 {model} 占位符的 Gemini 提交 URL。 */
    static String buildGenerateContentUrl(String baseUrl, String apiSuffix, String model) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("gemini text model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StringUtils.isBlank(apiSuffix)) {
            log.error("gemini text model apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        String suffix = apiSuffix.trim();
        if (!suffix.contains("{model}")) {
            if (suffix.endsWith("/")) {
                suffix = suffix + "{model}" + GeminiConstants.OPERATION_GENERATE_CONTENT;
            } else {
                return ProviderEndpointUtils.buildSubmitUrl(baseUrl, suffix);
            }
        }
        return ProviderEndpointUtils.buildModelSubmitUrl(baseUrl, suffix, model);
    }

    static String buildStreamGenerateContentUrl(String baseUrl, String apiSuffix, String model) {
        String url = buildGenerateContentUrl(baseUrl, apiSuffix, model)
                .replace(":generateContent", ":streamGenerateContent");
        return url.contains("?") ? url + "&alt=sse" : url + "?alt=sse";
    }
}
