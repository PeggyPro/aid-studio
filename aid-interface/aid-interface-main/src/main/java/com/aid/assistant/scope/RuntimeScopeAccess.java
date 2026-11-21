package com.aid.assistant.scope;

import com.aid.assistant.dto.AssistantRequests;

/** Authoritative access adapter for scoped assistant context. */
public interface RuntimeScopeAccess
{
    boolean supports(String kind);

    ResolvedContextRef resolvePinned(Long userId, AssistantRequests.ContextRef contextRef);

    ResolvedContextRef resolveCurrent(Long userId, AssistantRequests.ContextRef contextIdentity);
}
