package com.aid.assistant.skill;

import lombok.Data;

/** Server-only authorization identity persisted with an Assistant-triggered Skill run. */
@Data
public class AssistantSkillBudgetEnvelope {
    private Long assistantRuntimeRunId;
    private Long assistantParentStepId;
    /** Creation-ticket fences captured when the outer Assistant Skill operation was claimed. */
    private Long assistantRuntimeRunFencingToken;
    private Long assistantParentStepFencingToken;
    private String assistantOuterStableStepKey;
    private Integer assistantAttemptNo;
    private Long billingAuthorizationId;
    private Long billingAuthorizationRevision;
    private Long payerUserId;
    private Long approvalId;
    private Long skillVersionId;
    private String packageDigest;
    private String approvedScopeDigest;
    /** Exact approved operation scope; persisted with the Skill run and rechecked before every Provider call. */
    private String approvedOperationScopeJson;
    private String approvedOperationScopeDigest;
}
