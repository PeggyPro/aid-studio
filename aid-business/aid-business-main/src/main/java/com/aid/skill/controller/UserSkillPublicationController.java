package com.aid.skill.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.service.ISkillAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 已认证用户维护自己由可信 Skill 创建器产生的稳定身份与公开状态。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/runtime/creator/skill")
public class UserSkillPublicationController {
    private final ISkillAdminService skillAdminService;

    @PostMapping("/identity/edit")
    public AjaxResult editIdentity(@Valid @RequestBody SkillAdminRequests.IdentitySaveRequest request) {
        skillAdminService.updateOwnedIdentity(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PostMapping("/cover/upload")
    public AjaxResult uploadCover(@RequestParam("skillId") Long skillId,
                                  @RequestParam("file") MultipartFile file) {
        return AjaxResult.success(skillAdminService.uploadCover(skillId, file,
                SecurityUtils.getUserId(), SecurityUtils.getUsername(), false));
    }

    @PostMapping("/publication/settings")
    public AjaxResult publicationSettings(
            @RequestBody(required = false) SkillAdminRequests.EmptyRequest request) {
        return AjaxResult.success(skillAdminService.publicationSettings());
    }
}
