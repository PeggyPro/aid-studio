package com.aid.assistant.service;

import com.aid.runtime.domain.AidRuntimeRunStep;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Recognizes host-owned finalization receipts after their artifact references were authorized. */
public final class AssistantMaterializedOutput {
    private static final Set<String> REF_FIELDS = Set.of(
            "artifactId", "logicalArtifactId", "revision", "ref", "contentHash", "status");
    private AssistantMaterializedOutput() { }

    public static boolean matches(AidRuntimeRunStep step, Object output) {
        if (step == null || step.getMediaTaskId() == null || step.getMediaTaskId() <= 0
                || !"TOOL".equals(step.getStepKind()) || !"SUCCEEDED".equals(step.getStatus())
                || !(output instanceof JSONObject receipt) || step.getOutputArtifactRef() == null) return false;
        try {
            List<String> expected = JSON.parseArray(step.getOutputArtifactRef(), String.class);
            if (expected == null || expected.isEmpty() || new HashSet<>(expected).size() != expected.size()) return false;
            List<String> actual = new ArrayList<>();
            if (receipt.keySet().equals(Set.of("artifacts", "mediaTaskId", "status"))) {
                if (!"FINALIZED".equals(receipt.get("status"))
                        || !String.valueOf(step.getMediaTaskId()).equals(receipt.get("mediaTaskId"))) return false;
                Object values = receipt.get("artifacts");
                if (!(values instanceof JSONArray artifacts) || artifacts.isEmpty()) return false;
                for (Object value : artifacts) {
                    if (!(value instanceof JSONObject ref) || !validRef(ref)) return false;
                    actual.add(ref.getString("ref"));
                }
            } else {
                if (!validRef(receipt)) return false;
                actual.add(receipt.getString("ref"));
            }
            return actual.equals(expected);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean validRef(JSONObject ref) {
        if (!ref.keySet().equals(REF_FIELDS) || !"FINALIZED".equals(ref.get("status"))
                || !(ref.get("artifactId") instanceof Number id) || id.longValue() <= 0
                || !(ref.get("revision") instanceof Number revision) || revision.longValue() <= 0
                || !(ref.get("logicalArtifactId") instanceof String logical)
                || !logical.matches("[A-Za-z0-9._/:-]{1,64}")
                || !(ref.get("contentHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")) return false;
        return Objects.equals(ref.get("ref"), "artifact:" + logical + ":" + revision.longValue());
    }
}
