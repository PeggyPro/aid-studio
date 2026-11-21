package com.aid.assistant.skill;

import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests.ReserveCallRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;
import com.aid.billing.dto.BillingBudgetViews.ReservationView;
import com.aid.billing.dto.BillingBudgetViews.TextQuoteView;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.billing.service.AssistantMediaBillingQuoteService;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.executor.SkillExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Uses the public quote and authorization services; it never creates a second wallet balance. */
@Service
@RequiredArgsConstructor
public class AssistantSkillBillingBridgeImpl implements AssistantSkillBillingBridge {
    private final AssistantMediaBillingQuoteService quoteService;
    private final AssistantBillingBudgetService budgetService;
    private final AssistantBudgetPolicyService budgetPolicyService;
    private final AssistantSkillUnsubmittedReservationReleaseGuard releaseGuard;
    private final AssistantSkillBudgetReservationAdmissionGuard reservationAdmissionGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaTextGenerateRequest quoteReserveAndAttach(SkillExecutionContext context,
                                                          MediaTextGenerateRequest request) {
        if (context == null || request == null) {
            throw new ServiceException("Assistant Skill billing request is invalid");
        }
        AssistantSkillBudgetEnvelope envelope = context.getAssistantBudgetEnvelope();
        AssistantSkillBudgetIdentity.validate(envelope);
        AidSkillRun run = context.getRun();
        AidSkillRunStep step = context.getStep();
        if (run == null || step == null || run.getId() == null || step.getId() == null
                || !Objects.equals(run.getId(), step.getRunId())
                || !Objects.equals(run.getUserId(), envelope.getPayerUserId())
                || !Objects.equals(run.getSkillVersionId(), envelope.getSkillVersionId())
                || !Objects.equals(run.getSkillConfigHash(), envelope.getPackageDigest())
                || !Objects.equals(context.getLogicalCallKey(), request.getCallId())) {
            throw new ServiceException("Assistant Skill billing identity is stale");
        }
        TextQuoteView quote = quoteService.quoteText(request);
        if (quote == null || !quote.isHardUpperBoundAvailable()
                || quote.getNormalizedRequest() == null || quote.getQuotedUpperBound() == null
                || quote.getQuoteDigest() == null) {
            throw new ServiceException("Assistant Skill call has no hard billing upper bound");
        }
        // This graph lock stays held through reserveCall. REPLAN winning first therefore creates no
        // reservation; reserve winning first leaves a durable reservation for Phase 2 to release.
        reservationAdmissionGuard.assertMayReserve(context);
        budgetPolicyService.assertProviderSubmission(envelope.getAssistantRuntimeRunId(),
                envelope.getBillingAuthorizationId(), envelope.getBillingAuthorizationRevision(),
                AssistantSkillBudgetIdentity.providerScope(envelope, quote.getModelCode(),
                        quote.getQuotedUpperBound()));

        ReserveCallRequest reserve = new ReserveCallRequest();
        reserve.setAuthorizationId(envelope.getBillingAuthorizationId());
        reserve.setAuthorizationRevision(envelope.getBillingAuthorizationRevision());
        reserve.setPayerUserId(envelope.getPayerUserId());
        reserve.setRuntimeRunId(envelope.getAssistantRuntimeRunId());
        reserve.setRuntimeStepId(step.getId());
        reserve.setStableStepKey(AssistantSkillBudgetIdentity.stableStepKey(envelope, step));
        reserve.setApprovalId(envelope.getApprovalId());
        reserve.setAttemptNo(step.getWorkflowAttempt());
        reserve.setOperationKind("SKILL_TEXT");
        reserve.setLogicalCallKey(context.getLogicalCallKey());
        reserve.setRequestDigest(quote.getRequestDigest());
        reserve.setQuoteId(quote.getQuoteId());
        reserve.setQuoteDigest(quote.getQuoteDigest());
        reserve.setQuoteExpiresAt(quote.getExpiresAt());
        reserve.setQuotedUpperBound(quote.getQuotedUpperBound());
        reserve.setOperator("skill:" + run.getId());
        ReservationView reservation = budgetService.reserveCall(reserve);

        BillingBudgetContext budget = new BillingBudgetContext();
        budget.setMode(BillingBudgetContext.MODE_RUN_AUTHORIZATION);
        budget.setAuthorizationId(reservation.getAuthorizationId());
        budget.setAuthorizationRevision(reservation.getAuthorizationRevision());
        budget.setReservationId(reservation.getId());
        budget.setReservationRevision(reservation.getRevision());
        budget.setRuntimeRunId(envelope.getAssistantRuntimeRunId());
        budget.setRuntimeStepId(step.getId());
        budget.setRuntimeRunFencingToken(envelope.getAssistantRuntimeRunFencingToken());
        budget.setRuntimeStepFencingToken(envelope.getAssistantParentStepFencingToken());
        budget.setStableStepKey(reservation.getStableStepKey());
        budget.setAttemptNo(step.getWorkflowAttempt());
        budget.setLogicalCallKey(context.getLogicalCallKey());
        budget.setRequestDigest(quote.getRequestDigest());
        budget.setQuoteId(quote.getQuoteId());
        budget.setQuoteDigest(quote.getQuoteDigest());
        budget.setQuoteExpiresAt(quote.getExpiresAt());
        MediaTextGenerateRequest normalized = quote.getNormalizedRequest();
        normalized.setBillingBudgetContext(budget);
        return normalized;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseNotEntered(SkillExecutionContext context,
                                     MediaSubmissionNotEnteredException notEntered) {
        if (context == null || notEntered == null || notEntered.getBudgetContext() == null) {
            throw new ServiceException("Assistant Skill not-entered evidence is invalid");
        }
        BillingBudgetContext budget = notEntered.getBudgetContext();
        AssistantSkillBudgetEnvelope envelope = context.getAssistantBudgetEnvelope();
        AssistantSkillBudgetIdentity.validate(envelope);
        releaseGuard.markNotEntered(context, budget, notEntered.getEvidenceCode());

        ReleaseUnsubmittedReservationRequest release = new ReleaseUnsubmittedReservationRequest();
        release.setAuthorizationId(budget.getAuthorizationId());
        release.setAuthorizationRevision(budget.getAuthorizationRevision());
        release.setReservationId(budget.getReservationId());
        release.setExpectedReservationRevision(budget.getReservationRevision());
        release.setPayerUserId(envelope.getPayerUserId());
        release.setRuntimeRunId(envelope.getAssistantRuntimeRunId());
        release.setRuntimeStepId(context.getStep().getId());
        release.setStableStepKey(budget.getStableStepKey());
        release.setApprovalId(envelope.getApprovalId());
        release.setAttemptNo(context.getStep().getWorkflowAttempt());
        release.setOperationKind(AssistantSkillUnsubmittedReservationReleaseGuard.OPERATION_KIND);
        release.setLogicalCallKey(budget.getLogicalCallKey());
        release.setOperator("skill:" + context.getRun().getId());
        ReservationView outcome = budgetService.releaseUnsubmittedReservation(release);
        if (outcome == null || !("RELEASED".equals(outcome.getState())
                || "RECONCILING".equals(outcome.getState()))) {
            throw new ServiceException("Assistant Skill not-entered release is invalid");
        }
        return "RELEASED".equals(outcome.getState());
    }
}
