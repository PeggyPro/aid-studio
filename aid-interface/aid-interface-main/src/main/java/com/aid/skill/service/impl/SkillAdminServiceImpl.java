package com.aid.skill.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.service.IAdminUploadService;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.core.domain.SysConfig;
import com.aid.core.service.ISysConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.businessgroup.domain.AidBusinessGroupMember;
import com.aid.businessgroup.domain.AidBusinessGroup;
import com.aid.businessgroup.mapper.AidBusinessGroupMemberMapper;
import com.aid.businessgroup.mapper.AidBusinessGroupMapper;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeReferenceErrors;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.aid.skill.service.ISkillAdminService;
import com.aid.skill.service.ISkillPackageAdminService;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.aid.skill.service.SkillCoverPolicy;
import com.aid.skill.service.SkillRuntimeCapabilities;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.vo.SkillAdminVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Administration is independent from the removed legacy chat implementation. */
@Service
@RequiredArgsConstructor
public class SkillAdminServiceImpl implements ISkillAdminService {
    private static final String NORMAL = "0";
    private static final String DELETED = "1";
    private static final String DISABLED = "1";
    private static final Pattern SKILL_CODE = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final String PUBLICATION_CONFIG_KEY = "aid.skill.allowUserPublicSkills";

    private final AidSkillMapper skillMapper;
    private final AidSkillRelationMapper relationMapper;
    private final AidSkillRunMapper runMapper;
    private final AidSkillRunStepMapper stepMapper;
    private final AidSkillRunTaskLinkMapper taskLinkMapper;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final SkillModelService skillModelService;
    private final SkillPackageResourceLoader packageResourceLoader;
    private final AidBusinessGroupMemberMapper groupMemberMapper;
    private final AidBusinessGroupMapper groupMapper;
    private final AidRuntimeAgentMapper agentMapper;
    private final AidRuntimeAgentSkillRefMapper agentSkillRefs;
    private final AidRuntimeRunMapper runtimeRuns;
    private final ISkillPackageAdminService packageAdminService;
    private final ISysConfigService sysConfigService;
    private final IAdminUploadService adminUploadService;
    private final SkillCoverPolicy skillCoverPolicy;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillAdminVO.SkillSummary createSkill(SkillAdminRequests.CreateRequest request,
                                                  Long operatorId, String operatorName, boolean platform) {
        if (!platform) {
            throw new ServiceException("请通过Skill创建器创建");
        }
        String skillCode = request.getSkillCode();
        if (skillCode == null || !SKILL_CODE.matcher(skillCode).matches()) {
            throw new ServiceException("Skill编码须为2-64位，以小写字母开头，且仅含小写字母、数字和下划线");
        }
        AidSkill skill = new AidSkill();
        skill.setSkillCode(skillCode);
        skill.setName(StrUtil.trim(request.getName()));
        skill.setDescription(StrUtil.trim(request.getDescription()));
        skill.setIconUrl(StrUtil.trim(request.getIconUrl()));
        skill.setOwnerType(platform ? "PLATFORM" : "USER");
        skill.setOwnerUserId(platform ? null : operatorId);
        // A new identity is only a disabled editing shell. Making it public is a
        // separate, cover-verified transition before the first immutable publish.
        skill.setVisibility("PRIVATE");
        skill.setCacheToDisk(platform && Boolean.TRUE.equals(request.getCacheToDisk()));
        skill.setInvocationScope(request.getInvocationScope());
        skill.setExecutorType("PROMPT");
        skill.setModelCode("");
        skill.setInputSchemaJson("{}");
        skill.setOutputSchemaJson("{}");
        skill.setSystemPrompt("");
        skill.setDefinitionJson("{\"outputKind\":\"TEXT\"}");
        skill.setReasoningPolicy("DISABLED");
        skill.setDefaultReasoningEnabled(false);
        skill.setShowReasoningDefault(false);
        skill.setReasoningBudgetTokens(0);
        skill.setMaxOutputTokens(8192);
        skill.setContextWindowTokens(128000);
        skill.setSafetyMarginTokens(2048);
        skill.setConfigHash(SecureUtil.sha256("unpublished|" + skill.getSkillCode()));
        skill.setStatus(DISABLED);
        skill.setDelFlag(NORMAL);
        skill.setCreateBy(operator(operatorName, operatorId));
        skill.setCreateTime(new Date());
        skill.setUpdateBy(operator(operatorName, operatorId));
        skill.setUpdateTime(new Date());
        try {
            if (skillMapper.insert(skill) != 1) throw new ServiceException("Skill创建失败");
        } catch (DuplicateKeyException error) {
            throw new ServiceException("Skill编码已存在");
        }
        if (request.getTemplateVersionId() != null) {
            packageAdminService.copyVersionToNewSkill(skill.getId(), request.getTemplateVersionId(),
                    operatorId, operatorName);
        }
        return toSkillSummary(skill);
    }

    @Override
    public SkillAdminVO.PageResult<SkillAdminVO.SkillSummary> pageSkills(
            SkillAdminRequests.PageRequest request) {
        SkillAdminRequests.PageRequest source = Objects.requireNonNullElseGet(
                request, SkillAdminRequests.PageRequest::new);
        boolean orderByOwnerIndex = source.getIncludeDeleted() != null && StrUtil.isNotBlank(source.getOwnerType());
        Page<AidSkill> page = skillMapper.selectPage(
                new Page<>(positive(source.getPageNum(), 1), bounded(source.getPageSize(), 20, 100)),
                new LambdaQueryWrapper<AidSkill>()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getDescription, AidSkill::getCapabilityDescription,
                                AidSkill::getIconUrl, AidSkill::getOwnerType, AidSkill::getOwnerUserId,
                                AidSkill::getVisibility, AidSkill::getCacheToDisk,
                                AidSkill::getModerationReason, AidSkill::getModeratedBy,
                                AidSkill::getModeratedAt, AidSkill::getInvocationScope,
                                AidSkill::getCurrentVersionId, AidSkill::getStatus, AidSkill::getDelFlag,
                                AidSkill::getModelCode, AidSkill::getReasoningPolicy, AidSkill::getUpdateTime)
                        .ne(AidSkill::getSkillCode, SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT)
                        .eq(source.getSkillId() != null, AidSkill::getId, source.getSkillId())
                        .eq(Boolean.FALSE.equals(source.getIncludeDeleted()), AidSkill::getDelFlag, "0")
                        .and(StrUtil.isNotBlank(source.getKeyword()), query -> query
                                .like(AidSkill::getName, source.getKeyword())
                                .or().like(AidSkill::getSkillCode, source.getKeyword()))
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidSkill::getStatus, source.getStatus())
                        .eq(StrUtil.isNotBlank(source.getOwnerType()), AidSkill::getOwnerType, source.getOwnerType())
                        .eq(source.getOwnerUserId() != null, AidSkill::getOwnerUserId, source.getOwnerUserId())
                        .eq(StrUtil.isNotBlank(source.getVisibility()), AidSkill::getVisibility, source.getVisibility())
                        .eq(StrUtil.isNotBlank(source.getInvocationScope()), AidSkill::getInvocationScope,
                                source.getInvocationScope())
                        .eq(source.getCacheToDisk() != null, AidSkill::getCacheToDisk, source.getCacheToDisk())
                        .orderByDesc(orderByOwnerIndex && source.getOwnerUserId() == null, AidSkill::getOwnerUserId)
                        .orderByDesc(orderByOwnerIndex && StrUtil.isBlank(source.getStatus()), AidSkill::getStatus)
                        .orderByDesc(orderByOwnerIndex && Boolean.TRUE.equals(source.getIncludeDeleted()), AidSkill::getDelFlag)
                        .orderByDesc(AidSkill::getId));
        return new SkillAdminVO.PageResult<>(page.getTotal(), page.getRecords().stream()
                .map(this::toSkillSummary).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateIdentity(SkillAdminRequests.IdentitySaveRequest request,
                               Long operatorId, String operatorName) {
        updateIdentity(request, operatorId, operatorName, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOwnedIdentity(SkillAdminRequests.IdentitySaveRequest request,
                                    Long ownerUserId, String operatorName) {
        updateIdentity(request, ownerUserId, operatorName, ownerUserId);
    }

    private void updateIdentity(SkillAdminRequests.IdentitySaveRequest request,
                                Long operatorId, String operatorName, Long requiredOwnerUserId) {
        AidSkill skill = requireActiveSkillForUpdate(request.getId());
        if (requiredOwnerUserId != null && (!"USER".equals(skill.getOwnerType())
                || !Objects.equals(skill.getOwnerUserId(), requiredOwnerUserId))) {
            throw new ServiceException("Skill不存在或无权编辑");
        }
        String nextName = StrUtil.trim(request.getName());
        String nextDescription = StrUtil.trim(request.getDescription());
        String nextCapabilityDescription = StrUtil.trim(request.getCapabilityDescription());
        String nextIconUrl = StrUtil.trim(request.getIconUrl());
        String nextVisibility = StrUtil.blankToDefault(request.getVisibility(), skill.getVisibility());
        String nextScope = StrUtil.blankToDefault(request.getInvocationScope(), skill.getInvocationScope());
        String nextStatus = normalizeStatus(request.getStatus());
        validateUserPublicStatusTransition(skill, nextVisibility, nextStatus);
        boolean publicTransition = "PUBLIC".equals(nextVisibility)
                && !"PUBLIC".equals(skill.getVisibility());
        packageAdminService.assertIdentityScopeCompatible(skill.getId(), nextScope,
                DISABLED.equals(nextStatus) && !publicTransition);
        boolean nextDiskCache = request.getCacheToDisk() == null
                ? ("PLATFORM".equals(skill.getOwnerType()) && Boolean.TRUE.equals(skill.getCacheToDisk())) : request.getCacheToDisk();
        boolean coverChanged = !Objects.equals(StrUtil.trim(skill.getIconUrl()), nextIconUrl);
        validateVisibilityChange(skill, nextVisibility, nextDiskCache, nextIconUrl, coverChanged);
        if ("PUBLIC".equals(nextVisibility)) {
            if (skill.getCurrentVersionId() == null) {
                if (NORMAL.equals(nextStatus)) {
                    throw new ServiceException("请先发布并激活公开Skill版本");
                }
            } else if (NORMAL.equals(nextStatus)) {
                packageAdminService.assertCurrentVersionRunnable(skill.getId(), nextVisibility);
            } else if (publicTransition) {
                // A disabled identity may be staged as public from an existing private
                // formal version, then publish one public immutable replacement.
                packageAdminService.assertCurrentVersionPublishable(skill.getId());
            }
        }
        if (NORMAL.equals(skill.getStatus()) && DISABLED.equals(nextStatus)) {
            ensureSkillNotInUse(skill);
        }
        var condition = Wrappers.<AidSkill>lambdaUpdate()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getName, nextName)
                .set(AidSkill::getDescription, nextDescription)
                .set(AidSkill::getCapabilityDescription, nextCapabilityDescription)
                .set(AidSkill::getIconUrl, nextIconUrl)
                .set(AidSkill::getVisibility, nextVisibility)
                .set(AidSkill::getInvocationScope, nextScope)
                .set(AidSkill::getCacheToDisk, nextDiskCache)
                .set(AidSkill::getStatus, nextStatus)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date());
        if (coverChanged) {
            condition.set(AidSkill::getCoverDigest, null)
                    .set(AidSkill::getCoverMimeType, null)
                    .set(AidSkill::getCoverUploadedBy, null)
                    .set(AidSkill::getCoverVerifiedAt, null);
        }
        ensureChanged(skillMapper.update(null, condition));
        packageResourceLoader.refreshAfterCommit(skill.getId(), skill.getCurrentVersionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(SkillAdminRequests.StatusRequest request,
                             Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(request.getId());
        String nextStatus = normalizeStatus(request.getStatus());
        validateUserPublicStatusTransition(skill, skill.getVisibility(), nextStatus);
        if (DISABLED.equals(skill.getStatus()) && NORMAL.equals(nextStatus)
                && "PUBLIC".equals(skill.getVisibility())) {
            if (!skillCoverPolicy.validUploadedCover(skill)) throw new ServiceException("请先上传Skill封面");
            packageAdminService.assertCurrentVersionRunnable(skill.getId(), skill.getVisibility());
        }
        if (DISABLED.equals(skill.getStatus()) && NORMAL.equals(nextStatus)) {
            packageAdminService.assertIdentityScopeCompatible(
                    skill.getId(), skill.getInvocationScope(), false);
        }
        if (NORMAL.equals(skill.getStatus()) && DISABLED.equals(nextStatus)) {
            ensureSkillNotInUse(skill);
        }
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getStatus, nextStatus)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
        packageResourceLoader.refreshAfterCommit(skill.getId(), skill.getCurrentVersionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSkill(Long id, Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(id);
        ensureSkillNotInUse(skill);
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getStatus, DISABLED).set(AidSkill::getDelFlag, DELETED)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
        packageResourceLoader.refreshAfterCommit(skill.getId(), skill.getCurrentVersionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreSkill(Long id, Long operatorId, String operatorName) {
        AidSkill skill = requireSkill(id);
        if (!DELETED.equals(skill.getDelFlag())) {
            throw new ServiceException("Skill is not deleted");
        }
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, DELETED)
                .set(AidSkill::getStatus, DISABLED).set(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
        packageResourceLoader.refreshAfterCommit(skill.getId(), skill.getCurrentVersionId());
    }

    @Override
    public SkillAdminVO.PublicationSettings publicationSettings() {
        SkillAdminVO.PublicationSettings result = new SkillAdminVO.PublicationSettings();
        result.setAllowUserPublicSkills(userPublicSkillsAllowed());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePublicationSettings(SkillAdminRequests.PublicationSettingsRequest request,
                                          Long operatorId, String operatorName) {
        SysConfig query = new SysConfig();
        query.setConfigKey(PUBLICATION_CONFIG_KEY);
        List<SysConfig> rows = sysConfigService.selectConfigList(query);
        String value = Boolean.TRUE.equals(request.getAllowUserPublicSkills()) ? "true" : "false";
        if (rows.isEmpty()) {
            SysConfig created = new SysConfig();
            created.setConfigName("允许用户公开Skill");
            created.setConfigKey(PUBLICATION_CONFIG_KEY);
            created.setConfigValue(value);
            created.setConfigType("Y");
            created.setCreateBy(operator(operatorName, operatorId));
            created.setCreateTime(new Date());
            if (sysConfigService.insertConfig(created) != 1) throw new ServiceException("公开配置保存失败");
        } else {
            SysConfig update = rows.get(0);
            update.setConfigValue(value);
            update.setUpdateBy(operator(operatorName, operatorId));
            update.setUpdateTime(new Date());
            if (sysConfigService.updateConfig(update) != 1) throw new ServiceException("公开配置保存失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moderateUnpublish(SkillAdminRequests.ModerateUnpublishRequest request,
                                  Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(request.getId());
        if (!"USER".equals(skill.getOwnerType()) || !"PUBLIC".equals(skill.getVisibility())) {
            throw new ServiceException("用户公开Skill不存在");
        }
        Date now = new Date();
        ensureChanged(skillMapper.update(null, Wrappers.<AidSkill>lambdaUpdate()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getVisibility, "PUBLIC")
                .eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getVisibility, "PRIVATE")
                .set(AidSkill::getCacheToDisk, false)
                .set(AidSkill::getModerationReason, StrUtil.trim(request.getReason()))
                .set(AidSkill::getModeratedBy, operatorId)
                .set(AidSkill::getModeratedAt, now)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, now)));
        packageResourceLoader.refreshAfterCommit(skill.getId(), skill.getCurrentVersionId());
    }

    @Override
    public OssUploadResponse uploadCover(Long skillId, MultipartFile file,
                                         Long operatorId, String operatorName, boolean platformAdmin) {
        assertCoverUploadOwner(requireSkill(skillId), operatorId, platformAdmin);
        SkillCoverPolicy.UploadEvidence evidence = skillCoverPolicy.inspect(file);
        OssUploadResponse uploaded = adminUploadService.uploadAvatar(file);
        if (uploaded == null || !skillCoverPolicy.validManagedImageUrl(uploaded.getUrl())) {
            throw new ServiceException("Skill封面上传失败");
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AidSkill locked = requireActiveSkillForUpdate(skillId);
                assertCoverUploadOwner(locked, operatorId, platformAdmin);
                Date now = new Date();
                ensureChanged(skillMapper.update(null, Wrappers.<AidSkill>lambdaUpdate()
                        .eq(AidSkill::getId, locked.getId()).eq(AidSkill::getDelFlag, NORMAL)
                        .set(AidSkill::getIconUrl, uploaded.getUrl())
                        .set(AidSkill::getCoverDigest, evidence.digest())
                        .set(AidSkill::getCoverMimeType, evidence.mimeType())
                        .set(AidSkill::getCoverUploadedBy, operatorId)
                        .set(AidSkill::getCoverVerifiedAt, now)
                        .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                        .set(AidSkill::getUpdateTime, now)));
            });
            return uploaded;
        } catch (RuntimeException error) {
            skillCoverPolicy.deleteUploaded(uploaded.getUrl());
            throw error;
        }
    }

    @Override
    public List<SkillAdminVO.TextModelOption> listTextModelOptions() {
        return skillModelService.adminOptions();
    }

    @Override
    public SkillAdminVO.PageResult<SkillAdminVO.RunSummary> pageRuns(
            SkillAdminRequests.RunPageRequest request) {
        SkillAdminRequests.RunPageRequest source = Objects.requireNonNullElseGet(
                request, SkillAdminRequests.RunPageRequest::new);
        Page<AidSkillRun> page = runMapper.selectPage(
                new Page<>(positive(source.getPageNum(), 1), bounded(source.getPageSize(), 20, 100)),
                Wrappers.<AidSkillRun>lambdaQuery()
                        .select(AidSkillRun::getId, AidSkillRun::getUserId, AidSkillRun::getSkillId,
                                AidSkillRun::getSkillVersionId, AidSkillRun::getProjectId,
                                AidSkillRun::getEpisodeId, AidSkillRun::getSkillConfigHash,
                                AidSkillRun::getModelCode, AidSkillRun::getInvokeSource,
                                AidSkillRun::getClientRequestId, AidSkillRun::getGeneration,
                                AidSkillRun::getStatus, AidSkillRun::getStage, AidSkillRun::getActionMode,
                                AidSkillRun::getQualityMode, AidSkillRun::getStartedAt,
                                AidSkillRun::getFinishedAt)
                        .eq(source.getSkillId() != null, AidSkillRun::getSkillId, source.getSkillId())
                        .eq(source.getUserId() != null, AidSkillRun::getUserId, source.getUserId())
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidSkillRun::getStatus, source.getStatus())
                        .isNotNull(AidSkillRun::getSkillVersionId)
                        .eq(AidSkillRun::getDelFlag, NORMAL)
                        .orderByDesc(AidSkillRun::getId));
        return new SkillAdminVO.PageResult<>(page.getTotal(), page.getRecords().stream()
                .map(this::toRunSummary).toList());
    }

    @Override
    public SkillAdminVO.RunItem getRun(Long runId) {
        AidSkillRun run = runMapper.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).isNotNull(AidSkillRun::getSkillVersionId)
                .eq(AidSkillRun::getDelFlag, NORMAL).last("limit 1"));
        if (run == null) {
            throw new ServiceException("Runtime run not found");
        }
        SkillAdminVO.RunItem item = toRunItem(run);
        item.setTasks(loadRunTasks(run.getId()));
        return item;
    }

    private List<SkillAdminVO.RunTaskItem> loadRunTasks(Long runId) {
        List<AidSkillRunStep> steps = stepMapper.selectList(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getRunId, runId).eq(AidSkillRunStep::getDelFlag, NORMAL)
                .orderByAsc(AidSkillRunStep::getStepSeq).orderByAsc(AidSkillRunStep::getId));
        if (steps.isEmpty()) {
            return List.of();
        }
        List<AidSkillRunTaskLink> links = taskLinkMapper.selectList(
                Wrappers.<AidSkillRunTaskLink>lambdaQuery().eq(AidSkillRunTaskLink::getRunId, runId)
                        .eq(AidSkillRunTaskLink::getDelFlag, NORMAL));
        Map<Long, AidSkillRunTaskLink> linkByStep = links.stream().collect(Collectors.toMap(
                AidSkillRunTaskLink::getStepId, Function.identity(), (left, right) -> left));
        List<Long> taskIds = links.stream().map(AidSkillRunTaskLink::getMediaTaskId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AidMediaTask> tasks = taskIds.isEmpty() ? Collections.emptyMap()
                : mediaTaskMapper.selectList(Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getStatus,
                                AidMediaTask::getBillingStatus, AidMediaTask::getActualCost)
                        .in(AidMediaTask::getId, taskIds)).stream()
                        .collect(Collectors.toMap(AidMediaTask::getId, Function.identity()));
        return steps.stream().map(step -> toRunTask(step, linkByStep.get(step.getId()), tasks)).toList();
    }

    private SkillAdminVO.RunTaskItem toRunTask(AidSkillRunStep step, AidSkillRunTaskLink link,
                                                Map<Long, AidMediaTask> tasks) {
        SkillAdminVO.RunTaskItem item = new SkillAdminVO.RunTaskItem();
        item.setStepId(step.getId());
        item.setStepSeq(step.getStepSeq());
        item.setStepKey(step.getStepKey());
        item.setStepExecutionId(step.getStepExecutionId());
        item.setSkillId(step.getSkillId());
        item.setSkillVersionId(step.getSkillVersionId());
        item.setActionMode(step.getActionMode());
        item.setWorkflowAttempt(step.getWorkflowAttempt());
        item.setOrchestrationStatus(step.getOrchestrationStatus());
        if (link != null) {
            item.setMediaTaskId(link.getMediaTaskId());
            AidMediaTask task = tasks.get(link.getMediaTaskId());
            if (task != null) {
                item.setMediaStatus(task.getStatus());
                item.setBillingStatus(task.getBillingStatus());
                item.setActualCost(task.getActualCost() == null ? null : task.getActualCost().toPlainString());
            }
        }
        return item;
    }

    private AidSkill requireSkill(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid Skill id");
        }
        AidSkill skill = skillMapper.selectById(id);
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            throw new ServiceException("Skill not found");
        }
        return skill;
    }

    private AidSkill requireActiveSkillForUpdate(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid Skill id");
        }
        AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getId, id).last("FOR UPDATE"));
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            throw new ServiceException("Skill not found");
        }
        if (!NORMAL.equals(skill.getDelFlag())) {
            throw new ServiceException("Skill is deleted");
        }
        return skill;
    }

    private SkillAdminVO.SkillSummary toSkillSummary(AidSkill skill) {
        SkillAdminVO.SkillSummary item = new SkillAdminVO.SkillSummary();
        item.setId(skill.getId());
        item.setSkillCode(skill.getSkillCode());
        item.setName(SkillRuntimeCapabilities.displayName(skill.getSkillCode(), skill.getName()));
        item.setDescription(SkillRuntimeCapabilities.displayDescription(
                skill.getSkillCode(), skill.getDescription()));
        item.setCapabilityDescription(skill.getCapabilityDescription());
        item.setIconUrl(skill.getIconUrl());
        item.setOwnerType(skill.getOwnerType());
        item.setOwnerUserId(skill.getOwnerUserId());
        item.setVisibility(skill.getVisibility());
        item.setCacheToDisk(Boolean.TRUE.equals(skill.getCacheToDisk()));
        item.setModerationReason(skill.getModerationReason());
        item.setModeratedBy(skill.getModeratedBy());
        item.setModeratedAt(skill.getModeratedAt());
        item.setInvocationScope(skill.getInvocationScope());
        item.setCurrentVersionId(skill.getCurrentVersionId());
        item.setStatus(skill.getStatus());
        item.setDelFlag(skill.getDelFlag());
        item.setModelCode(skill.getModelCode());
        item.setReasoningPolicy(skill.getReasoningPolicy());
        item.setUpdateTime(skill.getUpdateTime());
        return item;
    }

    private SkillAdminVO.RunSummary toRunSummary(AidSkillRun run) {
        SkillAdminVO.RunSummary item = new SkillAdminVO.RunSummary();
        copyRunSummary(run, item);
        return item;
    }

    private SkillAdminVO.RunItem toRunItem(AidSkillRun run) {
        SkillAdminVO.RunItem item = new SkillAdminVO.RunItem();
        copyRunSummary(run, item);
        item.setClientRequestDigest(run.getClientRequestDigest());
        item.setExecutionSnapshotDigest(run.getExecutionSnapshotDigest());
        item.setResolvedConfigDigest(run.getResolvedConfigDigest());
        item.setRootRunId(run.getRootRunId());
        item.setParentRunId(run.getParentRunId());
        item.setInputJson(run.getInputJson());
        item.setOutputJson(run.getOutputJson());
        item.setErrorMessage(run.getErrorMessage());
        return item;
    }

    private void copyRunSummary(AidSkillRun run, SkillAdminVO.RunSummary item) {
        item.setId(run.getId());
        item.setUserId(run.getUserId());
        item.setSkillId(run.getSkillId());
        item.setSkillVersionId(run.getSkillVersionId());
        item.setProjectId(run.getProjectId());
        item.setEpisodeId(run.getEpisodeId());
        item.setSkillConfigHash(run.getSkillConfigHash());
        item.setModelCode(run.getModelCode());
        item.setInvokeSource(run.getInvokeSource());
        item.setClientRequestId(run.getClientRequestId());
        item.setGeneration(run.getGeneration());
        item.setStatus(run.getStatus());
        item.setStage(run.getStage());
        item.setActionMode(run.getActionMode());
        item.setQualityMode(run.getQualityMode());
        item.setStartedAt(run.getStartedAt());
        item.setFinishedAt(run.getFinishedAt());
        if (run.getStartedAt() != null && run.getFinishedAt() != null) {
            item.setDurationMillis(Math.max(0L,
                    run.getFinishedAt().getTime() - run.getStartedAt().getTime()));
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private void ensureSkillNotInUse(AidSkill skill) {
        List<DependencyReference> refs = new ArrayList<>();
        List<AidBusinessGroupMember> memberships = groupMemberMapper.selectList(
                Wrappers.<AidBusinessGroupMember>lambdaQuery()
                        .eq(AidBusinessGroupMember::getMemberType, "SKILL")
                        .eq(AidBusinessGroupMember::getMemberId, skill.getId())
                        .eq(AidBusinessGroupMember::getDelFlag, NORMAL));
        for (AidBusinessGroupMember member : memberships) {
            AidBusinessGroup group = groupMapper.selectById(member.getGroupId());
            refs.add(RuntimeReferenceErrors.reference("BUSINESS_GROUP", member.getGroupId(),
                    group == null ? null : group.getGroupCode(),
                    group == null ? null : group.getName(), null,
                    "GROUP_MEMBER", "aid/businessGroup/index"));
        }
        List<AidRuntimeRun> active = runtimeRuns.selectList(Wrappers.<AidRuntimeRun>lambdaQuery()
                .select(AidRuntimeRun::getId, AidRuntimeRun::getVersionId)
                .eq(AidRuntimeRun::getMemberType, "SKILL")
                .eq(AidRuntimeRun::getMemberId, skill.getId())
                .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                .eq(AidRuntimeRun::getDelFlag, NORMAL));
        for (AidRuntimeRun run : active)
            refs.add(RuntimeReferenceErrors.reference("ACTIVE_RUN", run.getId(), skill.getSkillCode(),
                    skill.getName(), String.valueOf(run.getVersionId()), "EXECUTION", "aid/skill/index"));
        List<AidRuntimeAgentSkillRef> agentLinks = agentSkillRefs.selectList(
                Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                        .eq(AidRuntimeAgentSkillRef::getSkillId, skill.getId()));
        for (AidRuntimeAgentSkillRef link : agentLinks) {
            List<AidRuntimeAgent> parents = agentMapper.selectList(Wrappers.<AidRuntimeAgent>lambdaQuery()
                    .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentCode,
                            AidRuntimeAgent::getName, AidRuntimeAgent::getCurrentVersionId)
                    .eq(AidRuntimeAgent::getCurrentVersionId, link.getAgentVersionId())
                    .eq(AidRuntimeAgent::getStatus, NORMAL).eq(AidRuntimeAgent::getDelFlag, NORMAL));
            for (AidRuntimeAgent parent : parents)
                refs.add(RuntimeReferenceErrors.reference("AGENT_VERSION", link.getAgentVersionId(),
                        parent.getAgentCode(), parent.getName(),
                        String.valueOf(link.getAgentVersionId()), "CHILD_SKILL", "aid/runtimeAgent/index"));
            long executingParents = runtimeRuns.selectCount(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getMemberType, "AGENT")
                    .eq(AidRuntimeRun::getVersionId, link.getAgentVersionId())
                    .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                    .eq(AidRuntimeRun::getDelFlag, NORMAL));
            if (executingParents > 0)
                refs.add(RuntimeReferenceErrors.reference("ACTIVE_RUN", link.getAgentVersionId(),
                        skill.getSkillCode(), skill.getName(), String.valueOf(link.getAgentVersionId()),
                        "CHILD_SKILL", "aid/runtimeAgent/index"));
        }
        List<AidSkillRelation> oldLinks = relationMapper.selectList(
                Wrappers.<AidSkillRelation>lambdaQuery()
                        .select(AidSkillRelation::getParentVersionId)
                        .eq(AidSkillRelation::getChildSkillId, skill.getId())
                        .eq(AidSkillRelation::getDelFlag, NORMAL));
        for (Long parentVersionId : oldLinks.stream().map(AidSkillRelation::getParentVersionId)
                .filter(Objects::nonNull).distinct().toList()) {
            long entrypoint = skillMapper.selectCount(Wrappers.<AidSkill>lambdaQuery()
                    .eq(AidSkill::getCurrentVersionId, parentVersionId)
                    .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                    .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL));
            long oldRuns = runMapper.selectCount(Wrappers.<AidSkillRun>lambdaQuery()
                    .eq(AidSkillRun::getSkillVersionId, parentVersionId)
                    .notIn(AidSkillRun::getStatus, "SUCCEEDED", "FAILED", "CANCELED")
                    .eq(AidSkillRun::getDelFlag, NORMAL));
            if (entrypoint > 0 || oldRuns > 0)
                refs.add(RuntimeReferenceErrors.reference("SKILL_VERSION", parentVersionId,
                        skill.getSkillCode(), skill.getName(), String.valueOf(parentVersionId),
                        "CHILD_SKILL", "aid/skill/index"));
        }
        RuntimeReferenceErrors.reject(refs, refs.size());
    }

    private static int bounded(Integer value, int fallback, int max) {
        return Math.min(positive(value, fallback), max);
    }

    private static String normalizeStatus(String status) {
        if (!NORMAL.equals(status) && !DISABLED.equals(status)) {
            throw new ServiceException("Invalid Skill status");
        }
        return status;
    }

    private void validateUserPublicStatusTransition(AidSkill skill, String nextVisibility,
                                                    String nextStatus) {
        if (DISABLED.equals(skill.getStatus()) && NORMAL.equals(nextStatus)
                && "USER".equals(skill.getOwnerType()) && "PUBLIC".equals(nextVisibility)
                && !userPublicSkillsAllowed()) {
            throw new ServiceException("用户公开Skill已关闭");
        }
    }

    private void validateVisibilityChange(AidSkill skill, String visibility,
                                          boolean cacheToDisk, String iconUrl, boolean coverChanged) {
        if (!Set.of("PUBLIC", "PRIVATE").contains(visibility)) {
            throw new ServiceException("Skill可见性错误");
        }
        if ("USER".equals(skill.getOwnerType()) && "PUBLIC".equals(visibility)
                && !"PUBLIC".equals(skill.getVisibility()) && !userPublicSkillsAllowed()) {
            throw new ServiceException("用户公开Skill已关闭");
        }
        if ("PUBLIC".equals(visibility) && (coverChanged
                || !Objects.equals(iconUrl, skill.getIconUrl())
                || !skillCoverPolicy.validUploadedCover(skill))) {
            throw new ServiceException("请先上传Skill封面");
        }
        if (cacheToDisk && !"PLATFORM".equals(skill.getOwnerType())) {
            throw new ServiceException("仅平台 Skill 可缓存到本地磁盘");
        }
    }

    private boolean userPublicSkillsAllowed() {
        return Boolean.parseBoolean(StrUtil.blankToDefault(
                sysConfigService.selectConfigByKey(PUBLICATION_CONFIG_KEY), "false"));
    }

    private static void assertCoverUploadOwner(AidSkill skill, Long operatorId, boolean platformAdmin) {
        if (!platformAdmin && (!"USER".equals(skill.getOwnerType())
                || !Objects.equals(skill.getOwnerUserId(), operatorId))) {
            throw new ServiceException("Skill不存在或无权编辑");
        }
    }


    private static String operator(String name, Long id) {
        return StrUtil.blankToDefault(StrUtil.trim(name), id == null ? "system" : String.valueOf(id));
    }

    private static void ensureChanged(int changed) {
        if (changed != 1) {
            throw new ServiceException("Skill changed concurrently");
        }
    }
}
