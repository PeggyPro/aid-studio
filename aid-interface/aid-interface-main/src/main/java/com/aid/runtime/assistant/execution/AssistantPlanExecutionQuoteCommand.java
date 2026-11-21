package com.aid.runtime.assistant.execution;

/** Quote-only user intent; revalidation token never grants submission authority. */
public record AssistantPlanExecutionQuoteCommand(Long userId,Long runId,Long approvalId,
        long expectedRunRevision,long expectedApprovalRevision,String clientRequestId,String referenceRevalidationToken) {
    public AssistantPlanExecutionQuoteCommand(Long userId,Long runId,Long approvalId,long expectedRunRevision,
            long expectedApprovalRevision,String clientRequestId) {
        this(userId,runId,approvalId,expectedRunRevision,expectedApprovalRevision,clientRequestId,null);
    }
}
