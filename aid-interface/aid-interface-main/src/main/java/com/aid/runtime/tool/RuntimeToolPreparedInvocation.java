package com.aid.runtime.tool;

import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.service.RuntimeToolRegistry;
import lombok.Data;

/** Durable, digest-bound Tool preparation consumed without re-normalizing provider input. */
@Data
public class RuntimeToolPreparedInvocation
{
    private RuntimeToolRegistry.Descriptor descriptor;
    private Long runId;
    private Long userId;
    private Long runtimeStepId;
    private Long planRevision;
    private String stableStepId;
    private Integer attemptNo;
    private String operationKind;
    private String toolCode;
    private String contractVersion;
    private String schemaDigest;
    private String argumentsJson;
    private String argumentsDigest;
    private String contextManifestJson;
    private Long approvalId;
    private String approvedScopeDigest;
    private String idempotencyKey;
    /** Run and child leases use independent fencing sequences and must both be checked. */
    private RuntimeLease runLease;
    private RuntimeLease stepLease;
    private AssistantBudgetOperationScope operationScope;
    private String preparedPayloadJson;
    private String preparedPayloadDigest;
    private RuntimeToolBillingIntent billingIntent;
}
