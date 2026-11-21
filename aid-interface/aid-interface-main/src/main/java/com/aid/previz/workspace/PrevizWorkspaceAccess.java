package com.aid.previz.workspace;

/** 校验独立三维项目的当前成员权限。 */
public interface PrevizWorkspaceAccess
{
    void requireRead(Long userId, String projectId);

    void requireWrite(Long userId, String projectId);
}
