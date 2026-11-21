package com.aid.assistant.scope;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Supplies the configured assistant reference and assembled-context limits. */
@Component
public class AssistantContextLimits {
    private static final int ABSOLUTE_MAX_REFERENCES = 1_000;
    private static final int ABSOLUTE_MAX_CONTEXT_BYTES = 4 * 1024 * 1024;

    private final int maxReferences;
    private final int maxContextBytes;

    public AssistantContextLimits(
            @Value("${assistant.context.max-reference-count:100}") int maxReferences,
            @Value("${assistant.context.max-bytes:256000}") int maxContextBytes) {
        if (maxReferences < 1 || maxReferences > ABSOLUTE_MAX_REFERENCES
                || maxContextBytes < 1 || maxContextBytes > ABSOLUTE_MAX_CONTEXT_BYTES) {
            throw new IllegalStateException("Assistant context limits are invalid");
        }
        this.maxReferences = maxReferences;
        this.maxContextBytes = maxContextBytes;
    }

    public int maxReferences() {
        return maxReferences;
    }

    public int maxContextBytes() {
        return maxContextBytes;
    }

    /** Checks the client-authored list before the server adds its implicit workspace scope. */
    public void checkRawRequestBudget(List<AssistantRequests.ContextRef> references) {
        checkRawRequestBudget(references == null ? 0 : references.size());
    }

    /** Checks a count that must fit the public reference budget without an implicit scope. */
    public void checkRawRequestBudget(int actual) {
        checkReferenceCount(actual, maxReferences, false);
    }

    /** Checks a server-resolved list, which may contain one implicit workspace reference. */
    public void checkResolvedBudget(List<ResolvedContextRef> references) {
        checkResolvedCount(references == null ? 0 : references.size());
    }

    /** Checks persisted canonical identities that came from a server-resolved manifest. */
    public void checkResolvedIdentityBudget(List<AssistantRequests.ContextRef> references) {
        checkResolvedCount(references == null ? 0 : references.size());
    }

    private void checkResolvedCount(int actual) {
        checkReferenceCount(actual, maxReferences + 1, true);
    }

    private void checkReferenceCount(int actual, int maximum, boolean implicitWorkspaceAllowance) {
        if (actual < 0) {
            throw new IllegalArgumentException("Assistant reference count must not be negative");
        }
        if (actual > maximum) {
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "ASSISTANT_CONTEXT_REFERENCE_LIMIT", "引用数量超过助手上下文限制",
                    Map.of("actual", actual, "maxReferences", maxReferences,
                            "implicitWorkspaceAllowance", implicitWorkspaceAllowance ? 1 : 0));
        }
    }
}
