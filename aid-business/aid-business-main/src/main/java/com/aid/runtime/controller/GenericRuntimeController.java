package com.aid.runtime.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.runtime.dto.GenericRuntimeRequests;
import com.aid.runtime.dto.RuntimeCatalogRequests;
import com.aid.runtime.dto.RuntimeTestGrantRequests;
import com.aid.runtime.service.GenericRuntimeService;
import com.aid.runtime.service.RuntimeCatalogService;
import com.aid.runtime.service.RuntimeTestGrantService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/runtime")
public class GenericRuntimeController {
    private final GenericRuntimeService runtime;
    private final RuntimeCatalogService catalog;
    private final RuntimeTestGrantService testGrants;

    @PostMapping("/test-grant/create")
    public AjaxResult testGrant(@Valid @RequestBody RuntimeTestGrantRequests.Create request) {
        var grant = testGrants.create(request, SecurityUtils.getUserId());
        return AjaxResult.success(java.util.Map.of("grantId", com.aid.common.aid.json.JsonSafeId.value(grant.getId()),
                "expiresAt", grant.getExpiresAt(), "status", grant.getStatus()));
    }

    @PostMapping("/catalog/groups")
    public AjaxResult groups(@Valid @RequestBody(required = false) RuntimeCatalogRequests.GroupPage request) {
        return AjaxResult.success(catalog.groups(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/catalog/members")
    public AjaxResult members(@Valid @RequestBody RuntimeCatalogRequests.MemberPage request) {
        return AjaxResult.success(catalog.memberPage(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/catalog/models")
    public AjaxResult models(@Valid @RequestBody RuntimeCatalogRequests.ModelPage request) {
        return AjaxResult.success(catalog.modelPage(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/invoke")
    public AjaxResult invoke(@Valid @RequestBody GenericRuntimeRequests.Invoke request) {
        return AjaxResult.success(runtime.invoke(request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PostMapping("/run/detail")
    public AjaxResult run(@Valid @RequestBody GenericRuntimeRequests.RunId request) {
        return AjaxResult.success(runtime.run(request.getRunId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/run/cancel")
    public AjaxResult cancel(@Valid @RequestBody GenericRuntimeRequests.RunId request) {
        return AjaxResult.success(runtime.cancel(request.getRunId(), SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PostMapping("/run/history")
    public AjaxResult history(@Valid @RequestBody(required = false) GenericRuntimeRequests.History request) {
        Page<?> page = runtime.history(request, SecurityUtils.getUserId());
        return AjaxResult.success(java.util.Map.of("items", page.getRecords(),
                "total", page.getTotal(), "pageNum", page.getCurrent(), "pageSize", page.getSize()));
    }

    @PostMapping("/input/respond")
    public AjaxResult respond(@Valid @RequestBody GenericRuntimeRequests.Clarify request) {
        return AjaxResult.success(runtime.clarify(request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }
}
