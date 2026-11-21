package com.aid.media.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.media.dto.OfficialAudioGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 音乐、配音及音频编辑共用统一任务、队列、计费和恢复入口。 */
@RestController
@RequestMapping("/api/user/audio")
@RequiredArgsConstructor
public class OfficialAudioController extends BaseController {
    private final IMediaGenerationService generation;
    private final com.aid.billing.service.AssistantMediaBillingQuoteService quotes;
    private final com.aid.media.provider.audio.OfficialAudioTaskAccess tasks;

    @PostMapping("/quote")
    public AjaxResult quote(@RequestBody OfficialAudioGenerateRequest input) {
        return success(quotes.quoteAudio(input.toInternal(SecurityUtils.getUserId())));
    }

    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody OfficialAudioGenerateRequest input) {
        input.requireSubmissionKey();
        var request=input.toInternal(SecurityUtils.getUserId());
        var previous=tasks.replay(request);
        if(previous!=null)return success(previous);
        return success(com.aid.media.dto.OfficialAudioTaskView.from(
                generation.generateAudio(request)));
    }

    @PostMapping("/task/status")
    public AjaxResult status(@jakarta.validation.Valid @RequestBody com.aid.media.dto.OfficialAudioTaskQuery input) {
        return success(tasks.query(input.taskId(),SecurityUtils.getUserId()));
    }
}
