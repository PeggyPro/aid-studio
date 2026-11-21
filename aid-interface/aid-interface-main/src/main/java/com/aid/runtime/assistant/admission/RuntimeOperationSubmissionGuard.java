package com.aid.runtime.assistant.admission;

/** Called as the first database action inside a writing Tool or Skill transaction. */
public interface RuntimeOperationSubmissionGuard
{
    RuntimeOperationSubmissionDecision claim(RuntimeOperationSubmissionClaim claim);

    /** Checks and locks the same graph without consuming the durable media task's submission claim. */
    default RuntimeOperationSubmissionDecision assertBeforeTaskBinding(RuntimeOperationSubmissionClaim claim) {
        throw new com.aid.common.exception.ServiceException("OPERATION_TASK_BINDING_GUARD_UNAVAILABLE");
    }

    /** Records the child receipt in its creation transaction, before its first reservation. */
    default void attachSkillRun(RuntimeOperationSubmissionClaim claim, Long childRunId) {
        throw new com.aid.common.exception.ServiceException("OPERATION_SKILL_BINDING_GUARD_UNAVAILABLE");
    }

}
