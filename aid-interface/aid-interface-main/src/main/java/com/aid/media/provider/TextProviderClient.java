package com.aid.media.provider;

import com.aid.common.error.TaskErrorSnapshot;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文本生成协议适配器：与 {@link ImageProviderClient} 同级，由编排层按 protocol / 模型名路由。
 */
public interface TextProviderClient {

    // 业务含义：同步 submit 聚合时允许写入 raw 的最大字符数，与流式接口审计上限一致。
    int TEXT_SYNC_RAW_SNAPSHOT_CAP = 100_000;

    // 业务含义：落库 aid_media_task.protocol，供扣费与排障追踪。
    String protocol();

    // 业务含义：与 protocol() 忽略大小写相等时命中显式协议路由。
    default boolean supportsProtocol(String protocol) {
        return protocol != null && protocol().equalsIgnoreCase(protocol);
    }

    /** Whether this exact protocol adapter transports a caller-supplied JSON Schema. */
    default boolean supportsNativeNonStream(MediaTextGenerateRequest request) {
        return false;
    }

    default void validateNativeNonStream(MediaTextGenerateRequest request) {
        if (Boolean.TRUE.equals(request.getRequireNativeNonStream())
                && !supportsNativeNonStream(request)) {
            throw new com.aid.common.exception.ServiceException("当前模型协议不支持所选的非流式调用方式");
        }
    }

    default boolean supportsNativeJsonSchema() {
        return false;
    }

    /** Whether this exact protocol adapter transports caller-supplied native function tools. */
    default boolean supportsNativeToolCalling() {
        return false;
    }

    // 业务含义：模型名包含协议关键字（去掉 -text）时的弱匹配，用于多模型族自动选路。
    default boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String marker = protocol().toLowerCase()
            .replace("-text", "")
            .replace("_text", "");
        return modelName.toLowerCase().contains(marker);
    }

    /** Pure provider contract validation; no network I/O or external mutation. */
    default void validateRequest(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
    }

    /**
     * Validate provider-side configuration needed for a real upstream call (credential, endpoint and
     * upstream model identity). Quote-only validation must not call this hook. Submission paths call it
     * after idempotency reuse has been resolved and before a task is created or any amount is frozen.
     */
    default void validateProviderConfiguration(AiModelConfigVo modelConfig,
                                               MediaTextGenerateRequest request) {
    }

    // 业务含义：上游一律走流式拉取，本方法将增量聚合为整段后写入 directText（同步 JSON 接口复用）。
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        return submit(modelConfig, request, null, null);
    }

    /** Queue workers may observe provider-declared public summaries without retaining an HTTP sink. */
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                                        java.util.function.Consumer<String> reasoningObserver) {
        return submit(modelConfig, request, null, reasoningObserver);
    }

    /**
     * Queue and recovery workers may forward body deltas and provider-declared public summaries while
     * this method still performs one upstream stream and returns the same aggregated result.
     */
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                                        java.util.function.Consumer<String> textObserver,
                                        java.util.function.Consumer<String> reasoningObserver) {
        return submit(modelConfig, request, textObserver, reasoningObserver, null);
    }

    /** The server-supplied projection strategy selects display text; the decorator forwards its display-only output. */
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                                        java.util.function.Consumer<String> textObserver,
                                        java.util.function.Consumer<String> reasoningObserver,
                                        java.util.function.Function<String, String> previewProjector) {
        return submit(modelConfig, request, textObserver, reasoningObserver, null, previewProjector);
    }

    /** Keep provider-returned thinking and public summaries distinct while queue workers forward them. */
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                                        java.util.function.Consumer<String> textObserver,
                                        java.util.function.Consumer<String> reasoningObserver,
                                        java.util.function.Consumer<String> providerReasoningObserver,
                                        java.util.function.Function<String, String> previewProjector) {
        StringBuilder aggregated = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        AtomicReference<String> errorRef = new AtomicReference<>();
        AtomicReference<String> errorDetail = new AtomicReference<>();
        // 业务含义：捕获 onUsage 回调（input_tokens/output_tokens/total_tokens），
        // 同步 submit 路径同样要带出真实 token usage 给 settleBilling，避免按预扣封顶结算。
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        AtomicReference<MediaTextGenerateRequest.TextMessageItem> toolMessage = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        final int maxRawChars = TEXT_SYNC_RAW_SNAPSHOT_CAP;
        try {
            streamChat(modelConfig, request, new TextStreamCallbacks() {
                @Override
                public void onDelta(String textDelta) {
                    if (textDelta != null) {
                        aggregated.append(textDelta);
                        if (textObserver != null) textObserver.accept(textDelta);
                    }
                }

                @Override
                public String projectPublicTextPreview(String eventJson) {
                    return previewProjector == null ? "" : previewProjector.apply(eventJson);
                }

                @Override
                public void onPublicTextPreviewDelta(String delta) {
                    if (textObserver != null && delta != null) textObserver.accept(delta);
                }

                @Override
                public void onProviderReasoningDelta(String content) {
                    if (providerReasoningObserver != null && Boolean.TRUE.equals(request.getIncludeReasoning())
                            && content != null) providerReasoningObserver.accept(content);
                }

                @Override
                public void onPublicReasoningSummaryDelta(String reasoningDelta) {
                    if (reasoningObserver != null && Boolean.TRUE.equals(request.getIncludeReasoning())
                            && reasoningDelta != null) {
                        reasoningObserver.accept(reasoningDelta);
                    }
                    // Public summaries are transient; they never enter directText or raw here.
                }

                @Override
                public void onToolMessage(MediaTextGenerateRequest.TextMessageItem message) {
                    toolMessage.set(message);
                }

                @Override
                public void onSseDataLine(String dataLine) {
                    if (raw.length() < maxRawChars && dataLine != null) {
                        int room = maxRawChars - raw.length();
                        if (room <= 0) {
                            return;
                        }
                        String chunk = dataLine.length() <= room ? dataLine : dataLine.substring(0, room);
                        raw.append(chunk).append('\n');
                    }
                }

                @Override
                public void onFailure(TextProviderFailureEvidence failure) {
                    if (failure == null) {
                        onError("上游请求失败", null);
                        return;
                    }
                    usageRef.updateAndGet(current -> ProviderUsageSupport.merge(
                            current, failure.usage()));
                    if (TaskErrorSnapshot.read(failure.errorDetailJson()) != null) {
                        errorDetail.set(failure.errorDetailJson());
                    }
                    errorRef.compareAndSet(null, failure.message());
                }

                @Override
                public void onError(String message, Throwable cause) {
                    if (cause instanceof com.aid.common.exception.ServiceException exception) errorDetail.set(exception.getTaskErrorJson());
                    if (errorRef.get() == null) {
                        errorRef.set(StringUtils.defaultIfBlank(message, cause != null ? cause.getMessage() : "流式错误"));
                    }
                }

                @Override
                public void onComplete() {
                    completed.set(true);
                }

                @Override
                public void onUsage(Map<String, Object> usage) {
                    usageRef.updateAndGet(current -> ProviderUsageSupport.merge(current, usage));
                }
            });
        } catch (IOException | RuntimeException e) {
            return new TextProviderFailureEvidence(ProviderErrorSanitizer.safeMessage(e.getMessage(), "上游请求失败"),
                    null, e instanceof com.aid.common.exception.ServiceException exception
                    ? exception.getTaskErrorJson() : errorDetail.get(), usageRef.get()).toSubmitResult();
        }
        if (errorRef.get() != null) {
            return new TextProviderFailureEvidence(errorRef.get(), null, errorDetail.get(), usageRef.get()).toSubmitResult();
        }
        if (!completed.get()) return ProviderSubmitResult.builder()
                .rawResponse("上游响应未完整结束").usage(usageRef.get()).build();
        boolean hasTools = toolMessage.get() != null && toolMessage.get().getToolCalls() != null
                && !toolMessage.get().getToolCalls().isEmpty();
        if (StringUtils.isBlank(aggregated.toString()) && !hasTools)
            return TextFailureBillingPolicy.completedEmptyResponse(usageRef.get()).toSubmitResult();
        String rawStr = raw.length() >= maxRawChars
            ? raw + "\n...[truncated]"
            : raw.toString();
        return ProviderSubmitResult.builder()
            .directText(aggregated.toString())
            .toolMessage(toolMessage.get())
            .rawResponse(rawStr)
            .usage(usageRef.get())
            .build();
    }

    // 业务含义：对上游发起流式 Chat Completions 并在回调中吐出增量。
    void streamChat(AiModelConfigVo modelConfig, MediaTextGenerateRequest request, TextStreamCallbacks callbacks) throws IOException;

    /**
     * 普通非流式文本生成（stream=false）：一次 HTTP 请求拿到完整响应，稳定解析 usage。
     * 适用于提取/billingExempt 等需要稳定 token 用量的场景，不依赖 SSE 末尾 usage chunk。
     * 默认实现委托 submit()（流式聚合），各 provider 可覆盖为真正的非流式调用。
     */
    default ProviderSubmitResult chatSync(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        return submit(modelConfig, request);
    }

    // 业务含义：同步 Chat 无远端轮询时占位返回成功，保持接口形状统一。
    ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId);
}
