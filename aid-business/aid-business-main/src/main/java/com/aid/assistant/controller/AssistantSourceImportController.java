package com.aid.assistant.controller;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.source.dto.SourceImportRequests;
import com.aid.assistant.source.service.AssistantSourceImportService;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Durable, user-owned source document import; no model invocation or billing. */
@Slf4j
@RestController
@RequestMapping("/api/user/assistant/source/import")
public class AssistantSourceImportController {
    @Resource private AssistantSourceImportService imports;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult upload(@Valid @ModelAttribute SourceImportRequests.Upload request) {
        return AjaxResult.success(imports.upload(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/page")
    public AjaxResult page(@Valid @RequestBody SourceImportRequests.Page request) {
        return AjaxResult.success(imports.page(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody SourceImportRequests.Detail request) {
        return AjaxResult.success(imports.detail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/command")
    public AjaxResult command(@Valid @RequestBody SourceImportRequests.Lookup request) {
        return AjaxResult.success(imports.lookup(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/content")
    public AjaxResult content(@Valid @RequestBody SourceImportRequests.Content request) {
        return AjaxResult.success(imports.content(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/advance")
    public AjaxResult advance(@Valid @RequestBody SourceImportRequests.Advance request) {
        return AjaxResult.success(imports.advance(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/cancel")
    public AjaxResult cancel(@Valid @RequestBody SourceImportRequests.Cancel request) {
        return AjaxResult.success(imports.cancel(SecurityUtils.getUserId(), request));
    }

    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> failure(AssistantServiceException error) {
        log.info("source import API rejected, code={}", error.getErrorCode());
        return ResponseEntity.status(error.getHttpStatus()).body(AjaxResult.error(error.getHttpStatus(), error.getMessage())
                .put("data", new AssistantViews.Error(error.getErrorCode(), error.getDetails())));
    }
}
