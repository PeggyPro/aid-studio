package com.aid.assistant.creative.series;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.error.AssistantServiceException;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Reconstructs one authorized immutable SCREENPLAY artifact with exact UTF-16/hash checks. */
@Service
public class SeriesArtifactSnapshotService {
    public static final int MAX_JOINED_CHARS = 1_000_000;

    @Resource private AidRuntimeArtifactMapper artifacts;
    @Resource private AidRuntimeArtifactChunkMapper chunks;

    public Snapshot load(Long userId, Long sessionId, Long artifactId, String expectedContentHash) {
        AidRuntimeArtifact artifact = artifacts.selectAuthorizedForSession(userId, sessionId, artifactId);
        if (artifact == null) fail(HttpStatus.NOT_FOUND, "SERIES_ARTIFACT_NOT_FOUND",
                "Series artifact is not available in this session", artifactId);
        if (!"FINALIZED".equals(artifact.getStatus())) fail(HttpStatus.CONFLICT, "SERIES_ARTIFACT_NOT_FINALIZED",
                "Series artifact is not finalized", artifactId);
        if (!"SCREENPLAY".equals(artifact.getArtifactType())) fail(HttpStatus.UNPROCESSABLE_ENTITY,
                "SERIES_ARTIFACT_TYPE_INVALID", "Series adoption requires a SCREENPLAY artifact",
                artifact.getArtifactType());
        if (expectedContentHash != null && !Objects.equals(expectedContentHash, artifact.getContentHash())) {
            fail(HttpStatus.CONFLICT, "SERIES_ARTIFACT_HASH_CONFLICT",
                    "Series artifact content hash has changed", artifact.getContentHash());
        }
        List<AidRuntimeArtifactChunk> rows = chunks.selectAllActive(artifactId);
        if (rows.isEmpty()) fail(HttpStatus.UNPROCESSABLE_ENTITY, "SERIES_ARTIFACT_CONTENT_REQUIRED",
                "Series artifact has no text content", artifactId);
        StringBuilder text = new StringBuilder();
        int expectedIndex = 0;
        int expectedStart = 0;
        for (AidRuntimeArtifactChunk chunk : rows) {
            String content = chunk.getContentText();
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != expectedIndex
                    || chunk.getCharStart() == null || chunk.getCharStart() != expectedStart
                    || chunk.getCharEnd() == null || chunk.getCharCount() == null || content == null
                    || chunk.getCharEnd() != chunk.getCharStart() + content.length()
                    || chunk.getCharCount() != content.length()
                    || !SecureUtil.sha256(content).equals(chunk.getContentHash())
                    || hasUnpairedSurrogate(content)) {
                fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ARTIFACT_CHUNK_CORRUPT",
                        "Series artifact chunk failed integrity validation", chunk.getChunkIndex());
            }
            if ((long) text.length() + content.length() > MAX_JOINED_CHARS) {
                fail(HttpStatus.UNPROCESSABLE_ENTITY, "SERIES_ARTIFACT_TOO_LARGE",
                        "Series artifact exceeds the one-million-character read limit", artifactId);
            }
            text.append(content);
            expectedStart = chunk.getCharEnd();
            expectedIndex++;
        }
        String content = text.toString();
        String actualHash = SecureUtil.sha256(content);
        if (!Objects.equals(actualHash, artifact.getContentHash())) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ARTIFACT_CONTENT_CORRUPT",
                    "Series artifact content failed integrity validation", artifactId);
        }
        return new Snapshot(artifact, "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision(),
                content, actualHash, rows.size());
    }

    public void verifyIdentity(Long userId, Long sessionId, Snapshot snapshot) {
        AidRuntimeArtifact current = artifacts.selectAuthorizedForSession(userId, sessionId, snapshot.artifact().getId());
        if (current == null || !Objects.equals(current.getLogicalArtifactId(), snapshot.artifact().getLogicalArtifactId())
                || !Objects.equals(current.getRevision(), snapshot.artifact().getRevision())
                || !Objects.equals(current.getArtifactType(), snapshot.artifact().getArtifactType())
                || !Objects.equals(current.getStatus(), snapshot.artifact().getStatus())
                || !Objects.equals(current.getContentHash(), snapshot.contentHash())) {
            fail(HttpStatus.CONFLICT, "SERIES_ARTIFACT_IDENTITY_CONFLICT",
                    "Series artifact identity changed before commit", snapshot.artifact().getId());
        }
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) return true;
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private void fail(HttpStatus status, String code, String message, Object details) {
        throw new AssistantServiceException(status.value(), code, message, details);
    }

    public record Snapshot(AidRuntimeArtifact artifact, String artifactRef, String content,
                           String contentHash, int chunkCount) {
    }
}
