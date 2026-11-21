package com.aid.assistant.source.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.source.domain.AidAssistantSourceImport;
import com.aid.assistant.source.mapper.AidAssistantSourceImportMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Re-authorizes the parent session before exposing one immutable document identity. */
@Component
public class AssistantDocumentAccess {
    @Resource private AidAssistantSourceImportMapper documents;
    @Resource private AidRuntimeSourceRevisionMapper revisions;
    // Core constructs the context registry. Scope authorization itself checks only the parent
    // project/episode/workspace; delaying this reference avoids a bean construction cycle.
    @Lazy @Resource private AssistantCoreService core;

    public Snapshot require(Long userId, Long documentId) {
        if (userId == null || userId < 1 || documentId == null || documentId < 1) throw invalid();
        AidAssistantSourceImport document = documents.selectOne(Wrappers.<AidAssistantSourceImport>lambdaQuery()
                .select(AidAssistantSourceImport::getId, AidAssistantSourceImport::getUserId,
                        AidAssistantSourceImport::getSessionId, AidAssistantSourceImport::getProjectId,
                        AidAssistantSourceImport::getStatus, AidAssistantSourceImport::getVersion,
                        AidAssistantSourceImport::getSourceRevisionId, AidAssistantSourceImport::getSourceRevision,
                        AidAssistantSourceImport::getContentHash, AidAssistantSourceImport::getCharCount,
                        AidAssistantSourceImport::getWordCount)
                .eq(AidAssistantSourceImport::getId, documentId).eq(AidAssistantSourceImport::getUserId, userId)
                .eq(AidAssistantSourceImport::getDelFlag, "0").last("LIMIT 1"));
        if (document == null || !"FINALIZED".equals(document.getStatus())) throw invalid();
        core.authorizeSession(userId, document.getSessionId(), false);
        AidRuntimeSourceRevision revision = revisions.selectAuthorized(userId, document.getSourceRevisionId());
        String scope = SecureUtil.sha256("DOCUMENT|" + userId + "|" + document.getSessionId() + "|" + document.getProjectId());
        if (revision == null || !"ACTIVE".equals(revision.getStatus())
                || !"DOCUMENT".equals(revision.getSourceKind())
                || !Objects.equals(revision.getSourceObjectId(), String.valueOf(documentId))
                || !Objects.equals(revision.getSourceRevision(), document.getSourceRevision())
                || !Objects.equals(revision.getContentHash(), document.getContentHash())
                || !Objects.equals(revision.getAccessScopeDigest(), scope)
                || !Objects.equals(revision.getCharCount(), document.getCharCount())) throw invalid();
        return new Snapshot(document, revision);
    }

    private AssistantServiceException invalid() {
        return new AssistantServiceException(409, "DOCUMENT_SOURCE_UNAVAILABLE", "文档来源不可用", null);
    }

    public record Snapshot(AidAssistantSourceImport document, AidRuntimeSourceRevision revision) { }
}
