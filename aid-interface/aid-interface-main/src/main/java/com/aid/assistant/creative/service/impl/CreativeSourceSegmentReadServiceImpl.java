package com.aid.assistant.creative.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.dto.CreativeSegmentRequests;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeSourceSegmentReadService;
import com.aid.assistant.creative.service.CreativeTextSegmenter;
import com.aid.assistant.creative.vo.CreativeSegmentViews;
import com.aid.runtime.tool.RuntimeToolInvocation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CreativeSourceSegmentReadServiceImpl implements CreativeSourceSegmentReadService {
    private static final String SCHEMA = "creative-source-segment-page/v1";

    @Resource private AidRuntimeSourceRevisionMapper revisions;
    @Resource private AidRuntimeSourceChunkMapper chunks;
    @Resource private CreativeContextAuthorizer contextAuthorizer;

    @Override
    public CreativeSegmentViews.SourcePage read(RuntimeToolInvocation invocation,
                                                CreativeSegmentRequests.SourceRead request) {
        if (invocation == null || invocation.getUserId() == null || invocation.getUserId() < 1
                || request == null || request.getSourceRevisionId() == null
                || request.getSourceRevisionId() < 1) invalid("sourceRevisionId");
        AidRuntimeSourceRevision source = revisions.selectAuthorized(
                invocation.getUserId(), request.getSourceRevisionId());
        if (source == null || !"ACTIVE".equals(source.getStatus())
                || source.getChunkCount() == null || source.getChunkCount() < 0
                || source.getContentHash() == null || !source.getContentHash().matches("[0-9a-f]{64}")) {
            invalid("sourceRevisionId");
        }
        contextAuthorizer.requireRead(invocation.getContextManifestJson(), source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());
        int chunkIndex = request.getChunkIndex() == null ? 0 : request.getChunkIndex();
        int charOffset = request.getCharOffset() == null ? 0 : request.getCharOffset();
        if (chunkIndex < 0 || charOffset < 0) invalid("cursor");
        if (source.getChunkCount() == 0) {
            if (chunkIndex != 0 || charOffset != 0) invalid("cursor");
            return empty(source);
        }
        if (chunkIndex >= source.getChunkCount()) invalid("cursor");

        AidRuntimeSourceChunk previous = chunkIndex == 0 ? null : chunks.selectAuthorizedChunk(
                invocation.getUserId(), source.getId(), chunkIndex - 1);
        if (chunkIndex > 0) validate(previous, source.getId(), chunkIndex - 1, null);
        int expectedStart = previous == null ? 0 : previous.getCharEnd();
        AidRuntimeSourceChunk current = chunks.selectAuthorizedChunk(
                invocation.getUserId(), source.getId(), chunkIndex);
        validate(current, source.getId(), chunkIndex, expectedStart);
        if (charOffset >= current.getContentText().length()) invalid("cursor");

        CreativeTextSegmenter.Slice slice = CreativeTextSegmenter.slice(
                current.getContentText(), charOffset, request.getMaxUtf8Bytes());
        CreativeSegmentViews.Cursor cursor = cursor(chunkIndex, charOffset);
        CreativeSegmentViews.Cursor next = nextCursor(source.getChunkCount(), current, slice);
        CreativeSegmentViews.SourcePage result = new CreativeSegmentViews.SourcePage();
        result.setSchemaVersion(SCHEMA);
        result.setSourceRevisionId(String.valueOf(source.getId()));
        result.setSourceKind(source.getSourceKind());
        result.setSourceObjectId(source.getSourceObjectId());
        result.setSourceRevision(source.getSourceRevision());
        result.setContentHash(source.getContentHash());
        result.setChunkCount(source.getChunkCount());
        result.setCursor(cursor);
        result.setNextCursor(next);
        result.setComplete(next == null);
        result.setSegment(segment(current, slice));
        return result;
    }

    private CreativeSegmentViews.SourcePage empty(AidRuntimeSourceRevision source) {
        CreativeSegmentViews.SourcePage result = new CreativeSegmentViews.SourcePage();
        result.setSchemaVersion(SCHEMA);
        result.setSourceRevisionId(String.valueOf(source.getId()));
        result.setSourceKind(source.getSourceKind());
        result.setSourceObjectId(source.getSourceObjectId());
        result.setSourceRevision(source.getSourceRevision());
        result.setContentHash(source.getContentHash());
        result.setChunkCount(0);
        result.setCursor(cursor(0, 0));
        result.setNextCursor(null);
        result.setComplete(true);
        result.setSegment(null);
        return result;
    }

    private void validate(AidRuntimeSourceChunk value, Long sourceId, int index, Integer expectedStart) {
        String content = value == null ? null : value.getContentText();
        int end;
        try {
            end = content == null || value.getCharStart() == null ? -1
                    : Math.addExact(value.getCharStart(), content.length());
        } catch (ArithmeticException overflow) {
            integrity();
            return;
        }
        if (value == null || !Objects.equals(value.getSourceRevisionId(), sourceId)
                || !Objects.equals(value.getChunkIndex(), index)
                || value.getCharStart() == null || value.getCharStart() < 0
                || expectedStart != null && !Objects.equals(value.getCharStart(), expectedStart)
                || index == 0 && !Objects.equals(value.getCharStart(), 0)
                || !Objects.equals(value.getCharEnd(), end)
                || !Objects.equals(value.getCharCount(), content == null ? null : content.length())
                || content == null || content.isEmpty() || !CreativeTextSegmenter.wellFormedUtf16(content)
                || !Objects.equals(value.getContentHash(), SecureUtil.sha256(content))) integrity();
    }

    private CreativeSegmentViews.Cursor nextCursor(int count, AidRuntimeSourceChunk parent,
                                                   CreativeTextSegmenter.Slice slice) {
        if (slice.charEnd() < parent.getContentText().length()) {
            return cursor(parent.getChunkIndex(), slice.charEnd());
        }
        return parent.getChunkIndex() + 1 < count ? cursor(parent.getChunkIndex() + 1, 0) : null;
    }

    private CreativeSegmentViews.Segment segment(AidRuntimeSourceChunk parent,
                                                 CreativeTextSegmenter.Slice slice) {
        CreativeSegmentViews.Segment result = new CreativeSegmentViews.Segment();
        result.setParentChunkIndex(parent.getChunkIndex());
        result.setParentCharStart(parent.getCharStart());
        result.setParentCharEnd(parent.getCharEnd());
        result.setParentCharCount(parent.getCharCount());
        result.setParentContentHash(parent.getContentHash());
        result.setCharStart(Math.addExact(parent.getCharStart(), slice.charStart()));
        result.setCharEnd(Math.addExact(parent.getCharStart(), slice.charEnd()));
        result.setCharCount(slice.content().length());
        result.setUtf8ByteCount(slice.utf8ByteCount());
        result.setContentHash(slice.contentHash());
        result.setContent(slice.content());
        return result;
    }

    private CreativeSegmentViews.Cursor cursor(int chunkIndex, int charOffset) {
        CreativeSegmentViews.Cursor result = new CreativeSegmentViews.Cursor();
        result.setChunkIndex(chunkIndex);
        result.setCharOffset(charOffset);
        return result;
    }

    private void invalid(String field) {
        throw new IllegalArgumentException("Invalid creative source segment " + field);
    }

    private void integrity() {
        throw new IllegalStateException("Stored source chunk failed integrity validation");
    }
}
