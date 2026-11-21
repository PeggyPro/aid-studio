package com.aid.runtime.legacy.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.legacy.service.LegacyRuntimeAgentAdminService;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.service.GenericRuntimeService;
import com.aid.runtime.dto.GenericRuntimeRequests;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/aid/runtime-plan-agent")
public class LegacyRuntimeAgentAdminController {
    private final LegacyRuntimeAgentAdminService service;
    private final RuntimeToolRegistry tools;
    private final GenericRuntimeService runtime;

    @ModelAttribute
    public void requireLegacyFamily(
            @RequestHeader(value = LegacyRuntimeAgentRequests.ADMIN_FAMILY_HEADER, required = false) String family) {
        if (!LegacyRuntimeAgentRequests.LEGACY_PLAN.equals(family))
            throw new ServiceException("Agent协议不匹配", 409);
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/draft/try-run")
    public AjaxResult tryDraft(@Valid @RequestBody GenericRuntimeRequests.DraftTryRun request) {
        return AjaxResult.success(runtime.tryDraft(request, "AGENT", SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/model/options")
    public AjaxResult modelOptions() { return AjaxResult.success(service.modelOptions()); }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/skill/options")
    public AjaxResult skillOptions() { return AjaxResult.success(service.skillOptions(SecurityUtils.getUserId())); }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody(required = false) LegacyRuntimeAgentRequests.Page request) {
        return page(service.page(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody LegacyRuntimeAgentRequests.Id request) {
        return AjaxResult.success(service.detail(request.getId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/create")
    public AjaxResult create(@Valid @RequestBody LegacyRuntimeAgentRequests.Create request) {
        return AjaxResult.success(service.create(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername(), true));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/identity/edit")
    public AjaxResult edit(@Valid @RequestBody LegacyRuntimeAgentRequests.Identity request) {
        service.editIdentity(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/status")
    public AjaxResult status(@Valid @RequestBody LegacyRuntimeAgentRequests.Status request) {
        service.status(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:remove')")
    @PostMapping("/remove")
    public AjaxResult remove(@Valid @RequestBody LegacyRuntimeAgentRequests.Id request) {
        service.remove(request.getId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/version/list")
    public AjaxResult versions(@Valid @RequestBody LegacyRuntimeAgentRequests.VersionPage request) {
        return page(service.versions(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/version/detail")
    public AjaxResult version(@Valid @RequestBody LegacyRuntimeAgentRequests.Id request) {
        return AjaxResult.success(service.version(request.getId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/draft/detail")
    public AjaxResult draft(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftDetail request) {
        return AjaxResult.success(service.draft(request.getAgentId(), request.getBaseVersionId(),
                SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/draft/save")
    public AjaxResult saveDraft(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftSave request) {
        return AjaxResult.success(service.saveDraft(request, request.getDraftId(), request.getDraftDigest(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/draft/validate")
    public AjaxResult validate(@Valid @RequestBody LegacyRuntimeAgentRequests.DraftPayload request) {
        return AjaxResult.success(service.validate(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/draft/publish")
    public AjaxResult publish(@Valid @RequestBody LegacyRuntimeAgentRequests.Publish request) {
        return AjaxResult.success(service.publish(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:edit')")
    @PostMapping("/version/activate")
    public AjaxResult activate(@Valid @RequestBody LegacyRuntimeAgentRequests.Activate request) {
        service.activate(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:query')")
    @PostMapping("/tool/options")
    public AjaxResult toolOptions() { return AjaxResult.success(tools.options()); }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:run:list')")
    @PostMapping("/run/list")
    public AjaxResult runs(@Valid @RequestBody(required = false) LegacyRuntimeAgentRequests.RunPage request) {
        return page(service.runs(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:runtimeAgent:run:query')")
    @PostMapping("/run/detail")
    public AjaxResult run(@Valid @RequestBody LegacyRuntimeAgentRequests.Id request) {
        return AjaxResult.success(runtime.adminRun(request.getId(), "AGENT"));
    }

    private static AjaxResult page(Page<?> source) {
        AjaxResult result = AjaxResult.success();
        result.put("total", source.getTotal());
        result.put("data", source.getRecords());
        return result;
    }
}
