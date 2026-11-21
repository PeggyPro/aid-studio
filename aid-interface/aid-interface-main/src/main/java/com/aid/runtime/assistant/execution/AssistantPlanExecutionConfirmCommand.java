package com.aid.runtime.assistant.execution;

/** User-confirmed, revision-bound operation approval command. */
public record AssistantPlanExecutionConfirmCommand(Long userId,
                                                   Long runId,
                                                   Long approvalId,
                                                   long expectedRunRevision,
                                                   long expectedApprovalRevision,
                                                   String clientRequestId,
                                                   boolean confirmRenewedExecution)
{
    public AssistantPlanExecutionConfirmCommand(Long userId,Long runId,Long approvalId,
            long expectedRunRevision,long expectedApprovalRevision,String clientRequestId) {
        this(userId,runId,approvalId,expectedRunRevision,expectedApprovalRevision,clientRequestId,false);
    }
}
