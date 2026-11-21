package com.aid.media.event;

/** Internal, in-process provider turn. Never publish its body to logs or client events. */
public record MediaTextTurnCapturedEvent(Long taskId, Long userId, String bizTaskType,
                                         Long bizTaskId, String protocol, String turnJson) {
    @Override public String toString() {
        return "MediaTextTurnCapturedEvent[taskId=" + taskId + ", body=redacted]";
    }
}
