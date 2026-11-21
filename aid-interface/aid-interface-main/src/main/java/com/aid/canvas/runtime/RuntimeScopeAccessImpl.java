package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimeScopeAccessImpl implements RuntimeScopeAccess {
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final CanvasCollaborationService collaborationService;

    @Override
    public WorkspaceScope authorizeWorkspace(Long runId, Long userId, Lease lease,
                                             Long projectId, Long workspaceId) {
        return requireWorkspaceCurrent(runId, userId, lease, projectId, workspaceId, true);
    }

    @Override
    public Scope authorizeTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                                 String objectId, Long objectVersion) {
        WorkspaceScope workspace = requireWorkspaceCurrent(runId, userId, lease, projectId, workspaceId, true);
        return targetScope(workspace, objectId, objectVersion);
    }

    @Override
    public Scope authorizeExactTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                                      String objectId, Long objectVersion) {
        return requireTargetCurrent(runId, userId, lease, projectId, workspaceId, objectId, objectVersion,
                true, false);
    }

    @Override
    public Scope reserveTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                               String objectId, Long objectVersion) {
        Scope scope = requireTargetCurrent(runId, userId, lease, projectId, workspaceId,
                objectId, objectVersion, true, true);
        long nodeFence = collaborationService.beginAssistantRuntimeLock(scope.workspaceId(), scope.objectId(),
                scope.objectVersion(), scope.userId(), scope.runId(), scope.runtimeFencingToken());
        boolean retained = false;
        try {
            Scope current = requireTargetCurrent(runId, userId, lease, projectId, workspaceId,
                    objectId, objectVersion, true, true);
            if (!Objects.equals(scope.objectVersion(), current.objectVersion())) {
                throw failure("CANVAS_REVISION_CHANGED");
            }
            retained = true;
            return withNodeFence(current, nodeFence);
        } finally {
            if (!retained) {
                collaborationService.releaseAssistantRuntimeLock(scope.workspaceId(), scope.objectId(),
                        scope.userId(), scope.runId(), scope.runtimeFencingToken());
            }
        }
    }

    @Override
    public void release(Scope scope) {
        if (scope == null || scope.runId() == null || scope.userId() == null || scope.workspaceId() == null
                || scope.objectId() == null || scope.runtimeFencingToken() == null) {
            throw failure("CANVAS_SCOPE_INCOMPLETE");
        }
        collaborationService.releaseAssistantRuntimeLock(scope.workspaceId(), scope.objectId(), scope.userId(),
                scope.runId(), scope.runtimeFencingToken());
    }

    private WorkspaceScope requireWorkspaceCurrent(Long runId, Long userId, Lease lease,
                                                   Long projectId, Long workspaceId, boolean requireGenerate) {
        if (runId == null || userId == null || lease == null || blank(lease.owner())
                || lease.fencingToken() == null || lease.fencingToken() < 1 || lease.leaseUntil() == null
                || !lease.leaseUntil().after(new Date())) {
            throw failure("RUNTIME_LEASE_INVALID");
        }
        Map<String, Object> row = canvasMapper.selectAssistantRuntimeScope(runId);
        Date persistedLeaseUntil = date(row, "leaseUntil");
        if (row == null || !Objects.equals(runId, number(row, "runId"))
                || !Objects.equals(userId, number(row, "userId"))
                || !Objects.equals(userId, number(row, "sessionUserId"))
                || !Objects.equals(runId, number(row, "activeRuntimeRunId"))
                || !"ASSISTANT".equals(text(row, "sessionType"))
                || !"ASSISTANT_LOOP".equals(text(row, "executorStrategy"))
                || !RuntimeScopeAccess.permitsNewOperation(text(row, "status"), text(row, "nextAction"))
                || !Boolean.FALSE.equals(row.get("stopRequested"))
                || !"0".equals(text(row, "delFlag"))
                || !"0".equals(text(row, "sessionDelFlag"))
                || !Objects.equals(lease.owner(), text(row, "leaseOwner"))
                || !Objects.equals(lease.fencingToken(), number(row, "runtimeFencingToken"))
                || persistedLeaseUntil == null || !persistedLeaseUntil.after(new Date())
                || lease.leaseUntil().after(persistedLeaseUntil)) {
            throw failure("RUNTIME_SCOPE_FENCED");
        }
        Long runProjectId = specifiedProject(number(row, "projectId"));
        Long sessionProjectId = specifiedProject(number(row, "sessionProjectId"));
        if (projectId == null || projectId < 1 || workspaceId == null
                || !Objects.equals(runProjectId, sessionProjectId)
                || runProjectId != null && !Objects.equals(projectId, runProjectId)
                || !Objects.equals(workspaceId, number(row, "workspaceId"))
                || !Objects.equals(workspaceId, number(row, "sessionWorkspaceId"))) {
            throw failure("CANVAS_WORKSPACE_SCOPE_CHANGED");
        }
        // Free Canvas sessions keep the unrelated episode-project field empty.
        // The server-frozen, digest-checked Canvas refs determine their project.
        requirePinnedCanvasScope(row, projectId, workspaceId, requireGenerate);
        Workspace workspace = requireGenerate
                ? accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR")
                : accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR", "VIEWER");
        if (!Objects.equals(projectId, workspace.getProjectId())) {
            throw failure("CANVAS_PROJECT_SCOPE_CHANGED");
        }
        if (requireGenerate) accessService.requireFeature(userId, projectId, "canvas.generate");
        return new WorkspaceScope(runId, userId, projectId, workspaceId, workspace.getRevision(), lease.owner(),
                lease.fencingToken(), persistedLeaseUntil);
    }

    private static Long specifiedProject(Long value) {
        return Objects.equals(value, 0L) ? null : value;
    }

    private static void requirePinnedCanvasScope(Map<String, Object> row, Long projectId, Long workspaceId,
                                                boolean mutate) {
        JSONObject manifest;
        try {
            JSONObject request = JSON.parseObject(text(row, "requestJson"));
            manifest = request == null ? null : request.getJSONObject("_contextManifest");
        } catch (RuntimeException invalid) {
            throw failure("CANVAS_MANIFEST_INVALID");
        }
        if (manifest == null || !Objects.equals(text(row, "contextDigest"),
                SecureUtil.sha256(manifest.toJSONString()))) {
            throw failure("CANVAS_MANIFEST_INVALID");
        }
        CanvasAssistantContextManifest.requireScope(manifest.toJSONString(), projectId, workspaceId, mutate);
    }

    private Scope requireTargetCurrent(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                                       String objectId, Long objectVersion, boolean requireExactNodeVersion,
                                       boolean requireGenerate) {
        WorkspaceScope workspace = requireWorkspaceCurrent(runId, userId, lease, projectId, workspaceId,
                requireGenerate);
        Scope scope = targetScope(workspace, objectId, objectVersion);
        CanvasObject node = canvasMapper.selectCanvasObject(workspaceId, objectId);
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || (requireExactNodeVersion && !Objects.equals(node.getObjectVersion(), objectVersion))) {
            throw failure("CANVAS_REVISION_STALE");
        }
        return scope;
    }

    private static Scope targetScope(WorkspaceScope workspace, String objectId, Long objectVersion) {
        if (blank(objectId) || objectVersion == null || objectVersion < 1) {
            throw failure("CANVAS_TARGET_INVALID");
        }
        return new Scope(workspace.runId(), workspace.userId(), workspace.projectId(), workspace.workspaceId(),
                objectId, objectVersion, workspace.leaseOwner(),
                workspace.runtimeFencingToken(), workspace.leaseUntil(), null);
    }

    private static Scope withNodeFence(Scope scope, long nodeFence) {
        return new Scope(scope.runId(), scope.userId(), scope.projectId(), scope.workspaceId(), scope.objectId(),
                scope.objectVersion(), scope.leaseOwner(), scope.runtimeFencingToken(), scope.leaseUntil(), nodeFence);
    }

    private static Long number(Map<String, Object> row, String key) {
        if (row == null) return null;
        Object value = row.get(key);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Date date(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Date date ? date : null;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ServiceException failure(String code) {
        return new ServiceException("\u753b\u5e03\u8303\u56f4\u5931\u6548").setDetailMessage(code);
    }
}
