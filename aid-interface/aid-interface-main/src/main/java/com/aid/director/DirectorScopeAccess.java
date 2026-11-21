package com.aid.director;

import com.aid.aid.domain.AidComicProject;
import java.util.List;

/** 复用项目或工作区权威权限的适配入口。 */
public interface DirectorScopeAccess {
    AidComicProject require(Long projectId, Long workspaceId, Long userId, boolean write);
    void requireResources(Long projectId, Long workspaceId, Long userId, List<Long> resourceIds);
}
