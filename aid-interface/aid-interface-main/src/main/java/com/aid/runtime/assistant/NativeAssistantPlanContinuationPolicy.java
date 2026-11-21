package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies that a native plan has durably finished before it can be superseded by a
 * newly proposed stage. The new stage still uses the normal user approval boundary.
 */
public final class NativeAssistantPlanContinuationPolicy
{
    private static final int MAX_PLAN_STEPS = 100;
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "FAILED_PRETASK", "CANCELLED_PRETASK");

    private NativeAssistantPlanContinuationPolicy() { }

    public static boolean isCompletedContext(JSONObject definition, Map<String, JSONObject> prior)
    {
        List<PlannedStep> expected = plannedSteps(definition);
        if (prior == null) return false;
        for (PlannedStep step : expected)
        {
            JSONObject receipt = prior.get(step.stableStepId());
            if (receipt == null || !"SUCCEEDED".equals(receipt.getString("status"))) return false;
            if ("TOOL".equals(step.executorKind()))
            {
                if (!"TOOL".equals(receipt.getString("executorKind"))
                        || !Objects.equals(step.bindingCode(), receipt.getString("toolCode"))) return false;
            }
            else if (!"SKILL".equals(receipt.getString("executorKind"))
                    || !Objects.equals(step.bindingVersion(), receipt.getString("skillVersionId"))) return false;
        }
        return true;
    }

    public static boolean isDurablyCompleted(JSONObject definition, List<AidRuntimeRunStep> rows)
    {
        return durableOutcome(definition, rows, false);
    }

    /** A failed stage may be replanned, but is never completion evidence. */
    public static boolean isRecoverableContext(JSONObject definition, Map<String, JSONObject> prior)
    {
        List<PlannedStep> expected = plannedSteps(definition);
        if (prior == null || prior.isEmpty()) return false;
        Set<String> identities = new HashSet<>();
        boolean failed = false;
        for (PlannedStep step : expected)
        {
            identities.add(step.stableStepId());
            JSONObject receipt = prior.get(step.stableStepId());
            if (receipt == null) continue; // Unstarted dependants have no side effects to reconcile.
            String status = receipt.getString("status");
            int attempt = receipt.getIntValue("attemptNo");
            int maximum = "TOOL".equals(step.executorKind())
                    && NativeAssistantDiscoveryPlanService.isSafeRead(step.bindingCode(), definition)
                    ? 1_000 : step.maxAttempts();
            boolean identity = "TOOL".equals(step.executorKind())
                    ? "TOOL".equals(receipt.getString("executorKind"))
                    && Objects.equals(step.bindingCode(), receipt.getString("toolCode"))
                    : "SKILL".equals(receipt.getString("executorKind"))
                    && Objects.equals(step.bindingVersion(), receipt.getString("skillVersionId"));
            if (!identity || attempt < 1 || attempt > maximum
                    || status == null || !TERMINAL.contains(status)) return false;
            failed |= !"SUCCEEDED".equals(status);
        }
        return failed && identities.containsAll(prior.keySet());
    }

    public static boolean isDurablyRecoverable(JSONObject definition, List<AidRuntimeRunStep> rows)
    {
        return durableOutcome(definition, rows, true);
    }

    private static boolean durableOutcome(JSONObject definition, List<AidRuntimeRunStep> rows,
                                          boolean recovery)
    {
        List<PlannedStep> expected = plannedSteps(definition);
        Map<String, PlannedStep> byId = new HashMap<>();
        expected.forEach(step -> byId.put(step.stableStepId(), step));
        Map<String, AidRuntimeRunStep> latest = new HashMap<>();
        if (rows != null)
        {
            for (AidRuntimeRunStep row : rows)
            {
                if (row == null || !"0".equals(row.getDelFlag()) || "MAIN_AGENT".equals(row.getStepKind()))
                    continue;
                PlannedStep planned = byId.get(row.getStableStepId());
                int allowedAttempts = planned != null && "TOOL".equals(planned.executorKind())
                        && NativeAssistantDiscoveryPlanService.isSafeRead(planned.bindingCode(), definition)
                        ? 1_000 : planned == null ? 0 : planned.maxAttempts();
                boolean exactIdentity = planned != null && ("TOOL".equals(planned.executorKind())
                        ? "TOOL".equals(row.getStepKind())
                        && Objects.equals(planned.bindingCode(), row.getToolCode())
                        : "SKILL".equals(row.getStepKind())
                        && "ASSISTANT_SKILL".equals(row.getOperationKind())
                        && row.getToolCode() == null
                        && Objects.equals(positiveId(planned.bindingVersion()), row.getSkillVersionId()));
                require(exactIdentity && row.getAttemptNo() != null && row.getAttemptNo() > 0
                                && row.getAttemptNo() <= allowedAttempts,
                        "NATIVE_PLAN_CONTINUATION_RECEIPT_INVALID");
                if (!TERMINAL.contains(row.getStatus())) return false;
                AidRuntimeRunStep previous = latest.get(row.getStableStepId());
                if (previous == null || row.getAttemptNo() > previous.getAttemptNo()
                        || Objects.equals(row.getAttemptNo(), previous.getAttemptNo())
                        && value(row.getId()) > value(previous.getId()))
                    latest.put(row.getStableStepId(), row);
            }
        }
        boolean failed = false;
        for (PlannedStep planned : expected)
        {
            AidRuntimeRunStep receipt = latest.get(planned.stableStepId());
            if (recovery)
            {
                failed |= receipt != null && !"SUCCEEDED".equals(receipt.getStatus());
            }
            else if (receipt == null || !"SUCCEEDED".equals(receipt.getStatus())) return false;
        }
        return !recovery || failed;
    }

    private static List<PlannedStep> plannedSteps(JSONObject definition)
    {
        JSONArray rows = definition == null ? null : definition.getJSONArray("steps");
        require(rows != null && !rows.isEmpty() && rows.size() <= MAX_PLAN_STEPS,
                "NATIVE_PLAN_CONTINUATION_DEFINITION_INVALID");
        java.util.ArrayList<PlannedStep> result = new java.util.ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < rows.size(); index++)
        {
            JSONObject row = rows.getJSONObject(index);
            String stableStepId = row == null ? null : row.getString("stableStepId");
            String executorKind = row == null ? null : row.getString("executorKind");
            String bindingCode = row == null ? null : row.getString("bindingCode");
            String bindingVersion = row == null ? null : row.getString("bindingVersion");
            int maximum = row == null ? 0 : row.getIntValue("maxAttempts");
            require(row != null && ("TOOL".equals(executorKind) || "SKILL".equals(executorKind))
                            && StrUtil.isNotBlank(stableStepId) && stableStepId.length() <= 64
                            && identities.add(stableStepId) && StrUtil.isNotBlank(bindingCode)
                            && StrUtil.isNotBlank(bindingVersion)
                            && (!"SKILL".equals(executorKind) || positiveId(bindingVersion) != null)
                            && maximum > 0 && maximum <= 10,
                    "NATIVE_PLAN_CONTINUATION_DEFINITION_INVALID");
            result.add(new PlannedStep(stableStepId, executorKind, bindingCode, bindingVersion, maximum));
        }
        return List.copyOf(result);
    }

    private static long value(Long value) { return value == null ? 0L : value; }

    private static Long positiveId(String value)
    {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    private record PlannedStep(String stableStepId, String executorKind,
                               String bindingCode, String bindingVersion, int maxAttempts) { }
}
