package com.aid.canvas.publication.controller;

import com.aid.canvas.publication.dto.CanvasPublicationRequests.*;
import com.aid.canvas.publication.service.CanvasPublicationService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/public/canvas")
@RequiredArgsConstructor
public class CanvasPublicationPublicController {
    private final CanvasPublicationService service;
    @com.aid.common.aid.crypto.annotation.CryptoIgnore
    @Anonymous @GetMapping("/media") public ResponseEntity<Resource> media(@RequestParam Long publicationId,@RequestParam Long resourceId) {
        return service.content(publicationId,resourceId,null,false);
    }
    @Anonymous @PostMapping("/channel") public AjaxResult channel() { return AjaxResult.success(service.channels()); }
    @Anonymous @PostMapping("/list") public AjaxResult list(@Valid @RequestBody Page request) {
        return AjaxResult.success(service.list(request,true));
    }
    @Anonymous @PostMapping("/detail") public AjaxResult detail(@Valid @RequestBody Detail request) {
        return AjaxResult.success(service.detail(request.getPublicationId(),false));
    }
}
