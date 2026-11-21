package com.aid.runtime.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentDraft;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.legacy.service.LegacyRuntimeAgentAdminService;
import com.aid.runtime.mapper.AidRuntimeAgentDraftMapper;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.service.ISkillAdminService;
import com.aid.skill.service.ISkillPackageAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** Personal private definitions. Publication to the shared catalog is a separate review boundary. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/runtime/creator")
public class RuntimeCreatorController {
    private final ISkillAdminService skillAdmin;
    private final ISkillPackageAdminService skillPackages;
    private final AidSkillMapper skills;
    private final AidSkillDraftMapper skillDrafts;
    private final LegacyRuntimeAgentAdminService agents;
    private final AidRuntimeAgentDraftMapper agentDrafts;
    private final AidRuntimeAgentMapper agentMapper;

    @PostMapping("/skill/list")
    public AjaxResult ownSkills(@Valid @RequestBody(required = false)
                                SkillAdminRequests.PageRequest request) {
        SkillAdminRequests.PageRequest source = request == null
                ? new SkillAdminRequests.PageRequest() : request;
        if (source.getStatus() != null && !Set.of("0", "1").contains(source.getStatus()))
            throw new ServiceException("Skill 状态无效");
        if (source.getKeyword() != null && source.getKeyword().length() > 100)
            throw new ServiceException("搜索词过长");
        int pageNum = source.getPageNum() == null ? 1 : source.getPageNum();
        int pageSize = source.getPageSize() == null ? 20 : source.getPageSize();
        Page<AidSkill> page = skills.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getDescription, AidSkill::getIconUrl,
                                AidSkill::getVisibility, AidSkill::getInvocationScope,
                                AidSkill::getExecutorType, AidSkill::getModelCode,
                                AidSkill::getCurrentVersionId, AidSkill::getStatus,
                                AidSkill::getUpdateTime)
                        .eq(AidSkill::getOwnerType, "USER")
                        .eq(AidSkill::getOwnerUserId, SecurityUtils.getUserId())
                        .eq(AidSkill::getDelFlag, "0")
                        .eq(source.getStatus() != null, AidSkill::getStatus, source.getStatus())
                        .and(source.getKeyword() != null && !source.getKeyword().isBlank(), q -> q
                                .like(AidSkill::getName, source.getKeyword())
                                .or().like(AidSkill::getSkillCode, source.getKeyword()))
                        .orderByDesc(AidSkill::getId));
        return AjaxResult.success(ownPage(page));
    }

    @PostMapping("/skill/version/list")
    public AjaxResult ownSkillVersions(@Valid @RequestBody
                                       SkillPackageAdminRequests.VersionPageRequest request) {
        requireOwnedSkill(request.getSkillId());
        if (request.getPageNum() == null) request.setPageNum(1);
        if (request.getPageSize() == null) request.setPageSize(20);
        var versions = skillPackages.listVersions(request);
        JSONObject result = new JSONObject();
        result.put("items", versions.getData());
        result.put("total", versions.getTotal());
        result.put("pageNum", request.getPageNum());
        result.put("pageSize", request.getPageSize());
        result.put("currentVersionId", versions.getCurrentVersionId());
        return AjaxResult.success(result);
    }

    @PostMapping("/agent/list")
    public AjaxResult ownAgents(@Valid @RequestBody(required = false)
                                LegacyRuntimeAgentRequests.Page request) {
        LegacyRuntimeAgentRequests.Page source = request == null
                ? new LegacyRuntimeAgentRequests.Page() : request;
        int pageNum = source.getPageNum() == null ? 1 : source.getPageNum();
        int pageSize = source.getPageSize() == null ? 20 : source.getPageSize();
        Page<AidRuntimeAgent> page = agentMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentCode,
                                AidRuntimeAgent::getName, AidRuntimeAgent::getDescription,
                                AidRuntimeAgent::getIconUrl, AidRuntimeAgent::getVisibility,
                                AidRuntimeAgent::getAgentFamily,
                                AidRuntimeAgent::getCurrentVersionId,
                                AidRuntimeAgent::getStatus, AidRuntimeAgent::getUpdateTime)
                        .eq(AidRuntimeAgent::getOwnerType, "USER")
                        .eq(AidRuntimeAgent::getOwnerUserId, SecurityUtils.getUserId())
                        .eq(AidRuntimeAgent::getAgentFamily, LegacyRuntimeAgentRequests.LEGACY_PLAN)
                        .eq(AidRuntimeAgent::getDelFlag, "0")
                        .eq(source.getStatus() != null, AidRuntimeAgent::getStatus, source.getStatus())
                        .and(source.getKeyword() != null && !source.getKeyword().isBlank(), q -> q
                                .like(AidRuntimeAgent::getName, source.getKeyword())
                                .or().like(AidRuntimeAgent::getAgentCode, source.getKeyword()))
                        .orderByDesc(AidRuntimeAgent::getId));
        return AjaxResult.success(ownPage(page));
    }

    @PostMapping("/agent/version/list")
    public AjaxResult ownAgentVersions(@Valid @RequestBody LegacyRuntimeAgentRequests.VersionPage request) {
        AidRuntimeAgent owner = requireOwnedAgent(request.getAgentId());
        if (request.getPageNum() == null) request.setPageNum(1);
        if (request.getPageSize() == null) request.setPageSize(20);
        Page<?> page = agents.versions(request);
        JSONObject result = ownPage(page);
        result.put("currentVersionId", owner.getCurrentVersionId());
        return AjaxResult.success(result);
    }

    private static JSONObject ownPage(Page<?> page) {
        JSONObject result = new JSONObject();
        result.put("items", page.getRecords());
        result.put("total", page.getTotal());
        result.put("pageNum", page.getCurrent());
        result.put("pageSize", page.getSize());
        return result;
    }

    @PostMapping("/skill/draft/detail")
    public AjaxResult skillDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftRequest request) {
        requireOwnedSkill(request.getSkillId());
        return AjaxResult.success(skillPackages.getDraft(request.getSkillId(), request.getBaseVersionId(),
                SecurityUtils.getUserId()));
    }

    @PostMapping("/skill/draft/save")
    public AjaxResult saveSkillDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftSaveRequest request) {
        requireOwnedSkill(request.getSkillId());
        return AjaxResult.success(skillPackages.saveDraft(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PostMapping("/skill/draft/validate")
    public AjaxResult validateSkillDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftValidateRequest request) {
        requireOwnedSkill(request.getSkillId());
        return AjaxResult.success(skillPackages.validateDraft(request));
    }

    @PostMapping("/skill/draft/publish")
    public AjaxResult publishSkill(@Valid @RequestBody SkillPackageAdminRequests.DraftPublishRequest request) {
        AidSkillDraft draft = skillDrafts.selectById(request.getDraftId());
        if (draft == null) throw new ServiceException("Skill 草稿不存在");
        requireOwnedSkill(draft.getSkillId());
        return AjaxResult.success(skillPackages.publish(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PostMapping("/skill/version/activate")
    public AjaxResult activateSkill(@Valid @RequestBody SkillPackageAdminRequests.VersionActivateRequest request) {
        requireOwnedSkill(request.getSkillId());
        skillPackages.activate(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PostMapping("/skill/status")
    public AjaxResult skillStatus(@Valid @RequestBody SkillAdminRequests.StatusRequest request) {
        requireOwnedSkill(request.getId());
        skillAdmin.updateStatus(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PostMapping("/agent/create")
    public AjaxResult createAgent(@Valid @RequestBody LegacyRuntimeAgentRequests.Create request) {
        request.setVisibility("PRIVATE");
        return AjaxResult.success(agents.create(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername(), false));
    }

    @PostMapping("/agent/draft/detail")
    public AjaxResult agentDraft(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftDetail request) {
        requireOwnedAgent(request.getAgentId());
        return AjaxResult.success(agents.draft(request.getAgentId(), request.getBaseVersionId(),
                SecurityUtils.getUserId()));
    }

    @PostMapping("/agent/draft/save")
    public AjaxResult saveAgentDraft(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftSave request) {
        requireOwnedAgent(request.getAgentId());
        return AjaxResult.success(agents.saveDraft(request, request.getDraftId(), request.getDraftDigest(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PostMapping("/agent/draft/validate")
    public AjaxResult validateAgentDraft(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftPayload request) {
        requireOwnedAgent(request.getAgentId());
        return AjaxResult.success(agents.validate(request));
    }

    @PostMapping("/agent/draft/publish")
    public AjaxResult publishAgent(@Valid @RequestBody LegacyRuntimeAgentRequests.Publish request) {
        AidRuntimeAgentDraft draft = agentDrafts.selectById(request.getDraftId());
        if (draft == null) throw new ServiceException("Agent 草稿不存在");
        requireOwnedAgent(draft.getAgentId());
        return AjaxResult.success(agents.publish(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PostMapping("/agent/version/activate")
    public AjaxResult activateAgent(@Valid @RequestBody LegacyRuntimeAgentRequests.Activate request) {
        requireOwnedAgent(request.getAgentId());
        agents.activate(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PostMapping("/agent/status")
    public AjaxResult agentStatus(@Valid @RequestBody LegacyRuntimeAgentRequests.Status request) {
        requireOwnedAgent(request.getId());
        agents.status(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private void requireOwnedSkill(Long id) {
        AidSkill skill = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getOwnerType, AidSkill::getOwnerUserId)
                .eq(AidSkill::getId, id).eq(AidSkill::getDelFlag, "0").last("limit 1"));
        if (skill == null || !"USER".equals(skill.getOwnerType())
                || !Objects.equals(skill.getOwnerUserId(), SecurityUtils.getUserId()))
            throw new ServiceException("Skill 不存在或无权编辑");
    }

    private AidRuntimeAgent requireOwnedAgent(Long id) {
        AidRuntimeAgent agent = agents.requireAgent(id);
        if (!"USER".equals(agent.getOwnerType())
                || !Objects.equals(agent.getOwnerUserId(), SecurityUtils.getUserId()))
            throw new ServiceException("Agent 不存在或无权编辑");
        return agent;
    }
}
