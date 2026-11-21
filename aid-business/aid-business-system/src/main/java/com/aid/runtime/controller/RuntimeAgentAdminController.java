package com.aid.runtime.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.runtime.service.RuntimeAgentAdminService;
import com.aid.skill.service.BuiltinSkillDraftPreparationService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Runtime Agent version governance. It exposes no execution or paid test endpoint. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/aid/runtime-agent")
public class RuntimeAgentAdminController
{
    private final RuntimeAgentAdminService service;
    private final BuiltinSkillDraftPreparationService builtinSkills;

    @ModelAttribute
    public void requireAssistantFamily(
            @RequestHeader(value = RuntimeAgentRequests.ADMIN_FAMILY_HEADER, required = false) String family)
    {
        if (!RuntimeAgentRequests.ASSISTANT_LOOP.equals(family))
            throw new ServiceException("Agent协议不匹配", 409);
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/builtin/skill-dependencies")
    public AjaxResult builtinSkillDependencies()
    {
        return AjaxResult.success(builtinSkills.status(SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @Log(title = "内置Skill草稿准备", businessType = BusinessType.INSERT)
    @PostMapping("/builtin/skill-dependencies/prepare")
    public AjaxResult prepareBuiltinSkillDependencies()
    {
        return AjaxResult.success(builtinSkills.prepare(SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/model/options")
    public AjaxResult modelOptions()
    {
        return AjaxResult.success(service.modelOptions());
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/skill/options")
    public AjaxResult skillOptions()
    {
        return AjaxResult.success(service.skillOptions());
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/tool/options")
    public AjaxResult toolOptions()
    {
        return AjaxResult.success(service.toolOptions());
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/builtin/import")
    public AjaxResult importBuiltin(@Valid @RequestBody RuntimeAgentRequests.BuiltinImport request)
    {
        return AjaxResult.success(service.importBuiltin(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/module-binding/list")
    public AjaxResult moduleBindings()
    {
        return AjaxResult.success(service.moduleBindings());
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:activate')")
    @PostMapping("/module-binding/activate")
    public AjaxResult activateModuleBinding(
            @Valid @RequestBody RuntimeAgentRequests.ModuleBindingActivate request)
    {
        return AjaxResult.success(service.activateModuleBinding(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:list')")
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody(required = false) RuntimeAgentRequests.Page request)
    {
        return page(service.page(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody RuntimeAgentRequests.Id request)
    {
        return AjaxResult.success(service.detail(request.getId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/create")
    public AjaxResult create(@Valid @RequestBody RuntimeAgentRequests.Create request)
    {
        return AjaxResult.success(service.create(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/identity/edit")
    public AjaxResult editIdentity(@Valid @RequestBody RuntimeAgentRequests.Identity request)
    {
        service.editIdentity(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/status")
    public AjaxResult status(@Valid @RequestBody RuntimeAgentRequests.Status request)
    {
        return AjaxResult.success(service.status(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @Log(title = "Assistant Agent", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    public AjaxResult remove(@Valid @RequestBody RuntimeAgentRequests.Id request)
    {
        return AjaxResult.success(service.remove(request.getId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @Log(title = "Assistant Agent恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore")
    public AjaxResult restore(@Valid @RequestBody RuntimeAgentRequests.Id request)
    {
        return AjaxResult.success(service.restore(request.getId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/draft/detail")
    public AjaxResult draft(@Valid @RequestBody RuntimeAgentRequests.DraftDetail request)
    {
        return AjaxResult.success(service.draft(request.getAgentId(), request.getBaseVersionId(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/draft/save")
    public AjaxResult saveDraft(@Valid @RequestBody RuntimeAgentRequests.DraftSave request)
    {
        return AjaxResult.success(service.saveDraft(request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:edit')")
    @PostMapping("/draft/validate")
    public AjaxResult validateDraft(@Valid @RequestBody RuntimeAgentRequests.DraftPayload request)
    {
        return AjaxResult.success(service.validateForActor(request, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:publish')")
    @PostMapping("/draft/publish")
    public AjaxResult publish(@Valid @RequestBody RuntimeAgentRequests.Publish request)
    {
        return AjaxResult.success(service.publish(request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/version/list")
    public AjaxResult versions(@Valid @RequestBody RuntimeAgentRequests.VersionPage request)
    {
        return page(service.versions(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:query')")
    @PostMapping("/version/detail")
    public AjaxResult version(@Valid @RequestBody RuntimeAgentRequests.Id request)
    {
        return AjaxResult.success(service.version(request.getId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:agent:activate')")
    @PostMapping("/version/activate")
    public AjaxResult activate(@Valid @RequestBody RuntimeAgentRequests.Activate request)
    {
        service.activate(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private AjaxResult page(Page<?> page)
    {
        AjaxResult result = AjaxResult.success();
        result.put("total", page.getTotal());
        result.put("data", page.getRecords());
        return result;
    }
}
