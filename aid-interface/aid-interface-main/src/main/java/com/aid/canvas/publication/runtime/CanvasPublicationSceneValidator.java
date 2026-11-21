package com.aid.canvas.publication.runtime;

import com.aid.common.exception.ServiceException;
import com.aid.canvas.publication.service.CanvasSceneRecordingPort;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** 完整场景文档的资源引用协议；不接受浏览器 URL 工程或手机机位投影。 */
final class CanvasPublicationSceneValidator {
    private CanvasPublicationSceneValidator() {}
    static Set<String> validate(JSONObject document, Set<String> nodes, Set<String> expectedScenes) {
        fields(document, "document", "schemaVersion scenes");
        Object version = document.get("schemaVersion");
        if (!(version instanceof Number n) || (n.doubleValue() != 1 && n.doubleValue() != 2)) fail("场景文档版本不支持");
        boolean complete = ((Number) version).intValue() == 2;
        JSONArray scenes = array(document, "scenes", 100);
        Set<String> sceneIds = ids(scenes, "场景");
        if (!sceneIds.equals(expectedScenes)) fail("场景集合必须与预演台节点引用完全一致");
        Set<String> resources = new LinkedHashSet<>();
        int[] entries = {0};
        safe(document, 0, entries, hasRecordedPaths(document) ? 600000 : 200000);
        for (Object raw : scenes) {
            validateScene(object(raw), nodes, resources, complete);
        }
        return resources;
    }

    static void validateScene(JSONObject scene, Set<String> nodes, Set<String> resources, boolean complete) {
            fields(scene, "scene", "id name objects lights camera environmentColor environmentPreset gridVisible gridSnap groundVisible groundOpacity aspectRatio viewMode directorCamera cameraBookmarks modelAssets mainCameraVisible mainCameraLocked mainCameraLens mainCameraAimObjectId activeCameraId panoramaResourceId panoramaHistoryResourceIds panoramaYaw panoramaRadius captures timeline prompt sourceNodeIds previewImageResourceId actionLibrary scenePackage scenePackages viewportPrefs updatedAt" + (complete ? " modeling" : ""));
            text(scene, "name"); text(scene, "environmentColor");
            JSONArray objects = array(scene, "objects", 2000), lights = array(scene, "lights", 128);
            Set<String> objectIds = ids(objects, "物体"), cameraIds = new HashSet<>(Set.of("main-camera"));
            if(objectIds.contains("main-camera")) fail("物体不能占用保留机位标识");
            JSONArray bookmarks = optionalArray(scene, "cameraBookmarks", 64);
            for (Object row : bookmarks) {
                JSONObject b = object(row);
                fields(b, "cameraBookmark", "id name camera preset thumbnailResourceId visible locked createdAt lens aimObjectId");
                String id = key(b.get("id")); if (!cameraIds.add(id) || objectIds.contains(id)) fail("机位标识重复");
                camera(object(b.get("camera"))); lens(b.getJSONObject("lens"));
                ref(b, "aimObjectId", objectIds); resource(b, "thumbnailResourceId", resources);
            }
            JSONArray models = optionalArray(scene, "modelAssets", 100);
            if (scene.containsKey("modeling")) {
                CanvasSceneModelingValidator.validate(object(scene.get("modeling")), objectIds, cameraIds, ids(lights, "灯光"), resources);
            }
            Set<String> modelIds = ids(models, "模型");
            for(Object modelRow:models){JSONObject m=object(modelRow);fields(m,"modelAsset","id name fileName resourceId format kind source byteSize createdAt");if(!Set.of("glb","gltf","fbx","obj").contains(Objects.toString(m.getString("format"),""))||!Set.of("actor","prop").contains(Objects.toString(m.getString("kind"),""))||!m.containsKey("resourceId"))fail("模型必须使用已登记的完整资源包");resource(m,"resourceId",resources);}
            for (Object row : objects) {
                JSONObject o = object(row);
                fields(o, "object", "id kind name color shape sourceNodeId referenceImageResourceId modelAssetId crowdGroupId visible locked transform pose scenery actorMeta");
                if (!Set.of("actor", "prop", "scenery").contains(Objects.toString(o.getString("kind"),""))) fail("物体种类不支持");
                transform(object(o.get("transform"))); pose(o.getJSONObject("pose"));
                ref(o, "sourceNodeId", nodes); ref(o, "modelAssetId", modelIds);
                if("actor".equals(o.getString("kind"))&&!o.containsKey("modelAssetId"))fail("人物必须显式登记实际人体模型及骨骼资源，不能依赖默认人物");
                if(o.containsKey("modelAssetId")){JSONObject model=models.stream().map(CanvasPublicationSceneValidator::object).filter(m->Objects.equals(m.getString("id"),o.getString("modelAssetId"))).findFirst().orElseThrow();if(!Objects.equals(model.getString("kind"),o.getString("kind")))fail("物体种类与模型不匹配");}
                resource(o, "referenceImageResourceId", resources);
                JSONObject scenery = o.getJSONObject("scenery");
                if(scenery!=null){fields(scenery,"scenery","partIndex partName scenePackageResourceId meshPath");if(!scenery.containsKey("scenePackageResourceId"))fail("场景物体缺少包资源");resource(scenery,"scenePackageResourceId",resources);}
                JSONObject actor = o.getJSONObject("actorMeta");
                if (actor != null) fields(actor, "actorMeta", "heightMeters bodyPreset identifyColor locomotion");
            }
            ids(lights, "灯光");
            for (Object row : lights) {
                JSONObject l = object(row); fields(l, "light", "id kind name color intensity position enabled");
                if (!Set.of("ambient", "directional", "point", "spot").contains(Objects.toString(l.getString("kind"),""))) fail("灯光种类不支持");
                vector(l.get("position")); number(l.get("intensity"), 0, 1000);
            }
            camera(object(scene.get("camera")));
            if (scene.containsKey("directorCamera")) camera(object(scene.get("directorCamera")));
            lens(scene.getJSONObject("mainCameraLens"));
            ref(scene, "mainCameraAimObjectId", objectIds); ref(scene, "activeCameraId", cameraIds);
            refs(scene, "sourceNodeIds", nodes);
            resource(scene, "panoramaResourceId", resources); resource(scene, "previewImageResourceId", resources);
            resourceList(scene, "panoramaHistoryResourceIds", resources);
            JSONArray captures = optionalArray(scene, "captures", 100); ids(captures, "截图");
            for (Object row : captures) {
                JSONObject c = object(row); fields(c, "capture", "id name resourceId camera createdAt");
                if (!c.containsKey("resourceId")) fail("截图必须提供 resourceId");
                resource(c, "resourceId", resources); camera(object(c.get("camera")));
            }
            JSONArray library = optionalArray(scene, "actionLibrary", 200); Set<String> actions = ids(library, "动作");
            for (Object row : library) action(object(row));
            if (scene.containsKey("timeline")) timeline(object(scene.get("timeline")), objectIds, cameraIds, actions);
            if(scene.getJSONObject("scenePackage")!=null)pack(scene.getJSONObject("scenePackage"),resources);
            for(Object row:optionalArray(scene,"scenePackages",100))pack(object(row),resources);
            JSONObject prefs = scene.getJSONObject("viewportPrefs");
            if (prefs != null) {
                fields(prefs, "viewportPrefs", "shading cameraPreset showNameplates timelineOpen showMonitorPip burnNameplatesInRecord pathDrawMode pathEditClipId pathDrawPlane");
                if(prefs.containsKey("pathEditClipId")) {
                    JSONObject t=scene.getJSONObject("timeline");
                    Set<String> paths=t==null?Set.of():ids(optionalArray(t,"pathClips",2000),"路径片段");
                    ref(prefs,"pathEditClipId",paths);
                }
            }
    }

    private static void timeline(JSONObject t, Set<String> objects, Set<String> cameras, Set<String> actions) {
        fields(t, "timeline", "fps duration currentTime tracks cameraTracks poseTracks actionClips pathClips laneIds");
        number(t.get("duration"), 0, 86400);
        Set<String> all = new HashSet<>(objects); all.addAll(cameras);
        for(Object lane:optionalArray(t,"laneIds",10000)) {
            if(!(lane instanceof String)) fail("时间线通道标识无效");
            String[] parts=((String)lane).split(":",2);
            if(parts.length!=2||!Set.of("camera","object","pose","action").contains(parts[0])
                    ||!(parts[0].equals("camera")?cameras:objects).contains(parts[1])) fail("时间线通道引用不存在");
        }
        for (String kind : List.of("tracks", "cameraTracks", "poseTracks")) {
            for (Object row : optionalArray(t, kind, 2000)) {
                JSONObject track = object(row); String field = kind.equals("cameraTracks") ? "cameraId" : "objectId";
                fields(track, "track", field + " keyframes"); ref(track, field, kind.equals("cameraTracks") ? cameras : objects);
                if (!track.containsKey(field)) fail("轨道缺少对象标识");
                JSONArray frames = array(track, "keyframes", 10000); ids(frames, "关键帧");
                for (Object raw : frames) {
                    JSONObject f = object(raw); fields(f, "keyframe", "id time value"); number(f.get("time"), 0, 86400);
                    JSONObject value = object(f.get("value"));
                    if (kind.equals("tracks")) transform(value); else if (kind.equals("cameraTracks")) camera(value); else pose(value);
                }
            }
        }
        JSONArray clips = optionalArray(t, "actionClips", 2000); ids(clips, "动作片段");
        for (Object row : clips) {
            JSONObject c = object(row); fields(c, "actionClip", "id actorId action assetId assetName motion start end speed intensity blendIn pose");
            ref(c, "actorId", objects); ref(c, "assetId", actions); interval(c);
            number(c.get("speed"),.25,3);number(c.get("intensity"),0,1);number(c.get("blendIn"),0,Math.min(2,c.getDoubleValue("end")-c.getDoubleValue("start"))+0.000001);
            if (!c.containsKey("actorId")) fail("动作片段缺少 actorId");
            if(!Set.of("stand","walk","run","crouch","crouch_cry","sit","wave","pose","nod","shake_head","look_around","talk","bow","think","phone","arms_crossed","hands_hips","reach","cheer","stretch","kneel","march","tiptoe","jump","squat_repeat","lunge","punch","kick","throw","push","pull","dance","wave_both","imported").contains(Objects.toString(c.getString("action"),""))) fail("动作类型不支持");
            if (c.containsKey("motion")) action(object(c.get("motion")));
            if ("imported".equals(c.getString("action")) && !c.containsKey("motion") && !c.containsKey("assetId")) fail("导入动作缺少资源");
            pose(c.getJSONObject("pose"));
        }
        JSONArray paths = optionalArray(t, "pathClips", 2000); ids(paths, "运动片段");
        for (Object row : paths) {
            JSONObject p = object(row); fields(p, "pathClip", "id kind targetKind targetId start end waypoints penAnchors follow grounded source preset createdAt takeId");
            if (!Set.of("path", "follow_relative", "follow_track", "follow_static").contains(Objects.toString(p.getString("kind"),""))) fail("运动片段类型不支持");
            String targetKind = p.getString("targetKind");
            if (!Set.of("object", "camera").contains(Objects.toString(targetKind,""))) fail("运动片段目标类型不支持");
            if (!p.containsKey("targetId")) fail("运动片段缺少 targetId");
            ref(p, "targetId", targetKind.equals("camera") ? cameras : objects); interval(p);
            boolean recorded=p.containsKey("takeId");
            boolean baked=recorded || "virtual-camera".equals(p.getString("source"));
            if(recorded && (!p.getString("id").equals("take_"+takeId(p.get("takeId"))) || !"camera".equals(targetKind)
                    || !"path".equals(p.getString("kind")) || !"virtual-camera".equals(p.getString("source")))) fail("录制片段引用身份无效");
            JSONArray waypoints = array(p, "waypoints", baked ? CanvasSceneRecordingPort.MAX_RECORDED_WAYPOINTS : 10000); ids(waypoints, "路径点");
            if(baked && waypoints.size()<2) fail("录制片段缺少完整烘焙轨迹");
            for (Object raw : waypoints) {
                JSONObject w = object(raw); fields(w, "waypoint", "id time position rotation lookAt lookAtObjectId fov roll");
                number(w.get("time"), 0, 86400); vector(w.get("position"));
                if (w.containsKey("rotation")) vector(w.get("rotation"));
                if (w.containsKey("lookAt")) vector(w.get("lookAt")); ref(w, "lookAtObjectId", objects);
            }
            for (Object raw : optionalArray(p, "penAnchors", 10000)) {
                JSONObject a = object(raw); fields(a, "penAnchor", "p hIn hOut"); vector(a.get("p"));
                if (a.containsKey("hIn")) vector(a.get("hIn")); if (a.containsKey("hOut")) vector(a.get("hOut"));
            }
            JSONObject follow = p.getJSONObject("follow");
            if (follow != null) {
                fields(follow, "follow", "targetObjectId offset anchor continuousLookAt lockedPosition");
                ref(follow, "targetObjectId", objects); if (!follow.containsKey("targetObjectId")) fail("跟随缺少目标");
                if (follow.containsKey("offset")) vector(follow.get("offset")); if (follow.containsKey("lockedPosition")) vector(follow.get("lockedPosition"));
            }
        }
    }
    private static Long takeId(Object value) {
        String id=Objects.toString(value,"");
        if(!id.matches("[1-9][0-9]{0,18}")) fail("录制编号无效");
        try { return Long.valueOf(id); } catch(NumberFormatException error) { fail("录制编号无效"); return null; }
    }
    private static boolean hasRecordedPaths(JSONObject document) {
        for(Object raw:document.getJSONArray("scenes")) if(raw instanceof JSONObject scene
                && scene.get("timeline") instanceof JSONObject timeline && timeline.get("pathClips") instanceof JSONArray paths)
            for(Object clip:paths) if(clip instanceof JSONObject path && (path.containsKey("takeId") || "virtual-camera".equals(path.getString("source")))) return true;
        return false;
    }
    static List<CanvasSceneRecordingPort.Reference> recordingReferences(JSONObject document) {
        List<CanvasSceneRecordingPort.Reference> result=new ArrayList<>();
        for(Object raw:document.getJSONArray("scenes")) {
            JSONObject scene=object(raw),timeline=scene.getJSONObject("timeline");if(timeline==null)continue;
            for(Object row:optionalArray(timeline,"pathClips",2000)) {
                JSONObject path=object(row);if(!path.containsKey("takeId"))continue;
                result.add(new CanvasSceneRecordingPort.Reference(scene.getString("id"),path.getString("id"),path.getString("targetId"),takeId(path.get("takeId"))));
            }
        }
        return result;
    }
    /** A copied project owns the baked trajectory, never the original project's recording authority. */
    static void stripRecordingBindings(JSONObject document) {
        for(Object raw:document.getJSONArray("scenes")) {
            JSONObject timeline=object(raw).getJSONObject("timeline");if(timeline==null)continue;
            for(Object row:optionalArray(timeline,"pathClips",2000)) object(row).remove("takeId");
        }
    }
    private static void action(JSONObject a) {
        fields(a, "actionAsset", "id name duration loop source frames"); key(a.get("id")); number(a.get("duration"), 0, 86400);
        if(!Set.of("bvh","aid").contains(Objects.toString(a.getString("source"),""))) fail("动作来源类型不支持");
        for (Object row : array(a, "frames", 10000)) { JSONObject f=object(row); fields(f,"actionFrame","time pose"); number(f.get("time"),0,86400); pose(object(f.get("pose"))); }
    }
    private static void pack(JSONObject p,Set<String> resources){
        fields(p,"scenePackage","resourceId format partCount sourceImageResourceIds createdAt bounds");
        if(!p.containsKey("resourceId")||!Set.of("glb","gltf").contains(Objects.toString(p.getString("format"),"")))fail("场景包必须为完整登记的GLB/GLTF");
        resource(p,"resourceId",resources);resourceList(p,"sourceImageResourceIds",resources);
        if(p.getJSONObject("bounds")!=null){JSONObject b=p.getJSONObject("bounds");fields(b,"bounds","min max");vector(b.get("min"));vector(b.get("max"));}
    }
    private static void camera(JSONObject c) { fields(c,"camera","position target fov roll"); vector(c.get("position")); vector(c.get("target")); number(c.get("fov"),1,179); if(c.containsKey("roll")) number(c.get("roll"),-360,360); }
    private static void transform(JSONObject t) { fields(t,"transform","position rotation scale"); vector(t.get("position")); vector(t.get("rotation")); vector(t.get("scale")); }
    private static void lens(JSONObject l) { if(l!=null) { fields(l,"lens","bodyName lensType focalLengthMm apertureF sensorWidthMm cameraBody lensSeries"); number(l.get("focalLengthMm"),1,2000); if(!Set.of("full-frame","super-35","aps-c").contains(Objects.toString(l.getString("bodyName"),""))||!Set.of("prime","zoom").contains(Objects.toString(l.getString("lensType"),""))) fail("镜头规格类型无效"); } }
    private static void pose(JSONObject p) { if(p!=null) for(Object n:p.values()) number(n,-360,360); }
    private static void interval(JSONObject p) { number(p.get("start"),0,86400); number(p.get("end"),0,87000); if(p.getDoubleValue("start")>=p.getDoubleValue("end")) fail("片段时间区间无效"); }
    private static void vector(Object value) { if(!(value instanceof List<?> a)||a.size()!=3) fail("向量必须有3个数值"); for(Object n:(List<?>)value) number(n,-1000000,1000000); }
    static void number(Object n,double min,double max) { if(!(n instanceof Number v)||!Double.isFinite(v.doubleValue())||v.doubleValue()<min||v.doubleValue()>max) fail("数值无效或超出范围"); }
    private static void text(JSONObject o,String key) { if(!(o.get(key) instanceof String s)||s.isBlank()||s.length()>1000) fail(key+"无效"); }
    static String key(Object v) { if(!(v instanceof String s)||!s.matches("[A-Za-z0-9_-]{1,128}")) fail("标识无效"); return (String)v; }
    static JSONObject object(Object v) { if(!(v instanceof JSONObject)) fail("必须提供 JSON 对象"); return (JSONObject)v; }
    private static final Set<String> COMPOSITE_FIELDS=Set.of("scenes","objects","resources","currentResults","sceneDocument","content","modeling",
            "modelAssets","lights","camera","directorCamera","cameraBookmarks","timeline","captures","actionLibrary","scenePackage","scenePackages","viewportPrefs",
            "transform","pose","scenery","actorMeta","lens","mainCameraLens","sourceNodeIds","panoramaHistoryResourceIds","sourceImageResourceIds",
            "position","rotation","scale","target","tracks","cameraTracks","poseTracks","actionClips","pathClips","laneIds","keyframes","value","motion","frames",
            "waypoints","penAnchors","follow","offset","lockedPosition","lookAt","p","hIn","hOut","bounds","min","max","style","metadata","size","childObjectIds");
    static void fields(JSONObject o,String at,String allowed) {
        Set<String> names=Set.of(allowed.split(" "));
        for(String k:o.keySet()) {
            if(!names.contains(k)) fail(at+" 不支持字段 "+k);
            if((o.get(k) instanceof Map<?,?>||o.get(k) instanceof Iterable<?>)&&!COMPOSITE_FIELDS.contains(k))
                fail(at+"."+k+" 必须为明确的标量字段");
        }
    }
    private static JSONArray array(JSONObject o,String field,int max) { if(!(o.get(field) instanceof JSONArray a)||a.size()>max) fail(field+"必须提供数组且不能超限"); return (JSONArray)o.get(field); }
    private static JSONArray optionalArray(JSONObject o,String field,int max) { return o.containsKey(field)?array(o,field,max):new JSONArray(); }
    static Set<String> ids(JSONArray a,String what) { Set<String> out=new HashSet<>(); for(Object row:a) if(!out.add(key(object(row).get("id")))) fail(what+"标识重复"); return out; }
    private static void ref(JSONObject o,String field,Set<String> allowed) { if(o.containsKey(field)&&!allowed.contains(key(o.get(field)))) fail(field+"引用不存在"); }
    private static void refs(JSONObject o,String field,Set<String> allowed) { for(Object id:optionalArray(o,field,2000)) if(!allowed.contains(key(id))) fail(field+"引用不存在"); }
    private static void resource(JSONObject o,String field,Set<String> ids) { if(o.containsKey(field)) ids.add(resourceId(o.get(field))); }
    private static void resourceList(JSONObject o,String field,Set<String> ids) { for(Object id:optionalArray(o,field,2000)) ids.add(resourceId(id)); }
    static String resourceId(Object value) { if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}")) fail("资源编号必须是十进制字符串"); try { return Long.toString(Long.parseLong((String)value)); } catch(NumberFormatException e){ throw new ServiceException("场景资源编号越界"); } }
    private static void safe(Object v,int depth,int[] count,int limit) {
        if(depth>30||++count[0]>limit) fail("场景文档过大或层级过深");
        if(v instanceof Map<?,?> m) for(var e:m.entrySet()) {
            String k=e.getKey().toString().toLowerCase(Locale.ROOT);
            if(k.matches(".*(url|token|secret|password|sessionid|taskid|runid|userid|ownerid).*")) fail("场景不能包含地址、身份或执行数据");
            if(Set.of("visible","locked","enabled","loop","gridvisible","gridsnap","groundvisible","maincameravisible","maincameralocked","shownameplates","timelineopen","showmonitorpip","burnnameplatesinrecord","grounded","continuouslookat").contains(k)&&!(e.getValue() instanceof Boolean)) fail("场景布尔字段类型无效："+e.getKey());
            if (k.equals("assetoverrides") && e.getValue() instanceof Map<?, ?> overrides) {
                for (Object patch : overrides.values()) safe(patch,depth+2,count,limit);
            } else safe(e.getValue(),depth+1,count,limit);
        } else if(v instanceof Iterable<?> a) for(Object x:a) safe(x,depth+1,count,limit);
        else if(v instanceof String s) { if(s.length()>65536||s.matches("(?is)^\\s*(https?:|data:|blob:|file:|publication-file:|//|/profile/).*")) fail("场景不能包含裸地址"); }
        else if(v instanceof Number n) { if(!Double.isFinite(n.doubleValue())) fail("场景包含非有限数值"); }
        else if(v!=null&&!(v instanceof Boolean)) fail("场景值类型不支持");
    }
    static void fail(String message) { throw new ServiceException("完整场景文档："+message); }
}
