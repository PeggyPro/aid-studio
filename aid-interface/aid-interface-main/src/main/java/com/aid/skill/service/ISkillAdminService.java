package com.aid.skill.service;

import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.vo.SkillAdminVO;

import java.util.List;
import com.aid.aid.domain.dto.OssUploadResponse;
import org.springframework.web.multipart.MultipartFile;

/** Private administration for Skill identities and versioned Runtime audit. */
public interface ISkillAdminService {
    SkillAdminVO.SkillSummary createSkill(SkillAdminRequests.CreateRequest request,
                                         Long operatorId, String operatorName, boolean platform);
    SkillAdminVO.PageResult<SkillAdminVO.SkillSummary> pageSkills(SkillAdminRequests.PageRequest request);
    void updateIdentity(SkillAdminRequests.IdentitySaveRequest request, Long operatorId, String operatorName);
    void updateOwnedIdentity(SkillAdminRequests.IdentitySaveRequest request,
                             Long ownerUserId, String operatorName);
    void updateStatus(SkillAdminRequests.StatusRequest request, Long operatorId, String operatorName);
    void deleteSkill(Long id, Long operatorId, String operatorName);
    void restoreSkill(Long id, Long operatorId, String operatorName);
    SkillAdminVO.PublicationSettings publicationSettings();
    void updatePublicationSettings(SkillAdminRequests.PublicationSettingsRequest request,
                                   Long operatorId, String operatorName);
    void moderateUnpublish(SkillAdminRequests.ModerateUnpublishRequest request,
                           Long operatorId, String operatorName);
    OssUploadResponse uploadCover(Long skillId, MultipartFile file,
                                  Long operatorId, String operatorName, boolean platformAdmin);
    List<SkillAdminVO.TextModelOption> listTextModelOptions();
    SkillAdminVO.PageResult<SkillAdminVO.RunSummary> pageRuns(SkillAdminRequests.RunPageRequest request);
    SkillAdminVO.RunItem getRun(Long runId);
}
