package com.aid.skill.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.service.support.ModelReferenceGuard;
import com.aid.businessgroup.service.BusinessGroupService;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillResource;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillResourceMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.ISkillPackageAdminService;
import com.aid.skill.service.SkillPackageDigestCalculator;
import com.aid.skill.service.SkillCoverPolicy;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.aid.skill.service.PackageDocumentReferences;
import com.aid.skill.service.SkillRuntimeCapabilities;
import com.aid.skill.service.SkillModelConfiguration;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.vo.SkillAdminVO;
import com.aid.skill.vo.SkillPackageAdminVO;
import com.aid.core.service.ISysConfigService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Skill 管理端版本包服务；发布版本只增不改。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillPackageAdminServiceImpl implements ISkillPackageAdminService {
    private static final String NORMAL = "0";
    private static final String DELETED = "1";
    private static final String ENABLED = "0";
    private static final String DISABLED = "1";
    private static final String EDITING = "EDITING";
    private static final String DISCARDED = "DISCARDED";
    private static final String PUBLISHED = "PUBLISHED";
    private static final long MAX_SINGLE_RESOURCE_BYTES = 100L * 1024L;
    private static final long MAX_RESOURCE_BYTES = 512L * 1024L;
    private static final Set<String> RESOURCE_TYPES = Set.of("REFERENCE", "INSTRUCTION");
    private static final Set<String> MIME_TYPES = Set.of("text/markdown", "text/plain");
    private static final String SKILL_DOCUMENT_KEY = "__skill_md";
    private static final String PUBLICATION_CONFIG_KEY = "aid.skill.allowUserPublicSkills";

    private final AidSkillMapper skillMapper;
    private final AidSkillDraftMapper draftMapper;
    private final AidSkillVersionMapper versionMapper;
    private final AidSkillResourceMapper resourceMapper;
    private final AidSkillRelationMapper relationMapper;
    private final SkillModelService skillModelService;
    private final ModelReferenceGuard modelReferenceGuard;
    private final SkillPackageResourceLoader packageResourceLoader;
    private final BusinessGroupService businessGroupService;
    private final TransactionTemplate transactionTemplate;
    private final ISysConfigService sysConfigService;
    private final SkillCoverPolicy skillCoverPolicy;

    private final Validator validator;

    @Override
    public SkillPackageAdminVO.DraftDetail copyVersionToNewSkill(Long skillId, Long templateVersionId,
                                                                 Long operatorId, String operatorName) {
        AidSkill target = requireSkill(skillId);
        if (!"PLATFORM".equals(target.getOwnerType()) || target.getOwnerUserId() != null
                || !"PRIVATE".equals(target.getVisibility()) || !DISABLED.equals(target.getStatus())
                || target.getCurrentVersionId() != null
                || versionMapper.selectCount(Wrappers.<AidSkillVersion>lambdaQuery()
                    .eq(AidSkillVersion::getSkillId, target.getId())) != 0L) {
            throw new ServiceException("模板只能复制到未发布的平台Skill");
        }
        AidSkillVersion source = requireVersion(templateVersionId);
        AidSkill sourceIdentity = requireSkill(source.getSkillId());
        boolean platformSource = "PLATFORM".equals(sourceIdentity.getOwnerType());
        boolean publicUserSource = "USER".equals(sourceIdentity.getOwnerType())
                && "PUBLIC".equals(sourceIdentity.getVisibility());
        if ((!platformSource && !publicUserSource)
                || !("PUBLIC".equals(sourceIdentity.getVisibility())
                    || "PRIVATE".equals(sourceIdentity.getVisibility()))) {
            throw new ServiceException("模板不可访问");
        }
        boolean publicFormalVersion = "PUBLIC".equals(source.getVisibility())
                && PUBLISHED.equals(source.getPublishStatus());
        boolean privateFormalVersion = "PRIVATE".equals(source.getVisibility())
                && "PRIVATE".equals(source.getPublishStatus());
        boolean validPublicationPair = publicUserSource
                ? publicFormalVersion || privateFormalVersion
                : Objects.equals(source.getVisibility(), sourceIdentity.getVisibility())
                    && (publicFormalVersion || privateFormalVersion);
        if (!Objects.equals(sourceIdentity.getCurrentVersionId(), source.getId())
                || !Objects.equals(source.getSkillId(), sourceIdentity.getId())
                || !Objects.equals(source.getInvocationScope(), sourceIdentity.getInvocationScope())
                || !NORMAL.equals(sourceIdentity.getStatus())
                || !validPublicationPair
                || !NORMAL.equals(source.getStatus()) || !NORMAL.equals(source.getDelFlag())
                || source.getPackageDigest() == null
                || !source.getPackageDigest().matches("[0-9a-f]{64}")) {
            throw new ServiceException("模板正式版本不可用");
        }
        packageResourceLoader.verifyVersion(sourceIdentity.getSkillCode(), source);
        if (StrUtil.isBlank(packageResourceLoader.readPublishedSkillMarkdown(
                sourceIdentity.getSkillCode(), source))) {
            throw new ServiceException("模板缺少正式SKILL.md");
        }
        DraftDocument seed = seedDocument(sourceIdentity, source);
        SkillPackageAdminRequests.DraftSaveRequest copy = new SkillPackageAdminRequests.DraftSaveRequest();
        copy.setSkillId(target.getId());
        copy.setBaseVersionId(null);
        copyPayload(seed, copy);
        return saveDraft(copy, operatorId, operatorName);
    }

    @Override
    public SkillPackageAdminVO.VersionPageResult listVersions(
            SkillPackageAdminRequests.VersionPageRequest request) {
        AidSkill skill = requireSkillLight(request.getSkillId());
        Page<AidSkillVersion> page = versionMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                                AidSkillVersion::getVersionCode, AidSkillVersion::getPublishStatus,
                                AidSkillVersion::getPackageDigest, AidSkillVersion::getStatus,
                                AidSkillVersion::getCreateBy, AidSkillVersion::getCreateTime)
                        .eq(AidSkillVersion::getSkillId, request.getSkillId())
                        .eq(AidSkillVersion::getDelFlag, NORMAL)
                        .orderByDesc(AidSkillVersion::getId));
        List<SkillPackageAdminVO.VersionSummary> data = page.getRecords().stream()
                .map(version -> toSummary(version, skill.getCurrentVersionId())).toList();
        return new SkillPackageAdminVO.VersionPageResult(
                page.getTotal(), data, skill.getCurrentVersionId());
    }

    @Override
    public SkillPackageAdminVO.VersionDetail getVersion(Long versionId) {
        AidSkillVersion version = requireVersion(versionId);
        AidSkill skill = requireSkillLight(version.getSkillId());
        String skillMarkdown = packageResourceLoader.readVerifiedDraftUpgradeSkillMarkdown(
                skill.getSkillCode(), version);
        return toVersionDetail(skill, version, skillMarkdown);
    }

    @Override
    public void assertIdentityScopeCompatible(Long skillId, String invocationScope,
                                              boolean allowDisabledMismatch) {
        AidSkill skill = requireSkill(skillId);
        if (skill.getCurrentVersionId() == null) return;
        AidSkillVersion version = requireCurrentVersion(skill);
        if (!allowDisabledMismatch && !Objects.equals(invocationScope, version.getInvocationScope())) {
            throw new ServiceException("调用范围须通过新版本发布后变更");
        }
    }

    @Override
    public void assertCurrentVersionPublishable(Long skillId) {
        AidSkill skill = requireSkill(skillId);
        requireCurrentVersionPublishable(skill);
    }

    @Override
    public void assertCurrentVersionRunnable(Long skillId, String targetVisibility) {
        AidSkill skill = requireSkill(skillId);
        AidSkillVersion version = requireCurrentVersionPublishable(skill);
        if ("PLATFORM".equals(skill.getOwnerType()) && "PUBLIC".equals(targetVisibility)
                && (!"PUBLIC".equals(version.getVisibility())
                || !PUBLISHED.equals(version.getPublishStatus()))) {
            throw new ServiceException("平台公开Skill须先发布并激活公开版本");
        }
    }

    private AidSkillVersion requireCurrentVersionPublishable(AidSkill skill) {
        AidSkillVersion version = requireCurrentVersion(skill);
        if (!ENABLED.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                || !Set.of("PRIVATE", PUBLISHED).contains(version.getPublishStatus())) {
            throw new ServiceException("当前Skill版本不可公开");
        }
        if (!Objects.equals(skill.getInvocationScope(), version.getInvocationScope())) {
            throw new ServiceException("当前Skill版本调用范围不一致");
        }
        SkillModelConfiguration configuredModels = SkillModelConfiguration.from(version);
        List<Long> modelIds = skillModelService.activeModels(configuredModels.selectableModelCodes(),
                SkillModelService.outputKind(version)).values().stream().map(AidAiModel::getId).toList();
        List<Long> sortedModelIds = modelIds.stream().sorted().toList();
        for (int offset = 0; offset < sortedModelIds.size(); offset += 100) {
            modelReferenceGuard.lockModels(sortedModelIds.subList(
                    offset, Math.min(offset + 100, sortedModelIds.size())));
        }
        if (!configuredModels.isValid() || skillModelService.activeModels(
                configuredModels.selectableModelCodes(), SkillModelService.outputKind(version)).size()
                != configuredModels.selectableModelCodes().size()) {
            throw new ServiceException("当前Skill版本模型不可用");
        }
        requireRelationsAvailable(skill, version);
        requirePublicRelationsAvailable(version);
        businessGroupService.assertSkillSchemaCompatible(skill.getId(),
                version.getInputSchemaJson(), version.getOutputSchemaJson());
        packageResourceLoader.verifyVersion(skill.getSkillCode(), version);
        if (StrUtil.isBlank(packageResourceLoader.readPublishedSkillMarkdown(
                skill.getSkillCode(), version))) {
            throw new ServiceException("当前Skill版本缺少SKILL.md，请先发布正式包版本");
        }
        return version;
    }

    private void requirePublicRelationsAvailable(AidSkillVersion parentVersion) {
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .select(AidSkillRelation::getChildSkillId, AidSkillRelation::getChildVersionId,
                        AidSkillRelation::getRelationKey)
                .eq(AidSkillRelation::getParentVersionId, parentVersion.getId())
                .eq(AidSkillRelation::getDelFlag, NORMAL));
        Set<Long> childIds = relations.stream().map(AidSkillRelation::getChildSkillId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> childVersionIds = relations.stream().map(AidSkillRelation::getChildVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AidSkill> children = childIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getOwnerType, AidSkill::getVisibility,
                                AidSkill::getStatus, AidSkill::getDelFlag)
                        .in(AidSkill::getId, childIds)).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> childVersions = childVersionIds.isEmpty() ? Map.of()
                : versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                                AidSkillVersion::getVisibility, AidSkillVersion::getPublishStatus,
                                AidSkillVersion::getStatus, AidSkillVersion::getDelFlag)
                        .in(AidSkillVersion::getId, childVersionIds)).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        for (AidSkillRelation relation : relations) {
            AidSkill child = children.get(relation.getChildSkillId());
            AidSkillVersion childVersion = childVersions.get(relation.getChildVersionId());
            if (child == null || !"PUBLIC".equals(child.getVisibility())
                    || !Set.of("PLATFORM", "USER").contains(child.getOwnerType())
                    || !ENABLED.equals(child.getStatus()) || !NORMAL.equals(child.getDelFlag())
                    || childVersion == null || !Objects.equals(childVersion.getSkillId(), child.getId())
                    || !ENABLED.equals(childVersion.getStatus()) || !NORMAL.equals(childVersion.getDelFlag())
                    || !Set.of("PRIVATE", "PUBLIC").contains(childVersion.getVisibility())
                    || !Set.of("PRIVATE", PUBLISHED).contains(childVersion.getPublishStatus())
                    || ("PLATFORM".equals(child.getOwnerType())
                    && (!"PUBLIC".equals(childVersion.getVisibility())
                    || !PUBLISHED.equals(childVersion.getPublishStatus())))) {
                throw new ServiceException("公开Skill依赖包含不可公开子Skill");
            }
        }
    }

    @Override
    public SkillAdminVO.PageResult<SkillPackageAdminVO.DependencySkillOption> listDependencyOptions(
            SkillPackageAdminRequests.DependencySkillPageRequest request) {
        requireSkillLight(request.getSkillId());
        Page<AidSkill> page = skillMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getCurrentVersionId)
                        .ne(AidSkill::getId, request.getSkillId())
                        .ne(AidSkill::getSkillCode, SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT)
                        .eq(AidSkill::getInvocationScope, "INTERNAL")
                        .eq(AidSkill::getStatus, ENABLED).eq(AidSkill::getDelFlag, NORMAL)
                        .and(StrUtil.isNotBlank(request.getKeyword()), query -> query
                                .like(AidSkill::getSkillCode, StrUtil.trim(request.getKeyword()))
                                .or().like(AidSkill::getName, StrUtil.trim(request.getKeyword())))
                        .orderByAsc(AidSkill::getSkillCode));
        List<SkillPackageAdminVO.DependencySkillOption> data = page.getRecords().stream().map(child -> {
            SkillPackageAdminVO.DependencySkillOption option = new SkillPackageAdminVO.DependencySkillOption();
            option.setSkillId(child.getId());
            option.setSkillCode(child.getSkillCode());
            option.setName(SkillRuntimeCapabilities.displayName(child.getSkillCode(), child.getName()));
            option.setCurrentVersionId(child.getCurrentVersionId());
            return option;
        }).toList();
        return new SkillAdminVO.PageResult<>(page.getTotal(), data);
    }

    @Override
    public SkillAdminVO.PageResult<SkillPackageAdminVO.DependencyVersionOption> listDependencyVersionOptions(
            SkillPackageAdminRequests.DependencyVersionPageRequest request) {
        requireSkillLight(request.getParentSkillId());
        AidSkill child = requireSkillLight(request.getChildSkillId());
        if (Objects.equals(request.getParentSkillId(), child.getId())
                || !"INTERNAL".equals(child.getInvocationScope())
                || !ENABLED.equals(child.getStatus())) {
            log.warn("Skill子依赖目标错误, parentSkillId={}, childSkillId={}",
                    request.getParentSkillId(), request.getChildSkillId());
            throw new ServiceException("子Skill不可用");
        }
        Page<AidSkillVersion> page = versionMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getVersionCode)
                        .eq(AidSkillVersion::getSkillId, child.getId())
                        .eq(AidSkillVersion::getInvocationScope, "INTERNAL")
                        .eq(AidSkillVersion::getStatus, ENABLED)
                        .eq(AidSkillVersion::getDelFlag, NORMAL)
                        .like(StrUtil.isNotBlank(request.getKeyword()), AidSkillVersion::getVersionCode,
                                StrUtil.trim(request.getKeyword()))
                        .orderByDesc(AidSkillVersion::getId));
        List<SkillPackageAdminVO.DependencyVersionOption> data = page.getRecords().stream().map(version -> {
            SkillPackageAdminVO.DependencyVersionOption option =
                    new SkillPackageAdminVO.DependencyVersionOption();
            option.setId(version.getId());
            option.setVersionCode(version.getVersionCode());
            option.setCurrent(Objects.equals(child.getCurrentVersionId(), version.getId()));
            return option;
        }).toList();
        return new SkillAdminVO.PageResult<>(page.getTotal(), data);
    }

    @Override
    public List<SkillPackageAdminVO.DependencyLabel> listDependencyLabels(
            SkillPackageAdminRequests.DependencyLabelRequest request) {
        requireSkillLight(request.getParentSkillId());
        Set<Long> requestedVersionIds = new LinkedHashSet<>(request.getVersionIds());
        if (requestedVersionIds.size() > 16) {
            log.warn("Skill已选子版本标签过多, parentSkillId={}, count={}",
                    request.getParentSkillId(), requestedVersionIds.size());
            throw new ServiceException("已选子版本不能超过16个");
        }
        Map<Long, AidSkillVersion> versions = versionMapper.selectList(
                        Wrappers.<AidSkillVersion>lambdaQuery()
                                .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                                        AidSkillVersion::getVersionCode)
                                .in(AidSkillVersion::getId, requestedVersionIds)).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        Set<Long> childSkillIds = versions.values().stream().map(AidSkillVersion::getSkillId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AidSkill> children = childSkillIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getCurrentVersionId)
                        .in(AidSkill::getId, childSkillIds)
                        .ne(AidSkill::getSkillCode, SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT)
                        .eq(AidSkill::getDelFlag, NORMAL)).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        return requestedVersionIds.stream().map(versionId -> {
            AidSkillVersion version = versions.get(versionId);
            AidSkill child = version == null ? null : children.get(version.getSkillId());
            if (version == null || child == null) {
                return null;
            }
            SkillPackageAdminVO.DependencyLabel label = new SkillPackageAdminVO.DependencyLabel();
            label.setChildSkillId(child.getId());
            label.setChildSkillCode(child.getSkillCode());
            label.setChildSkillName(SkillRuntimeCapabilities.displayName(
                    child.getSkillCode(), child.getName()));
            label.setChildVersionId(version.getId());
            label.setChildVersionCode(version.getVersionCode());
            label.setCurrent(Objects.equals(child.getCurrentVersionId(), version.getId()));
            return label;
        }).filter(Objects::nonNull).toList();
    }

    @Override
    public SkillPackageAdminVO.DraftDetail getDraft(Long skillId, Long baseVersionId, Long operatorId) {
        try {
            AidSkill skill = requireSkill(skillId);
            AidSkillDraft draft = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                    .eq(AidSkillDraft::getSkillId, skillId).eq(AidSkillDraft::getOwnerUserId, operatorId)
                    .eq(AidSkillDraft::getStatus, EDITING).orderByDesc(AidSkillDraft::getId).last("limit 1"));
            if (draft != null) {
                return toDraftDetail(skill, draft, parseDraft(draft));
            }
            if (baseVersionId == null && skill.getCurrentVersionId() == null) {
                return toDraftDetail(skill, null, seedIdentityDocument(skill));
            }
            AidSkillVersion base = baseVersionId == null
                    ? requireCurrentVersion(skill) : requireVersion(baseVersionId);
            if (!Objects.equals(base.getSkillId(), skill.getId())) {
                log.warn("Skill草稿基础版本错误, skillId={}, versionId={}", skillId, baseVersionId);
                throw new ServiceException("基础版本错误");
            }
            DraftDocument document = seedDocument(skill, base);
            return toDraftDetail(skill, null, document);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            log.error("Skill草稿读取失败, skillId={}, baseVersionId={}, operatorId={}",
                    skillId, baseVersionId, operatorId, error);
            throw new ServiceException("Skill草稿读取失败");
        }
    }

    @Override
    public SkillPackageAdminVO.DraftDetail saveDraft(SkillPackageAdminRequests.DraftSaveRequest request,
                                                      Long operatorId, String operatorName) {
        requireDraftShape(request);
        AidSkill skill = requireSkill(request.getSkillId());
        if (!baseVersionValid(skill, request.getBaseVersionId())) {
            log.warn("Skill草稿基础版本错误, skillId={}, versionId={}",
                    skill.getId(), request.getBaseVersionId());
            throw new ServiceException("基础版本错误");
        }
        DraftDocument incoming = document(request, request.getBaseVersionId());
        if (com.aid.skill.service.StyleAnalysisService.SKILL_CODE.equals(skill.getSkillCode())) {
            incoming.setInputSchemaJson(skill.getInputSchemaJson());
            incoming.setOutputSchemaJson(skill.getOutputSchemaJson());
            JSONObject definition = JSON.parseObject(incoming.getDefinitionJson());
            if (definition == null) definition = new JSONObject();
            definition.put("stream", false);
            definition.put("structuredOutputEnabled", true);
            incoming.setDefinitionJson(definition.toJSONString());
        }
        normalizeModelConfiguration(incoming);
        String incomingJson = canonicalDraftJson(incoming);
        String incomingDigest = SecureUtil.sha256(incomingJson);
        String operator = operator(operatorId, operatorName);
        AidSkillDraft saved = transactionTemplate.execute(status -> {
            Date now = new Date();
            if (request.getDraftId() == null) {
                AidSkillDraft existing = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                        .eq(AidSkillDraft::getSkillId, skill.getId())
                        .eq(AidSkillDraft::getOwnerUserId, operatorId)
                        .eq(AidSkillDraft::getStatus, EDITING).last("limit 1 for update"));
                if (existing != null) {
                    if (Objects.equals(existing.getDraftDigest(), incomingDigest)) {
                        return existing;
                    }
                    log.warn("Skill草稿重复创建, skillId={}, operatorId={}", skill.getId(), operatorId);
                    throw new ServiceException("草稿已存在");
                }
                AidSkillDraft created = new AidSkillDraft();
                created.setSkillId(skill.getId());
                created.setOwnerUserId(operatorId);
                created.setActiveKey(activeDraftKey(operatorId, skill.getId()));
                created.setDraftJson(incomingJson);
                created.setDraftDigest(incomingDigest);
                created.setStatus(EDITING);
                created.setCreateBy(operator);
                created.setCreateTime(now);
                created.setUpdateBy(operator);
                created.setUpdateTime(now);
                try {
                    if (draftMapper.insert(created) != 1) {
                        log.error("Skill草稿创建失败, skillId={}, operatorId={}", skill.getId(), operatorId);
                        throw new ServiceException("草稿保存失败");
                    }
                    packageResourceLoader.refreshAfterCommit(skill.getId(), null);
                } catch (DuplicateKeyException error) {
                    AidSkillDraft winner = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                            .eq(AidSkillDraft::getSkillId, skill.getId())
                            .eq(AidSkillDraft::getOwnerUserId, operatorId)
                            .eq(AidSkillDraft::getStatus, EDITING).last("limit 1 for update"));
                    if (winner != null && Objects.equals(winner.getDraftDigest(), incomingDigest)) {
                        return winner;
                    }
                    log.warn("Skill活动草稿冲突, skillId={}, operatorId={}",
                            skill.getId(), operatorId);
                    throw new ServiceException("草稿已存在");
                }
                return created;
            }
            AidSkillDraft current = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                    .eq(AidSkillDraft::getId, request.getDraftId())
                    .eq(AidSkillDraft::getSkillId, skill.getId())
                    .eq(AidSkillDraft::getOwnerUserId, operatorId)
                    .eq(AidSkillDraft::getStatus, EDITING).last("limit 1 for update"));
            if (current == null) {
                log.warn("Skill草稿不存在, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿不存在");
            }
            if (Objects.equals(current.getDraftDigest(), incomingDigest)) {
                return current;
            }
            if (!Objects.equals(current.getDraftDigest(), request.getDraftDigest())) {
                log.warn("Skill草稿并发冲突, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿已变化");
            }
            current.setDraftJson(incomingJson);
            current.setDraftDigest(incomingDigest);
            current.setUpdateBy(operator);
            current.setUpdateTime(now);
            int changed = draftMapper.update(current, Wrappers.<AidSkillDraft>lambdaUpdate()
                    .eq(AidSkillDraft::getId, current.getId())
                    .eq(AidSkillDraft::getDraftDigest, request.getDraftDigest())
                    .eq(AidSkillDraft::getStatus, EDITING));
            if (changed != 1) {
                log.warn("Skill草稿更新冲突, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿已变化");
            }
            packageResourceLoader.refreshAfterCommit(skill.getId(), null);
            return current;
        });
        return toDraftDetail(skill, saved, incoming);
    }

    @Override
    public SkillPackageAdminVO.ValidationResult validateDraft(
            SkillPackageAdminRequests.PackagePayload request) {
        return validatePackage(request, null);
    }

    @Override
    public void discard(SkillPackageAdminRequests.DraftDiscardRequest request,
                        Long operatorId, String operatorName) {
        transactionTemplate.executeWithoutResult(status -> {
            AidSkillDraft draft = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                    .eq(AidSkillDraft::getId, request.getDraftId())
                    .eq(AidSkillDraft::getOwnerUserId, operatorId)
                    .last("limit 1 for update"));
            if (draft == null) {
                log.warn("Skill待放弃草稿不存在, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿不存在");
            }
            if (DISCARDED.equals(draft.getStatus())) {
                try {
                    if (Objects.equals(JSON.parseObject(draft.getDraftJson())
                            .getString("sourceDraftDigest"), request.getDraftDigest())) {
                        return;
                    }
                } catch (RuntimeException error) {
                    log.error("Skill放弃回执损坏, draftId={}", draft.getId(), error);
                    throw new ServiceException("放弃回执损坏");
                }
                log.warn("Skill草稿放弃重试参数冲突, draftId={}", draft.getId());
                throw new ServiceException("放弃参数已变化");
            }
            if (!EDITING.equals(draft.getStatus())) {
                log.warn("Skill草稿状态不可放弃, draftId={}, status={}", draft.getId(), draft.getStatus());
                throw new ServiceException("草稿不可放弃");
            }
            if (!Objects.equals(draft.getDraftDigest(), request.getDraftDigest())) {
                log.warn("Skill草稿放弃冲突, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿已变化");
            }
            Date now = new Date();
            String receiptJson = JSON.toJSONString(Map.of(
                    "discarded", true, "sourceDraftDigest", request.getDraftDigest()));
            AidSkillDraft update = new AidSkillDraft();
            update.setId(draft.getId());
            update.setDraftJson(receiptJson);
            update.setDraftDigest(SecureUtil.sha256(receiptJson));
            update.setStatus(DISCARDED);
            update.setUpdateBy(operator(operatorId, operatorName));
            update.setUpdateTime(now);
            int changed = draftMapper.update(update, Wrappers.<AidSkillDraft>lambdaUpdate()
                    .set(AidSkillDraft::getActiveKey, null)
                    .eq(AidSkillDraft::getId, draft.getId())
                    .eq(AidSkillDraft::getDraftDigest, request.getDraftDigest())
                    .eq(AidSkillDraft::getStatus, EDITING));
            if (changed != 1) {
                log.warn("Skill草稿放弃收口冲突, draftId={}", draft.getId());
                throw new ServiceException("草稿已变化");
            }
        });
    }

    @Override
    public SkillPackageAdminVO.VersionDetail publish(SkillPackageAdminRequests.DraftPublishRequest request,
                                                      Long operatorId, String operatorName) {
        PublishedCoordinate coordinate = transactionTemplate.execute(status -> {
            AidSkillDraft draft = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                    .eq(AidSkillDraft::getId, request.getDraftId())
                    .eq(AidSkillDraft::getOwnerUserId, operatorId)
                    .last("limit 1 for update"));
            if (draft == null) {
                log.warn("Skill草稿不存在, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿不存在");
            }
            if (PUBLISHED.equals(draft.getStatus())) {
                PublishedCoordinate receipt = publishedReceipt(draft, request);
                packageResourceLoader.refreshAfterCommit(receipt.skillId(), receipt.versionId());
                return receipt;
            }
            if (!EDITING.equals(draft.getStatus())) {
                log.warn("Skill草稿状态不可发布, draftId={}, status={}", draft.getId(), draft.getStatus());
                throw new ServiceException("草稿不可发布");
            }
            if (!Objects.equals(draft.getDraftDigest(), request.getDraftDigest())) {
                log.warn("Skill草稿发布冲突, draftId={}, operatorId={}", request.getDraftId(), operatorId);
                throw new ServiceException("草稿已变化");
            }
            AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                    .eq(AidSkill::getId, draft.getSkillId()).eq(AidSkill::getDelFlag, NORMAL)
                    .last("limit 1 for update"));
            if (skill == null) {
                log.warn("Skill发布目标不存在, draftId={}", draft.getId());
                throw new ServiceException("Skill不存在");
            }
            if (versionMapper.selectCount(Wrappers.<AidSkillVersion>lambdaQuery()
                    .eq(AidSkillVersion::getSkillId, skill.getId())
                    .eq(AidSkillVersion::getVersionCode, request.getVersionCode())) > 0) {
                log.warn("Skill版本号重复, skillId={}, version={}", skill.getId(), request.getVersionCode());
                throw new ServiceException("版本号已存在");
            }
            DraftDocument document = parseDraft(draft);
            SkillPackageAdminVO.ValidationResult validation = validatePackage(document, skill);
            if (!Boolean.TRUE.equals(validation.getValid())) {
                log.warn("Skill草稿校验失败, draftId={}, errors={}", draft.getId(), validation.getErrors().size());
                throw new ServiceException("草稿校验失败");
            }
            validatePublication(skill);
            SkillModelConfiguration configuredModels = normalizeModelConfiguration(document);
            List<Long> modelIds = skillModelService.activeModels(configuredModels.selectableModelCodes(),
                    draftOutputKind(document.getDefinitionJson())).values().stream()
                    .map(AidAiModel::getId).toList();
            modelReferenceGuard.lockModels(modelIds);
            if (skillModelService.activeModels(configuredModels.selectableModelCodes(),
                    draftOutputKind(document.getDefinitionJson())).size() != configuredModels.selectableModelCodes().size())
                throw new ServiceException("Skill 模型已变化，请重新校验", 409);
            lockAndVerifyDraftRelations(skill, document);
            Date now = new Date();
            String operator = operator(operatorId, operatorName);
            AidSkillVersion version = buildVersion(skill, document, request.getVersionCode(), operator, now);
            businessGroupService.assertSkillSchemaCompatible(skill.getId(),
                    version.getInputSchemaJson(), version.getOutputSchemaJson());
            List<AidSkillResource> resources = buildResources(skill, version, document, operator, now);
            List<AidSkillRelation> relations = buildRelations(document, operator, now);
            version.setManifestJson(buildManifest(skill, version, resources));
            version.setPackageDigest(SkillPackageDigestCalculator.calculate(
                    skill.getSkillCode(), version, resources, relations));
            try {
                if (versionMapper.insert(version) != 1) {
                    log.error("Skill版本插入失败, skillId={}, version={}", skill.getId(),
                            request.getVersionCode());
                    throw new ServiceException("版本发布失败");
                }
            } catch (DuplicateKeyException error) {
                log.warn("Skill版本号并发重复, skillId={}, version={}", skill.getId(),
                        request.getVersionCode());
                throw new ServiceException("版本号已存在");
            }
            for (AidSkillResource resource : resources) {
                resource.setSkillVersionId(version.getId());
                if (resourceMapper.insert(resource) != 1) {
                    log.error("Skill资源插入失败, versionId={}, resourceKey={}",
                            version.getId(), resource.getResourceKey());
                    throw new ServiceException("资源发布失败");
                }
            }
            for (AidSkillRelation relation : relations) {
                relation.setParentVersionId(version.getId());
                if (relationMapper.insert(relation) != 1) {
                    log.error("Skill关系插入失败, versionId={}, relationKey={}",
                            version.getId(), relation.getRelationKey());
                    throw new ServiceException("关系发布失败");
                }
            }
            AidSkillDraft draftUpdate = new AidSkillDraft();
            draftUpdate.setId(draft.getId());
            String receiptJson = JSON.toJSONString(Map.of(
                    "publishedVersionId", version.getId(),
                    "versionCode", version.getVersionCode(),
                    "packageDigest", version.getPackageDigest(),
                    "sourceDraftDigest", request.getDraftDigest()));
            draftUpdate.setDraftJson(receiptJson);
            draftUpdate.setDraftDigest(SecureUtil.sha256(receiptJson));
            draftUpdate.setStatus(PUBLISHED);
            draftUpdate.setActiveKey(null);
            draftUpdate.setUpdateBy(operator);
            draftUpdate.setUpdateTime(now);
            int changed = draftMapper.update(draftUpdate, Wrappers.<AidSkillDraft>lambdaUpdate()
                    .set(AidSkillDraft::getActiveKey, null)
                    .eq(AidSkillDraft::getId, draft.getId())
                    .eq(AidSkillDraft::getDraftDigest, request.getDraftDigest())
                    .eq(AidSkillDraft::getStatus, EDITING));
            if (changed != 1) {
                log.warn("Skill草稿发布收口冲突, draftId={}", draft.getId());
                throw new ServiceException("草稿已变化");
            }
            packageResourceLoader.refreshAfterCommit(skill.getId(), version.getId());
            return new PublishedCoordinate(skill.getId(), version.getId());
        });
        return getVersion(coordinate.versionId());
    }

    private PublishedCoordinate publishedReceipt(AidSkillDraft draft,
                                                  SkillPackageAdminRequests.DraftPublishRequest request) {
        try {
            JSONObject receipt = JSON.parseObject(draft.getDraftJson());
            Long versionId = receipt.getLong("publishedVersionId");
            String versionCode = receipt.getString("versionCode");
            String sourceDigest = receipt.getString("sourceDraftDigest");
            if (versionId != null && Objects.equals(versionCode, request.getVersionCode())
                    && Objects.equals(sourceDigest, request.getDraftDigest())
                    && versionMapper.selectCount(Wrappers.<AidSkillVersion>lambdaQuery()
                    .eq(AidSkillVersion::getId, versionId)
                    .eq(AidSkillVersion::getSkillId, draft.getSkillId())
                    .eq(AidSkillVersion::getDelFlag, NORMAL)) == 1) {
                return new PublishedCoordinate(draft.getSkillId(), versionId);
            }
        } catch (RuntimeException error) {
            log.error("Skill发布回执损坏, draftId={}", draft.getId(), error);
            throw new ServiceException("发布回执损坏");
        }
        log.warn("Skill发布重试参数冲突, draftId={}", draft.getId());
        throw new ServiceException("发布参数已变化");
    }

    @Override
    public void activate(SkillPackageAdminRequests.VersionActivateRequest request,
                         Long operatorId, String operatorName) {
        transactionTemplate.executeWithoutResult(status -> {
            AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                    .eq(AidSkill::getId, request.getSkillId()).eq(AidSkill::getDelFlag, NORMAL)
                    .last("limit 1 for update"));
            if (skill == null) {
                log.warn("Skill切换目标不存在, skillId={}", request.getSkillId());
                throw new ServiceException("Skill不存在");
            }
            AidSkillVersion target = versionMapper.selectOne(Wrappers.<AidSkillVersion>lambdaQuery()
                    .eq(AidSkillVersion::getId, request.getVersionId())
                    .eq(AidSkillVersion::getSkillId, request.getSkillId())
                    .eq(AidSkillVersion::getDelFlag, NORMAL).last("limit 1 for update"));
            if (target == null) {
                log.warn("Skill版本归属错误, skillId={}, versionId={}",
                        request.getSkillId(), request.getVersionId());
                throw new ServiceException("版本归属错误");
            }
            requirePublicPlatformVersion(skill, target);
            if (Objects.equals(skill.getCurrentVersionId(), target.getId())) {
                return;
            }
            if (!Objects.equals(skill.getCurrentVersionId(), request.getExpectedCurrentVersionId())) {
                log.warn("Skill版本切换前置冲突, skillId={}, expected={}, actual={}", skill.getId(),
                        request.getExpectedCurrentVersionId(), skill.getCurrentVersionId());
                throw new ServiceException("当前版本已变化");
            }
            if (!ENABLED.equals(target.getStatus())) {
                log.warn("Skill版本已停用, versionId={}", request.getVersionId());
                throw new ServiceException("版本不可用");
            }
            if (!Objects.equals(skill.getInvocationScope(), target.getInvocationScope())) {
                throw new ServiceException("版本调用范围与Skill身份不一致");
            }
            if ("PUBLIC".equals(skill.getVisibility())) {
                validatePublication(skill);
                if (StrUtil.isBlank(packageResourceLoader.readPublishedSkillMarkdown(
                        skill.getSkillCode(), target))) {
                    throw new ServiceException("目标Skill版本缺少SKILL.md");
                }
            }
            SkillModelConfiguration configuredModels = SkillModelConfiguration.from(target);
            List<Long> modelIds = skillModelService.activeModels(configuredModels.selectableModelCodes(),
                    SkillModelService.outputKind(target)).values().stream()
                    .map(AidAiModel::getId).toList();
            modelReferenceGuard.lockModels(modelIds);
            if (!configuredModels.isValid() || skillModelService.activeModels(configuredModels.selectableModelCodes(),
                    SkillModelService.outputKind(target)).size() != configuredModels.selectableModelCodes().size())
                throw new ServiceException("Skill 模型已变化，请重新选择版本", 409);
            requireRelationsAvailable(skill, target);
            if ("PUBLIC".equals(skill.getVisibility())) {
                requirePublicRelationsAvailable(target);
            }
            businessGroupService.assertSkillSchemaCompatible(skill.getId(),
                    target.getInputSchemaJson(), target.getOutputSchemaJson());
            AidSkill update = new AidSkill();
            update.setId(skill.getId());
            update.setCurrentVersionId(target.getId());
            update.setExecutorType(target.getExecutorType());
            update.setModelCode(target.getModelCode());
            update.setSystemPrompt(target.getSystemPrompt());
            update.setInputSchemaJson(target.getInputSchemaJson());
            update.setOutputSchemaJson(target.getOutputSchemaJson());
            update.setDefinitionJson(target.getDefinitionJson());
            update.setMaxOutputTokens(target.getMaxOutputTokens());
            update.setContextWindowTokens(target.getContextWindowTokens());
            update.setSafetyMarginTokens(target.getSafetyMarginTokens());
            update.setConfigHash(SecureUtil.sha256(skill.getSkillCode() + "|"
                    + target.getVersionCode() + "|" + target.getPackageDigest()));
            update.setUpdateBy(operator(operatorId, operatorName));
            update.setUpdateTime(new Date());
            var updateCondition = Wrappers.<AidSkill>lambdaUpdate()
                    .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL);
            if (request.getExpectedCurrentVersionId() == null) {
                updateCondition.isNull(AidSkill::getCurrentVersionId);
            } else {
                updateCondition.eq(AidSkill::getCurrentVersionId, request.getExpectedCurrentVersionId());
            }
            int changed = skillMapper.update(update, updateCondition);
            if (changed != 1) {
                log.warn("Skill版本切换冲突, skillId={}, versionId={}", skill.getId(), target.getId());
                throw new ServiceException("当前版本已变化");
            }
            packageResourceLoader.refreshAfterCommit(skill.getId(), target.getId());
        });
    }

    private void requirePublicPlatformVersion(AidSkill skill, AidSkillVersion version) {
        if ("PLATFORM".equals(skill.getOwnerType()) && "PUBLIC".equals(skill.getVisibility())
                && (!"PUBLIC".equals(version.getVisibility())
                || !PUBLISHED.equals(version.getPublishStatus()))) {
            throw new ServiceException("平台公开Skill只能激活公开发布版本");
        }
    }

    private SkillPackageAdminVO.ValidationResult validatePackage(
            SkillPackageAdminRequests.PackagePayload payload, AidSkill knownSkill) {
        SkillPackageAdminVO.ValidationResult result = new SkillPackageAdminVO.ValidationResult();
        validator.validate(payload, Default.class, SkillPackageAdminRequests.PublishReady.class).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .forEach(violation -> issue(result.getErrors(),
                        violation.getPropertyPath().toString(), violation.getMessage()));
        SkillModelConfiguration modelConfig = normalizeModelConfiguration(payload);
        result.setDraftDigest(SecureUtil.sha256(canonicalDraftJson(
                document(payload, payload.getBaseVersionId()))));
        AidSkill skill = knownSkill;
        try {
            if (skill == null) {
                skill = requireSkill(payload.getSkillId());
            }
        } catch (ServiceException error) {
            issue(result.getErrors(), "skillId", "Skill不存在");
        }
        validateJsonObject(payload.getInputSchemaJson(), "inputSchemaJson", "输入结构错误", result);
        validateJsonObject(payload.getOutputSchemaJson(), "outputSchemaJson", "输出结构错误", result);
        for (String field : List.of("inputSchemaJson", "outputSchemaJson")) {
            try {
                com.aid.skill.service.SkillContractExamples.validate(field,
                        field.equals("inputSchemaJson") ? payload.getInputSchemaJson() : payload.getOutputSchemaJson());
            } catch (RuntimeException invalid) {
                issue(result.getErrors(), field, invalid.getMessage());
            }
        }
        validateJsonObject(StrUtil.blankToDefault(payload.getDefinitionJson(), "{}"),
                "definitionJson", "定义内容错误", result);
        validateStructuredOutputSetting(payload.getDefinitionJson(), result);
        if (skill != null && "USER".equals(skill.getOwnerType())
                && "ENTRYPOINT".equals(skill.getInvocationScope())) {
            String outputKind = draftOutputKind(payload.getDefinitionJson());
            if (!"TEXT".equals(outputKind) && !"IMAGE".equals(outputKind))
                issue(result.getErrors(), "definitionJson.outputKind",
                        "画布个人Skill支持TEXT方法包或IMAGE直接图片包；视频等制作流程请使用TEXT方法并由授权工具执行");
            if (payload.getRelations() != null && !payload.getRelations().isEmpty())
                issue(result.getErrors(), "relations", "画布个人方法由助手编排工具，不支持嵌套子Skill执行关系");
        }
        if (skill != null && "ENTRYPOINT".equals(skill.getInvocationScope())
                && "IMAGE".equals(draftOutputKind(payload.getDefinitionJson()))) {
            if (!com.aid.skill.service.SkillImageExecutionContract.supportsDefinition(payload.getDefinitionJson()))
                issue(result.getErrors(), "definitionJson.capabilityCode", "图片Skill需要受支持的图片能力");
            if (!com.aid.skill.service.SkillImageExecutionContract.supportsSchemas(
                    payload.getInputSchemaJson(), payload.getOutputSchemaJson()))
                issue(result.getErrors(), "inputSchemaJson", "图片Skill的输入和结果必须符合共享图片执行契约");
        }
        if (skill != null && "PROMPT".equals(skill.getExecutorType())
                && Set.of("INTERNAL", "ENTRYPOINT").contains(skill.getInvocationScope())) {
            try {
                if ("TEXT".equals(draftOutputKind(payload.getDefinitionJson()))) {
                    try {
                        com.aid.skill.service.SkillMethodInputContract.objectParameters(payload.getInputSchemaJson());
                    } catch (RuntimeException invalid) {
                        issue(result.getErrors(), "inputSchemaJson", invalid.getMessage());
                    }
                    com.aid.skill.service.SkillMethodOutputContract.validateForPublication(
                            payload.getDefinitionJson(), payload.getOutputSchemaJson());
                }
            } catch (RuntimeException invalid) {
                issue(result.getErrors(), "outputSchemaJson", invalid.getMessage());
            }
        }
        if (payload.getContextWindowTokens() != null && payload.getMaxOutputTokens() != null
                && payload.getSafetyMarginTokens() != null
                && (long) payload.getMaxOutputTokens() + payload.getSafetyMarginTokens()
                >= payload.getContextWindowTokens()) {
            issue(result.getErrors(), "contextWindowTokens", "上下文预算不足");
        }
        if (!modelConfig.isValid()) {
            issue(result.getErrors(), "defaultModelCode", "默认模型必须属于可选模型");
        } else if (skillModelService.activeModels(modelConfig.selectableModelCodes(),
                draftOutputKind(payload.getDefinitionJson())).size()
                != modelConfig.selectableModelCodes().size()) {
            issue(result.getErrors(), "selectableModelCodes", "存在已停用、不可用或输出类型不匹配的模型");
        }
        if (StrUtil.isNotBlank(payload.getModelConfigJson())
                && parseModelConfig(payload.getModelConfigJson()) == null) {
            issue(result.getErrors(), "modelConfigJson", "模型配置必须为JSON对象");
        }
        if (StrUtil.isBlank(payload.getSystemPrompt())) {
            issue(result.getErrors(), "systemPrompt", "系统提示词不能为空");
        }
        if (StrUtil.isBlank(payload.getSkillMarkdown())) {
            issue(result.getErrors(), "skillMarkdown", "Skill说明不能为空");
        }
        validateResources(payload.getResources(), result);
        validateDocumentReferences(payload, result);
        if (skill != null) {
            if (!baseVersionValid(skill, payload.getBaseVersionId())) {
                issue(result.getErrors(), "baseVersionId", "基础版本错误");
            }
            validateRelations(skill, payload, result);
        }
        result.setValid(result.getErrors().isEmpty());
        return result;
    }

    private void validateDocumentReferences(SkillPackageAdminRequests.PackagePayload payload,
                                            SkillPackageAdminVO.ValidationResult result) {
        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        documents.put("SKILL.md", payload.getSkillMarkdown());
        documents.put("system_prompt.md", payload.getSystemPrompt());
        fields.put("SKILL.md", "skillMarkdown");
        fields.put("system_prompt.md", "systemPrompt");
        List<SkillPackageAdminRequests.ResourceItem> resources = safeResources(payload.getResources());
        for (int index = 0; index < resources.size(); index++) {
            SkillPackageAdminRequests.ResourceItem resource = resources.get(index);
            if (resource == null) continue;
            String path = normalizedResourcePath(resource);
            if (safeReferencePath(path)) {
                documents.put(path, resource.getContent());
                fields.put(path, "resources[" + index + "].content");
            }
        }
        for (PackageDocumentReferences.MissingReference reference : PackageDocumentReferences.validate(documents)) {
            issue(result.getErrors(), fields.get(reference.sourcePath()), "第" + reference.line()
                    + "行引用的包内文件不存在或路径越界：" + reference.targetPath());
        }
    }

    private static String draftOutputKind(String definitionJson) {
        if (StrUtil.isBlank(definitionJson)) return "TEXT";
        try {
            String value = JSON.parseObject(definitionJson).getString("outputKind");
            String kind = StrUtil.blankToDefault(value, "TEXT").toUpperCase(java.util.Locale.ROOT);
            return Set.of("TEXT", "IMAGE", "VIDEO").contains(kind) ? kind : "INVALID";
        } catch (RuntimeException error) {
            return "INVALID";
        }
    }

    private void requireDraftShape(SkillPackageAdminRequests.PackagePayload payload) {
        validator.validate(payload, Default.class).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .ifPresent(message -> {
                    throw new ServiceException(message);
                });
        SkillPackageAdminVO.ValidationResult result = new SkillPackageAdminVO.ValidationResult();
        if (StrUtil.isNotBlank(payload.getInputSchemaJson())) {
            validateJsonObject(payload.getInputSchemaJson(),
                    "inputSchemaJson", "输入结构错误", result);
        }
        if (StrUtil.isNotBlank(payload.getOutputSchemaJson())) {
            validateJsonObject(payload.getOutputSchemaJson(),
                    "outputSchemaJson", "输出结构错误", result);
        }
        if (StrUtil.isNotBlank(payload.getDefinitionJson())) {
            validateJsonObject(payload.getDefinitionJson(),
                    "definitionJson", "定义内容错误", result);
            validateStructuredOutputSetting(payload.getDefinitionJson(), result);
        }
        if (StrUtil.isNotBlank(payload.getModelConfigJson())
                && parseModelConfig(payload.getModelConfigJson()) == null) {
            issue(result.getErrors(), "modelConfigJson", "模型配置必须为JSON对象");
        }
        validateResources(payload.getResources(), result);
        if (!result.getErrors().isEmpty()) {
            throw new ServiceException(result.getErrors().get(0).getMessage());
        }
    }

    private void validateResources(List<SkillPackageAdminRequests.ResourceItem> source,
                                   SkillPackageAdminVO.ValidationResult result) {
        List<SkillPackageAdminRequests.ResourceItem> resources = source == null ? List.of() : source;
        Set<String> keys = new HashSet<>();
        Set<String> paths = new HashSet<>();
        long totalBytes = 0;
        for (int index = 0; index < resources.size(); index++) {
            SkillPackageAdminRequests.ResourceItem resource = resources.get(index);
            String field = "resources[" + index + "]";
            if (resource == null) {
                issue(result.getErrors(), field, "资源内容错误");
                continue;
            }
            if (StrUtil.isBlank(resource.getResourceKey())) {
                issue(result.getErrors(), field + ".resourceKey", "资源标识必填");
            } else if (!keys.add(resource.getResourceKey())) {
                issue(result.getErrors(), field + ".resourceKey", "资源标识重复");
            }
            String filePath = normalizedResourcePath(resource);
            if (!safeReferencePath(filePath) || !paths.add(filePath)) {
                issue(result.getErrors(), field + ".filePath", "资源路径错误或重复");
            }
            String resourceType = StrUtil.blankToDefault(resource.getResourceType(), "REFERENCE")
                    .toUpperCase();
            if (!RESOURCE_TYPES.contains(resourceType)) {
                issue(result.getErrors(), field + ".resourceType", "资源类型错误");
            }
            String mimeType = StrUtil.blankToDefault(resource.getMimeType(), "text/markdown").toLowerCase();
            if (!MIME_TYPES.contains(mimeType)) {
                issue(result.getErrors(), field + ".mimeType", "媒体类型错误");
            }
            long contentBytes = value(resource.getContent()).getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > MAX_SINGLE_RESOURCE_BYTES) {
                issue(result.getErrors(), field + ".content", "单个资源过大");
            }
            totalBytes += contentBytes;
            validateRouteJson(resource.getRouteJson(), field + ".routeJson", result);
        }
        if (totalBytes > MAX_RESOURCE_BYTES) {
            issue(result.getErrors(), "resources", "资源总量过大");
        }
        if (resources.isEmpty()) {
            issue(result.getWarnings(), "resources", "未配置参考资源");
        }
    }

    private void validateRouteJson(String text, String field, SkillPackageAdminVO.ValidationResult result) {
        try {
            JSONObject route = JSON.parseObject(StrUtil.blankToDefault(text, "{}"));
            if (route == null) {
                throw new IllegalArgumentException();
            }
            Object always = route.get("always");
            if (always != null && !(always instanceof Boolean)) {
                issue(result.getErrors(), field, "always须为布尔值");
            }
            validateStringArray(route.get("operations"), field, result);
            validateStringArray(route.get("keywords"), field, result);
        } catch (RuntimeException error) {
            issue(result.getErrors(), field, "路由规则错误");
        }
    }

    private void validateStringArray(Object value, String field, SkillPackageAdminVO.ValidationResult result) {
        if (value == null) {
            return;
        }
        if (!(value instanceof JSONArray array)
                || array.stream().anyMatch(item -> !(item instanceof String text)
                || StrUtil.isBlank(text) || text.length() > 100)) {
            issue(result.getErrors(), field, "路由数组错误");
        }
    }

    private void validateRelations(AidSkill skill, SkillPackageAdminRequests.PackagePayload payload,
                                   SkillPackageAdminVO.ValidationResult result) {
        List<SkillPackageAdminRequests.RelationItem> relations = payload.getRelations() == null
                ? List.of() : payload.getRelations();
        Set<Long> childSkillIds = relations.stream().filter(Objects::nonNull)
                .map(SkillPackageAdminRequests.RelationItem::getChildSkillId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> childVersionIds = relations.stream().filter(Objects::nonNull)
                .map(SkillPackageAdminRequests.RelationItem::getChildVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AidSkill> children = childSkillIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getOwnerType,
                        AidSkill::getOwnerUserId, AidSkill::getVisibility, AidSkill::getInvocationScope,
                        AidSkill::getStatus, AidSkill::getDelFlag)
                .in(AidSkill::getId, childSkillIds)).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> childVersions = childVersionIds.isEmpty() ? Map.of()
                : versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                        AidSkillVersion::getPublishStatus, AidSkillVersion::getInvocationScope, AidSkillVersion::getStatus,
                        AidSkillVersion::getDelFlag)
                .in(AidSkillVersion::getId, childVersionIds)).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < relations.size(); index++) {
            SkillPackageAdminRequests.RelationItem relation = relations.get(index);
            String field = "relations[" + index + "]";
            if (relation == null) {
                issue(result.getErrors(), field, "子Skill错误");
                continue;
            }
            if (!keys.add(relation.getRelationKey())) {
                issue(result.getErrors(), field + ".relationKey", "关系标识重复");
            }
            if (Objects.equals(relation.getChildSkillId(), skill.getId())) {
                issue(result.getErrors(), field, "禁止循环引用");
                continue;
            }
            AidSkill child = children.get(relation.getChildSkillId());
            AidSkillVersion childVersion = childVersions.get(relation.getChildVersionId());
            if (child == null || childVersion == null
                    || !Objects.equals(child.getId(), childVersion.getSkillId())
                    || !"INTERNAL".equals(child.getInvocationScope())
                    || !ENABLED.equals(child.getStatus()) || !NORMAL.equals(child.getDelFlag())
                    || !"INTERNAL".equals(childVersion.getInvocationScope())
                    || !Set.of("PRIVATE", PUBLISHED).contains(childVersion.getPublishStatus())
                    || !ENABLED.equals(childVersion.getStatus())
                    || !NORMAL.equals(childVersion.getDelFlag())
                    || !relationVisibleToParent(skill, child)) {
                issue(result.getErrors(), field, "子版本不可用");
            } else if (!Objects.equals(relation.getRelationKey(), child.getSkillCode())) {
                issue(result.getErrors(), field + ".relationKey", "关系标识须等于子Skill编码");
            } else if ("PUBLIC".equals(skill.getVisibility())
                    && (!"PUBLIC".equals(child.getVisibility())
                    || !Set.of("PLATFORM", "USER").contains(child.getOwnerType()))) {
                issue(result.getErrors(), field, "公开Skill只能固定公开子Skill");
            }
        }
        Set<String> requiredChildren = new HashSet<>(requiredChildren(payload.getDefinitionJson()));
        requiredChildren.addAll(platformRequiredChildren(skill));
        requiredChildren.forEach(required -> {
            boolean present = relations.stream().filter(Objects::nonNull)
                    .anyMatch(relation -> required.equals(relation.getRelationKey())
                            && Boolean.TRUE.equals(relation.getRequiredFlag()));
            if (!present) {
                issue(result.getErrors(), "relations", "缺少子Skill:" + required);
            }
        });
        if (!childVersionIds.isEmpty() && relationGraphReachesSkill(childVersionIds, skill.getId())) {
            issue(result.getErrors(), "relations", "依赖图过深或存在循环");
        }
    }

    private Set<String> platformRequiredChildren(AidSkill skill) {
        if ("screenplay".equals(skill.getSkillCode())) {
            return Set.of("screenplay_write", "screenplay_review");
        }
        return Set.of();
    }

    private boolean relationGraphReachesSkill(Set<Long> startVersionIds, Long targetSkillId) {
        Set<Long> visited = new HashSet<>();
        Set<Long> frontier = new HashSet<>(startVersionIds);
        int depth = 0;
        while (!frontier.isEmpty()) {
            if (++depth > 32 || visited.size() + frontier.size() > 256) {
                return true;
            }
            visited.addAll(frontier);
            List<AidSkillRelation> nextRelations = relationMapper.selectList(
                    Wrappers.<AidSkillRelation>lambdaQuery()
                            .select(AidSkillRelation::getChildSkillId, AidSkillRelation::getChildVersionId)
                            .in(AidSkillRelation::getParentVersionId, frontier)
                            .eq(AidSkillRelation::getDelFlag, NORMAL));
            if (nextRelations.stream().anyMatch(relation ->
                    Objects.equals(relation.getChildSkillId(), targetSkillId))) {
                return true;
            }
            frontier = nextRelations.stream().map(AidSkillRelation::getChildVersionId)
                    .filter(Objects::nonNull).filter(versionId -> !visited.contains(versionId))
                    .collect(Collectors.toSet());
        }
        return false;
    }

    private List<String> requiredChildren(String definitionJson) {
        try {
            JSONArray children = JSON.parseObject(StrUtil.blankToDefault(definitionJson, "{}"))
                    .getJSONArray("children");
            if (children == null) {
                return List.of();
            }
            return children.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private void validateJsonObject(String text, String field, String message,
                                    SkillPackageAdminVO.ValidationResult result) {
        try {
            if (JSON.parseObject(text) == null) {
                issue(result.getErrors(), field, message);
            }
        } catch (RuntimeException error) {
            issue(result.getErrors(), field, message);
        }
    }

    /** Skill 版本结构化输出只能显式使用布尔值；缺省即普通文本。 */
    private void validateStructuredOutputSetting(String definitionJson,
                                                  SkillPackageAdminVO.ValidationResult result) {
        try {
            JSONObject definition = JSON.parseObject(StrUtil.blankToDefault(definitionJson, "{}"));
            Object configured = definition == null ? null : definition.get("structuredOutputEnabled");
            if (configured != null && !(configured instanceof Boolean)) {
                issue(result.getErrors(), "definitionJson.structuredOutputEnabled", "结构化输出开关必须为布尔值");
            }
        } catch (RuntimeException ignored) {
            // definitionJson 的通用校验已记录错误，避免重复提示。
        }
    }

    private AidSkillVersion buildVersion(AidSkill skill, DraftDocument document, String versionCode,
                                         String operator, Date now) {
        AidSkillVersion version = new AidSkillVersion();
        version.setSkillId(skill.getId());
        version.setVersionCode(versionCode);
        version.setVisibility(skill.getVisibility());
        version.setInvocationScope(skill.getInvocationScope());
        version.setPublishStatus("PUBLIC".equals(skill.getVisibility()) ? "PUBLISHED" : "PRIVATE");
        version.setExecutorType(skill.getExecutorType());
        SkillModelConfiguration modelConfig = normalizeModelConfiguration(document);
        version.setModelCode(modelConfig.defaultModelCode());
        version.setModelConfigJson(preservedModelConfigJson(document, modelConfig));
        version.setInputSchemaJson(document.getInputSchemaJson());
        version.setOutputSchemaJson(document.getOutputSchemaJson());
        version.setSystemPrompt(document.getSystemPrompt());
        version.setDefinitionJson(StrUtil.blankToDefault(document.getDefinitionJson(), "{}"));
        version.setMaxOutputTokens(document.getMaxOutputTokens());
        version.setContextWindowTokens(document.getContextWindowTokens());
        version.setSafetyMarginTokens(document.getSafetyMarginTokens());
        version.setStatus(ENABLED);
        version.setDelFlag(NORMAL);
        version.setCreateBy(operator);
        version.setCreateTime(now);
        version.setUpdateBy(operator);
        version.setUpdateTime(now);
        version.setRemark("后台发布的不可变版本");
        return version;
    }

    private List<AidSkillResource> buildResources(AidSkill skill, AidSkillVersion version,
                                                   DraftDocument document, String operator, Date now) {
        List<AidSkillResource> result = new ArrayList<>();
        AidSkillResource skillDocument = new AidSkillResource();
        skillDocument.setResourceKey(SKILL_DOCUMENT_KEY);
        skillDocument.setResourceType("PACKAGE");
        skillDocument.setObjectKey("database:skills/" + skill.getOwnerType().toLowerCase()
                + "/" + ownerPartition(skill) + "/" + skill.getSkillCode() + "/"
                + version.getVersionCode() + "/SKILL.md");
        skillDocument.setContentText(document.getSkillMarkdown());
        skillDocument.setContentDigest(SecureUtil.sha256(document.getSkillMarkdown()));
        skillDocument.setMimeType("text/markdown");
        skillDocument.setSizeBytes((long) document.getSkillMarkdown().getBytes(StandardCharsets.UTF_8).length);
        skillDocument.setRouteJson("{}");
        skillDocument.setStatus(ENABLED);
        skillDocument.setDelFlag(NORMAL);
        skillDocument.setCreateBy(operator);
        skillDocument.setCreateTime(now);
        skillDocument.setUpdateBy(operator);
        skillDocument.setUpdateTime(now);
        result.add(skillDocument);
        for (SkillPackageAdminRequests.ResourceItem item : safeResources(document.getResources())) {
            AidSkillResource resource = new AidSkillResource();
            resource.setResourceKey(StrUtil.trim(item.getResourceKey()));
            resource.setResourceType(StrUtil.blankToDefault(item.getResourceType(), "REFERENCE").toUpperCase());
            resource.setObjectKey("database:skills/" + skill.getOwnerType().toLowerCase()
                    + "/" + ownerPartition(skill) + "/" + skill.getSkillCode() + "/"
                    + version.getVersionCode() + "/" + normalizedResourcePath(item));
            resource.setContentText(item.getContent());
            resource.setContentDigest(SecureUtil.sha256(item.getContent()));
            resource.setMimeType(StrUtil.blankToDefault(item.getMimeType(), "text/markdown").toLowerCase());
            resource.setSizeBytes((long) item.getContent().getBytes(StandardCharsets.UTF_8).length);
            resource.setRouteJson(compactObject(StrUtil.blankToDefault(item.getRouteJson(), "{}")));
            resource.setStatus(ENABLED);
            resource.setDelFlag(NORMAL);
            resource.setCreateBy(operator);
            resource.setCreateTime(now);
            resource.setUpdateBy(operator);
            resource.setUpdateTime(now);
            result.add(resource);
        }
        return result;
    }

    private List<AidSkillRelation> buildRelations(DraftDocument document, String operator, Date now) {
        List<AidSkillRelation> result = new ArrayList<>();
        for (SkillPackageAdminRequests.RelationItem item : safeRelations(document.getRelations())) {
            AidSkillRelation relation = new AidSkillRelation();
            relation.setChildSkillId(item.getChildSkillId());
            relation.setChildVersionId(item.getChildVersionId());
            relation.setRelationType("CHILD");
            relation.setRelationKey(StrUtil.trim(item.getRelationKey()));
            relation.setRequiredFlag(!Boolean.FALSE.equals(item.getRequiredFlag()));
            relation.setDelFlag(NORMAL);
            relation.setCreateBy(operator);
            relation.setCreateTime(now);
            relation.setUpdateBy(operator);
            relation.setUpdateTime(now);
            result.add(relation);
        }
        return result;
    }

    private String buildManifest(AidSkill skill, AidSkillVersion version,
                                 List<AidSkillResource> resources) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", "2");
        manifest.put("code", skill.getSkillCode());
        manifest.put("version", version.getVersionCode());
        manifest.put("invocationScope", version.getInvocationScope());
        manifest.put("source", "DATABASE");
        manifest.put("digestAlgorithm", SkillPackageDigestCalculator.ALGORITHM_V3);
        AidSkillResource skillDocument = resources.stream()
                .filter(resource -> SKILL_DOCUMENT_KEY.equals(resource.getResourceKey()))
                .findFirst().orElseThrow(() -> new ServiceException("Skill说明缺失"));
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("skill", Map.of("path", "SKILL.md", "digest", skillDocument.getContentDigest()));
        files.put("systemPrompt", Map.of("path", "system_prompt.md",
                "digest", SecureUtil.sha256(version.getSystemPrompt())));
        manifest.put("files", files);
        manifest.put("resources", resources.stream()
                .filter(resource -> !SKILL_DOCUMENT_KEY.equals(resource.getResourceKey()))
                .map(resource -> Map.of(
                "key", resource.getResourceKey(), "digest", resource.getContentDigest(),
                "path", relativeObjectPath(resource.getObjectKey()),
                "route", JSON.parseObject(resource.getRouteJson()))).toList());
        return JSON.toJSONString(manifest);
    }

    private void requireRelationsAvailable(AidSkill parent, AidSkillVersion parentVersion) {
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .eq(AidSkillRelation::getParentVersionId, parentVersion.getId())
                .eq(AidSkillRelation::getRelationType, "CHILD")
                .eq(AidSkillRelation::getDelFlag, NORMAL)
                .orderByAsc(AidSkillRelation::getId).last("for update"));
        Set<String> requiredChildren = new HashSet<>(requiredChildren(parentVersion.getDefinitionJson()));
        requiredChildren.addAll(platformRequiredChildren(parent));
        for (String required : requiredChildren) {
            boolean present = relations.stream().anyMatch(relation -> required.equals(relation.getRelationKey())
                    && Boolean.TRUE.equals(relation.getRequiredFlag()));
            if (!present) {
                log.warn("Skill必需关系缺失, versionId={}, relationKey={}",
                        parentVersion.getId(), required);
                throw new ServiceException("必需关系缺失");
            }
        }
        Set<Long> skillIds = relations.stream().map(AidSkillRelation::getChildSkillId)
                .collect(Collectors.toSet());
        Set<Long> versionIds = relations.stream().map(AidSkillRelation::getChildVersionId)
                .collect(Collectors.toSet());
        Map<Long, AidSkill> children = skillIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .in(AidSkill::getId, skillIds).orderByAsc(AidSkill::getId)
                        .last("for update")).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> versions = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .in(AidSkillVersion::getId, versionIds).orderByAsc(AidSkillVersion::getId)
                        .last("for update")).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        SkillModelConfiguration modelConfig = SkillModelConfiguration.from(parentVersion);
        if (!modelConfig.isValid()
                || skillModelService.activeModels(modelConfig.selectableModelCodes(),
                SkillModelService.outputKind(parentVersion)).size()
                != modelConfig.selectableModelCodes().size()) {
            log.warn("Skill目标版本模型不可用, versionId={}, modelCode={}",
                    parentVersion.getId(), parentVersion.getModelCode());
            throw new ServiceException("版本模型不可用");
        }
        Map<String, AidSkillVersion> packages = new LinkedHashMap<>();
        packages.put(parent.getSkillCode(), parentVersion);
        List<AidSkillVersion> childVersions = new ArrayList<>();
        for (AidSkillRelation relation : relations) {
            AidSkill child = children.get(relation.getChildSkillId());
            AidSkillVersion version = versions.get(relation.getChildVersionId());
            if (child == null || version == null
                    || !Objects.equals(version.getSkillId(), child.getId())
                    || !Objects.equals(relation.getRelationKey(), child.getSkillCode())
                    || !"INTERNAL".equals(child.getInvocationScope())
                    || !ENABLED.equals(child.getStatus()) || !NORMAL.equals(child.getDelFlag())
                    || !"INTERNAL".equals(version.getInvocationScope())
                    || !Set.of("PRIVATE", PUBLISHED).contains(version.getPublishStatus())
                    || !ENABLED.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                    || !relationVisibleToParent(parent, child)) {
                log.warn("Skill固定子版本不可用, parentVersionId={}, relationKey={}",
                        parentVersion.getId(), relation.getRelationKey());
                throw new ServiceException("子版本不可用");
            }
            packages.put(child.getSkillCode(), version);
            childVersions.add(version);
        }
        requireModelsAvailable(childVersions, "子版本模型不可用");
        packageResourceLoader.verifyVersions(packages);
    }

    /** 发布时锁定固定依赖的身份和版本，并在同一事务中重新验证可见性、模型与包摘要。 */
    private void lockAndVerifyDraftRelations(AidSkill parent, DraftDocument document) {
        List<SkillPackageAdminRequests.RelationItem> relations = safeRelations(document.getRelations());
        Set<Long> childSkillIds = relations.stream().filter(Objects::nonNull)
                .map(SkillPackageAdminRequests.RelationItem::getChildSkillId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> childVersionIds = relations.stream().filter(Objects::nonNull)
                .map(SkillPackageAdminRequests.RelationItem::getChildVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (childSkillIds.isEmpty() && childVersionIds.isEmpty()) return;
        Map<Long, AidSkill> children = skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .in(AidSkill::getId, childSkillIds).orderByAsc(AidSkill::getId)
                        .last("for update")).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> versions = versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .in(AidSkillVersion::getId, childVersionIds).orderByAsc(AidSkillVersion::getId)
                        .last("for update")).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        Map<String, AidSkillVersion> packages = new LinkedHashMap<>();
        for (SkillPackageAdminRequests.RelationItem relation : relations) {
            AidSkill child = relation == null ? null : children.get(relation.getChildSkillId());
            AidSkillVersion version = relation == null ? null : versions.get(relation.getChildVersionId());
            if (child == null || version == null || !Objects.equals(version.getSkillId(), child.getId())
                    || !Objects.equals(relation.getRelationKey(), child.getSkillCode())
                    || !"INTERNAL".equals(child.getInvocationScope())
                    || !ENABLED.equals(child.getStatus()) || !NORMAL.equals(child.getDelFlag())
                    || !"INTERNAL".equals(version.getInvocationScope())
                    || !Set.of("PRIVATE", PUBLISHED).contains(version.getPublishStatus())
                    || !ENABLED.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                    || !relationVisibleToParent(parent, child)
                    || ("PUBLIC".equals(parent.getVisibility())
                    && (!"PUBLIC".equals(child.getVisibility())
                    || !Set.of("PLATFORM", "USER").contains(child.getOwnerType())))) {
                throw new ServiceException("子版本已变化，请重新校验", 409);
            }
            if (packages.put(child.getSkillCode(), version) != null) {
                throw new ServiceException("子Skill重复", 409);
            }
        }
        requireModelsAvailable(versions.values(), "子版本模型不可用");
        packageResourceLoader.verifyVersions(packages);
    }

    private void requireModelsAvailable(java.util.Collection<AidSkillVersion> versions, String message) {
        if (versions == null || versions.isEmpty()) return;
        Set<Long> modelIds = new LinkedHashSet<>();
        for (AidSkillVersion version : versions) {
            SkillModelConfiguration config = SkillModelConfiguration.from(version);
            if (!config.isValid()) throw new ServiceException(message);
            modelIds.addAll(skillModelService.activeModels(config.selectableModelCodes(),
                    SkillModelService.outputKind(version)).values().stream()
                    .map(AidAiModel::getId).toList());
        }
        List<Long> sortedModelIds = modelIds.stream().sorted().toList();
        for (int offset = 0; offset < sortedModelIds.size(); offset += 100) {
            modelReferenceGuard.lockModels(sortedModelIds.subList(
                    offset, Math.min(offset + 100, sortedModelIds.size())));
        }
        for (AidSkillVersion version : versions) {
            SkillModelConfiguration config = SkillModelConfiguration.from(version);
            if (skillModelService.activeModels(config.selectableModelCodes(),
                    SkillModelService.outputKind(version)).size() != config.selectableModelCodes().size()) {
                throw new ServiceException(message);
            }
        }
    }

    private static boolean relationVisibleToParent(AidSkill parent, AidSkill child) {
        if (parent == null || child == null) return false;
        if ("PUBLIC".equals(child.getVisibility())) return true;
        if (!"PRIVATE".equals(child.getVisibility())) return false;
        if ("PLATFORM".equals(parent.getOwnerType())) {
            return "PLATFORM".equals(child.getOwnerType());
        }
        return "USER".equals(parent.getOwnerType()) && "USER".equals(child.getOwnerType())
                && Objects.equals(parent.getOwnerUserId(), child.getOwnerUserId());
    }

    private SkillPackageAdminVO.VersionDetail toVersionDetail(AidSkill skill, AidSkillVersion version,
                                                               String skillMarkdown) {
        SkillPackageAdminVO.VersionDetail result = new SkillPackageAdminVO.VersionDetail();
        result.setId(version.getId());
        result.setSkillId(version.getSkillId());
        result.setSkillCode(skill.getSkillCode());
        result.setVersionCode(version.getVersionCode());
        result.setVisibility(version.getVisibility());
        result.setInvocationScope(version.getInvocationScope());
        result.setPublishStatus(version.getPublishStatus());
        result.setExecutorType(version.getExecutorType());
        SkillModelConfiguration modelConfig = SkillModelConfiguration.from(version);
        result.setModelCode(modelConfig.defaultModelCode());
        result.setModelConfigJson(version.getModelConfigJson());
        result.setDefaultModelCode(modelConfig.defaultModelCode());
        result.setSelectableModelCodes(modelConfig.selectableModelCodes());
        result.setPackageDigest(version.getPackageDigest());
        result.setManifestJson(version.getManifestJson());
        result.setInputSchemaJson(version.getInputSchemaJson());
        result.setOutputSchemaJson(version.getOutputSchemaJson());
        result.setSystemPrompt(version.getSystemPrompt());
        result.setDefinitionJson(version.getDefinitionJson());
        result.setMaxOutputTokens(version.getMaxOutputTokens());
        result.setContextWindowTokens(version.getContextWindowTokens());
        result.setSafetyMarginTokens(version.getSafetyMarginTokens());
        result.setStatus(version.getStatus());
        result.setCurrent(Objects.equals(skill.getCurrentVersionId(), version.getId()));
        result.setCreateBy(version.getCreateBy());
        result.setCreateTime(version.getCreateTime());
        List<AidSkillResource> resources = selectPublishedResources(version);
        result.setSkillMarkdown(skillMarkdown);
        result.setResources(toResources(skill.getSkillCode(), version, resources));
        result.setRelations(loadRelationItems(version.getId()));
        return result;
    }

    private List<SkillPackageAdminVO.ResourceItem> toResources(String skillCode, AidSkillVersion version,
                                                                List<AidSkillResource> resources) {
        List<SkillPackageAdminVO.ResourceItem> result = new ArrayList<>();
        Set<String> editableResourcePaths = new HashSet<>();
        for (AidSkillResource resource : resources) {
            if (SKILL_DOCUMENT_KEY.equals(resource.getResourceKey())) continue;
            SkillPackageAdminVO.ResourceItem item = toResource(skillCode, version, resource);
            requireUniqueEditableResourcePath(editableResourcePaths, item.getFilePath());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private SkillPackageAdminVO.ResourceItem toResource(String skillCode, AidSkillVersion version, AidSkillResource resource) {
        SkillPackageAdminVO.ResourceItem result = new SkillPackageAdminVO.ResourceItem();
        result.setId(resource.getId());
        result.setResourceKey(resource.getResourceKey());
        result.setFilePath(editableResourcePath(resource));
        result.setResourceType(resource.getResourceType());
        result.setObjectKey(resource.getObjectKey());
        result.setContentDigest(resource.getContentDigest());
        result.setMimeType(resource.getMimeType());
        result.setSizeBytes(resource.getSizeBytes());
        result.setRouteJson(resource.getRouteJson());
        result.setContent(packageResourceLoader.readPublishedResource(skillCode, version, resource));
        return result;
    }

    /**
     * Classpath packages predate content_text and must stay readable before the database-package migration.
     * Database packages opt in to the body column explicitly because their immutable content lives in the row.
     */
    private List<AidSkillResource> selectPublishedResources(AidSkillVersion version) {
        var query = Wrappers.<AidSkillResource>lambdaQuery();
        if (packageResourceLoader.isDatabasePackage(version)) {
            query.select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                    AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                    AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                    AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                    AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                    AidSkillResource::getStatus, AidSkillResource::getDelFlag);
        } else {
            query.select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                    AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                    AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                    AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                    AidSkillResource::getRouteJson, AidSkillResource::getStatus,
                    AidSkillResource::getDelFlag);
        }
        return resourceMapper.selectList(query
                .eq(AidSkillResource::getSkillVersionId, version.getId())
                .eq(AidSkillResource::getDelFlag, NORMAL).orderByAsc(AidSkillResource::getId));
    }

    private List<SkillPackageAdminVO.RelationItem> loadRelationItems(Long parentVersionId) {
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .eq(AidSkillRelation::getParentVersionId, parentVersionId)
                .eq(AidSkillRelation::getDelFlag, NORMAL).orderByAsc(AidSkillRelation::getId));
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<Long> skillIds = relations.stream().map(AidSkillRelation::getChildSkillId).collect(Collectors.toSet());
        Set<Long> versionIds = relations.stream().map(AidSkillRelation::getChildVersionId).collect(Collectors.toSet());
        Map<Long, AidSkill> skills = skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode).in(AidSkill::getId, skillIds)).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> versions = versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getVersionCode)
                        .in(AidSkillVersion::getId, versionIds)).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        return relations.stream().map(relation -> {
            SkillPackageAdminVO.RelationItem item = new SkillPackageAdminVO.RelationItem();
            item.setId(relation.getId());
            item.setRelationKey(relation.getRelationKey());
            item.setChildSkillId(relation.getChildSkillId());
            item.setChildVersionId(relation.getChildVersionId());
            item.setRequiredFlag(relation.getRequiredFlag());
            AidSkill child = skills.get(relation.getChildSkillId());
            AidSkillVersion childVersion = versions.get(relation.getChildVersionId());
            item.setChildSkillCode(child == null ? null : child.getSkillCode());
            item.setChildVersionCode(childVersion == null ? null : childVersion.getVersionCode());
            return item;
        }).toList();
    }

    private DraftDocument seedDocument(AidSkill skill, AidSkillVersion version) {
        String skillMarkdown = packageResourceLoader.readVerifiedDraftUpgradeSkillMarkdown(
                skill.getSkillCode(), version);
        DraftDocument document = new DraftDocument();
        document.setBaseVersionId(version.getId());
        document.setSkillId(version.getSkillId());
        SkillModelConfiguration modelConfig = SkillModelConfiguration.from(version);
        document.setModelCode(modelConfig.defaultModelCode());
        document.setDefaultModelCode(modelConfig.defaultModelCode());
        document.setModelConfigJson(version.getModelConfigJson());
        document.setSelectableModelCodes(new ArrayList<>(modelConfig.selectableModelCodes()));
        document.setSystemPrompt(version.getSystemPrompt());
        document.setInputSchemaJson(version.getInputSchemaJson());
        document.setOutputSchemaJson(version.getOutputSchemaJson());
        document.setDefinitionJson(version.getDefinitionJson());
        document.setMaxOutputTokens(version.getMaxOutputTokens());
        document.setContextWindowTokens(version.getContextWindowTokens());
        document.setSafetyMarginTokens(version.getSafetyMarginTokens());
        List<AidSkillResource> resources = selectPublishedResources(version);
        document.setSkillMarkdown(skillMarkdown == null ? defaultSkillMarkdown(skill) : skillMarkdown);
        Set<String> editableResourcePaths = new HashSet<>();
        document.setResources(resources.stream()
                .filter(resource -> !SKILL_DOCUMENT_KEY.equals(resource.getResourceKey())).map(resource -> {
            SkillPackageAdminRequests.ResourceItem item = new SkillPackageAdminRequests.ResourceItem();
            item.setResourceKey(resource.getResourceKey());
            String filePath = editableResourcePath(resource);
            requireUniqueEditableResourcePath(editableResourcePaths, filePath);
            item.setFilePath(filePath);
            item.setResourceType(resource.getResourceType());
            item.setMimeType(resource.getMimeType());
            item.setContent(packageResourceLoader.readPublishedResource(skill.getSkillCode(), version, resource));
            item.setRouteJson(resource.getRouteJson());
            return item;
        }).toList());
        document.setRelations(loadRelationItems(version.getId()).stream().map(source -> {
            SkillPackageAdminRequests.RelationItem item = new SkillPackageAdminRequests.RelationItem();
            item.setRelationKey(source.getRelationKey());
            item.setChildSkillId(source.getChildSkillId());
            item.setChildVersionId(source.getChildVersionId());
            item.setRequiredFlag(source.getRequiredFlag());
            return item;
        }).toList());
        return document;
    }

    private DraftDocument seedIdentityDocument(AidSkill skill) {
        DraftDocument document = new DraftDocument();
        document.setBaseVersionId(null);
        document.setSkillId(skill.getId());
        document.setModelCode(skill.getModelCode());
        document.setDefaultModelCode(skill.getModelCode());
        document.setSelectableModelCodes(StrUtil.isBlank(skill.getModelCode())
                ? new ArrayList<>() : new ArrayList<>(List.of(skill.getModelCode())));
        document.setSystemPrompt(skill.getSystemPrompt());
        document.setSkillMarkdown(defaultSkillMarkdown(skill));
        document.setInputSchemaJson(skill.getInputSchemaJson());
        document.setOutputSchemaJson(skill.getOutputSchemaJson());
        document.setDefinitionJson(StrUtil.blankToDefault(skill.getDefinitionJson(), "{}"));
        document.setMaxOutputTokens(skill.getMaxOutputTokens());
        document.setContextWindowTokens(skill.getContextWindowTokens());
        document.setSafetyMarginTokens(skill.getSafetyMarginTokens());
        return document;
    }

    private SkillPackageAdminVO.DraftDetail toDraftDetail(AidSkill skill, AidSkillDraft draft,
                                                           DraftDocument document) {
        SkillPackageAdminVO.DraftDetail result = new SkillPackageAdminVO.DraftDetail();
        copyPayload(document, result);
        result.setDraftId(draft == null ? null : draft.getId());
        result.setBaseVersionId(document.getBaseVersionId());
        result.setBaseVersionCode(versionCode(document.getBaseVersionId()));
        result.setSkillCode(skill.getSkillCode());
        result.setExecutorType(skill.getExecutorType());
        result.setInvocationScope(skill.getInvocationScope());
        result.setDraftDigest(draft == null
                ? SecureUtil.sha256(canonicalDraftJson(document)) : draft.getDraftDigest());
        result.setUpdateTime(draft == null ? null : draft.getUpdateTime());
        return result;
    }

    private String versionCode(Long versionId) {
        if (versionId == null) {
            return null;
        }
        AidSkillVersion version = versionMapper.selectOne(Wrappers.<AidSkillVersion>lambdaQuery()
                .select(AidSkillVersion::getId, AidSkillVersion::getVersionCode)
                .eq(AidSkillVersion::getId, versionId).last("limit 1"));
        return version == null ? null : version.getVersionCode();
    }

    private DraftDocument document(SkillPackageAdminRequests.PackagePayload source, Long baseVersionId) {
        DraftDocument result = new DraftDocument();
        result.setBaseVersionId(baseVersionId);
        copyPayload(source, result);
        result.setResources(new ArrayList<>(safeResources(source.getResources())));
        result.setRelations(new ArrayList<>(safeRelations(source.getRelations())));
        return result;
    }

    private void copyPayload(SkillPackageAdminRequests.PackagePayload source,
                             SkillPackageAdminRequests.PackagePayload target) {
        target.setSkillId(source.getSkillId());
        target.setBaseVersionId(source.getBaseVersionId());
        target.setModelCode(source.getModelCode());
        target.setDefaultModelCode(source.getDefaultModelCode());
        target.setModelConfigJson(source.getModelConfigJson());
        target.setSelectableModelCodes(new ArrayList<>(source.getSelectableModelCodes() == null
                ? List.of() : source.getSelectableModelCodes()));
        target.setSystemPrompt(source.getSystemPrompt());
        target.setSkillMarkdown(source.getSkillMarkdown());
        target.setInputSchemaJson(source.getInputSchemaJson());
        target.setOutputSchemaJson(source.getOutputSchemaJson());
        target.setDefinitionJson(source.getDefinitionJson());
        target.setMaxOutputTokens(source.getMaxOutputTokens());
        target.setContextWindowTokens(source.getContextWindowTokens());
        target.setSafetyMarginTokens(source.getSafetyMarginTokens());
        target.setResources(new ArrayList<>(safeResources(source.getResources())));
        target.setRelations(new ArrayList<>(safeRelations(source.getRelations())));
    }

    private String canonicalDraftJson(DraftDocument document) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 2);
        value.put("baseVersionId", document.getBaseVersionId());
        value.put("skillId", document.getSkillId());
        value.put("modelCode", document.getModelCode());
        value.put("defaultModelCode", document.getDefaultModelCode());
        value.put("modelConfigJson", document.getModelConfigJson());
        value.put("selectableModelCodes", document.getSelectableModelCodes());
        value.put("systemPrompt", document.getSystemPrompt());
        value.put("skillMarkdown", document.getSkillMarkdown());
        value.put("inputSchemaJson", document.getInputSchemaJson());
        value.put("outputSchemaJson", document.getOutputSchemaJson());
        value.put("definitionJson", document.getDefinitionJson());
        value.put("maxOutputTokens", document.getMaxOutputTokens());
        value.put("contextWindowTokens", document.getContextWindowTokens());
        value.put("safetyMarginTokens", document.getSafetyMarginTokens());
        value.put("resources", safeResources(document.getResources()).stream()
                .map(item -> {
                    Map<String, Object> resource = new LinkedHashMap<>();
                    if (item == null) {
                        resource.put("invalid", true);
                        return resource;
                    }
                    resource.put("resourceKey", item.getResourceKey());
                    resource.put("filePath", normalizedResourcePath(item));
                    resource.put("resourceType", item.getResourceType());
                    resource.put("mimeType", item.getMimeType());
                    resource.put("content", item.getContent());
                    resource.put("routeJson", item.getRouteJson());
                    return resource;
                }).toList());
        value.put("relations", safeRelations(document.getRelations()).stream()
                .sorted(Comparator.comparing(item -> item == null ? null : item.getRelationKey(),
                        Comparator.nullsFirst(String::compareTo)))
                .map(item -> {
                    Map<String, Object> relation = new LinkedHashMap<>();
                    if (item == null) {
                        relation.put("invalid", true);
                        return relation;
                    }
                    relation.put("relationKey", item.getRelationKey());
                    relation.put("childSkillId", item.getChildSkillId());
                    relation.put("childVersionId", item.getChildVersionId());
                    relation.put("requiredFlag", item.getRequiredFlag());
                    return relation;
                }).toList());
        return JSON.toJSONString(value);
    }

    private DraftDocument parseDraft(AidSkillDraft draft) {
        try {
            String storedJson = draft.getDraftJson();
            DraftDocument result = JSON.parseObject(storedJson, DraftDocument.class);
            boolean storedDigestMatches = Objects.equals(SecureUtil.sha256(storedJson), draft.getDraftDigest());
            boolean currentCanonicalMatches = result != null && Objects.equals(
                    SecureUtil.sha256(canonicalDraftJson(result)), draft.getDraftDigest());
            if (result == null || (!storedDigestMatches && !currentCanonicalMatches)) {
                throw new IllegalArgumentException();
            }
            if (result.getSkillId() != null && !Objects.equals(result.getSkillId(), draft.getSkillId())) {
                throw new IllegalArgumentException();
            }
            if (result.getSkillId() == null) {
                result.setSkillId(draft.getSkillId());
            }
            return result;
        } catch (RuntimeException error) {
            log.error("Skill草稿内容损坏, draftId={}", draft.getId(), error);
            throw new ServiceException("草稿内容损坏");
        }
    }

    private AidSkill requireSkill(Long skillId) {
        AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getId, skillId).eq(AidSkill::getDelFlag, NORMAL).last("limit 1"));
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            log.warn("Skill管理目标不存在, skillId={}", skillId);
            throw new ServiceException("Skill不存在");
        }
        return skill;
    }

    private AidSkill requireSkillLight(Long skillId) {
        AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                        AidSkill::getInvocationScope, AidSkill::getCurrentVersionId,
                        AidSkill::getStatus, AidSkill::getDelFlag)
                .eq(AidSkill::getId, skillId).eq(AidSkill::getDelFlag, NORMAL).last("limit 1"));
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            log.warn("Skill管理目标不存在, skillId={}", skillId);
            throw new ServiceException("Skill不存在");
        }
        return skill;
    }

    private AidSkillVersion requireVersion(Long versionId) {
        AidSkillVersion version = versionMapper.selectOne(Wrappers.<AidSkillVersion>lambdaQuery()
                .eq(AidSkillVersion::getId, versionId).eq(AidSkillVersion::getDelFlag, NORMAL)
                .last("limit 1"));
        if (version == null) {
            log.warn("Skill版本不存在, versionId={}", versionId);
            throw new ServiceException("版本不存在");
        }
        return version;
    }

    private AidSkillVersion requireCurrentVersion(AidSkill skill) {
        if (skill.getCurrentVersionId() == null) {
            log.warn("Skill当前版本缺失, skillId={}", skill.getId());
            throw new ServiceException("当前版本不存在");
        }
        AidSkillVersion version = versionMapper.selectOne(Wrappers.<AidSkillVersion>lambdaQuery()
                .eq(AidSkillVersion::getId, skill.getCurrentVersionId())
                .eq(AidSkillVersion::getSkillId, skill.getId())
                .eq(AidSkillVersion::getDelFlag, NORMAL).last("limit 1"));
        if (version == null) {
            log.warn("Skill当前版本无效, skillId={}, versionId={}",
                    skill.getId(), skill.getCurrentVersionId());
            throw new ServiceException("当前版本不存在");
        }
        return version;
    }

    private boolean baseVersionValid(AidSkill skill, Long versionId) {
        if (versionId == null) {
            return skill.getCurrentVersionId() == null;
        }
        return versionMapper.selectCount(Wrappers.<AidSkillVersion>lambdaQuery()
                .eq(AidSkillVersion::getId, versionId).eq(AidSkillVersion::getSkillId, skill.getId())
                .eq(AidSkillVersion::getDelFlag, NORMAL)) == 1;
    }

    private SkillPackageAdminVO.VersionSummary toSummary(AidSkillVersion version, Long currentVersionId) {
        SkillPackageAdminVO.VersionSummary result = new SkillPackageAdminVO.VersionSummary();
        result.setId(version.getId());
        result.setSkillId(version.getSkillId());
        result.setVersionCode(version.getVersionCode());
        result.setPublishStatus(version.getPublishStatus());
        result.setPackageDigest(version.getPackageDigest());
        result.setStatus(version.getStatus());
        result.setCurrent(Objects.equals(currentVersionId, version.getId()));
        result.setCreateBy(version.getCreateBy());
        result.setCreateTime(version.getCreateTime());
        return result;
    }

    private SkillModelConfiguration normalizeModelConfiguration(
            SkillPackageAdminRequests.PackagePayload payload) {
        JSONObject original = parseModelConfig(payload.getModelConfigJson());
        String defaultCode = StrUtil.blankToDefault(
                StrUtil.trim(payload.getDefaultModelCode()), StrUtil.blankToDefault(
                        StrUtil.trim(payload.getModelCode()), original == null
                                ? null : original.getString("defaultModelCode")));
        List<String> candidates = payload.getSelectableModelCodes();
        if ((candidates == null || candidates.isEmpty()) && original != null
                && original.getJSONArray("selectableModelCodes") != null) {
            candidates = original.getJSONArray("selectableModelCodes").toJavaList(String.class);
        }
        SkillModelConfiguration config = SkillModelConfiguration.normalized(defaultCode, candidates, true);
        payload.setDefaultModelCode(config.defaultModelCode());
        payload.setSelectableModelCodes(new ArrayList<>(config.selectableModelCodes()));
        payload.setModelCode(config.defaultModelCode());
        return config;
    }

    private String preservedModelConfigJson(SkillPackageAdminRequests.PackagePayload payload,
                                            SkillModelConfiguration normalized) {
        String originalText = payload.getModelConfigJson();
        JSONObject original = parseModelConfig(originalText);
        if (original == null) return normalized.toJson();
        SkillModelConfiguration originalProjection = SkillModelConfiguration.normalized(
                original.getString("defaultModelCode"),
                original.getJSONArray("selectableModelCodes") == null ? List.of()
                        : original.getJSONArray("selectableModelCodes").toJavaList(String.class), true);
        if (Objects.equals(originalProjection, normalized)) return originalText;
        original.put("defaultModelCode", normalized.defaultModelCode());
        original.put("selectableModelCodes", normalized.selectableModelCodes());
        return original.toJSONString();
    }

    private JSONObject parseModelConfig(String text) {
        if (StrUtil.isBlank(text)) return null;
        try {
            return JSON.parseObject(text);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String compactObject(String text) {
        return JSON.toJSONString(JSON.parseObject(text));
    }

    private void validatePublication(AidSkill skill) {
        if (!"PUBLIC".equals(skill.getVisibility())) {
            return;
        }
        if ("USER".equals(skill.getOwnerType()) && !Boolean.parseBoolean(StrUtil.blankToDefault(
                sysConfigService.selectConfigByKey(PUBLICATION_CONFIG_KEY), "false"))) {
            throw new ServiceException("用户公开Skill已关闭");
        }
        if (!skillCoverPolicy.validUploadedCover(skill)) {
            throw new ServiceException("请先上传Skill封面");
        }
    }

    private static String normalizedResourcePath(SkillPackageAdminRequests.ResourceItem item) {
        if (item == null) return "";
        if (StrUtil.isNotBlank(item.getFilePath())) return StrUtil.trim(item.getFilePath());
        return defaultReferencePath(item.getResourceKey());
    }

    private static boolean safeReferencePath(String path) {
        if (StrUtil.isBlank(path) || !path.startsWith("references/") || path.contains("\\")) return false;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return path.length() <= 192 && path.chars().noneMatch(value -> value < 0x20 || value == 0x7f);
    }

    private static String relativeObjectPath(String objectKey) {
        if (StrUtil.isBlank(objectKey)) return null;
        int reference = objectKey.indexOf("references/");
        if (reference >= 0) return objectKey.substring(reference);
        if (objectKey.endsWith("/SKILL.md")) return "SKILL.md";
        return null;
    }

    private static String editableResourcePath(AidSkillResource resource) {
        String path = relativeObjectPath(resource.getObjectKey());
        if (StrUtil.isBlank(path)) {
            path = defaultReferencePath(resource.getResourceKey());
        }
        if (!safeReferencePath(path)) {
            throw new ServiceException("Skill资源路径无效");
        }
        return path;
    }

    private static String defaultReferencePath(String resourceKey) {
        String key = StrUtil.trim(resourceKey);
        if (StrUtil.isBlank(key)) return "";
        return "references/" + key + (key != null && key.contains(".") ? "" : ".md");
    }

    private static void requireUniqueEditableResourcePath(Set<String> paths, String path) {
        if (!paths.add(path)) {
            throw new ServiceException("Skill资源路径重复");
        }
    }

    private static String ownerPartition(AidSkill skill) {
        return "USER".equals(skill.getOwnerType())
                ? String.valueOf(skill.getOwnerUserId()) : "platform";
    }

    private static String defaultSkillMarkdown(AidSkill skill) {
        StringBuilder markdown = new StringBuilder("# ").append(skill.getName()).append("\n\n");
        if (StrUtil.isNotBlank(skill.getDescription())) markdown.append(skill.getDescription()).append("\n");
        return markdown.toString();
    }

    private void issue(List<SkillPackageAdminVO.ValidationIssue> target, String field, String message) {
        target.add(new SkillPackageAdminVO.ValidationIssue(field, message));
    }

    private List<SkillPackageAdminRequests.ResourceItem> safeResources(
            List<SkillPackageAdminRequests.ResourceItem> resources) {
        return resources == null ? List.of() : resources;
    }

    private List<SkillPackageAdminRequests.RelationItem> safeRelations(
            List<SkillPackageAdminRequests.RelationItem> relations) {
        return relations == null ? List.of() : relations;
    }

    private String operator(Long operatorId, String operatorName) {
        return StrUtil.blankToDefault(operatorName, String.valueOf(operatorId));
    }

    private String activeDraftKey(Long operatorId, Long skillId) {
        return operatorId + ":" + skillId;
    }

    private String value(String source) {
        return source == null ? "" : source;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class DraftDocument extends SkillPackageAdminRequests.PackagePayload {
        private Integer schemaVersion = 2;
    }

    private record PublishedCoordinate(Long skillId, Long versionId) { }
}
