package com.aid.assistant.controller;

import com.aid.assistant.service.AssistantSkillDirectoryService;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/assistant/skills")
public class AssistantSkillDirectoryController {
    private final AssistantSkillDirectoryService directory;
    @PostMapping("/page")
    public AjaxResult page(@Valid @RequestBody AssistantSkillDirectoryService.Request request) {
        return AjaxResult.success(directory.page(SecurityUtils.getUserId(), request));
    }
    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> failure(AssistantServiceException error) {
        return ResponseEntity.status(error.getHttpStatus()).body(AjaxResult.error(error.getHttpStatus(), error.getMessage())
                .put("data", new AssistantViews.Error(error.getErrorCode(), error.getDetails())));
    }
}
