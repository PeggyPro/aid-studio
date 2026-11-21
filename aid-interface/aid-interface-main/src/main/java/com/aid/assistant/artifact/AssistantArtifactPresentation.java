package com.aid.assistant.artifact;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Display purpose only; it never changes artifact authority, persistence or completion evidence. */
public final class AssistantArtifactPresentation {
    public static final String DELIVERABLE = "DELIVERABLE";
    public static final String INTERNAL_STATE = "INTERNAL_STATE";

    private AssistantArtifactPresentation() { }

    public static String role(AidRuntimeArtifact artifact) {
        if (artifact == null) return DELIVERABLE;
        JSONObject metadata;
        try { metadata = JSON.parseObject(artifact.getMetadataJson()); }
        catch (RuntimeException malformed) { return DELIVERABLE; }
        if (metadata == null) return DELIVERABLE;
        if (metadata.containsKey("presentationRole"))
            return INTERNAL_STATE.equals(metadata.get("presentationRole")) ? INTERNAL_STATE : DELIVERABLE;
        // Compatibility for structural records written before producers declared a display purpose.
        // The schema and record kind must both match; ordinary JSON text remains a deliverable.
        if ("CREATIVE_TEXT".equals(artifact.getArtifactType())
                && "story-workspace/v2".equals(artifact.getSchemaVersion())
                && "STORY_WORKSPACE".equals(metadata.get("kind"))) return INTERNAL_STATE;
        return DELIVERABLE;
    }
}
