package com.aid.canvas.service.impl;

import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.canvas.service.CanvasRuntimeReservationService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CanvasRuntimeReservationServiceImpl implements CanvasRuntimeReservationService {
    private static final Set<String> ACTIVE = Set.of("CREATED", "RUNNING", "WAITING_INPUT");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    private final CanvasAccessService accessService;
    private final CanvasMapper canvasMapper;
    private final CanvasCollaborationService collaborationService;
    private final AidRuntimeRunMapper runtimeRunMapper;

    @Override
    public Reservation reserve(Long runId, Long workspaceId, String objectId, Long expectedVersion,
                               String editSessionId, String lockToken, Long userId) {
        AidRuntimeRun run = runtimeRunMapper.selectById(runId);
        if (run == null || !ACTIVE.contains(run.getStatus()) || !"0".equals(run.getDelFlag())
                || !Objects.equals(run.getUserId(), userId)
                || !Objects.equals(run.getWorkspaceId(), workspaceId)
                || !Objects.equals(run.getNodeObjectId(), objectId)
                || !Objects.equals(run.getNodeVersion(), expectedVersion)
                || !Set.of("SKILL", "AGENT").contains(run.getMemberType())) {
            throw new ServiceException("运行与画布节点绑定不匹配");
        }
        Workspace workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        accessService.requireFeature(userId, workspace.getProjectId(), "canvas.generate");
        CanvasObject node = canvasMapper.selectCanvasObject(workspaceId, objectId);
        if (node == null || node.getDeleted() != 0 || !"NODE".equals(node.getObjectType())
                || !Objects.equals(node.getObjectVersion(), expectedVersion)
                || (run.getProjectId() != null && !Objects.equals(run.getProjectId(), workspace.getProjectId()))) {
            throw new ServiceException("运行节点版本已变化");
        }
        collaborationService.beginRuntimeLock(workspaceId, objectId, editSessionId, lockToken, userId, runId);
        CanvasObject current = canvasMapper.selectCanvasObjectForUpdate(workspaceId, objectId);
        if (current == null || !Objects.equals(current.getObjectVersion(), expectedVersion)
                || !Objects.equals(current.getDeleted(), 0)) throw new ServiceException("运行节点版本已变化");
        return new Reservation(workspace.getProjectId(), workspaceId, objectId, expectedVersion, userId);
    }

    @Override
    public Reservation assertReserved(Long runId, Long userId) {
        AidRuntimeRun run = runtimeRunMapper.selectById(runId);
        if (run == null || !"0".equals(run.getDelFlag()) || !ACTIVE.contains(run.getStatus())
                || !Objects.equals(userId, run.getUserId()) || run.getWorkspaceId() == null
                || run.getNodeObjectId() == null || run.getNodeVersion() == null) {
            throw new ServiceException("画布运行占用不存在或已经结束");
        }
        Workspace workspace = accessService.requireWorkspace(run.getWorkspaceId(), userId, "OWNER", "EDITOR");
        CanvasObject node = canvasMapper.selectCanvasObject(run.getWorkspaceId(), run.getNodeObjectId());
        if (node == null || node.getDeleted() != 0 || !"NODE".equals(node.getObjectType())
                || !Objects.equals(node.getObjectVersion(), run.getNodeVersion())
                || !Objects.equals(run.getProjectId(), workspace.getProjectId())) {
            throw new ServiceException("运行节点版本已变化");
        }
        return new Reservation(workspace.getProjectId(), workspace.getId(), node.getObjectId(),
                node.getObjectVersion(), userId);
    }

    @Override
    public void renew(Long runId) {
        AidRuntimeRun run = runtimeRunMapper.selectById(runId);
        if (run == null || !ACTIVE.contains(run.getStatus()) || run.getWorkspaceId() == null
                || run.getNodeObjectId() == null) return;
        assertReserved(runId, run.getUserId());
        collaborationService.renewRuntimeLock(run.getWorkspaceId(), run.getNodeObjectId(), run.getUserId(), runId);
    }

    @Override
    public void release(Long runId) {
        AidRuntimeRun run = runtimeRunMapper.selectById(runId);
        if (run == null || run.getWorkspaceId() == null || run.getNodeObjectId() == null) return;
        if (!TERMINAL.contains(run.getStatus())) throw new ServiceException("未终结的运行不能释放画布占用");
        collaborationService.releaseRuntimeLock(run.getWorkspaceId(), run.getNodeObjectId(), run.getUserId(), runId);
    }
}
