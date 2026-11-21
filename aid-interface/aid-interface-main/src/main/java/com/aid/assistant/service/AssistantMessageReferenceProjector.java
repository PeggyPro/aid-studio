package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.scope.ResolvedContextRef;

/** Adds safe, currently authorized presentation data to a frozen message reference. */
public interface AssistantMessageReferenceProjector {
    boolean supports(String kind);

    Snapshot snapshot(Long userId, ResolvedContextRef frozen);

    Projection project(Long userId, AssistantRequests.ContextRef frozen,
                       ResolvedContextRef current, Snapshot snapshot);

    record Snapshot(String mediaKind, Long previewResourceId, String previewContentHash) { }

    record Projection(String mediaKind, String previewUrl) { }
}
