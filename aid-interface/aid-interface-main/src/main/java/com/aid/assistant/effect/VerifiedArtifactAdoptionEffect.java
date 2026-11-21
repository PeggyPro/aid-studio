package com.aid.assistant.effect;

/** Server-verified successful artifact adoption supplied to an optional product projector. */
public record VerifiedArtifactAdoptionEffect(Long userId,
                                             Long sessionId,
                                             Long projectId,
                                             Long workspaceId,
                                             Long artifactId,
                                             Long adoptionId,
                                             String clientRequestId,
                                             String requestDigest,
                                             String targetKind,
                                             String targetObjectId,
                                             String expectedTargetRevision,
                                             String resultTargetRevision,
                                             String artifactContentHash,
                                             String receiptJson) {
}
