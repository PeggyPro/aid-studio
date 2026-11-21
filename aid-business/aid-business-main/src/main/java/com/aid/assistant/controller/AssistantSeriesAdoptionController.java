package com.aid.assistant.controller;

import com.aid.assistant.creative.series.CreativeSeriesAdoptionService;
import com.aid.assistant.creative.series.dto.SeriesAdoptionRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.vo.AssistantViews;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated project-scope series adoption endpoints. */
@Slf4j
@RestController
@RequestMapping("/api/user/assistant/artifact/adoption/series")
public class AssistantSeriesAdoptionController {
    @Resource private CreativeSeriesAdoptionService seriesAdoption;

    @PostMapping("/capabilities")
    public AjaxResult capabilities(@Valid @RequestBody SeriesAdoptionRequests.Capabilities request) {
        return AjaxResult.success(seriesAdoption.capabilities(SecurityUtils.getUserId(), request));
    }

    @PostMapping
    public AjaxResult commit(@Valid @RequestBody SeriesAdoptionRequests.Commit request) {
        return AjaxResult.success(seriesAdoption.commit(SecurityUtils.getUserId(), request));
    }

    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> handleAssistantFailure(AssistantServiceException exception) {
        log.info("assistant series adoption rejected, code={}", exception.getErrorCode());
        AssistantViews.Error error = new AssistantViews.Error(exception.getErrorCode(), exception.getDetails());
        return ResponseEntity.status(exception.getHttpStatus())
                .body(AjaxResult.error(exception.getHttpStatus(), exception.getMessage()).put("data", error));
    }
}
