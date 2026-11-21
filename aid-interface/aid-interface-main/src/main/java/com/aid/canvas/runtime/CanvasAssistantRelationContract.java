package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One immutable source binding supplies both graph provenance and subsequent input preparation. */
public final class CanvasAssistantRelationContract {
    private static final Set<String> KINDS = Set.of("reference", "sequence", "flow");
    private static final Set<String> ROLES = Set.of("reference", "first_frame", "last_frame", "text", "audio");
    private CanvasAssistantRelationContract() { }

    public record NodeDefinition(String action, String type, String kind, String mediaKind, int width, int height) { }
    public static NodeDefinition nodeDefinition(String kind) {
        if (kind == null) throw invalid();
        return switch (kind) {
            case "TEXT" -> new NodeDefinition("CREATE_TEXT", "TEXT", "text", null, 360, 260);
            case "IMAGE" -> new NodeDefinition("CREATE_IMAGE", "IMAGE", "image", "image", 480, 270);
            case "VIDEO" -> new NodeDefinition("CREATE_VIDEO", "VIDEO", "video", "video", 480, 270);
            case "MUSIC" -> new NodeDefinition("CREATE_MUSIC", "VOICE", "voice", "audio", 320, 180);
            case "AUDIO" -> new NodeDefinition("CREATE_AUDIO", "VOICE", "voice", "audio", 320, 180);
            default -> throw invalid();
        };
    }
    public static java.util.Map<String, Object> nodePayload(JSONObject arguments) {
        var definition = nodeDefinition(arguments.getString("nodeKind"));
        if ("TEXT".equals(definition.type())) {
            if (arguments.containsKey("prompt") || arguments.containsKey("modelCode")
                    || arguments.containsKey("generationSettings")) throw invalid();
            return CanvasAssistantOperationCanonicalizer.textNodePayload(arguments);
        }
        if (arguments.containsKey("textContent") || "VOICE".equals(definition.type())
                && (arguments.containsKey("modelCode") || arguments.containsKey("generationSettings"))) throw invalid();
        return CanvasAssistantOperationCanonicalizer.mediaNodePayload(arguments, definition.type(), definition.kind(),
                definition.mediaKind(), definition.width(), definition.height());
    }

    public record Source(String objectId, long objectVersion, String kind, String role, int order) {
        public Source {
            if (objectId == null || !objectId.matches("[A-Za-z0-9_-]{1,64}") || objectVersion < 1
                    || kind == null || !KINDS.contains(kind) || order < 0
                    || ("reference".equals(kind) ? role == null || !ROLES.contains(role) : role != null)) throw invalid();
        }
        public JSONObject snapshot() {
            JSONObject value = JSONObject.of("objectId", objectId, "objectVersion", Long.toString(objectVersion),
                    "kind", kind, "order", order);
            if (role != null) value.put("role", role);
            return value;
        }
    }

    public static List<Source> bind(JSONArray supplied, String manifest, long projectId, long workspaceId) {
        if (supplied == null || supplied.isEmpty()) throw invalid();
        List<Source> result = new ArrayList<>();
        Set<String> identities = new HashSet<>(), singletonRoles = new HashSet<>();
        for (int i = 0; i < supplied.size(); i++) {
            if (!(supplied.get(i) instanceof JSONObject value)
                    || !Set.of("objectId", "kind", "role").containsAll(value.keySet())
                    || !(value.get("objectId") instanceof String objectId)
                    || !(value.get("kind") instanceof String kind)) throw invalid();
            String role = null;
            if ("reference".equals(kind)) {
                if (!(value.get("role") instanceof String selected)) throw invalid();
                role = selected;
            } else if (value.containsKey("role")) throw invalid();
            var observed = CanvasAssistantContextManifest.requireObservedSource(manifest, projectId, workspaceId, objectId);
            Source source = new Source(objectId, observed.objectVersion(), kind, role, i);
            if (!identities.add(objectId + ":" + kind + ":" + role)) throw invalid();
            if (Set.of("first_frame", "last_frame").contains(role == null ? "" : role)
                    && !singletonRoles.add(role)) throw invalid();
            result.add(source);
        }
        return List.copyOf(result);
    }

    public static String edgeId(String targetId, Source source) {
        requireTarget(targetId, source);
        // Version/ordinal are properties of this relationship, not its identity.
        return "asst_edge_" + SecureUtil.sha256(targetId + "\n" + source.objectId() + "\n"
                + source.kind() + "\n" + (source.role() == null ? "" : source.role())).substring(0, 24);
    }

    public static JSONObject edgePayload(String targetId, Source source) {
        requireTarget(targetId, source);
        JSONObject data = JSONObject.of("kind", source.kind(), "sourceVersion", Long.toString(source.objectVersion()),
                "inputOrder", source.order());
        if (source.role() != null) data.put("inputRole", source.role());
        return JSONObject.of("sourceObjectId", source.objectId(), "targetObjectId", targetId,
                "metadata", JSONObject.of("type", "studioEdge", "studioData", data));
    }

    /** Feed this complete batch into the existing workspace transaction, never a second edge write. */
    public static List<CanvasRequests.Mutation> creationBatch(CanvasRequests.Mutation node, List<Source> sources) {
        if (node == null || !"NODE".equals(node.getObjectType()) || !"UPSERT".equals(node.getAction())
                || !Long.valueOf(0).equals(node.getExpectedVersion()) || sources == null || sources.isEmpty()) throw invalid();
        List<CanvasRequests.Mutation> result = new ArrayList<>();
        result.add(node);
        Set<String> ids = new HashSet<>();
        for (Source source : sources) {
            String id = edgeId(node.getObjectId(), source);
            if (!ids.add(id)) throw invalid();
            CanvasRequests.Mutation edge = new CanvasRequests.Mutation();
            edge.setObjectId(id);
            edge.setObjectType("EDGE");
            edge.setAction("UPSERT");
            edge.setExpectedVersion(0L);
            edge.setLockToken(node.getLockToken());
            edge.setPayload(edgePayload(node.getObjectId(), source));
            result.add(edge);
        }
        return List.copyOf(result);
    }

    private static void requireTarget(String targetId, Source source) {
        if (source == null || targetId == null || !targetId.matches("[A-Za-z0-9_-]{1,64}")
                || targetId.equals(source.objectId())) throw invalid();
    }
    private static ServiceException invalid() {
        return new ServiceException("节点来源关系无效").setDetailMessage("CANVAS_SOURCE_RELATION_INVALID");
    }
}
