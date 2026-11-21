package com.aid.runtime.tool;

import com.aid.billing.dto.BillingBudgetContext;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.runtime.assistant.RuntimeLease;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Trusted persisted invocation reconstructed by the Runtime coordinator. */
@Data
public class RuntimeToolInvocation
{
    private Long runId;
    private Long userId;
    private Long planRevision;
    private Long runtimeStepId;
    private String stableStepId;
    private Integer attemptNo;
    private String operationKind;
    private String toolCode;
    private String contractVersion;
    private String schemaDigest;
    private String argumentsJson;
    private String argumentsDigest;
    private String contextManifestJson;

    /** Coordinator-only evidence: exact observed revisions already proven presentation-equivalent. */
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient String verifiedPresentationRevisionsJson;
    private Long approvalId;
    private String approvedScopeDigest;
    private String idempotencyKey;
    private RuntimeLease lease;
    private AssistantBudgetOperationScope operationScope;

    /** Server-projected prompt including the run's authorized references; never accepted from callers. */
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient String assistantReferencePrompt;

    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    private BillingBudgetContext billingBudgetContext;
}
