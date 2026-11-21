package com.aid.canvas.runtime;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Accepts persisted node creation and text updates with exact, still-current write receipts. */
@Component
@RequiredArgsConstructor
public class CanvasTextTargetWriteCompletionVerifier implements AssistantTargetWriteCompletionVerifier {
    private final CanvasAssistantTargetEffectProjector projector;
    private final CanvasAccessService accessService;
    private final CanvasMapper canvasMapper;
    private final CanvasCompletionWriteProgression progression;

    @Override
    public Set<String> supportedToolCodes() {
        var codes = new java.util.LinkedHashSet<>(CanvasAssistantTargetEffectProjector.creationToolCodes());
        codes.add(CanvasAssistantToolProvider.NODE_PATCH);
        return Set.copyOf(codes);
    }

    @Override
    public void requireCurrentWrite(VerifiedRuntimeToolEffect effect) {
        var projection = projector.project(effect);
        var target = projection.resultTargetRef();
        if (!Set.of("CREATED", "UPDATED").contains(projection.effectKind()) || target == null
                || !"FREE_CANVAS".equals(target.getKind())) invalid();
        Long workspaceId = Long.valueOf(target.getWorkspaceId());
        Workspace authorized = accessService.requireWorkspace(workspaceId, effect.userId(), "OWNER", "EDITOR");
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(workspaceId);
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(workspaceId, target.getObjectId());
        if (workspace == null || !Objects.equals(workspace.getProjectId(), authorized.getProjectId())
                || !Objects.equals(String.valueOf(workspace.getProjectId()), target.getProjectId())
                || node == null || !Objects.equals(node.getDeleted(), 0) || !"NODE".equals(node.getObjectType())) invalid();
        progression.requireCurrent(effect, target, node);
        JSONObject content = JSON.parseObject(node.getContentJson());
        if (content == null) invalid();
        boolean textNode = "TEXT".equals(content.getString("nodeType"));
        // Existing patch completion remains text-only. Creation uses the same verified
        // operation receipt as the Canvas projector, and proves creation, never generation.
        if (!textNode && !CanvasAssistantTargetEffectProjector.creationToolCodes().contains(effect.toolCode())) invalid();
        Object body = content.get(textNode ? "textContent" : "prompt");
        if (!(body instanceof String) || textNode && ((String) body).isBlank()) invalid();
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
