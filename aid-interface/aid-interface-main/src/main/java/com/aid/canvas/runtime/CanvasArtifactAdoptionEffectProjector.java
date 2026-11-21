package com.aid.canvas.runtime;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.effect.AssistantArtifactAdoptionEffectProjector;
import com.aid.assistant.effect.AssistantTargetEffectProjection;
import com.aid.assistant.effect.VerifiedArtifactAdoptionEffect;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Validates the durable Canvas adoption receipt before exposing its target. */
@Component
@RequiredArgsConstructor
public class CanvasArtifactAdoptionEffectProjector implements AssistantArtifactAdoptionEffectProjector {
    private static final Set<String> RECEIPT_KEYS = Set.of("objectId", "previousVersion", "newVersion",
            "workspaceId", "operationId", "artifactId", "contentHash");
    private final CanvasAssistantOperationReceiptVerifier receiptVerifier;

    @Override
    public String targetKind() {
        return "FREE_CANVAS";
    }

    @Override
    public AssistantTargetEffectProjection project(VerifiedArtifactAdoptionEffect effect) {
        if (effect == null || !targetKind().equals(effect.targetKind()) || effect.projectId() == null
                || effect.workspaceId() == null) invalid();
        JSONObject receipt;
        try { receipt = JSON.parseObject(effect.receiptJson()); }
        catch (RuntimeException error) { receipt = null; }
        if (receipt == null || !RECEIPT_KEYS.equals(receipt.keySet())
                || !Objects.equals(effect.targetObjectId(), receipt.getString("objectId"))
                || !Objects.equals(effect.expectedTargetRevision(), receipt.getString("previousVersion"))
                || !Objects.equals(effect.resultTargetRevision(), receipt.getString("newVersion"))
                || !Objects.equals(String.valueOf(effect.workspaceId()), receipt.getString("workspaceId"))
                || !Objects.equals(String.valueOf(effect.artifactId()), receipt.getString("artifactId"))
                || !Objects.equals(effect.artifactContentHash(), receipt.getString("contentHash"))
                || receipt.getString("operationId") == null || receipt.getString("operationId").isBlank()
                || !positive(effect.expectedTargetRevision()) || !positive(effect.resultTargetRevision())
                || Long.parseLong(effect.resultTargetRevision()) <= Long.parseLong(effect.expectedTargetRevision())) {
            invalid();
        }
        receiptVerifier.requireArtifactAdoption(effect, receipt);
        AssistantTargetViews.TargetRef target = new AssistantTargetViews.TargetRef();
        target.setKind("FREE_CANVAS");
        target.setProjectId(String.valueOf(effect.projectId()));
        target.setWorkspaceId(String.valueOf(effect.workspaceId()));
        target.setObjectId(effect.targetObjectId());
        target.setVersion(effect.resultTargetRevision());
        AssistantRequests.ContextRef identity = new AssistantRequests.ContextRef();
        identity.setKind("FREE_CANVAS");
        identity.setProjectId(effect.projectId());
        identity.setWorkspaceId(effect.workspaceId());
        identity.setObjectId(effect.targetObjectId());
        return new AssistantTargetEffectProjection("ADOPTED", target, identity);
    }

    private static boolean positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static void invalid() {
        throw new ServiceException("Canvas adoption receipt is invalid");
    }
}
