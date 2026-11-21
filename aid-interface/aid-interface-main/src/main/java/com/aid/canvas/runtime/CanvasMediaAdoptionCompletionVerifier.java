package com.aid.canvas.runtime;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.NodeResultSelection;
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

/** Verifies a current selected media result against its immutable adoption operation. */
@Component
@RequiredArgsConstructor
public class CanvasMediaAdoptionCompletionVerifier implements AssistantTargetWriteCompletionVerifier {
    private final CanvasAssistantTargetEffectProjector projector;
    private final CanvasAccessService accessService;
    private final CanvasMapper canvasMapper;
    private final CanvasCompletionWriteProgression progression;

    @Override
    public Set<String> supportedToolCodes() {
        return Set.of(CanvasAssistantToolProvider.ADOPT);
    }

    @Override
    public void requireCurrentWrite(VerifiedRuntimeToolEffect effect) {
        if (effect == null || !CanvasAssistantToolProvider.ADOPT.equals(effect.toolCode())) invalid();
        // The projector verifies run/actor/arguments and the exact persisted operation receipt.
        var projection = projector.project(effect);
        var target = projection.resultTargetRef();
        if (!"ADOPTED".equals(projection.effectKind()) || target == null
                || !"FREE_CANVAS".equals(target.getKind())) invalid();
        Long workspaceId = Long.valueOf(target.getWorkspaceId());
        Workspace authorized = accessService.requireWorkspace(workspaceId, effect.userId(), "OWNER", "EDITOR");
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(workspaceId);
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(workspaceId, target.getObjectId());
        if (workspace == null || !Objects.equals(workspace.getProjectId(), authorized.getProjectId())
                || !Objects.equals(String.valueOf(workspace.getProjectId()), target.getProjectId())
                || node == null || !Objects.equals(node.getDeleted(), 0) || !"NODE".equals(node.getObjectType())) invalid();
        progression.requireCurrent(effect, target, node);
        JSONObject output = JSON.parseObject(effect.outputJson());
        JSONObject arguments = JSON.parseObject(effect.argumentsJson());
        NodeResultSelection selection = canvasMapper.selectNodeResultSelection(workspaceId, target.getObjectId());
        if (output == null || arguments == null || selection == null
                || !Objects.equals(selection.getProjectId(), workspace.getProjectId())
                || !Objects.equals(selection.getWorkspaceId(), workspaceId)
                || !Objects.equals(selection.getObjectId(), target.getObjectId())
                || !Objects.equals(selection.getResultId(), arguments.getLong("resultId"))
                || !Objects.equals(selection.getResultId(), output.getLong("resultId"))
                || !Objects.equals(selection.getTaskId(), output.getLong("taskId"))
                || !Objects.equals(selection.getSelectionVersion(), output.getLong("selectionVersion"))
                || !CanvasArtifactCandidateServiceImpl.eligibleInputVersion(
                        selection.getInputObjectVersion(), node.getObjectVersion())) invalid();
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
