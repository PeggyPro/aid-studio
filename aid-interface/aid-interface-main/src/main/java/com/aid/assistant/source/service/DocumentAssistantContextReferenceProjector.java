package com.aid.assistant.source.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.service.AssistantContextReferenceProjector;
import com.aid.assistant.source.domain.AidAssistantSourceImport;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Exposes authorized immutable source identifiers, without loading or regenerating the document. */
@Component
public class DocumentAssistantContextReferenceProjector implements AssistantContextReferenceProjector {
    private final AssistantDocumentAccess access;

    public DocumentAssistantContextReferenceProjector(AssistantDocumentAccess access) {
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public boolean supports(String kind) {
        return "DOCUMENT".equals(kind);
    }

    @Override
    public JSONObject project(Long userId, ResolvedContextRef reference, int ordinal) {
        if (reference == null || reference.contextRef() == null || ordinal < 0
                || !"READ".equals(reference.access())) throw unavailable();
        AssistantRequests.ContextRef ref = reference.contextRef();
        if (!supports(ref.getKind())) throw unavailable();
        AssistantDocumentAccess.Snapshot snapshot = access.require(userId, ref.getDocumentId());
        AidAssistantSourceImport document = snapshot.document();
        Long projectId = document.getProjectId() == null || document.getProjectId() == 0
                ? null : document.getProjectId();
        if (!Objects.equals(ref.getDocumentId(), document.getId())
                || !Objects.equals(ref.getDocumentSessionId(), document.getSessionId())
                || !Objects.equals(ref.getProjectId(), projectId)
                || !Objects.equals(ref.getVersion(), String.valueOf(document.getVersion()))
                || !Objects.equals(ref.getContentHash(), document.getContentHash())
                || !Objects.equals(reference.contentRef(), document.getSourceRevision())
                || snapshot.revision().getId() == null || snapshot.revision().getId() < 1
                || !Objects.equals(document.getSourceRevisionId(), snapshot.revision().getId())) {
            throw unavailable();
        }
        JSONObject result = new JSONObject();
        result.put("ordinal", ordinal);
        result.put("source", "DOCUMENT");
        result.put("semanticSource", "IMPORTED_DOCUMENT");
        result.put("documentId", String.valueOf(document.getId()));
        result.put("version", String.valueOf(document.getVersion()));
        result.put("sourceRevisionId", String.valueOf(snapshot.revision().getId()));
        result.put("sourceKind", "DOCUMENT");
        result.put("sourceObjectId", String.valueOf(document.getId()));
        result.put("sourceRevision", document.getSourceRevision());
        result.put("contentHash", document.getContentHash());
        result.put("charCount", document.getCharCount());
        result.put("wordCount", document.getWordCount());
        result.put("chunkCount", snapshot.revision().getChunkCount());
        result.put("readPolicy", "This is an authorized immutable document identity, not a receipt that its text was read. "
                + "Use sourceRevisionId with the currently available source-reading or processing tools. "
                + "Do not substitute documentId, a canvas node ID or a guessed ID for sourceRevisionId.");
        return result;
    }

    private AssistantServiceException unavailable() {
        return new AssistantServiceException(409, "ASSISTANT_CONTEXT_STALE",
                "文档引用已变化，请更新引用后继续", "DOCUMENT_SOURCE_CHANGED");
    }
}
