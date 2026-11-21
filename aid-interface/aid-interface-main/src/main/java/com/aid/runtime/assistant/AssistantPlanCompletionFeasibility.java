package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;
import java.util.function.Function;

/** Pure preflight shared by decision parsing and authoritative plan admission. */
public final class AssistantPlanCompletionFeasibility {
    private AssistantPlanCompletionFeasibility() { }

    public static boolean declaresBillableOutputs(JSONObject plan, java.util.function.Predicate<String> billable) {
        JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
        if (steps == null || steps.isEmpty()) return false;
        for (Object item : steps) {
            if (!(item instanceof JSONObject step)) return false;
            if (!"TOOL".equals(step.getString("executorKind"))) continue;
            if (!billable.test(step.getString("bindingCode"))) continue;
            Object value = step.get("outputLogicalArtifactIds");
            if (!(value instanceof JSONArray ids) || ids.size() != 1
                    || !(ids.get(0) instanceof String id)
                    || !id.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")) return false;
        }
        return true;
    }

    public static boolean declaresArtifactOutputs(JSONObject plan, Function<String,RuntimeToolProvider.OutputReferenceKind> outputs) {
        return declaresBillableOutputs(plan,code -> outputs.apply(code)==RuntimeToolProvider.OutputReferenceKind.RUNTIME_ARTIFACT);
    }

    public static boolean canProduceEvidence(JSONObject plan,
            Function<String, RuntimeToolProvider.OutputReferenceKind> outputKind,
            Set<String> targetWriteTools) {
        JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
        JSONArray criteria = plan == null ? null : plan.getJSONArray("completionCriteria");
        if (steps == null || steps.isEmpty() || criteria == null || criteria.isEmpty()) return false;
        boolean method = false, artifact = false, write = false;
        for (Object item : steps) {
            if (!(item instanceof JSONObject step)) return false;
            String code = step.getString("bindingCode");
            if (StrUtil.isBlank(code)) return false;
            if ("SKILL".equals(step.getString("executorKind"))) {
                String version = step.getString("bindingVersion");
                if (version == null || !version.matches("^[1-9][0-9]{0,18}$")) return false;
                method = true;
            } else if ("TOOL".equals(step.getString("executorKind"))) {
                var kind = outputKind.apply(code);
                // Unknown legacy output is not proof of impossibility; completion still validates it.
                artifact |= kind == RuntimeToolProvider.OutputReferenceKind.RUNTIME_ARTIFACT
                        || kind == RuntimeToolProvider.OutputReferenceKind.UNKNOWN;
                write |= targetWriteTools.contains(code);
            } else return false;
        }
        for (Object item : criteria) {
            if (!(item instanceof JSONObject criterion)) return false;
            String kind = criterion.getString("criterionKind");
            boolean possible = "METHOD_RESULT".equals(kind) ? method
                    : "TARGET_WRITE".equals(kind) ? write : artifact;
            if (!possible) return false;
        }
        return true;
    }
}
