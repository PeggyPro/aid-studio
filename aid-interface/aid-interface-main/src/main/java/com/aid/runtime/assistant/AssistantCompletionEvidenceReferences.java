package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Checks declaration consistency only; persisted evidence and permission checks remain authoritative. */
public final class AssistantCompletionEvidenceReferences
{
    private AssistantCompletionEvidenceReferences() { }

    public static void requireDeclared(JSONObject completion, String trustedContractDigest)
    {
        requireDeclared(completion, trustedContractDigest, false);
    }

    public static void requireDeclared(JSONObject completion, String trustedContractDigest, boolean methodResults)
    {
        if (completion == null) throw new IllegalArgumentException("Completion structure is unavailable");
        Set<String> artifacts = references(completion.get("artifactRefs"), false);
        Set<String> targets = references(completion.get("targetWriteEvidenceRefs"), true);
        if (!(completion.get("completionCriteriaResults") instanceof JSONArray criteria)
                || criteria.isEmpty() || criteria.size() > 100)
            throw new IllegalArgumentException("Completion criteria structure is unavailable");
        for (Object raw : criteria)
        {
            if (!(raw instanceof JSONObject criterion) || !(criterion.get("criterionKind") instanceof String kind)
                    || !Set.of("TECHNICAL_ARCHIVE", "CREATIVE_QUALITY", "CONTENT_REQUIREMENT", "TARGET_WRITE").contains(kind)
                        && !(methodResults && "METHOD_RESULT".equals(kind)))
                throw new IllegalArgumentException("Completion criterion structure is unavailable");
            if ("METHOD_RESULT".equals(kind)) {
                Set<String> methodRefs = references(criterion.get("evidenceRefs"), false);
                if (methodRefs.isEmpty() || methodRefs.stream().anyMatch(ref -> !ref.matches("runtime-step:[1-9][0-9]{0,18}")))
                    throw new IllegalArgumentException("Method result reference structure is unavailable");
                continue; // Exact child/approval/current-plan checks belong to the completion service.
            }
            Set<String> declared = "TARGET_WRITE".equals(kind) ? targets : artifacts;
            for (String reference : references(criterion.get("evidenceRefs"), false))
                if (!declared.contains(reference))
                    throw new AssistantDecisionValidationRejectedException(
                            AssistantDecisionValidationFeedback.completionReferenceNotDeclared(trustedContractDigest));
        }
    }

    private static Set<String> references(Object raw, boolean optional)
    {
        if (raw == null && optional) return Set.of();
        if (!(raw instanceof JSONArray values) || values.size() > 100)
            throw new IllegalArgumentException("Completion reference structure is unavailable");
        Set<String> result = new HashSet<>();
        for (Object item : values)
        {
            if (!(item instanceof String value))
                throw new IllegalArgumentException("Completion reference type is invalid");
            result.add(value);
        }
        return Set.copyOf(result);
    }
}
