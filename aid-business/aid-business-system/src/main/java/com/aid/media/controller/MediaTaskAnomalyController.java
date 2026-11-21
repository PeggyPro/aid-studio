package com.aid.media.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.media.dto.MediaTaskAnomalyConfigRequest;
import com.aid.media.dto.MediaTaskAnomalyRequests;
import com.aid.media.service.MediaTaskAnomalyAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
@RequiredArgsConstructor
@RequestMapping("/system/media/task/anomaly")
public class MediaTaskAnomalyController extends BaseController {
    private final MediaTaskAnomalyAdminService service;

    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:list')")
    @GetMapping("/summary")
    public AjaxResult summary() {
        return success(service.summary());
    }

    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:list')")
    @GetMapping("/config")
    public AjaxResult config() {
        return success(service.config());
    }

    @Log(title = "媒体异常任务配置", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:config')")
    @PutMapping("/config")
    public AjaxResult config(@Valid @RequestBody MediaTaskAnomalyConfigRequest request) {
        service.updateConfig(request, getUserId(), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) Long taskId,
                           @RequestParam(required = false) Long userId,
                           @RequestParam(required = false) String mediaType,
                           @RequestParam(required = false) String anomalyType,
                           @RequestParam(required = false) String anomalyStatus,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date detectedStartTime,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date detectedEndTime,
                           @RequestParam(defaultValue = "1") int pageNum,
                           @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.list(taskId, userId, mediaType, anomalyType, anomalyStatus,
                detectedStartTime, detectedEndTime, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:list')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id) {
        return success(service.detail(id));
    }

    @Log(title = "媒体异常任务复查", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:recheck')")
    @PostMapping("/{id}/recheck")
    public AjaxResult recheck(@PathVariable Long id) {
        return success(service.recheck(id, getUserId(), getUsername()));
    }

    @Log(title = "媒体任务按 ID 复查", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:recheck')")
    @PostMapping("/task/{taskId}/recheck")
    public AjaxResult recheckTask(@PathVariable Long taskId,
                                  @Valid @RequestBody MediaTaskAnomalyRequests.TaskRecheck request) {
        return success(service.recheckTask(taskId, request, getUserId(), getUsername()));
    }

    @Log(title = "媒体异常任务续等", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:continue-wait')")
    @PostMapping("/{id}/continue-wait")
    public AjaxResult waitMore(@PathVariable Long id,
                               @Valid @RequestBody MediaTaskAnomalyRequests.ContinueWait request) {
        service.continueWait(id, request, getUserId(), getUsername());
        return success();
    }

    @Log(title = "媒体异常任务确认失败", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('system:media:task:anomaly:confirm-failure')")
    @PostMapping("/{id}/confirm-failure")
    public AjaxResult fail(@PathVariable Long id,
                           @Valid @RequestBody MediaTaskAnomalyRequests.ConfirmFailure request) {
        return success(service.confirmFailure(id, request, getUserId(), getUsername()));
    }
}
