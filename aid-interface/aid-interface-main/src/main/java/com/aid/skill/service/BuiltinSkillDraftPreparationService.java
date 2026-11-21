package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.vo.BuiltinSkillPackageViews;
import com.aid.skill.vo.SkillAdminVO;
import com.aid.skill.vo.SkillPackageAdminVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creates reviewable Skill identities and drafts; publishing and activation remain explicit admin actions. */
@Service
@RequiredArgsConstructor
public class BuiltinSkillDraftPreparationService
{
    private static final String NORMAL = "0";
    private static final String DISABLED = "1";
    private static final String EDITING = "EDITING";
    private final BuiltinSkillPackageCatalog catalog;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper versions;
    private final AidSkillDraftMapper drafts;
    private final ISkillPackageAdminService packages;
    private final SkillModelService models;

    public BuiltinSkillPackageViews.Status status(Long actorId)
    {
        return inspect(actorId, modelCode(false));
    }

    @Transactional(rollbackFor = Exception.class)
    public BuiltinSkillPackageViews.Status prepare(Long actorId, String actor)
    {
        if (actorId == null) throw new ServiceException("管理员身份无效");
        String modelCode = modelCode(true);
        BuiltinSkillPackageViews.Status before = inspect(actorId, modelCode);
        if (before.getConflicts() > 0) throw new ServiceException("内置Skill存在冲突，请先处理");
        Map<String, BuiltinSkillPackageViews.Dependency> states = before.getItems().stream()
                .collect(Collectors.toMap(BuiltinSkillPackageViews.Dependency::getSkillCode, Function.identity()));
        for (BuiltinSkillPackageCatalog.Definition definition : catalog.definitions())
        {
            BuiltinSkillPackageViews.Dependency state = states.get(definition.code());
            if (state != null && List.of("READY", "PUBLISHED_DISABLED", "PUBLISHED_INACTIVE", "DRAFT_READY")
                    .contains(state.getState()))
                continue;
            AidSkill skill = requireOrCreateIdentity(definition, actorId, actor, modelCode);
            SkillPackageAdminRequests.DraftSaveRequest request = catalog.draft(definition, skill.getId(), modelCode);
            SkillPackageAdminVO.ValidationResult validation = packages.validateDraft(request);
            if (!Boolean.TRUE.equals(validation.getValid()))
                throw new ServiceException(validation.getErrors().isEmpty()
                        ? "内置Skill草稿校验失败" : validation.getErrors().get(0).getMessage());
            packages.saveDraft(request, actorId, actor);
        }
        return inspect(actorId, modelCode);
    }

    private BuiltinSkillPackageViews.Status inspect(Long actorId, String modelCode)
    {
        List<AidSkill> identities = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .in(AidSkill::getSkillCode, catalog.codes()));
        Map<String, AidSkill> byCode = identities.stream().collect(Collectors.toMap(
                AidSkill::getSkillCode, Function.identity(), (left, right) -> left));
        List<Long> skillIds = identities.stream().map(AidSkill::getId).toList();
        List<Long> currentVersionIds = identities.stream().map(AidSkill::getCurrentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AidSkillVersion> currentById = currentVersionIds.isEmpty() ? Map.of()
                : versions.selectBatchIds(currentVersionIds).stream().collect(Collectors.toMap(
                        AidSkillVersion::getId, Function.identity(), (left, right) -> left));
        List<Long> inactiveSkillIds = identities.stream()
                .filter(skill -> skill.getCurrentVersionId() == null).map(AidSkill::getId).toList();
        Map<Long, AidSkillVersion> fallbackBySkill = inactiveSkillIds.isEmpty() ? Map.of()
                : versions.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .in(AidSkillVersion::getSkillId, inactiveSkillIds)
                        .eq(AidSkillVersion::getVersionCode, BuiltinSkillPackageCatalog.VERSION)
                        .orderByDesc(AidSkillVersion::getId)).stream().collect(Collectors.toMap(
                        AidSkillVersion::getSkillId, Function.identity(), (left, right) -> left));
        Map<Long, AidSkillVersion> bySkill = new HashMap<>(fallbackBySkill);
        for (AidSkill skill : identities)
        {
            if (skill.getCurrentVersionId() == null) continue;
            AidSkillVersion current = currentById.get(skill.getCurrentVersionId());
            if (current != null && Objects.equals(current.getSkillId(), skill.getId()))
                bySkill.put(skill.getId(), current);
            else
                bySkill.remove(skill.getId());
        }
        Map<Long, AidSkillDraft> byDraftSkill = actorId == null || skillIds.isEmpty() ? Map.of()
                : drafts.selectList(Wrappers.<AidSkillDraft>lambdaQuery()
                        .in(AidSkillDraft::getSkillId, skillIds)
                        .eq(AidSkillDraft::getOwnerUserId, actorId)
                        .eq(AidSkillDraft::getStatus, EDITING).orderByDesc(AidSkillDraft::getId))
                .stream().collect(Collectors.toMap(AidSkillDraft::getSkillId, Function.identity(),
                        (left, right) -> left));
        BuiltinSkillPackageViews.Status result = new BuiltinSkillPackageViews.Status();
        List<BuiltinSkillPackageViews.Dependency> items = catalog.definitions().stream()
                .map(definition -> dependency(definition, byCode.get(definition.code()), bySkill, byDraftSkill,
                        modelCode)).toList();
        result.setItems(items); result.setTotal(items.size());
        result.setReady((int) items.stream().filter(item -> "READY".equals(item.getState())).count());
        result.setDrafts((int) items.stream().filter(item -> "DRAFT_READY".equals(item.getState())).count());
        result.setMissing((int) items.stream().filter(item -> State.missing(item.getState())).count());
        result.setConflicts((int) items.stream().filter(item -> State.conflict(item.getState())).count());
        return result;
    }

    private BuiltinSkillPackageViews.Dependency dependency(BuiltinSkillPackageCatalog.Definition definition,
                                                            AidSkill skill,
                                                            Map<Long, AidSkillVersion> bySkill,
                                                            Map<Long, AidSkillDraft> byDraftSkill,
                                                            String modelCode)
    {
        BuiltinSkillPackageViews.Dependency item = new BuiltinSkillPackageViews.Dependency();
        item.setSkillCode(definition.code()); item.setName(definition.name());
        item.setVersionCode(definition.version()); item.setSourceDigest(definition.sourceDigest());
        if (skill == null)
        {
            item.setState(modelCode == null ? "MODEL_REQUIRED" : "MISSING");
            item.setMessage(modelCode == null ? "请先配置可用文本模型" : "待准备身份与草稿");
            return item;
        }
        item.setSkillId(skill.getId()); item.setCurrentVersionId(skill.getCurrentVersionId());
        if (!NORMAL.equals(skill.getDelFlag())) return conflict(item, "IDENTITY_CONFLICT", "同名Skill已删除");
        AidSkillVersion version = bySkill.get(skill.getId());
        if (skill.getCurrentVersionId() != null && version == null)
            return conflict(item, "VERSION_CONFLICT", "当前版本不存在或不属于该Skill");
        if (version != null)
        {
            item.setVersionId(version.getId()); item.setVersionCode(version.getVersionCode());
            if (!sourceMatches(version.getDefinitionJson(), definition.sourceDigest()))
                return conflict(item, "VERSION_CONFLICT", "同版本来源摘要不一致");
            if (!NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag()))
                return conflict(item, "VERSION_DISABLED", "固定版本不可用");
            if (!Objects.equals(skill.getCurrentVersionId(), version.getId()))
            {
                item.setState("PUBLISHED_INACTIVE");
                item.setMessage("\u7248\u672c\u5df2\u53d1\u5e03\uff0c\u8bf7\u663e\u5f0f\u6fc0\u6d3b\u56fa\u5b9a\u7248\u672c");
                return item;
            }
            item.setState(NORMAL.equals(skill.getStatus()) ? "READY" : "PUBLISHED_DISABLED");
            item.setMessage(NORMAL.equals(skill.getStatus())
                    ? "\u56fa\u5b9a\u7248\u672c\u53ef\u4f9bAgent\u5bfc\u5165"
                    : "\u7248\u672c\u5df2\u6fc0\u6d3b\uff0c\u8bf7\u663e\u5f0f\u542f\u7528Skill\u8eab\u4efd");
            return item;
        }
        AidSkillDraft draft = byDraftSkill.get(skill.getId());
        if (draft == null)
        {
            item.setState(modelCode == null ? "MODEL_REQUIRED" : "MISSING_DRAFT");
            item.setMessage(modelCode == null ? "请先配置可用文本模型" : "待准备草稿");
            return item;
        }
        item.setDraftId(draft.getId()); item.setDraftDigest(draft.getDraftDigest());
        if (!sourceMatches(draftDefinition(draft), definition.sourceDigest()))
            return conflict(item, "DRAFT_CONFLICT", "现有管理员草稿不是当前内置包");
        item.setState("DRAFT_READY"); item.setMessage("草稿待管理员校验、发布和启用");
        return item;
    }

    private AidSkill requireOrCreateIdentity(BuiltinSkillPackageCatalog.Definition definition, Long actorId,
                                               String actor, String modelCode)
    {
        AidSkill existing = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getSkillCode, definition.code()).last("LIMIT 1 FOR UPDATE"));
        if (existing != null) return existing;
        AidSkill created = new AidSkill();
        SkillPackageAdminRequests.DraftSaveRequest seed = catalog.draft(definition, null, modelCode);
        created.setSkillCode(definition.code()); created.setName(definition.name());
        created.setDescription("内置方法包 " + definition.name());
        created.setCapabilityDescription("由内置资源准备的固定版本Skill");
        created.setOwnerType("PLATFORM"); created.setOwnerUserId(null); created.setVisibility("PUBLIC");
        created.setInvocationScope("INTERNAL"); created.setExecutorType("PROMPT");
        created.setModelCode(modelCode);
        // aid_skill seeds the first editable draft. Its required snapshot must be
        // complete even while this new identity remains disabled and non-executable.
        created.setSystemPrompt(seed.getSystemPrompt());
        created.setInputSchemaJson(seed.getInputSchemaJson());
        created.setOutputSchemaJson(seed.getOutputSchemaJson());
        created.setDefinitionJson(seed.getDefinitionJson());
        created.setMaxOutputTokens(seed.getMaxOutputTokens());
        created.setContextWindowTokens(seed.getContextWindowTokens());
        created.setSafetyMarginTokens(seed.getSafetyMarginTokens());
        created.setConfigHash(SecureUtil.sha256(
                definition.code() + "|DRAFT|" + definition.sourceDigest()));
        created.setStatus(DISABLED); created.setDelFlag(NORMAL);
        created.setCreateBy(StrUtil.blankToDefault(actor, String.valueOf(actorId))); created.setCreateTime(new Date());
        created.setUpdateBy(created.getCreateBy()); created.setUpdateTime(created.getCreateTime());
        try
        {
            if (skills.insert(created) != 1) throw new ServiceException("内置Skill身份创建失败");
        }
        catch (DuplicateKeyException conflict)
        {
            throw new ServiceException("内置Skill身份并发冲突");
        }
        return created;
    }

    private String modelCode(boolean required)
    {
        String code = models.firstAvailableTextModelCode();
        if (required && code == null) throw new ServiceException("没有可用文本模型");
        return code;
    }

    private String draftDefinition(AidSkillDraft draft)
    {
        try { return JSON.parseObject(draft.getDraftJson()).getString("definitionJson"); }
        catch (RuntimeException invalid) { return null; }
    }

    private boolean sourceMatches(String definitionJson, String digest)
    {
        try
        {
            JSONObject definition = JSON.parseObject(definitionJson);
            JSONObject source = definition == null ? null : definition.getJSONObject("builtinSource");
            return source != null && Objects.equals(digest, source.getString("digest"));
        }
        catch (RuntimeException invalid) { return false; }
    }

    private BuiltinSkillPackageViews.Dependency conflict(BuiltinSkillPackageViews.Dependency item,
                                                           String state, String message)
    {
        item.setState(state); item.setMessage(message); return item;
    }

    private static final class State
    {
        private State() { }
        private static boolean missing(String value)
        {
            return List.of("MISSING", "MISSING_DRAFT", "MODEL_REQUIRED").contains(value);
        }
        private static boolean conflict(String value)
        {
            return value != null && (value.endsWith("CONFLICT") || "VERSION_DISABLED".equals(value));
        }
    }
}
