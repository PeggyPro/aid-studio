package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.service.CreativeTextChunker;
import com.aid.assistant.creative.source.CreativeSourceProvider;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.util.Date;
import java.util.Objects;

/** Short database transaction for the verified second pass over an immutable source. */
@Service
public class CreativeSourcePersistenceService {
    @Resource private AidRuntimeSourceRevisionMapper revisionMapper;
    @Resource private AidRuntimeSourceChunkMapper chunkMapper;
    @Resource private RuntimeOperationSubmissionGuard submissionGuard;

    @Transactional(rollbackFor = Exception.class)
    public CreativeViews.SourceRevision persist(Long userId, String sourceKind,
                                                 CreativeSourceProvider.AuthorizedSource source,
                                                 int maxChunkChars, CreativeTextChunker.Stats expected,
                                                 RuntimeOperationSubmissionClaim submissionClaim) {
        // This must remain the first database action in the mutation transaction.
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(submissionClaim);
        AidRuntimeSourceRevision existing = revisionMapper.selectImmutable(userId, sourceKind,
                source.sourceObjectId(), source.sourceRevision(), expected.contentHash());
        if (existing != null) return view(existing);
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            throw new ReceiptMissingException();
        }

        Date now = new Date();
        AidRuntimeSourceRevision revision = new AidRuntimeSourceRevision();
        revision.setUserId(userId);
        revision.setSourceKind(sourceKind);
        revision.setSourceObjectId(source.sourceObjectId());
        revision.setSourceRevision(source.sourceRevision());
        revision.setContentHash(expected.contentHash());
        revision.setCharCount(expected.charCount());
        revision.setWordCount(expected.wordCount());
        revision.setChunkCount(expected.chunkCount());
        revision.setAccessScopeDigest(source.accessScopeDigest());
        revision.setStatus("ACTIVE");
        revision.setDelFlag("0");
        revision.setCreateBy(String.valueOf(userId));
        revision.setCreateTime(now);
        revision.setUpdateBy(String.valueOf(userId));
        revision.setUpdateTime(now);
        revisionMapper.insert(revision);

        CreativeTextChunker.Stats actual;
        try (Reader reader = source.readerFactory().open()) {
            actual = CreativeTextChunker.scan(reader, maxChunkChars, chunk -> {
                AidRuntimeSourceChunk row = new AidRuntimeSourceChunk();
                row.setSourceRevisionId(revision.getId());
                row.setChunkIndex(chunk.index());
                row.setCharStart(chunk.charStart());
                row.setCharEnd(chunk.charEnd());
                row.setCharCount(chunk.charEnd() - chunk.charStart());
                row.setWordCount(chunk.wordCount());
                JSONObject locator = new JSONObject();
                if (StrUtil.isNotBlank(source.locatorJson())) locator.put("source", JSON.parse(source.locatorJson()));
                locator.put("charStart", chunk.charStart());
                locator.put("charEnd", chunk.charEnd());
                row.setLocatorJson(locator.toJSONString());
                row.setContentText(chunk.content());
                row.setContentHash(chunk.contentHash());
                row.setStatus("ACTIVE");
                row.setCreateBy(String.valueOf(userId));
                row.setCreateTime(now);
                chunkMapper.insert(row);
            });
        } catch (IOException error) {
            throw new IllegalStateException("Source could not be read", error);
        }
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Source revision changed while it was being ingested");
        }
        return view(revision);
    }

    private CreativeViews.SourceRevision view(AidRuntimeSourceRevision row) {
        CreativeViews.SourceRevision result = new CreativeViews.SourceRevision();
        result.setSourceRevisionId(String.valueOf(row.getId()));
        result.setSourceKind(row.getSourceKind());
        result.setSourceObjectId(row.getSourceObjectId());
        result.setSourceRevision(row.getSourceRevision());
        result.setContentHash(row.getContentHash());
        result.setCharCount(row.getCharCount());
        result.setWordCount(row.getWordCount());
        result.setChunkCount(row.getChunkCount());
        result.setStatus(row.getStatus());
        return result;
    }

    /** A replay-safe write was already claimed, but its deterministic immutable receipt is absent. */
    public static final class ReceiptMissingException extends RuntimeException {
        public ReceiptMissingException() {
            super("Creative source receipt is unavailable for reconciliation");
        }
    }
}
