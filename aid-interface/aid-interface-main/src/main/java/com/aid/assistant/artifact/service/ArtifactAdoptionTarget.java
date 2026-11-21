package com.aid.assistant.artifact.service;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.domain.AidAssistantSession;
import lombok.AllArgsConstructor;
import lombok.Data;

public interface ArtifactAdoptionTarget {
    String targetKind();

    Result adopt(Context context);

    Capability inspect(CapabilityContext context);

    @Data
    @AllArgsConstructor
    class Context {
        private Long userId;
        private AidRuntimeArtifact artifact;
        private AidAssistantSession session;
        private ArtifactRequests.Adopt request;
    }

    @Data
    @AllArgsConstructor
    class Result {
        private String targetRevision;
        private Object receipt;
    }

    @Data
    @AllArgsConstructor
    class CapabilityContext {
        private Long userId;
        private AidRuntimeArtifact artifact;
        private AidAssistantSession session;
        private ArtifactRequests.AdoptionCapabilities request;
    }

    @Data
    @AllArgsConstructor
    class Capability {
        private String targetKind;
        private String targetObjectId;
        private String currentTargetRevision;
        private boolean allowed;
        private String reasonCode;
        private String displayName;
        private Object requiredContext;
    }
}
