package com.aid.web.controller.aid;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.notification.dto.NotificationAdminRequests;
import com.aid.notification.service.NotificationService;
import com.aid.notification.vo.NotificationViews;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端站内系统通知接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/aid/notification")
public class NotificationAdminController
{
    private final NotificationService notificationService;

    @PostMapping("/list")
    @PreAuthorize("@ss.hasPermi('system:notification:list')")
    @Operation(summary = "分页查询站内系统通知", description = "按状态和关键词查询草稿、定时、已发布与已撤回通知")
    public AjaxResult list(@Valid @RequestBody NotificationAdminRequests.ListRequest request)
    {
        return pageResult(notificationService.adminList(request));
    }

    @PostMapping("/detail")
    @PreAuthorize("@ss.hasPermi('system:notification:list')")
    @Operation(summary = "查询站内系统通知详情", description = "返回正文、乐观锁版本及固定收件人")
    public AjaxResult detail(@Valid @RequestBody NotificationAdminRequests.Detail request)
    {
        return AjaxResult.success(notificationService.adminDetail(request.getId()));
    }

    @PostMapping("/save")
    @PreAuthorize("@ss.hasPermi('system:notification:save')")
    @Log(title = "站内系统通知", businessType = BusinessType.INSERT)
    @Operation(summary = "保存站内系统通知草稿", description = "新建或按expectedVersion编辑草稿并校验真实C端收件人")
    public AjaxResult save(@Valid @RequestBody NotificationAdminRequests.Save request)
    {
        return AjaxResult.success(notificationService.save(SecurityUtils.getUserId(), SecurityUtils.getUsername(), request));
    }

    @PostMapping("/publish")
    @PreAuthorize("@ss.hasPermi('system:notification:publish')")
    @Log(title = "发布站内系统通知", businessType = BusinessType.UPDATE)
    @Operation(summary = "发布或安排站内系统通知", description = "未来scheduledTime进入定时状态，否则立即发布")
    public AjaxResult publish(@Valid @RequestBody NotificationAdminRequests.Action request)
    {
        return AjaxResult.success(notificationService.publish(SecurityUtils.getUserId(), SecurityUtils.getUsername(), request));
    }

    @PostMapping("/withdraw")
    @PreAuthorize("@ss.hasPermi('system:notification:withdraw')")
    @Log(title = "撤回站内系统通知", businessType = BusinessType.UPDATE)
    @Operation(summary = "撤回已发布通知", description = "撤回后C端立即不可见并推进全局可见性修订")
    public AjaxResult withdraw(@Valid @RequestBody NotificationAdminRequests.Action request)
    {
        return AjaxResult.success(notificationService.withdraw(SecurityUtils.getUserId(), SecurityUtils.getUsername(), request));
    }

    @PostMapping("/cancel-schedule")
    @PreAuthorize("@ss.hasPermi('system:notification:cancelSchedule')")
    @Log(title = "取消定时通知", businessType = BusinessType.UPDATE)
    @Operation(summary = "取消定时发布", description = "将定时通知恢复为可编辑草稿")
    public AjaxResult cancelSchedule(@Valid @RequestBody NotificationAdminRequests.Action request)
    {
        return AjaxResult.success(notificationService.cancelSchedule(
                SecurityUtils.getUserId(), SecurityUtils.getUsername(), request));
    }

    @PostMapping("/recipients")
    @PreAuthorize("@ss.hasPermi('system:notification:recipients')")
    @Operation(summary = "分页查询通知收件人", description = "查询真实C端账户，停用账户仅展示且不可发布")
    public AjaxResult recipients(@Valid @RequestBody NotificationAdminRequests.Recipients request)
    {
        return pageResult(notificationService.recipients(request));
    }

    private AjaxResult pageResult(NotificationViews.Page<?> page)
    {
        return AjaxResult.success(page.getItems())
                .put("total", page.getTotal())
                .put("pageNum", page.getPageNum())
                .put("pageSize", page.getPageSize());
    }
}
