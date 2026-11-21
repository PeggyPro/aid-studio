package com.aid.skill.executor;

/** Skill实时执行回调。 */
public interface SkillExecutionCallbacks {
    void onTaskPrepared(long taskId);

    /** Provider-returned thinking; opt-in consumer, separate from public summaries and final text. */
    default void onProviderReasoningDelta(String content) {}
    default void onDetached(long taskId) { }
    void onPublicReasoningSummaryDelta(String content);
    void onDelta(String content);
    void onDone(String fullText);
    void onFailed(String message);
}
