package com.aid.assistant.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Projects verified own-run settlement facts without changing accounting state. */
@Slf4j
@Service
public class AssistantRunCostObservationService
{
    private static final int MAX_ROWS = 200;
    private static final String KIND = "CURRENT_RUN_COST_FACTS";
    private static final String AUTHORITY = "SERVER_OWNED_RUN_SETTLEMENT";
    private static final Set<String> TERMINAL_TASKS = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> PENDING_RESERVATIONS = Set.of("RESERVING", "RESERVED", "INFLIGHT", "RECONCILING");
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidBillingAuthorizationReservationMapper reservations;
    @Resource private AidMediaTaskMapper tasks;
    @Resource private IAccountUpdateService accounts;
    @Resource private AssistantLinkedSkillCostScopeService linkedSkillCosts;

    public void appendToContext(Long actorId, AidRuntimeRun run, JSONObject root, String fixedInputSchema)
    {
        if (appendIfCompatible(root, fixedInputSchema, observe(actorId, run))) return;
        JSONObject policy = root.getJSONObject("priorResultsPolicy");
        if (policy != null)
            policy.put("readAttempts", StrUtil.nullToEmpty(policy.getString("readAttempts"))
                    + " Verified cost facts are unavailable for this fixed input observation contract. Their absence never establishes zero cost or a free run.");
        log.info("Assistant cost observation omitted: FIXED_INPUT_OBSERVATIONS_CONTRACT_UNSUPPORTED");
    }

    public JSONObject observe(Long actorId, AidRuntimeRun run)
    {
        String startedAt = DateUtil.now();
        if (run == null || run.getId() == null || actorId == null
                || !Objects.equals(actorId, run.getUserId())) return unavailable(startedAt);
        try
        {
            // Keep the extra row so a bounded scan cannot masquerade as a complete total.
            List<AidRuntimeRunStep> stepRows = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getRunId,
                            AidRuntimeRunStep::getStepKind, AidRuntimeRunStep::getStableStepId,
                            AidRuntimeRunStep::getSkillVersionId,
                            AidRuntimeRunStep::getInputDigest,
                            AidRuntimeRunStep::getAttemptNo, AidRuntimeRunStep::getOperationKind,
                            AidRuntimeRunStep::getIdempotencyKey, AidRuntimeRunStep::getApprovalId,
                            AidRuntimeRunStep::getReservationId, AidRuntimeRunStep::getMediaTaskId,
                            AidRuntimeRunStep::getChildRunId, AidRuntimeRunStep::getDelFlag)
                    .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                    .orderByAsc(AidRuntimeRunStep::getId).last("LIMIT 201"));
            List<AidBillingAuthorizationReservation> reservationRows = reservations.selectList(
                    new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                            .select(AidBillingAuthorizationReservation::getId,
                                    AidBillingAuthorizationReservation::getAuthorizationId,
                                    AidBillingAuthorizationReservation::getAuthorizationRevision,
                                    AidBillingAuthorizationReservation::getRuntimeRunId,
                                    AidBillingAuthorizationReservation::getRuntimeStepId,
                                    AidBillingAuthorizationReservation::getStableStepKey,
                                    AidBillingAuthorizationReservation::getAttemptNo,
                                    AidBillingAuthorizationReservation::getOperationKind,
                                    AidBillingAuthorizationReservation::getApprovalId,
                                    AidBillingAuthorizationReservation::getLogicalCallKey,
                                    AidBillingAuthorizationReservation::getRequestDigest,
                                    AidBillingAuthorizationReservation::getMediaTaskId,
                                    AidBillingAuthorizationReservation::getBillingTraceId,
                                    AidBillingAuthorizationReservation::getReservedAmount,
                                    AidBillingAuthorizationReservation::getWalletFrozenAmount,
                                    AidBillingAuthorizationReservation::getSettledAmount,
                                    AidBillingAuthorizationReservation::getReleasedAmount,
                                    AidBillingAuthorizationReservation::getWalletRefundedAmount,
                                    AidBillingAuthorizationReservation::getState,
                                    AidBillingAuthorizationReservation::getSettlementKey,
                                    AidBillingAuthorizationReservation::getDelFlag)
                            .eq(AidBillingAuthorizationReservation::getRuntimeRunId, run.getId())
                            .eq(AidBillingAuthorizationReservation::getDelFlag, "0")
                            .orderByAsc(AidBillingAuthorizationReservation::getId).last("LIMIT 201"));
            if (stepRows.size() > MAX_ROWS || reservationRows.size() > MAX_ROWS)
                return summarize(actorId, run.getId(), stepRows, reservationRows, Map.of(), Map.of(),
                        false, startedAt, DateUtil.now());
            Set<Long> taskIds = new HashSet<>();
            for (AidRuntimeRunStep step : stepRows)
                if (step.getMediaTaskId() != null) taskIds.add(step.getMediaTaskId());
            for (AidBillingAuthorizationReservation reservation : reservationRows)
                if (reservation.getMediaTaskId() != null) taskIds.add(reservation.getMediaTaskId());
            if (taskIds.size() > MAX_ROWS) return unavailable(startedAt);
            if (stepRows.isEmpty()) return summarize(actorId, run.getId(), stepRows, reservationRows,
                    Map.of(), Map.of(), true, startedAt, DateUtil.now());
            Map<Long, AidMediaTask> taskRows = new HashMap<>();
            if (!taskIds.isEmpty())
                for (AidMediaTask task : tasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                        .select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getMediaType,
                                AidMediaTask::getBizTaskId, AidMediaTask::getBizTaskType,
                                AidMediaTask::getIdempotencyKey,
                                AidMediaTask::getBillingSnapshotJson,
                                AidMediaTask::getStatus, AidMediaTask::getBillingStatus,
                                AidMediaTask::getBillingTraceId, AidMediaTask::getActualCost)
                        .in(AidMediaTask::getId, taskIds)
                        .eq(AidMediaTask::getUserId, actorId)))
                    taskRows.put(task.getId(), task);
            Set<Long> linkedChildReservations = linkedSkillCosts.identify(
                    actorId, run, stepRows, reservationRows, taskRows);
            Set<String> ledgerTraces = new HashSet<>();
            Map<Long, AidRuntimeRunStep> byStep = new HashMap<>();
            for (AidRuntimeRunStep step : stepRows) byStep.put(step.getId(), step);
            for (AidBillingAuthorizationReservation reservation : reservationRows)
            {
                AidRuntimeRunStep step = byStep.get(reservation.getRuntimeStepId());
                AidMediaTask task = reservation.getMediaTaskId() == null ? null
                        : taskRows.get(reservation.getMediaTaskId());
                if (matchesStep(run.getId(), step, reservation)
                        && matchesTask(actorId, step, reservation, task)
                        && TERMINAL_TASKS.contains(StrUtil.nullToEmpty(task.getStatus())))
                    ledgerTraces.add(task.getBillingTraceId());
            }
            Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger = ledgerTraces.isEmpty()
                    ? Map.of() : accounts.inspectLedgerBatch(ledgerTraces);
            if (ledger == null || !ledger.keySet().equals(ledgerTraces)
                    || ledger.values().stream().anyMatch(Objects::isNull)) return unavailable(startedAt);
            return summarize(actorId, run.getId(), stepRows, reservationRows, taskRows, ledger,
                    true, startedAt, DateUtil.now(), linkedChildReservations);
        }
        catch (RuntimeException unavailable)
        {
            log.info("Assistant cost facts unavailable, runId={}, cause={}", run.getId(),
                    unavailable.getClass().getSimpleName());
            return unavailable(startedAt);
        }
    }

    public static boolean appendIfCompatible(JSONObject root, String fixedInputSchema, JSONObject observation)
    {
        if (root == null || observation == null || StrUtil.isBlank(fixedInputSchema)) return false;
        JSONArray existing = root.getJSONArray("observations");
        JSONArray values = existing == null ? new JSONArray() : new JSONArray(existing);
        for (Object raw : values)
            if (raw instanceof JSONObject value && (KIND.equals(value.getString("observationKind"))
                    || AUTHORITY.equals(value.getString("authority")))) return false;
        values.add(observation);
        try
        {
            JSONObject schema = JSON.parseObject(fixedInputSchema);
            if (schema == null || !"object".equals(schema.getString("type"))) return false;
            // Do not guess how an old root-level conditional contract affects this optional field.
            for (String key : Set.of("$ref", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
                    "dependentSchemas", "dependentRequired", "dependencies", "maxProperties", "propertyNames",
                    "patternProperties", "unevaluatedProperties"))
                if (schema.containsKey(key)) return false;
            JSONObject properties = schema.getJSONObject("properties");
            JSONObject observationSchema = properties == null ? null : properties.getJSONObject("observations");
            if (observationSchema == null) return false;
            // Check the unchanged field contract, including original root definitions for local refs.
            // Other existing root fields are not introduced or reinterpreted by this projection.
            JSONSchema parent = JSONSchema.of(schema);
            if (!JSONSchema.of(observationSchema, parent).validate(values).isSuccess()) return false;
        }
        catch (RuntimeException incompatible) { return false; }
        root.put("observations", values);
        return true;
    }

    static JSONObject summarize(Long actorId, Long runId, List<AidRuntimeRunStep> stepRows,
                                List<AidBillingAuthorizationReservation> reservationRows,
                                Map<Long, AidMediaTask> taskRows,
                                Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger,
                                boolean scanComplete, String startedAt, String asOf)
    {
        return summarize(actorId, runId, stepRows, reservationRows, taskRows, ledger,
                scanComplete, startedAt, asOf, Set.of());
    }

    static JSONObject summarize(Long actorId, Long runId, List<AidRuntimeRunStep> stepRows,
                                List<AidBillingAuthorizationReservation> reservationRows,
                                Map<Long, AidMediaTask> taskRows,
                                Map<String, IAccountUpdateService.WalletLedgerEvidence> ledger,
                                boolean scanComplete, String startedAt, String asOf,
                                Set<Long> linkedChildReservations)
    {
        JSONObject result = base(startedAt, asOf);
        result.put("scanComplete", scanComplete);
        if (!scanComplete)
        {
            result.put("evidenceState", "INCOMPLETE_SCAN");
            return result;
        }
        Map<Long, AidRuntimeRunStep> byStep = new HashMap<>();
        Set<Long> duplicateSteps = new HashSet<>();
        int excludedChildRuns = 0;
        for (AidRuntimeRunStep step : stepRows)
        {
            if (byStep.put(step.getId(), step) != null) duplicateSteps.add(step.getId());
            if (step.getChildRunId() != null) excludedChildRuns++;
        }
        Map<Long, Integer> reservationCounts = new HashMap<>();
        Map<Long, Integer> taskCounts = new HashMap<>();
        Map<String, Integer> traceCounts = new HashMap<>();
        List<AidBillingAuthorizationReservation> directRows = reservationRows.stream()
                .filter(row -> !linkedChildReservations.contains(row.getId())).toList();
        for (AidBillingAuthorizationReservation row : directRows)
        {
            reservationCounts.merge(row.getRuntimeStepId(), 1, Integer::sum);
            if (row.getMediaTaskId() != null) taskCounts.merge(row.getMediaTaskId(), 1, Integer::sum);
            if (row.getBillingTraceId() != null) traceCounts.merge(row.getBillingTraceId(), 1, Integer::sum);
        }
        int pending = 0;
        int unknown = 0;
        int verified = 0;
        BigDecimal textAmount = BigDecimal.ZERO;
        BigDecimal mediaAmount = BigDecimal.ZERO;
        for (AidRuntimeRunStep step : stepRows)
            if ((step.getMediaTaskId() != null || step.getReservationId() != null)
                    && !reservationCounts.containsKey(step.getId())) unknown++;
        for (AidBillingAuthorizationReservation row : directRows)
        {
            AidRuntimeRunStep step = byStep.get(row.getRuntimeStepId());
            if (!matchesStep(runId, step, row) || duplicateSteps.contains(row.getRuntimeStepId())
                    || reservationCounts.get(row.getRuntimeStepId()) != 1
                    || row.getMediaTaskId() != null && taskCounts.get(row.getMediaTaskId()) != 1
                    || row.getBillingTraceId() != null && traceCounts.get(row.getBillingTraceId()) != 1)
            { unknown++; continue; }
            AidMediaTask task = row.getMediaTaskId() == null ? null : taskRows.get(row.getMediaTaskId());
            if (!Set.of("SETTLED", "REFUNDED", "RELEASED").contains(StrUtil.nullToEmpty(row.getState())))
            {
                if (PENDING_RESERVATIONS.contains(StrUtil.nullToEmpty(row.getState()))
                        && (row.getMediaTaskId() == null || matchesTask(actorId, step, row, task))) pending++;
                else unknown++;
                continue;
            }
            BigDecimal net = verifiedNet(actorId, step, row, task,
                    row.getBillingTraceId() == null ? null : ledger.get(row.getBillingTraceId()));
            if (net == null) { unknown++; continue; }
            verified++;
            if ("TEXT".equals(task.getMediaType())) textAmount = textAmount.add(net);
            else mediaAmount = mediaAmount.add(net);
        }
        result.put("evidenceState", unknown > 0 ? "PARTIAL_UNKNOWN" : "VERIFIED_OBSERVED_CALLS");
        result.put("verifiedSettledCallCount", verified);
        result.put("pendingCallCount", pending);
        result.put("unknownCallCount", unknown);
        result.put("excludedChildRunCount", excludedChildRuns);
        result.put("excludedChildReservationCount", reservationRows.size() - directRows.size());
        result.put("childCostsIncluded", false);
        result.put("observedDirectCallsComplete", unknown == 0);
        if (verified > 0)
        {
            result.put("verifiedSettledSubtotal", textAmount.add(mediaAmount).toPlainString());
            result.put("verifiedTextSubtotal", textAmount.toPlainString());
            result.put("verifiedMediaSubtotal", mediaAmount.toPlainString());
        }
        return result;
    }

    private static boolean matchesStep(Long runId, AidRuntimeRunStep step,
                                       AidBillingAuthorizationReservation row)
    {
        // The step stores the approved request digest; the media gateway request hash has a different contract.
        return runId != null && step != null && step.getId() != null && row.getId() != null
                && step.getApprovalId() != null && step.getAttemptNo() != null
                && StrUtil.isNotBlank(step.getStableStepId()) && StrUtil.isNotBlank(step.getOperationKind())
                && StrUtil.isNotBlank(step.getInputDigest())
                && Objects.equals(step.getInputDigest(), row.getRequestDigest())
                && Objects.equals(runId, step.getRunId())
                && Objects.equals(runId, row.getRuntimeRunId()) && "0".equals(step.getDelFlag())
                && "0".equals(row.getDelFlag()) && Objects.equals(step.getReservationId(), row.getId())
                && Objects.equals(step.getMediaTaskId(), row.getMediaTaskId())
                && Objects.equals(step.getApprovalId(), row.getApprovalId())
                && Objects.equals(step.getStableStepId(), row.getStableStepKey())
                && Objects.equals(step.getAttemptNo(), row.getAttemptNo())
                && Objects.equals(step.getOperationKind(), row.getOperationKind())
                && StrUtil.isNotBlank(step.getIdempotencyKey())
                && Objects.equals(step.getIdempotencyKey(), row.getLogicalCallKey());
    }

    private static BigDecimal verifiedNet(Long actorId, AidRuntimeRunStep step,
                                          AidBillingAuthorizationReservation row, AidMediaTask task,
                                          IAccountUpdateService.WalletLedgerEvidence ledger)
    {
        if (!matchesTask(actorId, step, row, task)
                || !TERMINAL_TASKS.contains(StrUtil.nullToEmpty(task.getStatus()))) return null;
        BigDecimal reserved = row.getReservedAmount();
        BigDecimal frozen = row.getWalletFrozenAmount();
        BigDecimal settled = row.getSettledAmount();
        BigDecimal released = row.getReleasedAmount();
        BigDecimal refunded = row.getWalletRefundedAmount();
        if (!nonnegative(reserved) || !nonnegative(frozen) || !nonnegative(settled)
                || !nonnegative(released) || !nonnegative(refunded)
                || reserved.compareTo(settled.add(released)) != 0
                || frozen.compareTo(settled.add(refunded)) != 0) return null;
        boolean paid = "SETTLED".equals(row.getState());
        if (paid ? !"SUCCESS".equals(task.getBillingStatus())
                || !Objects.equals("settle:" + task.getId(), row.getSettlementKey())
                || !nonnegative(task.getActualCost()) || settled.compareTo(task.getActualCost()) != 0
                : !"REFUNDED".equals(row.getState()) || !"FAILED".equals(task.getBillingStatus())
                || !Objects.equals("refund:" + task.getId(), row.getSettlementKey())
                || settled.signum() != 0 || task.getActualCost() != null && task.getActualCost().signum() != 0)
            return null;
        if (ledger == null) return null;
        if (frozen.signum() == 0) return ledger.empty() && settled.signum() == 0 ? settled : null;
        if (!ledger.valid() || !Objects.equals(actorId, ledger.userId())
                || !Objects.equals(task.getBillingTraceId(), ledger.traceId())
                || !same(frozen, ledger.frozenAmount()) || !same(settled, ledger.netConsumedAmount())) return null;
        if (paid ? !same(frozen, ledger.grossConsumedAmount())
                || !same(refunded, ledger.settleRefundedAmount())
                || !same(BigDecimal.ZERO, ledger.unfrozenAmount())
                || !same(BigDecimal.ZERO, ledger.settleUnfrozenAmount())
                : !same(frozen, ledger.unfrozenAmount()) || !same(BigDecimal.ZERO, ledger.grossConsumedAmount())
                || !same(BigDecimal.ZERO, ledger.settleRefundedAmount())
                || !same(BigDecimal.ZERO, ledger.settleUnfrozenAmount())) return null;
        return settled;
    }

    private static boolean matchesTask(Long actorId, AidRuntimeRunStep step,
                                       AidBillingAuthorizationReservation row, AidMediaTask task)
    {
        return actorId != null && task != null && Objects.equals(actorId, task.getUserId())
                && Objects.equals(task.getId(), row.getMediaTaskId())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && "ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                && Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO").contains(StrUtil.nullToEmpty(task.getMediaType()))
                && Objects.equals(task.getIdempotencyKey(), row.getLogicalCallKey())
                && StrUtil.isNotBlank(row.getRequestDigest())
                && StrUtil.isNotBlank(task.getBillingTraceId())
                && Objects.equals(task.getBillingTraceId(), row.getBillingTraceId());
    }

    private static JSONObject base(String startedAt, String asOf)
    {
        JSONObject result = new JSONObject();
        result.put("observationKind", KIND);
        result.put("authority", AUTHORITY);
        result.put("scope", "DIRECT_CURRENT_RUN_CALLS_ONLY");
        result.put("unit", "ACCOUNT_CREDITS");
        result.put("readStartedAt", startedAt);
        result.put("asOf", asOf);
        result.put("readConsistency", "NON_ATOMIC_READ_WINDOW");
        result.put("isFinalRunCost", false);
        result.put("coversAllRunCosts", false);
        result.put("currentOrNextMainMayAddCost", true);
        result.put("instruction", "These are server-verified settlement facts for direct calls of this run only, not a quote or final total. Child-run costs are excluded. A non-billable READ describes that tool's direct charge only; the assistant's text model and media calls can still cost credits. Missing, pending, incomplete or unknown evidence never means zero. The current or next MAIN call is not included before settlement and may add cost. Use the user's language when explaining these facts; do not expose internal identifiers or account details.");
        return result;
    }

    private static JSONObject unavailable(String startedAt)
    {
        JSONObject result = base(startedAt, DateUtil.now());
        result.put("scanComplete", false);
        result.put("coversAllRunCosts", false);
        result.put("evidenceState", "UNAVAILABLE");
        return result;
    }

    private static boolean nonnegative(BigDecimal value) { return value != null && value.signum() >= 0; }
    private static boolean same(BigDecimal left, BigDecimal right)
    { return left != null && right != null && left.compareTo(right) == 0; }
}
