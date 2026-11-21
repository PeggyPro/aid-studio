package com.aid.canvas.publication.runtime;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.*;
import java.util.function.Function;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static com.aid.canvas.publication.runtime.CanvasPublicationSceneValidator.*;

/** Checks the authored modeling slice. IDs in this slice are scoped to the scene or model instance. */
final class CanvasSceneModelingValidator {
    private CanvasSceneModelingValidator() {}

    static void validate(JSONObject document, Set<String> objects, Set<String> cameras, Set<String> lights, Set<String> sceneResources) {
        fieldsOnly(document, "schemaVersion scene versions annotations thumbnailResourceId directorBindings adoption");
        integer(document.get("schemaVersion"), 1, 1);
        if (document.containsKey("adoption")) {
            JSONObject adoption = object(document.get("adoption"));
            fieldsOnly(adoption, "workspace source document editor steps");
            key(adoption.get("workspace"));
            for (String version : List.of("source", "document", "editor")) {
                if (adoption.get(version) != null && !text(adoption.get(version), 64).matches("[a-f0-9]{64}")) {
                    fail("AI 工作副本的修订无效");
                }
            }
            Set<String> steps = new HashSet<>();
            for (Object step : array(adoption, "steps", 1000)) {
                String id = text(step, 20);
                if (!id.matches("[1-9][0-9]{0,19}") || !steps.add(id)) fail("建模采纳回执标识无效或重复");
            }
        }
        Map<String, String> resources = new LinkedHashMap<>();
        Map<String, String> bindings = new HashMap<>();
        Set<String> bound = new HashSet<>();
        if (document.containsKey("directorBindings")) object(document.get("directorBindings")).forEach((id, raw) -> {
            JSONObject binding = object(raw);
            fieldsOnly(binding, "id kind");
            String target = key(binding.get("id"));
            String kind = choice(binding, "kind", "object camera light");
            Set<String> valid = switch (kind) {
                case "camera" -> cameras;
                case "light" -> lights;
                default -> objects;
            };
            if (!valid.contains(target) || !bound.add(kind + ":" + target)) fail("建模绑定目标不存在或重复");
            bindings.put(key(id), kind.toUpperCase(Locale.ROOT));
        });
        JSONObject current = object(document.get("scene"));
        Set<String> nodes = graph(current, bindings, resources);
        int frames = current.getJSONObject("timeline").getIntValue("frameCount");
        Set<String> versions = new HashSet<>();
        for (Object raw : array(document, "versions", 20)) {
            JSONObject version = object(raw);
            fieldsOnly(version, "id revision summary createdAt scene directorSnapshot directorBindings");
            if (!versions.add(key(version.get("id")))) fail("建模修订标识重复");
            integer(version.get("revision"), 0, 9007199254740991L);
            text(version.get("summary"), 1000);
            text(version.get("createdAt"), 64);
            JSONObject versionGraph = object(version.get("scene"));
            Set<String> versionNodes = graph(versionGraph, Map.of(), resources);
            if (version.containsKey("directorSnapshot") != version.containsKey("directorBindings")) fail("历史预演场景和绑定必须同时提供");
            if (version.containsKey("directorSnapshot")) {
                JSONObject snapshot = object(version.get("directorSnapshot"));
                key(snapshot.get("id"));
                validateScene(snapshot, Set.of(), sceneResources, false);
                Set<String> snapshotObjects = ids(array(snapshot, "objects", 2000), "历史场景物体");
                Set<String> snapshotCameras = new HashSet<>(Set.of("main-camera"));
                if (snapshot.containsKey("cameraBookmarks")) snapshotCameras.addAll(ids(array(snapshot, "cameraBookmarks", 64), "历史场景机位"));
                Set<String> snapshotLights = ids(array(snapshot, "lights", 128), "历史场景灯光");
                Set<String> targets = new HashSet<>();
                object(version.get("directorBindings")).forEach((id, value) -> {
                    JSONObject binding = object(value);
                    fieldsOnly(binding, "id kind");
                    String kind = choice(binding, "kind", "object camera light");
                    String target = key(binding.get("id"));
                    Set<String> candidates = switch (kind) {
                        case "camera" -> snapshotCameras;
                        case "light" -> snapshotLights;
                        default -> snapshotObjects;
                    };
                    if (!versionNodes.contains(key(id)) || !candidates.contains(target)
                            || !targets.add(kind + ":" + target)) fail("历史建模绑定不存在或重复");
                });
            }
        }
        Set<String> annotations = new HashSet<>();
        for (Object raw : array(document, "annotations", 1000)) {
            JSONObject row = object(raw);
            fieldsOnly(row, "id frame nodeIds resourceId note points");
            if (!annotations.add(key(row.get("id")))) fail("标注标识重复");
            integer(row.get("frame"), 0, frames - 1);
            text(row.get("note"), 1000);
            for (Object id : array(row, "nodeIds", 1000)) if (!nodes.contains(key(id))) fail("标注引用不存在");
            for (Object point : array(row, "points", 500)) tuple(point, 2, 0, 1);
            resource(resources, row.get("resourceId"), "IMAGE");
        }
        if (document.containsKey("thumbnailResourceId")) resource(resources, document.get("thumbnailResourceId"), "IMAGE");
        sceneResources.addAll(resources.keySet());
    }

    private static Set<String> graph(JSONObject graph, Map<String, String> bindings, Map<String, String> resources) {
        fieldsOnly(graph, "schemaVersion units upAxis sdkVersion nodes assets timeline shots aspectRatio environment");
        int schema = integer(graph.get("schemaVersion"), 3, 4);
        if (!"meter".equals(graph.get("units")) || !"Y".equals(graph.get("upAxis"))
                || !(schema == 3 ? "aid-previz-web@1.0.0" : "aid-previz-web@1.1.0").equals(graph.get("sdkVersion"))) fail("建模坐标或版本无效");
        choice(graph, "aspectRatio", "16:9 9:16 1:1 4:3 3:4 21:9");
        JSONObject environment = object(graph.get("environment"));
        fieldsOnly(environment, "presetCode background exposure");
        choice(environment, "presetCode", "studio day night");
        color(environment.get("background"));
        number(environment.get("exposure"), Math.pow(2, -16), 256);
        Map<String, String> assets = new HashMap<>();
        for (Object raw : array(graph, "assets", 32)) {
            JSONObject asset = object(raw);
            fieldsOnly(asset, "id name kind mime resourceId");
            String kind = choice(asset, "kind", "GLB IMAGE");
            if (assets.putIfAbsent(key(asset.get("id")), kind) != null) fail("建模素材标识重复");
            text(asset.get("name"), 200);
            choice(asset, "mime", kind.equals("GLB") ? "model/gltf-binary" : "image/png image/jpeg image/webp");
            resource(resources, asset.get("resourceId"), kind.equals("GLB") ? "MODEL" : "IMAGE");
        }
        Map<String, JSONObject> owned = new HashMap<>();
        Map<String, String> nodes = new HashMap<>(bindings);
        for (Object raw : array(graph, "nodes", 4000)) {
            JSONObject node = object(raw);
            fieldsOnly(node, "id name parentId kind localTransform color visible locked assetId assetObjectIds assetPivotId assetPlayback assetOverrides camera light motionFacing action");
            String id = key(node.get("id"));
            String kind = choice(node, "kind", "ACTOR BOX SPHERE CONE PLANE TABLE CHAIR TREE ARCH WALL GLB CAMERA LIGHT");
            if (owned.putIfAbsent(id, node) != null || nodes.putIfAbsent(id, kind) != null) fail("建模对象标识重复");
            text(node.get("name"), 120);
            color(node.get("color"));
            bool(node.get("visible"));
            bool(node.get("locked"));
            JSONObject transform = object(node.get("localTransform"));
            fieldsOnly(transform, "position rotation scale");
            vector(transform.get("position"), false);
            vector(transform.get("rotation"), false);
            vector(transform.get("scale"), true);
            validateNode(node, kind, schema, assets);
        }
        owned.forEach((id, node) -> {
            Set<String> visited = new HashSet<>(Set.of(id));
            Object parent = node.get("parentId");
            while (parent != null) {
                String parentId = key(parent);
                JSONObject ancestor = owned.get(parentId);
                if (ancestor == null || !visited.add(parentId)) fail("建模对象父级不存在或形成循环");
                parent = ancestor.get("parentId");
            }
            JSONObject camera = node.getJSONObject("camera");
            if (camera != null && camera.containsKey("followNodeId")) {
                String target = key(camera.get("followNodeId"));
                String kind = nodes.get(target);
                if (kind == null || Set.of("CAMERA", "LIGHT", "PLANE").contains(kind)) fail("建模机位跟随目标无效");
                for (JSONObject candidate = owned.get(target); candidate != null; candidate = owned.get(candidate.getString("parentId"))) {
                    if (id.equals(candidate.getString("id"))) fail("建模机位跟随形成循环");
                }
            }
        });
        JSONObject timeline = object(graph.get("timeline"));
        fieldsOnly(timeline, "fps frameCount tracks");
        integer(timeline.get("fps"), 30, 30);
        int frames = integer(timeline.get("frameCount"), 30, 2_592_000);
        Set<String> tracks = new HashSet<>();
        Set<List<String>> channels = new HashSet<>();
        for (Object raw : array(timeline, "tracks", 4000)) {
            JSONObject track = object(raw);
            fieldsOnly(track, "id nodeId objectId channel interpolation keys");
            String node = key(track.get("nodeId"));
            String channel = choice(track, "channel", "position rotation scale target");
            String sub = track.containsKey("objectId") ? text(track.get("objectId"), 200) : "";
            if (!tracks.add(key(track.get("id"))) || !nodes.containsKey(node) || !channels.add(List.of(node, sub, channel))
                    || ("target".equals(channel) && !"CAMERA".equals(nodes.get(node)))
                    || (track.containsKey("objectId") && (sub.isBlank() || schema != 4 || !"GLB".equals(nodes.get(node))))) fail("建模轨道引用无效");
            choice(track, "interpolation", "linear step");
            int previous = -1;
            for (Object value : array(track, "keys", 1800)) {
                JSONObject frame = object(value);
                fieldsOnly(frame, "frame value");
                previous = integer(frame.get("frame"), previous + 1, frames - 1);
                vector(frame.get("value"), "scale".equals(channel));
            }
            if (previous < 0) fail("建模轨道缺少关键帧");
        }
        Set<String> shots = new HashSet<>();
        int end = 0;
        for (Object raw : array(graph, "shots", 60)) {
            JSONObject shot = object(raw);
            fieldsOnly(shot, "id name cameraId startFrame endFrameExclusive");
            String camera = text(shot.get("cameraId"), 500);
            if (!shots.add(key(shot.get("id"))) || !validCamera(camera, nodes)) fail("建模分镜机位引用无效");
            text(shot.get("name"), 120);
            integer(shot.get("startFrame"), end, end);
            end = integer(shot.get("endFrameExclusive"), end + 1, frames);
        }
        if (end != frames) fail("分镜必须连续覆盖建模时间轴");
        return nodes.keySet();
    }

    private static boolean validCamera(String camera, Map<String, String> nodes) {
        if ("CAMERA".equals(nodes.get(camera))) return true;
        String[] parts = camera.split("/", -1);
        return parts.length == 3 && "@native".equals(parts[0]) && "GLB".equals(nodes.get(parts[1])) && !parts[2].isBlank();
    }

    static void validateGlbReferences(JSONObject modeling, Function<String, JSONObject> catalogForResource) {
        validateGlbGraph(modeling.getJSONObject("scene"), catalogForResource);
        for (Object raw : modeling.getJSONArray("versions")) {
            validateGlbGraph(object(raw).getJSONObject("scene"), catalogForResource);
        }
    }

    private static void validateGlbGraph(JSONObject graph, Function<String, JSONObject> catalogForResource) {
        Map<String, JSONObject> assets = new HashMap<>();
        Map<String, GlbInstance> instances = new HashMap<>();
        for (Object raw : graph.getJSONArray("assets")) {
            JSONObject asset = object(raw);
            if ("GLB".equals(asset.getString("kind"))) {
                assets.put(asset.getString("id"), catalogForResource.apply(asset.getString("resourceId")));
            }
        }
        for (Object raw : graph.getJSONArray("nodes")) {
            JSONObject node = object(raw);
            if (!"GLB".equals(node.getString("kind"))) continue;
            JSONObject catalog = assets.get(node.getString("assetId"));
            JSONArray selection = node.getJSONArray("assetObjectIds");
            Set<String> selected = new HashSet<>();
            if (selection != null) for (Object id : selection) selected.add((String)id);
            GlbInstance instance = new GlbInstance(catalog, selected);
            instances.put(node.getString("id"), instance);
            for (String id : selected) instance.requireObject(id);
            JSONObject overrides = node.getJSONObject("assetOverrides");
            if (overrides != null) overrides.keySet().forEach(instance::requireObject);
            JSONObject playback = node.getJSONObject("assetPlayback");
            if (playback != null && (catalog == null || playback.getIntValue("clipIndex") >= catalog.getIntValue("animationCount"))) {
                fail("GLB 动画片段不属于当前模型");
            }
        }
        for (Object raw : graph.getJSONObject("timeline").getJSONArray("tracks")) {
            JSONObject track = object(raw);
            if (track.containsKey("objectId")) {
                instances.get(track.getString("nodeId")).requireObject(track.getString("objectId"));
            }
        }
        for (Object raw : graph.getJSONArray("shots")) {
            String id = object(raw).getString("cameraId");
            if (!id.startsWith("@native/")) continue;
            String[] parts = id.split("/", -1);
            GlbInstance instance = instances.get(parts[1]);
            String camera;
            try { camera = URLDecoder.decode(parts[2], StandardCharsets.UTF_8); }
            catch (IllegalArgumentException error) { fail("GLB 机位标识编码无效"); return; }
            if (instance == null || instance.catalog() == null || !instance.catalog().getJSONArray("cameras").contains(camera)) {
                fail("GLB 机位不属于当前模型");
            }
            if (!instance.selected().isEmpty()) instance.requireObject(camera);
        }
    }

    private record GlbInstance(JSONObject catalog, Set<String> selected) {
        void requireObject(String id) {
            JSONObject parents = catalog == null ? null : catalog.getJSONObject("parents");
            if (parents == null || !parents.containsKey(id)) fail("GLB 子对象不属于当前模型，请核对模型版本");
            if (selected.isEmpty()) return;
            int depth = 0;
            for (String parent = id; parent != null && !parent.isEmpty(); parent = parents.getString(parent)) {
                if (selected.contains(parent)) return;
                if (++depth > parents.size()) fail("GLB 内部对象目录存在循环");
            }
            fail("GLB 子对象超出当前采用范围");
        }
    }

    private static void validateNode(JSONObject node, String kind, int schema, Map<String, String> assets) {
        if ("GLB".equals(kind)) {
            if (!"GLB".equals(assets.get(key(node.get("assetId"))))) fail("建模 GLB 引用无效");
            Set<String> selected = new HashSet<>();
            if (node.containsKey("assetObjectIds")) {
                if (schema != 4) fail("建模 GLB 子树需要新版图结构");
                for (Object raw : array(node, "assetObjectIds", 5000)) if (!selected.add(text(raw, 200))) fail("GLB 子树重复");
                if (selected.isEmpty() || selected.contains("")) fail("GLB 子树为空");
            }
            if (node.containsKey("assetPivotId") && !selected.contains(text(node.get("assetPivotId"), 200))) fail("GLB 枢轴不在选择范围");
            if (node.containsKey("assetPlayback")) {
                JSONObject playback = object(node.get("assetPlayback"));
                fieldsOnly(playback, "clipIndex clipName loop");
                integer(playback.get("clipIndex"), 0, Integer.MAX_VALUE);
                if (playback.containsKey("clipName")) text(playback.get("clipName"), 200);
                bool(playback.get("loop"));
            }
            if (node.containsKey("assetOverrides")) {
                JSONObject overrides = object(node.get("assetOverrides"));
                if (overrides.size() > 5000) fail("GLB 局部编辑超出上限");
                overrides.forEach((id, raw) -> {
                    if (text(id, 200).isBlank()) fail("GLB 局部对象标识无效");
                    JSONObject patch = object(raw);
                    fieldsOnly(patch, "positionOffset rotationOffset scaleMultiplier color visible");
                    for (String field : List.of("positionOffset", "rotationOffset", "scaleMultiplier")) {
                        if (patch.containsKey(field)) vector(patch.get(field), "scaleMultiplier".equals(field));
                    }
                    if (patch.containsKey("color")) color(patch.get("color"));
                    if (patch.containsKey("visible")) bool(patch.get("visible"));
                });
            }
        } else if (node.keySet().stream().anyMatch(k -> k.startsWith("asset"))) fail("仅 GLB 支持模型素材和局部编辑");
        if (node.containsKey("action")) choice(node, "action", "idle walk run wave sit");
        if (node.containsKey("motionFacing")) choice(node, "motionFacing", "path manual");
        if ("CAMERA".equals(kind)) {
            JSONObject camera = object(node.get("camera"));
            fieldsOnly(camera, "projection orthoHeight roll fov near far target followNodeId followPosition followOffset");
            vector(camera.get("target"), false);
            number(camera.get("fov"), 1, 179.999999);
            boolean ortho = camera.containsKey("projection") && "orthographic".equals(choice(camera, "projection", "perspective orthographic"));
            if (ortho) number(camera.get("orthoHeight"), Double.MIN_VALUE, 1_000_000);
            number(camera.get("near"), ortho ? 0 : Double.MIN_VALUE, 10000);
            number(camera.get("far"), Math.nextUp(camera.getDoubleValue("near")), 10000);
            if (camera.containsKey("roll")) number(camera.get("roll"), -1_000_000, 1_000_000);
            if (camera.containsKey("followPosition")) bool(camera.get("followPosition"));
            if (camera.containsKey("followOffset")) vector(camera.get("followOffset"), false);
            if (Boolean.TRUE.equals(camera.get("followPosition")) && (!camera.containsKey("followNodeId") || !camera.containsKey("followOffset"))) fail("建模机位跟随缺少目标或偏移");
        } else if (node.containsKey("camera")) fail("仅机位支持镜头参数");
        if ("LIGHT".equals(kind)) {
            JSONObject light = object(node.get("light"));
            fieldsOnly(light, "kind intensity");
            choice(light, "kind", "directional point spot");
            number(light.get("intensity"), 0, 20);
        } else if (node.containsKey("light")) fail("仅灯光对象支持灯光参数");
    }

    private static void fieldsOnly(JSONObject object, String allowed) {
        if (!Set.of(allowed.split(" ")).containsAll(object.keySet())) fail("建模文档包含未声明的字段");
    }

    private static JSONArray array(JSONObject object, String field, int max) {
        if (!(object.get(field) instanceof JSONArray values) || values.size() > max) fail("建模字段 " + field + " 必须为数组且不能超限");
        return (JSONArray) object.get(field);
    }

    private static void resource(Map<String, String> resources, Object value, String kind) {
        String previous = resources.putIfAbsent(resourceId(value), kind);
        if (previous != null && !previous.equals(kind)) fail("建模素材类型冲突");
    }

    private static int integer(Object value, long min, long max) {
        number(value, min, max);
        double n = ((Number) value).doubleValue();
        if (n != Math.rint(n)) fail("建模字段必须为整数");
        return ((Number) value).intValue();
    }

    private static void vector(Object value, boolean scale) {
        tuple(value, 3, scale ? Double.MIN_VALUE : -10000, scale ? 100 : 10000);
    }

    private static void tuple(Object value, int size, double min, double max) {
        if (!(value instanceof JSONArray values) || values.size() != size) fail("建模向量长度无效");
        for (Object item : (JSONArray) value) number(item, min, max);
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s) || s.length() > max) fail("建模文本格式或长度无效");
        return (String) value;
    }

    private static String choice(JSONObject object, String field, String choices) {
        String value = text(object.get(field), 64);
        if (!Set.of(choices.split(" ")).contains(value)) fail("建模字段 " + field + " 取值无效");
        return value;
    }

    private static void color(Object value) {
        if (!text(value, 7).matches("#[a-fA-F0-9]{6}")) fail("建模颜色无效");
    }

    private static void bool(Object value) {
        if (!(value instanceof Boolean)) fail("建模布尔字段类型无效");
    }
}
