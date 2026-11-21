package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.vo.SkillPackageAdminVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Date;
import java.util.regex.Pattern;

/** 可信 Skill 创建器使用的私有身份与草稿原子存储入口。 */
@Service
@RequiredArgsConstructor
public class SkillAuthoringStorageService {
    private static final Pattern SKILL_CODE = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final String PROPOSAL_RECEIPT_SCHEMA = "skill-proposal-receipt/v1";
    private final AidSkillMapper skillMapper;
    private final ISkillPackageAdminService packageAdminService;
    private final AidSkillDraftMapper draftMapper;
    private final AidRuntimeRunMapper runtimeRunMapper;
    private final AidRuntimeRunStepMapper runtimeRunStepMapper;

    @Transactional(rollbackFor = Exception.class)
    public AuthoringDraftReceipt createPrivateDraft(AuthoringDraftCommand command) {
        validate(command);
        lockTrustedCreatorExecution(command);
        AidSkillDraft existing = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                .eq(AidSkillDraft::getCreatorRequestKey, command.creatorRequestKey())
                .last("limit 1 for update"));
        if (existing != null) {
            if (!Objects.equals(existing.getOwnerUserId(), command.ownerUserId())
                    || !Objects.equals(existing.getCreatorRunId(), command.creatorRunId())
                    || !Objects.equals(existing.getCreatorStepId(), command.creatorStepId())) {
                throw new ServiceException("创建请求归属冲突");
            }
            return immutableProposalReceipt(existing);
        }
        SkillAdminRequests.CreateRequest identity = command.identity();
        identity.setVisibility("PRIVATE");
        identity.setCacheToDisk(false);
        AidSkill skill = insertPrivateIdentity(identity, command.ownerUserId(), command.operatorName());
        SkillPackageAdminRequests.DraftSaveRequest payload = command.draft();
        payload.setSkillId(skill.getId());
        payload.setBaseVersionId(null);
        // Use the same publication validator before acknowledging a generated package. The
        // enclosing transaction rolls back the unpublished identity as well on any failure.
        SkillPackageAdminVO.ValidationResult validation = packageAdminService.validateDraft(payload);
        if (!Boolean.TRUE.equals(validation.getValid())) {
            String problems = validation.getErrors().stream().limit(8)
                    .map(error -> error.getField() + ": " + error.getMessage())
                    .collect(java.util.stream.Collectors.joining("；"));
            throw new ServiceException("创建Skill草稿校验失败，请修正后重新保存：" + problems);
        }
        SkillPackageAdminVO.DraftDetail draft = packageAdminService.saveDraft(payload,
                command.ownerUserId(), command.operatorName());
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "TRUSTED_SKILL_CREATOR");
        provenance.put("creatorRunId", command.creatorRunId());
        provenance.put("creatorStepId", command.creatorStepId());
        provenance.put("creatorRequestKey", command.creatorRequestKey());
        provenance.put("sourceMode", command.sourceMode());
        provenance.put("authorizedSources", command.authorizedSources().stream().map(source -> Map.of(
                "kind", source.kind(),
                "canonicalRef", JSON.parseObject(source.canonicalRefJson()),
                "contentRefDigest", source.contentRefDigest())).toList());
        Map<String, Object> proposalReceipt = new LinkedHashMap<>();
        proposalReceipt.put("schema", PROPOSAL_RECEIPT_SCHEMA);
        proposalReceipt.put("skillId", skill.getId());
        proposalReceipt.put("draftId", draft.getDraftId());
        proposalReceipt.put("draftDigest", draft.getDraftDigest());
        proposalReceipt.put("visibility", "PRIVATE");
        proposalReceipt.put("status", "DRAFT");
        proposalReceipt.put("published", false);
        provenance.put("proposalReceipt", proposalReceipt);
        String provenanceJson = JSON.toJSONString(provenance);
        AidSkillDraft update = new AidSkillDraft();
        update.setId(draft.getDraftId());
        update.setCreatorRunId(command.creatorRunId());
        update.setCreatorStepId(command.creatorStepId());
        update.setCreatorRequestKey(command.creatorRequestKey());
        update.setProvenanceJson(provenanceJson);
        try {
            int changed = draftMapper.update(update, Wrappers.<AidSkillDraft>lambdaUpdate()
                    .eq(AidSkillDraft::getId, draft.getDraftId())
                    .eq(AidSkillDraft::getOwnerUserId, command.ownerUserId())
                    .isNull(AidSkillDraft::getCreatorRequestKey));
            if (changed != 1) throw new ServiceException("创建草稿已变化");
        } catch (DuplicateKeyException error) {
            throw new ServiceException("创建请求已存在");
        }
        return immutableProposalReceipt(skill.getId(), draft.getDraftId(), provenanceJson);
    }

    /** Read-only durable receipt lookup used after an accepted submission or an unknown response. */
    @Transactional(readOnly = true)
    public AuthoringDraftReceipt findPrivateDraftReceipt(String creatorRequestKey,
                                                         Long ownerUserId,
                                                         Long creatorRunId,
                                                         Long creatorStepId) {
        if (StrUtil.isBlank(creatorRequestKey) || ownerUserId == null || ownerUserId <= 0
                || creatorRunId == null || creatorRunId <= 0
                || creatorStepId == null || creatorStepId <= 0) {
            throw new ServiceException("创建草稿回执身份无效");
        }
        AidSkillDraft existing = draftMapper.selectOne(Wrappers.<AidSkillDraft>lambdaQuery()
                .eq(AidSkillDraft::getCreatorRequestKey, creatorRequestKey)
                .last("limit 1"));
        if (existing == null) return null;
        if (!Objects.equals(existing.getOwnerUserId(), ownerUserId)
                || !Objects.equals(existing.getCreatorRunId(), creatorRunId)
                || !Objects.equals(existing.getCreatorStepId(), creatorStepId)) {
            throw new ServiceException("创建草稿回执归属冲突");
        }
        return immutableProposalReceipt(existing);
    }

    private static AuthoringDraftReceipt immutableProposalReceipt(AidSkillDraft draft) {
        if (draft == null) throw new ServiceException("创建草稿回执无效");
        return immutableProposalReceipt(draft.getSkillId(), draft.getId(), draft.getProvenanceJson());
    }

    private static AuthoringDraftReceipt immutableProposalReceipt(Long expectedSkillId,
                                                                   Long expectedDraftId,
                                                                   String provenanceJson) {
        try {
            JSONObject provenance = JSON.parseObject(provenanceJson);
            JSONObject receipt = provenance == null ? null : provenance.getJSONObject("proposalReceipt");
            Long skillId = receipt == null ? null : receipt.getLong("skillId");
            Long draftId = receipt == null ? null : receipt.getLong("draftId");
            String draftDigest = receipt == null ? null : receipt.getString("draftDigest");
            Boolean published = receipt == null ? null : receipt.getBoolean("published");
            if (receipt == null || !PROPOSAL_RECEIPT_SCHEMA.equals(receipt.getString("schema"))
                    || !Objects.equals(expectedSkillId, skillId)
                    || !Objects.equals(expectedDraftId, draftId)
                    || skillId == null || skillId <= 0 || draftId == null || draftId <= 0
                    || StrUtil.isBlank(draftDigest) || !draftDigest.matches("[0-9a-f]{64}")
                    || !"PRIVATE".equals(receipt.getString("visibility"))
                    || !"DRAFT".equals(receipt.getString("status"))
                    || !Boolean.FALSE.equals(published)) {
                throw new ServiceException("创建草稿回执无效");
            }
            return new AuthoringDraftReceipt(skillId, draftId, draftDigest, provenanceJson);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServiceException("创建草稿回执无效");
        }
    }

    /**
     * Locking the persisted run makes the idempotency check and the identity/draft insert one
     * cross-node critical section. A retry with the same trusted run waits for the winner and then
     * returns its receipt instead of first racing on the globally unique Skill code.
     */
    private void lockTrustedCreatorExecution(AuthoringDraftCommand command) {
        AidRuntimeRun run = runtimeRunMapper.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId, AidRuntimeRun::getDelFlag)
                .eq(AidRuntimeRun::getId, command.creatorRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("limit 1 for update"));
        AidRuntimeRunStep step = runtimeRunStepMapper.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getRunId,
                        AidRuntimeRunStep::getToolCode, AidRuntimeRunStep::getDelFlag)
                .eq(AidRuntimeRunStep::getId, command.creatorStepId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("limit 1"));
        if (run == null || !Objects.equals(run.getUserId(), command.ownerUserId())
                || step == null || !Objects.equals(step.getRunId(), run.getId())
                || !com.aid.runtime.assistant.NativeSkillAuthoringContract.isProposalTool(step.getToolCode())) {
            throw new ServiceException("可信Skill创建执行不存在");
        }
    }

    private AidSkill insertPrivateIdentity(SkillAdminRequests.CreateRequest request,
                                           Long ownerUserId, String operatorName) {
        if (StrUtil.isBlank(request.getSkillCode()) || !SKILL_CODE.matcher(request.getSkillCode()).matches()
                || StrUtil.isBlank(request.getName())) {
            throw new ServiceException("Skill身份参数错误");
        }
        Date now = new Date();
        String operator = StrUtil.blankToDefault(StrUtil.trim(operatorName), String.valueOf(ownerUserId));
        AidSkill skill = new AidSkill();
        skill.setSkillCode(request.getSkillCode());
        skill.setName(StrUtil.trim(request.getName()));
        skill.setDescription(StrUtil.trim(request.getDescription()));
        skill.setIconUrl(StrUtil.trim(request.getIconUrl()));
        skill.setOwnerType("USER");
        skill.setOwnerUserId(ownerUserId);
        skill.setVisibility("PRIVATE");
        skill.setCacheToDisk(false);
        skill.setInvocationScope(StrUtil.blankToDefault(request.getInvocationScope(), "ENTRYPOINT"));
        skill.setExecutorType("PROMPT");
        skill.setModelCode("");
        skill.setInputSchemaJson("{}");
        skill.setOutputSchemaJson("{}");
        skill.setSystemPrompt("");
        skill.setDefinitionJson("{\"outputKind\":\"TEXT\"}");
        skill.setReasoningPolicy("DISABLED");
        skill.setDefaultReasoningEnabled(false);
        skill.setShowReasoningDefault(false);
        skill.setReasoningBudgetTokens(0);
        skill.setMaxOutputTokens(8192);
        skill.setContextWindowTokens(128000);
        skill.setSafetyMarginTokens(2048);
        skill.setConfigHash(SecureUtil.sha256("unpublished|" + skill.getSkillCode()));
        skill.setStatus("1");
        skill.setDelFlag("0");
        skill.setCreateBy(operator);
        skill.setCreateTime(now);
        skill.setUpdateBy(operator);
        skill.setUpdateTime(now);
        try {
            if (skillMapper.insert(skill) != 1) throw new ServiceException("Skill创建失败");
        } catch (DuplicateKeyException error) {
            throw new ServiceException("Skill编码已存在");
        }
        return skill;
    }

    private static void validate(AuthoringDraftCommand command) {
        if (command == null || command.ownerUserId() == null || command.ownerUserId() <= 0
                || command.creatorRunId() == null || command.creatorRunId() <= 0
                || command.creatorStepId() == null || command.creatorStepId() <= 0
                || StrUtil.isBlank(command.creatorRequestKey()) || command.creatorRequestKey().length() > 128
                || command.identity() == null || command.draft() == null
                || !Set.of("SCRATCH", "CONVERSATION", "CANVAS").contains(command.sourceMode())
                || command.authorizedSources() == null || command.authorizedSources().size() > 32) {
            throw new ServiceException("创建草稿参数错误");
        }
        for (AuthoringSourceSnapshot source : command.authorizedSources()) {
            if (source == null || StrUtil.isBlank(source.kind()) || source.kind().length() > 64
                    || StrUtil.isBlank(source.canonicalRefJson()) || source.canonicalRefJson().length() > 4096
                    || StrUtil.isBlank(source.contentRefDigest())
                    || !source.contentRefDigest().matches("[0-9a-f]{64}")) {
                throw new ServiceException("创建草稿来源无效");
            }
            try {
                if (JSON.parseObject(source.canonicalRefJson()) == null) {
                    throw new ServiceException("创建草稿来源无效");
                }
            } catch (RuntimeException error) {
                throw new ServiceException("创建草稿来源无效");
            }
        }
    }

    public record AuthoringDraftCommand(Long ownerUserId, Long creatorRunId, Long creatorStepId,
                                         String creatorRequestKey, String operatorName,
                                         SkillAdminRequests.CreateRequest identity,
                                         SkillPackageAdminRequests.DraftSaveRequest draft,
                                         String sourceMode,
                                         List<AuthoringSourceSnapshot> authorizedSources) { }

    public record AuthoringSourceSnapshot(String kind, String canonicalRefJson,
                                          String contentRefDigest) { }

    public record AuthoringDraftReceipt(Long skillId, Long draftId, String draftDigest,
                                        String provenanceJson) { }
}
