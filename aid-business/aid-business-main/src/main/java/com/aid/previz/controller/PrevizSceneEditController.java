package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.previz.service.PrevizSceneEditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/user/previz/scene-edit")
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public class PrevizSceneEditController {
    private final PrevizSceneEditService edits;
    @Data public static class Receipt {
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String projectId;
        @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") private String stepId;
    }
    @PostMapping("/receipt") public AjaxResult receipt(@Valid @RequestBody Receipt request)throws IOException {
        return AjaxResult.success(edits.receipt(SecurityUtils.getUserId(),request.getProjectId(),Long.valueOf(request.getStepId())));
    }
}
