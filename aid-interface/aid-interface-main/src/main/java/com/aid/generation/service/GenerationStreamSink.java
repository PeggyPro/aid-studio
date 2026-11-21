package com.aid.generation.service;

/** 通用文本生成的流式事件出口。 */
public interface GenerationStreamSink {
    void onPrepared(long taskId, Long sessionId, Long messageId);
    void onDelta(String content);
    default void onPublicReasoningSummaryDelta(String content) { }
    void onDone(String fullText);
    void onDetached(long taskId);
    void onFailed(String message);
}
