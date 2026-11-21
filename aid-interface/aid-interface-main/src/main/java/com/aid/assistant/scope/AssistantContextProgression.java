package com.aid.assistant.scope;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.runtime.domain.AidRuntimeRun;
import java.util.List;

/** Optional read-only, receipt-proven execution-state progression; authority remains immutable. */
public interface AssistantContextProgression {
    boolean supports(AidAssistantSession session);
    Progressed resolvePinned(Long userId, AidAssistantSession session, AidRuntimeRun run,
                             String authorityManifestJson);
    /** Optional proof that only presentation changed after the Agent observed these revisions. */
    default boolean matchesExpectedRevisions(Long userId, AidAssistantSession session, AidRuntimeRun run,
                                             com.alibaba.fastjson2.JSONArray observed,
                                             String executionManifestJson) {
        return false;
    }
    record Progressed(List<ResolvedContextRef> authorityRefs, List<ResolvedContextRef> executionRefs) { }
}
