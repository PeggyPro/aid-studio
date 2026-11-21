package com.aid.assistant.controller;

import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.service.AssistantArtifactService;
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

/** User-facing immutable artifact browsing, review and adoption API. */
@Slf4j
@RestController
@RequestMapping("/api/user/assistant/artifact")
public class AssistantArtifactController {
    @Resource private AssistantArtifactService artifactService;

    @PostMapping("/page")
    public AjaxResult page(@Valid @RequestBody ArtifactRequests.Page request) {
        return AjaxResult.success(artifactService.page(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody ArtifactRequests.Detail request) {
        return AjaxResult.success(artifactService.detail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/media")
    public AjaxResult media(@Valid @RequestBody ArtifactRequests.Media request) {
        return AjaxResult.success(artifactService.media(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/content")
    public AjaxResult content(@Valid @RequestBody ArtifactRequests.Content request) {
        return AjaxResult.success(artifactService.content(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/review/page")
    public AjaxResult reviews(@Valid @RequestBody ArtifactRequests.ReviewPage request) {
        return AjaxResult.success(artifactService.reviews(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/source/coverage")
    public AjaxResult coverage(@Valid @RequestBody ArtifactRequests.CoveragePage request) {
        return AjaxResult.success(artifactService.coverage(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/source/page")
    public AjaxResult sources(@Valid @RequestBody ArtifactRequests.SourcePage request) {
        return AjaxResult.success(artifactService.sources(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/source/manifest")
    public AjaxResult sourceManifest(@Valid @RequestBody ArtifactRequests.SourceManifest request) {
        return AjaxResult.success(artifactService.sourceManifest(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/source/content")
    public AjaxResult sourceContent(@Valid @RequestBody ArtifactRequests.SourceContent request) {
        return AjaxResult.success(artifactService.sourceContent(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/adoption/capabilities")
    public AjaxResult adoptionCapabilities(@Valid @RequestBody ArtifactRequests.AdoptionCapabilities request) {
        return AjaxResult.success(artifactService.adoptionCapabilities(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/adopt")
    public AjaxResult adopt(@Valid @RequestBody ArtifactRequests.Adopt request) {
        return AjaxResult.success(artifactService.adopt(SecurityUtils.getUserId(), request));
    }

    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> handleAssistantFailure(AssistantServiceException exception) {
        log.info("assistant artifact request rejected, code={}", exception.getErrorCode());
        AssistantViews.Error error = new AssistantViews.Error(exception.getErrorCode(), exception.getDetails());
        return ResponseEntity.status(exception.getHttpStatus())
                .body(AjaxResult.error(exception.getHttpStatus(), exception.getMessage()).put("data", error));
    }
}
