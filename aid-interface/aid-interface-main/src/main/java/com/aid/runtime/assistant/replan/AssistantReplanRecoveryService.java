package com.aid.runtime.assistant.replan;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.skill.AssistantSkillReplanReconciliationService;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.*;
import com.aid.media.service.TaskCompletionService;
import com.aid.runtime.assistant.*;
import com.aid.runtime.assistant.execution.*;
import com.aid.runtime.domain.*;
import com.aid.runtime.mapper.*;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Reconciles the existing graph and money before Core applies a pending correction. No submission API. */
@Service
public class AssistantReplanRecoveryService {
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","FAILED","CANCELLED","FAILED_PRETASK","CANCELLED_PRETASK");
    private static final Set<String> MONEY_TERMINAL=Set.of("RELEASED","SETTLED","REFUNDED");
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeReplanIntentMapper intents;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidMediaTaskMapper tasks;
    @Resource private AidBillingAuthorizationReservationMapper reservations;
    @Resource private RuntimeReplanCancellationAuthorizationPort authorization;
    @Resource private AssistantBillingBudgetService budgets;
    @Resource private IAccountUpdateService accounts;
    @Resource private TaskCompletionService completion;
    @Resource private AssistantSkillReplanReconciliationService skills;
    @Resource private AssistantPlanExecutionService execution;
    @Resource private AssistantCoreService core;
    @Resource private TransactionTemplate transactions;

    public AssistantLoopReconcileResult reconcile(Long runId,RuntimeLease lease) {
        AidRuntimeRun run=runs.selectById(runId);
        AidRuntimeReplanIntent intent=transactions.execute(status -> {
            AidRuntimeReplanIntent value=activeIntent(runId);
            if (value==null) throw new ServiceException("REPLAN_INTENT_UNAVAILABLE");
            verify(run,value,lease);return value;
        });
        MediaTaskCancellationRequest ticket=new MediaTaskCancellationRequest(null,MediaTaskCancellationPurpose.REPLAN,
                runId,lease.getFencingToken(),lease.getOwner(),intent.getId(),intent.getStateRevision(),intent.getCommandId());
        List<AidRuntimeRunStep> graph=steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .select(AidRuntimeRunStep::getId,AidRuntimeRunStep::getRunId,AidRuntimeRunStep::getOperationKind,
                        AidRuntimeRunStep::getStatus,AidRuntimeRunStep::getProviderAcceptanceState,AidRuntimeRunStep::getReservationId,
                        AidRuntimeRunStep::getMediaTaskId,AidRuntimeRunStep::getChildRunId,AidRuntimeRunStep::getApprovalId,
                        AidRuntimeRunStep::getStableStepId,AidRuntimeRunStep::getAttemptNo,AidRuntimeRunStep::getIdempotencyKey,AidRuntimeRunStep::getErrorMessage)
                .eq(AidRuntimeRunStep::getRunId,runId).eq(AidRuntimeRunStep::getDelFlag,"0").orderByAsc(AidRuntimeRunStep::getId).last("LIMIT 1001"));
        if (graph.size()>1000) throw new ServiceException("REPLAN_GRAPH_LIMIT_EXCEEDED");
        // Finish children before their already-paid decision parent; never replay an obsolete Agent decision.
        graph.sort(Comparator.comparing((AidRuntimeRunStep step) -> "ASSISTANT_DECISION".equals(step.getOperationKind()))
                .thenComparing(AidRuntimeRunStep::getId));
        boolean blocked=false;
        String errorCode=null;
        for (AidRuntimeRunStep step:graph) {
            try {
                if (!reconcileStep(run,intent,step,lease,ticket)) blocked=true;
            } catch (RuntimeException uncertain) {blocked=true;errorCode="REPLAN_EXISTING_STATE_UNKNOWN";}
        }
        if (!blocked) {
            try {blocked=!core.completeDeferredReplan(runId,lease);}
            catch (RuntimeException unsettled) {blocked=true;errorCode="REPLAN_APPLY_REQUIRES_REVIEW";}
        }
        if (blocked) {
            String persistedError=errorCode;
            transactions.executeWithoutResult(status -> {
                AidRuntimeReplanIntent current=activeIntent(runId);
                verify(runs.selectById(runId),current,lease);
                Date retry=new Date(System.currentTimeMillis()+30_000);
                intents.update(null,Wrappers.<AidRuntimeReplanIntent>lambdaUpdate().eq(AidRuntimeReplanIntent::getId,current.getId())
                        .set(AidRuntimeReplanIntent::getStatus,"RECONCILING")
                        .set(AidRuntimeReplanIntent::getStateRevision,current.getStateRevision()+1)
                        .set(AidRuntimeReplanIntent::getNextRetryAt,retry).set(AidRuntimeReplanIntent::getLastErrorCode,persistedError));
                runs.update(null,Wrappers.<AidRuntimeRun>lambdaUpdate().eq(AidRuntimeRun::getId,runId)
                        .eq(AidRuntimeRun::getLeaseOwner,lease.getOwner()).eq(AidRuntimeRun::getFencingToken,lease.getFencingToken())
                        .set(AidRuntimeRun::getStatus,"PAUSING").set(AidRuntimeRun::getNextAction,"WAIT_REPLAN_RECONCILIATION")
                        .set(AidRuntimeRun::getNextRetryAt,retry));
            });
        }
        AidRuntimeRun current=runs.selectById(runId);
        AssistantLoopReconcileResult result=new AssistantLoopReconcileResult(); result.setRunId(runId);
        result.setStateRevision(current.getStateRevision());result.setStatus(current.getStatus());result.setRecovered(!blocked);
        result.setNextRetryAt(current.getNextRetryAt());return result;
    }

    private boolean reconcileStep(AidRuntimeRun run,AidRuntimeReplanIntent intent,AidRuntimeRunStep step,
                                  RuntimeLease lease,MediaTaskCancellationRequest ticket) {
        if (step.getReservationId()!=null && step.getMediaTaskId()==null && step.getChildRunId()==null
                && ("READY".equals(step.getStatus()) && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                    || "CANCELLED_PRETASK".equals(step.getStatus()) && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                    || "RECONCILING".equals(step.getStatus()) && "UNKNOWN".equals(step.getProviderAcceptanceState())
                        && "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN".equals(step.getErrorMessage()))) {
            return releaseBeforeTask(run,intent,step,lease);
        }
        if (TERMINAL.contains(step.getStatus())) return moneySettled(step);
        if (step.getChildRunId()!=null && "ASSISTANT_SKILL".equals(step.getOperationKind())) {
            skills.reconcile(run.getId(),step.getId(),step.getStableStepId(),step.getAttemptNo(),run.getUserId(),lease.getOwner(),ticket);
            return reconcileChild(run,step,lease);
        }
        if (step.getMediaTaskId()!=null) {
            AidMediaTask task=tasks.selectById(step.getMediaTaskId());
            if (task==null || !Objects.equals(task.getUserId(),run.getUserId())
                    || !AssistantRuntimeSubmissionAdmissionHandler.BIZ_TASK_TYPE.equals(task.getBizTaskType())
                    || !Objects.equals(task.getBizTaskId(),step.getId())) return false;
            // Exact admission handlers prove task/step/approval/reservation identity under graph locks.
            if (Set.of("PENDING","QUEUED").contains(task.getStatus())) completion.cancelBudgetManagedUnsubmittedTask(ticket.withTaskId(task.getId()));
            task=tasks.selectById(task.getId());
            if (task==null || !Set.of("SUCCEEDED","FAILED","CANCELLED").contains(Objects.toString(task.getStatus(), ""))
                    || !(MediaBillingStatus.SUCCESS.name().equals(task.getBillingStatus())
                    || MediaBillingStatus.FAILED.name().equals(task.getBillingStatus()))) return false;
            // Media-task billing and budget-reservation states are different contracts. A terminal
            // label alone is insufficient: use the existing reservation and wallet-ledger proof.
            requireTaskSettlement(run, step, task);
            if (!"ASSISTANT_DECISION".equals(step.getOperationKind())) return reconcileChild(run,step,lease);
            AidMediaTask receipt=task;
            transactions.executeWithoutResult(status -> {
                verify(run,intent,lease);
                AidRuntimeRunStep current=steps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                        .eq(AidRuntimeRunStep::getId,step.getId()).eq(AidRuntimeRunStep::getRunId,run.getId()).last("FOR UPDATE"));
                if (current==null || !Objects.equals(current.getMediaTaskId(),receipt.getId())) throw new ServiceException("REPLAN_TASK_IDENTITY_CHANGED");
                AidRuntimeApproval used = approvals.selectOne(Wrappers.<AidRuntimeApproval>lambdaQuery()
                        .eq(AidRuntimeApproval::getId, current.getApprovalId())
                        .eq(AidRuntimeApproval::getRunId, run.getId()).eq(AidRuntimeApproval::getUserId, run.getUserId())
                        .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
                if (used == null || !("ACCEPTED".equals(used.getStatus()) || "CONSUMED".equals(used.getStatus())))
                    throw new ServiceException("REPLAN_EXECUTED_APPROVAL_CHANGED");
                if ("ACCEPTED".equals(used.getStatus())) {
                    used.setStatus("CONSUMED");
                    used.setStateRevision((used.getStateRevision() == null ? 0L : used.getStateRevision()) + 1L);
                    used.setUpdateBy(lease.getOwner()); used.setUpdateTime(new Date());
                    if (approvals.updateById(used) != 1) throw new ServiceException("REPLAN_EXECUTED_APPROVAL_CHANGED");
                }
                String state="SUCCEEDED".equals(receipt.getStatus())?"SUCCEEDED":"CANCELLED".equals(receipt.getStatus())?"CANCELLED":"FAILED";
                JSONObject output=new JSONObject();output.put("mediaTaskId",String.valueOf(receipt.getId()));output.put("status",receipt.getStatus());
                output.put("obsoleteDecisionDiscarded",true);output.put("resultDigest",receipt.getResultText()==null?null:SecureUtil.sha256(receipt.getResultText()));
                steps.update(null,Wrappers.<AidRuntimeRunStep>lambdaUpdate().eq(AidRuntimeRunStep::getId,current.getId())
                        .set(AidRuntimeRunStep::getStatus,state).set(AidRuntimeRunStep::getProviderAcceptanceState,"ACCEPTED")
                        .set(AidRuntimeRunStep::getOutputJson,output.toJSONString()).set(AidRuntimeRunStep::getErrorMessage,"REPLAN_SUPERSEDED_DECISION")
                        .set(AidRuntimeRunStep::getLeaseOwner,null).set(AidRuntimeRunStep::getLeaseUntil,null));
            });return moneySettled(steps.selectById(step.getId()));
        }
        // Pure local planned work has no task/reservation/child. Core atomically invalidates it at apply.
        if (Set.of("PREPARING","AWAITING_APPROVAL","READY").contains(step.getStatus())
                && step.getReservationId()==null && step.getChildRunId()==null && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())) {
            transactions.executeWithoutResult(status -> {
                verify(run,intent,lease);
                steps.update(null,Wrappers.<AidRuntimeRunStep>lambdaUpdate().eq(AidRuntimeRunStep::getId,step.getId())
                        .in(AidRuntimeRunStep::getStatus,"PREPARING","AWAITING_APPROVAL","READY")
                        .eq(AidRuntimeRunStep::getProviderAcceptanceState,"NOT_SUBMITTED")
                        .isNull(AidRuntimeRunStep::getReservationId).isNull(AidRuntimeRunStep::getChildRunId).isNull(AidRuntimeRunStep::getMediaTaskId)
                        .set(AidRuntimeRunStep::getStatus,"CANCELLED_PRETASK").set(AidRuntimeRunStep::getErrorMessage,"RUNTIME_REPLAN_CANCELLED_BEFORE_TASK_INTENT")
                        .set(AidRuntimeRunStep::getLeaseOwner,null).set(AidRuntimeRunStep::getLeaseUntil,null));
            });return true;
        }
        if ("DECISION_READY".equals(step.getStatus()) && "ASSISTANT_DECISION".equals(step.getOperationKind())) {
            boolean activeChild=steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery().eq(AidRuntimeRunStep::getRunId,run.getId())
                    .in(AidRuntimeRunStep::getOperationKind,"ASSISTANT_TOOL","ASSISTANT_SKILL").eq(AidRuntimeRunStep::getDelFlag,"0")
                    .notIn(AidRuntimeRunStep::getStatus,TERMINAL)).stream().anyMatch(child -> Objects.equals(parentId(child),step.getId()));
            if (activeChild) return false;
            transactions.executeWithoutResult(status -> {verify(run,intent,lease);steps.update(null,Wrappers.<AidRuntimeRunStep>lambdaUpdate()
                    .eq(AidRuntimeRunStep::getId,step.getId()).eq(AidRuntimeRunStep::getStatus,"DECISION_READY")
                    .set(AidRuntimeRunStep::getStatus,"SUCCEEDED").set(AidRuntimeRunStep::getErrorMessage,"REPLAN_SUPERSEDED_DECISION"));});
            return moneySettled(steps.selectById(step.getId()));
        }
        return false;
    }
    private boolean reconcileChild(AidRuntimeRun run,AidRuntimeRunStep step,RuntimeLease lease) {
        Long parentId=parentId(step);
        AidRuntimeRunStep parent=parentId==null?null:steps.selectById(parentId);
        if (parent==null || parent.getOutputJson()==null || !Objects.equals(parent.getRunId(),run.getId())) return false;
        execution.applyDecision(new AssistantPlanExecutionCommand(run.getId(),parent.getId(),parent.getOutputJson(),lease));
        AidRuntimeRunStep current=steps.selectById(step.getId());return current!=null && TERMINAL.contains(current.getStatus()) && moneySettled(current);
    }
    private boolean releaseBeforeTask(AidRuntimeRun run,AidRuntimeReplanIntent intent,AidRuntimeRunStep step,RuntimeLease lease) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            verify(run,intent,lease);
            AidRuntimeRunStep current=steps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getId,step.getId()).eq(AidRuntimeRunStep::getRunId,run.getId()).last("FOR UPDATE"));
            boolean refundRetry=current!=null && "RECONCILING".equals(current.getStatus())
                    && "UNKNOWN".equals(current.getProviderAcceptanceState())
                    && "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN".equals(current.getErrorMessage());
            if (current==null || current.getMediaTaskId()!=null || current.getChildRunId()!=null
                    || !refundRetry && (!Set.of("READY","CANCELLED_PRETASK").contains(current.getStatus())
                        || !"NOT_SUBMITTED".equals(current.getProviderAcceptanceState()))) return false;
            if (tasks.selectByAssistantOperationForUpdate(run.getUserId(),AssistantRuntimeSubmissionAdmissionHandler.BIZ_TASK_TYPE,
                    current.getId(),current.getIdempotencyKey())!=null) return false;
            AidBillingAuthorizationReservation reservation=reservations.selectOne(Wrappers.<AidBillingAuthorizationReservation>lambdaQuery()
                    .eq(AidBillingAuthorizationReservation::getId,current.getReservationId()).eq(AidBillingAuthorizationReservation::getRuntimeRunId,run.getId()).last("FOR UPDATE"));
            if (reservation==null || reservation.getMediaTaskId()!=null) return false;
            if (!refundRetry) steps.update(null,Wrappers.<AidRuntimeRunStep>lambdaUpdate().eq(AidRuntimeRunStep::getId,current.getId())
                    .set(AidRuntimeRunStep::getStatus,"CANCELLED_PRETASK").set(AidRuntimeRunStep::getErrorMessage,"RUNTIME_REPLAN_CANCELLED_BEFORE_TASK_INTENT")
                    .set(AidRuntimeRunStep::getLeaseOwner,null).set(AidRuntimeRunStep::getLeaseUntil,null));
            BillingBudgetRequests.ReleaseUnsubmittedReservationRequest request=new BillingBudgetRequests.ReleaseUnsubmittedReservationRequest();
            request.setAuthorizationId(reservation.getAuthorizationId());request.setAuthorizationRevision(reservation.getAuthorizationRevision());
            request.setReservationId(reservation.getId());request.setExpectedReservationRevision(reservation.getRevision());request.setPayerUserId(run.getUserId());
            request.setRuntimeRunId(run.getId());request.setRuntimeStepId(current.getId());request.setStableStepKey(current.getStableStepId());
            request.setApprovalId(current.getApprovalId());request.setAttemptNo(current.getAttemptNo());request.setOperationKind(current.getOperationKind());
            request.setLogicalCallKey(current.getIdempotencyKey());request.setOperator(lease.getOwner());
            return "RELEASED".equals(budgets.releaseUnsubmittedReservation(request).getState());
        }));
    }
    private boolean moneySettled(AidRuntimeRunStep step) {
        if (step.getReservationId()==null) return true;
        AidBillingAuthorizationReservation reservation=reservations.selectById(step.getReservationId());
        return reservation!=null && Objects.equals(reservation.getRuntimeRunId(),step.getRunId())
                && Objects.equals(reservation.getRuntimeStepId(),step.getId()) && MONEY_TERMINAL.contains(reservation.getState());
    }
    private void requireTaskSettlement(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task) {
        AidBillingAuthorizationReservation reservation = step.getReservationId() == null ? null
                : reservations.selectById(step.getReservationId());
        if (reservation == null || !Objects.equals(reservation.getRuntimeRunId(), run.getId())
                || !Objects.equals(reservation.getRuntimeStepId(), step.getId())
                || task.getBillingTraceId() == null || task.getBillingTraceId().isBlank())
            throw new ServiceException("REPLAN_SETTLEMENT_UNPROVEN");
        AssistantTerminalChildEvidenceVerifier.requireTerminalSettlement(run.getUserId(), reservation, task,
                accounts.inspectLedger(task.getBillingTraceId()));
    }
    private void verify(AidRuntimeRun run,AidRuntimeReplanIntent intent,RuntimeLease lease) {
        if (run==null || intent==null || lease==null) throw new ServiceException("REPLAN_IDENTITY_UNAVAILABLE");
        authorization.lockAndVerify(new RuntimeReplanCancellationAuthorizationPort.ReplanCancellationAuthorizationRequest(run.getId(),run.getSessionId(),run.getUserId(),
                lease.getOwner(),lease.getFencingToken(),intent.getId(),intent.getStateRevision(),intent.getCommandId()));
    }
    private AidRuntimeReplanIntent activeIntent(Long runId) {
        return intents.selectOne(Wrappers.<AidRuntimeReplanIntent>lambdaQuery().eq(AidRuntimeReplanIntent::getRunId,runId)
                .eq(AidRuntimeReplanIntent::getActiveSlot,1).eq(AidRuntimeReplanIntent::getDelFlag,"0"));
    }
    private Long parentId(AidRuntimeRunStep step) {
        if (step.getApprovalId()==null) return null;
        AidRuntimeApproval approval=approvals.selectById(step.getApprovalId());
        if (approval==null || !Objects.equals(approval.getRunId(),step.getRunId()) || !"0".equals(approval.getDelFlag())
                || approval.getScopeJson()==null || !Objects.equals(approval.getScopeDigest(),SecureUtil.sha256(approval.getScopeJson())))
            throw new ServiceException("REPLAN_PARENT_IDENTITY_CHANGED");
        JSONObject scope=JSON.parseObject(approval.getScopeJson());
        if (!"assistant-operation-scope/v1".equals(scope.getString("schemaVersion")) || scope.getJSONObject("decision")==null)
            throw new ServiceException("REPLAN_PARENT_SCOPE_INVALID");
        return scope.getJSONObject("decision").getLong("mainAgentStepId");
    }
}
