package com.aid.canvas.publication.controller;

import com.aid.canvas.publication.dto.CanvasPublicationRequests.*;
import com.aid.canvas.publication.service.CanvasPublicationService;
import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/aid/audit/canvas")
@RequiredArgsConstructor
public class CanvasPublicationAdminController {
    private final CanvasPublicationService service;
    @PreAuthorize("@ss.hasPermi('aid:canvasAudit:query')")
    @GetMapping("/media") public ResponseEntity<Resource> media(@RequestParam Long publicationId,@RequestParam Long resourceId) {
        return service.content(publicationId,resourceId,null,true);
    }
    @PreAuthorize("@ss.hasPermi('aid:canvasAudit:list')")
    @PostMapping("/list") public AjaxResult list(@Valid @RequestBody Page request) {
        return AjaxResult.success(service.list(request,false));
    }
    @PreAuthorize("@ss.hasPermi('aid:canvasAudit:query')")
    @PostMapping("/detail") public AjaxResult detail(@Valid @RequestBody Detail request) {
        return AjaxResult.success(service.detail(request.getPublicationId(),true));
    }
    @PreAuthorize("@ss.hasPermi('aid:canvasAudit:review')")
    @Log(title="画布发布审核",businessType=BusinessType.UPDATE)
    @PostMapping("/review") public AjaxResult review(@Valid @RequestBody Review request) {
        if("OFFLINE".equals(request.getAction())) throw new com.aid.common.exception.ServiceException("下架请使用独立下架接口");
        return AjaxResult.success(service.review(request,"ADMIN:"+SecurityUtils.getUserId()));
    }
    @PreAuthorize("@ss.hasPermi('aid:canvasAudit:offline')")
    @Log(title="画布作品下架",businessType=BusinessType.UPDATE)
    @PostMapping("/offline") public AjaxResult offline(@Valid @RequestBody Review request) {
        request.setAction("OFFLINE");
        return AjaxResult.success(service.review(request,"ADMIN:"+SecurityUtils.getUserId()));
    }
}
