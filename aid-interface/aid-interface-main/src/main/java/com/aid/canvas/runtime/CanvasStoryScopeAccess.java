package com.aid.canvas.runtime;

import com.aid.canvas.service.CanvasAccessService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.RuntimeToolInvocation;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Resolves screenplay scope from the same authorized workspace used by the Canvas assistant. */
@Component
public class CanvasStoryScopeAccess {
    private final CanvasAccessService accessService;

    public CanvasStoryScopeAccess(CanvasAccessService accessService) {
        this.accessService = Objects.requireNonNull(accessService);
    }

    /** Pure discovery over the server-produced manifest; this does not authorize an operation. */
    public static Long planningProjectId(Long projectId, Long workspaceId, String manifestJson) {
        if (workspaceId == null || workspaceId < 1) throw denied();
        RuntimeToolInvocation manifest = new RuntimeToolInvocation();
        manifest.setContextManifestJson(manifestJson);
        var workspace = CanvasAssistantContextManifest.requireWorkspace(
                manifest, String.valueOf(workspaceId), true);
        if (projectId != null && projectId != 0L && !Objects.equals(projectId, workspace.projectId()))
            throw denied();
        CanvasAssistantContextManifest.requireScope(manifestJson, workspace.projectId(), workspaceId, true);
        return workspace.projectId();
    }

    /** Recheck live membership before preparation, execution or receipt reconciliation. */
    public Long authorizeProject(Long userId, Long projectId, Long workspaceId, String manifestJson) {
        if (userId == null || userId < 1) throw denied();
        Long expectedProject = planningProjectId(projectId, workspaceId, manifestJson);
        var workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        if (!Objects.equals(workspace.getProjectId(), expectedProject)) throw denied();
        return expectedProject;
    }

    private static ServiceException denied() {
        return new ServiceException("STORY_CANVAS_SCOPE_INVALID");
    }
}
