package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.service.ArtifactAdoptionTarget;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasProjectService;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transactional adoption of a finalized text artifact into an existing Canvas text node. */
@Component
@RequiredArgsConstructor
public class CanvasArtifactAdoptionTarget implements ArtifactAdoptionTarget {
    private static final int MAX_CANVAS_TEXT_LENGTH = 65_536;

    private final AidAssistantSessionMapper sessionMapper;
    private final AidRuntimeArtifactChunkMapper chunkMapper;
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final CanvasCollaborationService collaborationService;
    private final CanvasProjectService projectService;

    @Override
    public String targetKind() {
        return "FREE_CANVAS";
    }

    @Override
    @Transactional(readOnly = true)
    public Capability inspect(CapabilityContext context) {
        ArtifactRequests.AdoptionCapabilities request = context == null ? null : context.getRequest();
        AidAssistantSession suppliedSession = context == null ? null : context.getSession();
        Long requestedWorkspaceId = request == null ? null : request.getWorkspaceId();
        Map<String, Object> required = requiredContext(requestedWorkspaceId);
        if (context == null || context.getUserId() == null || context.getArtifact() == null
                || suppliedSession == null || request == null
                || !"FINALIZED".equals(context.getArtifact().getStatus())) {
            return capability(null, null, false, "CANVAS_ARTIFACT_NOT_READY", required);
        }
        AidAssistantSession session = suppliedSession;
        String targetObjectId = request.getTargetObjectId();
        if (!Objects.equals(context.getUserId(), session.getUserId()) || !"ASSISTANT".equals(session.getSessionType())
                || !"0".equals(session.getDelFlag()) || session.getWorkspaceId() == null
                || request.getWorkspaceId() == null || !Objects.equals(request.getWorkspaceId(), session.getWorkspaceId())
                || (!blank(request.getTargetKind()) && !targetKind().equals(request.getTargetKind()))
                || blank(targetObjectId)) {
            return capability(targetObjectId, null, false, "CANVAS_TARGET_REQUIRED", required);
        }
        try {
            verifiedContent(context.getUserId(), context.getArtifact());
        } catch (AssistantServiceException incompatible) {
            return capability(targetObjectId, null, false, "CANVAS_ARTIFACT_UNSUPPORTED", required);
        }
        try {
            Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), context.getUserId(),
                    "OWNER", "EDITOR");
            accessService.requireFeature(context.getUserId(), workspace.getProjectId(), "canvas.generate");
            CanvasObject node = canvasMapper.selectCanvasObject(request.getWorkspaceId(), targetObjectId);
            if (!isTextNode(node)) {
                return capability(targetObjectId, null, false, "CANVAS_TEXT_TARGET_REQUIRED", required);
            }
            return capability(node.getObjectId(), String.valueOf(node.getObjectVersion()), true, null, required);
        } catch (ServiceException denied) {
            return capability(targetObjectId, null, false, "CANVAS_ACCESS_DENIED", required);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result adopt(Context context) {
        if (context == null || context.getUserId() == null || context.getArtifact() == null
                || context.getRequest() == null) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_ADOPTION_INVALID", null);
        }
        AidRuntimeArtifact artifact = context.getArtifact();
        ArtifactRequests.Adopt request = context.getRequest();
        if (!"FINALIZED".equals(artifact.getStatus()) || !Objects.equals(artifact.getContentHash(), request.getContentHash())
                || request.getWorkspaceId() == null || blank(request.getTargetObjectId())
                || blank(request.getExpectedTargetRevision()) || blank(request.getEditSessionId())
                || blank(request.getRuntimeLeaseId())) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_ADOPTION_INVALID", null);
        }

        AidAssistantSession session = sessionMapper.selectById(request.getSessionId());
        if (session == null || !Objects.equals(context.getUserId(), session.getUserId())
                || !"ASSISTANT".equals(session.getSessionType()) || !"0".equals(session.getDelFlag())
                || !Objects.equals(request.getWorkspaceId(), session.getWorkspaceId())) {
            throw failure(HttpStatus.FORBIDDEN, "CANVAS_SESSION_SCOPE_CHANGED", request.getTargetObjectId());
        }

        long expectedVersion = positiveRevision(request.getExpectedTargetRevision());
        Workspace workspace;
        try {
            workspace = accessService.requireWorkspace(request.getWorkspaceId(), context.getUserId(),
                    "OWNER", "EDITOR");
            accessService.requireFeature(context.getUserId(), workspace.getProjectId(), "canvas.generate");
        } catch (ServiceException denied) {
            throw failure(HttpStatus.FORBIDDEN, "CANVAS_ACCESS_DENIED", request.getTargetObjectId());
        }
        CanvasObject node = canvasMapper.selectCanvasObject(request.getWorkspaceId(), request.getTargetObjectId());
        if (!isTextNode(node) || !Objects.equals(node.getObjectVersion(), expectedVersion)) {
            throw failure(HttpStatus.CONFLICT, "CANVAS_TARGET_REVISION_CHANGED",
                    node == null ? null : String.valueOf(node.getObjectVersion()));
        }
        try {
            collaborationService.requireEditFence(request.getWorkspaceId(), request.getTargetObjectId(),
                    request.getEditSessionId(), request.getRuntimeLeaseId(), context.getUserId());
        } catch (ServiceException staleFence) {
            throw failure(HttpStatus.CONFLICT, "CANVAS_EDIT_FENCE_INVALID", request.getTargetObjectId());
        }

        String content = verifiedContent(context.getUserId(), artifact);
        String normalized = CanvasPlainText.normalize(content, null);
        if (normalized.length() > MAX_CANVAS_TEXT_LENGTH || !Objects.equals(normalized, content)) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_ARTIFACT_TEXT_UNSUPPORTED", null);
        }
        String operationId = CanvasAssistantOperationCanonicalizer.artifactAdoptionOperationId(
                artifact.getId(), request.getClientRequestId());
        CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
        mutation.setObjectId(request.getTargetObjectId());
        mutation.setObjectType("NODE");
        mutation.setAction("PATCH");
        mutation.setExpectedVersion(expectedVersion);
        mutation.setLockToken(request.getRuntimeLeaseId());
        mutation.setPayload(Map.of("textContent", content));
        CanvasRequests.Mutate mutate = new CanvasRequests.Mutate();
        mutate.setWorkspaceId(request.getWorkspaceId());
        mutate.setSessionId(request.getEditSessionId());
        mutate.setOperationId(operationId);
        mutate.setMutations(List.of(mutation));

        Map<String, Object> result;
        try {
            result = projectService.mutate(mutate, context.getUserId());
        } catch (ServiceException rejected) {
            throw failure(HttpStatus.CONFLICT, "CANVAS_MUTATION_REJECTED", request.getTargetObjectId());
        }
        Long newVersion = changedVersion(result, request.getTargetObjectId());
        if (newVersion == null || newVersion != expectedVersion + 1) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "CANVAS_ADOPTION_RECEIPT_INVALID", result);
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("objectId", request.getTargetObjectId());
        receipt.put("previousVersion", String.valueOf(expectedVersion));
        receipt.put("newVersion", String.valueOf(newVersion));
        receipt.put("workspaceId", String.valueOf(request.getWorkspaceId()));
        receipt.put("operationId", operationId);
        receipt.put("artifactId", String.valueOf(artifact.getId()));
        receipt.put("contentHash", artifact.getContentHash());
        return new Result(String.valueOf(newVersion), receipt);
    }

    private String verifiedContent(Long userId, AidRuntimeArtifact artifact) {
        int count = chunkMapper.countAuthorized(userId, artifact.getId());
        if (count < 0 || count > 1_000_000) throw failure(HttpStatus.INTERNAL_SERVER_ERROR,
                "CANVAS_ARTIFACT_CHUNKS_INVALID", count);
        List<AidRuntimeArtifactChunk> chunks = chunkMapper.selectAuthorizedPage(userId, artifact.getId(), -1,
                Math.max(1, count + 1));
        if (chunks.size() != count) throw failure(HttpStatus.INTERNAL_SERVER_ERROR,
                "CANVAS_ARTIFACT_CHUNKS_INVALID", chunks.size());
        StringBuilder content = new StringBuilder();
        int charStart = 0;
        for (int index = 0; index < chunks.size(); index++) {
            AidRuntimeArtifactChunk chunk = chunks.get(index);
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != index
                    || chunk.getCharStart() == null || chunk.getCharStart() != charStart
                    || chunk.getCharEnd() == null || chunk.getCharCount() == null
                    || chunk.getCharEnd() - chunk.getCharStart() != chunk.getCharCount()
                    || chunk.getContentText() == null || chunk.getContentText().length() != chunk.getCharCount()
                    || !SecureUtil.sha256(chunk.getContentText()).equals(chunk.getContentHash())) {
                throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "CANVAS_ARTIFACT_CHUNKS_INVALID", index);
            }
            content.append(chunk.getContentText());
            charStart = chunk.getCharEnd();
            if (content.length() > MAX_CANVAS_TEXT_LENGTH) {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_ARTIFACT_TEXT_UNSUPPORTED", content.length());
            }
        }
        if (!SecureUtil.sha256(content.toString()).equals(artifact.getContentHash())) {
            throw failure(HttpStatus.CONFLICT, "CANVAS_ARTIFACT_HASH_INVALID", artifact.getId());
        }
        return content.toString();
    }

    private static boolean isTextNode(CanvasObject node) {
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || node.getObjectVersion() == null || node.getContentJson() == null) return false;
        try {
            JSONObject content = JSON.parseObject(node.getContentJson());
            return content != null && "TEXT".equalsIgnoreCase(content.getString("nodeType"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static Capability capability(String objectId, String revision, boolean allowed, String reason,
                                         Map<String, Object> required) {
        return new Capability("FREE_CANVAS", objectId, revision, allowed, reason,
                "\u91c7\u7528\u5230\u753b\u5e03", required);
    }

    private static Map<String, Object> requiredContext(Long workspaceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (workspaceId != null) {
            value.put("workspaceId", String.valueOf(workspaceId));
        }
        value.put("editSessionId", "required");
        value.put("runtimeLeaseId", "required");
        value.put("runtimeLeaseKind", "CANVAS_EDIT_LOCK");
        return value;
    }

    private static Long changedVersion(Map<String, Object> result, String objectId) {
        if (result == null || !(result.get("objects") instanceof List<?> objects)) return null;
        for (Object value : objects) {
            if (!(value instanceof Map<?, ?> object)
                    || !Objects.equals(objectId, Objects.toString(object.get("objectId"), null))) continue;
            Object revision = object.get("objectVersion");
            if (revision instanceof Number number) return number.longValue();
            try { return revision == null ? null : Long.valueOf(revision.toString()); }
            catch (NumberFormatException invalid) { return null; }
        }
        return null;
    }

    private static long positiveRevision(String value) {
        if (blank(value) || !value.matches("[1-9][0-9]{0,18}")) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_TARGET_REVISION_INVALID", value);
        }
        try {
            long revision = Long.parseLong(value);
            if (revision < 1 || revision == Long.MAX_VALUE) throw failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CANVAS_TARGET_REVISION_INVALID", value);
            return revision;
        } catch (NumberFormatException invalid) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CANVAS_TARGET_REVISION_INVALID", value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static AssistantServiceException failure(HttpStatus status, String code, Object details) {
        return new AssistantServiceException(status.value(), code, "\u753b\u5e03\u91c7\u7528\u5931\u8d25", details);
    }
}
