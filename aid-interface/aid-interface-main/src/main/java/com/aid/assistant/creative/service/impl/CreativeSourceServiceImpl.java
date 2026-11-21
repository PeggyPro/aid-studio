package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeSourceService;
import com.aid.assistant.creative.service.CreativeTextChunker;
import com.aid.assistant.creative.source.CreativeSourceProvider;
import com.aid.assistant.creative.source.CreativeSourceProviderRegistry;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Re-authorizes a source and verifies two identical streaming passes before committing it. */
@Service
public class CreativeSourceServiceImpl implements CreativeSourceService {
    @Resource private CreativeSourceProviderRegistry providers;
    @Resource private CreativeSourcePersistenceService persistence;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private AidRuntimeSourceRevisionMapper revisionMapper;
    @Resource private AidRuntimeSourceChunkMapper chunkMapper;

    @Override
    public CreativeViews.SourceRevision ingest(Long userId, CreativeRequests.SourceIngest request,
                                               String contextManifestJson,
                                               RuntimeOperationSubmissionClaim submissionClaim) {
        if (userId == null || userId < 1 || request == null) throw new IllegalArgumentException("Invalid source request");
        requireText(request.getSourceKind(), 40, "sourceKind");
        requireText(request.getSourceObjectId(), 128, "sourceObjectId");
        requireDigest(request.getContentHash(), "contentHash");
        if (request.getSourceRevision() != null && request.getSourceRevision().length() > 128) invalid("sourceRevision");
        int chunkChars = request.getMaxChunkChars() == null ? 12000
                : Math.max(1000, Math.min(request.getMaxChunkChars(), 50000));
        String kind = request.getSourceKind().trim().toUpperCase(Locale.ROOT);
        contextAuthorizer.requireRead(contextManifestJson, kind,
                request.getSourceObjectId().trim(), request.getContentHash());
        CreativeSourceProvider.AuthorizedSource source = providers.require(kind).authorize(
                userId, request.getSourceObjectId().trim(), trim(request.getSourceRevision()));
        validateSource(source);
        CreativeTextChunker.Stats stats;
        try (Reader reader = source.readerFactory().open()) {
            stats = CreativeTextChunker.scan(reader, chunkChars, ignored -> { });
        } catch (IOException error) {
            throw new IllegalStateException("Source could not be read", error);
        }
        if (!Objects.equals(stats.contentHash(), request.getContentHash())) {
            throw new IllegalArgumentException("Source content changed after context authorization");
        }
        return persistence.persist(userId, kind, source, chunkChars, stats, submissionClaim);
    }

    @Override
    public CreativeViews.SourceChunkPage read(Long userId, CreativeRequests.SourceChunkRead request,
                                              String contextManifestJson) {
        if (userId == null || userId < 1 || request == null
                || request.getSourceRevisionId() == null || request.getSourceRevisionId() < 1) {
            invalid("sourceRevisionId");
        }
        int after = request.getAfterChunkIndex() == null ? -1 : request.getAfterChunkIndex();
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        if (after < -1 || limit < 1 || limit > 100) invalid("source chunk cursor");
        AidRuntimeSourceRevision source = revisionMapper.selectAuthorized(userId, request.getSourceRevisionId());
        if (source == null || !"ACTIVE".equals(source.getStatus())) invalid("sourceRevisionId");
        contextAuthorizer.requireRead(contextManifestJson, source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());

        int expectedStart = 0;
        if (after >= 0) {
            AidRuntimeSourceChunk previous = chunkMapper.selectAuthorizedChunk(
                    userId, source.getId(), after);
            if (previous == null || previous.getCharEnd() == null) invalid("afterChunkIndex");
            validateChunk(previous, after, previous.getCharStart());
            expectedStart = previous.getCharEnd();
        }
        List<AidRuntimeSourceChunk> rows = chunkMapper.selectAuthorizedPage(
                userId, source.getId(), after, limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        int expectedIndex = after + 1;
        List<CreativeViews.SourceChunk> chunks = new ArrayList<>();
        JSONObject legacyDocumentSource = null;
        for (AidRuntimeSourceChunk row : rows) {
            validateChunk(row, expectedIndex++, expectedStart);
            expectedStart = row.getCharEnd();
            CreativeViews.SourceChunk chunk = chunk(row);
            if ("DOCUMENT".equals(source.getSourceKind())
                    && chunk.getLocator() instanceof JSONObject locator && locator.containsKey("documentId")) {
                legacyDocumentSource = authorizeLegacyDocumentLocator(
                        userId, source, row, locator, legacyDocumentSource);
                JSONObject canonical = new JSONObject();
                canonical.put("source", legacyDocumentSource);
                canonical.put("charStart", row.getCharStart());
                canonical.put("charEnd", row.getCharEnd());
                chunk.setLocator(canonical);
            }
            chunks.add(chunk);
        }
        int count = source.getChunkCount() == null ? 0 : source.getChunkCount();
        int next = rows.isEmpty() ? after : rows.get(rows.size() - 1).getChunkIndex();
        boolean complete = !more && ((count == 0 && after < 0) || next == count - 1);
        CreativeViews.SourceChunkPage result = new CreativeViews.SourceChunkPage();
        result.setSourceRevisionId(String.valueOf(source.getId()));
        result.setSourceKind(source.getSourceKind());
        result.setSourceObjectId(source.getSourceObjectId());
        result.setSourceRevision(source.getSourceRevision());
        result.setContentHash(source.getContentHash());
        result.setChunkCount(count);
        result.setNextAfterChunkIndex(next);
        result.setComplete(complete);
        result.setChunks(chunks);
        return result;
    }

    /** Projects legacy document locators from current authority without rewriting immutable chunks. */
    private JSONObject authorizeLegacyDocumentLocator(Long userId, AidRuntimeSourceRevision source,
            AidRuntimeSourceChunk row, JSONObject locator, JSONObject authorizedLocator) {
        if (!Set.of("documentId", "charStart", "charEnd").equals(locator.keySet())
                || !Objects.equals(locator.get("documentId"), source.getSourceObjectId())
                || !Objects.equals(locator.get("charStart"), row.getCharStart())
                || !Objects.equals(locator.get("charEnd"), row.getCharEnd())) {
            throw new IllegalStateException("Stored document locator failed integrity validation");
        }
        if (authorizedLocator != null) return authorizedLocator;
        CreativeSourceProvider.AuthorizedSource authorized = providers.require(source.getSourceKind())
                .authorize(userId, source.getSourceObjectId(), source.getSourceRevision());
        validateSource(authorized);
        if (!Objects.equals(authorized.sourceObjectId(), source.getSourceObjectId())
                || !Objects.equals(authorized.sourceRevision(), source.getSourceRevision())
                || !Objects.equals(authorized.accessScopeDigest(), source.getAccessScopeDigest())) {
            throw new IllegalStateException("Authorized document source changed");
        }
        JSONObject identity = JSON.parseObject(authorized.locatorJson());
        if (identity == null || !Set.of("documentId", "sessionId").equals(identity.keySet())
                || !Objects.equals(identity.get("documentId"), source.getSourceObjectId())
                || !(identity.get("sessionId") instanceof String sessionId)
                || !sessionId.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalStateException("Authorized document locator is invalid");
        }
        return identity;
    }

    private void validateSource(CreativeSourceProvider.AuthorizedSource source) {
        if (source == null || source.readerFactory() == null) invalid("source");
        requireText(source.sourceObjectId(), 128, "resolved sourceObjectId");
        requireText(source.sourceRevision(), 128, "resolved sourceRevision");
        if (source.accessScopeDigest() == null || !source.accessScopeDigest().matches("[0-9a-f]{64}")) {
            invalid("accessScopeDigest");
        }
        if (StrUtil.isNotBlank(source.locatorJson())) {
            try { com.alibaba.fastjson2.JSON.parse(source.locatorJson()); }
            catch (RuntimeException error) { invalid("locatorJson"); }
        }
    }

    private CreativeViews.SourceChunk chunk(AidRuntimeSourceChunk row) {
        CreativeViews.SourceChunk result = new CreativeViews.SourceChunk();
        result.setChunkIndex(row.getChunkIndex());
        result.setCharStart(row.getCharStart());
        result.setCharEnd(row.getCharEnd());
        result.setCharCount(row.getCharCount());
        result.setWordCount(row.getWordCount());
        result.setLocator(StrUtil.isBlank(row.getLocatorJson()) ? null : JSON.parse(row.getLocatorJson()));
        result.setContentHash(row.getContentHash());
        result.setContent(row.getContentText());
        return result;
    }

    private void validateChunk(AidRuntimeSourceChunk row, int expectedIndex, Integer expectedStart) {
        if (row == null || row.getChunkIndex() == null || row.getChunkIndex() != expectedIndex
                || row.getCharStart() == null || !Objects.equals(row.getCharStart(), expectedStart)
                || row.getCharEnd() == null || row.getCharCount() == null || row.getContentText() == null
                || row.getCharEnd() != row.getCharStart() + row.getContentText().length()
                || row.getCharCount() != row.getContentText().length()
                || !SecureUtil.sha256(row.getContentText()).equals(row.getContentHash())) {
            throw new IllegalStateException("Stored source chunk failed integrity validation");
        }
    }

    private void requireText(String value, int max, String field) {
        if (StrUtil.isBlank(value) || value.length() > max) invalid(field);
    }
    private void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid(field);
    }
    private String trim(String value) { return StrUtil.isBlank(value) ? null : value.trim(); }
    private void invalid(String field) { throw new IllegalArgumentException("Invalid creative " + field); }
}
