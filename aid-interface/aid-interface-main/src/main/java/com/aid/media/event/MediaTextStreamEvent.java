package com.aid.media.event;

import org.springframework.context.ApplicationEvent;

/** Trusted media-worker signal carrying public text or a provider-declared public reasoning summary. */
public class MediaTextStreamEvent extends ApplicationEvent {
    private final Long taskId;
    private final Long userId;
    private final String phase;
    private final String content;
    private final String reasoningKind;

    public MediaTextStreamEvent(Object source, Long taskId, Long userId, String phase, String content,
                                String reasoningKind) {
        super(source);
        this.taskId = taskId;
        this.userId = userId;
        this.phase = phase;
        this.content = content;
        this.reasoningKind = reasoningKind;
    }
    public Long getTaskId() { return taskId; }
    public Long getUserId() { return userId; }
    public String getPhase() { return phase; }
    public String getContent() { return content; }
    public String getReasoningKind() { return reasoningKind; }
}
