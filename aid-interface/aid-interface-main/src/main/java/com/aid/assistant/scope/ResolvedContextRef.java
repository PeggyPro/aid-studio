package com.aid.assistant.scope;

import com.aid.assistant.dto.AssistantRequests;

/** Server-authoritative context reference and immutable content identity. */
public record ResolvedContextRef(AssistantRequests.ContextRef contextRef,
                                 String contentRef,
                                 String access)
{
}
