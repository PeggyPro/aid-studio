package com.aid.businessgroup.controller;

import com.aid.businessgroup.dto.BusinessGroupRequests;
import com.aid.businessgroup.service.BusinessGroupService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/aid/business-group")
public class BusinessGroupAdminController {
    private final BusinessGroupService service;

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:query')")
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody(required = false) BusinessGroupRequests.Page request) {
        return page(service.page(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:edit')")
    @PostMapping("/save")
    public AjaxResult save(@Valid @RequestBody BusinessGroupRequests.Save request) {
        return AjaxResult.success(service.save(request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:edit')")
    @PostMapping("/remove")
    public AjaxResult remove(@Valid @RequestBody BusinessGroupRequests.Id request) {
        service.remove(request.getId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:query')")
    @PostMapping("/member/list")
    public AjaxResult members(@Valid @RequestBody BusinessGroupRequests.MemberPage request) {
        return page(service.members(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:edit')")
    @PostMapping("/member/save")
    public AjaxResult saveMember(@Valid @RequestBody BusinessGroupRequests.MemberSave request) {
        return AjaxResult.success(service.saveMember(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:businessGroup:edit')")
    @PostMapping("/member/remove")
    public AjaxResult removeMember(@Valid @RequestBody BusinessGroupRequests.MemberRemove request) {
        service.removeMember(request, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private static AjaxResult page(Page<?> source) {
        AjaxResult result = AjaxResult.success();
        result.put("total", source.getTotal());
        result.put("data", source.getRecords());
        return result;
    }
}
