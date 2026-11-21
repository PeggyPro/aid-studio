package com.aid.media.provider;

/**
 * 文本上游流式回调：由 {@link TextProviderClient#streamChat} 在解析 SSE 时驱动，编排层可转发到 SSE 或聚合为整段。
 */
public interface TextStreamCallbacks {

    /** 注册当前上游响应体，供真正取消时从其他线程主动关闭阻塞读取。 */
    default void onResponseBody(AutoCloseable responseBody) {}

    /** Internal diagnostics only. Consumers must not mutate or retain the frame or its private contents. */
    default void onProtocolFrame(com.fasterxml.jackson.databind.JsonNode frame) {}

    /** The upstream explicitly emitted its terminal marker. This is not a business success signal. */
    default void onProtocolEndMarker() {}

    /** Server-owned strategy selects public body text; it must never publish raw frames itself. */
    default String projectPublicTextPreview(String eventJson) { return ""; }

    /** Display-only projection, separate from provider result aggregation and tool execution. */
    default void onPublicTextPreviewDelta(String delta) {}

    /** Provider-returned thinking; opt-in consumer, separate from public summaries and final text. */
    default void onProviderReasoningDelta(String content) {}

    // 业务含义：模型输出的可见正文增量片段（多段拼接即为完整回复）。
    void onDelta(String textDelta);

    /**
     * 上游协议明确标记为可公开展示的推理摘要增量。原始 reasoning/thinking 字段不得调用此回调；
     * 摘要仅用于本次实时响应，不得写入任务快照、对话历史或业务记录。
     */
    default void onPublicReasoningSummaryDelta(String summaryDelta) {}

    /** 完整且已结束的助手工具调用消息；调用方负责工具执行和续轮。 */
    default void onToolMessage(com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem message) {}

    // 业务含义：单条 SSE data 行原文（不含 "data: " 前缀），用于审计或排障时落库截断快照。
    void onSseDataLine(String dataLine);

    // 业务含义：流式链路异常（HTTP 非 200、JSON 解析失败、上游业务错误体等）。
    void onError(String message, Throwable cause);

    /**
     * 上游返回了可判定的失败证据。HTTP 状态、错误快照和 usage 必须作为一个整体传递，
     * 避免只转发安全短文案后把明确拒绝误判为结果未知。
     */
    default void onFailure(TextProviderFailureEvidence failure) {
        if (failure == null) {
            onError("上游请求失败", null);
            return;
        }
        if (ProviderUsageSupport.hasAnyProviderUsage(failure.usage())) {
            onUsage(failure.usage());
        }
        onError(failure.message(), failure.asException());
    }

    // 业务含义：上游正常结束（读到 [DONE] 或流关闭且无未处理错误）。
    void onComplete();

    /**
     * 上游返回的 token usage 数据（SSE 最后一个 chunk 中的 usage 对象）。
     * 默认空实现，实现类按需覆写。
     */
    default void onUsage(java.util.Map<String, Object> usage) {}
}
