package com.aid.assistant.effect;

import java.util.List;

/** Server-verified persisted Tool receipt supplied to an optional product projector. */
public record VerifiedRuntimeToolEffect(Long userId,
                                        Long sessionId,
                                        Long runId,
                                        Long stepId,
                                        String idempotencyKey,
                                        String toolCode,
                                        String contractVersion,
                                        String schemaDigest,
                                        String contextManifestJson,
                                        String argumentsJson,
                                        String outputJson,
                                        List<String> outputArtifactRefs,
                                        String preparedPayloadJson) {
    /** Existing projectors retain their original argument semantics. */
    public VerifiedRuntimeToolEffect(Long userId, Long sessionId, Long runId, Long stepId,
            String idempotencyKey, String toolCode, String contractVersion, String schemaDigest,
            String contextManifestJson, String argumentsJson, String outputJson,
            List<String> outputArtifactRefs) {
        this(userId, sessionId, runId, stepId, idempotencyKey, toolCode, contractVersion, schemaDigest,
                contextManifestJson, argumentsJson, outputJson, outputArtifactRefs, null);
    }
}
