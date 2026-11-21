package com.aid.assistant.scope;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.source.domain.AidAssistantSourceImport;
import com.aid.assistant.source.service.AssistantDocumentAccess;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** DOCUMENT is an exact session-owned, finalized source, independent of editable SCRIPT. */
@Component
public class DocumentRuntimeScopeAccess implements RuntimeScopeAccess {
    @Resource private AssistantDocumentAccess access;

    @Override public boolean supports(String kind) { return "DOCUMENT".equals(kind); }

    @Override public ResolvedContextRef resolvePinned(Long userId, AssistantRequests.ContextRef requested) {
        AidAssistantSourceImport document = access.require(userId, requested.getDocumentId()).document();
        if (!Objects.equals(String.valueOf(document.getVersion()), requested.getVersion())
                || !Objects.equals(document.getContentHash(), requested.getContentHash())) throw stale();
        return resolved(document, requested);
    }

    @Override public ResolvedContextRef resolveCurrent(Long userId, AssistantRequests.ContextRef identity) {
        return resolved(access.require(userId, identity.getDocumentId()).document(), identity);
    }

    private ResolvedContextRef resolved(AidAssistantSourceImport document, AssistantRequests.ContextRef identity) {
        if (identity.getSelection() != null || identity.getScriptId() != null || identity.getWorkspaceId() != null
                || identity.getObjectId() != null || identity.getEntityId() != null || identity.getEpisodeId() != null) throw stale();
        AssistantRequests.ContextRef ref = new AssistantRequests.ContextRef();
        ref.setKind("DOCUMENT"); ref.setDocumentId(document.getId()); ref.setDocumentSessionId(document.getSessionId());
        ref.setProjectId(document.getProjectId() == 0 ? null : document.getProjectId());
        ref.setVersion(String.valueOf(document.getVersion())); ref.setContentHash(document.getContentHash());
        ref.setReason(identity.getReason());
        return new ResolvedContextRef(ref, document.getSourceRevision(), "READ");
    }

    private AssistantServiceException stale() {
        return new AssistantServiceException(409, "ASSISTANT_CONTEXT_STALE", "文档上下文已变更", "DOCUMENT_SOURCE_CHANGED");
    }
}
