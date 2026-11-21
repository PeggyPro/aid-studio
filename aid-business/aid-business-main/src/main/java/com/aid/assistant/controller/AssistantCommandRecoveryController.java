package com.aid.assistant.controller;

import com.aid.assistant.dto.AssistantCommandRecoveryRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCommandRecoveryService;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only recovery over the same authenticated/encrypted assistant HTTP transport. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/assistant/commands")
public class AssistantCommandRecoveryController
{
    private final AssistantCommandRecoveryService service;

    @PostMapping("/page")
    public AjaxResult page(@Valid @RequestBody AssistantCommandRecoveryRequests.Page request)
    {
        return AjaxResult.success(service.page(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/lookup")
    public AjaxResult lookup(@Valid @RequestBody AssistantCommandRecoveryRequests.Lookup request)
    {
        return AjaxResult.success(service.lookup(SecurityUtils.getUserId(), request));
    }

    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> failure(AssistantServiceException exception)
    {
        return ResponseEntity.status(exception.getHttpStatus()).body(
                AjaxResult.error(exception.getHttpStatus(), exception.getMessage())
                        .put("data", new AssistantViews.Error(exception.getErrorCode(), exception.getDetails())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AjaxResult> invalidRequest(MethodArgumentNotValidException exception)
    {
        return failure(new AssistantServiceException(422, "COMMAND_RECOVERY_REQUEST_INVALID", "恢复参数无效", null));
    }
}
