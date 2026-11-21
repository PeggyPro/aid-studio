package com.aid.runtime.assistant.admission;

import com.aid.runtime.assistant.RuntimeLease;
import lombok.Data;

/** Trusted identity presented inside the Provider's own persistence transaction. */
@Data
public class RuntimeOperationSubmissionClaim
{
    private Long runId;
    private Long userId;
    private Long stepId;
    private Long approvalId;
    private String operationKind;
    private String idempotencyKey;
    private RuntimeLease runLease;
    private RuntimeLease stepLease;
}
