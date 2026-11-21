package com.aid.assistant.service;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import java.util.Map;

/** The assistant entry belongs to a conversation, not to a server instance. */
public final class AssistantEntryTransport {
    private AssistantEntryTransport() { }

    public static boolean supported(String value) {
        return "MAIN".equals(value) || "CANVAS".equals(value);
    }

    public static String require(String value) {
        if (!supported(value)) {
            throw new AssistantServiceException(422, "ASSISTANT_SCOPE_UNAVAILABLE",
                    "当前入口不可用", Map.of("field", "transport"));
        }
        return value;
    }

    public static String resolve(String supplied, Long workspaceId, String moduleCode) {
        return supplied == null ? legacy(workspaceId, moduleCode) : require(supplied);
    }

    public static String of(AidAssistantSession session) {
        if (session == null) throw new IllegalArgumentException("Assistant session is required");
        return resolve(session.getEntryTransport(), session.getWorkspaceId(), session.getModuleCode());
    }

    /** Compatibility for clients and conversations created before entry was persisted. */
    private static String legacy(Long workspaceId, String moduleCode) {
        return workspaceId != null && workspaceId > 0 || "SPATIAL_PREVIS".equals(moduleCode)
                ? "CANVAS" : "MAIN";
    }
}
