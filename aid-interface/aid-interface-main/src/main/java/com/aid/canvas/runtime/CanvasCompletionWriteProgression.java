package com.aid.canvas.runtime;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Completion uses the same durable progression proof as execution, never a relaxed version comparison. */
@Component
@RequiredArgsConstructor
public class CanvasCompletionWriteProgression {
    private final AssistantRuntimeContextManifestService manifests;
    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;
    private final CanvasMapper canvas;

    /** Caller has already verified the immutable receipt, access and locked current workspace/node. */
    public void requireCurrent(VerifiedRuntimeToolEffect effect, AssistantTargetViews.TargetRef target,
                               CanvasObject node) {
        if (Objects.equals(String.valueOf(node.getObjectVersion()), target.getVersion())) return;
        if (node.getObjectVersion() == null || node.getObjectVersion() <= Long.parseLong(target.getVersion())) invalid();
        var run = runs.selectById(effect.runId());
        var session = sessions.selectById(effect.sessionId());
        if (run == null || session == null || !"0".equals(run.getDelFlag())
                || !"0".equals(session.getDelFlag()) || !Objects.equals(run.getSessionId(), effect.sessionId())) invalid();
        // Replays from the original signed run authority, including generation, selection and
        // resource projection receipts. Starting at a later tool would lose prior selection proof.
        var current = manifests.requirePinned(effect.userId(), session, run);
        long matching = current.executionContextRefs().stream().filter(ref ->
                "FREE_CANVAS".equals(ref.getKind())
                && Objects.equals(String.valueOf(ref.getProjectId()), target.getProjectId())
                && Objects.equals(String.valueOf(ref.getWorkspaceId()), target.getWorkspaceId())
                && Objects.equals(ref.getObjectId(), target.getObjectId())
                && Objects.equals(ref.getVersion(), String.valueOf(node.getObjectVersion()))).count();
        if (matching != 1) invalid();
        var output = JSON.parseObject(effect.outputJson());
        String receipt = output.getString(CanvasAssistantToolProvider.ADOPT.equals(effect.toolCode())
                ? "operationId" : "receiptId");
        Long workspaceId = Long.valueOf(target.getWorkspaceId());
        var original = canvas.selectOperation(workspaceId, receipt);
        var workspace = canvas.selectWorkspaceForUpdate(workspaceId);
        if (original == null || workspace == null || original.getWorkspaceRevision() == null
                || workspace.getRevision() == null || original.getWorkspaceRevision() > workspace.getRevision()) invalid();
        long revision = original.getWorkspaceRevision();
        while (revision < workspace.getRevision()) {
            var operations = canvas.selectOperationsAfterRevision(workspaceId, revision, 200);
            if (operations == null || operations.isEmpty()) invalid();
            for (var operation : operations) {
                if (revision >= workspace.getRevision()) break;
                if (operation == null || !Objects.equals(operation.getWorkspaceRevision(), revision + 1)) invalid();
                var changes = canvas.selectOperationObjects(operation.getId());
                if (changes == null || changes.stream().anyMatch(Objects::isNull)) invalid();
                // Even a later authorized semantic patch/adoption supersedes this particular write.
                // MUTATE is safe only because requirePinned above proved every referenced mutation
                // was presentation-only or a projection of this run's verified selected result.
                if (!"MUTATE".equals(operation.getOperationType()) && changes.stream()
                        .anyMatch(change -> Objects.equals(change.getObjectId(), target.getObjectId()))) invalid();
                revision++;
            }
        }
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
