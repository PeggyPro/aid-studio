package com.aid.canvas.publication.service;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Derived from the verified GLB JSON chunk at upload, never from editor input. */
final class CanvasSceneGlbCatalog {
    private CanvasSceneGlbCatalog() {}

    static JSONObject fromDocument(JSONObject gltf) {
        JSONArray nodes = gltf.getJSONArray("nodes");
        if (nodes.size() > 5000) return null;
        Map<String,Integer> counts = new HashMap<>();
        for (Object raw : nodes) {
            String id = id((JSONObject)raw);
            if (id != null) counts.merge(id, 1, Integer::sum);
        }
        JSONObject parents = new JSONObject();
        Set<String> cameraIds = new LinkedHashSet<>();
        Map<String,Integer> reachableCounts = new HashMap<>();
        JSONArray definitions = gltf.getJSONArray("cameras");
        JSONArray roots = gltf.getJSONArray("scenes").getJSONObject(gltf.getIntValue("scene", 0)).getJSONArray("nodes");
        Deque<Visit> pending = new ArrayDeque<>();
        for (Object root : roots) pending.add(new Visit(index(root, nodes.size()), ""));
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Visit visit = pending.removeLast();
            if (!seen.add(visit.index())) throw new ServiceException("GLB 内部对象不能循环或重复挂载");
            JSONObject node = nodes.getJSONObject(visit.index());
            String id = id(node);
            String parent = visit.parent();
            if (id != null) reachableCounts.merge(id, 1, Integer::sum);
            if (id != null && counts.get(id) == 1) {
                parents.put(id, parent);
                parent = id;
            }
            if (id != null && definitions != null && node.containsKey("camera")) {
                JSONObject camera = definitions.getJSONObject(index(node.get("camera"), definitions.size()));
                String type = camera.getString("type");
                if ("perspective".equals(type) || "orthographic".equals(type)) cameraIds.add(id);
            }
            JSONArray children = node.getJSONArray("children");
            if (children != null) for (Object child : children) pending.add(new Visit(index(child, nodes.size()), parent));
        }
        JSONArray animations = gltf.getJSONArray("animations");
        List<String> cameras = cameraIds.stream().filter(id -> reachableCounts.get(id) == 1).toList();
        return JSONObject.of("parents", parents, "cameras", cameras, "animationCount", animations == null ? 0 : animations.size());
    }

    private static String id(JSONObject node) {
        JSONObject extras = node.getJSONObject("extras");
        Object raw = extras == null ? null : extras.get("hf_id");
        return raw instanceof String id && !id.isBlank() && id.length() <= 200 ? id : null;
    }

    private static int index(Object raw, int size) {
        if (!(raw instanceof Number value) || value.doubleValue() != value.intValue()
                || value.intValue() < 0 || value.intValue() >= size) throw new ServiceException("GLB 内部对象索引无效");
        return value.intValue();
    }

    private record Visit(int index, String parent) {}
}
