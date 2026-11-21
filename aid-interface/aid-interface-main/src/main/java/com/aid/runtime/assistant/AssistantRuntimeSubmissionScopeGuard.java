package com.aid.runtime.assistant;

import lombok.Builder;
import lombok.Value;

/**
 * Optional server-side final authorization guard for a scoped provider call.
 * It receives only persisted identities and fences, never a browser token or session.
 */
public interface AssistantRuntimeSubmissionScopeGuard
{
    String supportedContextKind();

    void assertStillAuthorized(Context context);

    @Value
    @Builder
    class Context
    {
        String contextKind;
        Long runId;
        Long userId;
        Long projectId;
        Long workspaceId;
        String objectId;
        Long objectVersion;
        String executorStrategy;
        String runtimeLeaseOwner;
        Long runtimeFencingToken;
        Long stepId;
        String toolCode;
        String operationKind;
        Long submissionFencingToken;
        Long mediaTaskId;
        /** TASK_INTENT or PROVIDER_CLAIM, fixed by the admission handler, never the model/browser. */
        String submissionPhase;

    }
}
