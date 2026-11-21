package com.aid.director;

import com.aid.aid.domain.AidComicProject;
import com.aid.project.service.IUserProjectBusinessService;
import com.aid.project.service.IProjectContentGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 普通项目归属与公开锁定校验。 */
@Service
@RequiredArgsConstructor
public class ProjectDirectorScopeAccess implements DirectorScopeAccess {
    private final IUserProjectBusinessService projects;
    private final IProjectContentGuardService guard;

    @Override
    public AidComicProject require(Long projectId, Long workspaceId, Long userId, boolean write) {
        if (workspaceId != null) throw new DirectorException("WORKSPACE_UNAVAILABLE", 409, "工作区尚未就绪");
        AidComicProject project = projects.selectUserProjectById(projectId, userId);
        if (project == null) throw DirectorException.denied();
        if (write) guard.assertProjectEditable(project);
        return project;
    }

    @Override
    public void requireResources(Long projectId, Long workspaceId, Long userId, List<Long> resourceIds) {
        if (!resourceIds.isEmpty()) throw new DirectorException("RESOURCE_UNAVAILABLE", 409, "素材尚未登记");
    }
}
