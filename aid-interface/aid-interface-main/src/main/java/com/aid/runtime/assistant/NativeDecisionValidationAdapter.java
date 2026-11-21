package com.aid.runtime.assistant;

import java.util.Set;

/** Converts only explicitly owned public validation feedback into a Native correction signal. */
final class NativeDecisionValidationAdapter
{
    private static final Set<AssistantDecisionValidationFeedback.Reason> ELIGIBLE_REASONS = Set.of(
            AssistantDecisionValidationFeedback.Reason.CHAT_MESSAGE_OBJECT_REQUIRED,
            AssistantDecisionValidationFeedback.Reason.COMPLETION_REFERENCE_NOT_DECLARED,
            AssistantDecisionValidationFeedback.Reason.PLAN_STEPS_ARRAY_REQUIRED,
            AssistantDecisionValidationFeedback.Reason.PLAN_ARRAY_REQUIRED,
            AssistantDecisionValidationFeedback.Reason.PLAN_STEP_UNKNOWN_FIELD,
            AssistantDecisionValidationFeedback.Reason.PLAN_INPUT_OUTPUT_NOT_DECLARED,
            AssistantDecisionValidationFeedback.Reason.NATIVE_ARGUMENTS_OBJECT_REQUIRED,
            AssistantDecisionValidationFeedback.Reason.NATIVE_ARGUMENTS_JSON_INVALID,
            AssistantDecisionValidationFeedback.Reason.ARGUMENT_SCHEMA_MISMATCH,
            AssistantDecisionValidationFeedback.Reason.NATIVE_CONTROL_SCHEMA_MISMATCH);

    private NativeDecisionValidationAdapter() { }

    /** Classifies rejected shapes without coercing data or changing the frozen control contract. */
    static void requirePlanArrays(com.alibaba.fastjson2.JSONObject plan, String contractDigest,
            NativeRejectedSourceIdentity sourceIdentity)
    {
        for (String field : java.util.List.of("assumptions", "deliverables", "completionCriteria"))
        {
            Object value = plan.get(field);
            if ("assumptions".equals(field) && value == null) continue;
            if (value instanceof com.alibaba.fastjson2.JSONArray) continue;
            var path = switch (field)
            {
                case "assumptions" -> AssistantDecisionValidationFeedback.SafePath.PLAN_ASSUMPTIONS;
                case "deliverables" -> AssistantDecisionValidationFeedback.SafePath.PLAN_DELIVERABLES;
                default -> AssistantDecisionValidationFeedback.SafePath.PLAN_COMPLETION_CRITERIA;
            };
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.planArrayRequired(contractDigest, path,
                            AssistantDecisionValidationFeedback.ObservedType.fromJsonValue(value)),
                    sourceIdentity);
        }
    }

    /** Reject absent/current-self edges without rewriting the model's proposed plan. */
    static void requireCurrentPlanDependencies(com.alibaba.fastjson2.JSONObject plan, String digest,
            NativeRejectedSourceIdentity source)
    {
        Object raw = plan.get("steps");
        if (!(raw instanceof com.alibaba.fastjson2.JSONArray steps)) return;
        Set<String> ids = new java.util.HashSet<>();
        for (Object item : steps)
        {
            if (!(item instanceof com.alibaba.fastjson2.JSONObject step)
                    || !(step.get("stableStepId") instanceof String id) || id.isBlank()
                    || !ids.add(id)) return;
        }
        for (Object item : steps)
        {
            var step = (com.alibaba.fastjson2.JSONObject) item;
            Object rawDeps = step.get("dependsOn");
            if (rawDeps == null) continue;
            if (!(rawDeps instanceof com.alibaba.fastjson2.JSONArray deps)) return;
            for (Object dependency : deps) if (!(dependency instanceof String)) return;
        }
        for (Object item : steps)
        {
            var step = (com.alibaba.fastjson2.JSONObject) item;
            var deps = step.getJSONArray("dependsOn");
            if (deps == null) continue;
            for (Object dependency : deps)
                if (!ids.contains(dependency) || dependency.equals(step.get("stableStepId")))
                    throw new NativeDecisionValidationRejectedException(
                            AssistantDecisionValidationFeedback.planDependencyNotDeclared(digest), source);
        }
    }

    /** Same pure feasibility rule as plan admission; no plan or operation has been persisted. */
    static void requireArtifactOutputs(com.alibaba.fastjson2.JSONObject plan,
            java.util.function.Function<String,com.aid.runtime.tool.RuntimeToolProvider.OutputReferenceKind> outputs, String digest, NativeRejectedSourceIdentity source)
    {
        if (!AssistantPlanCompletionFeasibility.declaresArtifactOutputs(plan,outputs))
            throw new NativeDecisionValidationRejectedException(AssistantDecisionValidationFeedback.planArtifactOutputRequired(digest),source);
    }

    static void requireBillableOutputs(com.alibaba.fastjson2.JSONObject plan,
            java.util.function.Predicate<String> billable, String digest, NativeRejectedSourceIdentity source)
    {
        if (!AssistantPlanCompletionFeasibility.declaresBillableOutputs(plan, billable))
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.planBillableOutputRequired(digest), source);
    }

    /** Same pure feasibility rule as plan admission; no plan or operation has been persisted. */
    static void requirePlanCompletionEvidence(com.alibaba.fastjson2.JSONObject plan,
            java.util.function.Function<String, com.aid.runtime.tool.RuntimeToolProvider.OutputReferenceKind> outputs,
            Set<String> writes, String digest, NativeRejectedSourceIdentity source)
    {
        if (!AssistantPlanCompletionFeasibility.canProduceEvidence(plan, outputs, writes))
            throw new NativeDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.planCompletionEvidenceUnsupported(digest), source);
    }

    static NativeDecisionValidationRejectedException askSchemaMismatch(
            NativeRejectedSourceIdentity sourceIdentity)
    {
        return new NativeDecisionValidationRejectedException(
                AssistantDecisionValidationFeedback.nativeAskSchemaMismatch(
                        NativeAssistantControlContractV1.ASK_INPUT_SCHEMA_DIGEST), sourceIdentity);
    }

    static NativeDecisionValidationRejectedException wrap(
            AssistantDecisionValidationRejectedException rejected,
            NativeRejectedSourceIdentity sourceIdentity)
    {
        AssistantDecisionValidationFeedback feedback = rejected.feedback();
        if (feedback == null || !ELIGIBLE_REASONS.contains(feedback.reason())) throw rejected;
        return new NativeDecisionValidationRejectedException(feedback, sourceIdentity);
    }

    static NativeDecisionValidationRejectedException argumentsObjectRequired(
            String action, String trustedContractDigest, Object observed,
            NativeRejectedSourceIdentity sourceIdentity)
    {
        AssistantDecisionValidationFeedback feedback =
                AssistantDecisionValidationFeedback.nativeArgumentsObjectRequired(
                        actionFamily(action), trustedContractDigest,
                        AssistantDecisionValidationFeedback.ObservedType.fromJsonValue(observed));
        return new NativeDecisionValidationRejectedException(feedback, sourceIdentity);
    }

    static NativeDecisionValidationRejectedException argumentsJsonInvalid(
            String action, String trustedContractDigest, NativeRejectedSourceIdentity sourceIdentity)
    {
        return new NativeDecisionValidationRejectedException(
                AssistantDecisionValidationFeedback.nativeArgumentsJsonInvalid(
                        actionFamily(action), trustedContractDigest), sourceIdentity);
    }

    private static AssistantDecisionValidationFeedback.ActionFamily actionFamily(String action)
    {
        return switch (action)
        {
            case "CHAT" -> AssistantDecisionValidationFeedback.ActionFamily.CHAT;
            case "PLAN" -> AssistantDecisionValidationFeedback.ActionFamily.PLAN;
            case "TOOL" -> AssistantDecisionValidationFeedback.ActionFamily.TOOL;
            case "ASK" -> AssistantDecisionValidationFeedback.ActionFamily.ASK;
            case "CHECKPOINT" -> AssistantDecisionValidationFeedback.ActionFamily.CHECKPOINT;
            case "COMPLETE" -> AssistantDecisionValidationFeedback.ActionFamily.COMPLETE;
            default -> throw new IllegalStateException("Native validation action is not eligible");
        };
    }
}
