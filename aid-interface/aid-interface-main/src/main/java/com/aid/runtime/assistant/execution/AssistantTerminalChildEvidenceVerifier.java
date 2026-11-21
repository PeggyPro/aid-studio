package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.assistant.skill.AssistantSkillBudgetIdentity;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verifies terminal Skill children and their own task and wallet identities without mutating them. */
@Service
public class AssistantTerminalChildEvidenceVerifier
{
    private static final int MAX_ROWS = 200;
    @Resource private AidSkillRunMapper skillRuns;
    @Resource private AidSkillRunStepMapper skillSteps;
    @Resource private AidSkillRunTaskLinkMapper links;
    @Resource private AidMediaTaskMapper tasks;
    @Resource private AidBillingAuthorizationReservationMapper reservations;
    @Resource private IAccountUpdateService accounts;

    /** Callers own the outer session/run locks before requesting locked evidence. */
    public void requireTerminalChildren(AidRuntimeRun parent, List<AidRuntimeRunStep> outerSteps,
            Map<Long, AidRuntimeApproval> outerApprovals, boolean lock)
    {
        requireTerminalChildReservationIds(parent, outerSteps, outerApprovals, lock);
    }

    /** Exact reservations proved by the typed Skill graph, never inferred from an operation label. */
    public Set<Long> requireTerminalChildReservationIds(AidRuntimeRun parent,
            List<AidRuntimeRunStep> outerSteps, Map<Long, AidRuntimeApproval> outerApprovals, boolean lock)
    {
        require(parent != null && parent.getId() != null && parent.getUserId() != null
                && outerSteps != null && outerSteps.size() <= MAX_ROWS, "CHILD_GRAPH_IDENTITY_UNPROVEN");
        if (outerSteps.isEmpty()) return Set.of();
        Set<Long> rootIds = new HashSet<>();
        for (AidRuntimeRunStep outer : outerSteps)
            require(outer != null && "SKILL_ORCHESTRATOR".equals(outer.getChildRunKind())
                    && outer.getChildRunId() != null && rootIds.add(outer.getChildRunId()),
                    "CHILD_RUNTIME_KIND_UNSUPPORTED");
        String bounded = lock ? "LIMIT 201 FOR UPDATE" : "LIMIT 201";
        List<AidSkillRun> runs = new ArrayList<>(skillRuns.selectList(new LambdaQueryWrapper<AidSkillRun>()
                .in(AidSkillRun::getId, rootIds).orderByAsc(AidSkillRun::getId).last(bounded)));
        runs.addAll(skillRuns.selectList(new LambdaQueryWrapper<AidSkillRun>()
                .in(AidSkillRun::getRootRunId, rootIds).orderByAsc(AidSkillRun::getId).last(bounded)));
        Map<Long, AidSkillRun> runById = unique(runs, AidSkillRun::getId);
        require(runById.keySet().containsAll(rootIds), "CHILD_GRAPH_IDENTITY_UNPROVEN");
        List<Long> runIds = List.copyOf(runById.keySet());
        List<AidSkillRunStep> stepRows = skillSteps.selectList(new LambdaQueryWrapper<AidSkillRunStep>()
                .in(AidSkillRunStep::getRunId, runIds).orderByAsc(AidSkillRunStep::getId).last(bounded));
        List<AidSkillRunTaskLink> linkRows = links.selectList(new LambdaQueryWrapper<AidSkillRunTaskLink>()
                .in(AidSkillRunTaskLink::getRunId, runIds).orderByAsc(AidSkillRunTaskLink::getId).last(bounded));
        require(stepRows.size() <= MAX_ROWS && linkRows.size() <= MAX_ROWS, "CHILD_GRAPH_INCOMPLETE");
        List<Long> stepIds = stepRows.stream().map(AidSkillRunStep::getId).toList();
        Set<String> callKeys = new HashSet<>(linkRows.stream().map(AidSkillRunTaskLink::getLogicalCallKey).toList());
        for (AidSkillRunStep step : stepRows)
        {
            AidSkillRun owner = runById.get(step.getRunId());
            require(owner != null && digest(owner.getIdempotencyScopeHash()) && owner.getGeneration() != null,
                    "CHILD_GRAPH_LINK_UNPROVEN");
            callKeys.add(SecureUtil.sha256(owner.getIdempotencyScopeHash() + "|" + owner.getGeneration()
                    + "|" + step.getStepExecutionId() + "|" + step.getWorkflowAttempt()));
        }
        List<Long> taskIds = linkRows.stream().map(AidSkillRunTaskLink::getMediaTaskId).toList();
        require(!stepIds.contains(null) && !callKeys.contains(null) && !taskIds.contains(null), "CHILD_GRAPH_LINK_UNPROVEN");
        List<AidMediaTask> taskRows = new ArrayList<>();
        if (!taskIds.isEmpty()) taskRows.addAll(tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .in(AidMediaTask::getId, taskIds).last(bounded)));
        if (!stepIds.isEmpty()) taskRows.addAll(tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getBizTaskType, "SKILL_RUNTIME_STEP")
                .in(AidMediaTask::getBizTaskId, stepIds).last(bounded)));
        if (!callKeys.isEmpty()) taskRows.addAll(tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .in(AidMediaTask::getIdempotencyKey, callKeys).last(bounded)));
        Map<Long, AidMediaTask> taskById = unique(taskRows, AidMediaTask::getId);
        List<Long> approvalIds = outerSteps.stream().map(AidRuntimeRunStep::getApprovalId).toList();
        require(!approvalIds.contains(null), "CHILD_GRAPH_APPROVAL_UNPROVEN");
        List<AidBillingAuthorizationReservation> receiptRows = new ArrayList<>(reservations.selectList(
                new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                        .in(AidBillingAuthorizationReservation::getApprovalId, approvalIds).last(bounded)));
        if (!stepIds.isEmpty()) receiptRows.addAll(reservations.selectList(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .eq(AidBillingAuthorizationReservation::getOperationKind, "SKILL_TEXT")
                .in(AidBillingAuthorizationReservation::getRuntimeStepId, stepIds).last(bounded)));
        if (!callKeys.isEmpty()) receiptRows.addAll(reservations.selectList(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .in(AidBillingAuthorizationReservation::getLogicalCallKey, callKeys).last(bounded)));
        Map<Long, AidBillingAuthorizationReservation> receiptById = unique(receiptRows,
                AidBillingAuthorizationReservation::getId);
        Set<String> traces = new HashSet<>();
        for (AidMediaTask task : taskById.values())
            require(StrUtil.isNotBlank(task.getBillingTraceId()) && traces.add(task.getBillingTraceId()),
                    "CHILD_GRAPH_TASK_IDENTITY_UNPROVEN");
        Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger = traces.isEmpty()
                ? Map.of() : accounts.inspectLedgerBatch(traces);
        require(ledger != null && ledger.keySet().equals(traces) && ledger.values().stream().noneMatch(Objects::isNull),
                "CHILD_GRAPH_LEDGER_UNAVAILABLE");
        Map<Long, SkillIdentity> identities = new HashMap<>();
        for (AidSkillRun run : runById.values()) identities.put(run.getId(), projectSkillIdentity(run.getInputJson()));
        Map<Long, JSONObject> checkpoints = new HashMap<>();
        for (AidSkillRunStep step : stepRows) checkpoints.put(step.getId(), JSON.parseObject(step.getCheckpointJson()));
        Map<Long, BillingSnapshot> snapshots = new HashMap<>();
        for (AidMediaTask task : taskById.values()) snapshots.put(task.getId(),
                JSON.parseObject(task.getBillingSnapshotJson(), BillingSnapshot.class));
        verifySkillGraph(parent, outerSteps, outerApprovals, runById, stepRows, linkRows,
                taskById, receiptById, identities, checkpoints, snapshots, ledger);
        return Set.copyOf(receiptById.keySet());
    }

    static SkillIdentity projectSkillIdentity(String inputJson)
    {
        JSONObject input = JSON.parseObject(inputJson);
        AssistantSkillBudgetEnvelope envelope = input == null ? null
                : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
        AssistantSkillBudgetIdentity.validate(envelope);
        return new SkillIdentity(envelope.getAssistantRuntimeRunId(), envelope.getAssistantParentStepId(),
                envelope.getAssistantOuterStableStepKey(), envelope.getAssistantAttemptNo(),
                envelope.getBillingAuthorizationId(), envelope.getBillingAuthorizationRevision(),
                envelope.getPayerUserId(), envelope.getApprovalId(), envelope.getSkillVersionId(),
                envelope.getPackageDigest(), envelope.getApprovedScopeDigest(),
                envelope.getApprovedOperationScopeDigest(), SecureUtil.sha256(envelope.getApprovedOperationScopeJson()));
    }

    static void verifySkillGraph(AidRuntimeRun parent, List<AidRuntimeRunStep> outerSteps,
            Map<Long, AidRuntimeApproval> outerApprovals, Map<Long, AidSkillRun> runById,
            List<AidSkillRunStep> stepRows, List<AidSkillRunTaskLink> linkRows,
            Map<Long, AidMediaTask> taskById, Map<Long, AidBillingAuthorizationReservation> receiptById,
            Map<Long, SkillIdentity> identities, Map<Long, JSONObject> checkpoints,
            Map<Long, BillingSnapshot> snapshots, Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger)
    {
        require(!runById.isEmpty() && runById.size() <= MAX_ROWS && stepRows.size() <= MAX_ROWS
                && linkRows.size() <= MAX_ROWS && taskById.size() <= MAX_ROWS && receiptById.size() <= MAX_ROWS,
                "CHILD_GRAPH_INCOMPLETE");
        Map<Long, AidRuntimeRunStep> outerByChild = new HashMap<>();
        for (AidRuntimeRunStep outer : outerSteps)
        {
            AidRuntimeApproval approval = outerApprovals.get(outer.getApprovalId());
            require(Objects.equals(parent.getId(), outer.getRunId()) && "0".equals(outer.getDelFlag())
                    && "SKILL".equals(outer.getStepKind()) && "ASSISTANT_SKILL".equals(outer.getOperationKind())
                    && "SKILL_ORCHESTRATOR".equals(outer.getChildRunKind())
                    && Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(Objects.toString(outer.getStatus(), ""))
                    && "ACCEPTED".equals(outer.getProviderAcceptanceState())
                    && outer.getMediaTaskId() == null && outer.getReservationId() == null
                    && approval != null && "0".equals(approval.getDelFlag())
                    && Objects.equals(approval.getRunId(), parent.getId())
                    && Objects.equals(approval.getUserId(), parent.getUserId())
                    && "PLAN_EXECUTION".equals(approval.getScopeKind()) && "CONSUMED".equals(approval.getStatus())
                    && approval.getAcceptedAt() != null && Objects.equals(outer.getInputDigest(), approval.getRequestDigest())
                    && outerByChild.putIfAbsent(outer.getChildRunId(), outer) == null,
                    "CHILD_GRAPH_APPROVAL_UNPROVEN");
            AssistantPlanExecutionScopeCodec.Persisted frozen =
                    new AssistantPlanExecutionScopeCodec().decode(approval.getScopeJson());
            require(!frozen.isTool() && "SKILL".equals(frozen.plan().getString("executorKind"))
                    && Objects.equals(frozen.requestDigest(), approval.getRequestDigest())
                    && Objects.equals(frozen.plan().getString("stableStepId"), outer.getStableStepId())
                    && Objects.equals(frozen.plan().getInteger("position"), outer.getPlanPosition())
                    && Objects.equals(frozen.plan().getString("definitionDigest"), approval.getPlanDigest())
                    && Objects.equals(frozen.context().getString("digest"), approval.getContextDigest())
                    && Objects.equals(frozen.policy().getLong("authorizationId"), approval.getBillingAuthorizationId())
                    && Objects.equals(frozen.policy().getLong("authorizationRevision"), approval.getBillingAuthorizationRevision())
                    && Objects.equals(outer.getPlanRevision(), approval.getPlanRevision())
                    && Objects.equals(frozen.decision().getLong("observedPlanRevision"), approval.getPlanRevision())
                    && frozen.decision().getLong("mainAgentStepId") != null
                    && frozen.decision().getLong("mainAgentStepId") > 0,
                    "CHILD_GRAPH_APPROVAL_UNPROVEN");
        }
        for (AidSkillRun run : runById.values())
        {
            // Current METHOD executions have no nested Skill invocation graph. Unsupported workflow
            // kinds retain reconciliation protection rather than being mistaken for Runtime runs.
            AidRuntimeRunStep outer = outerByChild.get(run.getId());
            require(outer != null && Objects.equals(run.getRootRunId(), run.getId()) && run.getParentRunId() == null
                    && "METHOD".equals(run.getActionMode()), "CHILD_GRAPH_WORKFLOW_UNSUPPORTED");
            require("0".equals(run.getDelFlag()) && Objects.equals(run.getUserId(), parent.getUserId())
                    && "ASSISTANT_RUNTIME".equals(run.getInvokeSource())
                    && Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(Objects.toString(run.getStatus(), ""))
                    && run.getFinishedAt() != null
                    && ("SUCCEEDED".equals(run.getStatus()) ? "SUCCEEDED".equals(outer.getStatus())
                            && StrUtil.isNotBlank(run.getOutputJson()) : "FAILED".equals(run.getStatus())
                            ? "FAILED".equals(outer.getStatus())
                            : Set.of("FAILED", "CANCELLED").contains(outer.getStatus()))
                    && digest(run.getIdempotencyScopeHash()) && Objects.equals(run.getGeneration(), 0)
                    && Objects.equals(run.getSkillVersionId(), outer.getSkillVersionId())
                    && Objects.equals(run.getClientRequestId(), SecureUtil.sha256("assistant-skill-method|" + outer.getIdempotencyKey())),
                    "CHILD_GRAPH_IDENTITY_UNPROVEN");
            AidRuntimeApproval approval = outerApprovals.get(outer.getApprovalId());
            AssistantPlanExecutionScopeCodec.Persisted source = new AssistantPlanExecutionScopeCodec().decode(approval.getScopeJson());
            SkillIdentity identity = identities.get(run.getId());
            require(identity != null && Objects.equals(identity.runtimeRunId(), parent.getId())
                    && Objects.equals(identity.parentStepId(), outer.getId())
                    && Objects.equals(identity.outerStableStepKey(), outer.getStableStepId())
                    && Objects.equals(identity.outerAttempt(), outer.getAttemptNo())
                    && Objects.equals(identity.authorizationId(), approval.getBillingAuthorizationId())
                    && Objects.equals(identity.authorizationRevision(), approval.getBillingAuthorizationRevision())
                    && Objects.equals(identity.payerId(), parent.getUserId()) && Objects.equals(identity.approvalId(), approval.getId())
                    && Objects.equals(identity.skillVersionId(), run.getSkillVersionId())
                    && Objects.equals(identity.packageDigest(), run.getSkillConfigHash())
                    && source.skill() != null && Objects.equals(source.skill().getString("packageDigest"), run.getSkillConfigHash())
                    && Objects.equals(source.skill().getLong("skillVersionId"), run.getSkillVersionId())
                    && Objects.equals(source.digest(), approval.getScopeDigest())
                    && Objects.equals(identity.approvedScopeDigest(), approval.getScopeDigest())
                    && Objects.equals(identity.operationScopeDigest(), identity.operationScopeJsonDigest())
                    && Objects.equals(identity.operationScopeDigest(), SecureUtil.sha256(AssistantPlanExecutionJson.canonical(
                            JSON.parseObject(JSON.toJSONString(source.operationScope()))))), "CHILD_GRAPH_ENVELOPE_UNPROVEN");
        }
        Map<Long, AidSkillRunStep> stepById = unique(stepRows, AidSkillRunStep::getId);
        require(stepById.size() == stepRows.size(), "CHILD_GRAPH_DUPLICATE_STEP");
        require(checkpoints.keySet().equals(stepById.keySet()) && identities.keySet().equals(runById.keySet())
                && snapshots.keySet().equals(taskById.keySet())
                && ledger.keySet().equals(taskById.values().stream().map(AidMediaTask::getBillingTraceId)
                        .collect(java.util.stream.Collectors.toSet())), "CHILD_GRAPH_ORPHAN_OR_MISSING_EVIDENCE");
        for (AidSkillRunStep step : stepRows)
        {
            AidSkillRun run = runById.get(step.getRunId());
            JSONObject checkpoint = checkpoints.get(step.getId());
            require(run != null && "0".equals(step.getDelFlag())
                    && ("SUCCEEDED".equals(run.getStatus()) ? "OUTPUT_READY".equals(step.getOrchestrationStatus())
                        : Set.of("OUTPUT_READY", "WAITING_TASK", "INFLIGHT", "PLANNED").contains(
                                Objects.toString(step.getOrchestrationStatus(), "")))
                    && "METHOD".equals(step.getActionMode()) && "method".equals(step.getStepKey())
                    && Objects.equals(step.getStepSeq(), 0) && Objects.equals(step.getWorkflowAttempt(), 0)
                    && Objects.equals(step.getSkillId(), run.getSkillId())
                    && Objects.equals(step.getSkillVersionId(), run.getSkillVersionId()) && checkpoint != null
                    && Boolean.TRUE.equals(checkpoint.get("methodOnly"))
                    && Objects.equals(checkpoint.getLong("childSkillId"), run.getSkillId())
                    && Objects.equals(checkpoint.getLong("childVersionId"), run.getSkillVersionId())
                    && Objects.equals(checkpoint.getString("childPackageDigest"), run.getSkillConfigHash())
                    && digest(checkpoint.getString("promptDigest"))
                    && validResourceLocks(checkpoint.get("resources"))
                    && Objects.equals(step.getStepExecutionId(), SecureUtil.sha256(run.getId() + "|method|0|0|"
                            + checkpoint.getString("promptDigest"))), "CHILD_GRAPH_CHECKPOINT_UNPROVEN");
        }
        Set<Long> linkedTasks = new HashSet<>();
        Set<Long> linkedSteps = new HashSet<>();
        Set<String> linkedKeys = new HashSet<>();
        Set<Long> matchedReservations = new HashSet<>();
        for (AidSkillRunTaskLink link : linkRows)
        {
            AidSkillRunStep step = stepById.get(link.getStepId());
            AidSkillRun run = runById.get(link.getRunId());
            AidMediaTask task = taskById.get(link.getMediaTaskId());
            require(step != null && run != null && task != null && "0".equals(link.getDelFlag())
                    && Objects.equals(step.getRunId(), run.getId())
                    && Objects.equals(link.getStepExecutionId(), step.getStepExecutionId())
                    && Objects.equals(link.getWorkflowAttempt(), step.getWorkflowAttempt())
                    && StrUtil.isNotBlank(link.getLogicalCallKey())
                    && Objects.equals(link.getLogicalCallKey(), SecureUtil.sha256(run.getIdempotencyScopeHash()
                            + "|" + run.getGeneration() + "|" + step.getStepExecutionId() + "|" + step.getWorkflowAttempt()))
                    && linkedKeys.add(link.getLogicalCallKey())
                    && linkedTasks.add(link.getMediaTaskId()) && linkedSteps.add(step.getId())
                    && "SKILL_RUNTIME_STEP".equals(task.getBizTaskType())
                    && Objects.equals(task.getBizTaskId(), step.getId())
                    && Objects.equals(task.getIdempotencyKey(), link.getLogicalCallKey())
                    && Objects.equals(task.getUserId(), parent.getUserId()) && "TEXT".equals(task.getMediaType()),
                    "CHILD_GRAPH_LINK_UNPROVEN");
            BillingSnapshot snapshot = snapshots.get(task.getId());
            AidRuntimeRunStep outer = outerByChild.get(run.getId());
            AidRuntimeApproval approval = outerApprovals.get(outer.getApprovalId());
            AidBillingAuthorizationReservation receipt = snapshot == null ? null : receiptById.get(snapshot.getBillingReservationId());
            String stable = outer.getStableStepId() + "/skill/" + step.getStepExecutionId();
            if (stable.length() > 128) stable = "skill:" + SecureUtil.sha256(outer.getStableStepId() + "|" + step.getStepExecutionId());
            require(snapshot != null && receipt != null && matchedReservations.add(receipt.getId())
                    && "RUN_AUTHORIZATION".equals(snapshot.getBudgetMode()) && "0".equals(receipt.getDelFlag())
                    && Objects.equals(receipt.getRuntimeRunId(), parent.getId())
                    && Objects.equals(receipt.getRuntimeStepId(), step.getId())
                    && Objects.equals(receipt.getApprovalId(), approval.getId())
                    && Objects.equals(receipt.getAuthorizationId(), approval.getBillingAuthorizationId())
                    && Objects.equals(receipt.getAuthorizationRevision(), approval.getBillingAuthorizationRevision())
                    && "SKILL_TEXT".equals(receipt.getOperationKind()) && Objects.equals(receipt.getAttemptNo(), step.getWorkflowAttempt())
                    && Objects.equals(receipt.getStableStepKey(), stable)
                    && Objects.equals(receipt.getMediaTaskId(), task.getId())
                    && Objects.equals(receipt.getLogicalCallKey(), link.getLogicalCallKey())
                    && Objects.equals(snapshot.getBillingAuthorizationId(), receipt.getAuthorizationId())
                    && Objects.equals(snapshot.getBillingAuthorizationRevision(), receipt.getAuthorizationRevision())
                    && Objects.equals(snapshot.getRuntimeRunId(), parent.getId())
                    && Objects.equals(snapshot.getRuntimeStepId(), step.getId())
                    && Objects.equals(snapshot.getStableStepKey(), stable)
                    && Objects.equals(snapshot.getBillingAttemptNo(), step.getWorkflowAttempt())
                    && Objects.equals(snapshot.getLogicalCallKey(), link.getLogicalCallKey())
                    && digest(snapshot.getRequestDigest()) && Objects.equals(snapshot.getRequestDigest(), receipt.getRequestDigest())
                    && Objects.equals(snapshot.getQuoteId(), receipt.getQuoteId())
                    && Objects.equals(snapshot.getQuoteDigest(), receipt.getQuoteDigest())
                    && same(snapshot.getAuthorizedChargeCap(), receipt.getQuotedUpperBound()), "CHILD_GRAPH_RESERVATION_UNPROVEN");
            // A failed Skill may have a successfully charged task (for example an empty method
            // observation), or a refunded failed task. The wallet proof, never the Run label,
            // determines its cost. Stale WAITING_TASK is closed only by this complete terminal proof.
            requireTerminalSettlement(parent.getUserId(), receipt, task, ledger.get(task.getBillingTraceId()));
            if ("SUCCEEDED".equals(run.getStatus())) require("SUCCEEDED".equals(task.getStatus())
                    && "SETTLED".equals(receipt.getState()), "CHILD_GRAPH_SETTLEMENT_UNPROVEN");
        }
        Set<Long> unsubmittedSteps = new HashSet<>();
        for (AidSkillRunStep step : stepRows)
        {
            if (linkedSteps.contains(step.getId())) continue;
            AidSkillRun owner = runById.get(step.getRunId());
            require(!"SUCCEEDED".equals(owner.getStatus()) && "PLANNED".equals(step.getOrchestrationStatus()),
                    "CHILD_GRAPH_UNLINKED_STEP_UNPROVEN");
            unsubmittedSteps.add(step.getId());
        }
        Set<Long> completeSteps = new HashSet<>(linkedSteps);
        completeSteps.addAll(unsubmittedSteps);
        require(linkedTasks.equals(taskById.keySet()) && matchedReservations.equals(receiptById.keySet())
                && completeSteps.equals(stepById.keySet()), "CHILD_GRAPH_ORPHAN_OR_MISSING_EVIDENCE");
        Set<Long> runsWithSteps = stepRows.stream().map(AidSkillRunStep::getRunId)
                .collect(java.util.stream.Collectors.toSet());
        for (AidSkillRun run : runById.values())
            require(runsWithSteps.contains(run.getId()) || !"SUCCEEDED".equals(run.getStatus()),
                    "CHILD_GRAPH_ORPHAN_OR_MISSING_EVIDENCE");

    }

    public static void requireSettled(Long actor, AidBillingAuthorizationReservation row, AidMediaTask task,
            IAccountUpdateService.WalletLedgerEvidence ledger)
    {
        require(task != null && row != null && "SUCCEEDED".equals(task.getStatus())
                && "SETTLED".equals(row.getState()), "CHILD_GRAPH_SETTLEMENT_UNPROVEN");
        requireTerminalSettlement(actor, row, task, ledger);
    }

    public static void requireTerminalSettlement(Long actor, AidBillingAuthorizationReservation row, AidMediaTask task,
            IAccountUpdateService.WalletLedgerEvidence ledger)
    {
        require(actor != null && actor > 0 && task != null && row != null
                && task.getId() != null && task.getId() > 0 && row.getId() != null && row.getId() > 0
                && "0".equals(row.getDelFlag()) && StrUtil.isNotBlank(task.getBillingTraceId())
                && Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(Objects.toString(task.getStatus(), ""))
                && Objects.equals(task.getUserId(), actor) && Objects.equals(task.getId(), row.getMediaTaskId())
                && Objects.equals(task.getBillingTraceId(), row.getBillingTraceId())
                && nonnegative(row.getReservedAmount()) && nonnegative(row.getSettledAmount())
                && nonnegative(row.getReleasedAmount()) && nonnegative(row.getWalletFrozenAmount())
                && nonnegative(row.getWalletRefundedAmount())
                && same(row.getReservedAmount(), row.getSettledAmount().add(row.getReleasedAmount()))
                && same(row.getWalletFrozenAmount(), row.getSettledAmount().add(row.getWalletRefundedAmount())),
                "CHILD_GRAPH_SETTLEMENT_UNPROVEN");
        boolean paid = "SETTLED".equals(row.getState());
        require(paid ? "SUCCESS".equals(task.getBillingStatus())
                        && Objects.equals(row.getSettlementKey(), "settle:" + task.getId())
                        && same(task.getActualCost(), row.getSettledAmount())
                : "REFUNDED".equals(row.getState()) && "FAILED".equals(task.getBillingStatus())
                        && Objects.equals(row.getSettlementKey(), "refund:" + task.getId())
                        && row.getSettledAmount().signum() == 0
                        && (task.getActualCost() == null || task.getActualCost().signum() == 0),
                "CHILD_GRAPH_SETTLEMENT_UNPROVEN");
        if (row.getWalletFrozenAmount().signum() == 0)
            require(ledger != null && ledger.empty() && Objects.equals(ledger.traceId(), row.getBillingTraceId())
                            && row.getSettledAmount().signum() == 0,
                    "CHILD_GRAPH_LEDGER_UNPROVEN");
        else require(ledger != null && ledger.valid() && Objects.equals(ledger.userId(), actor)
                    && Objects.equals(ledger.traceId(), row.getBillingTraceId())
                    && same(ledger.frozenAmount(), row.getWalletFrozenAmount())
                    && same(ledger.netConsumedAmount(), row.getSettledAmount())
                    && same(ledger.settleUnfrozenAmount(), BigDecimal.ZERO)
                    && (paid ? same(ledger.grossConsumedAmount(), row.getWalletFrozenAmount())
                            && same(ledger.settleRefundedAmount(), row.getWalletRefundedAmount())
                            && same(ledger.unfrozenAmount(), BigDecimal.ZERO)
                        : same(ledger.unfrozenAmount(), row.getWalletFrozenAmount())
                            && same(ledger.grossConsumedAmount(), BigDecimal.ZERO)
                            && same(ledger.settleRefundedAmount(), BigDecimal.ZERO)), "CHILD_GRAPH_LEDGER_UNPROVEN");
    }

    private static <T> Map<Long, T> unique(Collection<T> rows, java.util.function.Function<T, Long> identity)
    {
        require(rows.size() <= MAX_ROWS * 3, "CHILD_GRAPH_INCOMPLETE");
        Map<Long, T> result = new HashMap<>();
        for (T row : rows)
        {
            require(row != null && identity.apply(row) != null, "CHILD_GRAPH_IDENTITY_UNPROVEN");
            T prior = result.putIfAbsent(identity.apply(row), row);
            require(prior == null || Objects.equals(JSON.toJSONString(prior), JSON.toJSONString(row)),
                    "CHILD_GRAPH_IDENTITY_UNPROVEN");
        }
        require(result.size() <= MAX_ROWS, "CHILD_GRAPH_INCOMPLETE");
        return result;
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean validResourceLocks(Object raw)
    {
        if (!(raw instanceof JSONArray resources) || resources.size() > MAX_ROWS) return false;
        Set<String> keys = new HashSet<>();
        for (Object value : resources)
        {
            if (!(value instanceof JSONObject item) || !item.keySet().equals(Set.of("resourceKey", "digest"))
                    || !(item.get("resourceKey") instanceof String key) || StrUtil.isBlank(key)
                    || !keys.add(key) || !digest(item.getString("digest"))) return false;
        }
        return true;
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    private static boolean nonnegative(BigDecimal value) { return value != null && value.signum() >= 0; }
    private static void require(boolean valid, String code) { if (!valid) throw new ServiceException(code); }

    public record SkillIdentity(Long runtimeRunId, Long parentStepId, String outerStableStepKey,
            Integer outerAttempt, Long authorizationId, Long authorizationRevision, Long payerId,
            Long approvalId, Long skillVersionId, String packageDigest, String approvedScopeDigest,
            String operationScopeDigest, String operationScopeJsonDigest) { }
}
