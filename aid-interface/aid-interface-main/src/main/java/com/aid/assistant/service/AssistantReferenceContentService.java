package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.ResolvedContextRef;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects resolved references into ordered, bounded model-safe content. */
@Service
public class AssistantReferenceContentService {
    private final AssistantContextLimits contextLimits;
    private final List<AssistantContextReferenceProjector> projectors;

    public AssistantReferenceContentService(
            AssistantContextLimits contextLimits,
            List<AssistantContextReferenceProjector> projectors) {
        this.contextLimits = contextLimits;
        this.projectors = projectors == null ? List.of() : List.copyOf(projectors);
    }

    public JSONArray project(Long userId,
                             List<AssistantRequests.ContextRef> rawReferences,
                             List<ResolvedContextRef> references) {
        JSONArray result = new JSONArray();
        if (rawReferences == null || references == null
                || rawReferences.size() != references.size()) {
            throw invalid("ASSISTANT_CONTEXT_REFERENCE_INVALID", "RESOLVED_REFERENCE_MISSING");
        }
        contextLimits.checkResolvedBudget(references);
        for (int ordinal = 0; ordinal < references.size(); ordinal++) {
            ResolvedContextRef reference = references.get(ordinal);
            if (reference == null || reference.contextRef() == null
                    || !Objects.equals(rawReferences.get(ordinal), reference.contextRef())) {
                throw invalid("ASSISTANT_CONTEXT_REFERENCE_INVALID", ordinal);
            }
            List<AssistantContextReferenceProjector> matching = projectors.stream()
                    .filter(projector -> projector.supports(reference.contextRef().getKind()))
                    .toList();
            if (matching.size() > 1) {
                throw invalid("ASSISTANT_CONTEXT_PROJECTOR_AMBIGUOUS", reference.contextRef().getKind());
            }
            // Some scope kinds are execution targets rather than model reference content.
            if (matching.isEmpty()) continue;
            JSONObject projected = matching.get(0).project(userId, reference, ordinal);
            if (projected == null) continue;
            result.add(projected);
            int projectedBytes = result.toJSONString().getBytes(StandardCharsets.UTF_8).length;
            if (projectedBytes > contextLimits.maxContextBytes()) {
                throw invalid("ASSISTANT_CONTEXT_TOO_LARGE", Map.of(
                        "actualBytes", projectedBytes,
                        "maxBytes", contextLimits.maxContextBytes(),
                        "overflowPolicy", "REJECT"));
            }
        }
        return result;
    }

    /**
     * Compares two already validated execution manifests while allowing only the implicit
     * FREE_CANVAS workspace baseline to advance. Explicit node and resource identities stay exact.
     */
    public boolean matchesPinnedReferenceContext(String approvedManifestJson, String currentManifestJson) {
        return AssistantReferenceContextFence.matchesPinnedManifest(
                approvedManifestJson, currentManifestJson);
    }

    /** Validates the model's ordered revision declaration against the progressed execution manifest. */
    public boolean matchesExpectedContextRevisions(JSONArray observed, String currentManifestJson) {
        return AssistantReferenceContextFence.matchesExpectedRevisions(observed, currentManifestJson);
    }

    private AssistantServiceException invalid(String code, Object details) {
        return new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code,
                "引用内容不可用于助手", details);
    }
}
