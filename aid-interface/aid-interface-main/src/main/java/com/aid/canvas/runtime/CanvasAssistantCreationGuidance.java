package com.aid.canvas.runtime;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.Set;

/** Model-facing creation routing; persisted Tool contracts and old execution receipts stay unchanged. */
public final class CanvasAssistantCreationGuidance {
    private static final Set<String> STANDALONE = Set.of(
            CanvasAssistantToolProvider.NODE_CREATE_TEXT,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE, CanvasAssistantToolProvider.NODE_CREATE_VIDEO,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_MUSIC, CanvasAssistantToolProvider.NODE_CREATE_AUDIO);

    private CanvasAssistantCreationGuidance() { }

    public static String description(String code, String original) {
        if (STANDALONE.contains(code)) {
            return original + " This is a standalone-node creator: it creates NO source edge. "
                    + "For a node derived from a script, image or other existing canvas node, use "
                    + "canvas.node.create_related with the immediate source IDs instead, preserving the "
                    + "selected model and validated settings. Do not choose this standalone alternative "
                    + "for a dependent step merely because it supports the same node type or settings.";
        }
        if (CanvasAssistantToolProvider.NODE_CREATE_RELATED.equals(code)) {
            return original + " This is the creation route for dependent workflow steps, including "
                    + "script-to-image, script-to-video and image-to-video. Use kind=flow for content "
                    + "derivation without media input; use kind=reference with the actual role for "
                    + "media actually supplied to the model. It supports the same validated model/settings "
                    + "as standalone image/video creation. A plan dependsOn edge alone does not draw a canvas edge.";
        }
        return original;
    }

    public static void apply(JSONObject request, JSONArray toolCatalog) {
        boolean relatedAvailable = toolCatalog != null && toolCatalog.stream()
                .filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .anyMatch(item -> CanvasAssistantToolProvider.NODE_CREATE_RELATED.equals(item.getString("toolCode")));
        if (!relatedAvailable) return;
        JSONObject policy = new JSONObject(request.getJSONObject("catalogPolicy"));
        policy.put("canvasCreationLineage", Map.of(
                "route", "For each derived canvas node, plan canvas.node.create_related and declare sources at execution. "
                        + "Its transaction creates the node AND visible incoming edges. Standalone create tools are only for independent roots. "
                        + "Plan dependsOn establishes execution order but never creates a canvas relationship. "
                        + "Related-node creation consumes node TARGET_REFERENCE receipts through sources, not RuntimeArtifacts. "
                        + "Keep its inputArtifactBindings empty when its only input is a source node; depending on a completed media step "
                        + "does not require inventing or binding a media logicalArtifactId. Read the actual created node ID from its receipt.",
                "sources", "Use immediate actual upstream node IDs from successful creation/read receipts in this workspace. "
                        + "First wait for the producer step and consume its node receipt. Connect script -> image -> video when the video uses that image; "
                        + "connect script -> video for direct script-based video. Do not connect unrelated nodes or every previously generated node.",
                "edgeKinds", "A script/content derivation uses kind=flow and no role. A media input uses kind=reference with its real role, "
                        + "for example first_frame for an image used by image-to-video. A decorative flow edge does not supply an image to the model. "
                        + "Use only the capability and roles supported by the configured model.",
                "preserve", "Keep the selected model, exact validated settings, actual input references and normal fee approval. "
                        + "Creating an edge is not generation authorization. Preserve branches and all declared immediate sources; independent roots remain unlinked."));
        request.put("catalogPolicy", policy);
    }
}
