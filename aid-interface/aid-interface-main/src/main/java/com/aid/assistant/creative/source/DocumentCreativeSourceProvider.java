package com.aid.assistant.creative.source;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.source.service.AssistantDocumentAccess;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Streams existing immutable source chunks in bounded pages, without rereading the original file. */
@Component
public class DocumentCreativeSourceProvider implements CreativeSourceProvider {
    @Resource private AssistantDocumentAccess access;
    @Resource private AidRuntimeSourceChunkMapper chunks;

    @Override public String sourceKind() { return "DOCUMENT"; }

    @Override public AuthorizedSource authorize(Long userId, String sourceObjectId, String requestedRevision) {
        Long documentId;
        try { documentId = Long.valueOf(sourceObjectId); }
        catch (RuntimeException invalid) { throw new AssistantServiceException(422, "DOCUMENT_ID_INVALID", "文档标识无效", null); }
        AssistantDocumentAccess.Snapshot snapshot = access.require(userId, documentId);
        AidRuntimeSourceRevision revision = snapshot.revision();
        if (requestedRevision != null && !requestedRevision.isBlank()
                && !Objects.equals(requestedRevision, revision.getSourceRevision()))
            throw new AssistantServiceException(409, "DOCUMENT_REVISION_CHANGED", "文档版本已变更", null);
        JSONObject locator = new JSONObject();
        locator.put("documentId", sourceObjectId); locator.put("sessionId", String.valueOf(snapshot.document().getSessionId()));
        return new AuthorizedSource(sourceObjectId, revision.getSourceRevision(), revision.getAccessScopeDigest(),
                locator.toJSONString(), () -> new ChunkReader(userId, revision));
    }

    private final class ChunkReader extends Reader {
        private final Long userId;
        private final AidRuntimeSourceRevision revision;
        private final ArrayDeque<AidRuntimeSourceChunk> page = new ArrayDeque<>();
        private String pending = "";
        private int offset;
        private int nextIndex;
        private int nextChar;
        private boolean closed;

        private ChunkReader(Long userId, AidRuntimeSourceRevision revision) { this.userId = userId; this.revision = revision; }

        @Override public int read(char[] target, int start, int length) throws IOException {
            Objects.checkFromIndexSize(start, length, target.length);
            if (closed) throw new IOException("Source reader closed");
            if (length == 0) return 0;
            if (offset == pending.length()) {
                if (nextIndex == revision.getChunkCount()) {
                    if (nextChar != revision.getCharCount()) throw new IOException("Source length changed");
                    return -1;
                }
                if (page.isEmpty()) {
                    List<AidRuntimeSourceChunk> rows = chunks.selectAuthorizedPage(userId, revision.getId(), nextIndex - 1, 20);
                    page.addAll(rows);
                }
                AidRuntimeSourceChunk value = page.pollFirst();
                if (value == null || !Objects.equals(value.getChunkIndex(), nextIndex)
                        || !Objects.equals(value.getCharStart(), nextChar) || value.getContentText() == null
                        || value.getCharEnd() == null || value.getContentText().length() < 1
                        || value.getContentText().length() > 50_000
                        || value.getCharEnd() != nextChar + value.getContentText().length()
                        || !Objects.equals(SecureUtil.sha256(value.getContentText()), value.getContentHash()))
                    throw new IOException("Source chunk integrity failed");
                nextIndex++; nextChar = value.getCharEnd(); pending = value.getContentText(); offset = 0;
            }
            int count = Math.min(length, pending.length() - offset);
            pending.getChars(offset, offset + count, target, start); offset += count;
            return count;
        }

        @Override public void close() { closed = true; page.clear(); pending = ""; }
    }
}
