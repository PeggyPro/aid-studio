package com.aid.skill.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.service.SkillAuthoringStorageService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/** Technical persistence evidence for an exact own, still-private unpublished Skill draft. */
@Component
@RequiredArgsConstructor
public class SkillDraftTargetWriteCompletionVerifier implements AssistantTargetWriteCompletionVerifier {
    private final AidSkillMapper skills;
    private final AidSkillDraftMapper drafts;

    @Override
    public Set<String> supportedToolCodes() {
        return com.aid.runtime.assistant.NativeSkillAuthoringContract.proposalToolCodes();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requireCurrentWrite(VerifiedRuntimeToolEffect effect) {
        if (effect == null || !positive(effect.userId()) || !positive(effect.runId())
                || !positive(effect.stepId()) || !com.aid.runtime.assistant.NativeSkillAuthoringContract.isProposalTool(effect.toolCode())
                || !"1.0.0".equals(effect.contractVersion())
                || effect.schemaDigest() == null || !effect.schemaDigest().matches("[0-9a-f]{64}")
                || StrUtil.isBlank(effect.idempotencyKey())
                || effect.outputArtifactRefs() == null || !effect.outputArtifactRefs().isEmpty()) invalid();
        JSONObject input = object(effect.argumentsJson());
        JSONObject output = object(effect.outputJson());
        Long skillId = positiveId(output.get("skillId"));
        Long draftId = positiveId(output.get("draftId"));
        if (!positive(skillId) || !positive(draftId)) invalid();
        String requestKey = SkillDraftProposalReceiptPolicy.requestKey(effect.runId(), effect.stepId(),
                effect.idempotencyKey(), input);
        // Match the administration lock order: Skill identity before its editable draft.
        AidSkill skill = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getId, skillId).eq(AidSkill::getOwnerUserId, effect.userId())
                .eq(AidSkill::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
        AidSkillDraft draft = drafts.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                .eq(AidSkillDraft::getId, draftId).eq(AidSkillDraft::getOwnerUserId, effect.userId())
                .last("LIMIT 1 FOR UPDATE"));
        if (skill == null || draft == null
                || !Objects.equals(skill.getId(), skillId)
                || !"USER".equals(skill.getOwnerType())
                || !Objects.equals(skill.getOwnerUserId(), effect.userId())
                || !Objects.equals(skill.getSkillCode(), input.getString("skillCode"))
                || !"PRIVATE".equals(skill.getVisibility()) || !"0".equals(skill.getDelFlag())
                || skill.getCurrentVersionId() != null || !"1".equals(skill.getStatus())
                || !Objects.equals(draft.getId(), draftId) || !Objects.equals(draft.getSkillId(), skillId)
                || !Objects.equals(draft.getOwnerUserId(), effect.userId())
                || !Objects.equals(draft.getCreatorRunId(), effect.runId())
                || !Objects.equals(draft.getCreatorStepId(), effect.stepId())
                || !Objects.equals(draft.getCreatorRequestKey(), requestKey)
                || !"EDITING".equals(draft.getStatus()) || StrUtil.isBlank(draft.getActiveKey())
                || StrUtil.isBlank(draft.getDraftJson()) || draft.getDraftDigest() == null
                || !draft.getDraftDigest().matches("[0-9a-f]{64}")
                || !Objects.equals(draft.getDraftDigest(), SecureUtil.sha256(draft.getDraftJson()))) invalid();
        var receipt = new SkillAuthoringStorageService.AuthoringDraftReceipt(skillId, draftId,
                draft.getDraftDigest(), draft.getProvenanceJson());
        JSONObject current = SkillDraftProposalReceiptPolicy.project(receipt, effect.runId(),
                effect.stepId(), requestKey, input);
        if (!object(current.toJSONString()).equals(output)) invalid();
    }

    private static boolean positive(Long id) { return id != null && id > 0; }

    private static Long positiveId(Object raw) {
        Long id = raw instanceof Integer value ? value.longValue() : raw instanceof Long value ? value : null;
        if (!positive(id)) invalid();
        return id;
    }

    private static JSONObject object(String json) {
        if (StrUtil.isBlank(json)) invalid();
        try {
            JSONObject value = JSON.parseObject(json);
            if (value != null) return value;
        } catch (RuntimeException malformed) {
            throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");
        }
        throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
