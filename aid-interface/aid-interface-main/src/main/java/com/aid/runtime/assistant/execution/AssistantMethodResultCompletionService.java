package com.aid.runtime.assistant.execution;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Evidence of an actual method result, never evidence of a target write or creative review. */
@Service
@RequiredArgsConstructor
public class AssistantMethodResultCompletionService {
    public static final String CRITERION_KIND = "METHOD_RESULT";
    private final AssistantTargetWriteCompletionService completedPlans;
    private final AssistantSkillResultTextResolver results;

    /** A method-only delivery is the verified source, not a second model transcription. */
    public String renderMethodDelivery(AidRuntimeRun run, JSONArray criteria, String fallback) {
        if (criteria == null || criteria.isEmpty()) return fallback;
        Set<String> references = new LinkedHashSet<>();
        for (Object raw : criteria) {
            if (!(raw instanceof JSONObject criterion)
                    || !CRITERION_KIND.equals(criterion.getString("criterionKind"))) return fallback;
            if (!(criterion.get("evidenceRefs") instanceof JSONArray refs) || refs.isEmpty()) throw invalid();
            for (Object item : refs) {
                if (!(item instanceof String ref) || !ref.matches("runtime-step:[1-9][0-9]{0,18}"))
                    throw invalid();
                references.add(ref);
            }
        }
        if (references.size() > 100) throw invalid();
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(run.getId());
        invocation.setUserId(run.getUserId());
        invocation.setPlanRevision(run.getPlanRevision());
        StringBuilder delivery = new StringBuilder();
        for (String ref : references) {
            long id;
            try { id = Long.parseLong(ref.substring("runtime-step:".length())); }
            catch (NumberFormatException failure) { throw invalid(); }
            var source = results.resolveResult(invocation, id);
            if (!Objects.equals(source.sourceRunId(), run.getId()) || !hasResult(source.result()))
                throw invalid();
            String text = source.result() instanceof String value ? value
                    : com.alibaba.fastjson2.JSON.toJSONString(source.result(),
                        com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
            if (!delivery.isEmpty()) delivery.append("\n\n---\n\n");
            if (references.size() > 1) delivery.append("## Skill 原始结果 · ").append(ref).append("\n\n");
            delivery.append(text);
            if (delivery.length() > 100000) throw invalid();
        }
        return delivery.toString();
    }

    public Set<String> requireDeclaredEvidence(AidRuntimeRun run, AidRuntimePlan plan, JSONArray criteria) {
        Set<String> references = new HashSet<>();
        if (criteria == null) return Set.of();
        for (Object raw : criteria) {
            if (!(raw instanceof JSONObject criterion)
                    || !CRITERION_KIND.equals(criterion.getString("criterionKind"))) continue;
            if (!(criterion.get("evidenceRefs") instanceof JSONArray refs) || refs.isEmpty()
                    || refs.size() > 100) throw invalid();
            Set<String> own = new HashSet<>();
            for (Object item : refs) {
                if (!(item instanceof String ref) || !ref.matches("runtime-step:[1-9][0-9]{0,18}")
                        || !own.add(ref)) throw invalid();
                references.add(ref);
            }
        }
        if (references.isEmpty()) return Set.of();
        if (references.size() > 100) throw invalid();
        // This proves latest attempts, immutable approved steps and current-plan completion.
        var eligible = completedPlans.completedPlanSteps(run, plan);
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(run.getId());
        invocation.setUserId(run.getUserId());
        invocation.setPlanRevision(plan.getRevision());
        for (String ref : references) {
            long id;
            try { id = Long.parseLong(ref.substring("runtime-step:".length())); }
            catch (NumberFormatException failure) { throw invalid(); }
            var step = eligible.get(id);
            if (step == null || !"SKILL".equals(step.getStepKind())) throw invalid();
            var result = results.resolveResult(invocation, id);
            if (!Objects.equals(result.sourceRunId(), run.getId())
                    || !Objects.equals(result.sourceStepId(), step.getId())
                    || !Objects.equals(result.skillVersionId(), step.getSkillVersionId())
                    || !hasResult(result.result())) throw invalid();
        }
        return Set.copyOf(references);
    }

    private static boolean hasResult(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        if (value instanceof java.util.Collection<?> list) return !list.isEmpty();
        return value instanceof Number || value instanceof Boolean;
    }

    private static ServiceException invalid() {
        return new ServiceException("COMPLETION_METHOD_EVIDENCE_INVALID");
    }
}
