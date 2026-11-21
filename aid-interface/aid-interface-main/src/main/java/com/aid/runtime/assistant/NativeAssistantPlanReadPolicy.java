package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.assistant.execution.AssistantPlanDecisionRejectedException;
import com.aid.runtime.assistant.execution.AssistantPlanDecisionRejectedException.Reason;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces current-plan prerequisites without inheriting completed discovery reads. */
public final class NativeAssistantPlanReadPolicy
{
    private static final String SKILL_READ = NativeSkillAuthoringContract.READ_RESOURCE;
    private static final String SKILL_PROPOSE_DRAFT = NativeSkillAuthoringContract.PROPOSE_DRAFT;
    private static final int MAX_PLAN_STEPS = 100;

    private NativeAssistantPlanReadPolicy() { }

    /** The same policy owns model guidance and validation; discovery is not a draft prerequisite. */
    public static String plannerInstruction()
    {
        return "一般的方法文件、模型目录或项目状态探索直接调用只读发现工具，不另提仅含探索读取的执行计划。"
                + NativeSkillAuthoringContract.plannerInstruction();
    }

    public static String contractDigest()
    {
        return SecureUtil.sha256("native-plan-read-prerequisites/v2\n" + plannerInstruction());
    }

    /** Pure proposed-draft validation, safe to repeat on the exact locked Native receipt. */
    public static void requireDraftPlanPrerequisites(JSONObject next)
    {
        if ((containsToolBinding(next, SKILL_PROPOSE_DRAFT) || containsToolBinding(next, NativeSkillAuthoringContract.PROPOSE_DRAFT_V2)))
            requireReads(next, Set.of(SKILL_READ));
    }

    public static void requireNextPlan(String runRequestJson, JSONObject next,
                                       JSONObject previous, List<AidRuntimeRunStep> receipts)
    {
        requireNextPlan(runRequestJson, next, previous, receipts, Set.of(), false);
    }

    public static void requireNextPlan(String runRequestJson, JSONObject next,
                                       JSONObject previous, List<AidRuntimeRunStep> receipts,
                                       Set<String> reusablePackageReadCodes,
                                       boolean selectedSkillMasterReady)
    {
        Set<String> requiredCodes = new LinkedHashSet<>();
        // Earlier directory/state reads are observations, not new execution-plan dependencies.
        // Only the caller's verified current-context selected master proof can satisfy this prerequisite.
        if (requiresSelectedSkillMaster(selectedSkill(runRequestJson)) && !selectedSkillMasterReady
                && !NativeSkillResultReusePolicy.isReuseOnlyPlan(next))
            requiredCodes.add(SKILL_READ);
        // Skill authoring deliberately requires fresh, explicit current-plan read dependencies.
        if ((containsToolBinding(next, SKILL_PROPOSE_DRAFT) || containsToolBinding(next, NativeSkillAuthoringContract.PROPOSE_DRAFT_V2))) requiredCodes.add(SKILL_READ);
        requireReads(next, requiredCodes);
    }

    private static void requireReads(JSONObject next, Set<String> requiredCodes)
    {
        if (requiredCodes.isEmpty()) return;
        Map<String, Planned> byId = plannedSteps(next);
        Map<String, Set<String>> readIdsByCode = new LinkedHashMap<>();
        for (Planned step : byId.values())
            if ("TOOL".equals(step.executorKind()) && requiredCodes.contains(step.bindingCode())
                    && NativeAssistantDiscoveryPlanService.isSafeRead(step.bindingCode(), next))
                readIdsByCode.computeIfAbsent(step.bindingCode(), ignored -> new LinkedHashSet<>()).add(step.stableStepId());
        require(readIdsByCode.keySet().containsAll(requiredCodes), Reason.NATIVE_PLAN_SAFE_READ_REQUIRED);
        boolean draft = (containsToolBinding(next, SKILL_PROPOSE_DRAFT) || containsToolBinding(next, NativeSkillAuthoringContract.PROPOSE_DRAFT_V2));
        if (draft) require(readIdsByCode.get(SKILL_READ).size() >= 2, Reason.NATIVE_PLAN_SAFE_READ_REQUIRED);
        Map<String, Set<String>> dependencyClosures = new HashMap<>();
        for (Planned planned : byId.values())
        {
            if ("TOOL".equals(planned.executorKind())
                    && NativeAssistantDiscoveryPlanService.isSafeRead(planned.bindingCode(), next)) continue;
            Set<String> dependencies = dependencyClosure(planned, byId, new HashSet<>(), dependencyClosures);
            for (String code : requiredCodes)
                require(readIdsByCode.get(code).stream().anyMatch(dependencies::contains),
                        Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_REQUIRED);
            if (draft)
            {
                List<String> reads = readIdsByCode.get(SKILL_READ).stream().filter(dependencies::contains).toList();
                boolean orderedPair = false;
                for (String later : reads)
                    for (String earlier : reads)
                        if (!later.equals(earlier) && dependencyClosure(byId.get(later), byId,
                                new HashSet<>(), dependencyClosures).contains(earlier)) orderedPair = true;
                require(orderedPair, Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_REQUIRED);
            }
        }
    }

    /** Uses only declared approved-plan ancestors; no earlier plan or caller-provided expansion. */
    public static Set<String> ancestorStepIds(JSONObject plan, String stableStepId)
    {
        Map<String, Planned> byId = plannedSteps(plan);
        Planned step = byId.get(stableStepId);
        require(step != null, Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_INVALID);
        return dependencyClosure(step, byId, new HashSet<>(), new HashMap<>());
    }

    private static Map<String, Planned> plannedSteps(JSONObject next)
    {
        JSONArray rawSteps = next == null ? null : next.getJSONArray("steps");
        require(rawSteps != null && !rawSteps.isEmpty() && rawSteps.size() <= MAX_PLAN_STEPS,
                Reason.NATIVE_PLAN_SAFE_READ_REQUIRED);
        Map<String, Planned> byId = new LinkedHashMap<>();
        for (int index = 0; index < rawSteps.size(); index++)
        {
            JSONObject raw = rawSteps.getJSONObject(index);
            String stable = raw == null ? null : raw.getString("stableStepId");
            String code = raw == null ? null : raw.getString("bindingCode");
            String executorKind = raw == null ? null : raw.getString("executorKind");
            JSONArray dependencies = raw == null ? null : raw.getJSONArray("dependsOn");
            require(raw != null && ("TOOL".equals(executorKind) || "SKILL".equals(executorKind))
                            && StrUtil.isNotBlank(stable) && stable.length() <= 64
                            && StrUtil.isNotBlank(code) && !byId.containsKey(stable),
                    Reason.NATIVE_PLAN_SAFE_READ_REQUIRED);
            Set<String> dependsOn = new LinkedHashSet<>();
            if (dependencies != null)
            {
                for (int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++)
                {
                    String dependency = dependencies.getString(dependencyIndex);
                    require(StrUtil.isNotBlank(dependency) && dependsOn.add(dependency),
                            Reason.NATIVE_PLAN_SAFE_READ_REQUIRED);
                }
            }
            Planned planned = new Planned(stable, executorKind, code, Set.copyOf(dependsOn));
            byId.put(stable, planned);
        }
        return byId;
    }

    private static boolean containsToolBinding(JSONObject plan, String toolCode)
    {
        JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
        if (steps == null) return false;
        for (int index = 0; index < steps.size(); index++)
        {
            JSONObject step = steps.getJSONObject(index);
            if (step != null && "TOOL".equals(step.getString("executorKind"))
                    && Objects.equals(toolCode, step.getString("bindingCode"))) return true;
        }
        return false;
    }

    static JSONObject selectedSkill(String runRequestJson)
    {
        JSONObject request;
        try { request = JSON.parseObject(runRequestJson); }
        catch (RuntimeException invalid) { throw new ServiceException("NATIVE_SELECTED_SKILL_INVALID"); }
        return request == null ? null : request.getJSONObject("_selectedSkill");
    }

    static boolean requiresSelectedSkillMaster(JSONObject selected)
    {
        return selected != null && "TEXT".equals(selected.getString("outputKind"))
                && "SKILL".equals(selected.getString("executionRoute"));
    }

    private static Set<String> dependencyClosure(Planned step, Map<String, Planned> byId,
                                                  Set<String> visiting,
                                                  Map<String, Set<String>> completed)
    {
        Set<String> cached = completed.get(step.stableStepId());
        if (cached != null) return cached;
        require(visiting.add(step.stableStepId()), Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_INVALID);
        Set<String> result = new LinkedHashSet<>();
        for (String dependencyId : step.dependsOn())
        {
            Planned dependency = byId.get(dependencyId);
            require(dependency != null, Reason.NATIVE_PLAN_SAFE_READ_DEPENDENCY_INVALID);
            result.add(dependencyId);
            result.addAll(dependencyClosure(dependency, byId, visiting, completed));
        }
        visiting.remove(step.stableStepId());
        Set<String> closure = Set.copyOf(result);
        completed.put(step.stableStepId(), closure);
        return closure;
    }

    private static void require(boolean condition, Reason reason)
    {
        if (!condition) throw new AssistantPlanDecisionRejectedException(reason, null);
    }

    private record Planned(String stableStepId, String executorKind,
                           String bindingCode, Set<String> dependsOn) { }
}
