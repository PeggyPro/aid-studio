package com.aid.runtime.assistant;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.skill.*;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.admission.*;
import com.aid.runtime.domain.*;
import com.aid.runtime.mapper.*;
import com.aid.skill.domain.*;
import com.aid.skill.executor.SkillExecutionContext;
import com.aid.skill.mapper.*;
import cn.hutool.crypto.SecureUtil;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantSkillCreationReceiptRegressionTest {
    static void field(Object o,String n,Object value)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
    static AssistantSkillBudgetEnvelope envelope() {
        var e=new AssistantSkillBudgetEnvelope();e.setAssistantRuntimeRunId(44L);e.setAssistantParentStepId(57L);e.setAssistantRuntimeRunFencingToken(1L);e.setAssistantParentStepFencingToken(1L);e.setAssistantOuterStableStepKey("method");e.setAssistantAttemptNo(0);e.setPayerUserId(48L);e.setApprovalId(17L);e.setBillingAuthorizationId(23L);e.setBillingAuthorizationRevision(3L);e.setSkillVersionId(73L);e.setPackageDigest("a".repeat(64));e.setApprovedScopeDigest("b".repeat(64));e.setApprovedOperationScopeJson("{\"operationKind\":\"SKILL_TEXT\",\"quotedUpperBound\":10}");e.setApprovedOperationScopeDigest(SecureUtil.sha256(e.getApprovedOperationScopeJson()));
        return e;
    }
    static class Fixture {
        final RuntimeOperationSubmissionGuardImpl guard=new RuntimeOperationSubmissionGuardImpl();
        final AidAssistantSession session=new AidAssistantSession(); final AidRuntimeRun run=new AidRuntimeRun();
        final AidRuntimeRunStep step=new AidRuntimeRunStep(); final AidRuntimeApproval approval=new AidRuntimeApproval();
        final AidAssistantSessionMapper sessions=mock(AidAssistantSessionMapper.class);
        final AidRuntimeRunMapper runs=mock(AidRuntimeRunMapper.class);
        final AidRuntimeApprovalMapper approvals=mock(AidRuntimeApprovalMapper.class);
        final AidRuntimeRunStepMapper steps=mock(AidRuntimeRunStepMapper.class);
        final AssistantBudgetPolicyService budget=mock(AssistantBudgetPolicyService.class);
        final RuntimeOperationSubmissionClaim claim=new RuntimeOperationSubmissionClaim();
        final AidSkillRun child=new AidSkillRun();
        Fixture()throws Exception {
            Date until=new Date(System.currentTimeMillis()+60000);
            RuntimeLease lease=new RuntimeLease();lease.setOwner("worker");lease.setFencingToken(1L);lease.setLeaseUntil(until);
            session.setId(134L);session.setSessionType("ASSISTANT");session.setUserId(48L);session.setActiveRuntimeRunId(44L);
            run.setId(44L);run.setSessionId(134L);run.setUserId(48L);run.setStatus("RUNNING");run.setLeaseOwner("worker");run.setLeaseUntil(until);run.setFencingToken(1L);run.setBudgetAuthorizationId(23L);
            step.setStableStepId("method");step.setAttemptNo(0);step.setId(57L);step.setRunId(44L);step.setStepKind("SKILL");step.setOperationKind("ASSISTANT_SKILL");step.setApprovalId(17L);step.setIdempotencyKey("frozen-key");step.setStatus("SUBMITTING");step.setProviderAcceptanceState("ACCEPTED");step.setLeaseOwner("worker");step.setLeaseUntil(until);step.setFencingToken(1L);
            approval.setId(17L);approval.setRunId(44L);approval.setStatus("ACCEPTED");approval.setExpiresAt(until);approval.setBillingAuthorizationId(23L);approval.setBillingAuthorizationRevision(3L);approval.setScopeJson("{\"operationScope\":{\"operationKind\":\"SKILL_TEXT\",\"quotedUpperBound\":10}}");
            claim.setRunId(44L);claim.setUserId(48L);claim.setStepId(57L);claim.setApprovalId(17L);claim.setOperationKind("ASSISTANT_SKILL");claim.setIdempotencyKey("frozen-key");claim.setRunLease(lease);claim.setStepLease(lease);
            child.setId(91L);child.setRootRunId(91L);child.setUserId(48L);child.setInvokeSource("ASSISTANT_RUNTIME");child.setDelFlag("0");child.setSkillVersionId(73L);child.setSkillConfigHash("a".repeat(64));child.setInputJson(com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of("assistantBudgetEnvelope",envelope())));
            var skillRuns=mock(AidSkillRunMapper.class);when(skillRuns.selectById(91L)).thenReturn(child);field(guard,"skillRuns",skillRuns);
            when(sessions.selectOne(any())).thenReturn(session);when(runs.selectById(44L)).thenReturn(run);when(runs.selectOne(any())).thenReturn(run);
            when(approvals.selectOne(any())).thenReturn(approval);when(approvals.selectById(17L)).thenReturn(approval);when(steps.selectOne(any())).thenReturn(step);when(steps.selectById(57L)).thenReturn(step);when(steps.updateById(any(AidRuntimeRunStep.class))).thenReturn(1);
            var policies=mock(AidRuntimeBudgetPolicyMapper.class);when(policies.selectOne(any())).thenReturn(new AidRuntimeBudgetPolicy());
            field(guard,"sessions",sessions);field(guard,"runs",runs);field(guard,"approvals",approvals);field(guard,"steps",steps);field(guard,"policies",policies);field(guard,"budgetPolicies",budget);
        }
        void reserve() {
            var child=new AidSkillRun();child.setId(91L);child.setUserId(48L);child.setStatus("RUNNING");
            var inner=new AidSkillRunStep();inner.setId(92L);inner.setRunId(91L);inner.setWorkflowAttempt(0);inner.setOrchestrationStatus("PLANNED");
            var childMapper=mock(AidSkillRunMapper.class);when(childMapper.selectOne(any())).thenReturn(child);
            var innerMapper=mock(AidSkillRunStepMapper.class);when(innerMapper.selectOne(any())).thenReturn(inner);
            var e=envelope();
            new AssistantSkillBudgetReservationAdmissionGuard(sessions,runs,approvals,steps,childMapper,innerMapper).assertMayReserve(SkillExecutionContext.builder().run(child).step(inner).assistantBudgetEnvelope(e).build());
        }
    }
    @Test void firstReservationRequiresDurableChildReceipt()throws Exception {var f=new Fixture();assertThrows(ServiceException.class,f::reserve);f.guard.attachSkillRun(f.claim,91L);assertDoesNotThrow(f::reserve);assertEquals(91L,f.step.getChildRunId());assertEquals("SUBMITTING",f.step.getStatus());assertEquals("ACCEPTED",f.step.getProviderAcceptanceState());verify(f.steps).updateById(f.step);}
    @Test void sameReceiptCannotCreateAnotherSubmission()throws Exception {var f=new Fixture();f.step.setChildRunId(91L);f.guard.attachSkillRun(f.claim,91L);verify(f.steps,never()).updateById(any(AidRuntimeRunStep.class));}
    @Test void conflictingReceiptCannotReplaceChild()throws Exception {var f=new Fixture();f.step.setChildRunId(91L);assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,93L));assertEquals(91L,f.step.getChildRunId());verifyNoInteractions(f.budget);}
    @Test void stoppedAndPausedRunsCannotAttachOrReserve()throws Exception {for(String state:List.of("STOPPED","PAUSING","PAUSED","RECONCILING")){var f=new Fixture();f.run.setStatus(state);assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());verifyNoInteractions(f.budget);}}
    @Test void stopRequestAndExpiredApprovalRemainRejected()throws Exception {for(boolean stop:List.of(true,false)){var f=new Fixture();if(stop)f.run.setStopRequested(true);else f.approval.setExpiresAt(new Date(0));assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());}}
    @Test void changedFenceAndExpiredLeaseRemainRejected()throws Exception {for(boolean fence:List.of(true,false)){var f=new Fixture();if(fence)f.step.setFencingToken(2L);else f.run.setLeaseUntil(new Date(0));assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());verifyNoInteractions(f.budget);}}
    @Test void actorAndClaimIdentityRemainBound()throws Exception {for(int n=0;n<3;n++){var f=new Fixture();if(n==0)f.claim.setUserId(49L);if(n==1)f.claim.setIdempotencyKey("new-key");if(n==2)f.claim.setOperationKind("ASSISTANT_TOOL");assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());}}
    @Test void unknownOutcomeAndTerminalStepsCannotBeRebound()throws Exception {for(String state:List.of("RECONCILING","RUNNING","FAILED","SUCCEEDED")){var f=new Fixture();f.step.setStatus(state);assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());}}
    @Test void invalidChildAndBudgetRejectionCannotLeaveReceipt()throws Exception {var f=new Fixture();assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,0L));doThrow(new ServiceException("BUDGET_POLICY_INACTIVE")).when(f.budget).assertProviderSubmission(anyLong(),anyLong(),anyLong(),any());assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());}
    @Test void foreignOrDifferentOuterIdentityCannotBeAttached()throws Exception {for(int n=0;n<3;n++){var f=new Fixture();if(n==0)f.child.setUserId(49L);if(n==1)f.child.setSkillConfigHash("c".repeat(64));if(n==2){var e=envelope();e.setAssistantParentStepId(58L);f.child.setInputJson(com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of("assistantBudgetEnvelope",e)));}assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));assertNull(f.step.getChildRunId());verifyNoInteractions(f.budget);}}
    @Test void lostReceiptWriteIsRejected()throws Exception {var f=new Fixture();when(f.steps.updateById(any(AidRuntimeRunStep.class))).thenReturn(0);assertThrows(ServiceException.class,()->f.guard.attachSkillRun(f.claim,91L));}
}
