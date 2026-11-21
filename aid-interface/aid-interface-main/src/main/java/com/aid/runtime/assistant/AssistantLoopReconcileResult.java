package com.aid.runtime.assistant;

import lombok.Data;

import java.util.Date;

/** Result of reconciling persisted loop/task state. */
@Data
public class AssistantLoopReconcileResult
{
    private Long runId;
    private Long stateRevision;
    private String status;
    private boolean recovered;
    private Date nextRetryAt;
}
