package com.aid.assistant.creative.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.creative.dto.CreativeSegmentRequests;
import com.aid.assistant.creative.service.CreativeArtifactInputAuthorizer;
import com.aid.assistant.creative.service.CreativeArtifactSegmentReadService;
import com.aid.assistant.creative.service.CreativeTextSegmenter;
import com.aid.assistant.creative.vo.CreativeSegmentViews;
import com.aid.runtime.tool.RuntimeToolInvocation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CreativeArtifactSegmentReadServiceImpl implements CreativeArtifactSegmentReadService {
    private static final String SCHEMA = "creative-artifact-segment-page/v1";

    @Resource private AidRuntimeArtifactChunkMapper chunks;
    @Resource private CreativeArtifactInputAuthorizer inputAuthorizer;

    @Override
    public CreativeSegmentViews.ArtifactPage read(RuntimeToolInvocation invocation,
                                                  CreativeSegmentRequests.ArtifactRead request) {
        if (invocation == null || request == null || request.getArtifactId() == null
                || request.getArtifactId() < 1) invalid("artifactId");
        AidRuntimeArtifact artifact = inputAuthorizer.requireTextInput(invocation, request.getArtifactId());
        int chunkCount = chunks.countAuthorized(invocation.getUserId(), artifact.getId());
        if (chunkCount < 1) integrity();
        int chunkIndex = request.getChunkIndex() == null ? 0 : request.getChunkIndex();
        int charOffset = request.getCharOffset() == null ? 0 : request.getCharOffset();
        if (chunkIndex < 0 || chunkIndex >= chunkCount || charOffset < 0) invalid("cursor");

        AidRuntimeArtifactChunk previous = chunkIndex == 0 ? null : chunks.selectActive(
                artifact.getId(), chunkIndex - 1);
        if (chunkIndex > 0) validate(previous, artifact.getId(), chunkIndex - 1, null);
        int expectedStart = previous == null ? 0 : previous.getCharEnd();
        AidRuntimeArtifactChunk current = chunks.selectActive(artifact.getId(), chunkIndex);
        validate(current, artifact.getId(), chunkIndex, expectedStart);
        if (charOffset >= current.getContentText().length()) invalid("cursor");

        CreativeTextSegmenter.Slice slice = CreativeTextSegmenter.slice(
                current.getContentText(), charOffset, request.getMaxUtf8Bytes());
        CreativeSegmentViews.Cursor cursor = cursor(chunkIndex, charOffset);
        CreativeSegmentViews.Cursor next = nextCursor(chunkCount, current, slice);
        CreativeSegmentViews.ArtifactPage result = new CreativeSegmentViews.ArtifactPage();
        result.setSchemaVersion(SCHEMA);
        result.setArtifactId(String.valueOf(artifact.getId()));
        result.setArtifactRef("artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision());
        result.setArtifactType(artifact.getArtifactType());
        result.setRevision(artifact.getRevision());
        result.setContentHash(artifact.getContentHash());
        result.setChunkCount(chunkCount);
        result.setCursor(cursor);
        result.setNextCursor(next);
        result.setComplete(next == null);
        result.setSegment(segment(current, slice));
        return result;
    }

    private void validate(AidRuntimeArtifactChunk value, Long artifactId, int index, Integer expectedStart) {
        String content = value == null ? null : value.getContentText();
        int end;
        try {
            end = content == null || value.getCharStart() == null ? -1
                    : Math.addExact(value.getCharStart(), content.length());
        } catch (ArithmeticException overflow) {
            integrity();
            return;
        }
        if (value == null || !Objects.equals(value.getArtifactId(), artifactId)
                || !Objects.equals(value.getChunkIndex(), index)
                || value.getCharStart() == null || value.getCharStart() < 0
                || expectedStart != null && !Objects.equals(value.getCharStart(), expectedStart)
                || index == 0 && !Objects.equals(value.getCharStart(), 0)
                || !Objects.equals(value.getCharEnd(), end)
                || !Objects.equals(value.getCharCount(), content == null ? null : content.length())
                || content == null || content.isEmpty() || !CreativeTextSegmenter.wellFormedUtf16(content)
                || !Objects.equals(value.getContentHash(), SecureUtil.sha256(content))) integrity();
    }

    private CreativeSegmentViews.Cursor nextCursor(int count, AidRuntimeArtifactChunk parent,
                                                   CreativeTextSegmenter.Slice slice) {
        if (slice.charEnd() < parent.getContentText().length()) {
            return cursor(parent.getChunkIndex(), slice.charEnd());
        }
        return parent.getChunkIndex() + 1 < count ? cursor(parent.getChunkIndex() + 1, 0) : null;
    }

    private CreativeSegmentViews.Segment segment(AidRuntimeArtifactChunk parent,
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
        throw new IllegalArgumentException("Invalid creative artifact segment " + field);
    }

    private void integrity() {
        throw new IllegalStateException("Stored artifact chunk failed integrity validation");
    }
}
