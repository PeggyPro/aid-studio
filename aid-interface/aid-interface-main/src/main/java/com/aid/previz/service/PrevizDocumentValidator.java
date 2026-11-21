package com.aid.previz.service;

import com.aid.previz.workspace.PrevizProjectBlobStore;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Validates the complete editor document; it never evaluates JS, Python or client-supplied URLs. */
public final class PrevizDocumentValidator {
    private static final Set<String> KINDS=Set.of("ACTOR","BOX","SPHERE","CONE","PLANE","TABLE","CHAIR","TREE","ARCH","WALL","GLB","CAMERA","LIGHT");
    private final PrevizProjectBlobStore blobs;
    private final PrevizArtifactService artifacts;
    public PrevizDocumentValidator(PrevizProjectBlobStore blobs,PrevizArtifactService artifacts){this.blobs=blobs;this.artifacts=artifacts;}
    public void validate(Long actor,String project,JSONObject document)throws IOException {
        keys(document,Set.of("schemaVersion","title","scene","versions","annotations","thumbnail","adoptedBuildSteps"));
        equal(document.get("schemaVersion"),1);if(text(document.get("title"),1,120).isBlank())invalid();
        Context context=new Context(actor,project);
        scene(object(document.get("scene")),context);
        JSONArray versions=array(document.get("versions"),0,20);Set<String> versionIds=new HashSet<>();
        for(Object raw:versions){JSONObject version=object(raw);if(!versionIds.add(text(version.get("id"),1,10000)))invalid();integer(version.get("revision"),0,9007199254740991L);text(version.get("summary"),0,10000);date(version.get("createdAt"));scene(object(version.get("scene")),context);}
        JSONArray annotations=array(document.get("annotations"),0,1000);Set<String> annotationIds=new HashSet<>();
        for(Object raw:annotations){JSONObject annotation=object(raw);if(annotation.containsKey("image")||!annotationIds.add(text(annotation.get("id"),1,10000)))invalid();integer(annotation.get("frame"),0,1800);text(annotation.get("note"),0,1000);
            for(Object id:array(annotation.get("nodeIds"),0,1000))text(id,1,10000);
            for(Object rawPoint:array(annotation.get("points"),0,500)){JSONArray point=array(rawPoint,2,2);number(point.get(0),0,1);number(point.get(1),0,1);}
            blob(object(annotation.get("imageRef")),context,true);
        }
        if(document.containsKey("thumbnail"))blob(object(document.get("thumbnail")),context,true);
        if(document.containsKey("adoptedBuildSteps")){for(Object step:array(document.get("adoptedBuildSteps"),0,20000)){String id=text(step,1,19);if(!id.matches("[1-9][0-9]{0,18}"))invalid();}}
    }
    private final class Context {
        final Long actor;final String project;final Set<String> verified=new HashSet<>();
        final Set<String> verifiedOrigins=new HashSet<>();
        final LinkedHashMap<String,Set<String>> cameras=new LinkedHashMap<>();
        final Map<String,Set<String>> objects=new HashMap<>();
        final Map<String,Set<String>> scopes=new HashMap<>();
        Set<String> active(JSONObject node,String hash)throws IOException {
            if(!node.containsKey("assetObjectIds"))return objects(hash);String key=hash+":"+node.getJSONArray("assetObjectIds").toJSONString();
            Set<String> cached=scopes.get(key);if(cached!=null)return cached;Set<String> value=blobs.editableObjectIds(actor,project,hash,node.getList("assetObjectIds",String.class));scopes.put(key,value);return value;
        }
        Set<String> objects(String hash)throws IOException {
            Set<String> cached=objects.get(hash);if(cached!=null)return cached;
            Set<String> value=blobs.nativeObjectIds(actor,project,hash);objects.put(hash,value);return value;
        }
        Context(Long actor,String project){this.actor=actor;this.project=project;}
        Set<String> cameras(String hash)throws IOException {
            Set<String> cached=cameras.get(hash);if(cached!=null)return cached;
            Set<String> value=blobs.nativeCameraIds(actor,project,hash);
            if(cameras.size()>=16)cameras.remove(cameras.keySet().iterator().next());cameras.put(hash,value);return value;
        }
    }
    private void blob(JSONObject ref,Context context,boolean image)throws IOException {
        keys(ref,Set.of("sha256","sizeBytes","mimeType"));String hash=text(ref.get("sha256"),64,64);if(!hash.matches("[a-f0-9]{64}"))invalid();
        long size=integer(ref.get("sizeBytes"),1,PrevizProjectBlobStore.MAX_BLOB_BYTES);String mime=text(ref.get("mimeType"),1,40);
        if(image&&!Set.of("image/png","image/jpeg","image/webp").contains(mime))invalid();
        String key=hash+":"+size+":"+mime;if(context.verified.add(key))blobs.requireBlob(context.actor,context.project,hash,size,mime);
    }
    private void scene(JSONObject scene,Context context)throws IOException {
        long schema=integer(scene.get("schemaVersion"),3,4);equal(scene.get("units"),"meter");equal(scene.get("upAxis"),"Y");equal(scene.get("sdkVersion"),schema==3?"aid-previz-web@1.0.0":"aid-previz-web@1.1.0");
        choice(scene.get("aspectRatio"),Set.of("16:9","9:16","1:1","4:3","3:4","21:9"));
        JSONObject timeline=object(scene.get("timeline"));equal(timeline.get("fps"),30);long frames=integer(timeline.get("frameCount"),30,1800);
        JSONObject environment=object(scene.get("environment"));choice(environment.get("presetCode"),Set.of("studio","day","night"));color(environment.get("background"));number(environment.get("exposure"),Math.pow(2,-16),Math.pow(2,8));
        Map<String,JSONObject> assets=new HashMap<>();long encodedBytes=0;
        for(Object raw:array(scene.get("assets"),0,32)) {
            JSONObject asset=object(raw);String id=text(asset.get("id"),1,10000);if(assets.putIfAbsent(id,asset)!=null||asset.containsKey("dataUrl"))invalid();text(asset.get("name"),0,200);
            String kind=choice(asset.get("kind"),Set.of("GLB","IMAGE"));JSONObject reference=object(asset.get("blob"));blob(reference,context,"IMAGE".equals(kind));
            if("GLB".equals(kind))equal(reference.get("mimeType"),"model/gltf-binary");equal(asset.get("mime"),reference.get("mimeType"));
            long encodedSize=((reference.getLongValue("sizeBytes")+2)/3)*4+("data:"+reference.getString("mimeType")+";base64,").length();
            if("IMAGE".equals(kind)&&encodedSize>40_000_000)invalid();encodedBytes+=encodedSize;if(encodedBytes>65_000_000)invalid();
            if(asset.containsKey("buildOrigin")) {
                if(!"GLB".equals(kind)||artifacts==null)invalid();JSONObject origin=object(asset.get("buildOrigin"));
                if(origin.containsKey("entry")&&!origin.containsKey("runtimeRunId"))invalid();
                String originProject=text(origin.get("projectId"),1,100),step=text(origin.get("stepId"),1,19);
                if(!originProject.matches("[A-Za-z0-9_-]{1,100}")||!step.matches("[1-9][0-9]{0,18}"))invalid();
                equal(origin.get("sha256"),reference.get("sha256"));String originKey=origin.toJSONString();
                if(context.verifiedOrigins.add(originKey)){
                    JSONObject receipt=artifacts.manifest(context.actor,originProject,Long.valueOf(step));
                    equal(origin.get("sourceVersionId"),receipt.get("sourceVersionId"));equal(origin.get("engine"),receipt.get("engine"));
                    equal(origin.get("sha256"),receipt.getJSONObject("artifact").get("sha256"));
                    if(origin.containsKey("runtimeRunId")||origin.containsKey("sceneId")) {
                        equal(origin.get("runtimeRunId"),receipt.get("runtimeRunId"));
                        equal(origin.get("sceneId"),receipt.get("sceneId"));
                        equal(origin.get("editorVersion"),receipt.get("editorVersion"));
                        if(origin.containsKey("entry"))equal(origin.get("entry"),receipt.get("entry"));
                    }
                }
            }
        }
        Map<String,JSONObject> nodes=new HashMap<>();
        for(Object raw:array(scene.get("nodes"),0,1000)) {
            JSONObject node=object(raw);String id=text(node.get("id"),1,10000);if(nodes.putIfAbsent(id,node)!=null)invalid();text(node.get("name"),0,120);String kind=choice(node.get("kind"),KINDS);
            bool(node.get("visible"));bool(node.get("locked"));color(node.get("color"));
            if(!node.containsKey("parentId")||node.get("parentId")!=null&&!(node.get("parentId") instanceof String))invalid();
            JSONObject transform=object(node.get("localTransform"));vector(transform.get("position"),false);vector(transform.get("rotation"),false);vector(transform.get("scale"),true);
            if(node.containsKey("assetPivotId")){String pivot=text(node.get("assetPivotId"),1,200);if(!"GLB".equals(kind)||!(node.get("assetObjectIds") instanceof JSONArray selected)||!selected.contains(pivot))invalid();}
            if(node.containsKey("assetObjectIds")){
                if(schema<4)invalid();
                if(!"GLB".equals(kind))invalid();JSONObject asset=assets.get(node.getString("assetId"));if(asset==null)invalid();
                Set<String> available=context.objects(asset.getJSONObject("blob").getString("sha256")),selected=new HashSet<>();
                for(Object rawId:array(node.get("assetObjectIds"),1,5000)){String objectId=text(rawId,1,200);if(!selected.add(objectId)||!available.contains(objectId))invalid();}
            }
            if(node.containsKey("assetPlayback")) {
                if(!"GLB".equals(kind))invalid();JSONObject playback=object(node.get("assetPlayback"));keys(playback,Set.of("clipIndex","clipName","loop"));integer(playback.get("clipIndex"),0,Integer.MAX_VALUE);bool(playback.get("loop"));if(playback.containsKey("clipName"))text(playback.get("clipName"),0,200);
            }
            if(node.containsKey("assetOverrides")) {
                if(!"GLB".equals(kind))invalid();JSONObject overrides=object(node.get("assetOverrides"));if(overrides.size()>5000)invalid();
                for(String objectId:overrides.keySet()){if(objectId.isBlank()||objectId.length()>200)invalid();JSONObject patch=object(overrides.get(objectId));keys(patch,Set.of("positionOffset","rotationOffset","scaleMultiplier","color","visible"));
                    for(String field:List.of("positionOffset","rotationOffset","scaleMultiplier"))if(patch.containsKey(field))vector(patch.get(field),field.equals("scaleMultiplier"));if(patch.containsKey("color"))color(patch.get("color"));if(patch.containsKey("visible"))bool(patch.get("visible"));
                }
            }
            if(node.containsKey("motionFacing")){if(!"ACTOR".equals(kind))invalid();choice(node.get("motionFacing"),Set.of("path","manual"));}
            if(node.containsKey("action"))choice(node.get("action"),Set.of("idle","walk","run","wave","sit"));
            JSONObject camera=node.containsKey("camera")?object(node.get("camera")):null;
            if(camera!=null){if(camera.containsKey("followOffset"))vector(camera.get("followOffset"),false);if(camera.containsKey("followPosition"))bool(camera.get("followPosition"));if(camera.getBooleanValue("followPosition")&&(!camera.containsKey("followNodeId")||!camera.containsKey("followOffset")))invalid();}
            if("CAMERA".equals(kind)) {
                if(camera==null)invalid();vector(camera.get("target"),false);double fov=number(camera.get("fov"),1,180);if(fov==180)invalid();
                if(camera.containsKey("projection"))choice(camera.get("projection"),Set.of("perspective","orthographic"));boolean ortho="orthographic".equals(camera.get("projection"));
                if(ortho&&number(camera.get("orthoHeight"),0,1_000_000)==0)invalid();if(camera.containsKey("roll"))number(camera.get("roll"),-Double.MAX_VALUE,Double.MAX_VALUE);
                double near=number(camera.get("near"),0,10000),far=number(camera.get("far"),0,10000);if(far<=near||!ortho&&near==0)invalid();
            }
            if("LIGHT".equals(kind)){JSONObject light=object(node.get("light"));choice(light.get("kind"),Set.of("directional","point","spot"));number(light.get("intensity"),0,20);}
            if("GLB".equals(kind)){JSONObject asset=assets.get(node.getString("assetId"));if(asset==null||!"GLB".equals(asset.get("kind")))invalid();}
        }
        for(JSONObject node:nodes.values()) {
            Set<String> seen=new HashSet<>();seen.add(node.getString("id"));String parent=node.getString("parentId");
            while(parent!=null){JSONObject ancestor=nodes.get(parent);if(ancestor==null||!seen.add(parent))invalid();parent=ancestor.getString("parentId");}
            JSONObject camera=node.getJSONObject("camera");if(camera!=null&&camera.containsKey("followNodeId")) {
                String targetId=text(camera.get("followNodeId"),1,10000);JSONObject target=nodes.get(targetId);
                if(target==null||Set.of("CAMERA","LIGHT","PLANE").contains(target.getString("kind")))invalid();
                seen.clear();while(target!=null){String id=target.getString("id");if(id.equals(node.getString("id"))||!seen.add(id))invalid();target=nodes.get(target.getString("parentId"));}
            }
        }
        Set<String> tracks=new HashSet<>(),channels=new HashSet<>();
        for(Object raw:array(timeline.get("tracks"),0,4000)) {
            JSONObject track=object(raw);String id=text(track.get("id"),0,10000),nodeId=text(track.get("nodeId"),1,10000),channel=choice(track.get("channel"),Set.of("position","rotation","scale","target"));
            String objectId=track.containsKey("objectId")?text(track.get("objectId"),1,200):null;
            if(objectId!=null&&schema<4)invalid();
            if(!tracks.add(id)||!channels.add(com.alibaba.fastjson2.JSON.toJSONString(List.of(nodeId,objectId==null?"":objectId,channel)))||!nodes.containsKey(nodeId)||channel.equals("target")&&!"CAMERA".equals(nodes.get(nodeId).get("kind")))invalid();choice(track.get("interpolation"),Set.of("linear","step"));
            if(objectId!=null){JSONObject node=nodes.get(nodeId);if(!"GLB".equals(node.getString("kind"))||"target".equals(channel))invalid();
                JSONObject asset=assets.get(node.getString("assetId"));if(asset==null||!context.active(node,asset.getJSONObject("blob").getString("sha256")).contains(objectId))invalid();}
            long previous=-1;for(Object rawKey:array(track.get("keys"),1,1800)){JSONObject key=object(rawKey);long frame=integer(key.get("frame"),0,frames-1);if(frame<=previous)invalid();previous=frame;vector(key.get("value"),channel.equals("scale"));}
        }
        long end=0;Set<String> shots=new HashSet<>();
        for(Object raw:array(scene.get("shots"),1,60)) {
            JSONObject shot=object(raw);if(!shots.add(text(shot.get("id"),0,10000)))invalid();text(shot.get("name"),0,120);
            String camera=text(shot.get("cameraId"),1,10000);JSONObject node=nodes.get(camera);boolean valid=node!=null&&"CAMERA".equals(node.get("kind"));
            if(!valid&&camera.startsWith("@native/"))for(JSONObject candidate:nodes.values())if("GLB".equals(candidate.get("kind"))&&camera.startsWith("@native/"+encode(candidate.getString("id"))+"/")) {
                JSONObject asset=assets.get(candidate.getString("assetId"));String hash=asset.getJSONObject("blob").getString("sha256");for(String hf:context.cameras(hash))if(context.active(candidate,hash).contains(hf)&&camera.equals("@native/"+encode(candidate.getString("id"))+"/"+encode(hf)))valid=true;
            }
            if(!valid||integer(shot.get("startFrame"),0,frames)!=end)invalid();long next=integer(shot.get("endFrameExclusive"),1,frames);if(next<=end)invalid();end=next;
        }
        if(end!=frames)invalid();
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("%21","!").replace("%27","'").replace("%28","(").replace("%29",")").replace("%7E","~");}
    private static JSONObject object(Object value){if(!(value instanceof JSONObject))invalid();return (JSONObject)value;}
    private static JSONArray array(Object value,int min,int max){if(!(value instanceof JSONArray values)||values.size()<min||values.size()>max)invalid();return (JSONArray)value;}
    private static String text(Object value,int min,int max){if(!(value instanceof String text)||text.length()<min||text.length()>max)invalid();return (String)value;}
    private static String choice(Object value,Set<String> choices){String text=text(value,0,100);if(!choices.contains(text))invalid();return text;}
    private static void color(Object value){if(!text(value,7,7).matches("#[a-fA-F0-9]{6}"))invalid();}
    private static void bool(Object value){if(!(value instanceof Boolean))invalid();}
    private static double number(Object value,double min,double max){if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<min||number.doubleValue()>max)invalid();return ((Number)value).doubleValue();}
    private static long integer(Object value,long min,long max){double number=number(value,min,max);if(number!=Math.rint(number))invalid();return ((Number)value).longValue();}
    private static void vector(Object value,boolean scale){JSONArray values=array(value,3,3);for(Object item:values){double n=number(item,scale?0:-10000,scale?100:10000);if(scale&&n==0)invalid();}}
    private static void equal(Object left,Object right){if(left instanceof Number&&right instanceof Number){if(((Number)left).doubleValue()!=((Number)right).doubleValue())invalid();}else if(!Objects.equals(left,right))invalid();}
    private static void keys(JSONObject object,Set<String> allowed){if(object==null||!allowed.containsAll(object.keySet()))invalid();}
    private static void date(Object value){try{Instant.parse(text(value,1,100));}catch(RuntimeException invalid){invalid();}}
    private static void invalid(){throw new IllegalArgumentException("三维工程文档结构、引用或参数无效");}
}
