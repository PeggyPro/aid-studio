package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Map;

/** A plan revision changes work to do; it does not invalidate an immutable, verified technical receipt. */
final class AssistantTechnicalEvidenceScope {
    private AssistantTechnicalEvidenceScope() { }

    static boolean permitsEarlierPlan(Long artifactPlan, Long currentPlan, String reference,
                                      JSONArray results, Map<String, String> approvedCriteria) {
        if (artifactPlan == null || currentPlan == null || artifactPlan <= 0 || artifactPlan >= currentPlan
                || reference == null || results == null || approvedCriteria == null) return false;
        boolean used = false;
        for (Object raw : results) {
            if (!(raw instanceof JSONObject result)) return false;
            if (!(result.get("evidenceRefs") instanceof JSONArray evidence) || !evidence.contains(reference)) continue;
            String id = result.get("criterionId") instanceof String text ? text : null;
            if (id == null || !"TECHNICAL_ARCHIVE".equals(approvedCriteria.get(id))
                    || !"TECHNICAL_ARCHIVE".equals(result.getString("criterionKind"))
                    || !Boolean.TRUE.equals(result.get("passed"))) return false;
            used = true;
        }
        return used;
    }
}
