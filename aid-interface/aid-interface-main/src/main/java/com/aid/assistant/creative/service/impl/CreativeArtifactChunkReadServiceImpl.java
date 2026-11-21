package com.aid.assistant.creative.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.service.CreativeArtifactChunkReadService;
import com.aid.assistant.creative.service.CreativeArtifactInputAuthorizer;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verifies frozen input authorization and chunk integrity before returning artifact text. */
@Service
public class CreativeArtifactChunkReadServiceImpl implements CreativeArtifactChunkReadService {
    private static final int MAX_PAGE_CHARS = 100_000;

    @Resource private AidRuntimeArtifactChunkMapper chunkMapper;
    @Resource private CreativeArtifactInputAuthorizer inputAuthorizer;

    @Override
    public CreativeViews.ArtifactChunkPage read(RuntimeToolPreparedInvocation invocation,
                                                CreativeAuditRequests.ArtifactChunkRead request) {
        if (invocation == null || invocation.getUserId() == null || invocation.getUserId() < 1
                || request == null || request.getArtifactId() == null || request.getArtifactId() < 1) {
            invalid("artifactId");
        }
        int after = request.getAfterChunkIndex() == null ? -1 : request.getAfterChunkIndex();
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        if (after < -1 || limit < 1 || limit > 100) invalid("artifact chunk cursor");

        AidRuntimeArtifact artifact = inputAuthorizer.requireTextInput(invocation, request.getArtifactId());
        int expectedStart = 0;
        if (after >= 0) {
            AidRuntimeArtifactChunk previous = chunkMapper.selectActive(artifact.getId(), after);
            if (previous == null) invalid("afterChunkIndex");
            validateChunk(previous, after, previous.getCharStart());
            if (after == 0 && previous.getCharStart() != 0) integrity();
            if (after > 0) {
                AidRuntimeArtifactChunk predecessor = chunkMapper.selectActive(artifact.getId(), after - 1);
                if (predecessor == null) integrity();
                validateChunk(predecessor, after - 1, predecessor.getCharStart());
                if (!Objects.equals(predecessor.getCharEnd(), previous.getCharStart())) integrity();
            }
            expectedStart = previous.getCharEnd();
        }

        List<AidRuntimeArtifactChunk> candidates = chunkMapper.selectAuthorizedPage(
                invocation.getUserId(), artifact.getId(), after, limit + 1);
        List<AidRuntimeArtifactChunk> rows = new ArrayList<>();
        int pageChars = 0;
        for (AidRuntimeArtifactChunk candidate : candidates) {
            String content = candidate == null ? null : candidate.getContentText();
            if (content == null || content.length() > MAX_PAGE_CHARS) integrity();
            if (!rows.isEmpty() && pageChars + content.length() > MAX_PAGE_CHARS) break;
            rows.add(candidate);
            pageChars += content.length();
            if (rows.size() == limit) break;
        }
        boolean more = candidates.size() > rows.size();
        int expectedIndex = after + 1;
        List<CreativeViews.ArtifactChunk> chunks = new ArrayList<>();
        for (AidRuntimeArtifactChunk row : rows) {
            validateChunk(row, expectedIndex++, expectedStart);
            expectedStart = row.getCharEnd();
            chunks.add(view(row));
        }
        int count = chunkMapper.countAuthorized(invocation.getUserId(), artifact.getId());
        int next = rows.isEmpty() ? after : rows.get(rows.size() - 1).getChunkIndex();
        boolean complete = !more && next == count - 1;
        if (complete) validateWholeArtifact(artifact, count);

        CreativeViews.ArtifactChunkPage result = new CreativeViews.ArtifactChunkPage();
        result.setArtifactId(String.valueOf(artifact.getId()));
        result.setArtifactRef(ref(artifact));
        result.setArtifactType(artifact.getArtifactType());
        result.setRevision(artifact.getRevision());
        result.setContentHash(artifact.getContentHash());
        result.setChunkCount(count);
        result.setNextAfterChunkIndex(next);
        result.setComplete(complete);
        result.setChunks(chunks);
        return result;
    }

    private void validateWholeArtifact(AidRuntimeArtifact artifact, int count) {
        List<AidRuntimeArtifactChunk> all = chunkMapper.selectAllActive(artifact.getId());
        if (all == null || all.size() != count || all.isEmpty()) integrity();
        StringBuilder content = new StringBuilder();
        int expectedStart = 0;
        for (int index = 0; index < all.size(); index++) {
            AidRuntimeArtifactChunk chunk = all.get(index);
            validateChunk(chunk, index, expectedStart);
            expectedStart = chunk.getCharEnd();
            content.append(chunk.getContentText());
        }
        if (!Objects.equals(artifact.getContentHash(), SecureUtil.sha256(content.toString()))) integrity();
    }

    private void validateChunk(AidRuntimeArtifactChunk row, int expectedIndex, Integer expectedStart) {
        String content = row == null ? null : row.getContentText();
        int end;
        try { end = content == null || row.getCharStart() == null ? -1
                : Math.addExact(row.getCharStart(), content.length()); }
        catch (ArithmeticException overflow) { integrity(); return; }
        if (row == null || !Objects.equals(row.getChunkIndex(), expectedIndex)
                || !Objects.equals(row.getCharStart(), expectedStart)
                || !Objects.equals(row.getCharEnd(), end)
                || !Objects.equals(row.getCharCount(), content == null ? null : content.length())
                || content == null || content.length() > MAX_PAGE_CHARS
                || !Objects.equals(row.getContentHash(), SecureUtil.sha256(content))
                || !wellFormedUtf16(content)) integrity();
    }

    private CreativeViews.ArtifactChunk view(AidRuntimeArtifactChunk row) {
        CreativeViews.ArtifactChunk result = new CreativeViews.ArtifactChunk();
        result.setChunkIndex(row.getChunkIndex());
        result.setCharStart(row.getCharStart());
        result.setCharEnd(row.getCharEnd());
        result.setCharCount(row.getCharCount());
        result.setWordCount(row.getWordCount());
        result.setContentHash(row.getContentHash());
        result.setContent(row.getContentText());
        return result;
    }

    private String ref(AidRuntimeArtifact artifact) {
        return "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision();
    }

    private boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)) return false;
            if (!Character.isHighSurrogate(current)) continue;
            if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
        }
        return true;
    }

    private void integrity() { throw new IllegalStateException("Stored artifact chunk failed integrity validation"); }
    private void invalid(String field) { throw new IllegalArgumentException("Invalid creative " + field); }
}
