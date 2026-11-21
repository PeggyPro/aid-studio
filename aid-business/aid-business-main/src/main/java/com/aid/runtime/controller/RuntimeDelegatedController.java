package com.aid.runtime.controller;

import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.service.ISkillInvocationService;
import com.aid.skill.vo.SkillInvocationVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent strategy adapter for immutable legacy orchestration runs. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/runtime/delegated")
public class RuntimeDelegatedController {
    private final ISkillInvocationService skillRuntime;

    @PostMapping("/run/detail")
    public AjaxResult detail(@Valid @RequestBody SkillInvocationRequests.RunRequest request) {
        return AjaxResult.success(view(skillRuntime.getRun(request.getRunId(), SecurityUtils.getUserId())));
    }

    @PostMapping("/input/respond")
    public AjaxResult respond(@Valid @RequestBody SkillInvocationRequests.RespondRequest request) {
        return AjaxResult.success(view(skillRuntime.respond(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername())));
    }

    @PostMapping("/run/events")
    public AjaxResult events(@Valid @RequestBody SkillInvocationRequests.EventPageRequest request) {
        return AjaxResult.success(java.util.Map.of("data",
                skillRuntime.listEvents(request, SecurityUtils.getUserId()), "run",
                view(skillRuntime.getRun(request.getRunId(), SecurityUtils.getUserId()))));
    }

    @PostMapping("/run/cancel")
    public AjaxResult cancel(@Valid @RequestBody SkillInvocationRequests.RunRequest request) {
        skillRuntime.cancel(request.getRunId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private static JSONObject view(SkillInvocationVO source) {
        JSONObject value = JSON.parseObject(JSON.toJSONString(source));
        value.put("runType", "SKILL_ORCHESTRATOR");
        value.put("legacyRunId", JsonSafeId.value(source.getRunId()));
        value.put("runId", JsonSafeId.value(source.getRunId()));
        return value;
    }
}
