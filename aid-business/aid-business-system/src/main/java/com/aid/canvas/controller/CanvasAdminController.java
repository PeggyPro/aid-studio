package com.aid.canvas.controller;

import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.service.CanvasAdminService;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/aid/canvas")
public class CanvasAdminController extends BaseController {
    @Resource private CanvasAdminService canvasAdminService;

    @PreAuthorize("@ss.hasPermi('aid:canvas:project:list')")
    @GetMapping("/project/list")
    public AjaxResult projects(@RequestParam(required = false) String projectName,
                               @RequestParam(required = false) Long ownerUserId,
                               @RequestParam(required = false) Integer status,
                               @RequestParam(defaultValue = "1") int pageNum,
                               @RequestParam(defaultValue = "20") int pageSize) {
        return success(canvasAdminService.projects(projectName, ownerUserId, status, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:project:query')")
    @GetMapping("/project/{projectId}")
    public AjaxResult project(@PathVariable Long projectId) { return success(canvasAdminService.projectDetail(projectId)); }

    @PreAuthorize("@ss.hasPermi('aid:canvas:config:query')")
    @GetMapping("/config")
    public AjaxResult config() { return success(canvasAdminService.config()); }

    @Log(title = "画布协作配置", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('aid:canvas:config:edit')")
    @PutMapping("/config")
    public AjaxResult updateConfig(@Valid @RequestBody CanvasRequests.AdminConfig request) {
        canvasAdminService.updateConfig(request, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:entitlement:list')")
    @GetMapping("/entitlement/default")
    public AjaxResult defaults() { return success(canvasAdminService.defaultEntitlements()); }

    @Log(title = "画布默认权益", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('aid:canvas:entitlement:edit')")
    @PutMapping("/entitlement/default")
    public AjaxResult updateDefaults(@Valid @RequestBody CanvasRequests.DefaultEntitlements request) {
        canvasAdminService.updateDefaultEntitlements(request, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:entitlement:list')")
    @GetMapping("/entitlement/override/list")
    public AjaxResult overrides(@RequestParam(required = false) Long userId,
                                @RequestParam(required = false) String entitlementCode,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "1") int pageNum,
                                @RequestParam(defaultValue = "20") int pageSize) {
        return success(canvasAdminService.overrides(userId, entitlementCode, status, pageNum, pageSize));
    }

    @Log(title = "画布用户权益", businessType = BusinessType.INSERT)
    @PreAuthorize("@ss.hasPermi('aid:canvas:entitlement:edit')")
    @PostMapping("/entitlement/override")
    public AjaxResult addOverride(@Valid @RequestBody CanvasRequests.EntitlementOverride request) {
        return success(java.util.Map.of("id", JsonSafeId.value(
                canvasAdminService.createOverride(request, getUsername()))));
    }

    @Log(title = "画布用户权益", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('aid:canvas:entitlement:edit')")
    @PostMapping("/entitlement/override/revoke")
    public AjaxResult revokeOverride(@Valid @RequestBody CanvasRequests.RevokeOverride request) {
        canvasAdminService.revokeOverride(request, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:session:list')")
    @GetMapping("/session/list")
    public AjaxResult sessions(@RequestParam(required = false) Long projectId,
                               @RequestParam(required = false) Long userId,
                               @RequestParam(defaultValue = "1") int pageNum,
                               @RequestParam(defaultValue = "20") int pageSize) {
        return success(canvasAdminService.sessions(projectId, userId, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:session:list')")
    @GetMapping("/lock/list")
    public AjaxResult locks(@RequestParam(required = false) Long projectId,
                            @RequestParam(required = false) Long workspaceId,
                            @RequestParam(required = false) Long userId,
                            @RequestParam(defaultValue = "1") int pageNum,
                            @RequestParam(defaultValue = "20") int pageSize) {
        return success(canvasAdminService.locks(projectId, workspaceId, userId, pageNum, pageSize));
    }

    @Log(title = "画布在线会话", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('aid:canvas:session:terminate')")
    @PostMapping("/session/terminate")
    public AjaxResult terminate(@Valid @RequestBody CanvasRequests.TerminateSession request) {
        canvasAdminService.terminateSession(request, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:canvas:audit:list')")
    @GetMapping("/audit/list")
    public AjaxResult audits(@RequestParam(required = false) String operator,
                             @RequestParam(required = false) Long targetUserId,
                             @RequestParam(required = false) String action,
                             @RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "20") int pageSize) {
        return success(canvasAdminService.audits(operator, targetUserId, action, pageNum, pageSize));
    }
}
