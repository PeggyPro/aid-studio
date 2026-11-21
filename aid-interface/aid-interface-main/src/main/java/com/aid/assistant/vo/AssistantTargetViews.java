package com.aid.assistant.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Safe, server-verified targets produced by assistant effects. */
public final class AssistantTargetViews {
    private AssistantTargetViews() {
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Selection {
        private Integer start;
        private Integer end;
        private String selectedTextHash;
    }

    /** All durable identities and revisions are strings on the wire. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TargetRef {
        private String kind;
        private String projectId;
        private String episodeId;
        private String workspaceId;
        private String objectId;
        private String version;
        private String entityType;
        private String entityId;
        private String scriptId;
        private String contentHash;
        private Selection selection;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TargetEffect {
        private String effectId;
        private String sourceKind;
        private String sourceId;
        private String effectKind;
        private String toolCode;
        private String artifactId;
        private TargetRef resultTargetRef;
        private String locateState;
        private String reasonCode;
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EffectResolved {
        private String sessionId;
        private String effectId;
        private String locateState;
        private TargetRef currentTargetRef;
        private String reasonCode;
        private List<String> allowedActions = new ArrayList<>();
        private long observedSessionRevision;
        private long lastEventSeq;
    }
}
