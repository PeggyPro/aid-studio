package com.aid.director;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidComicProjectService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 持久化事务与项目权限变更共享的资源守卫。 */
@Component
@RequiredArgsConstructor
public class DirectorPermissionGuard {
    private final IAidComicProjectService projects;
    private final DirectorScopeAccess scopes;

    public void require(Long projectId, Long workspaceId, Long userId, boolean write) {
        if (write && TransactionSynchronizationManager.isActualTransactionActive()) {
            // 项目行锁先于场景与机位锁，阻止公开/删除与本次写入交叉提交。
            AidComicProject project = projects.getOne(Wrappers.<AidComicProject>lambdaQuery()
                    .select(AidComicProject::getId, AidComicProject::getUserId, AidComicProject::getDelFlag,
                            AidComicProject::getIsPublic)
                    .eq(AidComicProject::getId, projectId).last("FOR UPDATE"));
            if (project == null || !"0".equals(project.getDelFlag())) throw DirectorException.denied();
        }
        scopes.require(projectId, workspaceId, userId, write);
    }
}
