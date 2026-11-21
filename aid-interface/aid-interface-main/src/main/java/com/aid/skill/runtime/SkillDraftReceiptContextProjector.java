package com.aid.skill.runtime;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.service.AssistantToolReceiptProjector;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Current private draft receipt for model context; never a canvas reference or publication. */
@Component
@RequiredArgsConstructor
public class SkillDraftReceiptContextProjector implements AssistantToolReceiptProjector {
    private final SkillDraftTargetWriteCompletionVerifier currentDraft;

    @Override
    public Set<String> supportedToolCodes() {
        return com.aid.runtime.assistant.NativeSkillAuthoringContract.proposalToolCodes();
    }

    @Override
    public JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect effect) {
        currentDraft.requireCurrentWrite(effect);
        JSONObject output = JSON.parseObject(effect.outputJson());
        JSONObject receipt = new JSONObject();
        receipt.put("receiptKind", "PRIVATE_SKILL_DRAFT");
        receipt.put("skillId", String.valueOf(output.getLong("skillId")));
        receipt.put("draftId", String.valueOf(output.getLong("draftId")));
        receipt.put("creatorSkillVersionId", String.valueOf(output.getLong("creatorSkillVersionId")));
        for (String field : List.of("draftDigest", "visibility", "status", "published", "sourceMode",
                "creatorPackageDigest", "creatorMasterDigest", "creatorBranchPath", "creatorBranchDigest")) {
            if (output.containsKey(field)) receipt.put(field, output.get(field));
        }
        return receipt;
    }
}
