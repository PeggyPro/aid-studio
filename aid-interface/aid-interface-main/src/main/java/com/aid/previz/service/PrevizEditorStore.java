package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Immutable editor overlays are separate from authored code and never execution authority. */
@Service
@ConditionalOnProperty(prefix="aid.previz", name="enabled", havingValue="true")
public final class PrevizEditorStore {
    private final PrevizSourceStore store;
    private final PrevizArtifactService artifacts;
    public PrevizEditorStore(@Value("${aid.previz.source-root}") String root, PrevizProjectService projects,
                            PrevizArtifactService artifacts) throws IOException {
        store = new PrevizSourceStore(Path.of(root).resolve(".editor-state"), projects);
        this.artifacts = artifacts;
    }
    public String current(Long actor, String project) throws IOException { return store.currentVersion(actor, project); }
    public JSONObject read(Long actor, String project, String version) throws IOException {
        var files = store.read(actor, project, version);
        return JSON.parseObject(new String(files.get("editor.json"), StandardCharsets.UTF_8));
    }
    public String save(Long actor, String project, String expected, JSONObject state) throws IOException {
        if (state == null || state.toJSONString().getBytes(StandardCharsets.UTF_8).length > 2_000_000
                || !Set.of("sceneDigest", "frame", "targetNodeId", "nodes","selectedObjects","documentVersion","selectedNodeIds").containsAll(state.keySet())
                || !state.keySet().containsAll(Set.of("sceneDigest", "frame", "targetNodeId", "nodes"))) invalid();
        // Spring/Jackson creates nested Map/List values, whereas tool JSON uses JSONObject/JSONArray.
        // Normalize representation once; both entrances then use the same strict field validation.
        state = JSON.parseObject(JSON.toJSONString(state, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        if (!digest(state.getString("sceneDigest")) || !(state.get("frame") instanceof Number)
                || state.getDoubleValue("frame") < 0 || state.getDoubleValue("frame") > 1800) invalid();
        if(state.containsKey("documentVersion")&&!digest(state.getString("documentVersion")))invalid();
        var nodes = state.getJSONArray("nodes");
        if (nodes == null || nodes.size() > 1000) invalid();
        var ids = new java.util.HashSet<String>();
        for (Object raw : nodes) {
            if (!(raw instanceof JSONObject)) invalid();
            JSONObject node = (JSONObject) raw;
            if (!Set.of("id","name","kind","localTransform","visible","locked","assetOverrides","assetPlayback","buildOrigin","parentId","color","assetObjectIds","assetPivotId").containsAll(node.keySet())) invalid();
            String id = node.getString("id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}") || !ids.add(id)
                    || node.getString("name") == null || node.getString("name").length() > 120
                    || !(node.get("visible") instanceof Boolean) || !(node.get("locked") instanceof Boolean)) invalid();
            JSONObject transform = node.getJSONObject("localTransform");
            if (transform == null || !transform.keySet().equals(Set.of("position","rotation","scale"))) invalid();
            for (String field : transform.keySet()) vector(transform.get(field), "scale".equals(field));
            if(node.containsKey("parentId")&&node.get("parentId")!=null&&(!(node.get("parentId") instanceof String parent)||!parent.matches("[A-Za-z0-9_-]{1,100}")))invalid();
            if(node.containsKey("color")&&(!(node.get("color") instanceof String color)||!color.matches("#[a-fA-F0-9]{6}")))invalid();
            if(node.containsKey("assetObjectIds")){
                if(!"GLB".equals(node.getString("kind"))||!(node.get("assetObjectIds") instanceof com.alibaba.fastjson2.JSONArray selected)||selected.isEmpty()||selected.size()>5000)invalid();
                var seen=new java.util.HashSet<String>();for(Object rawId:node.getJSONArray("assetObjectIds"))if(!(rawId instanceof String value)||value.isBlank()||value.length()>200||!seen.add(value))invalid();
            }
            if(node.containsKey("assetPivotId")&&(!(node.get("assetPivotId") instanceof String pivot)||!node.containsKey("assetObjectIds")||!node.getJSONArray("assetObjectIds").contains(pivot)))invalid();
            JSONObject playback=node.getJSONObject("assetPlayback");
            if(playback!=null) {
                if(!"GLB".equals(node.getString("kind"))||!Set.of("clipIndex","clipName","loop").containsAll(playback.keySet())
                        ||!(playback.get("clipIndex") instanceof Number index)||index.doubleValue()!=index.intValue()||index.intValue()<0
                        ||!(playback.get("loop") instanceof Boolean)
                        ||playback.containsKey("clipName")&&(!(playback.get("clipName") instanceof String name)||name.length()>200))invalid();
            }
            JSONObject patches = node.getJSONObject("assetOverrides");
            if (patches != null) {
                if (!"GLB".equals(node.getString("kind")) || patches.size() > 5000) invalid();
                for (String object : patches.keySet()) {
                    if (object.isBlank() || object.length() > 200 || !(patches.get(object) instanceof JSONObject)) invalid();
                    JSONObject patch = patches.getJSONObject(object);
                    if (!Set.of("positionOffset","rotationOffset","scaleMultiplier","color","visible").containsAll(patch.keySet())) invalid();
                    for (String field : patch.keySet()) {
                        Object value = patch.get(field);
                        if (field.equals("visible")) { if (!(value instanceof Boolean)) invalid(); }
                        else if (field.equals("color")) { if (!(value instanceof String color) || !color.matches("#[a-fA-F0-9]{6}")) invalid(); }
                        else vector(value, field.equals("scaleMultiplier"));
                    }
                }
            }
            JSONObject origin = node.getJSONObject("buildOrigin");
            if (origin != null) {
                if (!"GLB".equals(node.getString("kind")) || !project.equals(origin.getString("projectId"))) invalid();
                String step = origin.getString("stepId");
                if (step == null || !step.matches("[1-9][0-9]{0,18}")) invalid();
                JSONObject verified = artifacts.manifest(actor, project, Long.valueOf(step));
                if (!java.util.Objects.equals(origin.getString("sourceVersionId"), verified.getString("sourceVersionId"))
                    || !java.util.Objects.equals(origin.getString("engine"), verified.getString("engine"))
                    || !java.util.Objects.equals(origin.getString("sha256"), verified.getJSONObject("artifact").getString("sha256"))) invalid();
            }
        }
        String target = state.getString("targetNodeId");
        if(state.containsKey("selectedNodeIds")){
            if(!(state.get("selectedNodeIds") instanceof com.alibaba.fastjson2.JSONArray selected)||selected.size()>100)invalid();var selectedIds=new java.util.HashSet<String>();
            for(Object id:state.getJSONArray("selectedNodeIds"))if(!(id instanceof String value)||!ids.contains(value)||!selectedIds.add(value))invalid();
        }
        if(state.containsKey("selectedObjects")){
            if(!(state.get("selectedObjects") instanceof com.alibaba.fastjson2.JSONArray selected)||selected.size()>100)invalid();
            var seen=new java.util.HashSet<String>();
            for(Object raw:state.getJSONArray("selectedObjects")){
                if(!(raw instanceof JSONObject item)||!item.keySet().equals(Set.of("nodeId","objectId")))invalid();
                JSONObject item=(JSONObject)raw;String owner=item.getString("nodeId"),id=item.getString("objectId");
                if(!ids.contains(owner)||id==null||id.isBlank()||id.length()>200||!seen.add(owner+"\n"+id)
                    ||nodes.stream().noneMatch(value->value instanceof JSONObject n&&owner.equals(n.getString("id"))&&"GLB".equals(n.getString("kind"))))invalid();
            }
        }
        if (target != null && nodes.stream().noneMatch(raw -> raw instanceof JSONObject node
                && target.equals(node.getString("id")) && node.getJSONObject("buildOrigin") != null && !node.getBooleanValue("locked"))) invalid();
        // Store canonical key order, so equivalent editor snapshots replay idempotently.
        String canonical = JSON.toJSONString(state, com.alibaba.fastjson2.JSONWriter.Feature.MapSortField);
        return store.save(actor, project, expected, Map.of("editor.json", canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void vector(Object raw, boolean scale) {
        if (!(raw instanceof com.alibaba.fastjson2.JSONArray array) || array.size()!=3) invalid();
        for (Object value : (com.alibaba.fastjson2.JSONArray) raw) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || Math.abs(number.doubleValue()) > 100000 || scale && (number.doubleValue()<=0 || number.doubleValue()>100)) invalid();
        }
    }
    private static void invalid() { throw new ServiceException("三维编辑快照无效或构建来源不一致"); }
}
