package com.aid.runtime.legacy.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.service.support.ModelReferenceGuard;
import com.aid.businessgroup.domain.AidBusinessGroupMember;
import com.aid.businessgroup.domain.AidBusinessGroup;
import com.aid.businessgroup.mapper.AidBusinessGroupMemberMapper;
import com.aid.businessgroup.mapper.AidBusinessGroupMapper;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentDraft;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.mapper.AidRuntimeAgentDraftMapper;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.service.RuntimeReferenceErrors;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillModelConfiguration;
import com.aid.skill.vo.SkillAdminVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable identity, optimistic drafts and immutable Agent versions. */
@Service
@RequiredArgsConstructor
public class LegacyRuntimeAgentAdminService {
    private static final String NORMAL = "0";
    private static final String DISABLED = "1";
    private static final String LEGACY_FAMILY = LegacyRuntimeAgentRequests.LEGACY_PLAN;
    private final AidRuntimeAgentMapper agents;
    private final AidRuntimeAgentDraftMapper drafts;
    private final AidRuntimeAgentVersionMapper versions;
    private final AidRuntimeAgentSkillRefMapper skillRefs;
    private final AidRuntimeRunMapper runs;
    private final AidBusinessGroupMemberMapper groupMembers;
    private final AidBusinessGroupMapper groupMapper;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper skillVersions;
    private final SkillModelService modelService;
    private final ModelReferenceGuard modelReferenceGuard;
    private final RuntimeToolRegistry tools;

    public List<SkillAdminVO.TextModelOption> modelOptions() { return modelService.adminOptions(); }

    public List<JSONObject> skillOptions(Long userId) {
        List<AidSkill> candidates = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                        AidSkill::getCurrentVersionId, AidSkill::getOwnerUserId,
                        AidSkill::getVisibility)
                .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL)
                .and(q -> q.eq(AidSkill::getVisibility, "PUBLIC")
                        .or().eq(AidSkill::getOwnerUserId, userId))
                .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                .isNotNull(AidSkill::getCurrentVersionId)
                .orderByAsc(AidSkill::getId));
        if (candidates.isEmpty()) return List.of();
        Map<Long, AidSkillVersion> versionMap = skillVersions.selectList(
                Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getStatus,
                                AidSkillVersion::getDelFlag, AidSkillVersion::getExecutorType,
                                AidSkillVersion::getModelCode)
                        .in(AidSkillVersion::getId, candidates.stream()
                                .map(AidSkill::getCurrentVersionId).distinct().toList()))
                .stream().collect(java.util.stream.Collectors.toMap(AidSkillVersion::getId,
                        version -> version));
        return candidates.stream().map(skill -> {
                    AidSkillVersion version = versionMap.get(skill.getCurrentVersionId());
                    if (version == null || !NORMAL.equals(version.getStatus())
                            || !NORMAL.equals(version.getDelFlag())) return null;
                    JSONObject item = new JSONObject();
                    item.put("id", skill.getId());
                    item.put("skillCode", skill.getSkillCode());
                    item.put("name", skill.getName());
                    item.put("visibility", skill.getVisibility());
                    item.put("currentVersionId", skill.getCurrentVersionId());
                    item.put("currentExecutorType", version.getExecutorType());
                    item.put("currentModelCode", version.getModelCode());
                    return item;
                }).filter(Objects::nonNull).toList();
    }

    public Page<AidRuntimeAgent> page(LegacyRuntimeAgentRequests.Page request) {
        LegacyRuntimeAgentRequests.Page source = request == null ? new LegacyRuntimeAgentRequests.Page() : request;
        return agents.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentCode,
                                AidRuntimeAgent::getName, AidRuntimeAgent::getDescription,
                                AidRuntimeAgent::getIconUrl, AidRuntimeAgent::getOwnerType,
                                AidRuntimeAgent::getOwnerUserId, AidRuntimeAgent::getVisibility,
                                AidRuntimeAgent::getAgentFamily,
                                AidRuntimeAgent::getCurrentVersionId, AidRuntimeAgent::getStatus,
                                AidRuntimeAgent::getDelFlag, AidRuntimeAgent::getUpdateTime)
                        .eq(AidRuntimeAgent::getAgentFamily, LEGACY_FAMILY)
                        .eq(AidRuntimeAgent::getDelFlag, NORMAL)
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidRuntimeAgent::getStatus, source.getStatus())
                        .and(StrUtil.isNotBlank(source.getKeyword()), q -> q
                                .like(AidRuntimeAgent::getAgentCode, source.getKeyword())
                                .or().like(AidRuntimeAgent::getName, source.getKeyword()))
                        .orderByDesc(AidRuntimeAgent::getId));
    }

    public AidRuntimeAgent detail(Long id) { return requireAgent(id); }

    @Transactional(rollbackFor = Exception.class)
    public AidRuntimeAgent create(LegacyRuntimeAgentRequests.Create request, Long actorId,
                                  String actor, boolean platform) {
        requireAvailableCode(request.getAgentCode());
        AidRuntimeAgent agent = new AidRuntimeAgent();
        agent.setAgentCode(StrUtil.trim(request.getAgentCode()));
        agent.setName(StrUtil.trim(request.getName()));
        agent.setDescription(StrUtil.trim(request.getDescription()));
        agent.setIconUrl(StrUtil.trim(request.getIconUrl()));
        agent.setOwnerType(platform ? "PLATFORM" : "USER");
        agent.setOwnerUserId(platform ? null : actorId);
        agent.setVisibility(platform ? request.getVisibility() : "PRIVATE");
        agent.setAgentFamily(LEGACY_FAMILY);
        agent.setStatus(DISABLED);
        agent.setDelFlag(NORMAL);
        agent.setCreateBy(actor);
        agent.setCreateTime(new Date());
        agent.setUpdateBy(actor);
        agent.setUpdateTime(new Date());
        try { agents.insert(agent); }
        catch (DuplicateKeyException error) {
            requireAvailableCode(request.getAgentCode());
            throw new ServiceException("Agent code already exists", 409);
        }
        if (request.getTemplateVersionId() != null) {
            AidRuntimeAgentVersion source = requireVersion(request.getTemplateVersionId());
            AidRuntimeAgent sourceIdentity = requireAgent(source.getAgentId());
            if (!"PUBLIC".equals(sourceIdentity.getVisibility())
                    && !Objects.equals(sourceIdentity.getOwnerUserId(), actorId)) {
                throw new ServiceException("Agent 模板不可访问");
            }
            LegacyRuntimeAgentRequests.DraftPayload seed = definition(source);
            seed.setAgentId(agent.getId());
            seed.setBaseVersionId(null);
            saveDraft(seed, null, null, actorId, actor);
        }
        return agent;
    }

    @Transactional(rollbackFor = Exception.class)
    public void editIdentity(LegacyRuntimeAgentRequests.Identity request, String actor) {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getId());
        agent.setName(StrUtil.trim(request.getName()));
        agent.setDescription(StrUtil.trim(request.getDescription()));
        agent.setIconUrl(StrUtil.trim(request.getIconUrl()));
        if (request.getVisibility() != null) agent.setVisibility(request.getVisibility());
        agent.setUpdateBy(actor);
        agent.setUpdateTime(new Date());
        agents.updateById(agent);
    }

    @Transactional(rollbackFor = Exception.class)
    public void status(LegacyRuntimeAgentRequests.Status request, String actor) {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getId());
        if (NORMAL.equals(request.getStatus()) && agent.getCurrentVersionId() != null)
            definition(requireVersion(agent.getCurrentVersionId()));
        if (NORMAL.equals(request.getStatus()) && agent.getCurrentVersionId() == null)
            throw new ServiceException("请先发布并激活 Agent 版本");
        agent.setStatus(request.getStatus());
        agent.setUpdateBy(actor);
        agent.setUpdateTime(new Date());
        agents.updateById(agent);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id, String actor) {
        AidRuntimeAgent agent = requireAgentForUpdate(id);
        long groups = groupMembers.selectCount(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getMemberType, "AGENT")
                .eq(AidBusinessGroupMember::getMemberId, id)
                .eq(AidBusinessGroupMember::getDelFlag, NORMAL));
        long active = runs.selectCount(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getMemberType, "AGENT")
                .eq(AidRuntimeRun::getMemberId, id)
                .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                .eq(AidRuntimeRun::getDelFlag, NORMAL));
        if (groups > 0 || active > 0) {
            List<DependencyReference> refs = new ArrayList<>();
            for (AidBusinessGroupMember member : groupMembers.selectList(
                    Wrappers.<AidBusinessGroupMember>lambdaQuery()
                            .eq(AidBusinessGroupMember::getMemberType, "AGENT")
                            .eq(AidBusinessGroupMember::getMemberId, id)
                            .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                            .orderByAsc(AidBusinessGroupMember::getId)
                            .last("limit 100 LOCK IN SHARE MODE"))) {
                AidBusinessGroup group = groupMapper.selectById(member.getGroupId());
                refs.add(RuntimeReferenceErrors.reference("BUSINESS_GROUP", member.getGroupId(),
                        group == null ? null : group.getGroupCode(), group == null ? null : group.getName(),
                        null, "GROUP_MEMBER", "aid/businessGroup/index"));
            }
            if (refs.size() < 100) {
                for (AidRuntimeRun run : runs.selectList(Wrappers.<AidRuntimeRun>lambdaQuery()
                        .select(AidRuntimeRun::getId, AidRuntimeRun::getVersionId)
                        .eq(AidRuntimeRun::getMemberType, "AGENT")
                        .eq(AidRuntimeRun::getMemberId, id)
                        .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                        .eq(AidRuntimeRun::getDelFlag, NORMAL)
                        .orderByAsc(AidRuntimeRun::getId)
                        .last("limit " + (100 - refs.size()) + " LOCK IN SHARE MODE")))
                    refs.add(RuntimeReferenceErrors.reference("ACTIVE_RUN", run.getId(),
                            agent.getAgentCode(), agent.getName(), String.valueOf(run.getVersionId()),
                            "EXECUTION", "aid/runtimeAgent/index"));
            }
            RuntimeReferenceErrors.reject(refs, groups + active);
        }
        agent.setStatus(DISABLED);
        agent.setDelFlag("1");
        agent.setUpdateBy(actor);
        agent.setUpdateTime(new Date());
        agents.updateById(agent);
    }

    public Page<AidRuntimeAgentVersion> versions(LegacyRuntimeAgentRequests.VersionPage request) {
        requireAgent(request.getAgentId());
        return versions.selectPage(new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                        .select(AidRuntimeAgentVersion::getId, AidRuntimeAgentVersion::getAgentId,
                                AidRuntimeAgentVersion::getVersionCode,
                                AidRuntimeAgentVersion::getPublishStatus,
                                AidRuntimeAgentVersion::getPackageDigest,
                                AidRuntimeAgentVersion::getStatus,
                                AidRuntimeAgentVersion::getCreateBy,
                                AidRuntimeAgentVersion::getCreateTime)
                        .eq(AidRuntimeAgentVersion::getAgentId, request.getAgentId())
                        .eq(AidRuntimeAgentVersion::getDelFlag, NORMAL)
                        .orderByDesc(AidRuntimeAgentVersion::getId));
    }

    public AidRuntimeAgentVersion version(Long id) { return requireVersion(id); }

    public JSONObject draft(Long agentId, Long baseVersionId, Long actorId) {
        AidRuntimeAgent agent = requireAgent(agentId);
        AidRuntimeAgentDraft editing = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getAgentId, agentId)
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                .eq(AidRuntimeAgentDraft::getStatus, "EDITING").last("limit 1"));
        if (editing != null) return draftView(agent, editing, JSON.parseObject(editing.getDraftJson()));
        AidRuntimeAgentVersion source = baseVersionId == null
                ? (agent.getCurrentVersionId() == null ? null : requireVersion(agent.getCurrentVersionId()))
                : requireVersion(baseVersionId);
        if (source != null && !Objects.equals(source.getAgentId(), agentId))
            throw new ServiceException("Agent 基础版本归属错误");
        LegacyRuntimeAgentRequests.DraftPayload payload = source == null
                ? new LegacyRuntimeAgentRequests.DraftPayload() : definition(source);
        payload.setAgentId(agentId);
        payload.setBaseVersionId(source == null ? null : source.getId());
        JSONObject result = JSON.parseObject(JSON.toJSONString(payload));
        return draftView(agent, null, result);
    }

    @Transactional(rollbackFor = Exception.class)
    public JSONObject saveDraft(LegacyRuntimeAgentRequests.DraftPayload payload, Long draftId,
                                String expectedDigest, Long actorId, String actor) {
        AidRuntimeAgent agent = requireAgent(payload.getAgentId());
        if (payload.getBaseVersionId() != null) {
            AidRuntimeAgentVersion base = requireVersion(payload.getBaseVersionId());
            if (!Objects.equals(base.getAgentId(), agent.getId()))
                throw new ServiceException("Agent 基础版本归属错误");
        }
        LegacyRuntimeAgentRequests.DraftPayload editable = JSON.parseObject(JSON.toJSONString(payload),
                LegacyRuntimeAgentRequests.DraftPayload.class);
        JSONObject body = JSON.parseObject(JSON.toJSONString(editable));
        String canonical = body.toJSONString();
        String digest = SecureUtil.sha256(canonical);
        AidRuntimeAgentDraft draft;
        if (draftId == null) {
            draft = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                    .eq(AidRuntimeAgentDraft::getAgentId, agent.getId())
                    .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                    .eq(AidRuntimeAgentDraft::getStatus, "EDITING").last("limit 1 FOR UPDATE"));
            if (draft != null && !Objects.equals(draft.getDraftDigest(), digest))
                throw new ServiceException("已有活动草稿，请带 draftId 和 draftDigest 更新");
            if (draft == null) {
                draft = new AidRuntimeAgentDraft();
                draft.setAgentId(agent.getId());
                draft.setOwnerUserId(actorId);
                draft.setActiveKey(actorId + ":" + agent.getId());
                draft.setBaseVersionId(payload.getBaseVersionId());
                draft.setDraftJson(canonical);
                draft.setDraftDigest(digest);
                draft.setStatus("EDITING");
                draft.setCreateBy(actor);
                draft.setCreateTime(new Date());
                draft.setUpdateBy(actor);
                draft.setUpdateTime(new Date());
                try { drafts.insert(draft); }
                catch (DuplicateKeyException error) { throw new ServiceException("已有活动草稿"); }
            }
        } else {
            draft = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                    .eq(AidRuntimeAgentDraft::getId, draftId)
                    .eq(AidRuntimeAgentDraft::getAgentId, agent.getId())
                    .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                    .eq(AidRuntimeAgentDraft::getStatus, "EDITING").last("limit 1 FOR UPDATE"));
            if (draft == null) throw new ServiceException("Agent 草稿不存在");
            if (!Objects.equals(draft.getDraftDigest(), digest)
                    && !Objects.equals(draft.getDraftDigest(), expectedDigest))
                throw new ServiceException("Agent 草稿已变化");
            draft.setBaseVersionId(payload.getBaseVersionId());
            draft.setDraftJson(canonical);
            draft.setDraftDigest(digest);
            draft.setUpdateBy(actor);
            draft.setUpdateTime(new Date());
            drafts.updateById(draft);
        }
        return draftView(agent, draft, body);
    }

    public JSONObject validate(LegacyRuntimeAgentRequests.DraftPayload payload) {
        requireAgent(payload == null ? null : payload.getAgentId());
        JSONObject result = new JSONObject();
        List<Map<String, String>> errors = new ArrayList<>();
        if (!isLegacyExecutor(payload.getExecutorType()))
            errors.add(Map.of("field", "executorType", "message", "Legacy executor type is invalid"));
        if ("SKILL_ORCHESTRATOR".equals(payload.getExecutorType())) {
            if (!"TEXT".equals(payload.getOutputKind()))
                errors.add(Map.of("field", "outputKind", "message", "委托编排 Skill 仅支持文本输出"));
            if (StrUtil.isBlank(payload.getDelegatedSkillCode())) {
                errors.add(Map.of("field", "delegatedSkillCode", "message", "请选择编排 Skill"));
            } else {
                AidSkill delegated = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                        .eq(AidSkill::getSkillCode, payload.getDelegatedSkillCode())
                        .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL)
                        .last("limit 1"));
                AidSkillVersion selected = delegated == null || delegated.getCurrentVersionId() == null ? null
                        : skillVersions.selectById(delegated.getCurrentVersionId());
                if (selected == null || !"ORCHESTRATOR".equals(selected.getExecutorType())
                        || !NORMAL.equals(selected.getStatus()) || !NORMAL.equals(selected.getDelFlag()))
                    errors.add(Map.of("field", "delegatedSkillCode", "message", "编排 Skill 未激活"));
                else if (!"PUBLIC".equals(delegated.getVisibility()))
                    errors.add(Map.of("field", "delegatedSkillCode", "message", "委托编排 Skill 须为 PUBLIC"));
                else if (!SkillModelConfiguration.from(selected).selectableModelCodes()
                        .containsAll(payload.getSelectableModelCodes()))
                    errors.add(Map.of("field", "selectableModelCodes",
                            "message", "委托模型须属于编排 Skill 的模型池"));
            }
            if (!payload.getSteps().isEmpty() || !payload.getAllowedSkillCodes().isEmpty()
                    || !payload.getAllowedToolCodes().isEmpty())
                errors.add(Map.of("field", "steps", "message", "委托编排由固定 Skill 版本执行，不配置通用步骤"));
        }
        Set<String> codes = new LinkedHashSet<>(payload.getSelectableModelCodes());
        if (codes.size() != payload.getSelectableModelCodes().size()
                || !codes.contains(payload.getDefaultModelCode()))
            errors.add(Map.of("field", "defaultModelCode", "message", "默认模型须属于无重复的可选文本模型"));
        if (modelService.activeModels(codes, payload.getOutputKind()).size() != codes.size())
            errors.add(Map.of("field", "selectableModelCodes", "message", "存在停用或非文本模型"));
        if (payload.getSteps().size() > payload.getMaxSteps())
            errors.add(Map.of("field", "steps", "message", "步骤数量超过预算"));
        long modelCalls = payload.getSteps().stream().filter(step -> !"TOOL".equals(step.getKind())).count();
        if (modelCalls > payload.getMaxTaskCount())
            errors.add(Map.of("field", "maxTaskCount", "message", "模型步骤超过任务预算"));
        Set<String> uniqueStepKeys = new LinkedHashSet<>();
        for (int i = 0; i < payload.getSteps().size(); i++) {
            LegacyRuntimeAgentRequests.Step step = payload.getSteps().get(i);
            String field = "steps[" + i + "]";
            if (!uniqueStepKeys.add(step.getStepKey()))
                errors.add(Map.of("field", field + ".stepKey", "message", "步骤编码重复"));
            if ("TOOL".equals(step.getKind()) && (!payload.getAllowedToolCodes().contains(step.getToolCode())
                    || !tools.supports(step.getToolCode())))
                errors.add(Map.of("field", field + ".toolCode", "message", "Tool 未授权或未注册"));
            if ("SKILL".equals(step.getKind()) && !payload.getAllowedSkillCodes().contains(step.getSkillCode()))
                errors.add(Map.of("field", field + ".skillCode", "message", "Skill 未授权"));
            if ("MODEL".equals(step.getKind())
                    && (StrUtil.isNotBlank(step.getToolCode()) || StrUtil.isNotBlank(step.getSkillCode())))
                errors.add(Map.of("field", field, "message", "模型步骤不能绑定 Tool 或 Skill"));
        }
        for (String code : payload.getAllowedToolCodes()) {
            if (!tools.supports(code)) errors.add(Map.of("field", "allowedToolCodes", "message", "Tool 未注册"));
        }
        if (!payload.getAllowedSkillCodes().isEmpty()) {
            List<AidSkill> found = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                    .select(AidSkill::getSkillCode, AidSkill::getStatus, AidSkill::getDelFlag,
                            AidSkill::getCurrentVersionId)
                    .in(AidSkill::getSkillCode, payload.getAllowedSkillCodes())
                    .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL)
                    .isNotNull(AidSkill::getCurrentVersionId));
            if (found.size() != new LinkedHashSet<>(payload.getAllowedSkillCodes()).size())
                errors.add(Map.of("field", "allowedSkillCodes", "message", "存在未发布或停用 Skill"));
        }
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("draftDigest", SecureUtil.sha256(JSON.toJSONString(payload)));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public AidRuntimeAgentVersion publish(LegacyRuntimeAgentRequests.Publish request, Long actorId, String actor) {
        AidRuntimeAgentDraft draft = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getId, request.getDraftId())
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId).last("FOR UPDATE"));
        if (draft == null) throw new ServiceException("Agent 草稿不存在");
        if ("PUBLISHED".equals(draft.getStatus())) {
            JSONObject receipt = JSON.parseObject(draft.getDraftJson());
            if (Objects.equals(receipt.getString("sourceDraftDigest"), request.getDraftDigest())
                    && Objects.equals(receipt.getString("versionCode"), request.getVersionCode()))
                return requireVersion(receipt.getLong("publishedVersionId"));
            throw new ServiceException("Agent 发布参数已变化");
        }
        if (!"EDITING".equals(draft.getStatus())
                || !Objects.equals(draft.getDraftDigest(), request.getDraftDigest()))
            throw new ServiceException("Agent 草稿已变化");
        AidRuntimeAgent agent = requireAgentForUpdate(draft.getAgentId());
        LegacyRuntimeAgentRequests.DraftPayload payload = JSON.parseObject(draft.getDraftJson(),
                LegacyRuntimeAgentRequests.DraftPayload.class);
        if (!Boolean.TRUE.equals(validate(payload).getBoolean("valid")))
            throw new ServiceException("Agent 草稿校验失败");
        List<Long> modelIds = modelService.activeModels(payload.getSelectableModelCodes(), payload.getOutputKind())
                .values().stream().map(AidAiModel::getId).toList();
        modelReferenceGuard.lockModels(modelIds);
        if (modelService.activeModels(payload.getSelectableModelCodes(), payload.getOutputKind()).size()
                != payload.getSelectableModelCodes().size())
            throw new ServiceException("Agent 模型已变化，请重新校验");
        Set<String> pinnedCodes = new LinkedHashSet<>(payload.getAllowedSkillCodes());
        if ("SKILL_ORCHESTRATOR".equals(payload.getExecutorType()))
            pinnedCodes.add(payload.getDelegatedSkillCode());
        List<AidSkill> children = pinnedCodes.isEmpty() ? List.of()
                : skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .in(AidSkill::getSkillCode, pinnedCodes)
                        .eq(AidSkill::getDelFlag, NORMAL)
                        .orderByAsc(AidSkill::getId).last("FOR UPDATE"));
        if (children.size() != pinnedCodes.size()
                || children.stream().anyMatch(child -> !NORMAL.equals(child.getStatus())
                || child.getCurrentVersionId() == null
                || !("PUBLIC".equals(child.getVisibility())
                || Objects.equals(child.getOwnerUserId(), actorId))))
            throw new ServiceException("Agent 依赖的 Skill 已变化");
        if ("SKILL_ORCHESTRATOR".equals(payload.getExecutorType())) {
            AidSkill delegated = children.stream().filter(child -> Objects.equals(
                    child.getSkillCode(), payload.getDelegatedSkillCode())).findFirst().orElse(null);
            AidSkillVersion pinned = delegated == null ? null : skillVersions.selectById(delegated.getCurrentVersionId());
            if (pinned == null || !"ORCHESTRATOR".equals(pinned.getExecutorType())
                    || !NORMAL.equals(pinned.getStatus()) || !NORMAL.equals(pinned.getDelFlag()))
                throw new ServiceException("编排 Skill 已变化，请重新校验", 409);
        }
        if (versions.selectCount(Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                .eq(AidRuntimeAgentVersion::getAgentId, agent.getId())
                .eq(AidRuntimeAgentVersion::getVersionCode, request.getVersionCode())) > 0)
            throw new ServiceException("Agent 版本号已存在");
        AidRuntimeAgentVersion version = new AidRuntimeAgentVersion();
        version.setAgentId(agent.getId());
        version.setVersionCode(request.getVersionCode());
        version.setPublishStatus("PRIVATE");
        version.setInstruction(payload.getInstruction());
        version.setModelConfigJson(JSON.toJSONString(Map.of("defaultModelCode", payload.getDefaultModelCode(),
                "selectableModelCodes", payload.getSelectableModelCodes())));
        version.setInputSchemaJson("{}");
        version.setOutputSchemaJson("{}");
        version.setDefinitionJson(JSON.toJSONString(payload));
        version.setPackageDigest(SecureUtil.sha256(agent.getAgentCode() + "|" + request.getVersionCode()
                + "|" + version.getDefinitionJson()));
        version.setStatus(NORMAL);
        version.setDelFlag(NORMAL);
        version.setCreateBy(actor);
        version.setCreateTime(new Date());
        version.setUpdateBy(actor);
        version.setUpdateTime(new Date());
        try { versions.insert(version); }
        catch (DuplicateKeyException error) { throw new ServiceException("Agent 版本号或摘要已存在"); }
        for (AidSkill child : children) {
            AidSkillVersion pinned = skillVersions.selectById(child.getCurrentVersionId());
            if (pinned == null || !NORMAL.equals(pinned.getStatus()) || !NORMAL.equals(pinned.getDelFlag())
                    || StrUtil.isBlank(pinned.getPackageDigest()))
                throw new ServiceException("SKILL_VERSION_DIGEST_MISSING", 409);
            AidRuntimeAgentSkillRef ref = new AidRuntimeAgentSkillRef();
            ref.setAgentVersionId(version.getId());
            ref.setSkillId(child.getId());
            ref.setSkillVersionId(pinned.getId());
            ref.setSkillCode(child.getSkillCode());
            ref.setPackageDigest(pinned.getPackageDigest());
            skillRefs.insert(ref);
        }
        draft.setStatus("PUBLISHED");
        draft.setActiveKey(null);
        draft.setDraftJson(JSON.toJSONString(Map.of("publishedVersionId", version.getId(),
                "versionCode", version.getVersionCode(), "sourceDraftDigest", request.getDraftDigest())));
        draft.setDraftDigest(SecureUtil.sha256(draft.getDraftJson()));
        draft.setUpdateBy(actor);
        draft.setUpdateTime(new Date());
        drafts.updateById(draft);
        return version;
    }

    @Transactional(rollbackFor = Exception.class)
    public void activate(LegacyRuntimeAgentRequests.Activate request, String actor) {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getAgentId());
        AidRuntimeAgentVersion version = requireVersion(request.getVersionId());
        if (!Objects.equals(version.getAgentId(), agent.getId()))
            throw new ServiceException("Agent 版本归属错误");
        if (Objects.equals(agent.getCurrentVersionId(), version.getId())) return;
        if (!Objects.equals(agent.getCurrentVersionId(), request.getExpectedCurrentVersionId()))
            throw new ServiceException("Agent 当前版本已变化");
        if (!NORMAL.equals(version.getStatus())) throw new ServiceException("Agent 版本不可用");
        LegacyRuntimeAgentRequests.DraftPayload definition = definition(version);
        List<Long> modelIds = modelService.activeModels(definition.getSelectableModelCodes(), definition.getOutputKind())
                .values().stream().map(AidAiModel::getId).toList();
        modelReferenceGuard.lockModels(modelIds);
        if (modelService.activeModels(definition.getSelectableModelCodes(), definition.getOutputKind()).size()
                != definition.getSelectableModelCodes().size())
            throw new ServiceException("Agent 模型已变化");
        agent.setCurrentVersionId(version.getId());
        agent.setUpdateBy(actor);
        agent.setUpdateTime(new Date());
        agents.updateById(agent);
    }

    public Page<AidRuntimeRun> runs(LegacyRuntimeAgentRequests.RunPage request) {
        LegacyRuntimeAgentRequests.RunPage source = request == null ? new LegacyRuntimeAgentRequests.RunPage() : request;
        return runs.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidRuntimeRun>lambdaQuery()
                        .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId,
                                AidRuntimeRun::getMemberId, AidRuntimeRun::getVersionId,
                                AidRuntimeRun::getModelCode, AidRuntimeRun::getOutputKind,
                                AidRuntimeRun::getMediaTaskId, AidRuntimeRun::getStatus,
                                AidRuntimeRun::getErrorMessage, AidRuntimeRun::getCreateTime)
                        .eq(AidRuntimeRun::getMemberType, "AGENT")
                        .isNull(AidRuntimeRun::getSessionId)
                        .isNotNull(AidRuntimeRun::getMemberId)
                        .isNotNull(AidRuntimeRun::getVersionId)
                        .apply("EXISTS (SELECT 1 FROM aid_runtime_agent a "
                                + "WHERE a.id=aid_runtime_run.member_id "
                                + "AND a.agent_family='LEGACY_PLAN' AND a.del_flag='0')")
                        .eq(source.getAgentId() != null, AidRuntimeRun::getMemberId, source.getAgentId())
                        .eq(source.getUserId() != null, AidRuntimeRun::getUserId, source.getUserId())
                        .eq(source.getStatus() != null, AidRuntimeRun::getStatus, source.getStatus())
                        .eq(AidRuntimeRun::getDelFlag, NORMAL).orderByDesc(AidRuntimeRun::getId));
    }

    public AidRuntimeAgent requireAgent(Long id) {
        if (id == null || id <= 0) throw new ServiceException("Agent ID 无效");
        AidRuntimeAgent result = agents.selectById(id);
        if (result == null || !NORMAL.equals(result.getDelFlag())) throw new ServiceException("Agent 不存在");
        requireLegacyFamily(result);
        return result;
    }

    public AidRuntimeAgentVersion requireVersion(Long id) {
        if (id == null || id <= 0) throw new ServiceException("Agent 版本 ID 无效");
        AidRuntimeAgentVersion result = versions.selectById(id);
        if (result == null || !NORMAL.equals(result.getDelFlag())) throw new ServiceException("Agent 版本不存在");
        requireAgent(result.getAgentId());
        return result;
    }

    public LegacyRuntimeAgentRequests.DraftPayload definition(AidRuntimeAgentVersion version) {
        JSONObject raw;
        try { raw = JSON.parseObject(version.getDefinitionJson()); }
        catch (RuntimeException error) { throw new ServiceException("Agent 版本定义损坏"); }
        String executorType = raw == null ? null : raw.getString("executorType");
        String protocolVersion = raw == null ? null : raw.getString("protocolVersion");
        if (!isLegacyExecutor(executorType)
                || StrUtil.isNotBlank(protocolVersion))
            throw new ServiceException("AGENT_FAMILY_DEFINITION_MISMATCH", 409);
        return raw.toJavaObject(LegacyRuntimeAgentRequests.DraftPayload.class);
    }

    private static boolean isLegacyExecutor(String executorType) {
        return "MODEL_PLAN".equals(executorType) || "SKILL_ORCHESTRATOR".equals(executorType);
    }

    private AidRuntimeAgent requireAgentForUpdate(Long id) {
        AidRuntimeAgent result = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .eq(AidRuntimeAgent::getId, id)
                .eq(AidRuntimeAgent::getAgentFamily, LEGACY_FAMILY)
                .eq(AidRuntimeAgent::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (result == null) throw new ServiceException("Agent 不存在");
        return result;
    }

    private void requireLegacyFamily(AidRuntimeAgent agent) {
        if (agent == null || !LEGACY_FAMILY.equals(agent.getAgentFamily()))
            throw new ServiceException("AGENT_FAMILY_MISMATCH", 409);
    }

    private void requireAvailableCode(String agentCode) {
        AidRuntimeAgent existing = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentFamily)
                .eq(AidRuntimeAgent::getAgentCode, StrUtil.trim(agentCode)).last("limit 1"));
        if (existing == null) return;
        if (!LEGACY_FAMILY.equals(existing.getAgentFamily()))
            throw new ServiceException("AGENT_CODE_FAMILY_CONFLICT", 409);
        throw new ServiceException("Agent code already exists", 409);
    }

    private static JSONObject draftView(AidRuntimeAgent agent, AidRuntimeAgentDraft draft, JSONObject body) {
        JSONObject result = new JSONObject(body);
        result.put("agentCode", agent.getAgentCode());
        result.put("draftId", draft == null ? null : draft.getId());
        result.put("draftDigest", draft == null ? SecureUtil.sha256(body.toJSONString()) : draft.getDraftDigest());
        result.put("updateTime", draft == null ? null : draft.getUpdateTime());
        return result;
    }
}
