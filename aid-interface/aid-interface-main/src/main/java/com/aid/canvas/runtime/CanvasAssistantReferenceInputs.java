package com.aid.canvas.runtime;

import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Resolves graph inputs once and rechecks the same source selections before quote/first submission. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantReferenceInputs {
    public static final String FIELD = "serverGraphInputEvidence";
    private final CanvasMapper mapper;

    public record Resolved(List<Long> resourceIds, Map<Long, String> roles, JSONObject evidence) { }

    /** A read projection, not node storage or a grant to submit generation. */
    public JSONArray describe(long projectId, long workspaceId, String target) {
        return describeView(projectId, workspaceId, target).getJSONArray("references");
    }

    public JSONObject describeView(long projectId, long workspaceId, String target) {
        JSONArray references = new JSONArray();
        List<CanvasObject> rows = mapper.selectIncomingReferenceEdges(workspaceId, target);
        if (rows == null) throw invalid();
        Map<String, CanvasObject> observed = new HashMap<>();
        for (CanvasObject row : rows) {
            if (row == null || !Objects.equals(row.getProjectId(), projectId)
                    || !Objects.equals(row.getWorkspaceId(), workspaceId) || !"EDGE".equals(row.getObjectType())
                    || !Integer.valueOf(0).equals(row.getDeleted())) throw invalid();
            JSONObject content = JSON.parseObject(row.getContentJson());
            JSONObject metadata = content == null ? null : content.getJSONObject("metadata");
            JSONObject data = metadata == null ? null : metadata.getJSONObject("studioData");
            if (data == null || !"reference".equals(data.getString("kind"))
                    || !target.equals(content.getString("targetObjectId"))) throw invalid();
            String sourceId = content.getString("sourceObjectId");
            if (sourceId == null || !sourceId.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
            // Observation envelopes allow at most 100 array entries. This bounds only the
            // descriptive view; resolve() still validates and consumes every incoming link.
            if (references.size() >= 100) continue;
            if (!observed.containsKey(sourceId)) observed.put(sourceId, mapper.selectCanvasObject(workspaceId, sourceId));
            CanvasObject source = observed.get(sourceId);
            boolean present = source != null && "NODE".equals(source.getObjectType())
                    && Objects.equals(source.getWorkspaceId(), workspaceId) && Objects.equals(source.getProjectId(), projectId)
                    && Integer.valueOf(0).equals(source.getDeleted());
            JSONObject view = JSONObject.of("sourceObjectId", sourceId, "sourceNodePresent", present,
                    "role", data.containsKey("inputRole") ? data.get("inputRole") : "reference");
            if (data.containsKey("inputOrder")) view.put("order", data.get("inputOrder"));
            if (present) {
                JSONObject node = JSON.parseObject(source.getContentJson());
                view.put("sourceVersion", String.valueOf(source.getObjectVersion()));
                view.put("sourceNodeType", node.get("nodeType"));
                view.put("sourceTitle", node.get("title"));
            }
            references.add(view);
        }
        return JSONObject.of("managedByHost", true, "resolution", "AT_QUOTE", "totalCount", rows.size(),
                "summaryTruncated", rows.size() > references.size(), "references", references);
    }

    public Resolved resolve(long projectId, long workspaceId, String target, List<Long> explicit) {
        if (explicit == null || explicit.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(explicit).size() != explicit.size()) throw invalid();
        List<CanvasObject> rows = mapper.selectIncomingReferenceEdges(workspaceId, target);
        if (rows == null) throw invalid();
        List<Edge> edges = new ArrayList<>();
        for (CanvasObject row : rows) {
            if (row == null || !Objects.equals(row.getWorkspaceId(), workspaceId)
                    || !Objects.equals(row.getProjectId(), projectId) || !"EDGE".equals(row.getObjectType())
                    || !Integer.valueOf(0).equals(row.getDeleted()) || row.getObjectVersion() == null) throw invalid();
            JSONObject content = JSON.parseObject(row.getContentJson());
            JSONObject metadata = content == null ? null : content.getJSONObject("metadata");
            JSONObject data = metadata == null ? null : metadata.getJSONObject("studioData");
            if (data == null || !"reference".equals(data.getString("kind"))
                    || !target.equals(content.getString("targetObjectId"))) throw invalid();
            String role = data.containsKey("inputRole") ? data.getString("inputRole") : "reference";
            if (role == null || !Set.of("reference", "first_frame", "last_frame", "audio", "text").contains(role)) throw invalid();
            Integer order = null;
            if (data.containsKey("inputOrder")) {
                if (!(data.get("inputOrder") instanceof Number number) || number.doubleValue() < 0
                        || number.doubleValue() > Integer.MAX_VALUE || number.doubleValue() != number.intValue()) throw invalid();
                order = number.intValue();
            }
            edges.add(new Edge(row, content.getString("sourceObjectId"), role, order));
        }
        // Stable sort keeps persisted order for historic edges without an explicit ordinal.
        edges.sort(Comparator.comparingInt(edge -> edge.order == null ? Integer.MAX_VALUE : edge.order));
        LinkedHashSet<Long> ids = new LinkedHashSet<>(explicit);
        Map<Long, String> roles = new LinkedHashMap<>();
        JSONArray references = new JSONArray();
        Map<String, CanvasObject> nodes = new HashMap<>();
        Set<String> singletonRoles = new HashSet<>();
        for (Edge edge : edges) {
            if (edge.source == null || !edge.source.matches("[A-Za-z0-9_-]{1,64}") || edge.source.equals(target)) throw invalid();
            CanvasObject node = nodes.computeIfAbsent(edge.source, id -> mapper.selectCanvasObject(workspaceId, id));
            if (node == null || !"NODE".equals(node.getObjectType()) || !Integer.valueOf(0).equals(node.getDeleted())
                    || !Objects.equals(node.getProjectId(), projectId) || !Objects.equals(node.getWorkspaceId(), workspaceId)
                    || node.getObjectVersion() == null) throw invalid();
            JSONObject content = JSON.parseObject(node.getContentJson());
            var selection = mapper.selectNodeResultSelection(workspaceId, edge.source);
            if (selection != null && selection.getSelectionVersion() == null) throw invalid();
            JSONObject metadata = content == null ? null : content.getJSONObject("metadata");
            JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
            String type = content == null ? null : content.getString("nodeType");
            if ("TEXT".equals(type)) {
                if (!Set.of("reference", "text").contains(edge.role)) throw invalid();
                Object rawText = content.containsKey("textContent") ? content.get("textContent") : content.get("content");
                if (!(rawText instanceof String body)) throw notReady();
                String plain = com.aid.canvas.service.CanvasPlainText.normalize(body, content.getString("textFormat"));
                if (plain == null || plain.isBlank() || plain.length() > 100000) throw notReady();
                references.add(JSONObject.of("edgeId", edge.row.getObjectId(), "edgeVersion", edge.row.getObjectVersion().toString(),
                        "sourceObjectId", edge.source, "sourceVersion", node.getObjectVersion().toString(),
                        "role", "TEXT", "textHash", cn.hutool.crypto.SecureUtil.sha256(plain)));
                continue;
            }
            if ("VOICE".equals(type) && content.getString("textContent") != null
                    && !content.getString("textContent").isBlank() && studio != null
                    && studio.getLong("audioTextSelectionVersion") != null
                    && studio.getLongValue("audioTextSelectionVersion") >= (selection == null ? 0L : selection.getSelectionVersion())) throw notReady();
            Long resourceId;
            if (selection != null) {
                if (!Objects.equals(selection.getProjectId(), projectId) || !Objects.equals(selection.getWorkspaceId(), workspaceId)
                        || !edge.source.equals(selection.getObjectId()) || selection.getResultId() == null
                        || selection.getSelectionVersion() == null) throw invalid();
                var resource = mapper.selectCanvasResourceBySource(workspaceId, "MEDIA_RESULT", selection.getResultId());
                if (resource == null) throw notReady();
                resourceId = resource.getId();
            } else {
                JSONArray resources = content == null ? null : content.getJSONArray("resourceIds");
                if (resources == null) throw notReady();
                JSONObject parameters = content.getJSONObject("parameters");
                JSONArray inputs = parameters == null ? null : parameters.getJSONArray("audioResourceIds");
                Set<String> audioInputs = "VOICE".equals(type) && inputs != null
                        ? new HashSet<>(inputs.stream().map(String::valueOf).toList()) : Set.of();
                List<String> candidates = resources.stream().map(String::valueOf).filter(id -> !audioInputs.contains(id)).distinct().toList();
                String primary = studio == null ? null : studio.getString("canvasPrimaryResourceId");
                String raw;
                if (primary != null) { if (!candidates.contains(primary)) throw notReady(); raw = primary; }
                else { if (candidates.size() != 1) throw notReady(); raw = candidates.get(0); }
                if (!raw.matches("[1-9][0-9]{0,18}")) throw invalid();
                try { resourceId = Long.valueOf(raw); } catch (NumberFormatException error) { throw invalid(); }
            }
            if (resourceId == null || resourceId <= 0) throw invalid();
            if ("text".equals(edge.role) || (Set.of("first_frame", "last_frame").contains(edge.role) && !"IMAGE".equals(type))
                    || "audio".equals(edge.role) && !Set.of("VOICE", "AUDIO").contains(Objects.toString(type, ""))) throw invalid();
            String role = switch (edge.role) { case "first_frame" -> "FIRST_FRAME"; case "last_frame" -> "LAST_FRAME"; default -> "REFERENCE"; };
            String prior = roles.putIfAbsent(resourceId, role);
            if (prior != null && !prior.equals(role)) throw invalid();
            if (prior == null && !"REFERENCE".equals(role) && !singletonRoles.add(role)) throw invalid();
            ids.add(resourceId);
            references.add(JSONObject.of("edgeId", edge.row.getObjectId(), "edgeVersion", edge.row.getObjectVersion().toString(),
                    "sourceObjectId", edge.source, "sourceVersion", node.getObjectVersion().toString(),
                    "resourceId", resourceId.toString(), "role", role,
                    "selectionVersion", selection == null ? null : String.valueOf(selection.getSelectionVersion()),
                    "resultId", selection == null ? null : String.valueOf(selection.getResultId())));
        }
        JSONObject evidence = JSONObject.of("schemaVersion", "canvas-graph-input/v1", "references", references,
                "explicitResourceIds", explicit.stream().map(String::valueOf).toList());
        return new Resolved(List.copyOf(ids), Collections.unmodifiableMap(roles), evidence);
    }

    /** Reuse the existing versioned text-resource resolver; text is never a media resource ID. */
    public static List<com.aid.generation.dto.GenerationRequests.CanvasTextReference> textReferences(Resolved resolved) {
        List<com.aid.generation.dto.GenerationRequests.CanvasTextReference> result = new ArrayList<>();
        for (Object item : resolved.evidence().getJSONArray("references")) {
            JSONObject row = (JSONObject) item;
            if (!"TEXT".equals(row.getString("role"))) continue;
            var reference = new com.aid.generation.dto.GenerationRequests.CanvasTextReference();
            reference.setObjectId(row.getString("sourceObjectId"));
            reference.setExpectedVersion(row.getLong("sourceVersion"));
            reference.setMention("source_text_" + (result.size() + 1));
            result.add(reference);
        }
        return List.copyOf(result);
    }

    public Resolved requireFrozen(JSONObject prepared, long project, long workspace, String target, List<Long> explicit) {
        if (prepared == null || !prepared.containsKey(FIELD)) return null;
        JSONObject frozen = prepared.getJSONObject(FIELD);
        if (frozen == null) throw invalid();
        Resolved current = resolve(project, workspace, target, explicit);
        if (!Objects.equals(JSON.parseObject(frozen.toJSONString()), JSON.parseObject(current.evidence.toJSONString()))) throw invalid();
        return current;
    }
    private record Edge(CanvasObject row, String source, String role, Integer order) { }
    private static ServiceException invalid() {
        return new ServiceException("引用关系或来源已变化，请重新核对后报价").setDetailMessage("OPERATION_QUOTE_INPUT_CHANGED");
    }
    private static ServiceException notReady() {
        return new ServiceException("来源节点尚无可用的已选素材，请先完成或选择来源结果").setDetailMessage("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
    }
}
