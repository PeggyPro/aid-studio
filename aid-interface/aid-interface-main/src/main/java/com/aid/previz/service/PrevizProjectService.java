package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.previz.domain.PrevizProject;
import com.aid.previz.domain.PrevizProjectMember;
import com.aid.previz.mapper.PrevizProjectMapper;
import com.aid.previz.mapper.PrevizProjectMemberMapper;
import com.aid.previz.workspace.PrevizWorkspaceAccess;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** 管理独立三维工程并校验当前成员权限。 */
@Service
@RequiredArgsConstructor
public class PrevizProjectService implements PrevizWorkspaceAccess
{
    private final PrevizProjectMapper projects;
    private final PrevizProjectMemberMapper members;

    @Transactional
    public PrevizProject create(Long userId, String title, String clientRequestId)
    {
        requireUser(userId);
        if (title == null || title.isBlank() || title.length() > 120)
            throw new ServiceException("工程名称需为1至120个字符");
        if (clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new ServiceException("缺少有效操作标识");
        String digest = titleDigest(title.trim());
        PrevizProject existing = byCreateRequest(userId, clientRequestId);
        if (existing != null) return replayCreate(existing, digest);
        LocalDateTime now = LocalDateTime.now();
        PrevizProject project = new PrevizProject();
        project.setId(UUID.randomUUID().toString());
        project.setOwnerUserId(userId);
        project.setTitle(title.trim());
        project.setCreateRequestId(clientRequestId);
        project.setCreateRequestDigest(digest);
        project.setRevision(0L);
        project.setDelFlag("0");
        project.setCreateTime(now);
        project.setUpdateTime(now);
        project.setCreateBy(userId);
        project.setUpdateBy(userId);
        try
        {
            if (projects.insert(project) != 1) throw new ServiceException("工程创建失败");
        }
        catch (DuplicateKeyException conflict)
        {
            PrevizProject winner = projects.selectOne(Wrappers.<PrevizProject>lambdaQuery()
                    .eq(PrevizProject::getOwnerUserId, userId).eq(PrevizProject::getCreateRequestId, clientRequestId)
                    .last("FOR UPDATE"));
            if (winner == null) throw conflict;
            return replayCreate(winner, digest);
        }
        return project;
    }

    private PrevizProject byCreateRequest(Long userId, String key)
    {
        return projects.selectOne(Wrappers.<PrevizProject>lambdaQuery()
                .eq(PrevizProject::getOwnerUserId, userId).eq(PrevizProject::getCreateRequestId, key));
    }

    private static PrevizProject replayCreate(PrevizProject project, String digest)
    {
        if (!digest.equals(project.getCreateRequestDigest())) throw new ServiceException("操作标识已用于不同请求", 409);
        if (!"0".equals(project.getDelFlag())) throw new ServiceException("原操作对应工程已删除", 409);
        return project;
    }

    private static String titleDigest(String title)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException(exception);
        }
    }

    public PrevizProject detail(Long userId, String projectId)
    {
        return requireProject(userId, projectId, false);
    }

    @Override
    public void requireRead(Long userId, String projectId)
    {
        requireProject(userId, projectId, false);
    }

    @Override
    public void requireWrite(Long userId, String projectId)
    {
        requireProject(userId, projectId, true);
    }

    /** Fence admission against deletion only inside an existing short write transaction. */
    public void requireWritableScope(Long actor,String project) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                &&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())lockActiveProject(project);
        requireWrite(actor,project);
    }
    public void lockActiveProject(String project) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("项目准入需要事务");
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}"))rejectAccess();
        var row=projects.selectOne(Wrappers.<PrevizProject>lambdaQuery().select(PrevizProject::getId)
                .eq(PrevizProject::getId,project).eq(PrevizProject::getDelFlag,"0").last("FOR UPDATE"));
        if(row==null)rejectAccess();
    }

    private PrevizProject requireProject(Long userId, String projectId, boolean write)
    {
        requireUser(userId);
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,100}")) rejectAccess();
        // These are the full business metadata fields required by the detail response.
        PrevizProject project = projects.selectOne(Wrappers.<PrevizProject>lambdaQuery()
                .select(PrevizProject::getId, PrevizProject::getOwnerUserId, PrevizProject::getTitle,
                        PrevizProject::getPreferredEngine, PrevizProject::getModelCode, PrevizProject::getRevision,
                        PrevizProject::getCreateTime, PrevizProject::getUpdateTime)
                .eq(PrevizProject::getId, projectId).eq(PrevizProject::getDelFlag, "0"));
        if (project == null) rejectAccess();
        if (Objects.equals(project.getOwnerUserId(), userId)) {project.setAccessRole("OWNER");return project;}
        PrevizProjectMember member = members.selectOne(Wrappers.<PrevizProjectMember>lambdaQuery()
                .select(PrevizProjectMember::getRoleCode)
                .eq(PrevizProjectMember::getProjectId, projectId)
                .eq(PrevizProjectMember::getUserId, userId).eq(PrevizProjectMember::getDelFlag, "0"));
        if (member == null || !(write ? List.of("EDITOR") : List.of("EDITOR", "VIEWER")).contains(member.getRoleCode()))
            rejectAccess();
        project.setAccessRole(member.getRoleCode());
        return project;
    }

    private static void requireUser(Long userId)
    {
        if (userId == null || userId <= 0) throw new ServiceException("请先登录");
    }

    private static void rejectAccess()
    {
        throw new ServiceException("工程不存在或无权访问");
    }
}
