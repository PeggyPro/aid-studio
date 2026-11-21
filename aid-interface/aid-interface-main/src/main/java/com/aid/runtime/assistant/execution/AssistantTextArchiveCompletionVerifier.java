package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies durable text bytes only; ownership, plan scope and creative review remain separate gates. */
@Service
public class AssistantTextArchiveCompletionVerifier
{
    private static final Set<String> TYPES = Set.of("CREATIVE_TEXT", "OUTLINE", "SCREENPLAY", "REVIEW_REPORT");
    private static final int PAGE_SIZE = 128;
    @Resource private AidRuntimeArtifactChunkMapper chunks;

    public static boolean supports(String type) { return type != null && TYPES.contains(type); }

    public void requireArchived(AidRuntimeArtifact artifact)
    {
        require(artifact != null && artifact.getId() != null && artifact.getId() > 0
                && supports(artifact.getArtifactType()) && "FINALIZED".equals(artifact.getStatus())
                && "0".equals(artifact.getDelFlag()) && artifact.getContentHash() != null
                && artifact.getContentHash().matches("^[0-9a-f]{64}$"));
        JSONObject metadata;
        try { metadata = JSON.parseObject(artifact.getMetadataJson()); }
        catch (RuntimeException invalid) { throw rejected(); }
        Integer expectedCount;
        Integer expectedChars;
        try
        {
            expectedCount = metadata == null ? null : metadata.getInteger("chunkCount");
            expectedChars = metadata == null ? null : metadata.getInteger("charCount");
        }
        catch (RuntimeException invalid) { throw rejected(); }
        require(expectedCount != null && expectedCount >= 0 && expectedCount <= 1_000_000
                && expectedChars != null && expectedChars >= 0);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        int index = 0;
        int charStart = 0;
        while (true)
        {
            List<AidRuntimeArtifactChunk> page = chunks.selectList(new LambdaQueryWrapper<AidRuntimeArtifactChunk>()
                    .eq(AidRuntimeArtifactChunk::getArtifactId, artifact.getId())
                    .eq(AidRuntimeArtifactChunk::getStatus, "ACTIVE")
                    .gt(AidRuntimeArtifactChunk::getChunkIndex, index - 1)
                    .orderByAsc(AidRuntimeArtifactChunk::getChunkIndex).last("LIMIT " + PAGE_SIZE));
            require(page != null && page.size() <= PAGE_SIZE);
            if (page.isEmpty()) break;
            for (AidRuntimeArtifactChunk chunk : page)
            {
                require(chunk != null && index < expectedCount
                        && Objects.equals(chunk.getArtifactId(), artifact.getId())
                        && "ACTIVE".equals(chunk.getStatus()) && Objects.equals(chunk.getChunkIndex(), index)
                        && Objects.equals(chunk.getCharStart(), charStart) && chunk.getContentText() != null
                        && chunk.getCharCount() != null && chunk.getCharCount() >= 0
                        && chunk.getContentText().length() == chunk.getCharCount()
                        && chunk.getCharEnd() != null && (long) charStart + chunk.getCharCount() == chunk.getCharEnd()
                        && SecureUtil.sha256(chunk.getContentText()).equals(chunk.getContentHash()));
                digest.update(chunk.getContentText().getBytes(StandardCharsets.UTF_8));
                charStart = chunk.getCharEnd();
                index++;
            }
        }
        require(index == expectedCount && charStart == expectedChars
                && HexFormat.of().formatHex(digest.digest()).equals(artifact.getContentHash()));
    }

    private static void require(boolean condition) { if (!condition) throw rejected(); }
    private static ServiceException rejected() { return new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID"); }
}
