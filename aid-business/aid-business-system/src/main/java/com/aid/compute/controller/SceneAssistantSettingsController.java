package com.aid.compute.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.compute.SceneAssistantSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/aid/compute/scene-assistant")
public class SceneAssistantSettingsController extends BaseController {
    private final SceneAssistantSettings settings;

    public SceneAssistantSettingsController(SceneAssistantSettings settings) {
        this.settings = settings;
    }

    public record Update(@NotNull(message = "请明确设置场景助手开关") Boolean enabled) { }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('aid:compute:query')")
    public AjaxResult read() {
        return success(settings.read());
    }

    @PutMapping
    @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title = "场景助手开关", businessType = BusinessType.UPDATE)
    public AjaxResult update(@Valid @RequestBody Update request) {
        return success(settings.update(request.enabled(), getUsername()));
    }
}
