package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.budget.AssistantBudgetOperationAdmission;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeRun;

import java.math.BigDecimal;

/** Runtime policy boundary layered over the monetary Billing authorization. */
public interface AssistantBudgetPolicyService
{
    String executionMode(AidRuntimeRun run);

    BillingBudgetViews.AuthorizationView ensureOperationPolicy(Long userId, AidRuntimeRun run,
            AssistantExecutionRequests.RunBudgetPolicy defaults, String operator);

    Confirmation confirmCall(Long userId, AidRuntimeRun run, AidRuntimeApproval approval,
                             AssistantExecutionRequests.RunBudgetPolicy requested, String operator);

    default Confirmation confirmCall(Long userId, AidRuntimeRun run, AidRuntimeApproval approval,
                                     AssistantExecutionRequests.RunBudgetPolicy requested,
                                     boolean confirmRenewedExecution, String operator) {
        if (confirmRenewedExecution) throw new UnsupportedOperationException("续授权确认不可用");
        return confirmCall(userId, run, approval, requested, operator);
    }

    AssistantExecutionViews.BudgetPolicy detail(Long userId, AssistantExecutionRequests.BudgetDetail request);

    AssistantExecutionViews.BudgetRevision revise(Long userId, AssistantExecutionRequests.BudgetRevise request);

    void recordScheduledIteration(Long runId, Long authorizationId, Long authorizationRevision,
                                  String modelCode, String operationKind, BigDecimal quotedUpperBound,
                                  String operator);

    void assertProviderSubmission(Long runId, Long authorizationId, Long authorizationRevision,
                                  String modelCode, String operationKind, BigDecimal authorizedChargeCap);

    AssistantBudgetOperationAdmission admitScheduledOperation(Long runId,
                                                              AssistantBudgetOperationScope operationScope,
                                                              String operator);

    /** Validates a manual operation before approval without consuming its counters. */
    AssistantBudgetOperationAdmission assertOperationSchedulable(Long runId,
                                                                 AssistantBudgetOperationScope operationScope);

    /** Validates quote-only scope without clearing a current renewal confirmation hold. */
    default AssistantBudgetOperationAdmission assertOperationQuotable(Long runId,
                                                                      AssistantBudgetOperationScope operationScope) {
        throw new UnsupportedOperationException("操作报价不可用");
    }

    /** Called only after an explicit operation confirmation has verified its exact current grant. */
    default AssistantBudgetOperationAdmission admitScheduledOperation(Long runId,
                                                                      AssistantBudgetOperationScope operationScope,
                                                                      String operator,
                                                                      boolean confirmRenewedExecution) {
        if (confirmRenewedExecution) throw new UnsupportedOperationException("续授权确认不可用");
        return admitScheduledOperation(runId, operationScope, operator);
    }

    void assertProviderSubmission(Long runId, Long authorizationId, Long authorizationRevision,
                                  AssistantBudgetOperationScope operationScope);

    record Confirmation(AidRuntimeBudgetPolicy policy,
                        BillingBudgetViews.AuthorizationView authorization) { }
}
