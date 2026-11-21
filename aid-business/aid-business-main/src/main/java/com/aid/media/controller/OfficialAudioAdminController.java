package com.aid.media.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.media.dto.OfficialAudioGenerateRequest;
import com.aid.media.provider.audio.OfficialAudioContractRegistry;
import com.aid.media.service.IMediaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 供应商契约和资源管理与前台任务隔离，复用后台操作计费主体。 */
@RestController
@RequestMapping("/aid/officialAudio")
@RequiredArgsConstructor
public class OfficialAudioAdminController extends BaseController {
    private final OfficialAudioContractRegistry contracts;
    private final IMediaGenerationService generation;
    private final com.aid.media.provider.audio.OfficialAudioTaskAccess tasks;

    @GetMapping("/contracts")
    @PreAuthorize("@ss.hasAnyPermi('aid:aidmodel:add,aid:aidmodel:edit')")
    public AjaxResult contracts(@RequestParam(required=false) String vendor) { return success(contracts.catalog(vendor)); }

    @GetMapping("/template")
    @PreAuthorize("@ss.hasAnyPermi('aid:aidmodel:add,aid:aidmodel:edit')")
    public AjaxResult template(@RequestParam String model) {return success(contracts.template(model));}

    @PostMapping("/generate")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    public AjaxResult generate(@RequestBody OfficialAudioGenerateRequest input) {
        input.requireSubmissionKey();
        Long admin=SecurityUtils.getUserId();
        var request=input.toInternal(-admin);
        var previous=tasks.replay(request);
        if(previous!=null)return success(previous);
        return success(com.aid.media.dto.OfficialAudioTaskView.from(
                generation.generateOperatorAudio(request,admin)));
    }

    @PostMapping("/task/status")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    public AjaxResult status(@jakarta.validation.Valid @RequestBody com.aid.media.dto.OfficialAudioTaskQuery input) {
        return success(tasks.query(input.taskId(),-SecurityUtils.getUserId()));
    }
}
