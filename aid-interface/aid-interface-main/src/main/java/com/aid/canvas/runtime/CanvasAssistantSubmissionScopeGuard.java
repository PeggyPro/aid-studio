package com.aid.canvas.runtime;

import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.AssistantRuntimeSubmissionScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Final trusted authorization check immediately before a Canvas-bound provider claim. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantSubmissionScopeGuard implements AssistantRuntimeSubmissionScopeGuard {
    private static final String CONTEXT_KIND = "FREE_CANVAS";
    private static final String EXECUTOR = "ASSISTANT_LOOP";

    private final CanvasAccessService accessService;
    private final CanvasCollaborationService collaborationService;
    private final com.aid.runtime.mapper.AidRuntimeRunMapper runs;
    private final com.aid.runtime.mapper.AidRuntimeRunStepMapper steps;
    private final CanvasAssistantMediaBindingVerifier mediaBindings;


    @Override
    public String supportedContextKind() {
        return CONTEXT_KIND;
    }

    @Override
    public void assertStillAuthorized(Context context) {
        if (context == null || !CONTEXT_KIND.equals(context.getContextKind())
                || !EXECUTOR.equals(context.getExecutorStrategy())
                || context.getRunId() == null || context.getRunId() < 1
                || context.getUserId() == null || context.getUserId() < 1
                || context.getProjectId() != null && context.getProjectId() < 1
                || context.getWorkspaceId() == null || context.getWorkspaceId() < 1
                || context.getStepId() == null || context.getStepId() < 1
                || context.getRuntimeFencingToken() == null || context.getRuntimeFencingToken() < 1
                || context.getSubmissionFencingToken() == null || context.getSubmissionFencingToken() < 1) {
            throw failure("CANVAS_SUBMISSION_FENCED");
        }
        boolean hasObject = !blank(context.getObjectId());
        if (hasObject != (context.getObjectVersion() != null)
                || hasObject && context.getObjectVersion() < 1) {
            throw failure("CANVAS_SUBMISSION_TARGET_INVALID");
        }

        Workspace workspace = accessService.requireWorkspace(context.getWorkspaceId(), context.getUserId(),
                "OWNER", "EDITOR");
        if (context.getProjectId() != null
                && !Objects.equals(context.getProjectId(), workspace.getProjectId())) {
            throw failure("CANVAS_PROJECT_SCOPE_CHANGED");
        }
        // Assistant sessions may carry only a Canvas workspace. Its authorized project is
        // authoritative; the optional session project belongs to the script workspace scope.
        accessService.requireFeature(context.getUserId(), workspace.getProjectId(), "canvas.generate");

        if (context.getToolCode() != null && CanvasAssistantMediaToolProvider.CODES.contains(context.getToolCode())) {
            boolean taskIntent = "TASK_INTENT".equals(context.getSubmissionPhase());
            if (!taskIntent && !"PROVIDER_CLAIM".equals(context.getSubmissionPhase())) {
                throw failure("CANVAS_MEDIA_SUBMISSION_PHASE_INVALID");
            }
            var run = runs.selectById(context.getRunId());
            var step = steps.selectById(context.getStepId());
            var proof = mediaBindings.require(context.getUserId(), run, step, context.getMediaTaskId(), taskIntent);
            if (!Objects.equals(proof.target().workspaceId(), context.getWorkspaceId())) {
                throw failure("CANVAS_MEDIA_SUBMISSION_WORKSPACE_CHANGED");
            }
            collaborationService.requireAssistantMediaTaskLock(proof.target().workspaceId(), proof.target().objectId(),
                    proof.target().objectVersion(), context.getUserId(), proof.binding().getOperationId(),
                    proof.binding().getCallId(), context.getMediaTaskId());
            return;
        }

        if (hasObject) {
            collaborationService.requireAssistantRuntimeFence(context.getWorkspaceId(), context.getObjectId(),
                    context.getObjectVersion(), context.getUserId(), context.getRunId(),
                    context.getRuntimeFencingToken());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ServiceException failure(String code) {
        return new ServiceException("\u753b\u5e03\u6388\u6743\u5931\u6548").setDetailMessage(code);
    }
}
