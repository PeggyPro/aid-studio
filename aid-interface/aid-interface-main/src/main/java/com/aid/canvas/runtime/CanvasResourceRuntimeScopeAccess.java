package com.aid.canvas.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccess;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Resolves assistant resource references through server-owned Canvas resource records. */
@Component
@RequiredArgsConstructor
public class CanvasResourceRuntimeScopeAccess implements RuntimeScopeAccess {
    private final CanvasAccessService accessService;
    private final CanvasResourceService resourceService;

    @Override
    public boolean supports(String kind) {
        return "CANVAS_RESOURCE".equals(kind);
    }

    @Override
    public ResolvedContextRef resolvePinned(Long userId, AssistantRequests.ContextRef requested) {
        Resolved resolved = requireResource(userId, requested);
        if (StrUtil.isNotBlank(requested.getContentHash())
                && !Objects.equals(requested.getContentHash(), resolved.contentHash())) {
            stale("CANVAS_RESOURCE_CHANGED");
        }
        return contextRef(resolved);
    }

    @Override
    public ResolvedContextRef resolveCurrent(Long userId, AssistantRequests.ContextRef identity) {
        if (identity != null && (StrUtil.isNotBlank(identity.getContentHash())
                || StrUtil.isNotBlank(identity.getVersion()))) {
            stale("CANVAS_RESOURCE_REFRESH_IDENTITY_INVALID");
        }
        return contextRef(requireResource(userId, identity));
    }

    private Resolved requireResource(Long userId, AssistantRequests.ContextRef requested) {
        if (userId == null || requested == null || !supports(requested.getKind())
                || requested.getWorkspaceId() == null || requested.getWorkspaceId() < 1
                || requested.getResourceId() == null || requested.getResourceId() < 1) {
            stale("CANVAS_RESOURCE_SCOPE_INVALID");
        }
        Workspace workspace;
        try {
            workspace = accessService.requireWorkspace(requested.getWorkspaceId(), userId,
                    "OWNER", "EDITOR", "VIEWER");
        } catch (ServiceException denied) {
            throw new AssistantServiceException(HttpStatus.FORBIDDEN.value(), "ASSISTANT_SCOPE_DENIED",
                    "画布素材访问已拒绝", "CANVAS_RESOURCE_DENIED");
        }
        CanvasResource resource;
        try {
            List<CanvasResource> rows = resourceService.requireResources(
                    workspace.getId(), List.of(requested.getResourceId()), userId);
            resource = rows.get(0);
        } catch (RuntimeException unavailable) {
            stale("CANVAS_RESOURCE_UNAVAILABLE");
            return null;
        }
        if (requested.getProjectId() != null
                && !Objects.equals(requested.getProjectId(), workspace.getProjectId())) {
            stale("CANVAS_RESOURCE_PROJECT_CHANGED");
        }
        if (!Objects.equals(resource.getProjectId(), workspace.getProjectId())
                || !Objects.equals(resource.getWorkspaceId(), workspace.getId())
                || !"ACTIVE".equals(resource.getStatus()) || StrUtil.isBlank(resource.getStorageUrl())
                || StrUtil.isBlank(resource.getMediaType())) {
            stale("CANVAS_RESOURCE_UNAVAILABLE");
        }
        return new Resolved(workspace, resource, identityHash(resource));
    }

    /** Stable server-owned resource identity used again immediately before model delivery. */
    public static String identityHash(CanvasResource resource) {
        if (resource == null) return null;
        JSONObject identity = new JSONObject();
        identity.put("resourceId", String.valueOf(resource.getId()));
        identity.put("projectId", String.valueOf(resource.getProjectId()));
        identity.put("workspaceId", String.valueOf(resource.getWorkspaceId()));
        identity.put("mediaType", resource.getMediaType());
        identity.put("storageUrl", resource.getStorageUrl());
        identity.put("fileSize", resource.getFileSize());
        identity.put("mimeType", resource.getMimeType());
        identity.put("metadata", resource.getMetadataJson());
        return SecureUtil.sha256(identity.toJSONString());
    }

    private ResolvedContextRef contextRef(Resolved resolved) {
        AssistantRequests.ContextRef ref = new AssistantRequests.ContextRef();
        ref.setKind("CANVAS_RESOURCE");
        ref.setProjectId(resolved.workspace().getProjectId());
        ref.setWorkspaceId(resolved.workspace().getId());
        ref.setResourceId(resolved.resource().getId());
        ref.setContentHash(resolved.contentHash());
        return new ResolvedContextRef(ref,
                "canvas:resource:" + resolved.workspace().getId() + ":"
                        + resolved.resource().getId() + ":" + resolved.contentHash(), "READ");
    }

    private static void stale(String reason) {
        throw new AssistantServiceException(HttpStatus.CONFLICT.value(), "ASSISTANT_CONTEXT_STALE",
                "画布素材上下文已变更", reason);
    }

    private record Resolved(Workspace workspace, CanvasResource resource, String contentHash) { }
}
