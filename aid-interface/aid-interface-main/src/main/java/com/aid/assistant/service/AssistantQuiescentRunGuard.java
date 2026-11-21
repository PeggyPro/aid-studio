package com.aid.assistant.service;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.assistant.execution.AssistantOperationQuoteGrantHistory;
import com.aid.runtime.assistant.execution.AssistantTerminalChildEvidenceVerifier;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.assistant.execution.AssistantOperationQuoteLineage;
import com.aid.runtime.assistant.execution.AssistantOperationQuoteSourceGuard;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.core.util.StrUtil;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verifies that only unsubmitted quotes remain before changing a conversation's execution boundary. */
@Service
public class AssistantQuiescentRunGuard
{
    private static final Set<String> SAFE_STEPS = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED",
            "FAILED_PRETASK", "CANCELLED_PRETASK", "AWAITING_APPROVAL");
    private static final Set<String> CLOSED_APPROVALS = Set.of("CONSUMED", "REJECTED", "EXPIRED", "CANCELLED", "INVALIDATED");
    private static final int MAX_ACTIVE_QUOTES = 200;
    private static final Set<String> RUNTIME_RESERVATION_OPERATIONS = Set.of(
            "ASSISTANT_DECISION", "ASSISTANT_TOOL", "ASSISTANT_SKILL");
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidMediaTaskMapper tasks;
    @Resource private AidBillingAuthorizationReservationMapper reservations;
    @Resource private IAccountUpdateService accounts;
    @Resource private AssistantOperationQuoteLineage quoteLineage;
    @Resource private AssistantTerminalChildEvidenceVerifier childEvidence;

    public Graph inspect(Long userId, Long runId, boolean lock)
    {
        String one = lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1";
        AidRuntimeRunStep pending = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId).eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .and(q -> q.isNull(AidRuntimeRunStep::getStatus).or().notIn(AidRuntimeRunStep::getStatus, SAFE_STEPS)
                        .or().eq(AidRuntimeRunStep::getProviderAcceptanceState, "UNKNOWN")
                        .or(nested -> nested.eq(AidRuntimeRunStep::getStatus, "AWAITING_APPROVAL")
                                .and(evidence -> evidence.isNotNull(AidRuntimeRunStep::getReservationId)
                                        .or().isNotNull(AidRuntimeRunStep::getMediaTaskId)
                                        .or().isNotNull(AidRuntimeRunStep::getChildRunId)
                                        .or().isNull(AidRuntimeRunStep::getProviderAcceptanceState)
                                        .or().ne(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED"))))
                .last(one));
        if (pending != null) return new Graph("RUN_RECOVERY_REQUIRED", List.of(), List.of());
        List<AidRuntimeApproval> active = approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .select(AidRuntimeApproval::getId, AidRuntimeApproval::getRunId, AidRuntimeApproval::getUserId,
                        AidRuntimeApproval::getStatus).eq(AidRuntimeApproval::getRunId, runId)
                .eq(AidRuntimeApproval::getDelFlag, "0")
                .and(q -> q.isNull(AidRuntimeApproval::getStatus).or().notIn(AidRuntimeApproval::getStatus, CLOSED_APPROVALS))
                .orderByAsc(AidRuntimeApproval::getId).last(lock ? "LIMIT 201 FOR UPDATE" : "LIMIT 201"));
        if (active.size() > MAX_ACTIVE_QUOTES || active.stream().anyMatch(a -> !"QUOTED".equals(a.getStatus())
                || !Objects.equals(a.getRunId(), runId) || !Objects.equals(a.getUserId(), userId)))
            return new Graph("RUN_RECOVERY_REQUIRED", List.of(), List.of());
        if (active.isEmpty()) return new Graph(null, List.of(), List.of());
        List<AidRuntimeRunStep> quoted = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getApprovalId, AidRuntimeRunStep::getRunId,
                        AidRuntimeRunStep::getStatus, AidRuntimeRunStep::getProviderAcceptanceState,
                        AidRuntimeRunStep::getReservationId, AidRuntimeRunStep::getMediaTaskId, AidRuntimeRunStep::getChildRunId)
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getDelFlag, "0")
                .in(AidRuntimeRunStep::getApprovalId, active.stream().map(AidRuntimeApproval::getId).toList())
                .last(lock ? "LIMIT 201 FOR UPDATE" : "LIMIT 201"));
        Map<Long, AidRuntimeRunStep> byApproval = new HashMap<>();
        for (AidRuntimeRunStep step : quoted)
        {
            if (!Objects.equals(step.getRunId(), runId) || !"AWAITING_APPROVAL".equals(step.getStatus())
                    || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()) || step.getReservationId() != null
                    || step.getMediaTaskId() != null || step.getChildRunId() != null
                    || byApproval.put(step.getApprovalId(), step) != null)
                return new Graph("RUN_RECOVERY_REQUIRED", List.of(), List.of());
        }
        if (quoted.size() != active.size() || active.stream().anyMatch(a -> !byApproval.containsKey(a.getId())))
            return new Graph("RUN_RECOVERY_REQUIRED", List.of(), List.of());
        return new Graph(null, active.stream().map(AidRuntimeApproval::getId).toList(),
                quoted.stream().map(AidRuntimeRunStep::getId).toList());
    }

    public List<String> invalidateQuotes(Long userId, Long runId, Graph graph, String cause)
    {
        if (graph == null || graph.blockReason() != null) throw blocked();
        if (graph.approvalIds().isEmpty()) return List.of();
        Date now = new Date();
        int approvalsChanged = approvals.update(null, new LambdaUpdateWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, runId).eq(AidRuntimeApproval::getDelFlag, "0")
                .in(AidRuntimeApproval::getId, graph.approvalIds()).eq(AidRuntimeApproval::getStatus, "QUOTED")
                .set(AidRuntimeApproval::getStatus, "INVALIDATED").set(AidRuntimeApproval::getInvalidatedAt, now)
                .setSql("state_revision = COALESCE(state_revision, 0) + 1")
                .set(AidRuntimeApproval::getUpdateTime, now).set(AidRuntimeApproval::getUpdateBy, String.valueOf(userId)));
        int stepsChanged = steps.update(null, new LambdaUpdateWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getDelFlag, "0")
                .in(AidRuntimeRunStep::getId, graph.stepIds()).eq(AidRuntimeRunStep::getStatus, "AWAITING_APPROVAL")
                .eq(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED")
                .isNull(AidRuntimeRunStep::getMediaTaskId).isNull(AidRuntimeRunStep::getReservationId).isNull(AidRuntimeRunStep::getChildRunId)
                .set(AidRuntimeRunStep::getStatus, "CANCELLED_PRETASK").set(AidRuntimeRunStep::getErrorMessage, cause)
                .set(AidRuntimeRunStep::getUpdateTime, now).set(AidRuntimeRunStep::getUpdateBy, String.valueOf(userId)));
        if (approvalsChanged != graph.approvalIds().size() || stepsChanged != graph.stepIds().size()) throw blocked();
        return graph.approvalIds().stream().map(String::valueOf).toList();
    }

    /** Budget renewal keeps a settled decision's pending operation; ordinary interjection does not. */
    public BudgetGraph inspectBudgetRenewal(Long userId, AidRuntimeRun run, AidRuntimeBudgetPolicy policy, boolean lock)
    {
        if (run == null || policy == null || policy.getAuthorizationId() == null
                || policy.getAuthorizationRevision() == null
                || !Objects.equals(userId, run.getUserId()) || !"0".equals(run.getDelFlag()))
            return budgetBlocked("RUN_RECOVERY_REQUIRED");
        List<AidRuntimeRunStep> deciding = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").eq(AidRuntimeRunStep::getStatus, "DECISION_READY")
                .orderByAsc(AidRuntimeRunStep::getId).last(lock ? "LIMIT 201 FOR UPDATE" : "LIMIT 201"));
        if (deciding.isEmpty())
        {
            Graph legacy = inspect(userId, run.getId(), lock);
            return new BudgetGraph(legacy.blockReason(), legacy.approvalIds(), legacy.stepIds(), List.of());
        }
        String bounded = lock ? "LIMIT 201 FOR UPDATE" : "LIMIT 201";
        List<AidRuntimeApproval> allApprovals = approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, run.getId()).eq(AidRuntimeApproval::getDelFlag, "0")
                .orderByAsc(AidRuntimeApproval::getId).last(bounded));
        List<AidRuntimeRunStep> allSteps = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByAsc(AidRuntimeRunStep::getId).last(bounded));
        if (deciding.size() > MAX_ACTIVE_QUOTES || allApprovals.size() > MAX_ACTIVE_QUOTES
                || allSteps.size() > MAX_ACTIVE_QUOTES) return budgetBlocked("RENEWAL_GRAPH_TOO_LARGE");
        List<Long> stepIds = allSteps.stream().map(AidRuntimeRunStep::getId).toList();
        List<Long> approvalIds = allApprovals.stream().map(AidRuntimeApproval::getId).toList();
        List<String> keys = allSteps.stream().map(AidRuntimeRunStep::getIdempotencyKey)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (stepIds.isEmpty() || approvalIds.isEmpty()) return budgetBlocked("RENEWAL_GRAPH_CHANGED");
        // Separate indexed identity reads retain cross-owner/deleted contradictions without an OR scan.
        List<AidBillingAuthorizationReservation> receipts = new ArrayList<>();
        receipts.addAll(reservations.selectList(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .in(AidBillingAuthorizationReservation::getRuntimeStepId, stepIds)
                .in(AidBillingAuthorizationReservation::getOperationKind, RUNTIME_RESERVATION_OPERATIONS).last(bounded)));
        receipts.addAll(reservations.selectList(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .in(AidBillingAuthorizationReservation::getApprovalId, approvalIds).last(bounded)));
        if (!keys.isEmpty()) receipts.addAll(reservations.selectList(
                new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                        .in(AidBillingAuthorizationReservation::getLogicalCallKey, keys).last(bounded)));
        List<AidMediaTask> taskRows = new ArrayList<>();
        taskRows.addAll(tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getBizTaskType, "ASSISTANT_RUNTIME_STEP")
                .in(AidMediaTask::getBizTaskId, stepIds).last(bounded)));
        if (!keys.isEmpty()) taskRows.addAll(tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .in(AidMediaTask::getIdempotencyKey, keys).last(bounded)));
        Map<Long, AidBillingAuthorizationReservation> receiptById = new HashMap<>();
        Map<Long, AidMediaTask> taskById = new HashMap<>();
        for (AidBillingAuthorizationReservation row : receipts)
        {
            AidBillingAuthorizationReservation prior = receiptById.putIfAbsent(row.getId(), row);
            if (row.getId() == null || prior != null && !Objects.equals(JSON.toJSONString(prior), JSON.toJSONString(row)))
                return budgetBlocked("RENEWAL_GRAPH_CHANGED");
        }
        for (AidMediaTask row : taskRows)
        {
            AidMediaTask prior = taskById.putIfAbsent(row.getId(), row);
            if (row.getId() == null || prior != null && !Objects.equals(JSON.toJSONString(prior), JSON.toJSONString(row)))
                return budgetBlocked("RENEWAL_GRAPH_CHANGED");
        }
        if (receiptById.size() > MAX_ACTIVE_QUOTES || taskById.size() > MAX_ACTIVE_QUOTES)
            return budgetBlocked("RENEWAL_GRAPH_TOO_LARGE");
        Set<String> traces = new HashSet<>();
        for (AidMediaTask task : taskById.values())
            if (StrUtil.isNotBlank(task.getBillingTraceId())) traces.add(task.getBillingTraceId());
        Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger = traces.isEmpty()
                ? Map.of() : accounts.inspectLedgerBatch(traces);
        if (ledger == null || !ledger.keySet().equals(traces) || ledger.values().stream().anyMatch(Objects::isNull))
            return budgetBlocked("RENEWAL_LEDGER_UNAVAILABLE");
        Map<Long, AidRuntimeApproval> approved = new HashMap<>();
        for (AidRuntimeApproval approval : allApprovals) approved.put(approval.getId(), approval);
        long oldestSourceRevision = Long.MAX_VALUE;
        for (AidRuntimeRunStep step : allSteps)
        {
            AidRuntimeApproval source = approved.get(step.getApprovalId());
            if (!"AWAITING_APPROVAL".equals(step.getStatus()) || source == null
                    || !"PLAN_EXECUTION".equals(source.getScopeKind()) || !"INVALIDATED".equals(source.getStatus())) continue;
            if (source.getBillingAuthorizationRevision() == null
                    || !Objects.equals(source.getBillingAuthorizationId(), policy.getAuthorizationId()))
                return budgetBlocked("RENEWAL_OPERATION_GRANT_HISTORY_UNPROVEN");
            oldestSourceRevision = Math.min(oldestSourceRevision, source.getBillingAuthorizationRevision());
        }
        List<AidRuntimeCommand> renewalReceipts;
        try
        {
            renewalReceipts = oldestSourceRevision == Long.MAX_VALUE ? List.of()
                    : quoteLineage.loadBudgetGrantHistory(run, policy.getAuthorizationId(), oldestSourceRevision,
                            policy.getAuthorizationRevision(), lock);
            quoteLineage.requireSupersededApprovalHistory(run, allSteps, approved, lock);
        }
        catch (ServiceException unproven) { return budgetBlocked("RENEWAL_OPERATION_GRANT_HISTORY_UNPROVEN"); }
        Set<Long> decidingIds = deciding.stream().map(AidRuntimeRunStep::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<AidRuntimeRunStep> children = new ArrayList<>();
        try
        {
            for (AidRuntimeRunStep step : allSteps)
            {
                if (step.getChildRunId() == null) continue;
                AidRuntimeApproval approval = approved.get(step.getApprovalId());
                if (approval == null || !"PLAN_EXECUTION".equals(approval.getScopeKind())) continue;
                JSONObject scope = JSON.parseObject(approval.getScopeJson());
                JSONObject decision = scope == null ? null : scope.getJSONObject("decision");
                if (decision != null && decidingIds.contains(decision.getLong("mainAgentStepId"))) children.add(step);
            }
        }
        catch (RuntimeException invalidScope) { return budgetBlocked("RENEWAL_OPERATION_SCOPE_UNPROVEN"); }
        try { childEvidence.requireTerminalChildren(run, children, approved, lock); }
        catch (ServiceException unproven) { return budgetBlocked(unproven.getMessage()); }
        return budgetGraph(userId, run, policy, allSteps, allApprovals, receiptById.values(), taskById, ledger,
                renewalReceipts, children.stream().map(AidRuntimeRunStep::getId).collect(java.util.stream.Collectors.toSet()));
    }

    static BudgetGraph budgetGraph(Long userId, AidRuntimeRun run, AidRuntimeBudgetPolicy policy, List<AidRuntimeRunStep> stepRows,
            List<AidRuntimeApproval> approvalRows, Collection<AidBillingAuthorizationReservation> receipts,
            Map<Long, AidMediaTask> taskById,
            Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger, List<AidRuntimeCommand> renewalReceipts,
            Set<Long> verifiedChildStepIds)
    {
        Map<Long, AidRuntimeApproval> byApproval = new HashMap<>();
        Map<Long, AidRuntimeRunStep> deciding = new HashMap<>();
        Set<Long> seenSteps = new HashSet<>();
        for (AidRuntimeApproval approval : approvalRows)
            if (approval == null || approval.getId() == null || approval.getStatus() == null
                    || !Objects.equals(approval.getUserId(), userId)
                    || !Objects.equals(approval.getRunId(), run.getId()) || !"0".equals(approval.getDelFlag())
                    || byApproval.putIfAbsent(approval.getId(), approval) != null)
                return budgetBlocked("RENEWAL_GRAPH_CHANGED");
        for (AidRuntimeRunStep step : stepRows)
        {
            if (step == null || step.getId() == null || step.getStatus() == null || !seenSteps.add(step.getId())
                    || !Objects.equals(step.getRunId(), run.getId()) || !"0".equals(step.getDelFlag())
                    || "UNKNOWN".equals(step.getProviderAcceptanceState())) return budgetBlocked("RUN_RECOVERY_REQUIRED");
            if ("DECISION_READY".equals(step.getStatus()))
            {
                AidRuntimeApproval approval = byApproval.get(step.getApprovalId());
                if (!"MAIN_AGENT".equals(step.getStepKind()) || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                        || !"ACCEPTED".equals(step.getProviderAcceptanceState()) || StrUtil.isBlank(step.getOutputJson())
                        || approval == null || !"ASSISTANT_ITERATION".equals(approval.getScopeKind())
                        || !"ACCEPTED".equals(approval.getStatus()) || approval.getAcceptedAt() == null
                        || !Objects.equals(approval.getRequestDigest(), step.getInputDigest())
                        || step.getMediaTaskId() == null || step.getReservationId() == null || step.getChildRunId() != null)
                    return budgetBlocked("RENEWAL_MAIN_SETTLEMENT_UNPROVEN");
                deciding.put(step.getId(), step);
            }
            else if (!SAFE_STEPS.contains(step.getStatus())) return budgetBlocked("RUN_RECOVERY_REQUIRED");
        }
        if (deciding.isEmpty()) return budgetBlocked("RENEWAL_GRAPH_CHANGED");
        List<Long> quoteIds = new ArrayList<>();
        List<Long> cancelledMainIds = new ArrayList<>();
        List<Long> operationIds = new ArrayList<>();
        Set<Long> retainedApprovals = new HashSet<>();
        List<AidRuntimeRunStep> paid = new ArrayList<>(deciding.values());
        Map<Long, Integer> operationChildren = new HashMap<>();
        try
        {
            for (AidRuntimeRunStep step : stepRows)
            {
                AidRuntimeApproval approval = byApproval.get(step.getApprovalId());
                boolean pending = "AWAITING_APPROVAL".equals(step.getStatus());
                if (pending && (approval == null || step.getMediaTaskId() != null || step.getReservationId() != null
                        || step.getChildRunId() != null || step.getChildRunKind() != null
                        || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()) || approval.getAcceptedAt() != null
                        || !noEffects(step, approval, receipts, taskById.values())))
                    return budgetBlocked("RENEWAL_OPERATION_SUBMISSION_UNPROVEN");
                if (pending && "ASSISTANT_ITERATION".equals(approval.getScopeKind()))
                {
                    if (!"MAIN_AGENT".equals(step.getStepKind()) || !"QUOTED".equals(approval.getStatus()))
                        return budgetBlocked("RENEWAL_GRAPH_CHANGED");
                    quoteIds.add(approval.getId()); cancelledMainIds.add(step.getId());
                    retainedApprovals.add(approval.getId()); continue;
                }
                if (approval == null || !"PLAN_EXECUTION".equals(approval.getScopeKind())) continue;
                JSONObject frozen = JSON.parseObject(approval.getScopeJson());
                JSONObject decision = frozen == null ? null : frozen.getJSONObject("decision");
                AidRuntimeRunStep main = decision == null ? null : deciding.get(decision.getLong("mainAgentStepId"));
                if (main == null)
                {
                    if (pending) return budgetBlocked("RENEWAL_OPERATION_PARENT_UNPROVEN");
                    continue;
                }
                AssistantOperationQuoteSourceGuard.requireBudgetRenewalOperationIdentity(run, main, step, approval);
                operationChildren.merge(main.getId(), 1, Integer::sum);
                retainedApprovals.add(approval.getId());
                if (pending)
                {
                    if (!("QUOTED".equals(approval.getStatus()) || "INVALIDATED".equals(approval.getStatus())
                            && priorBudgetInvalidation(run, policy, approval, renewalReceipts)))
                        return budgetBlocked("RENEWAL_OPERATION_GRANT_HISTORY_UNPROVEN");
                    if ("QUOTED".equals(approval.getStatus())) quoteIds.add(approval.getId());
                    operationIds.add(step.getId());
                }
                else if (step.getChildRunId() != null || step.getChildRunKind() != null)
                {
                    if (!verifiedChildStepIds.contains(step.getId()))
                        return budgetBlocked("RENEWAL_CHILD_RUNTIME_GRAPH_REQUIRES_RECONCILIATION");
                }
                else if (step.getMediaTaskId() != null || step.getReservationId() != null) paid.add(step);
                else
                {
                    JSONObject tool = frozen.getJSONObject("tool");
                    if (!"TOOL".equals(step.getStepKind()) || !"SUCCEEDED".equals(step.getStatus())
                            || !"NOT_APPLICABLE".equals(step.getProviderAcceptanceState())
                            || !"CONSUMED".equals(approval.getStatus()) || approval.getAcceptedAt() == null
                            || tool == null || !Boolean.FALSE.equals(tool.get("billable"))
                            || !Set.of("READ", "SAFE_WRITE").contains(tool.getString("sideEffectLevel"))
                            || StrUtil.isBlank(step.getOutputJson()) || JSON.parse(step.getOutputJson()) == null
                            || !noEffects(step, approval, receipts, taskById.values()))
                        return budgetBlocked("RENEWAL_ZERO_CHARGE_TOOL_RECEIPT_UNPROVEN");
                }
            }
        }
        catch (RuntimeException invalidScope) { return budgetBlocked("RENEWAL_OPERATION_SCOPE_UNPROVEN"); }
        if (deciding.keySet().stream().anyMatch(id -> !operationChildren.containsKey(id)))
            return budgetBlocked("RENEWAL_OPERATION_PARENT_UNPROVEN");
        for (AidRuntimeApproval approval : approvalRows)
            if (!CLOSED_APPROVALS.contains(approval.getStatus()) && !retainedApprovals.contains(approval.getId())
                    && deciding.values().stream().noneMatch(main -> Objects.equals(main.getApprovalId(), approval.getId())))
                return budgetBlocked("RUN_RECOVERY_REQUIRED");
        for (AidRuntimeRunStep step : paid)
            if (taskById.values().stream().anyMatch(task -> ("ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                    && Objects.equals(task.getBizTaskId(), step.getId())
                    || Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey()))
                    && !Objects.equals(task.getId(), step.getMediaTaskId())))
                return budgetBlocked("RENEWAL_OPERATION_SUBMISSION_UNPROVEN");
        List<AidBillingAuthorizationReservation> paidReceipts = receipts.stream()
                .filter(r -> paid.stream().anyMatch(s -> Objects.equals(s.getId(), r.getRuntimeStepId())
                        && r.getOperationKind() != null && RUNTIME_RESERVATION_OPERATIONS.contains(r.getOperationKind())
                        || Objects.equals(s.getApprovalId(), r.getApprovalId())
                        || Objects.equals(s.getIdempotencyKey(), r.getLogicalCallKey()))).toList();
        JSONObject summary = AssistantRunCostObservationService.summarize(userId, run.getId(), paid,
                paidReceipts, taskById, ledger, true, "renewal-check", "renewal-check");
        if (summary.getIntValue("verifiedSettledCallCount") != paid.size()
                || summary.getIntValue("pendingCallCount") != 0 || summary.getIntValue("unknownCallCount") != 0)
            return budgetBlocked("RENEWAL_MAIN_SETTLEMENT_UNPROVEN");
        for (AidRuntimeRunStep main : deciding.values())
        {
            AidMediaTask task = taskById.get(main.getMediaTaskId());
            AidBillingAuthorizationReservation reservation = paidReceipts.stream()
                    .filter(r -> Objects.equals(r.getId(), main.getReservationId())).findFirst().orElse(null);
            if (task == null || !"TEXT".equals(task.getMediaType()) || !"SUCCEEDED".equals(task.getStatus())
                    || reservation == null || !"SETTLED".equals(reservation.getState()))
                return budgetBlocked("RENEWAL_MAIN_SETTLEMENT_UNPROVEN");
        }
        return new BudgetGraph(null, List.copyOf(quoteIds), List.copyOf(cancelledMainIds), List.copyOf(operationIds));
    }

    private static boolean noEffects(AidRuntimeRunStep step, AidRuntimeApproval approval,
            Collection<AidBillingAuthorizationReservation> receipts, Collection<AidMediaTask> taskRows)
    {
        return receipts.stream().noneMatch(r -> Objects.equals(r.getRuntimeStepId(), step.getId())
                        && r.getOperationKind() != null && RUNTIME_RESERVATION_OPERATIONS.contains(r.getOperationKind())
                        || Objects.equals(r.getApprovalId(), approval.getId())
                        || Objects.equals(r.getLogicalCallKey(), step.getIdempotencyKey()))
                && taskRows.stream().noneMatch(t -> "ASSISTANT_RUNTIME_STEP".equals(t.getBizTaskType())
                        && Objects.equals(t.getBizTaskId(), step.getId())
                        || Objects.equals(t.getIdempotencyKey(), step.getIdempotencyKey()));
    }

    private static boolean priorBudgetInvalidation(AidRuntimeRun run, AidRuntimeBudgetPolicy policy,
            AidRuntimeApproval approval, List<AidRuntimeCommand> history)
    {
        try
        {
            AssistantOperationQuoteGrantHistory.requireBudgetRepriceSource(run, approval,
                    policy.getAuthorizationId(), policy.getAuthorizationRevision(), policy.getScopeDigest(), history);
            return true;
        }
        catch (ServiceException unproven) { return false; }
    }

    public List<String> invalidateBudgetQuotes(Long userId, Long runId, BudgetGraph graph)
    {
        if (graph == null || graph.blockReason() != null) throw blocked();
        Date now = new Date();
        if (!graph.approvalIds().isEmpty())
        {
            int changed = approvals.update(null, new LambdaUpdateWrapper<AidRuntimeApproval>()
                    .eq(AidRuntimeApproval::getRunId, runId).eq(AidRuntimeApproval::getUserId, userId)
                    .eq(AidRuntimeApproval::getDelFlag, "0").in(AidRuntimeApproval::getId, graph.approvalIds())
                    .eq(AidRuntimeApproval::getStatus, "QUOTED").isNull(AidRuntimeApproval::getAcceptedAt)
                    .set(AidRuntimeApproval::getStatus, "INVALIDATED").set(AidRuntimeApproval::getInvalidatedAt, now)
                    .setSql("state_revision = COALESCE(state_revision, 0) + 1")
                    .set(AidRuntimeApproval::getUpdateTime, now).set(AidRuntimeApproval::getUpdateBy, String.valueOf(userId)));
            if (changed != graph.approvalIds().size()) throw blocked();
        }
        if (!graph.cancelledMainStepIds().isEmpty())
        {
            int changed = steps.update(null, new LambdaUpdateWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getDelFlag, "0")
                    .in(AidRuntimeRunStep::getId, graph.cancelledMainStepIds())
                    .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                    .eq(AidRuntimeRunStep::getStatus, "AWAITING_APPROVAL")
                    .eq(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED")
                    .isNull(AidRuntimeRunStep::getMediaTaskId).isNull(AidRuntimeRunStep::getReservationId)
                    .isNull(AidRuntimeRunStep::getChildRunId)
                    .set(AidRuntimeRunStep::getStatus, "CANCELLED_PRETASK")
                    .set(AidRuntimeRunStep::getErrorMessage, "BUDGET_AUTHORIZATION_RENEWED")
                    .set(AidRuntimeRunStep::getUpdateTime, now).set(AidRuntimeRunStep::getUpdateBy, String.valueOf(userId)));
            if (changed != graph.cancelledMainStepIds().size()) throw blocked();
        }
        return graph.approvalIds().stream().map(String::valueOf).toList();
    }

    private static BudgetGraph budgetBlocked(String reason) { return new BudgetGraph(reason, List.of(), List.of(), List.of()); }

    public record BudgetGraph(String blockReason, List<Long> approvalIds,
                              List<Long> cancelledMainStepIds, List<Long> preservedOperationStepIds) { }

    public static AssistantServiceException blocked()
    {
        return new AssistantServiceException(409, "RUN_RECOVERY_REQUIRED", "已有调用待核实", null);
    }

    public record Graph(String blockReason, List<Long> approvalIds, List<Long> stepIds) { }
}
