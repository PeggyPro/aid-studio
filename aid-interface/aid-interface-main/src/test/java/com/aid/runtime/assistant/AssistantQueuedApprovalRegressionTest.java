package com.aid.runtime.assistant;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.MediaSubmissionAdmissionDecision;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise actual provider admission after queueing, including independent budget and identity gates. */
class AssistantQueuedApprovalRegressionTest {
    static final class Fixture {
        final AssistantRuntimeSubmissionAdmissionHandler handler = new AssistantRuntimeSubmissionAdmissionHandler();
        final AssistantBillingBudgetService budget = mock(AssistantBillingBudgetService.class);
        final AssistantBudgetPolicyService policy = mock(AssistantBudgetPolicyService.class);
        final AidRuntimeRunMapper runs = mock(AidRuntimeRunMapper.class);
        final AidRuntimeRunStepMapper steps = mock(AidRuntimeRunStepMapper.class);
        final AidRuntimeApprovalMapper approvals = mock(AidRuntimeApprovalMapper.class);
        final AidAssistantSessionMapper sessions = mock(AidAssistantSessionMapper.class);
        final AidRuntimeRun run = new AidRuntimeRun();
        final AidRuntimeRunStep step = new AidRuntimeRunStep();
        final AidRuntimeApproval approval = new AidRuntimeApproval();
        final AidAssistantSession session = new AidAssistantSession();
        final AidMediaTask task = new AidMediaTask();
        final BillingSnapshot snapshot = new BillingSnapshot();
        Fixture() throws Exception {
            session.setId(1L); session.setSessionType("ASSISTANT"); session.setUserId(2L); session.setActiveRuntimeRunId(3L);
            run.setId(3L); run.setSessionId(1L); run.setUserId(2L); run.setBudgetAuthorizationId(4L);
            run.setStatus("RUNNING"); run.setExecutorStrategy("ASSISTANT_LOOP"); run.setPlanRevision(0L);
            run.setContextDigest("context"); run.setFencingToken(1L);
            step.setId(5L); step.setRunId(3L); step.setApprovalId(6L); step.setReservationId(7L);
            step.setStableStepId("main"); step.setAttemptNo(0); step.setPlanRevision(0L); step.setFencingToken(1L);
            step.setStatus("READY"); step.setProviderAcceptanceState("NOT_SUBMITTED"); step.setMediaTaskId(8L);
            step.setOperationKind("ASSISTANT_DECISION");
            approval.setId(6L); approval.setRunId(3L); approval.setUserId(2L); approval.setStatus("ACCEPTED");
            approval.setPlanRevision(0L); approval.setBillingAuthorizationId(4L); approval.setBillingAuthorizationRevision(1L);
            approval.setRequestDigest("request"); approval.setQuoteId("quote"); approval.setQuoteDigest("digest");
            approval.setContextDigest("context"); approval.setScopeKind("ASSISTANT_ITERATION");
            long now = System.currentTimeMillis();
            approval.setAcceptedAt(new Date(now - 900_000L)); approval.setExpiresAt(new Date(now - 600_000L));
            task.setId(8L); task.setUserId(2L); task.setBizTaskId(5L); task.setBizTaskType("ASSISTANT_RUNTIME_STEP");
            task.setIdempotencyKey("call"); task.setModelName("text-model"); task.setStatus("PENDING");
            snapshot.setBudgetMode("RUN_AUTHORIZATION"); snapshot.setRuntimeRunId(3L); snapshot.setRuntimeStepId(5L);
            snapshot.setBillingAuthorizationId(4L); snapshot.setBillingAuthorizationRevision(1L);
            snapshot.setBillingReservationId(7L); snapshot.setBillingReservationRevision(1L);
            snapshot.setRuntimeRunFencingToken(1L); snapshot.setRuntimeStepFencingToken(1L);
            snapshot.setStableStepKey("main"); snapshot.setBillingAttemptNo(0); snapshot.setLogicalCallKey("call");
            snapshot.setRequestDigest("request"); snapshot.setQuoteId("quote"); snapshot.setQuoteDigest("digest");
            snapshot.setAuthorizedChargeCap(BigDecimal.ONE);
            when(runs.selectById(3L)).thenReturn(run); when(runs.selectOne(any())).thenReturn(run);
            when(steps.selectById(5L)).thenReturn(step); when(steps.selectOne(any())).thenReturn(step);
            when(steps.updateById(any(AidRuntimeRunStep.class))).thenReturn(1);
            when(approvals.selectOne(any())).thenReturn(approval); when(sessions.selectOne(any())).thenReturn(session);
            var claimed = new BillingBudgetViews.SubmissionClaimResult(); claimed.setNewlyClaimed(true);
            when(budget.claimSubmission(any())).thenReturn(claimed);
            inject("runMapper", runs); inject("stepMapper", steps); inject("approvalMapper", approvals);
            inject("sessionMapper", sessions); inject("budgetService", budget); inject("budgetPolicyService", policy);
        }
        void inject(String name, Object value) throws Exception { Field f = handler.getClass().getDeclaredField(name); f.setAccessible(true); f.set(handler, value); }
        MediaSubmissionAdmissionDecision claim() { return handler.claimBeforeProvider(task, snapshot); }
        void assertNoBudgetClaim() { verifyNoInteractions(budget); }
    }
    @Test void acceptedQuoteMayQueuePastOfferExpiryWhileRunBudgetRemainsActive() throws Exception {
        var f = new Fixture(); assertEquals(MediaSubmissionAdmissionDecision.PROCEED_NEW_CLAIM, f.claim());
        verify(f.policy).assertProviderSubmission(eq(3L), eq(4L), eq(1L), any(com.aid.assistant.budget.AssistantBudgetOperationScope.class));
        verify(f.budget).claimSubmission(any()); assertEquals("SUBMITTING", f.step.getStatus());
    }
    @Test void ordinaryUnexpiredAcceptedQuoteStillProceeds() throws Exception {
        var f = new Fixture(); f.approval.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        assertEquals(MediaSubmissionAdmissionDecision.PROCEED_NEW_CLAIM, f.claim());
    }
    @Test void acceptanceAtOrAfterQuoteExpiryIsRejectedBeforeBudgetClaim() throws Exception {
        for (long offset : new long[]{0L, 1L}) { var f = new Fixture(); f.approval.setAcceptedAt(new Date(f.approval.getExpiresAt().getTime()+offset)); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    }
    @Test void missingAcceptanceTimeIsRejected() throws Exception { var f = new Fixture(); f.approval.setAcceptedAt(null); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void missingQuoteExpiryIsRejected() throws Exception { var f = new Fixture(); f.approval.setExpiresAt(null); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void futureAcceptanceCannotAuthorizeExecution() throws Exception { var f = new Fixture(); f.approval.setAcceptedAt(new Date(System.currentTimeMillis()+60_000L)); f.approval.setExpiresAt(new Date(System.currentTimeMillis()+120_000L)); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void unacceptedQuoteCannotAuthorizeExecution() throws Exception { var f = new Fixture(); f.approval.setStatus("QUOTED"); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void expiredOrRevokedRunPolicyStillBlocksProviderAdmission() throws Exception {
        var f = new Fixture(); doThrow(new ServiceException("POLICY_INACTIVE")).when(f.policy).assertProviderSubmission(eq(3L), eq(4L), eq(1L), any(com.aid.assistant.budget.AssistantBudgetOperationScope.class));
        assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim();
    }
    @Test void billingAuthorizationExpiryStillReachesItsIndependentGuard() throws Exception { var f = new Fixture(); when(f.budget.claimSubmission(any())).thenThrow(new ServiceException("AUTHORIZATION_EXPIRED")); assertThrows(ServiceException.class, f::claim); verify(f.budget).claimSubmission(any()); }
    @Test void changedContextStillBlocksAdmission() throws Exception { var f = new Fixture(); f.run.setContextDigest("changed"); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void changedTaskBindingStillBlocksAdmission() throws Exception { var f = new Fixture(); f.step.setMediaTaskId(9L); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim(); }
    @Test void stopAndPauseDoNotStartPreviouslyQueuedProviderCall() throws Exception { for (boolean stop : new boolean[]{true, false}) { var f = new Fixture(); if(stop) f.run.setStopRequested(true); else f.run.setStatus("PAUSED"); assertEquals(MediaSubmissionAdmissionDecision.DEFER_UNTIL_RESUME, f.claim()); f.assertNoBudgetClaim(); verifyNoInteractions(f.policy); } }
    @Test void alreadyAdmittedTaskCannotBeSubmittedAgainAfterQuoteExpiry() throws Exception { var f = new Fixture(); f.step.setStatus("INFLIGHT"); f.step.setProviderAcceptanceState("ACCEPTED"); assertEquals(MediaSubmissionAdmissionDecision.RECONCILE_ONLY, f.claim()); f.assertNoBudgetClaim(); verifyNoInteractions(f.policy); }
    @Test void revokedCanvasEditPermissionStillBlocksBeforeBudgetClaim() throws Exception {
        var f = new Fixture(); var guard = mock(AssistantRuntimeSubmissionScopeGuard.class);
        when(guard.supportedContextKind()).thenReturn("FREE_CANVAS"); doThrow(new ServiceException("permission revoked")).when(guard).assertStillAuthorized(any());
        f.run.setWorkspaceId(10L); f.inject("scopeGuards", List.of(guard)); assertThrows(ServiceException.class, f::claim); f.assertNoBudgetClaim();
    }
}
