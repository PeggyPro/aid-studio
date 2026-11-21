package com.aid.canvas.runtime;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;

/** Uses only coordinator-proven presentation changes; the model's original arguments stay immutable. */
final class CanvasMediaPresentationTarget {
    static final String FIELD = "canvasPresentationTarget";
    private static final String VERSION = "canvas-presentation-target/v1";
    private CanvasMediaPresentationTarget() { }

    static CanvasAssistantContextManifest.TargetRef prepare(RuntimeToolInvocation invocation, JSONObject args) {
        JSONObject target = args == null ? null : args.getJSONObject("target");
        if (target == null) throw invalid();
        String id = target.getString("objectId"), version = target.getString("objectVersion");
        try { return CanvasAssistantContextManifest.requireTarget(invocation, id, version, true); }
        catch (ServiceException original) {
            if (invocation.getVerifiedPresentationRevisionsJson() == null) throw original;
            if (version == null || !version.matches("[1-9][0-9]{0,18}")) throw original;
            var candidates = CanvasAssistantContextManifest.mutableTargets(invocation).stream()
                    .filter(CanvasAssistantContextManifest.TargetRef.class::isInstance)
                    .map(CanvasAssistantContextManifest.TargetRef.class::cast)
                    .filter(ref -> Objects.equals(ref.objectId(), id) && "MUTATE".equals(ref.access()))
                    .toList();
            if (candidates.size() != 1) throw original;
            var current = candidates.get(0);
            if (current.objectVersion() <= Long.parseLong(version)) throw original;
            JSONArray observed = JSON.parseArray(invocation.getVerifiedPresentationRevisionsJson());
            long matches = observed.stream().filter(raw -> raw instanceof JSONObject row
                    && "FREE_CANVAS".equals(row.getString("kind"))
                    && row.get("canonicalRef") instanceof JSONObject ref
                    && Objects.equals(ref.getString("objectId"), id)
                    && Objects.equals(ref.getString("version"), version)
                    && Objects.equals(ref.getLong("workspaceId"), current.workspaceId())
                    && Objects.equals(ref.getLong("projectId"), current.projectId())).count();
            if (matches != 1) throw original;
            return current;
        }
    }

    static void freeze(JSONObject payload, JSONObject args, CanvasAssistantContextManifest.TargetRef target) {
        String originalVersion = args.getJSONObject("target").getString("objectVersion");
        if (String.valueOf(target.objectVersion()).equals(originalVersion)) return;
        if (payload.containsKey(FIELD)) throw invalid();
        JSONObject proof = new JSONObject();
        proof.put("schemaVersion", VERSION); proof.put("objectId", target.objectId());
        proof.put("originalVersion", originalVersion); proof.put("executionVersion", String.valueOf(target.objectVersion()));
        proof.put("workspaceId", String.valueOf(target.workspaceId())); proof.put("projectId", String.valueOf(target.projectId()));
        payload.put(FIELD, proof);
    }

    /** The prepared payload is already covered by the existing approval/request digests. */
    static CanvasAssistantContextManifest.TargetRef restore(RuntimeToolInvocation invocation, JSONObject args, String payloadJson) {
        JSONObject payload = JSON.parseObject(payloadJson);
        if (payload == null || !payload.containsKey(FIELD))
            return CanvasAssistantContextManifest.requireTarget(invocation, args.getJSONObject("target").getString("objectId"),
                    args.getJSONObject("target").getString("objectVersion"), true);
        JSONObject proof = payload.getJSONObject(FIELD), original = args.getJSONObject("target");
        if (proof == null || original == null || proof.getString("originalVersion") == null || !VERSION.equals(proof.getString("schemaVersion"))
                || !Objects.equals(original.getString("objectId"), proof.getString("objectId"))
                || !Objects.equals(original.getString("objectVersion"), proof.getString("originalVersion"))) throw invalid();
        var target = CanvasAssistantContextManifest.requireTarget(invocation, proof.getString("objectId"), proof.getString("executionVersion"), true);
        if (!Objects.equals(proof.getString("workspaceId"), String.valueOf(target.workspaceId()))
                || !Objects.equals(proof.getString("projectId"), String.valueOf(target.projectId()))
                || !proof.getString("originalVersion").matches("[1-9][0-9]{0,18}")
                || Long.parseLong(proof.getString("originalVersion")) >= target.objectVersion()) throw invalid();
        return target;
    }
    private static ServiceException invalid() { return new ServiceException("CANVAS_MEDIA_TARGET_CHANGED"); }
}
