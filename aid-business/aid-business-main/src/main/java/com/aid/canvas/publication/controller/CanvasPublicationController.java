package com.aid.canvas.publication.controller;

import com.aid.canvas.publication.dto.CanvasPublicationRequests.*;
import com.aid.canvas.publication.service.CanvasPublicationService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/user/canvas/publication")
@RequiredArgsConstructor
public class CanvasPublicationController {
    private final CanvasPublicationService service;
    @PostMapping("/versions") public AjaxResult versions(@Valid @RequestBody Project request) {
        return AjaxResult.success(service.versions(request.getProjectId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/version/detail") public AjaxResult version(@Valid @RequestBody Detail request) {
        return AjaxResult.success(service.ownVersion(request.getPublicationId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/copy/status") public AjaxResult copyStatus(@Valid @RequestBody Operation request) {
        return AjaxResult.success(service.copyStatus(request.getOperationId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/copy/cancel") public AjaxResult cancelCopy(@Valid @RequestBody Operation request){return AjaxResult.success(service.copyControl(request.getOperationId(),SecurityUtils.getUserId(),false));}
    @PostMapping("/copy/retry") public AjaxResult retryCopy(@Valid @RequestBody Operation request){return AjaxResult.success(service.copyControl(request.getOperationId(),SecurityUtils.getUserId(),true));}
    @com.aid.common.aid.crypto.annotation.CryptoIgnore
    @GetMapping("/resource/content") public ResponseEntity<Resource> resource(@RequestParam Long resourceId) {
        return service.copiedContent(resourceId,SecurityUtils.getUserId());
    }
    @com.aid.common.aid.crypto.annotation.CryptoIgnore
    @GetMapping("/media") public ResponseEntity<Resource> media(@RequestParam Long publicationId,@RequestParam Long resourceId) {
        return service.content(publicationId,resourceId,SecurityUtils.getUserId(),false);
    }
    @PostMapping("/prepare") public AjaxResult prepare(@Valid @RequestBody Project request) {
        return AjaxResult.success(service.prepare(request.getProjectId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/submit") public AjaxResult submit(@Valid @RequestBody Submit request) {
        return AjaxResult.success(service.submit(request,SecurityUtils.getUserId()));
    }
    @PostMapping("/status") public AjaxResult status(@Valid @RequestBody Project request) {
        return AjaxResult.success(service.status(request.getProjectId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/change") public AjaxResult change(@Valid @RequestBody Change request) {
        return AjaxResult.success(service.change(request,SecurityUtils.getUserId()));
    }
    @PostMapping("/copy") public AjaxResult copy(@Valid @RequestBody Copy request) {
        return AjaxResult.success(service.copy(request,SecurityUtils.getUserId()));
    }
    @PostMapping("/scenes/read") public AjaxResult scenes(@Valid @RequestBody Project request) {
        return AjaxResult.success(service.scenes(request.getProjectId(),SecurityUtils.getUserId()));
    }
    @PostMapping("/scenes/save") public AjaxResult saveScenes(@Valid @RequestBody Scene request) {
        return AjaxResult.success(service.saveScenes(request,SecurityUtils.getUserId()));
    }
}
