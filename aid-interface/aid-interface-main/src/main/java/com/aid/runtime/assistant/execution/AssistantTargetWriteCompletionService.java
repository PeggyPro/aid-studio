package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Checks current-plan completion and exact own receipts before accepting technical target evidence. */
@Service
@RequiredArgsConstructor
public class AssistantTargetWriteCompletionService {
    private final AidRuntimeRunStepMapper steps;
    private final AssistantCompletedToolScopeVerifier completedTools;
    private final com.aid.runtime.mapper.AidRuntimeApprovalMapper approvals;
    private final AssistantPlanExecutionScopeCodec scopeCodec;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<AssistantTargetWriteCompletionVerifier> verifiers = List.of();

    /** Technical completion copy comes from validated current-plan receipts, never model narration.
     * Call only after the host has validated every declared completion criterion and target write. */
    public static String renderVerifiedDelivery(JSONArray criteria, Set<String> verifiedTargetWrites,
                                                String fallback) {
        if (criteria == null || criteria.isEmpty()) return fallback;
        for (Object raw : criteria) {
            if (!(raw instanceof JSONObject criterion)
                    || !"TARGET_WRITE".equals(criterion.getString("criterionKind"))) return fallback;
        }
        if (verifiedTargetWrites == null || verifiedTargetWrites.isEmpty()) invalid();
        Set<String> used = new HashSet<>();
        for (Object raw : criteria) {
            JSONObject criterion = (JSONObject) raw;
            if (!Boolean.TRUE.equals(criterion.get("passed"))
                    || !(criterion.get("evidenceRefs") instanceof JSONArray refs) || refs.isEmpty()) invalid();
            Set<String> own = new HashSet<>();
            for (Object ref : criterion.getJSONArray("evidenceRefs")) {
                if (!(ref instanceof String value) || !value.matches("runtime-step:[1-9][0-9]{0,18}")
                        || !verifiedTargetWrites.contains(value) || !own.add(value)) invalid();
                used.add((String) ref);
            }
        }
        if (!used.equals(verifiedTargetWrites)) invalid();
        return "已完成本轮确认的 " + used.size() + " 项保存操作。可查看对应的已保存结果。";
    }

    public Set<String> supportedToolCodes() {
        Set<String> codes = new HashSet<>();
        for (AssistantTargetWriteCompletionVerifier verifier : verifiers)
            codes.addAll(verifier.supportedToolCodes());
        return Set.copyOf(codes);
    }

    public Set<String> requireDeclaredEvidence(AidRuntimeRun run, AidRuntimePlan plan, JSONArray refs) {
        if (refs == null || refs.isEmpty()) return Set.of();
        if (refs.size() > 100) invalid();
        Map<Long, AidRuntimeRunStep> eligible = completedPlanSteps(run, plan);
        Set<String> declared = new HashSet<>();
        Map<AssistantTargetWriteCompletionVerifier,List<VerifiedRuntimeToolEffect>> grouped = new HashMap<>();
        for (int index = 0; index < refs.size(); index++) {
            String ref = refs.getString(index);
            if (ref == null || !ref.matches("runtime-step:[1-9][0-9]{0,18}") || !declared.add(ref)) invalid();
            Long stepId;
            try { stepId = Long.valueOf(ref.substring("runtime-step:".length())); }
            catch (NumberFormatException invalid) { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
            AidRuntimeRunStep step = eligible.get(stepId);
            if (step == null) invalid();
            VerifiedRuntimeToolEffect proof = completedTools.require(run.getUserId(), run, step);
            List<AssistantTargetWriteCompletionVerifier> matches = verifiers.stream()
                    .filter(verifier -> verifier.supportedToolCodes().contains(proof.toolCode())).toList();
            if (matches.size() != 1) invalid();
            grouped.computeIfAbsent(matches.get(0),key->new java.util.ArrayList<>()).add(proof);
        }
        grouped.forEach((verifier,proofs)->verifier.requireCompletedWrites(List.copyOf(proofs)));
        return Set.copyOf(declared);
    }

    /** Applies to every COMPLETE decision, including media-only runs with no target-write refs. */
    public void requireCompletedCurrentPlan(AidRuntimeRun run, AidRuntimePlan plan) {
        completedPlanSteps(run, plan);
    }

    Map<Long, AidRuntimeRunStep> completedPlanSteps(AidRuntimeRun run, AidRuntimePlan plan) {
        if (run == null || plan == null
                || !Objects.equals(plan.getRunId(), run.getId())
                || !Objects.equals(plan.getRevision(), run.getPlanRevision())
                || !Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                || !Objects.equals(plan.getInputContextDigest(), run.getContextDigest())
                || !"0".equals(plan.getDelFlag())
                || !"APPROVED".equals(plan.getStatus())
                || !Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson()))) invalid();
        JSONObject definition = JSON.parseObject(plan.getDefinitionJson());
        JSONArray plannedSteps = definition == null ? null : definition.getJSONArray("steps");
        if (plannedSteps == null || plannedSteps.isEmpty() || plannedSteps.size() > 100) invalid();
        Map<String, Integer> positions = new HashMap<>();
        Map<String, JSONObject> plannedDefinitions = new HashMap<>();
        for (int index = 0; index < plannedSteps.size(); index++) {
            JSONObject item = plannedSteps.getJSONObject(index);
            String stable = item == null ? null : item.getString("stableStepId");
            Integer position = item == null ? null : item.getInteger("position");
            if (stable == null || position == null || position < 1
                    || positions.containsValue(position) || positions.putIfAbsent(stable, position) != null) invalid();
            plannedDefinitions.put(stable, item);
        }
        List<AidRuntimeRunStep> attempts = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getPlanRevision, plan.getRevision())
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .in(AidRuntimeRunStep::getStableStepId, positions.keySet())
                .ne(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .orderByDesc(AidRuntimeRunStep::getAttemptNo).orderByDesc(AidRuntimeRunStep::getId));
        Map<String, AidRuntimeRunStep> latest = new HashMap<>();
        for (AidRuntimeRunStep attempt : attempts) latest.putIfAbsent(attempt.getStableStepId(), attempt);
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            AidRuntimeRunStep step = latest.get(entry.getKey());
            if (step == null || !"SUCCEEDED".equals(step.getStatus())
                    || !Objects.equals(step.getPlanPosition(), entry.getValue()))
                throw new ServiceException("COMPLETION_PLAN_STEPS_INCOMPLETE");
            var approval = step.getApprovalId() == null ? null : approvals.selectById(step.getApprovalId());
            if (approval == null || !"0".equals(approval.getDelFlag())
                    || !Objects.equals(approval.getRunId(), run.getId())
                    || !Objects.equals(approval.getUserId(), run.getUserId())
                    || !Objects.equals(approval.getPlanRevision(), plan.getRevision())
                    || !Objects.equals(approval.getPlanDigest(), plan.getDefinitionDigest())
                    || !Objects.equals(approval.getContextDigest(), run.getContextDigest())
                    || !"PLAN_EXECUTION".equals(approval.getScopeKind())
                    || !("ACCEPTED".equals(approval.getStatus()) || "CONSUMED".equals(approval.getStatus()))) invalid();
            var authority = scopeCodec.decode(approval.getScopeJson());
            JSONObject plannedDefinition = plannedDefinitions.get(entry.getKey());
            if (!Objects.equals(authority.digest(), approval.getScopeDigest())
                    || !Objects.equals(authority.requestDigest(), approval.getRequestDigest())
                    || !Objects.equals(authority.plan().getString("definitionDigest"), plan.getDefinitionDigest())
                    || !Objects.equals(authority.plan().getJSONObject("planStep"), plannedDefinition)
                    || !Objects.equals(authority.plan().getString("stableStepId"), step.getStableStepId())
                    || !Objects.equals(authority.plan().getInteger("position"), step.getPlanPosition())
                    || !Objects.equals(authority.decision().getLong("observedPlanRevision"), plan.getRevision())
                    || !Objects.equals(authority.context().getString("digest"), run.getContextDigest())
                    || !(authority.isTool() ? "TOOL".equals(step.getStepKind())
                        && Objects.equals(authority.plan().getString("bindingCode"), step.getToolCode())
                        : "SKILL".equals(step.getStepKind())
                        && Objects.equals(authority.skill().getLong("skillVersionId"), step.getSkillVersionId()))) invalid();
        }
        Map<Long, AidRuntimeRunStep> eligible = new HashMap<>();
        latest.values().forEach(step -> eligible.put(step.getId(), step));
        return Map.copyOf(eligible);
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
