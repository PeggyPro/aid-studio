package com.aid.director.canvas;

import com.aid.aid.domain.AidComicProject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.director.DirectorException;
import com.aid.director.DirectorScopeAccess;
import com.aid.director.ProjectDirectorScopeAccess;
import com.aid.aid.service.IAidComicProjectService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.director.canvas.mapper.DirectorCanvasLockMapper;
import com.aid.project.service.IProjectContentGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

/** 只存在于画布分支，复用工作区、成员角色和登记素材的权威服务。 */
@Primary
@Service
@RequiredArgsConstructor
public class CanvasDirectorScopeAccess implements DirectorScopeAccess {
    private final CanvasAccessService access;
    private final CanvasResourceService resources;
    private final CanvasMapper canvas;
    private final DirectorCanvasLockMapper locks;
    private final IProjectContentGuardService contentGuard;
    private final ProjectDirectorScopeAccess ordinaryProjects;
    private final IAidComicProjectService projects;

    @Override
    public AidComicProject require(Long projectId, Long workspaceId, Long userId, boolean write) {
        if (workspaceId == null) {
            AidComicProject kind = projects.getOne(Wrappers.<AidComicProject>lambdaQuery()
                    .select(AidComicProject::getId, AidComicProject::getProjectType).eq(AidComicProject::getId, projectId));
            if (kind != null && !"canvas".equals(kind.getProjectType())) return ordinaryProjects.require(projectId, null, userId, write);
        }
        if (write && TransactionSynchronizationManager.isActualTransactionActive()) {
            // 项目锁已由调用方取得；成员DML与此行锁串行，撤权后不能交叉写入。
            locks.member(projectId, userId);
            if (workspaceId != null && canvas.selectWorkspaceForUpdate(workspaceId) == null) throw DirectorException.denied();
        }
        access.requireRole(projectId, userId, write ? new String[]{"OWNER", "EDITOR"} : new String[]{"OWNER", "EDITOR", "VIEWER"});
        if (workspaceId != null) {
            var workspace = access.requireWorkspace(workspaceId, userId);
            if (!projectId.equals(workspace.getProjectId())) throw DirectorException.denied();
        }
        AidComicProject project = access.requireProject(projectId, userId);
        if (write) contentGuard.assertProjectEditable(project);
        return project;
    }

    @Override
    public void requireResources(Long projectId, Long workspaceId, Long userId, List<Long> ids) {
        if (ids.isEmpty()) return;
        if (workspaceId == null) throw DirectorException.invalid();
        require(projectId, workspaceId, userId, false);
        resources.requireResources(workspaceId, ids, userId);
    }
}
