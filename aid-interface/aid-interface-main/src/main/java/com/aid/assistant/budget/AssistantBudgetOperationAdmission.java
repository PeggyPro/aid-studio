package com.aid.assistant.budget;

/** Authoritative run-policy identity captured by the child operation approval. */
public record AssistantBudgetOperationAdmission(
        Long authorizationId,
        Long authorizationRevision,
        Long policyRevision,
        String policyMode,
        String scopeDigest,
        boolean scopeOnly)
{
}
