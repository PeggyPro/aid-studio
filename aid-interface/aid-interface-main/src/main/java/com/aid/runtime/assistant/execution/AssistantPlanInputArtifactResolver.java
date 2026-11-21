package com.aid.runtime.assistant.execution;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves explicitly bound dependency outputs into immutable plan inputs. */
@Service
public class AssistantPlanInputArtifactResolver
{
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeArtifactMapper artifacts;

    public static final String DEPENDENCY_FAILED = "PLAN_DEPENDENCY_FAILED";

    /** Canvas plans use declared dependency edges, scoped to the approved plan revision. */
    public Set<String> failedCanvasMediaDependents(AidRuntimeRun run, AidRuntimePlan plan, JSONArray planSteps)
    {
        if (!"APPROVED".equals(plan.getStatus()) || !Objects.equals(run.getPlanRevision(), plan.getRevision()))
            return Set.of();
        List<String> ids = planSteps.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .map(step -> step.getString("stableStepId")).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Set.of();
        List<AidRuntimeRunStep> attempts = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getStableStepId, AidRuntimeRunStep::getStatus,
                        AidRuntimeRunStep::getProviderAcceptanceState, AidRuntimeRunStep::getMediaTaskId)
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getPlanRevision, plan.getRevision())
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .in(AidRuntimeRunStep::getStableStepId, ids)
                .orderByDesc(AidRuntimeRunStep::getAttemptNo, AidRuntimeRunStep::getId));
        Map<String, AidRuntimeRunStep> latest = new HashMap<>();
        for (AidRuntimeRunStep attempt : attempts)
            latest.putIfAbsent(attempt.getStableStepId(), attempt);
        return failedCanvasMediaDependents(planSteps, latest);
    }

    static Set<String> failedCanvasMediaDependents(JSONArray planSteps, Map<String, AidRuntimeRunStep> latest)
    {
        Map<String, List<String>> children = new HashMap<>();
        for (Object raw : planSteps)
        {
            if (!(raw instanceof JSONObject step)) continue;
            for (String source : optionalStrings(step.getJSONArray("dependsOn"), "PLAN_DEPENDENCIES_INVALID"))
                children.computeIfAbsent(source, key -> new ArrayList<>()).add(step.getString("stableStepId"));
        }
        ArrayDeque<String> pending = new ArrayDeque<>();
        latest.forEach((key, step) -> {
            if (step.getMediaTaskId() != null && !"UNKNOWN".equals(step.getProviderAcceptanceState())
                    && isFailedStep(step)) pending.add(key);
        });
        Set<String> blocked = new LinkedHashSet<>();
        while (!pending.isEmpty())
        {
            for (String child : children.getOrDefault(pending.removeFirst(), List.of()))
            {
                AidRuntimeRunStep attempt = latest.get(child);
                boolean notSubmitted = attempt == null || attempt.getMediaTaskId() == null
                        && !"UNKNOWN".equals(attempt.getProviderAcceptanceState()) && isFailedStep(attempt);
                if (notSubmitted && blocked.add(child)) pending.addLast(child);
            }
        }
        return Set.copyOf(blocked);
    }

    private static boolean isFailedStep(AidRuntimeRunStep step)
    {
        return switch (Objects.toString(step.getStatus(), "")) {
            case "FAILED", "FAILED_PRETASK", "CANCELLED", "CANCELLED_PRETASK" -> true;
            default -> false;
        };
    }

    /** Read-only projection; unresolved receipts cannot authorize execution. */
    public Projection resolveForProjection(AidRuntimeRun run, AidRuntimePlan plan, JSONObject targetStep)
    {
        try
        {
            return new Projection(resolve(run, plan, targetStep), null);
        }
        catch (ServiceException unresolved)
        {
            String code = unresolved.getMessage();
            if (!"PLAN_INPUT_BINDING_ARTIFACT_INVALID".equals(code)
                    && !"PLAN_INPUT_BINDING_RECEIPT_INVALID".equals(code)) throw unresolved;
            return new Projection(new Resolution(List.of(), false), code);
        }
    }

    public Resolution resolve(AidRuntimeRun run, AidRuntimePlan plan, JSONObject targetStep)
    {
        require(run != null && plan != null && targetStep != null
                        && Objects.equals(run.getId(), plan.getRunId())
                        && Objects.equals(run.getPlanRevision(), plan.getRevision()),
                "PLAN_INPUT_BINDING_IDENTITY_INVALID");
        JSONObject definition = object(plan.getDefinitionJson(), "PLAN_DEFINITION_INVALID");
        JSONArray planSteps = definition.getJSONArray("steps");
        require(planSteps != null && !planSteps.isEmpty(), "PLAN_STEPS_INVALID");
        String targetStable = identifier(targetStep.getString("stableStepId"));
        JSONObject authoritativeTarget = uniquePlanStep(planSteps, targetStable);
        require(Objects.equals(AssistantPlanExecutionJson.canonical(authoritativeTarget),
                        AssistantPlanExecutionJson.canonical(targetStep)),
                "PLAN_TARGET_STEP_CHANGED");

        List<String> result = optionalStrings(targetStep.getJSONArray("inputArtifactRefs"), "PLAN_INPUTS_INVALID");
        Set<String> unique = new LinkedHashSet<>(result);
        require(unique.size() == result.size(), "PLAN_INPUTS_DUPLICATE");
        Set<String> dependencies = new LinkedHashSet<>(optionalStrings(targetStep.getJSONArray("dependsOn"),
                "PLAN_DEPENDENCIES_INVALID"));
        JSONArray bindings = targetStep.getJSONArray("inputArtifactBindings");
        if (bindings == null || bindings.isEmpty()) return new Resolution(List.copyOf(result), true);

        for (Object raw : bindings)
        {
            require(raw instanceof JSONObject, "PLAN_INPUT_BINDING_INVALID");
            JSONObject binding = (JSONObject) raw;
            require(binding.size() == 3 && binding.containsKey("sourceStableStepId")
                            && binding.containsKey("logicalArtifactId") && binding.containsKey("artifactType"),
                    "PLAN_INPUT_BINDING_INVALID");
            String sourceStable = identifier(binding.getString("sourceStableStepId"));
            String logicalId = identifier(binding.getString("logicalArtifactId"));
            String artifactType = identifier(binding.getString("artifactType"));
            require(dependencies.contains(sourceStable), "PLAN_INPUT_BINDING_NOT_DIRECT_DEPENDENCY");
            JSONObject sourcePlanStep = uniquePlanStep(planSteps, sourceStable);
            List<String> declaredOutputs = strings(sourcePlanStep.getJSONArray("outputLogicalArtifactIds"),
                    "PLAN_OUTPUTS_INVALID");
            require(declaredOutputs.stream().filter(logicalId::equals).count() == 1,
                    "PLAN_INPUT_BINDING_OUTPUT_INVALID");

            List<AidRuntimeRunStep> producers = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, run.getId())
                    .eq(AidRuntimeRunStep::getPlanRevision, plan.getRevision())
                    .eq(AidRuntimeRunStep::getStableStepId, sourceStable)
                    .in(AidRuntimeRunStep::getStepKind, List.of("TOOL", "SKILL"))
                    .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                    .eq(AidRuntimeRunStep::getDelFlag, "0")
                    .orderByAsc(AidRuntimeRunStep::getId));
            if (producers == null || producers.isEmpty()) return new Resolution(List.copyOf(result), false);
            require(producers.size() == 1, "PLAN_INPUT_BINDING_SOURCE_AMBIGUOUS");
            AidRuntimeRunStep producer = producers.get(0);
            List<String> outputRefs = strings(parseArray(producer.getOutputArtifactRef()),
                    "PLAN_INPUT_BINDING_RECEIPT_INVALID");

            List<AidRuntimeArtifact> matches = artifacts.selectList(new LambdaQueryWrapper<AidRuntimeArtifact>()
                    .eq(AidRuntimeArtifact::getProducerRunId, run.getId())
                    .eq(AidRuntimeArtifact::getProducerStepId, producer.getId())
                    .eq(AidRuntimeArtifact::getAgentVersionId, run.getVersionId())
                    .eq(AidRuntimeArtifact::getPlanRevision, plan.getRevision())
                    .eq(AidRuntimeArtifact::getSourceContextDigest, run.getContextDigest())
                    .eq(AidRuntimeArtifact::getLogicalArtifactId, logicalId)
                    .eq(AidRuntimeArtifact::getArtifactType, artifactType)
                    .eq(AidRuntimeArtifact::getStatus, "FINALIZED")
                    .eq(AidRuntimeArtifact::getDelFlag, "0"));
            require(matches != null && matches.size() == 1, "PLAN_INPUT_BINDING_ARTIFACT_INVALID");
            AidRuntimeArtifact artifact = matches.get(0);
            require(artifact.getRevision() != null && artifact.getRevision() > 0
                            && artifact.getContentHash() != null
                            && artifact.getContentHash().matches("[0-9a-f]{64}"),
                    "PLAN_INPUT_BINDING_ARTIFACT_INVALID");
            String ref = "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision();
            require(outputRefs.stream().filter(ref::equals).count() == 1,
                    "PLAN_INPUT_BINDING_RECEIPT_INVALID");
            require(unique.add(ref), "PLAN_INPUTS_DUPLICATE");
            result.add(ref);
        }
        return new Resolution(List.copyOf(result), true);
    }

    private JSONObject uniquePlanStep(JSONArray values, String stableStepId)
    {
        JSONObject found = null;
        for (Object raw : values)
        {
            if (!(raw instanceof JSONObject candidate)) continue;
            if (!Objects.equals(stableStepId, candidate.getString("stableStepId"))) continue;
            require(found == null, "PLAN_STEP_DUPLICATE");
            found = candidate;
        }
        require(found != null, "PLAN_INPUT_BINDING_SOURCE_INVALID");
        return found;
    }

    private JSONArray parseArray(String value)
    {
        try { return value == null ? null : JSON.parseArray(value); }
        catch (RuntimeException invalid) { throw new ServiceException("PLAN_INPUT_BINDING_RECEIPT_INVALID"); }
    }

    private JSONObject object(String value, String code)
    {
        try
        {
            JSONObject result = JSON.parseObject(value);
            require(result != null, code);
            return result;
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException(code);
        }
    }

    private static List<String> strings(JSONArray values, String code)
    {
        require(values != null, code);
        List<String> result = new ArrayList<>();
        for (Object raw : values)
        {
            require(raw instanceof String, code);
            result.add(identifier((String) raw));
        }
        return result;
    }

    private static List<String> optionalStrings(JSONArray values, String code)
    {
        return values == null ? new ArrayList<>() : strings(values, code);
    }

    private static String identifier(String value)
    {
        require(value != null && !value.isBlank() && value.length() <= 500, "PLAN_INPUT_BINDING_INVALID");
        return value;
    }

    private static void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    public record Resolution(List<String> inputArtifactRefs, boolean inputsResolved) { }
    public record Projection(Resolution resolution, String inputResolutionErrorCode) { }
}
