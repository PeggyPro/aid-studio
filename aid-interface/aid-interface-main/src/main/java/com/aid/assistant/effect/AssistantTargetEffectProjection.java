package com.aid.assistant.effect;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.vo.AssistantTargetViews;

/** A verified durable effect target before current permission and revision resolution. */
public record AssistantTargetEffectProjection(String effectKind,
                                              AssistantTargetViews.TargetRef resultTargetRef,
                                              AssistantRequests.ContextRef targetIdentity) {
}
