package com.aid.aid.controller;

import com.aid.aid.service.LocalStorageRootService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/aidconfig/local-storage")
public class LocalStorageRootController {
    private final LocalStorageRootService storage;
    public record Save(@NotBlank String root,@NotBlank String expectedDigest) { }
    @GetMapping
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:list')")
    public AjaxResult detail(){return AjaxResult.success(storage.detail());}
    @PostMapping
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    public AjaxResult save(@Valid @RequestBody Save request){return AjaxResult.success(storage.save(request.root(),request.expectedDigest(),SecurityUtils.getUsername()));}
}
