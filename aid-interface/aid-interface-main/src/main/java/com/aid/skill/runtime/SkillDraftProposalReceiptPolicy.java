package com.aid.skill.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.service.SkillAuthoringStorageService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;

/** Shared immutable proposal identity and receipt projection; it never creates or executes a draft. */
public final class SkillDraftProposalReceiptPolicy {
    private SkillDraftProposalReceiptPolicy() { }

    public static String requestKey(Long runId, Long stepId, String idempotencyKey, JSONObject input) {
        if (runId == null || runId <= 0 || stepId == null || stepId <= 0
                || StrUtil.isBlank(idempotencyKey) || input == null)
            throw new ServiceException("SKILL_DRAFT_RECEIPT_INVALID");
        return "aid-skill-draft:" + SecureUtil.sha256(
                runId + ":" + stepId + ":" + idempotencyKey + ":" + input.toJSONString());
    }

    public static JSONObject project(SkillAuthoringStorageService.AuthoringDraftReceipt receipt,
                                     Long runId, Long stepId, String requestKey, JSONObject input) {
        if (receipt == null || receipt.skillId() == null || receipt.skillId() <= 0
                || receipt.draftId() == null || receipt.draftId() <= 0
                || StrUtil.isBlank(receipt.draftDigest())) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_INVALID");
        }
        JSONObject provenance = object(receipt.provenanceJson());
        JSONObject frozenReceipt = provenance.getJSONObject("proposalReceipt");
        if (frozenReceipt == null
                || !"skill-proposal-receipt/v1".equals(frozenReceipt.getString("schema"))
                || !Objects.equals(receipt.skillId(), frozenReceipt.getLong("skillId"))
                || !Objects.equals(receipt.draftId(), frozenReceipt.getLong("draftId"))
                || !Objects.equals(receipt.draftDigest(), frozenReceipt.getString("draftDigest"))
                || !"PRIVATE".equals(frozenReceipt.getString("visibility"))
                || !"DRAFT".equals(frozenReceipt.getString("status"))
                || !Boolean.FALSE.equals(frozenReceipt.getBoolean("published"))) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
        }
        JSONArray sources = provenance.getJSONArray("authorizedSources");
        String sourceMode = input.getString("sourceMode");
        if (!"TRUSTED_SKILL_CREATOR".equals(provenance.getString("source"))
                || !Objects.equals(runId, provenance.getLong("creatorRunId"))
                || !Objects.equals(stepId, provenance.getLong("creatorStepId"))
                || !Objects.equals(requestKey,
                provenance.getString("creatorRequestKey"))
                || !Objects.equals(sourceMode, provenance.getString("sourceMode"))
                || sources == null || sources.isEmpty() || sources.size() > 32) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
        }
        JSONObject creator = null;
        JSONObject branch = null;
        for (Object raw : sources) {
            if (!(raw instanceof JSONObject source)) {
                throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
            }
            if ("CREATOR_SKILL_VERSION".equals(source.getString("kind"))) {
                if (creator != null) throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
                creator = source;
            } else if ("CREATOR_SKILL_BRANCH".equals(source.getString("kind"))) {
                if (branch != null) throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
                branch = source;
            }
        }
        JSONObject creatorRef = creator == null ? null : creator.getJSONObject("canonicalRef");
        Long creatorVersionId = creatorRef == null ? null : creatorRef.getLong("skillVersionId");
        String packageDigest = creatorRef == null ? null : creatorRef.getString("packageDigest");
        String masterDigest = creatorRef == null ? null : creatorRef.getString("masterDigest");
        if (creatorVersionId == null || creatorVersionId <= 0
                || StrUtil.isBlank(packageDigest) || !packageDigest.matches("[0-9a-f]{64}")
                || StrUtil.isBlank(masterDigest) || !masterDigest.matches("[0-9a-f]{64}")
                || !Objects.equals(masterDigest, creator.getString("contentRefDigest"))) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
        }
        JSONObject output = new JSONObject();
        output.put("skillId", receipt.skillId());
        output.put("draftId", receipt.draftId());
        output.put("draftDigest", receipt.draftDigest());
        output.put("visibility", "PRIVATE");
        output.put("status", "DRAFT");
        output.put("published", false);
        output.put("sourceMode", sourceMode);
        output.put("authorizedSourceCount", sources.size());
        output.put("creatorSkillVersionId", creatorVersionId);
        output.put("creatorPackageDigest", packageDigest);
        output.put("creatorMasterDigest", masterDigest);
        if (branch != null) {
            JSONObject branchRef = branch.getJSONObject("canonicalRef");
            if (branchRef == null
                    || !Objects.equals(creatorVersionId, branchRef.getLong("skillVersionId"))
                    || !Objects.equals(packageDigest, branchRef.getString("packageDigest"))
                    || StrUtil.isBlank(branchRef.getString("path"))
                    || StrUtil.isBlank(branchRef.getString("resourceDigest"))
                    || !branchRef.getString("resourceDigest").matches("[0-9a-f]{64}")
                    || !Objects.equals(branchRef.getString("resourceDigest"),
                    branch.getString("contentRefDigest"))) {
                throw new ServiceException("SKILL_DRAFT_RECEIPT_CONFLICT");
            }
            output.put("creatorBranchPath", branchRef.getString("path"));
            output.put("creatorBranchDigest", branchRef.getString("resourceDigest"));
        }
        return output;
    }

    private static JSONObject object(String json) {
        if (StrUtil.isBlank(json)) throw new ServiceException("SKILL_DRAFT_RECEIPT_INVALID");
        try {
            JSONObject value = JSON.parseObject(json);
            if (value != null) return value;
        } catch (RuntimeException invalid) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_INVALID");
        }
        throw new ServiceException("SKILL_DRAFT_RECEIPT_INVALID");
    }
}
