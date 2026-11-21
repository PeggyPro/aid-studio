package com.aid.runtime.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.AssistantAgentModelReadinessService;
import com.aid.runtime.assistant.AssistantLoopExecutorRegistry;
import com.aid.runtime.assistant.NativeAssistantBindingPolicy;
import com.aid.runtime.assistant.SpatialPrevisGuidancePolicy;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentDraft;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.domain.AidRuntimeModuleAgentBinding;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.runtime.dto.RuntimeAgentViews;
import com.aid.runtime.mapper.AidRuntimeAgentDraftMapper;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.runtime.mapper.AidRuntimeModuleAgentBindingMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillModelConfiguration;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.aid.skill.service.PackageDocumentReferences;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.aid.skill.vo.SkillAdminVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stable identity, CAS drafts and immutable Assistant Loop Agent versions. */
@Service
@RequiredArgsConstructor
public class RuntimeAgentAdminService
{
    private static final String NORMAL = "0";
    private static final String DELETED = "1";
    private static final String DISABLED = "1";
    private static final String EDITING = "EDITING";
    private static final String FROZEN = "FROZEN";
    private static final String ACTIVE = "ACTIVE";
    private static final String ASSISTANT_FAMILY = RuntimeAgentRequests.ASSISTANT_LOOP;
    private static final String TEXT_CHAT = "text.chat";
    private static final String TEXT_OUTPUT_KIND = "TEXT";
    private static final String UNIVERSAL = "UNIVERSAL";
    private static final String SCREENWRITER = "SCREENWRITER";
    private static final String SPATIAL_PREVIS = "SPATIAL_PREVIS";
    private static final String UNIVERSAL_AGENT = "universal_canvas_agent";
    private static final String SCREENWRITER_AGENT = "story_script_agent";
    private static final String SPATIAL_PREVIS_AGENT = "spatial_previs_agent";
    private static final Map<String, BuiltinDefinition> BUILTINS = Map.of(
            UNIVERSAL_AGENT, new BuiltinDefinition(UNIVERSAL_AGENT, UNIVERSAL,
                    "agents/universal_canvas_agent/manifest.json"),
            SCREENWRITER_AGENT, new BuiltinDefinition(SCREENWRITER_AGENT, SCREENWRITER,
                    "agents/story_script_agent/manifest.json"),
            SPATIAL_PREVIS_AGENT, new BuiltinDefinition(SPATIAL_PREVIS_AGENT, SPATIAL_PREVIS,
                    "agents/spatial_previs_agent/manifest.json"));
    private static final List<String> MODULE_CODES = List.of(UNIVERSAL, SCREENWRITER, SPATIAL_PREVIS);
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleRegistry;
    private List<String> moduleCodes() { return moduleRegistry == null ? MODULE_CODES : moduleRegistry.moduleCodes(); }
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "STOPPED", "CANCELLED", "CANCELED");
    private static final Set<String> TERMINAL_STEP_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "FAILED_PRETASK", "CANCELLED", "CANCELLED_PRETASK", "CANCELED");
    private final AidRuntimeAgentMapper agents;
    private final AidRuntimeAgentDraftMapper drafts;
    private final AidRuntimeAgentVersionMapper versions;
    private final AidRuntimeModuleAgentBindingMapper moduleBindingMapper;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper runSteps;
    private final AidRuntimeAgentSkillRefMapper skillRefs;
    private final AidRuntimeAgentToolRefMapper toolRefs;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper skillVersions;
    private final AidSkillRelationMapper skillRelations;
    private final SkillModelService modelService;
    private final SkillPackageResourceLoader packageResourceLoader;
    private final AssistantAgentModelReadinessService modelReadiness;
    private final SkillRuntimeEntrypointReadinessService skillReadiness;
    private final RuntimeToolRegistry tools;
    private final AssistantLoopExecutorRegistry loopExecutors;
    private final RuntimeAgentAdminAudit audit;
    private final RuntimeAgentVersionLoader versionLoader;
    private final Validator validator;

    public List<SkillAdminVO.TextModelOption> modelOptions()
    {
        return modelService.adminOptionsForOutputKind(TEXT_OUTPUT_KIND);
    }

    public List<RuntimeAgentViews.SkillOption> skillOptions()
    {
        List<AidSkill> identities = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getOwnerType, "PLATFORM")
                .eq(AidSkill::getVisibility, "PUBLIC")
                .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL)
                .orderByAsc(AidSkill::getId));
        if (identities.isEmpty()) return List.of();
        Map<Long, AidSkill> identityMap = identities.stream().collect(Collectors.toMap(
                AidSkill::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        return skillVersions.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .in(AidSkillVersion::getSkillId, identityMap.keySet())
                        .eq(AidSkillVersion::getVisibility, "PUBLIC")
                        .eq(AidSkillVersion::getPublishStatus, "PUBLISHED")
                        .eq(AidSkillVersion::getStatus, NORMAL).eq(AidSkillVersion::getDelFlag, NORMAL)
                        .orderByAsc(AidSkillVersion::getSkillId).orderByDesc(AidSkillVersion::getId))
                .stream().filter(version -> publicAgentSkillBindingReady(
                        identityMap.get(version.getSkillId()), version, version.getPackageDigest()))
                .map(version -> {
                    AidSkill skill = identityMap.get(version.getSkillId());
                    RuntimeAgentViews.SkillOption item = new RuntimeAgentViews.SkillOption();
                    item.setSkillId(skill.getId()); item.setSkillVersionId(version.getId());
                    item.setSkillCode(skill.getSkillCode()); item.setName(skill.getName());
                    item.setVersionCode(version.getVersionCode()); item.setPackageDigest(version.getPackageDigest());
                    item.setInvocationScope(version.getInvocationScope());
                    return item;
                }).toList();
    }

    public List<RuntimeToolRegistry.Descriptor> toolOptions()
    {
        return tools.options();
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.BuiltinImportView importBuiltin(RuntimeAgentRequests.BuiltinImport request,
                                                              Long actorId, String actor)
    {
        BuiltinDefinition definition = BUILTINS.get(request.getAgentCode());
        if (definition == null) throw new ServiceException("内置Agent不存在");
        String builtinPackageVersionCode = builtinPackageVersionCode(definition);
        boolean replaceExistingDraft = Boolean.TRUE.equals(request.getReplaceExistingDraft());
        if (!replaceExistingDraft && (request.getExpectedDraftId() != null
                || StrUtil.isNotBlank(request.getExpectedDraftDigest())))
            throw new ServiceException("草稿更新参数无效");
        if (replaceExistingDraft && (request.getExpectedDraftId() == null
                || StrUtil.isBlank(request.getExpectedDraftDigest())))
            throw new ServiceException("请先刷新并确认当前草稿", 409);
        AidRuntimeAgent existing = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .eq(AidRuntimeAgent::getAgentCode, definition.agentCode())
                .last("LIMIT 1"));
        if (existing != null)
        {
            if (!ASSISTANT_FAMILY.equals(existing.getAgentFamily()))
                throw new ServiceException("AGENT_CODE_FAMILY_CONFLICT", 409);
            if (!NORMAL.equals(existing.getDelFlag()))
                throw new ServiceException("Agent code already exists", 409);
            if (replaceExistingDraft)
            {
                existing = requireAgentForUpdate(existing.getId());
                if (!Objects.equals(existing.getAgentCode(), definition.agentCode()))
                    throw new ServiceException("内置Agent身份已变更，请刷新后重试", 409);
            }
            AidRuntimeAgentDraft editing = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                    .eq(AidRuntimeAgentDraft::getAgentId, existing.getId())
                    .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                    .eq(AidRuntimeAgentDraft::getStatus, EDITING)
                    .last(replaceExistingDraft ? "FOR UPDATE" : "LIMIT 1"));
            if (editing != null)
            {
                if (!replaceExistingDraft)
                    return builtinImportView(existing, editing, "EXISTING_DRAFT", "已有编辑中草稿",
                            builtinPackageVersionCode);
                return replaceBuiltinDraft(definition, existing, editing, request, actor,
                        builtinPackageVersionCode);
            }
            if (replaceExistingDraft)
                throw new ServiceException("草稿已变更，请刷新后重试", 409);
            BuiltinDraft builtin = builtinDraft(definition);
            RuntimeAgentRequests.DraftPayload body = builtin.payload();
            body.setAgentId(existing.getId());
            body.setBaseVersionId(existing.getCurrentVersionId());
            if (existing.getCurrentVersionId() != null)
            {
                RuntimeAgentRequests.DraftPayload current = rawDefinition(
                        requireOwnedVersion(existing.getId(), existing.getCurrentVersionId()));
                body.setModelCapabilityCode(current.getModelCapabilityCode());
                body.setDefaultModelCode(current.getDefaultModelCode());
                body.setSelectableModelCodes(new ArrayList<>(safe(current.getSelectableModelCodes())));
                body.setTemperatureBasisPoints(current.getTemperatureBasisPoints());
            }
            RuntimeAgentViews.ValidationView validation = validate(body);
            if (!validation.isValid()) throw new ServiceException(validation.getErrors().get(0).message());
            String draftJson = canonicalDraft(body);
            AidRuntimeAgentDraft draft = new AidRuntimeAgentDraft();
            draft.setAgentId(existing.getId()); draft.setOwnerUserId(actorId);
            draft.setActiveKey(activeKey(existing.getId(), actorId));
            draft.setBaseVersionId(existing.getCurrentVersionId());
            draft.setDraftJson(draftJson); draft.setDraftDigest(SecureUtil.sha256(draftJson));
            draft.setStatus(EDITING); draft.setCreateBy(actor); draft.setCreateTime(new Date());
            draft.setUpdateBy(actor); draft.setUpdateTime(new Date()); drafts.insert(draft);
            audit.record("builtin/refresh", existing.getId(), existing.getCurrentVersionId(), actor);
            return builtinImportView(existing, draft, "REFRESHED_DRAFT", "已从正式内置包创建升级草稿",
                    builtinPackageVersionCode);
        }
        if (replaceExistingDraft)
            throw new ServiceException("内置Agent身份已变更，请刷新后重试", 409);

        BuiltinDraft builtin = builtinDraft(definition);
        AidRuntimeAgent agent = new AidRuntimeAgent();
        agent.setAgentCode(definition.agentCode());
        agent.setName(StrUtil.blankToDefault(builtin.name(), definition.agentCode()));
        agent.setDescription("内置主Agent草稿，发布与启用均需管理员显式操作");
        agent.setOwnerType("PLATFORM"); agent.setVisibility("PRIVATE");
        agent.setAgentFamily(ASSISTANT_FAMILY);
        agent.setStatus(DISABLED); agent.setDelFlag(NORMAL); touch(agent, actor, true);
        try
        {
            agents.insert(agent);
        }
        catch (DuplicateKeyException conflict)
        {
            AidRuntimeAgent raced = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                    .eq(AidRuntimeAgent::getAgentCode, definition.agentCode())
                    .last("LIMIT 1"));
            if (raced != null)
            {
                if (!ASSISTANT_FAMILY.equals(raced.getAgentFamily()))
                    throw new ServiceException("AGENT_CODE_FAMILY_CONFLICT", 409);
                if (!NORMAL.equals(raced.getDelFlag()))
                    throw new ServiceException("Agent code already exists", 409);
                return builtinImportView(raced, actorId, "EXISTS", "Agent已存在",
                        builtinPackageVersionCode);
            }
            throw new ServiceException("Agent导入冲突");
        }

        RuntimeAgentRequests.DraftPayload body = builtin.payload();
        body.setAgentId(agent.getId());
        ConversationAgentDefaults.apply(body, supportsContextPreparation(body));
        RuntimeAgentViews.ValidationView validation = validate(body);
        if (!validation.isValid()) throw new ServiceException(validation.getErrors().get(0).message());
        String draftJson = canonicalDraft(body);
        AidRuntimeAgentDraft draft = new AidRuntimeAgentDraft();
        draft.setAgentId(agent.getId()); draft.setOwnerUserId(actorId);
        draft.setActiveKey(activeKey(agent.getId(), actorId)); draft.setBaseVersionId(null);
        draft.setDraftJson(draftJson); draft.setDraftDigest(SecureUtil.sha256(draftJson));
        draft.setStatus(EDITING); draft.setCreateBy(actor); draft.setCreateTime(new Date());
        draft.setUpdateBy(actor); draft.setUpdateTime(new Date()); drafts.insert(draft);
        audit.record("builtin/import", agent.getId(), null, actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return builtinImportView(agent, draft, "CREATED", "已导入草稿", builtinPackageVersionCode);
    }

    private RuntimeAgentViews.BuiltinImportView replaceBuiltinDraft(
            BuiltinDefinition definition, AidRuntimeAgent agent, AidRuntimeAgentDraft editing,
            RuntimeAgentRequests.BuiltinImport request, String actor, String builtinPackageVersionCode)
    {
        if (!Objects.equals(editing.getId(), request.getExpectedDraftId())
                || !Objects.equals(editing.getDraftDigest(), request.getExpectedDraftDigest()))
            throw new ServiceException("草稿已变更，请刷新后重试", 409);

        RuntimeAgentRequests.DraftPayload previous = parseDraft(editing.getDraftJson());
        RuntimeAgentRequests.DraftPayload body = builtinDraft(definition).payload();
        body.setAgentId(agent.getId());
        body.setBaseVersionId(agent.getCurrentVersionId());
        body.setModelCapabilityCode(previous.getModelCapabilityCode());
        body.setDefaultModelCode(previous.getDefaultModelCode());
        body.setSelectableModelCodes(new ArrayList<>(safe(previous.getSelectableModelCodes())));
        body.setTemperatureBasisPoints(previous.getTemperatureBasisPoints());
        RuntimeAgentViews.ValidationView validation = validate(body);
        if (!validation.isValid()) throw new ServiceException(validation.getErrors().get(0).message());

        String draftJson = canonicalDraft(body);
        String draftDigest = SecureUtil.sha256(draftJson);
        Date updateTime = new Date();
        int changed = drafts.update(null, Wrappers.<AidRuntimeAgentDraft>lambdaUpdate()
                .eq(AidRuntimeAgentDraft::getId, editing.getId())
                .eq(AidRuntimeAgentDraft::getAgentId, agent.getId())
                .eq(AidRuntimeAgentDraft::getOwnerUserId, editing.getOwnerUserId())
                .eq(AidRuntimeAgentDraft::getStatus, EDITING)
                .eq(AidRuntimeAgentDraft::getDraftDigest, request.getExpectedDraftDigest())
                .set(AidRuntimeAgentDraft::getBaseVersionId, agent.getCurrentVersionId())
                .set(AidRuntimeAgentDraft::getDraftJson, draftJson)
                .set(AidRuntimeAgentDraft::getDraftDigest, draftDigest)
                .set(AidRuntimeAgentDraft::getUpdateBy, actor)
                .set(AidRuntimeAgentDraft::getUpdateTime, updateTime));
        if (changed != 1) throw new ServiceException("草稿已变更，请刷新后重试", 409);

        editing.setBaseVersionId(agent.getCurrentVersionId());
        editing.setDraftJson(draftJson);
        editing.setDraftDigest(draftDigest);
        editing.setUpdateBy(actor);
        editing.setUpdateTime(updateTime);
        audit.record("builtin/replace-draft", agent.getId(), agent.getCurrentVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return builtinImportView(agent, editing, "UPDATED_DRAFT",
                "已用当前正式内置包更新本人草稿", builtinPackageVersionCode);
    }

    public List<RuntimeAgentViews.ModuleBindingView> moduleBindings()
    {
        Map<String, AidRuntimeModuleAgentBinding> rows = moduleBindingMapper.selectList(
                        Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                                .in(AidRuntimeModuleAgentBinding::getModuleCode, moduleCodes())
                                .eq(AidRuntimeModuleAgentBinding::getStatus, ACTIVE)
                                .eq(AidRuntimeModuleAgentBinding::getDelFlag, NORMAL))
                .stream().collect(Collectors.toMap(AidRuntimeModuleAgentBinding::getModuleCode,
                        Function.identity(), (left, right) -> left));
        List<RuntimeAgentViews.ModuleBindingView> result = new ArrayList<>();
        for (String moduleCode : moduleCodes()) result.add(moduleBindingView(moduleCode, rows.get(moduleCode)));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.ModuleBindingView activateModuleBinding(
            RuntimeAgentRequests.ModuleBindingActivate request, String actor)
    {
        String expectedAgentCode = expectedAgentCode(request.getModuleCode());
        AidRuntimeAgent agent = requireAgentForUpdate(request.getAgentId());
        if (!NORMAL.equals(agent.getStatus())) throw new ServiceException("请先启用Agent");
        if (!expectedAgentCode.equals(agent.getAgentCode())) throw new ServiceException("模块Agent不匹配");
        AidRuntimeAgentVersion version = requireOwnedVersion(agent.getId(), request.getVersionId());
        requireNativeActiveVersion(version);
        if (!Objects.equals(agent.getCurrentVersionId(), version.getId()))
            throw new ServiceException("版本不是当前版本");
        if (!NORMAL.equals(version.getStatus()) || !FROZEN.equals(version.getPublishStatus()))
            throw new ServiceException("版本不可绑定");
        Availability runtime = availability(version);
        if (!runtime.available()) throw new ServiceException("运行时不可用：" + runtime.reason());

        AidRuntimeModuleAgentBinding other = moduleBindingMapper.selectOne(
                Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                        .eq(AidRuntimeModuleAgentBinding::getRuntimeAgentId, agent.getId())
                        .ne(AidRuntimeModuleAgentBinding::getModuleCode, request.getModuleCode())
                        .eq(AidRuntimeModuleAgentBinding::getStatus, ACTIVE)
                        .eq(AidRuntimeModuleAgentBinding::getDelFlag, NORMAL).last("LIMIT 1 FOR UPDATE"));
        if (other != null) throw new ServiceException("Agent已绑定其他模块");

        AidRuntimeModuleAgentBinding binding = moduleBindingMapper.selectOne(
                Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                        .eq(AidRuntimeModuleAgentBinding::getModuleCode, request.getModuleCode())
                        .last("LIMIT 1 FOR UPDATE"));
        boolean active = binding != null && ACTIVE.equals(binding.getStatus()) && NORMAL.equals(binding.getDelFlag());
        long currentRevision = active && binding.getBindingRevision() != null ? binding.getBindingRevision() : 0L;
        if (currentRevision != request.getExpectedBindingRevision())
            throw new ServiceException("模块绑定已更新");
        Date now = new Date();
        if (binding == null)
        {
            binding = new AidRuntimeModuleAgentBinding();
            binding.setModuleCode(request.getModuleCode()); binding.setCreateBy(actor); binding.setCreateTime(now);
        }
        binding.setRuntimeAgentId(agent.getId()); binding.setActiveVersionId(version.getId());
        binding.setBindingRevision(currentRevision + 1L); binding.setStatus(ACTIVE); binding.setDelFlag(NORMAL);
        binding.setUpdateBy(actor); binding.setUpdateTime(now);
        try
        {
            if (binding.getId() == null) moduleBindingMapper.insert(binding);
            else moduleBindingMapper.updateById(binding);
        }
        catch (DuplicateKeyException conflict)
        {
            throw new ServiceException("模块绑定已更新");
        }
        audit.record("module-binding/activate", agent.getId(), version.getId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return moduleBindingView(request.getModuleCode(), binding);
    }

    public Page<RuntimeAgentViews.AgentSummary> page(RuntimeAgentRequests.Page request)
    {
        RuntimeAgentRequests.Page source = request == null ? new RuntimeAgentRequests.Page() : request;
        Page<AidRuntimeAgent> page = agents.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .eq(AidRuntimeAgent::getAgentFamily, ASSISTANT_FAMILY)
                        .eq(!Boolean.TRUE.equals(source.getIncludeDeleted()),
                                AidRuntimeAgent::getDelFlag, NORMAL)
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidRuntimeAgent::getStatus, source.getStatus())
                        .and(StrUtil.isNotBlank(source.getKeyword()), q -> q
                                .like(AidRuntimeAgent::getAgentCode, source.getKeyword())
                                .or().like(AidRuntimeAgent::getName, source.getKeyword()))
                        .orderByDesc(AidRuntimeAgent::getId));
        Page<RuntimeAgentViews.AgentSummary> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, AidRuntimeAgentVersion> currentVersions = versionMap(page.getRecords().stream()
                .map(AidRuntimeAgent::getCurrentVersionId).filter(Objects::nonNull).toList());
        ListValidationContext context = listValidationContext(page.getRecords(), currentVersions);
        result.setRecords(page.getRecords().stream().map(agent -> summary(agent,
                agent.getCurrentVersionId() == null ? null
                        : currentVersions.get(agent.getCurrentVersionId()), context)).toList());
        return result;
    }

    public RuntimeAgentViews.AgentSummary detail(Long id)
    {
        AidRuntimeAgent agent = requireAgent(id);
        AidRuntimeAgentVersion version = agent.getCurrentVersionId() == null ? null
                : versions.selectById(agent.getCurrentVersionId());
        return summary(agent, version);
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.AgentSummary create(RuntimeAgentRequests.Create request, String actor)
    {
        requireAvailableCode(request.getAgentCode());
        AidRuntimeAgent agent = new AidRuntimeAgent();
        agent.setAgentCode(StrUtil.trim(request.getAgentCode())); agent.setName(StrUtil.trim(request.getName()));
        agent.setDescription(StrUtil.trim(request.getDescription())); agent.setIconUrl(StrUtil.trim(request.getIconUrl()));
        agent.setOwnerType("PLATFORM"); agent.setVisibility(request.getVisibility());
        agent.setAgentFamily(ASSISTANT_FAMILY);
        agent.setStatus(DISABLED); agent.setDelFlag(NORMAL);
        touch(agent, actor, true);
        try { agents.insert(agent); }
        catch (DuplicateKeyException error)
        {
            requireAvailableCode(request.getAgentCode());
            throw new ServiceException("Agent code already exists", 409);
        }
        audit.record("create", agent.getId(), null, actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return summary(agent, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void editIdentity(RuntimeAgentRequests.Identity request, String actor)
    {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getId());
        agent.setName(StrUtil.trim(request.getName())); agent.setDescription(StrUtil.trim(request.getDescription()));
        agent.setIconUrl(StrUtil.trim(request.getIconUrl()));
        if (request.getVisibility() != null) agent.setVisibility(request.getVisibility());
        touch(agent, actor, false); agents.updateById(agent);
        audit.record("identity/edit", agent.getId(), agent.getCurrentVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.StatusView status(RuntimeAgentRequests.Status request, String actor)
    {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getId());
        if (NORMAL.equals(request.getStatus()))
        {
            if (agent.getCurrentVersionId() == null) throw new ServiceException("请先激活Agent版本");
            AidRuntimeAgentVersion version = requireVersion(agent.getCurrentVersionId());
            requireNativeActiveVersion(version);
            Availability availability = availability(version);
            if (!availability.available()) throw new ServiceException("运行时不可用：" + availability.reason());
        }
        agent.setStatus(request.getStatus()); touch(agent, actor, false); agents.updateById(agent);
        audit.record("status", agent.getId(), agent.getCurrentVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        RuntimeAgentViews.StatusView receipt = new RuntimeAgentViews.StatusView();
        receipt.setId(agent.getId()); receipt.setStatus(agent.getStatus());
        receipt.setUpdateTime(agent.getUpdateTime());
        return receipt;
    }

    /**
     * Reversibly retire one Assistant Agent identity. Immutable versions, runs, messages and
     * billing evidence remain untouched; live execution references must be closed first.
     */
    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.IdentityStateView remove(Long id, String actor)
    {
        AidRuntimeAgent agent = requireAgentForUpdate(id);
        ensureRemovable(agent);
        Date now = new Date();
        int changed = agents.update(null, Wrappers.<AidRuntimeAgent>lambdaUpdate()
                .eq(AidRuntimeAgent::getId, agent.getId())
                .eq(AidRuntimeAgent::getAgentFamily, ASSISTANT_FAMILY)
                .eq(AidRuntimeAgent::getDelFlag, NORMAL)
                .set(AidRuntimeAgent::getStatus, DISABLED)
                .set(AidRuntimeAgent::getDelFlag, DELETED)
                .set(AidRuntimeAgent::getUpdateBy, actor)
                .set(AidRuntimeAgent::getUpdateTime, now));
        ensureIdentityChanged(changed);
        audit.record("identity/delete", agent.getId(), agent.getCurrentVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return identityState(agent.getId(), DISABLED, DELETED, now);
    }

    /** Restore a deleted identity in the disabled state so reactivation stays explicit. */
    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.IdentityStateView restore(Long id, String actor)
    {
        AidRuntimeAgent agent = requireAgentIdentityForUpdate(id);
        if (!DELETED.equals(agent.getDelFlag()))
            throw new ServiceException("Runtime Agent未删除", 409);
        Date now = new Date();
        int changed = agents.update(null, Wrappers.<AidRuntimeAgent>lambdaUpdate()
                .eq(AidRuntimeAgent::getId, agent.getId())
                .eq(AidRuntimeAgent::getAgentFamily, ASSISTANT_FAMILY)
                .eq(AidRuntimeAgent::getDelFlag, DELETED)
                .set(AidRuntimeAgent::getStatus, DISABLED)
                .set(AidRuntimeAgent::getDelFlag, NORMAL)
                .set(AidRuntimeAgent::getUpdateBy, actor)
                .set(AidRuntimeAgent::getUpdateTime, now));
        ensureIdentityChanged(changed);
        audit.record("identity/restore", agent.getId(), agent.getCurrentVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return identityState(agent.getId(), DISABLED, NORMAL, now);
    }

    public RuntimeAgentViews.DraftView draft(Long agentId, Long baseVersionId, Long actorId, String actor)
    {
        AidRuntimeAgent agent = requireAgent(agentId);
        AidRuntimeAgentDraft editing = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getAgentId, agentId)
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("LIMIT 1"));
        if (editing != null)
        {
            RuntimeAgentRequests.DraftPayload body = parseDraft(editing.getDraftJson());
            RuntimeAgentViews.DraftView view = draftView(agent, body, editing);
            audit.record("draft/detail", agentId, editing.getBaseVersionId(), actor);
            return view;
        }
        Long sourceId = baseVersionId != null ? baseVersionId : agent.getCurrentVersionId();
        RuntimeAgentRequests.DraftPayload body = sourceId == null ? emptyDraft(agentId)
                : rawDefinition(requireOwnedVersion(agentId, sourceId));
        if (sourceId != null)
        {
            // A formal package version is a one-publication lock. An ordinary successor keeps the
            // immutable base content and identity, but must choose its own new version on publish.
            body.setPackageVersionCode(null);
        }
        body.setAgentId(agentId); body.setBaseVersionId(sourceId);
        ConversationAgentDefaults.apply(body, supportsContextPreparation(body));
        RuntimeAgentViews.DraftView view = draftView(agent, body, null);
        audit.record("draft/detail", agentId, sourceId, actor);
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.DraftView saveDraft(RuntimeAgentRequests.DraftSave request, Long actorId, String actor)
    {
        ConversationAgentDefaults.apply(request, supportsContextPreparation(request));
        requireDraftShape(request);
        AidRuntimeAgent agent = requireAgentForUpdate(request.getAgentId());
        requireDraftReferences(request);
        AidRuntimeAgentDraft draft = request.getDraftId() == null ? null
                : drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getId, request.getDraftId())
                .eq(AidRuntimeAgentDraft::getAgentId, request.getAgentId())
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("FOR UPDATE"));
        if (request.getDraftId() != null && draft == null) throw new ServiceException("Agent草稿不存在或已发布");
        if (draft != null && !Objects.equals(draft.getDraftDigest(), request.getDraftDigest()))
            throw new ServiceException("Agent草稿已更新，请刷新后重试");
        preserveNativeBindingSelector(request, draft);
        String body = canonicalDraft(request);
        String digest = SecureUtil.sha256(body);
        if (draft == null)
        {
            // Older publications retained this nullable slot under NOT_NULL update strategies.
            // Release only closed drafts for this identity and owner while the identity is locked.
            drafts.update(null, Wrappers.<AidRuntimeAgentDraft>lambdaUpdate()
                    .eq(AidRuntimeAgentDraft::getActiveKey, activeKey(request.getAgentId(), actorId))
                    .eq(AidRuntimeAgentDraft::getStatus, "PUBLISHED")
                    .set(AidRuntimeAgentDraft::getActiveKey, null));
            AidRuntimeAgentDraft existing = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                    .eq(AidRuntimeAgentDraft::getActiveKey, activeKey(request.getAgentId(), actorId))
                    .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("FOR UPDATE"));
            if (existing != null) throw new ServiceException("Agent草稿已存在，请刷新后重试");
            draft = new AidRuntimeAgentDraft();
            draft.setAgentId(request.getAgentId()); draft.setOwnerUserId(actorId);
            draft.setActiveKey(activeKey(request.getAgentId(), actorId)); draft.setStatus(EDITING);
            draft.setCreateBy(actor); draft.setCreateTime(new Date());
        }
        draft.setBaseVersionId(request.getBaseVersionId()); draft.setDraftJson(body); draft.setDraftDigest(digest);
        draft.setUpdateBy(actor); draft.setUpdateTime(new Date());
        if (draft.getId() == null) drafts.insert(draft); else drafts.updateById(draft);
        audit.record("draft/save", agent.getId(), request.getBaseVersionId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
        return draftView(agent, parseDraft(body), draft);
    }

    public RuntimeAgentViews.ValidationView validate(RuntimeAgentRequests.DraftPayload request)
    {
        return validateNewVersion(request, null);
    }

    /** Advisory validation uses this actor's editing draft; publication still requires its CAS. */
    public RuntimeAgentViews.ValidationView validateForActor(
            RuntimeAgentRequests.DraftPayload request, Long actorId)
    {
        requireAgent(request == null ? null : request.getAgentId());
        if (actorId == null || actorId <= 0) throw new ServiceException("Agent草稿身份无效");
        AidRuntimeAgentDraft editing = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getAgentId, request.getAgentId())
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                .eq(AidRuntimeAgentDraft::getActiveKey, activeKey(request.getAgentId(), actorId))
                .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("LIMIT 1"));
        preserveNativeBindingSelector(request, editing);
        return validateNewVersion(request, null);
    }

    private void preserveNativeBindingSelector(RuntimeAgentRequests.DraftPayload request,
                                                AidRuntimeAgentDraft editing)
    {
        RuntimeAgentRequests.ResponsePolicy submitted = request.getResponsePolicy();
        if (submitted != null && submitted.getNativeToolBindingsSchemaVersion() != null)
        {
            String explicit = submitted.getNativeToolBindingsSchemaVersion();
            if (!NativeAssistantBindingPolicy.supportedSelector(explicit))
                throw new ServiceException("Agent原生绑定策略无效");
            return;
        }
        RuntimeAgentRequests.DraftPayload trusted = editing == null ? null : parseDraft(editing.getDraftJson());
        if (editing == null)
        {
            AidRuntimeAgentVersion base = baseVersion(request);
            if (base != null) trusted = rawDefinition(base);
        }
        String selector = trusted == null || trusted.getResponsePolicy() == null ? null
                : trusted.getResponsePolicy().getNativeToolBindingsSchemaVersion();
        if (submitted == null)
        {
            if (selector != null) throw new ServiceException("保留原生绑定策略时响应策略不能为空");
            return;
        }
        submitted.setNativeToolBindingsSchemaVersion(selector);
    }

    private RuntimeAgentViews.ValidationView validateNewVersion(
            RuntimeAgentRequests.DraftPayload request,
            AssistantAgentModelReadinessService.Verification preverifiedModel)
    {
        return validate(request, preverifiedModel, false);
    }

    private RuntimeAgentViews.ValidationView validateStoredVersion(
            RuntimeAgentRequests.DraftPayload request,
            AssistantAgentModelReadinessService.Verification preverifiedModel)
    {
        return validate(request, preverifiedModel, true);
    }

    private RuntimeAgentViews.ValidationView validate(
            RuntimeAgentRequests.DraftPayload request,
            AssistantAgentModelReadinessService.Verification preverifiedModel,
            boolean allowLegacyProtocol)
    {
        return validate(request, preverifiedModel, allowLegacyProtocol, null, null);
    }

    private RuntimeAgentViews.ValidationView validate(
            RuntimeAgentRequests.DraftPayload request,
            AssistantAgentModelReadinessService.Verification preverifiedModel,
            boolean allowLegacyProtocol,
            ListValidationContext context,
            AidRuntimeAgent preloadedAgent)
    {
        AidRuntimeAgent agent = preloadedAgent == null
                ? requireAgent(request == null ? null : request.getAgentId()) : preloadedAgent;
        RuntimeAgentViews.ValidationView result = new RuntimeAgentViews.ValidationView();
        List<RuntimeAgentViews.Issue> errors = result.getErrors();
        validator.validate(request, Default.class, RuntimeAgentRequests.PublishReady.class).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .forEach(violation -> errors.add(issue(
                        violation.getPropertyPath().toString(), violation.getMessage())));
        if (!RuntimeAgentRequests.ASSISTANT_LOOP.equals(request.getExecutorType()))
            errors.add(issue("executorType", "公共助手新版本只允许ASSISTANT_LOOP；旧类型仅保留读取兼容"));
        validateProtocol(request.getProtocolVersion(), allowLegacyProtocol, errors);
        if (!TEXT_CHAT.equals(request.getModelCapabilityCode()))
            errors.add(issue("modelCapabilityCode", "当前公共协议只支持text.chat模型能力"));
        if (StrUtil.isNotBlank(request.getPackageVersionCode())
                && !validVersion(request.getPackageVersionCode()))
            errors.add(issue("packageVersionCode", "正式包版本格式无效"));
        List<String> selectableModelCodes = safe(request.getSelectableModelCodes());
        if (request.getSelectableModelCodes() == null)
            errors.add(issue("selectableModelCodes", "候选模型不能为空"));
        else if (selectableModelCodes.isEmpty() || selectableModelCodes.size() > 20)
            errors.add(issue("selectableModelCodes", "候选模型数量必须为1到20个"));
        for (int index = 0; index < selectableModelCodes.size(); index++)
        {
            String modelCode = selectableModelCodes.get(index);
            if (StrUtil.isBlank(modelCode) || modelCode.length() > 100
                    || !Objects.equals(modelCode, StrUtil.trim(modelCode)))
                errors.add(issue("selectableModelCodes[" + index + "]", "候选模型编码无效"));
        }
        Set<String> modelCodes = selectableModelCodes.stream()
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        if (modelCodes.size() != selectableModelCodes.stream().filter(StrUtil::isNotBlank).count())
            errors.add(issue("selectableModelCodes", "候选模型不能重复"));
        if (!modelCodes.contains(request.getDefaultModelCode()))
            errors.add(issue("defaultModelCode", "默认模型必须在候选模型中"));
        validateResponsePolicy(request, errors);
        RuntimeAgentRequests.ResponsePolicy responsePolicy = request.getResponsePolicy();
        AssistantAgentModelReadinessService.Verification modelVerification = preverifiedModel == null
                ? modelReadiness.verify(request.getModelCapabilityCode(), request.getDefaultModelCode(),
                request.getSelectableModelCodes(), responsePolicy == null ? null : responsePolicy.getMode(),
                responsePolicy == null ? null : responsePolicy.getMaxRepairAttempts()) : preverifiedModel;
        addModelVerificationIssue(errors, modelVerification);
        validateSchemas(request, errors);
        validateFormalPackage(request, errors);
        validateSkills(request, errors, context);
        validateTools(request, errors);
        if (SpatialPrevisGuidancePolicy.appliesToAgent(agent.getAgentCode())
                && !SpatialPrevisGuidancePolicy.validDraftBindings(request))
            errors.add(issue("toolBindings", "空间预演方案助手只能固定 spatial_previs Skill 与只读包工具"));
        result.setValid(errors.isEmpty());
        if (errors.isEmpty()) result.setDraftDigest(SecureUtil.sha256(canonicalDraft(request)));
        if (!loopExecutors.isAvailable(request.getProtocolVersion()))
            result.getWarnings().add(issue("runtimeAvailability", "配置可冻结发布，但公共Assistant Loop执行器尚未唯一注册，运行时不可用"));
        return result;
    }

    private void requireDraftShape(RuntimeAgentRequests.DraftPayload request)
    {
        validator.validate(request, Default.class).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .ifPresent(message -> {
                    throw new ServiceException(message);
                });
        if (StrUtil.isNotBlank(request.getInputSchemaJson()) && !jsonObject(request.getInputSchemaJson()))
            throw new ServiceException("输入Schema必须是JSON对象");
        if (StrUtil.isNotBlank(request.getOutputSchemaJson()) && !jsonObject(request.getOutputSchemaJson()))
            throw new ServiceException("输出Schema必须是JSON对象");
    }

    private void requireDraftReferences(RuntimeAgentRequests.DraftPayload request)
    {
        List<RuntimeAgentViews.Issue> errors = new ArrayList<>();
        validatePackageResources(request, errors);
        validateSkills(request, errors);
        validateTools(request, errors);
        if (!errors.isEmpty()) throw new ServiceException(errors.get(0).message());
    }

    @Transactional(rollbackFor = Exception.class)
    public RuntimeAgentViews.VersionView publish(RuntimeAgentRequests.Publish request, Long actorId, String actor)
    {
        AidRuntimeAgentDraft draft = drafts.selectOne(Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                .eq(AidRuntimeAgentDraft::getId, request.getDraftId())
                .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("FOR UPDATE"));
        if (draft == null) throw new ServiceException("Agent草稿不存在或已发布");
        if (!Objects.equals(draft.getDraftDigest(), request.getDraftDigest()))
            throw new ServiceException("Agent草稿已更新，请刷新后重试");
        RuntimeAgentRequests.DraftPayload body = parseDraft(draft.getDraftJson());
        ConversationAgentDefaults.requireForPublication(body, supportsContextPreparation(body));
        if (StrUtil.isNotBlank(body.getPackageVersionCode())
                && !Objects.equals(body.getPackageVersionCode(), request.getVersionCode()))
            throw new ServiceException("发布版本与正式包不一致");
        RuntimeAgentRequests.ResponsePolicy policy = body.getResponsePolicy();
        AssistantAgentModelReadinessService.Verification modelVerification = modelReadiness.verify(
                body.getModelCapabilityCode(), body.getDefaultModelCode(), body.getSelectableModelCodes(),
                policy == null ? null : policy.getMode(),
                policy == null ? null : policy.getMaxRepairAttempts());
        RuntimeAgentViews.ValidationView validation = validateNewVersion(body, modelVerification);
        if (!validation.isValid()) throw new ServiceException(validation.getErrors().get(0).message());
        requireAgent(draft.getAgentId());
        AidRuntimeAgentVersion version = toVersion(body, request.getVersionCode(), actor, modelVerification);
        try { versions.insert(version); }
        catch (DuplicateKeyException error) { throw new ServiceException("Agent版本号或包摘要已存在"); }
        for (RuntimeAgentRequests.SkillBinding binding : safe(body.getSkillBindings()))
        {
            AidRuntimeAgentSkillRef ref = new AidRuntimeAgentSkillRef();
            ref.setAgentVersionId(version.getId()); ref.setSkillId(binding.getSkillId());
            ref.setSkillVersionId(binding.getSkillVersionId()); ref.setSkillCode(binding.getSkillCode());
            ref.setPackageDigest(binding.getPackageDigest()); skillRefs.insert(ref);
        }
        for (RuntimeAgentRequests.ToolBinding binding : safe(body.getToolBindings()))
        {
            AidRuntimeAgentToolRef ref = new AidRuntimeAgentToolRef();
            ref.setAgentVersionId(version.getId()); ref.setToolCode(binding.getToolCode());
            ref.setContractVersion(binding.getContractVersion()); ref.setSchemaDigest(binding.getSchemaDigest());
            toolRefs.insert(ref);
        }
        drafts.update(null, Wrappers.<AidRuntimeAgentDraft>lambdaUpdate()
                .eq(AidRuntimeAgentDraft::getId, draft.getId())
                .set(AidRuntimeAgentDraft::getStatus, "PUBLISHED")
                .set(AidRuntimeAgentDraft::getActiveKey, null)
                .set(AidRuntimeAgentDraft::getUpdateBy, actor)
                .set(AidRuntimeAgentDraft::getUpdateTime, new Date()));
        audit.record("draft/publish", body.getAgentId(), version.getId(), actor);
        versionLoader.refreshAfterCommit(body.getAgentId());
        return versionView(version, requireAgent(body.getAgentId()).getCurrentVersionId());
    }

    public Page<RuntimeAgentViews.VersionView> versions(RuntimeAgentRequests.VersionPage request)
    {
        AidRuntimeAgent agent = requireAgent(request.getAgentId());
        Page<AidRuntimeAgentVersion> page = versions.selectPage(new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidRuntimeAgentVersion>lambdaQuery().eq(AidRuntimeAgentVersion::getAgentId, request.getAgentId())
                        .eq(AidRuntimeAgentVersion::getDelFlag, NORMAL).orderByDesc(AidRuntimeAgentVersion::getId));
        Page<RuntimeAgentViews.VersionView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(version -> versionSummary(version, agent.getCurrentVersionId())).toList());
        return result;
    }

    public RuntimeAgentViews.VersionView version(Long id, String actor)
    {
        AidRuntimeAgentVersion version = requireVersion(id);
        AidRuntimeAgent agent = requireAgent(version.getAgentId());
        RuntimeAgentViews.VersionView view = versionView(version, agent.getCurrentVersionId());
        audit.record("version/detail", agent.getId(), version.getId(), actor);
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public void activate(RuntimeAgentRequests.Activate request, String actor)
    {
        AidRuntimeAgent agent = requireAgentForUpdate(request.getAgentId());
        AidRuntimeAgentVersion version = requireOwnedVersion(agent.getId(), request.getVersionId());
        requireNativeActiveVersion(version);
        if (!Objects.equals(agent.getCurrentVersionId(), request.getExpectedCurrentVersionId()))
            throw new ServiceException("Agent当前版本已变化，请刷新后重试");
        if (!NORMAL.equals(version.getStatus()) || !FROZEN.equals(version.getPublishStatus()))
            throw new ServiceException("Agent版本未冻结或已停用");
        Availability availability = availability(version);
        if (!availability.available())
            throw new ServiceException("Agent runtime unavailable: " + availability.reason(), 409);
        agent.setCurrentVersionId(version.getId()); touch(agent, actor, false); agents.updateById(agent);
        audit.record("version/activate", agent.getId(), version.getId(), actor);
        versionLoader.refreshAfterCommit(agent.getId());
    }

    private void validateSchemas(RuntimeAgentRequests.DraftPayload request, List<RuntimeAgentViews.Issue> errors)
    {
        if (!jsonObject(request.getInputSchemaJson())) errors.add(issue("inputSchemaJson", "输入Schema必须是JSON对象"));
        if (!jsonObject(request.getOutputSchemaJson())) errors.add(issue("outputSchemaJson", "输出Schema必须是JSON对象"));
        for (String field : List.of("inputSchemaJson", "outputSchemaJson")) {
            try {
                com.aid.skill.service.SkillContractExamples.validate(field,
                        field.equals("inputSchemaJson") ? request.getInputSchemaJson() : request.getOutputSchemaJson());
            } catch (RuntimeException invalid) {
                errors.add(issue(field, invalid.getMessage()));
            }
        }
    }

    private void validateFormalPackage(RuntimeAgentRequests.DraftPayload request,
                                       List<RuntimeAgentViews.Issue> errors)
    {
        if (StrUtil.isBlank(request.getMainMarkdown()))
            errors.add(issue("mainMarkdown", "Agent主文件不能为空"));
        if (StrUtil.isBlank(request.getSystemPrompt()))
            errors.add(issue("systemPrompt", "Agent SP.md不能为空"));
        if (!Objects.equals(request.getInstruction(), request.getSystemPrompt()))
            errors.add(issue("instruction", "instruction必须与systemPrompt完全一致"));
        if (RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1.equals(request.getProtocolVersion())
                && !Objects.equals(request.getMainMarkdown(), request.getSystemPrompt()))
            errors.add(issue("mainMarkdown", "原生Agent的mainMarkdown必须与SP.md完全一致"));
        validatePackageResources(request, errors);
        List<RuntimeAgentRequests.PackageResource> resources = safe(request.getResources());
        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        documents.put("SP.md", request.getSystemPrompt());
        fields.put("SP.md", "systemPrompt");
        for (int index = 0; index < resources.size(); index++)
        {
            RuntimeAgentRequests.PackageResource resource = resources.get(index);
            if (resource != null && safeAgentReferencePath(resource.getFilePath()))
            {
                documents.put(resource.getFilePath(), resource.getContent());
                fields.put(resource.getFilePath(), "resources[" + index + "].content");
            }
        }
        for (PackageDocumentReferences.MissingReference reference : PackageDocumentReferences.validate(documents))
        {
            errors.add(issue(fields.get(reference.sourcePath()), "第" + reference.line()
                    + "行引用的包内文件不存在或路径越界：" + reference.targetPath()));
        }
    }

    private void validatePackageResources(RuntimeAgentRequests.DraftPayload request,
                                          List<RuntimeAgentViews.Issue> errors)
    {
        Set<String> keys = new HashSet<>();
        Set<String> paths = new HashSet<>();
        List<RuntimeAgentRequests.PackageResource> resources = safe(request.getResources());
        for (int index = 0; index < resources.size(); index++)
        {
            RuntimeAgentRequests.PackageResource resource = resources.get(index);
            String field = "resources[" + index + "]";
            if (resource == null)
            {
                errors.add(issue(field, "Agent资源不能为空"));
                continue;
            }
            if (StrUtil.isBlank(resource.getResourceKey())
                    || !resource.getResourceKey().matches("[a-z0-9][a-z0-9._-]{0,99}")
                    || !keys.add(resource.getResourceKey()))
                errors.add(issue(field + ".resourceKey", "Agent资源标识无效或重复"));
            if (!safeAgentReferencePath(resource.getFilePath())
                    || !paths.add(resource.getFilePath()))
                errors.add(issue(field + ".filePath", "Agent资源路径无效或重复"));
            if (StrUtil.isBlank(resource.getContent()))
                errors.add(issue(field + ".content", "Agent资源内容不能为空"));
            try
            {
                if (JSON.parseObject(
                        StrUtil.blankToDefault(resource.getRouteJson(), "{}")) == null)
                    throw new IllegalArgumentException();
            }
            catch (RuntimeException invalid)
            {
                errors.add(issue(field + ".routeJson", "Agent资源路由必须是JSON对象"));
            }
        }
    }

    private void validateResponsePolicy(RuntimeAgentRequests.DraftPayload request,
                                        List<RuntimeAgentViews.Issue> errors)
    {
        RuntimeAgentRequests.ResponsePolicy policy = request.getResponsePolicy();
        if (policy == null)
        {
            errors.add(issue("responsePolicy", "响应策略不能为空"));
            return;
        }
        Integer repairs = policy.getMaxRepairAttempts();
        if (repairs == null || repairs < 0 || repairs > 2)
        {
            errors.add(issue("responsePolicy.maxRepairAttempts", "修复次数无效"));
            return;
        }
        if (repairs > 0)
            errors.add(issue("responsePolicy.maxRepairAttempts", "修复调用尚未授权"));
        boolean nativeProtocol = RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1.equals(
                request.getProtocolVersion());
        String nativeSelector = policy.getNativeToolBindingsSchemaVersion();
        if (nativeSelector != null && (!nativeProtocol
                || !NativeAssistantBindingPolicy.supportedSelector(nativeSelector)))
            errors.add(issue("responsePolicy.nativeToolBindingsSchemaVersion", "原生绑定策略只适用于原生循环协议且必须是已支持的版本"));
        if (nativeProtocol)
        {
            if (RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(policy.getMode()) && repairs == 0) return;
            errors.add(issue("responsePolicy.mode", "assistant-loop/native-v1必须使用NATIVE_TOOL_STREAM且修复次数为0"));
            return;
        }
        if (RuntimeAgentRequests.STRICT_LOCAL_JSON.equals(policy.getMode())
                || RuntimeAgentRequests.NATIVE_JSON_SCHEMA.equals(policy.getMode())) return;
        errors.add(issue("responsePolicy.mode", "响应策略无效"));
    }

    private void addModelVerificationIssue(List<RuntimeAgentViews.Issue> errors,
                                           AssistantAgentModelReadinessService.Verification verification)
    {
        if (verification.ready()) return;
        String field = switch (verification.failure())
        {
            case MODEL_CAPABILITY -> "modelCapabilityCode";
            case RESPONSE_POLICY, NATIVE_UNAVAILABLE, NATIVE_TOOL_UNAVAILABLE -> "responsePolicy.mode";
            case MODEL_CONFIG, MODEL_UNAVAILABLE -> "selectableModelCodes";
            default -> null;
        };
        if (field != null && errors.stream().noneMatch(existing -> existing.field().startsWith(field)))
            errors.add(issue(field, verification.reason()));
    }

    private void validateSkills(RuntimeAgentRequests.DraftPayload request, List<RuntimeAgentViews.Issue> errors)
    {
        validateSkills(request, errors, null);
    }

    private void validateSkills(RuntimeAgentRequests.DraftPayload request,
                                List<RuntimeAgentViews.Issue> errors,
                                ListValidationContext context)
    {
        if (request.getSkillBindings() == null)
        {
            errors.add(issue("skillBindings", "Skill绑定列表不能为空"));
            return;
        }
        if (request.getSkillBindings().size() > RuntimeAgentRequests.MAX_SKILL_BINDINGS)
        {
            errors.add(issue("skillBindings", "Skill绑定最多64项"));
            return;
        }
        List<RuntimeAgentRequests.SkillBinding> bindings = request.getSkillBindings();
        List<Long> skillIds = bindings.stream().filter(Objects::nonNull)
                .map(RuntimeAgentRequests.SkillBinding::getSkillId)
                .filter(id -> id != null && id > 0).distinct().toList();
        List<Long> versionIds = bindings.stream().filter(Objects::nonNull)
                .map(RuntimeAgentRequests.SkillBinding::getSkillVersionId)
                .filter(id -> id != null && id > 0).distinct().toList();
        Map<Long, AidSkill> skillById = context != null ? context.skills() : skillIds.isEmpty() ? Map.of()
                : skills.selectBatchIds(skillIds).stream().collect(Collectors.toMap(
                        AidSkill::getId, Function.identity(), (left, right) -> left));
        Map<Long, AidSkillVersion> versionById = context != null ? context.skillVersions() : versionIds.isEmpty() ? Map.of()
                : skillVersions.selectBatchIds(versionIds).stream().collect(Collectors.toMap(
                        AidSkillVersion::getId, Function.identity(), (left, right) -> left));
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < bindings.size(); i++)
        {
            RuntimeAgentRequests.SkillBinding binding = bindings.get(i);
            String field = "skillBindings[" + i + "]";
            if (binding == null)
            {
                errors.add(issue(field, "Skill绑定不能为空"));
                continue;
            }
            if (binding.getSkillId() == null || binding.getSkillId() <= 0)
            { errors.add(issue(field + ".skillId", "Skill身份无效")); continue; }
            if (binding.getSkillVersionId() == null || binding.getSkillVersionId() <= 0)
            { errors.add(issue(field + ".skillVersionId", "Skill固定版本无效")); continue; }
            if (StrUtil.isBlank(binding.getSkillCode()) || binding.getSkillCode().length() > 64)
            { errors.add(issue(field + ".skillCode", "Skill编码无效")); continue; }
            if (StrUtil.isBlank(binding.getPackageDigest())
                    || !binding.getPackageDigest().matches("[0-9a-f]{64}"))
            { errors.add(issue(field + ".packageDigest", "Skill包摘要无效")); continue; }
            if (!ids.add(binding.getSkillId())) { errors.add(issue(field, "同一Skill不能重复绑定")); continue; }
            AidSkill skill = skillById.get(binding.getSkillId());
            AidSkillVersion version = versionById.get(binding.getSkillVersionId());
            if (skill == null || version == null || !Objects.equals(version.getSkillId(), skill.getId()))
            { errors.add(issue(field, "固定Skill版本不存在或不属于该Skill")); continue; }
            if (!Objects.equals(binding.getSkillCode(), skill.getSkillCode())
                    || !Objects.equals(binding.getPackageDigest(), version.getPackageDigest()))
            { errors.add(issue(field, "Skill编码或包摘要与固定版本不一致")); continue; }
            if (!(context == null
                    ? publicAgentSkillBindingReady(skill, version, binding.getPackageDigest())
                    : context.readySkills().contains(new SkillCoordinate(
                            binding.getSkillId(), binding.getSkillVersionId(), binding.getPackageDigest()))))
                errors.add(issue(field, "固定Skill版本必须是平台公开、正式发布且符合对应执行契约的包"));
        }
    }

    private boolean publicAgentSkillBindingReady(AidSkill skill, AidSkillVersion version,
                                                 String expectedPackageDigest)
    {
        if (version != null && "IMAGE".equals(SkillModelService.outputKind(version)))
            return publicAgentImageBindingReady(skill, version, expectedPackageDigest);
        return publicAgentMethodBindingReady(skill, version, expectedPackageDigest);
    }

    private boolean publicAgentMethodBindingReady(AidSkill skill, AidSkillVersion version,
                                                  String expectedPackageDigest)
    {
        if (skill == null || version == null || !NORMAL.equals(skill.getStatus())
                || !NORMAL.equals(skill.getDelFlag())
                || !Objects.equals(version.getSkillId(), skill.getId())
                || !Objects.equals(expectedPackageDigest, version.getPackageDigest())
                || !"TEXT".equals(SkillModelService.outputKind(version))
                || !skillReadiness.isPublicPlatformPackageVersionReady(skill, version))
            return false;
        try
        {
            SkillRuntimeEntrypointReadinessService.ReadyEntrypoint ready =
                    skillReadiness.requireReadyMethod(skill, version.getId(), expectedPackageDigest);
            return ready != null && ready.rootVersion() != null
                    && Objects.equals(version.getId(), ready.rootVersion().getId())
                    && Objects.equals(expectedPackageDigest, ready.rootVersion().getPackageDigest());
        }
        catch (RuntimeException unavailable)
        {
            return false;
        }
    }

    private boolean publicAgentImageBindingReady(AidSkill skill, AidSkillVersion version,
                                                 String expectedPackageDigest)
    {
        if (skill == null || version == null || !NORMAL.equals(skill.getStatus())
                || !NORMAL.equals(skill.getDelFlag())
                || !Objects.equals(version.getSkillId(), skill.getId())
                || !Objects.equals(expectedPackageDigest, version.getPackageDigest())
                || !"PLATFORM".equals(skill.getOwnerType()) || !"PUBLIC".equals(skill.getVisibility())
                || !"ENTRYPOINT".equals(skill.getInvocationScope())
                || !"PROMPT".equals(skill.getExecutorType())
                || !"PUBLIC".equals(version.getVisibility())
                || !"ENTRYPOINT".equals(version.getInvocationScope())
                || !"PROMPT".equals(version.getExecutorType())
                || !"PUBLISHED".equals(version.getPublishStatus())
                || !"IMAGE".equals(SkillModelService.outputKind(version))
                || !AssistantImageSkillInvocationResolver.hasStrictSchemas(version)
                || !skillReadiness.isPublicPlatformPackageVersionReady(skill, version)) return false;
        try
        {
            JSONObject definition = JSON.parseObject(version.getDefinitionJson());
            return definition != null && com.aid.skill.service.SkillImageExecutionContract
                    .supportsDefinition(version.getDefinitionJson());
        }
        catch (RuntimeException unavailable)
        {
            return false;
        }
    }

    private void validateTools(RuntimeAgentRequests.DraftPayload request, List<RuntimeAgentViews.Issue> errors)
    {
        if (request.getToolBindings() == null)
        {
            errors.add(issue("toolBindings", "Tool绑定列表不能为空"));
            return;
        }
        List<RuntimeAgentRequests.ToolBinding> bindings = request.getToolBindings();
        if (bindings.size() > RuntimeAgentRequests.MAX_TOOL_BINDINGS)
        {
            errors.add(issue("toolBindings", "Tool绑定最多65项（含通用历史读取）"));
            return;
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < bindings.size(); i++)
        {
            RuntimeAgentRequests.ToolBinding binding = bindings.get(i);
            String field = "toolBindings[" + i + "]";
            if (binding == null)
            {
                errors.add(issue(field, "Tool绑定不能为空"));
                continue;
            }
            if (StrUtil.isBlank(binding.getToolCode()) || binding.getToolCode().length() > 100)
            { errors.add(issue(field + ".toolCode", "Tool编码无效")); continue; }
            if (StrUtil.isBlank(binding.getContractVersion()) || binding.getContractVersion().length() > 32)
            { errors.add(issue(field + ".contractVersion", "Tool契约版本无效")); continue; }
            if (StrUtil.isBlank(binding.getSchemaDigest())
                    || !binding.getSchemaDigest().matches("[0-9a-f]{64}"))
            { errors.add(issue(field + ".schemaDigest", "Tool Schema摘要无效")); continue; }
            if (!codes.add(binding.getToolCode())) { errors.add(issue(field, "同一Tool不能重复绑定")); continue; }
            if (!tools.supports(binding.getToolCode())) { errors.add(issue(field, "Tool未在服务端注册")); continue; }
            RuntimeToolRegistry.Descriptor descriptor = tools.require(binding.getToolCode());
            if (!Objects.equals(binding.getContractVersion(), descriptor.contractVersion())
                    || !Objects.equals(binding.getSchemaDigest(), descriptor.schemaDigest()))
                errors.add(issue(field, "Tool契约版本或Schema摘要已变化"));
        }
    }

    private Availability availability(AidRuntimeAgentVersion version)
    {
        return availability(version, null);
    }

    private Availability availability(AidRuntimeAgentVersion version, ListValidationContext context)
    {
        AssistantAgentModelReadinessService.Verification modelVerification = context == null
                ? modelReadiness.verifyFrozen(version.getModelConfigJson(), version.getDefinitionJson())
                : context.modelVerifications().get(version.getId());
        if (modelVerification == null) return new Availability(false, "版本定义不可读取");
        if (!modelVerification.ready()) return new Availability(false, modelVerification.reason());
        RuntimeAgentRequests.DraftPayload body;
        try { body = context == null ? rawDefinition(version) : context.definitions().get(version.getId()); }
        catch (RuntimeException error) { return new Availability(false, "版本定义不可读取"); }
        if (body == null) return new Availability(false, "版本定义不可读取");
        if (!RuntimeAgentRequests.ASSISTANT_LOOP.equals(body.getExecutorType())
                || !Set.of(RuntimeAgentRequests.ASSISTANT_LOOP_V1,
                RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1).contains(body.getProtocolVersion()))
            return new Availability(false, "版本不是受支持的Assistant Loop协议");
        if (!loopExecutors.isAvailable(body.getProtocolVersion()))
            return new Availability(false, "公共Assistant Loop执行器尚未唯一注册");
        if (!skillReferencesMatch(version.getId(), body.getSkillBindings(), context))
            return new Availability(false, "固定Skill版本引用不完整或摘要不一致");
        if (!toolReferencesMatch(version.getId(), body.getToolBindings(), context))
            return new Availability(false, "Tool允许范围引用不完整或契约摘要不一致");
        RuntimeAgentViews.ValidationView validation = context == null
                ? validateStoredVersion(body, modelVerification)
                : validate(body, modelVerification, true, context, context.agents().get(body.getAgentId()));
        if (!validation.isValid()) return new Availability(false, validation.getErrors().get(0).message());
        return new Availability(true, null);
    }

    static void validateProtocol(String protocolVersion, boolean allowLegacyProtocol,
                                 List<RuntimeAgentViews.Issue> errors)
    {
        if (RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1.equals(protocolVersion)) return;
        if (allowLegacyProtocol && RuntimeAgentRequests.ASSISTANT_LOOP_V1.equals(protocolVersion)) return;
        errors.add(issue("protocolVersion", allowLegacyProtocol
                ? "已冻结版本协议必须为assistant-loop/v1或assistant-loop/native-v1"
                : "新Agent版本只允许assistant-loop/native-v1；assistant-loop/v1仅保留既有冻结运行恢复"));
    }

    private void requireNativeActiveVersion(AidRuntimeAgentVersion version)
    {
        RuntimeAgentRequests.DraftPayload body = rawDefinition(version);
        if (!supportsNewActivation(body.getProtocolVersion()))
            throw new ServiceException("旧assistant-loop/v1版本仅保留既有运行恢复，不能重新启用或绑定");
    }

    static boolean supportsNewActivation(String protocolVersion)
    {
        return RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1.equals(protocolVersion);
    }

    private Availability activeAvailability(AidRuntimeAgentVersion version)
    {
        try
        {
            requireNativeActiveVersion(version);
        }
        catch (RuntimeException rejected)
        {
            return new Availability(false, rejected.getMessage());
        }
        return availability(version);
    }

    private boolean skillReferencesMatch(Long versionId, List<RuntimeAgentRequests.SkillBinding> bindings)
    {
        return skillReferencesMatch(versionId, bindings, null);
    }

    private boolean skillReferencesMatch(Long versionId, List<RuntimeAgentRequests.SkillBinding> bindings,
                                         ListValidationContext context)
    {
        if (bindings == null || bindings.stream().anyMatch(Objects::isNull)) return false;
        List<AidRuntimeAgentSkillRef> refs = context == null
                ? skillRefs.selectList(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                .eq(AidRuntimeAgentSkillRef::getAgentVersionId, versionId))
                : context.skillRefs().getOrDefault(versionId, List.of());
        if (refs.size() != safe(bindings).size()) return false;
        Map<Long, RuntimeAgentRequests.SkillBinding> expected = safe(bindings).stream().collect(Collectors.toMap(
                RuntimeAgentRequests.SkillBinding::getSkillId, Function.identity(), (left, right) -> left));
        return refs.stream().allMatch(ref -> {
            RuntimeAgentRequests.SkillBinding binding = expected.get(ref.getSkillId());
            return binding != null && Objects.equals(binding.getSkillVersionId(), ref.getSkillVersionId())
                    && Objects.equals(binding.getSkillCode(), ref.getSkillCode())
                    && Objects.equals(binding.getPackageDigest(), ref.getPackageDigest());
        });
    }

    private boolean toolReferencesMatch(Long versionId, List<RuntimeAgentRequests.ToolBinding> bindings)
    {
        return toolReferencesMatch(versionId, bindings, null);
    }

    private boolean toolReferencesMatch(Long versionId, List<RuntimeAgentRequests.ToolBinding> bindings,
                                        ListValidationContext context)
    {
        if (bindings == null || bindings.stream().anyMatch(Objects::isNull)) return false;
        List<AidRuntimeAgentToolRef> refs = context == null
                ? toolRefs.selectList(Wrappers.<AidRuntimeAgentToolRef>lambdaQuery()
                .eq(AidRuntimeAgentToolRef::getAgentVersionId, versionId))
                : context.toolRefs().getOrDefault(versionId, List.of());
        if (refs.size() != safe(bindings).size()) return false;
        Map<String, RuntimeAgentRequests.ToolBinding> expected = safe(bindings).stream().collect(Collectors.toMap(
                RuntimeAgentRequests.ToolBinding::getToolCode, Function.identity(), (left, right) -> left));
        return refs.stream().allMatch(ref -> {
            RuntimeAgentRequests.ToolBinding binding = expected.get(ref.getToolCode());
            return binding != null && Objects.equals(binding.getContractVersion(), ref.getContractVersion())
                    && Objects.equals(binding.getSchemaDigest(), ref.getSchemaDigest());
        });
    }

    private RuntimeAgentViews.AgentSummary summary(AidRuntimeAgent agent, AidRuntimeAgentVersion version)
    {
        return summary(agent, version, null);
    }

    private RuntimeAgentViews.AgentSummary summary(AidRuntimeAgent agent, AidRuntimeAgentVersion version,
                                                   ListValidationContext context)
    {
        RuntimeAgentViews.AgentSummary view = new RuntimeAgentViews.AgentSummary();
        view.setId(agent.getId()); view.setAgentCode(agent.getAgentCode()); view.setName(agent.getName());
        view.setDescription(agent.getDescription()); view.setIconUrl(agent.getIconUrl());
        view.setVisibility(agent.getVisibility()); view.setAgentFamily(agent.getAgentFamily());
        view.setCurrentVersionId(agent.getCurrentVersionId());
        view.setStatus(agent.getStatus()); view.setDelFlag(agent.getDelFlag());
        view.setUpdateTime(agent.getUpdateTime());
        Availability availability = version == null ? new Availability(false, "尚未激活版本")
                : availability(version, context);
        view.setRuntimeAvailability(availability.available() ? "AVAILABLE" : "UNAVAILABLE");
        view.setRuntimeUnavailableReason(availability.reason());
        return view;
    }

    private RuntimeAgentViews.VersionView versionView(AidRuntimeAgentVersion version, Long currentVersionId)
    {
        RuntimeAgentRequests.DraftPayload body = definitionForRead(version);
        RuntimeAgentViews.VersionView view = new RuntimeAgentViews.VersionView();
        copy(body, view);
        view.setId(version.getId()); view.setVersionCode(version.getVersionCode());
        view.setPublishStatus(version.getPublishStatus()); view.setPackageDigest(version.getPackageDigest());
        view.setStatus(version.getStatus()); view.setCurrent(Objects.equals(version.getId(), currentVersionId));
        Availability availability = availability(version);
        view.setRuntimeAvailability(availability.available() ? "AVAILABLE" : "UNAVAILABLE");
        view.setRuntimeUnavailableReason(availability.reason());
        view.setCreateBy(version.getCreateBy()); view.setCreateTime(version.getCreateTime());
        return view;
    }

    private RuntimeAgentViews.VersionView versionSummary(AidRuntimeAgentVersion version, Long currentVersionId)
    {
        RuntimeAgentViews.VersionView view = new RuntimeAgentViews.VersionView();
        RuntimeAgentRequests.DraftPayload body = definitionForRead(version);
        view.setAgentId(version.getAgentId()); view.setExecutorType(body.getExecutorType());
        view.setProtocolVersion(body.getProtocolVersion()); view.setModelCapabilityCode(body.getModelCapabilityCode());
        view.setDefaultModelCode(body.getDefaultModelCode());
        view.setId(version.getId()); view.setVersionCode(version.getVersionCode());
        view.setPublishStatus(version.getPublishStatus()); view.setPackageDigest(version.getPackageDigest());
        view.setStatus(version.getStatus()); view.setCurrent(Objects.equals(version.getId(), currentVersionId));
        Availability availability = availability(version);
        view.setRuntimeAvailability(availability.available() ? "AVAILABLE" : "UNAVAILABLE");
        view.setRuntimeUnavailableReason(availability.reason());
        view.setCreateBy(version.getCreateBy()); view.setCreateTime(version.getCreateTime());
        return view;
    }

    private RuntimeAgentRequests.DraftPayload definitionForRead(AidRuntimeAgentVersion version)
    {
        RuntimeAgentRequests.DraftPayload body = rawDefinition(version);
        normalizeLegacyStoredPackage(body);
        return body;
    }

    private RuntimeAgentRequests.DraftPayload rawDefinition(AidRuntimeAgentVersion version)
    {
        try
        {
            JSONObject rawDefinition = JSON.parseObject(version.getDefinitionJson());
            NativeAssistantBindingPolicy.explicitSelector(rawDefinition);
            RuntimeAgentRequests.DraftPayload body = rawDefinition == null ? null
                    : rawDefinition.toJavaObject(RuntimeAgentRequests.DraftPayload.class);
            if (body == null) throw new IllegalArgumentException();
            if (!rawDefinition.containsKey("protocolVersion"))
                body.setProtocolVersion(RuntimeAgentRequests.ASSISTANT_LOOP_V1);
            if (!rawDefinition.containsKey("checkpointPolicy")) body.setCheckpointPolicy("TURN_BOUNDARY");
            if (!rawDefinition.containsKey("responsePolicy")) body.setResponsePolicy(null);
            body.setAgentId(version.getAgentId()); body.setInstruction(version.getInstruction());
            body.setInputSchemaJson(version.getInputSchemaJson()); body.setOutputSchemaJson(version.getOutputSchemaJson());
            JSONObject model = JSON.parseObject(version.getModelConfigJson());
            body.setModelCapabilityCode(model.getString("modelCapabilityCode"));
            body.setDefaultModelCode(model.getString("defaultModelCode"));
            body.setSelectableModelCodes(model.getList("selectableModelCodes", String.class));
            body.setTemperatureBasisPoints(model.getInteger("temperatureBasisPoints"));
            return body;
        }
        catch (RuntimeException error)
        {
            throw new ServiceException("Agent版本定义不可读取");
        }
    }

    private AidRuntimeAgentVersion toVersion(RuntimeAgentRequests.DraftPayload body, String versionCode, String actor,
                                              AssistantAgentModelReadinessService.Verification modelVerification)
    {
        AidRuntimeAgentVersion version = new AidRuntimeAgentVersion();
        version.setAgentId(body.getAgentId()); version.setVersionCode(versionCode); version.setPublishStatus(FROZEN);
        version.setInstruction(body.getSystemPrompt());
        version.setModelConfigJson(modelConfig(body, modelVerification));
        version.setInputSchemaJson(body.getInputSchemaJson()); version.setOutputSchemaJson(body.getOutputSchemaJson());
        version.setDefinitionJson(definitionJson(body));
        version.setPackageDigest(RuntimeAgentVersionLoader.calculatePackageDigest(version));
        version.setStatus(NORMAL); version.setDelFlag(NORMAL);
        version.setCreateBy(actor); version.setCreateTime(new Date()); version.setUpdateBy(actor); version.setUpdateTime(new Date());
        return version;
    }

    private String modelConfig(RuntimeAgentRequests.DraftPayload body,
                               AssistantAgentModelReadinessService.Verification verification)
    {
        String mode = body.getResponsePolicy() == null ? null : body.getResponsePolicy().getMode();
        if (verification == null || !verification.ready() || !Objects.equals(mode, verification.responseMode()))
            throw new ServiceException("Agent模型能力验证已变化");
        return modelConfig(body);
    }

    private String modelConfig(RuntimeAgentRequests.DraftPayload body)
    {
        String mode = body.getResponsePolicy() == null ? null : body.getResponsePolicy().getMode();
        boolean nativeSchema = RuntimeAgentRequests.NATIVE_JSON_SCHEMA.equals(mode);
        boolean nativeTools = RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(mode);
        JSONObject model = baseJson(body, true);
        model.put("modelCapabilityCode", body.getModelCapabilityCode());
        model.put("defaultModelCode", body.getDefaultModelCode());
        model.put("selectableModelCodes", body.getSelectableModelCodes());
        model.put("candidateStrategy", "ADMIN_ORDERED_ALLOWLIST");
        model.put("temperatureBasisPoints", body.getTemperatureBasisPoints());
        model.put("nativeJsonSchemaSupported", nativeSchema);
        model.put("supportsStreaming", nativeTools);
        model.put("supportsToolCalling", nativeTools);
        model.put("nativeToolCallingSupported", nativeTools);
        return JSON.toJSONString(model);
    }

    private String definitionJson(RuntimeAgentRequests.DraftPayload body)
    {
        JSONObject definition = baseJson(body, false);
        definition.put("agentId", body.getAgentId()); definition.put("baseVersionId", body.getBaseVersionId());
        definition.put("executorType", body.getExecutorType()); definition.put("protocolVersion", body.getProtocolVersion());
        definition.put("mainMarkdown", body.getMainMarkdown());
        definition.put("systemPrompt", body.getSystemPrompt());
        definition.put("resources", mergePackageResources(definition.getJSONArray("resources"), body.getResources()));
        definition.put("checkpointPolicy", body.getCheckpointPolicy()); definition.put("maxTurns", body.getMaxTurns());
        definition.put("maxToolCalls", body.getMaxToolCalls()); definition.put("skillBindings", body.getSkillBindings());
        definition.put("toolBindings", body.getToolBindings());
        definition.put("packageVersionCode", body.getPackageVersionCode());
        JSONObject responsePolicy = definition.getJSONObject("responsePolicy");
        if (responsePolicy == null) responsePolicy = new JSONObject();
        if (body.getResponsePolicy() == null) responsePolicy = null;
        else
        {
            responsePolicy.put("mode", body.getResponsePolicy().getMode());
            responsePolicy.put("maxRepairAttempts", body.getResponsePolicy().getMaxRepairAttempts());
            String selector = body.getResponsePolicy().getNativeToolBindingsSchemaVersion();
            if (selector == null) responsePolicy.remove("nativeToolBindingsSchemaVersion");
            else responsePolicy.put("nativeToolBindingsSchemaVersion", selector);
        }
        definition.put("responsePolicy", responsePolicy);
        return JSON.toJSONString(definition);
    }

    private JSONObject baseJson(RuntimeAgentRequests.DraftPayload body, boolean model)
    {
        AidRuntimeAgentVersion base = baseVersion(body);
        String raw = base == null ? null : model ? base.getModelConfigJson() : base.getDefinitionJson();
        try
        {
            JSONObject value = StrUtil.isBlank(raw) ? new JSONObject() : JSON.parseObject(raw);
            return value == null ? new JSONObject() : value;
        }
        catch (RuntimeException invalid) { throw new ServiceException("Agent基础版本配置不可读取"); }
    }

    private AidRuntimeAgentVersion baseVersion(RuntimeAgentRequests.DraftPayload body)
    {
        if (body == null || body.getBaseVersionId() == null) return null;
        // Agent versions are immutable. Reading by the draft's fixed base retains fields unknown to
        // this server revision without trusting a duplicate client-side projection.
        AidRuntimeAgentVersion base = versions.selectById(body.getBaseVersionId());
        if (base == null || !Objects.equals(base.getAgentId(), body.getAgentId()))
            throw new ServiceException("Agent基础版本不存在或不属于当前Agent");
        return base;
    }

    private List<JSONObject> mergePackageResources(JSONArray base,
                                                    List<RuntimeAgentRequests.PackageResource> submitted)
    {
        Map<String, JSONObject> prior = new LinkedHashMap<>();
        if (base != null) for (Object raw : base)
        {
            JSONObject item = raw instanceof JSONObject object ? object
                    : JSON.parseObject(JSON.toJSONString(raw));
            if (item != null && StrUtil.isNotBlank(item.getString("resourceKey")))
                prior.put(item.getString("resourceKey"), item);
        }
        List<JSONObject> result = new ArrayList<>();
        for (RuntimeAgentRequests.PackageResource resource : safe(submitted))
        {
            JSONObject item = prior.getOrDefault(resource.getResourceKey(), new JSONObject());
            item.put("resourceKey", resource.getResourceKey()); item.put("filePath", resource.getFilePath());
            item.put("mimeType", resource.getMimeType()); item.put("content", resource.getContent());
            item.put("routeJson", resource.getRouteJson()); result.add(item);
        }
        return List.copyOf(result);
    }

    private String canonicalDraft(RuntimeAgentRequests.DraftPayload body)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agentId", body.getAgentId()); value.put("baseVersionId", body.getBaseVersionId());
        value.put("mainMarkdown", body.getMainMarkdown()); value.put("systemPrompt", body.getSystemPrompt());
        value.put("resources", body.getResources());
        value.put("instruction", body.getInstruction()); value.put("executorType", body.getExecutorType());
        value.put("protocolVersion", body.getProtocolVersion()); value.put("modelCapabilityCode", body.getModelCapabilityCode());
        value.put("defaultModelCode", body.getDefaultModelCode()); value.put("selectableModelCodes", body.getSelectableModelCodes());
        value.put("temperatureBasisPoints", body.getTemperatureBasisPoints()); value.put("maxTurns", body.getMaxTurns());
        value.put("maxToolCalls", body.getMaxToolCalls()); value.put("checkpointPolicy", body.getCheckpointPolicy());
        value.put("inputSchemaJson", body.getInputSchemaJson()); value.put("outputSchemaJson", body.getOutputSchemaJson());
        value.put("responsePolicy", canonicalResponsePolicy(body.getResponsePolicy()));
        value.put("skillBindings", body.getSkillBindings()); value.put("toolBindings", body.getToolBindings());
        value.put("packageVersionCode", body.getPackageVersionCode());
        value.put("preservedDefinitionJson", definitionJson(body));
        value.put("preservedModelConfigJson", modelConfig(body));
        return JSON.toJSONString(value, JSONWriter.Feature.WriteMapNullValue);
    }

    private RuntimeAgentRequests.DraftPayload parseDraft(String json)
    {
        try
        {
            JSONObject raw = JSON.parseObject(json);
            NativeAssistantBindingPolicy.explicitSelector(raw);
            RuntimeAgentRequests.DraftPayload body = raw == null ? null
                    : raw.toJavaObject(RuntimeAgentRequests.DraftPayload.class);
            if (body == null) throw new IllegalArgumentException();
            if (!raw.containsKey("protocolVersion"))
                body.setProtocolVersion(RuntimeAgentRequests.ASSISTANT_LOOP_V1);
            if (!raw.containsKey("checkpointPolicy")) body.setCheckpointPolicy("TURN_BOUNDARY");
            if (!raw.containsKey("responsePolicy")) body.setResponsePolicy(null);
            return body;
        }
        catch (RuntimeException error) { throw new ServiceException("Agent草稿定义不可读取"); }
    }

    private JSONObject canonicalResponsePolicy(RuntimeAgentRequests.ResponsePolicy policy)
    {
        if (policy == null) return null;
        JSONObject value = JSON.parseObject(JSON.toJSONString(policy, JSONWriter.Feature.WriteMapNullValue));
        if (policy.getNativeToolBindingsSchemaVersion() == null)
            value.remove("nativeToolBindingsSchemaVersion");
        return value;
    }

    private RuntimeAgentViews.DraftView draftView(AidRuntimeAgent agent, RuntimeAgentRequests.DraftPayload body,
                                                   AidRuntimeAgentDraft draft)
    {
        RuntimeAgentViews.DraftView view = new RuntimeAgentViews.DraftView(); copy(body, view);
        view.setAgentCode(agent.getAgentCode()); view.setDraftId(draft == null ? null : draft.getId());
        view.setDraftDigest(draft == null ? SecureUtil.sha256(canonicalDraft(body)) : draft.getDraftDigest());
        view.setUpdateTime(draft == null ? null : draft.getUpdateTime());
        return view;
    }

    private RuntimeAgentRequests.DraftPayload emptyDraft(Long agentId)
    {
        RuntimeAgentRequests.DraftPayload body = new RuntimeAgentRequests.DraftPayload();
        body.setAgentId(agentId); body.setModelCapabilityCode(TEXT_CHAT);
        body.setMainMarkdown("# Agent\n"); body.setSystemPrompt(""); body.setInstruction("");
        return body;
    }

    private void copy(RuntimeAgentRequests.DraftPayload source, RuntimeAgentRequests.DraftPayload target)
    {
        target.setAgentId(source.getAgentId()); target.setBaseVersionId(source.getBaseVersionId());
        target.setMainMarkdown(source.getMainMarkdown()); target.setSystemPrompt(source.getSystemPrompt());
        target.setResources(source.getResources());
        target.setInstruction(source.getInstruction()); target.setExecutorType(source.getExecutorType());
        target.setProtocolVersion(source.getProtocolVersion()); target.setModelCapabilityCode(source.getModelCapabilityCode());
        target.setDefaultModelCode(source.getDefaultModelCode()); target.setSelectableModelCodes(source.getSelectableModelCodes());
        target.setTemperatureBasisPoints(source.getTemperatureBasisPoints()); target.setMaxTurns(source.getMaxTurns());
        target.setMaxToolCalls(source.getMaxToolCalls()); target.setCheckpointPolicy(source.getCheckpointPolicy());
        target.setInputSchemaJson(source.getInputSchemaJson()); target.setOutputSchemaJson(source.getOutputSchemaJson());
        target.setResponsePolicy(source.getResponsePolicy());
        target.setSkillBindings(source.getSkillBindings()); target.setToolBindings(source.getToolBindings());
        target.setPackageVersionCode(source.getPackageVersionCode());
    }

    private AidRuntimeAgent requireAgent(Long id)
    {
        AidRuntimeAgent agent = id == null ? null : agents.selectById(id);
        if (agent == null || !NORMAL.equals(agent.getDelFlag())) throw new ServiceException("Runtime Agent不存在");
        requireAssistantFamily(agent);
        return agent;
    }

    private AidRuntimeAgent requireAgentForUpdate(Long id)
    {
        AidRuntimeAgent agent = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .eq(AidRuntimeAgent::getId, id)
                .eq(AidRuntimeAgent::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (agent == null) throw new ServiceException("Runtime Agent不存在");
        requireAssistantFamily(agent);
        return agent;
    }

    private AidRuntimeAgent requireAgentIdentityForUpdate(Long id)
    {
        AidRuntimeAgent agent = id == null ? null : agents.selectOne(
                Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .eq(AidRuntimeAgent::getId, id).last("FOR UPDATE"));
        if (agent == null) throw new ServiceException("Runtime Agent不存在");
        requireAssistantFamily(agent);
        return agent;
    }

    private void ensureRemovable(AidRuntimeAgent agent)
    {
        AidRuntimeModuleAgentBinding activeBinding = moduleBindingMapper.selectOne(
                Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                        .select(AidRuntimeModuleAgentBinding::getId)
                        .eq(AidRuntimeModuleAgentBinding::getRuntimeAgentId, agent.getId())
                        .eq(AidRuntimeModuleAgentBinding::getStatus, ACTIVE)
                        .eq(AidRuntimeModuleAgentBinding::getDelFlag, NORMAL)
                        .last("LIMIT 1 FOR UPDATE"));
        if (activeBinding != null)
            throw new ServiceException("Agent仍被助手模块启用，不能删除", 409);

        List<Long> versionIds = versions.selectList(Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                        .select(AidRuntimeAgentVersion::getId)
                        .eq(AidRuntimeAgentVersion::getAgentId, agent.getId()))
                .stream().map(AidRuntimeAgentVersion::getId).filter(Objects::nonNull).toList();
        if (versionIds.isEmpty()) return;

        AidRuntimeRun unclosed = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .select(AidRuntimeRun::getId, AidRuntimeRun::getStatus)
                .in(AidRuntimeRun::getVersionId, versionIds)
                .eq(AidRuntimeRun::getDelFlag, NORMAL)
                .and(q -> q.isNull(AidRuntimeRun::getStatus)
                        .or().notIn(AidRuntimeRun::getStatus, TERMINAL_RUN_STATUSES))
                .last("LIMIT 1 FOR UPDATE"));
        if (unclosed != null)
            throw new ServiceException("Agent仍有未收口运行，不能删除", 409);

        List<Long> runIds = runs.selectList(Wrappers.<AidRuntimeRun>lambdaQuery()
                        .select(AidRuntimeRun::getId)
                        .in(AidRuntimeRun::getVersionId, versionIds)
                        .eq(AidRuntimeRun::getDelFlag, NORMAL))
                .stream().map(AidRuntimeRun::getId).filter(Objects::nonNull).toList();
        for (int start = 0; start < runIds.size(); start += 500)
        {
            List<Long> slice = runIds.subList(start, Math.min(start + 500, runIds.size()));
            AidRuntimeRunStep unsettled = runSteps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getStatus,
                            AidRuntimeRunStep::getProviderAcceptanceState)
                    .in(AidRuntimeRunStep::getRunId, slice)
                    .eq(AidRuntimeRunStep::getDelFlag, NORMAL)
                    .and(q -> q.eq(AidRuntimeRunStep::getProviderAcceptanceState, "UNKNOWN")
                            .or(accepted -> accepted
                                    .in(AidRuntimeRunStep::getProviderAcceptanceState,
                                            List.of("SUBMITTING", "ACCEPTED"))
                                    .and(state -> state.isNull(AidRuntimeRunStep::getStatus)
                                            .or().notIn(AidRuntimeRunStep::getStatus,
                                                    TERMINAL_STEP_STATUSES))))
                    .last("LIMIT 1 FOR UPDATE"));
            if (unsettled != null)
                throw new ServiceException("Agent仍有已受理或状态未知的任务，不能删除", 409);
        }
    }

    private void ensureIdentityChanged(int changed)
    {
        if (changed != 1) throw new ServiceException("Runtime Agent身份已并发变更", 409);
    }

    private RuntimeAgentViews.IdentityStateView identityState(Long id, String status,
                                                               String delFlag, Date updateTime)
    {
        RuntimeAgentViews.IdentityStateView view = new RuntimeAgentViews.IdentityStateView();
        view.setId(id); view.setStatus(status); view.setDelFlag(delFlag); view.setUpdateTime(updateTime);
        return view;
    }

    private AidRuntimeAgentVersion requireVersion(Long id)
    {
        AidRuntimeAgentVersion version = id == null ? null : versions.selectById(id);
        if (version == null || !NORMAL.equals(version.getDelFlag())) throw new ServiceException("Agent版本不存在");
        requireAgent(version.getAgentId());
        return version;
    }

    private void requireAssistantFamily(AidRuntimeAgent agent)
    {
        if (agent == null || !ASSISTANT_FAMILY.equals(agent.getAgentFamily()))
            throw new ServiceException("AGENT_FAMILY_MISMATCH", 409);
    }

    private void requireAvailableCode(String agentCode)
    {
        AidRuntimeAgent existing = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentFamily)
                .eq(AidRuntimeAgent::getAgentCode, StrUtil.trim(agentCode)).last("LIMIT 1"));
        if (existing == null) return;
        if (!ASSISTANT_FAMILY.equals(existing.getAgentFamily()))
            throw new ServiceException("AGENT_CODE_FAMILY_CONFLICT", 409);
        throw new ServiceException("Agent code already exists", 409);
    }

    private AidRuntimeAgentVersion requireOwnedVersion(Long agentId, Long versionId)
    {
        AidRuntimeAgentVersion version = requireVersion(versionId);
        if (!Objects.equals(agentId, version.getAgentId())) throw new ServiceException("Agent版本归属不一致");
        return version;
    }

    private Map<Long, AidRuntimeAgentVersion> versionMap(List<Long> ids)
    {
        if (ids.isEmpty()) return Map.of();
        return versions.selectBatchIds(ids).stream().collect(Collectors.toMap(
                AidRuntimeAgentVersion::getId, Function.identity(), (left, right) -> left));
    }

    /** Build one strict, request-local validation snapshot for the governance page. */
    private ListValidationContext listValidationContext(List<AidRuntimeAgent> pageAgents,
                                                        Map<Long, AidRuntimeAgentVersion> currentVersions)
    {
        Map<Long, AidRuntimeAgent> agentById = pageAgents.stream().collect(Collectors.toMap(
                AidRuntimeAgent::getId, Function.identity(), (left, right) -> left));
        Map<Long, RuntimeAgentRequests.DraftPayload> definitions = new LinkedHashMap<>();
        Map<Long, AssistantAgentModelReadinessService.Verification> modelVerifications = new LinkedHashMap<>();
        for (AidRuntimeAgentVersion version : currentVersions.values())
        {
            try
            {
                definitions.put(version.getId(), rawDefinition(version));
            }
            catch (RuntimeException ignored)
            {
                /* The row remains UNAVAILABLE; other rows continue to use their verified snapshots. */
            }
        }
        Set<String> agentModelCodes = definitions.values().stream()
                .flatMap(body -> safe(body.getSelectableModelCodes()).stream()).collect(Collectors.toSet());
        Map<String, com.aid.aid.domain.AidAiModel> activeAgentModels;
        try { activeAgentModels = modelService.activeModels(agentModelCodes, TEXT_OUTPUT_KIND); }
        catch (RuntimeException unavailable) { activeAgentModels = Map.of(); }
        for (AidRuntimeAgentVersion version : currentVersions.values())
        {
            if (!definitions.containsKey(version.getId())) continue;
            try
            {
                modelVerifications.put(version.getId(), modelReadiness.verifyFrozen(
                        version.getModelConfigJson(), version.getDefinitionJson(), activeAgentModels));
            }
            catch (RuntimeException ignored)
            {
                /* The row remains UNAVAILABLE; other rows continue to use their verified snapshots. */
            }
        }
        Set<Long> agentVersionIds = currentVersions.keySet();
        Map<Long, List<AidRuntimeAgentSkillRef>> refsByVersion = agentVersionIds.isEmpty() ? Map.of()
                : skillRefs.selectList(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                        .in(AidRuntimeAgentSkillRef::getAgentVersionId, agentVersionIds)).stream()
                .collect(Collectors.groupingBy(AidRuntimeAgentSkillRef::getAgentVersionId));
        Map<Long, List<AidRuntimeAgentToolRef>> toolsByVersion = agentVersionIds.isEmpty() ? Map.of()
                : toolRefs.selectList(Wrappers.<AidRuntimeAgentToolRef>lambdaQuery()
                        .in(AidRuntimeAgentToolRef::getAgentVersionId, agentVersionIds)).stream()
                .collect(Collectors.groupingBy(AidRuntimeAgentToolRef::getAgentVersionId));
        List<RuntimeAgentRequests.SkillBinding> allBindings = definitions.values().stream()
                .flatMap(body -> safe(body.getSkillBindings()).stream()).filter(Objects::nonNull).toList();
        Set<Long> skillIds = allBindings.stream().map(RuntimeAgentRequests.SkillBinding::getSkillId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> skillVersionIds = allBindings.stream().map(RuntimeAgentRequests.SkillBinding::getSkillVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AidSkill> skillById = skillIds.isEmpty() ? Map.of()
                : skills.selectBatchIds(skillIds).stream().collect(Collectors.toMap(
                        AidSkill::getId, Function.identity(), (left, right) -> left));
        Map<Long, AidSkillVersion> skillVersionById = skillVersionIds.isEmpty() ? Map.of()
                : skillVersions.selectBatchIds(skillVersionIds).stream().collect(Collectors.toMap(
                        AidSkillVersion::getId, Function.identity(), (left, right) -> left));
        Set<SkillCoordinate> readySkills = readySkillCoordinates(allBindings, skillById, skillVersionById);
        return new ListValidationContext(Map.copyOf(agentById), Map.copyOf(definitions),
                Map.copyOf(modelVerifications), Map.copyOf(refsByVersion), Map.copyOf(toolsByVersion),
                Map.copyOf(skillById), Map.copyOf(skillVersionById), Set.copyOf(readySkills));
    }

    private Set<SkillCoordinate> readySkillCoordinates(List<RuntimeAgentRequests.SkillBinding> bindings,
                                                       Map<Long, AidSkill> skillById,
                                                       Map<Long, AidSkillVersion> versionById)
    {
        Map<Long, PackageCandidate> candidates = new LinkedHashMap<>();
        for (RuntimeAgentRequests.SkillBinding binding : bindings)
        {
            AidSkill skill = skillById.get(binding.getSkillId());
            AidSkillVersion version = versionById.get(binding.getSkillVersionId());
            if (skill != null && version != null && Objects.equals(version.getSkillId(), skill.getId())
                    && Objects.equals(binding.getSkillCode(), skill.getSkillCode())
                    && Objects.equals(binding.getPackageDigest(), version.getPackageDigest()))
                candidates.putIfAbsent(version.getId(), new PackageCandidate(skill, version));
        }
        if (candidates.isEmpty()) return Set.of();
        Set<Long> verifiedPackages = verifyPackageCandidates(new ArrayList<>(candidates.values()));
        Set<Long> candidateVersionIds = candidates.keySet();
        Set<Long> versionsWithRelations = candidateVersionIds.isEmpty() ? Set.of()
                : skillRelations.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                        .select(AidSkillRelation::getParentVersionId)
                        .in(AidSkillRelation::getParentVersionId, candidateVersionIds)
                        .eq(AidSkillRelation::getDelFlag, NORMAL)).stream()
                .map(AidSkillRelation::getParentVersionId).collect(Collectors.toSet());
        Set<String> modelCodes = candidates.values().stream()
                .flatMap(candidate -> SkillModelConfiguration.from(candidate.version())
                        .selectableModelCodes().stream()).collect(Collectors.toSet());
        Map<String, com.aid.aid.domain.AidAiModel> activeModels = modelService.activeModels(modelCodes);
        Set<SkillCoordinate> ready = new HashSet<>();
        for (RuntimeAgentRequests.SkillBinding binding : bindings)
        {
            AidSkill skill = skillById.get(binding.getSkillId());
            AidSkillVersion version = versionById.get(binding.getSkillVersionId());
            if (!baseReadySkill(skill, version, binding.getPackageDigest(), verifiedPackages, activeModels)) continue;
            String outputKind = SkillModelService.outputKind(version);
            boolean image = "IMAGE".equals(outputKind);
            boolean contractReady;
            if (image)
            {
                contractReady = "ENTRYPOINT".equals(skill.getInvocationScope())
                        && "PROMPT".equals(skill.getExecutorType())
                        && "ENTRYPOINT".equals(version.getInvocationScope())
                        && "IMAGE".equals(outputKind)
                        && AssistantImageSkillInvocationResolver.hasStrictSchemas(version)
                        && imageCapabilityMatches(skill, version);
            }
            else
            {
                contractReady = "TEXT".equals(outputKind)
                        && Set.of("INTERNAL", "ENTRYPOINT").contains(version.getInvocationScope())
                        && !versionsWithRelations.contains(version.getId());
            }
            if (contractReady) ready.add(new SkillCoordinate(
                    binding.getSkillId(), binding.getSkillVersionId(), binding.getPackageDigest()));
        }
        return ready;
    }

    private boolean baseReadySkill(AidSkill skill, AidSkillVersion version, String expectedDigest,
                                   Set<Long> verifiedPackages,
                                   Map<String, com.aid.aid.domain.AidAiModel> activeModels)
    {
        if (skill == null || version == null || !NORMAL.equals(skill.getStatus())
                || !NORMAL.equals(skill.getDelFlag()) || !"PLATFORM".equals(skill.getOwnerType())
                || !"PUBLIC".equals(skill.getVisibility()) || !Objects.equals(version.getSkillId(), skill.getId())
                || !Objects.equals(expectedDigest, version.getPackageDigest())
                || !"PUBLIC".equals(version.getVisibility()) || !"PUBLISHED".equals(version.getPublishStatus())
                || !"PROMPT".equals(version.getExecutorType()) || !NORMAL.equals(version.getStatus())
                || !NORMAL.equals(version.getDelFlag()) || !verifiedPackages.contains(version.getId())) return false;
        SkillModelConfiguration config = SkillModelConfiguration.from(version);
        String outputKind = SkillModelService.outputKind(version);
        return config.isValid() && config.selectableModelCodes().stream().map(activeModels::get)
                .filter(Objects::nonNull).anyMatch(model -> SkillModelService.compatible(model, outputKind));
    }

    private boolean imageCapabilityMatches(AidSkill skill, AidSkillVersion version)
    {
        try
        {
            JSONObject definition = JSON.parseObject(version.getDefinitionJson());
            return definition != null && com.aid.skill.service.SkillImageExecutionContract
                    .supportsDefinition(version.getDefinitionJson());
        }
        catch (RuntimeException unavailable)
        {
            return false;
        }
    }

    private Set<Long> verifyPackageCandidates(List<PackageCandidate> candidates)
    {
        if (candidates.isEmpty()) return Set.of();
        List<List<PackageCandidate>> batches = new ArrayList<>();
        for (PackageCandidate candidate : candidates)
        {
            List<PackageCandidate> target = batches.stream().filter(batch -> batch.stream()
                    .noneMatch(existing -> existing.skill().getSkillCode().equals(candidate.skill().getSkillCode())))
                    .findFirst().orElseGet(() -> { List<PackageCandidate> batch = new ArrayList<>();
                        batches.add(batch); return batch; });
            target.add(candidate);
        }
        Set<Long> verified = new HashSet<>();
        batches.forEach(batch -> verifyPackageBatch(batch, verified));
        return verified;
    }

    private void verifyPackageBatch(List<PackageCandidate> candidates, Set<Long> verified)
    {
        if (candidates.isEmpty()) return;
        Map<String, AidSkillVersion> packages = candidates.stream().collect(Collectors.toMap(
                candidate -> candidate.skill().getSkillCode(), PackageCandidate::version,
                (left, right) -> left, LinkedHashMap::new));
        try
        {
            packageResourceLoader.verifyVersions(packages);
            candidates.forEach(candidate -> verified.add(candidate.version().getId()));
        }
        catch (RuntimeException unavailable)
        {
            if (candidates.size() == 1) return;
            int middle = candidates.size() / 2;
            verifyPackageBatch(candidates.subList(0, middle), verified);
            verifyPackageBatch(candidates.subList(middle, candidates.size()), verified);
        }
    }

    private record SkillCoordinate(Long skillId, Long versionId, String packageDigest) { }
    private record PackageCandidate(AidSkill skill, AidSkillVersion version) { }
    private record ListValidationContext(
            Map<Long, AidRuntimeAgent> agents,
            Map<Long, RuntimeAgentRequests.DraftPayload> definitions,
            Map<Long, AssistantAgentModelReadinessService.Verification> modelVerifications,
            Map<Long, List<AidRuntimeAgentSkillRef>> skillRefs,
            Map<Long, List<AidRuntimeAgentToolRef>> toolRefs,
            Map<Long, AidSkill> skills,
            Map<Long, AidSkillVersion> skillVersions,
            Set<SkillCoordinate> readySkills) { }

    private boolean jsonObject(String value)
    {
        try { return JSON.parseObject(value) != null; }
        catch (RuntimeException error) { return false; }
    }

    private static RuntimeAgentViews.Issue issue(String field, String message)
    {
        return new RuntimeAgentViews.Issue(field, message);
    }

    private <T> List<T> safe(List<T> list)
    {
        return list == null ? List.of() : list;
    }

    private void normalizeLegacyStoredPackage(RuntimeAgentRequests.DraftPayload body)
    {
        if (body == null) return;
        if (StrUtil.isBlank(body.getSystemPrompt()) && StrUtil.isNotBlank(body.getInstruction()))
            body.setSystemPrompt(body.getInstruction());
        if (StrUtil.isBlank(body.getInstruction()) && StrUtil.isNotBlank(body.getSystemPrompt()))
            body.setInstruction(body.getSystemPrompt());
        if (StrUtil.isBlank(body.getMainMarkdown()))
            body.setMainMarkdown("# Agent\n\n该历史版本发布时尚未保存独立主文件；请在下一版本补齐。\n");
        if (body.getResources() == null) body.setResources(new ArrayList<>());
    }

    private boolean safeAgentReferencePath(String path)
    {
        return path != null && path.matches("references/[a-z0-9][a-z0-9._/-]{0,180}")
                && !path.contains("..") && !path.contains("//");
    }

    private String builtinPackageVersionCode(BuiltinDefinition definition)
    {
        try
        {
            JSONObject manifest = JSON.parseObject(readResource(definition.manifestResource()));
            String version = manifest == null ? null : manifest.getString("version");
            if (manifest == null || !definition.agentCode().equals(manifest.getString("code"))
                    || !validVersion(version))
                throw new ServiceException("内置Agent资源不匹配");
            return version;
        }
        catch (ServiceException error)
        {
            throw error;
        }
        catch (RuntimeException error)
        {
            throw new ServiceException("内置Agent资源无效");
        }
    }

    private BuiltinDraft builtinDraft(BuiltinDefinition definition)
    {
        JSONObject manifest;
        try { manifest = JSON.parseObject(readResource(definition.manifestResource())); }
        catch (RuntimeException error) { throw new ServiceException("内置Agent资源无效"); }
        if (manifest == null || !Objects.equals(1, manifest.getInteger("formatVersion"))
                || !definition.agentCode().equals(manifest.getString("code"))
                || !validVersion(manifest.getString("version"))
                || !RuntimeAgentRequests.ASSISTANT_LOOP.equals(manifest.getString("executorType"))
                || !RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1.equals(manifest.getString("protocolVersion")))
            throw new ServiceException("内置Agent资源不匹配");
        String base = definition.manifestResource().substring(
                0, definition.manifestResource().length() - "manifest.json".length());
        JSONObject entry = manifest.getJSONObject("entry");
        if (entry == null || !"SP.md".equals(entry.getString("path")))
            throw new ServiceException("内置Agent入口无效");
        String systemPrompt = verifiedBuiltinFile(base, entry, "内置Agent SP.md 摘要无效");
        JSONObject manifestPolicy = manifest.getJSONObject("responsePolicy");
        if (manifestPolicy == null || !RuntimeAgentRequests.NATIVE_TOOL_STREAM.equals(
                manifestPolicy.getString("mode"))
                || !Objects.equals(0, manifestPolicy.getInteger("maxRepairAttempts")))
            throw new ServiceException("内置Agent响应策略无效");
        JSONObject schemas = manifest.getJSONObject("schemas");
        if (schemas == null) throw new ServiceException("内置Agent Schema缺失");
        String inputSchema = verifiedBuiltinFile(base, schemas.getJSONObject("input"),
                "内置Agent输入Schema摘要无效");
        String outputSchema = verifiedBuiltinFile(base, schemas.getJSONObject("output"),
                "内置Agent输出Schema摘要无效");
        if (!jsonObject(inputSchema) || !jsonObject(outputSchema))
            throw new ServiceException("内置Agent Schema无效");

        List<String> modelCodes = nativeToolModelCodes();
        if (modelCodes.isEmpty()) throw new ServiceException("没有可用的原生流式工具文本模型");
        RuntimeAgentRequests.DraftPayload body = new RuntimeAgentRequests.DraftPayload();
        body.setMainMarkdown(systemPrompt); body.setSystemPrompt(systemPrompt); body.setInstruction(systemPrompt);
        List<RuntimeAgentRequests.PackageResource> packageResources = new ArrayList<>();
        JSONArray manifestResources = manifest.getJSONArray("resources");
        if (manifestResources == null || manifestResources.size() > 64)
            throw new ServiceException("内置Agent参考资源无效");
        for (Object raw : manifestResources)
        {
            if (!(raw instanceof JSONObject item) || !safeAgentReferencePath(item.getString("path")))
                throw new ServiceException("内置Agent参考资源无效");
            String content = verifiedBuiltinFile(base, item, "内置Agent参考资源摘要无效");
            RuntimeAgentRequests.PackageResource resource = new RuntimeAgentRequests.PackageResource();
            resource.setResourceKey(item.getString("key")); resource.setFilePath(item.getString("path"));
            resource.setMimeType(StrUtil.blankToDefault(item.getString("mimeType"), "text/markdown"));
            resource.setContent(content);
            JSONObject route = item.getJSONObject("route");
            if (route == null) route = new JSONObject();
            resource.setRouteJson(route.toJSONString()); packageResources.add(resource);
        }
        body.setResources(List.copyOf(packageResources));
        body.setExecutorType(RuntimeAgentRequests.ASSISTANT_LOOP);
        body.setProtocolVersion(RuntimeAgentRequests.ASSISTANT_LOOP_NATIVE_V1);
        body.setModelCapabilityCode(TEXT_CHAT); body.setDefaultModelCode(modelCodes.get(0));
        body.setSelectableModelCodes(modelCodes);
        body.setCheckpointPolicy("TOOL_BOUNDARY");
        body.setInputSchemaJson(inputSchema);
        body.setOutputSchemaJson(outputSchema);
        RuntimeAgentRequests.ResponsePolicy policy = new RuntimeAgentRequests.ResponsePolicy();
        policy.setMode(RuntimeAgentRequests.NATIVE_TOOL_STREAM); policy.setMaxRepairAttempts(0);
        policy.setNativeToolBindingsSchemaVersion(NativeAssistantBindingPolicy.explicitSelector(manifest));
        body.setResponsePolicy(policy);
        body.setSkillBindings(builtinSkillBindings(manifestCodes(manifest, "skillCodes",
                RuntimeAgentRequests.MAX_SKILL_BINDINGS)));
        body.setToolBindings(builtinToolBindings(manifestCodes(manifest, "toolCodes",
                RuntimeAgentRequests.MAX_TOOL_BINDINGS)));
        body.setPackageVersionCode(manifest.getString("version"));
        return new BuiltinDraft(manifest.getString("name"), body);
    }

    private List<RuntimeAgentRequests.SkillBinding> builtinSkillBindings(List<String> requiredCodes)
    {
        List<AidSkill> identities = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .in(AidSkill::getSkillCode, requiredCodes)
                .eq(AidSkill::getOwnerType, "PLATFORM")
                .eq(AidSkill::getVisibility, "PUBLIC")
                .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL));
        Map<String, AidSkill> byCode = identities.stream().collect(Collectors.toMap(
                AidSkill::getSkillCode, Function.identity(), (left, right) -> left));
        if (byCode.size() != requiredCodes.size())
            throw new ServiceException("内置Skill尚未准备完成");
        List<Long> currentVersionIds = identities.stream().map(AidSkill::getCurrentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AidSkillVersion> byVersionId = currentVersionIds.isEmpty() ? Map.of()
                : skillVersions.selectBatchIds(currentVersionIds).stream().collect(Collectors.toMap(
                        AidSkillVersion::getId, Function.identity(), (left, right) -> left));
        List<RuntimeAgentRequests.SkillBinding> result = new ArrayList<>();
        for (String code : requiredCodes)
        {
            AidSkill skill = byCode.get(code);
            Long currentVersionId = skill.getCurrentVersionId();
            AidSkillVersion version = currentVersionId == null ? null : byVersionId.get(currentVersionId);
            if (version == null || !Objects.equals(version.getSkillId(), skill.getId())
                    || !Objects.equals(currentVersionId, version.getId())
                    || StrUtil.isBlank(version.getPackageDigest()))
                throw new ServiceException("内置Skill固定版本缺失");
            if (!publicAgentSkillBindingReady(skill, version, version.getPackageDigest()))
                throw new ServiceException("内置Skill固定版本未达到公共Agent绑定条件");
            RuntimeAgentRequests.SkillBinding binding = new RuntimeAgentRequests.SkillBinding();
            binding.setSkillId(skill.getId()); binding.setSkillVersionId(version.getId());
            binding.setSkillCode(code); binding.setPackageDigest(version.getPackageDigest()); result.add(binding);
        }
        return result;
    }

    private List<RuntimeAgentRequests.ToolBinding> builtinToolBindings(List<String> requiredCodes)
    {
        Map<String, RuntimeToolRegistry.Descriptor> registered = tools.options().stream()
                .collect(Collectors.toMap(RuntimeToolRegistry.Descriptor::code, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<RuntimeAgentRequests.ToolBinding> result = new ArrayList<>();
        for (String code : requiredCodes)
        {
            RuntimeToolRegistry.Descriptor descriptor = registered.get(code);
            if (descriptor == null) throw new ServiceException("内置Agent Tool尚未注册: " + code);
            RuntimeAgentRequests.ToolBinding binding = new RuntimeAgentRequests.ToolBinding();
            binding.setToolCode(descriptor.code()); binding.setContractVersion(descriptor.contractVersion());
            binding.setSchemaDigest(descriptor.schemaDigest()); result.add(binding);
        }
        return result;
    }

    private List<String> nativeToolModelCodes()
    {
        List<String> result = new ArrayList<>();
        for (SkillAdminVO.TextModelOption option : modelService.adminOptionsForOutputKind(TEXT_OUTPUT_KIND))
        {
            if (option == null || !Boolean.TRUE.equals(option.getAvailable())
                    || StrUtil.isBlank(option.getModelCode())) continue;
            AssistantAgentModelReadinessService.Verification verification = modelReadiness.verify(
                    TEXT_CHAT, option.getModelCode(), List.of(option.getModelCode()),
                    RuntimeAgentRequests.NATIVE_TOOL_STREAM, 0);
            if (verification.ready() && !result.contains(option.getModelCode()))
            {
                result.add(option.getModelCode());
                if (result.size() == 20) break;
            }
        }
        return List.copyOf(result);
    }

    private List<String> manifestCodes(JSONObject manifest, String field, int maximum)
    {
        JSONArray raw = manifest == null ? null : manifest.getJSONArray(field);
        if (raw == null || raw.isEmpty() || raw.size() > maximum)
            throw new ServiceException("内置Agent绑定清单无效: " + field);
        List<String> result = new ArrayList<>(raw.size());
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < raw.size(); index++)
        {
            Object value = raw.get(index);
            if (!(value instanceof String code) || !code.matches("[a-z][a-z0-9._-]{1,99}")
                    || !unique.add(code))
                throw new ServiceException("内置Agent绑定清单无效: " + field);
            result.add(code);
        }
        return List.copyOf(result);
    }

    private String verifiedBuiltinFile(String base, JSONObject descriptor, String errorMessage)
    {
        if (descriptor == null || !safeBuiltinFilePath(descriptor.getString("path"))
                || descriptor.getString("digest") == null
                || !descriptor.getString("digest").matches("[0-9a-f]{64}"))
            throw new ServiceException(errorMessage);
        String content = readResource(base + descriptor.getString("path"));
        if (!Objects.equals(descriptor.getString("digest"), SecureUtil.sha256(content)))
            throw new ServiceException(errorMessage);
        return content;
    }

    private boolean safeBuiltinFilePath(String path)
    {
        return path != null && ("SP.md".equals(path)
                || path.matches("(?:references|schemas)/[a-z0-9][a-z0-9._/-]{0,180}"))
                && !path.contains("..") && !path.contains("//");
    }

    private boolean validVersion(String version)
    {
        return version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?");
    }
    private RuntimeAgentViews.BuiltinImportView builtinImportView(AidRuntimeAgent agent, Long actorId,
                                                                   String state, String message,
                                                                   String builtinPackageVersionCode)
    {
        AidRuntimeAgentDraft draft = actorId == null ? null : drafts.selectOne(
                Wrappers.<AidRuntimeAgentDraft>lambdaQuery()
                        .eq(AidRuntimeAgentDraft::getAgentId, agent.getId())
                        .eq(AidRuntimeAgentDraft::getOwnerUserId, actorId)
                        .eq(AidRuntimeAgentDraft::getStatus, EDITING).last("LIMIT 1"));
        return builtinImportView(agent, draft, state, message, builtinPackageVersionCode);
    }

    private RuntimeAgentViews.BuiltinImportView builtinImportView(AidRuntimeAgent agent,
                                                                  AidRuntimeAgentDraft draft,
                                                                   String state, String message,
                                                                   String builtinPackageVersionCode)
    {
        RuntimeAgentViews.BuiltinImportView view = new RuntimeAgentViews.BuiltinImportView();
        view.setAgentCode(agent.getAgentCode()); view.setAgentId(agent.getId());
        view.setDraftId(draft == null ? null : draft.getId());
        view.setDraftDigest(draft == null ? null : draft.getDraftDigest());
        view.setDraftPackageVersionCode(draft == null ? null
                : parseDraft(draft.getDraftJson()).getPackageVersionCode());
        view.setBuiltinPackageVersionCode(builtinPackageVersionCode);
        view.setState(state); view.setMessage(message); return view;
    }

    private RuntimeAgentViews.ModuleBindingView moduleBindingView(String moduleCode,
                                                                  AidRuntimeModuleAgentBinding binding)
    {
        RuntimeAgentViews.ModuleBindingView view = new RuntimeAgentViews.ModuleBindingView();
        view.setModuleCode(moduleCode); view.setExpectedAgentCode(expectedAgentCode(moduleCode));
        if (binding == null)
        {
            view.setBindingRevision(0L); view.setState("UNBOUND");
            view.setRuntimeAvailability("UNAVAILABLE"); view.setRuntimeUnavailableReason("未绑定主Agent");
            return view;
        }
        view.setAgentId(binding.getRuntimeAgentId()); view.setActiveVersionId(binding.getActiveVersionId());
        view.setBindingRevision(binding.getBindingRevision()); view.setState("BOUND");
        AidRuntimeAgent agent = binding.getRuntimeAgentId() == null ? null : agents.selectById(binding.getRuntimeAgentId());
        AidRuntimeAgentVersion version = binding.getActiveVersionId() == null ? null
                : versions.selectById(binding.getActiveVersionId());
        String invalid = null;
        if (agent == null || !ASSISTANT_FAMILY.equals(agent.getAgentFamily())
                || !NORMAL.equals(agent.getDelFlag()) || !NORMAL.equals(agent.getStatus()))
            invalid = "Agent未启用";
        else if (!expectedAgentCode(moduleCode).equals(agent.getAgentCode())) invalid = "模块Agent不匹配";
        else if (version == null || !NORMAL.equals(version.getDelFlag())
                || !Objects.equals(version.getAgentId(), agent.getId())
                || !Objects.equals(version.getId(), agent.getCurrentVersionId())) invalid = "绑定版本已失效";
        Availability runtime = invalid == null ? activeAvailability(version) : new Availability(false, invalid);
        view.setRuntimeAvailability(runtime.available() ? "AVAILABLE" : "UNAVAILABLE");
        view.setRuntimeUnavailableReason(runtime.reason()); return view;
    }

    private String expectedAgentCode(String moduleCode)
    {
        if (moduleRegistry != null && moduleRegistry.agentCode(moduleCode) != null) return moduleRegistry.agentCode(moduleCode);
        if (UNIVERSAL.equals(moduleCode)) return UNIVERSAL_AGENT;
        if (SCREENWRITER.equals(moduleCode)) return SCREENWRITER_AGENT;
        if (SPATIAL_PREVIS.equals(moduleCode)) return SPATIAL_PREVIS_AGENT;
        throw new ServiceException("模块不存在");
    }

    private String readResource(String path)
    {
        try (var input = new ClassPathResource(path).getInputStream())
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException error)
        {
            throw new ServiceException("内置Agent资源缺失");
        }
    }

    private String activeKey(Long agentId, Long actorId)
    {
        return agentId + ":" + actorId;
    }

    private void touch(AidRuntimeAgent agent, String actor, boolean create)
    {
        Date now = new Date();
        if (create) { agent.setCreateBy(actor); agent.setCreateTime(now); }
        agent.setUpdateBy(actor); agent.setUpdateTime(now);
    }

    private record Availability(boolean available, String reason)
    {
    }

    private record BuiltinDefinition(String agentCode, String moduleCode, String manifestResource)
    {
    }

    private record BuiltinDraft(String name, RuntimeAgentRequests.DraftPayload payload)
    {
    }
    private boolean supportsContextPreparation(RuntimeAgentRequests.DraftPayload draft) {
        return RuntimeAgentRequests.ASSISTANT_LOOP.equals(draft.getExecutorType())
                && loopExecutors.isAvailable(draft.getProtocolVersion())
                && loopExecutors.require(draft.getProtocolVersion()).supportsContextPreparation();
    }

}
