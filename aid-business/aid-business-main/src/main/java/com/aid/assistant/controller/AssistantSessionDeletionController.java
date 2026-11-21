package com.aid.assistant.controller;

import com.aid.assistant.service.AssistantSessionDeletionService;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/user/assistant")
public class AssistantSessionDeletionController {
    private final AssistantSessionDeletionService sessions;
    public record Delete(@NotNull @Positive Long sessionId,@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=128) String clientRequestId) { }
    @PostMapping("/session/delete")
    public AjaxResult delete(@Valid @RequestBody Delete request)throws Exception{
        return AjaxResult.success(sessions.delete(SecurityUtils.getUserId(),request.sessionId(),request.expectedRevision(),request.clientRequestId()));
    }
    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> handleAssistantFailure(AssistantServiceException exception) {
        return ResponseEntity.status(exception.getHttpStatus()).body(
            AjaxResult.error(exception.getHttpStatus(),exception.getMessage()).put("data",
                new AssistantViews.Error(exception.getErrorCode(),exception.getDetails())));
    }
}
