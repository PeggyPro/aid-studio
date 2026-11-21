package com.aid.assistant.effect;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.mapper.AidComicScriptMapper;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Projects only an adoption receipt that is still backed by the persisted script revision. */
@Component
public class ScriptArtifactAdoptionEffectProjector implements AssistantArtifactAdoptionEffectProjector {
    private static final Set<String> RECEIPT_KEYS = Set.of("projectId", "episodeId", "scriptId",
            "comicVersion", "previousRevision", "targetRevision", "contentHash");
    private final AidComicScriptMapper scripts;

    public ScriptArtifactAdoptionEffectProjector(AidComicScriptMapper scripts) {
        this.scripts = scripts;
    }

    @Override public String targetKind() { return "SCRIPT"; }

    @Override
    public AssistantTargetEffectProjection project(VerifiedArtifactAdoptionEffect effect) {
        if (effect == null || !targetKind().equals(effect.targetKind()) || effect.projectId() == null) invalid();
        JSONObject receipt;
        try { receipt = JSON.parseObject(effect.receiptJson()); }
        catch (RuntimeException invalid) { receipt = null; }
        String projectId = requiredNonNegative(receipt, "projectId", false);
        String episodeId = requiredNonNegative(receipt, "episodeId", true);
        String scriptId = requiredNonNegative(receipt, "scriptId", false);
        String comicVersion = requiredNonNegative(receipt, "comicVersion", true);
        String contentHash = receipt == null ? null : receipt.getString("contentHash");
        String targetRevision = receipt == null ? null : receipt.getString("targetRevision");
        if (receipt == null || !RECEIPT_KEYS.equals(receipt.keySet())
                || !Objects.equals(String.valueOf(effect.projectId()), projectId)
                || !Objects.equals("project:" + projectId + ":episode:" + episodeId,
                effect.targetObjectId())
                || !Objects.equals(effect.expectedTargetRevision(), receipt.getString("previousRevision"))
                || !Objects.equals(effect.resultTargetRevision(), targetRevision)
                || contentHash == null || !contentHash.matches("[0-9a-f]{64}")
                || !Objects.equals("script:" + scriptId + ":v" + comicVersion + ":" + contentHash,
                targetRevision)) invalid();

        AidComicScript persisted = scripts.selectOne(new LambdaQueryWrapper<AidComicScript>()
                .eq(AidComicScript::getId, Long.valueOf(scriptId))
                .eq(AidComicScript::getUserId, effect.userId())
                .eq(AidComicScript::getDelFlag, "0").last("LIMIT 1"));
        if (persisted == null || !Objects.equals(String.valueOf(persisted.getProjectId()), projectId)
                || !Objects.equals(String.valueOf(persisted.getEpisodeId()), episodeId)
                || !Objects.equals(String.valueOf(persisted.getComicVersion()), comicVersion)
                || !Objects.equals(SecureUtil.sha256(Objects.toString(persisted.getOriginalText(), "")),
                contentHash)) invalid();

        AssistantTargetViews.TargetRef target = new AssistantTargetViews.TargetRef();
        target.setKind("SCRIPT");
        target.setProjectId(projectId);
        target.setEpisodeId(episodeId);
        target.setScriptId(scriptId);
        target.setVersion(comicVersion);
        target.setContentHash(contentHash);
        AssistantRequests.ContextRef identity = new AssistantRequests.ContextRef();
        identity.setKind("SCRIPT");
        identity.setProjectId(Long.valueOf(projectId));
        identity.setEpisodeId(Long.valueOf(episodeId));
        identity.setScriptId(Long.valueOf(scriptId));
        return new AssistantTargetEffectProjection("ADOPTED", target, identity);
    }

    private static String requiredNonNegative(JSONObject value, String field, boolean zeroAllowed) {
        String result = value == null ? null : value.getString(field);
        String pattern = zeroAllowed ? "(?:0|[1-9][0-9]{0,18})" : "[1-9][0-9]{0,18}";
        if (result == null || !result.matches(pattern)) invalid();
        try { Long.parseLong(result); }
        catch (NumberFormatException invalid) { invalid(); }
        return result;
    }

    private static void invalid() { throw new ServiceException("Script adoption receipt is invalid"); }
}
