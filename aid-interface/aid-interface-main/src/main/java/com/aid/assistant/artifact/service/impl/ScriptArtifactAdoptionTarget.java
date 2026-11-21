package com.aid.assistant.artifact.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidComicScript;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.service.ArtifactAdoptionTarget;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.creative.source.ScriptCreativeSourceProvider;
import com.aid.script.dto.UserScriptSaveRequest;
import com.aid.script.helper.UserScriptContentHash;
import com.aid.script.service.IUserScriptBusinessService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transactional SCRIPT adoption through the existing project lock and script baseline CAS. */
@Component
public class ScriptArtifactAdoptionTarget implements ArtifactAdoptionTarget {
    private static final Pattern TARGET = Pattern.compile("^project:([1-9][0-9]*):episode:(0|[1-9][0-9]*)$");
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final int MAX_JOINED_CHARS = 1_000_000;
    private static final int MAX_EFFECTIVE_CHARS = 10_000;

    @Resource private IUserScriptBusinessService scriptService;
    @Resource private AidRuntimeArtifactChunkMapper chunkMapper;

    @Override
    public String targetKind() {
        return "SCRIPT";
    }

    @Override
    public Capability inspect(CapabilityContext context) {
        Long projectId = context.getSession().getProjectId();
        Long episodeId = context.getSession().getEpisodeId();
        if (projectId == null || projectId < 1 || episodeId == null || episodeId < 0) {
            return new Capability(targetKind(), null, null, false, "SCRIPT_CONTEXT_REQUIRED",
                    "Project script", Map.of());
        }
        AidComicScript current = scriptService.selectUserScriptByProject(projectId, episodeId, context.getUserId());
        String reason = null;
        boolean allowed = "FINALIZED".equals(context.getArtifact().getStatus())
                && "SCREENPLAY".equals(context.getArtifact().getArtifactType());
        if (!"FINALIZED".equals(context.getArtifact().getStatus())) reason = "ARTIFACT_NOT_FINALIZED";
        else if (!"SCREENPLAY".equals(context.getArtifact().getArtifactType())) reason = "ARTIFACT_TYPE_NOT_SCRIPT";
        else if (context.getRequest() != null && context.getRequest().getTargetObjectId() != null
                && !targetObject(projectId, episodeId).equals(context.getRequest().getTargetObjectId())) {
            allowed = false;
            reason = "SCRIPT_TARGET_SCOPE_CONFLICT";
        }
        else {
            ContentSnapshot snapshot = content(context.getArtifact().getId(), context.getArtifact().getContentHash());
            if (snapshot.effectiveChars() < 1) {
                allowed = false;
                reason = "SCRIPT_CONTENT_REQUIRED";
            } else if (snapshot.effectiveChars() > MAX_EFFECTIVE_CHARS) {
                allowed = false;
                reason = "SCRIPT_CONTENT_TOO_LARGE";
            }
        }
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("projectId", String.valueOf(projectId));
        required.put("episodeId", String.valueOf(episodeId));
        required.put("maxNonWhitespaceChars", MAX_EFFECTIVE_CHARS);
        required.put("supportsSeriesSplit", false);
        return new Capability(targetKind(), targetObject(projectId, episodeId), revision(current),
                allowed, reason, "Project script", required);
    }

    @Override
    public Result adopt(Context context) {
        ArtifactRequests.Adopt request = context.getRequest();
        Matcher matcher = TARGET.matcher(request.getTargetObjectId());
        if (!matcher.matches()) fail(HttpStatus.UNPROCESSABLE_ENTITY, "SCRIPT_TARGET_INVALID",
                "Script target is invalid", request.getTargetObjectId());
        Long projectId = Long.valueOf(matcher.group(1));
        Long episodeId = Long.valueOf(matcher.group(2));
        if (!Objects.equals(projectId, context.getSession().getProjectId())
                || !Objects.equals(episodeId, context.getSession().getEpisodeId())) {
            fail(HttpStatus.FORBIDDEN, "SCRIPT_TARGET_SCOPE_CONFLICT",
                    "目标剧本不属于当前助手会话", request.getTargetObjectId());
        }
        if (!"SCREENPLAY".equals(context.getArtifact().getArtifactType())) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_TYPE_NOT_SCRIPT",
                    "只有剧本制品可以采纳到项目剧本", context.getArtifact().getArtifactType());
        }
        AidComicScript current = scriptService.selectUserScriptByProject(projectId, episodeId, context.getUserId());
        String observedRevision = revision(current);
        if (!Objects.equals(observedRevision, request.getExpectedTargetRevision())) {
            fail(HttpStatus.CONFLICT, "SCRIPT_REVISION_CONFLICT", "Script revision has changed", observedRevision);
        }
        ContentSnapshot snapshot = content(context.getArtifact().getId(), context.getArtifact().getContentHash());
        if (snapshot.effectiveChars() < 1) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "SCRIPT_CONTENT_REQUIRED", "剧本内容不能为空", null);
        }
        if (snapshot.effectiveChars() > MAX_EFFECTIVE_CHARS) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "SCRIPT_CONTENT_TOO_LARGE",
                    "当前剧集最多采纳一万字，请先按集拆分", snapshot.effectiveChars());
        }
        UserScriptSaveRequest save = new UserScriptSaveRequest();
        save.setProjectId(projectId);
        save.setEpisodeId(episodeId);
        save.setOriginalText(snapshot.content());
        save.setBaseScriptId(current == null ? 0L : current.getId());
        save.setBaseContentHash(current == null ? UserScriptContentHash.calculate("")
                : UserScriptContentHash.calculate(current.getOriginalText()));
        AidComicScript created = scriptService.saveUserScript(save, context.getUserId());
        String createdHash = UserScriptContentHash.calculate(created.getOriginalText());
        String createdRevision = ScriptCreativeSourceProvider.revision(created, createdHash);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("projectId", String.valueOf(projectId));
        receipt.put("episodeId", String.valueOf(episodeId));
        receipt.put("scriptId", String.valueOf(created.getId()));
        receipt.put("comicVersion", created.getComicVersion());
        receipt.put("previousRevision", observedRevision);
        receipt.put("targetRevision", createdRevision);
        receipt.put("contentHash", createdHash);
        return new Result(createdRevision, receipt);
    }

    private ContentSnapshot content(Long artifactId, String expectedHash) {
        List<AidRuntimeArtifactChunk> chunks = chunkMapper.selectAllActive(artifactId);
        if (chunks.isEmpty()) fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_CONTENT_REQUIRED",
                "剧本制品没有文本内容", artifactId);
        StringBuilder joined = new StringBuilder();
        int expectedIndex = 0;
        int expectedStart = 0;
        for (AidRuntimeArtifactChunk chunk : chunks) {
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != expectedIndex++
                    || chunk.getCharStart() == null || chunk.getCharStart() != expectedStart
                    || chunk.getCharEnd() == null || chunk.getCharCount() == null
                    || chunk.getContentText() == null
                    || chunk.getCharEnd() != chunk.getCharStart() + chunk.getContentText().length()
                    || chunk.getCharCount() != chunk.getContentText().length()
                    || !SecureUtil.sha256(chunk.getContentText()).equals(chunk.getContentHash())) {
                fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_CHUNK_CORRUPT",
                        "剧本制品分片校验失败", chunk.getChunkIndex());
            }
            expectedStart = chunk.getCharEnd();
            if ((long) joined.length() + chunk.getContentText().length() > MAX_JOINED_CHARS) {
                fail(HttpStatus.UNPROCESSABLE_ENTITY, "SCRIPT_CONTENT_TOO_LARGE",
                        "剧本制品超过可读取上限", joined.length());
            }
            joined.append(chunk.getContentText());
        }
        String value = joined.toString();
        if (!SecureUtil.sha256(value).equals(expectedHash)) fail(HttpStatus.CONFLICT,
                "ARTIFACT_HASH_CONFLICT", "剧本制品内容校验失败", null);
        String pure = BR_TAG.matcher(value).replaceAll("").replaceAll("\\s+", "");
        return new ContentSnapshot(value, pure.length());
    }

    private String revision(AidComicScript script) {
        if (script == null) return "script:0:v0:" + UserScriptContentHash.calculate("");
        return ScriptCreativeSourceProvider.revision(script,
                UserScriptContentHash.calculate(script.getOriginalText()));
    }

    private String targetObject(Long projectId, Long episodeId) {
        return "project:" + projectId + ":episode:" + episodeId;
    }

    private void fail(HttpStatus status, String code, String message, Object details) {
        throw new AssistantServiceException(status.value(), code, message, details);
    }

    private record ContentSnapshot(String content, int effectiveChars) {
    }
}
