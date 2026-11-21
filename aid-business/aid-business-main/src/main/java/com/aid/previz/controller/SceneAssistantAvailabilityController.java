package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.compute.SceneAssistantSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/user/previz")
public class SceneAssistantAvailabilityController {
    private final SceneAssistantSettings settings;

    public SceneAssistantAvailabilityController(SceneAssistantSettings settings) {
        this.settings = settings;
    }

    @PostMapping("/scene-assistant/availability")
    public AjaxResult availability() {
        return AjaxResult.success(Map.of("available", settings.available()));
    }
}
