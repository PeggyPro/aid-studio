package com.aid.runtime.assistant.execution;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;

/** Optional domain-specific archive proof. Registration never substitutes for an archive receipt. */
public interface AssistantTechnicalArchiveVerifier {
    String artifactType();
    void requireArchived(AidRuntimeArtifact artifact);
}
