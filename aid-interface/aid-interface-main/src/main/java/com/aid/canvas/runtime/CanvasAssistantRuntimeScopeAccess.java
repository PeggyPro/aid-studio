package com.aid.canvas.runtime;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccess;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Resolves public assistant FREE_CANVAS refs from current Canvas state and permissions. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantRuntimeScopeAccess implements RuntimeScopeAccess {
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;

    @Override
    public boolean supports(String kind) {
        return "FREE_CANVAS".equals(kind);
    }

    @Override
    public ResolvedContextRef resolvePinned(Long userId, AssistantRequests.ContextRef requested) {
        Scope scope = requireScope(userId, requested);
        if (scope.object() == null) {
            if (!blank(requested.getVersion())
                    && !Objects.equals(scope.workspace().getRevision(), version(requested.getVersion(), true))) {
                stale("CANVAS_WORKSPACE_REVISION_CHANGED");
            }
            return workspaceRef(scope);
        }
        Long requestedVersion = version(requested.getVersion(), false);
        if (!Objects.equals(scope.object().getObjectVersion(), requestedVersion)) {
            stale("CANVAS_REVISION_CHANGED");
        }
        return objectRef(scope);
    }

    @Override
    public ResolvedContextRef resolveCurrent(Long userId, AssistantRequests.ContextRef identity) {
        if (!blank(identity == null ? null : identity.getVersion())) {
            stale("CANVAS_REFRESH_IDENTITY_INVALID");
        }
        Scope scope = requireScope(userId, identity);
        return scope.object() == null ? workspaceRef(scope) : objectRef(scope);
    }

    private Scope requireScope(Long userId, AssistantRequests.ContextRef requested) {
        if (userId == null || requested == null || !supports(requested.getKind())
                || requested.getWorkspaceId() == null || requested.getWorkspaceId() < 1) {
            stale("CANVAS_SCOPE_INVALID");
        }
        Workspace workspace;
        String role;
        try {
            workspace = accessService.requireWorkspace(requested.getWorkspaceId(), userId,
                    "OWNER", "EDITOR", "VIEWER");
            role = accessService.requireRole(workspace.getProjectId(), userId,
                    "OWNER", "EDITOR", "VIEWER");
        } catch (ServiceException denied) {
            throw new AssistantServiceException(HttpStatus.FORBIDDEN.value(), "ASSISTANT_SCOPE_DENIED",
                    "\u753b\u5e03\u8bbf\u95ee\u5df2\u62d2\u7edd", "CANVAS_WORKSPACE_DENIED");
        }
        if (requested.getProjectId() != null
                && !Objects.equals(requested.getProjectId(), workspace.getProjectId())) {
            stale("CANVAS_PROJECT_CHANGED");
        }
        CanvasObject object = null;
        if (!blank(requested.getObjectId())) {
            object = canvasMapper.selectCanvasObject(workspace.getId(), requested.getObjectId());
            if (object == null || !"NODE".equals(object.getObjectType())
                    || !Objects.equals(object.getDeleted(), 0)) {
                stale("CANVAS_OBJECT_UNAVAILABLE");
            }
        }
        String access = SetAccess.mutate(role) ? "MUTATE" : "READ";
        return new Scope(workspace, object, access);
    }

    private ResolvedContextRef workspaceRef(Scope scope) {
        AssistantRequests.ContextRef ref = baseRef(scope);
        ref.setVersion(String.valueOf(scope.workspace().getRevision()));
        return new ResolvedContextRef(ref,
                "canvas:workspace:" + scope.workspace().getId() + ":" + scope.workspace().getRevision(),
                scope.access());
    }

    private ResolvedContextRef objectRef(Scope scope) {
        AssistantRequests.ContextRef ref = baseRef(scope);
        ref.setObjectId(scope.object().getObjectId());
        ref.setVersion(String.valueOf(scope.object().getObjectVersion()));
        return new ResolvedContextRef(ref,
                "canvas:node:" + scope.workspace().getId() + ":" + scope.object().getObjectId()
                        + ":" + scope.object().getObjectVersion(), scope.access());
    }

    private AssistantRequests.ContextRef baseRef(Scope scope) {
        AssistantRequests.ContextRef ref = new AssistantRequests.ContextRef();
        ref.setKind("FREE_CANVAS");
        ref.setProjectId(scope.workspace().getProjectId());
        ref.setWorkspaceId(scope.workspace().getId());
        return ref;
    }

    private static Long version(String value, boolean workspace) {
        // Workspaces start at revision zero; persisted nodes start at version one.
        String canonicalVersion = workspace ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}";
        if (blank(value) || !value.matches(canonicalVersion)) stale("CANVAS_REVISION_INVALID");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException invalid) {
            stale("CANVAS_REVISION_INVALID");
            return null;
        }
    }

    private static void stale(String reason) {
        throw new AssistantServiceException(HttpStatus.CONFLICT.value(), "ASSISTANT_CONTEXT_STALE",
                "\u753b\u5e03\u4e0a\u4e0b\u6587\u5df2\u53d8\u66f4", reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Scope(Workspace workspace, CanvasObject object, String access) { }

    private static final class SetAccess {
        private SetAccess() { }
        private static boolean mutate(String role) {
            return "OWNER".equals(role) || "EDITOR".equals(role);
        }
    }
}
