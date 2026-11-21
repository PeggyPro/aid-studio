package com.aid.skill.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.skill.service.StyleAnalysisService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/asset/style/analysis")
public class UserStyleAnalysisController {
    private final StyleAnalysisService service;

    @PostMapping("/submit")
    public AjaxResult submit(@Valid @RequestBody Submit request) {
        return AjaxResult.success(service.submit(request.imageUrl(), request.requestId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/active")
    public AjaxResult active() {
        return AjaxResult.success(service.active(SecurityUtils.getUserId()));
    }

    @PostMapping("/status")
    public AjaxResult status(@Valid @RequestBody Status request) {
        return AjaxResult.success(service.status(request.taskId(), SecurityUtils.getUserId()));
    }

    public record Submit(@NotBlank @Size(max = 8192) String imageUrl,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId) {}
    public record Status(@NotNull Long taskId) {}
}
