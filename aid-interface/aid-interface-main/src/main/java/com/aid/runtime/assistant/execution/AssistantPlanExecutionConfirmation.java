package com.aid.runtime.assistant.execution;

public record AssistantPlanExecutionConfirmation(Long runId,
                                                 long runRevision,
                                                 Long approvalId,
                                                 long approvalRevision,
                                                 Long childStepId,
                                                 Long reservationId,
                                                 String status,
                                                 String nextAction,
                                                 long lastEventSeq)
{
}
