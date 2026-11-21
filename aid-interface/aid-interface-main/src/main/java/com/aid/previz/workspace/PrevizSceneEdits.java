package com.aid.previz.workspace;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Deterministic editor commands. Models choose intent; the host owns identity, animation and CAS. */
public final class PrevizSceneEdits {
    private PrevizSceneEdits() {}
    private static final Set<String> FIELDS=Set.of("position","rotation","scale","color","visible","target");
    private static final Map<String,String> OFFSETS=Map.of("position","positionOffset","rotation","rotationOffset","scale","scaleMultiplier");
    public static JSONArray prepare(JSONObject scene,JSONArray requested,String defaultNode,int frame) {
        if(requested==null||requested.isEmpty()||requested.size()>100||frame<0||frame>=scene.getJSONObject("timeline").getIntValue("frameCount"))invalid();
        Map<String,JSONObject> nodes=nodes(scene);Set<String> fields=new HashSet<>();JSONArray result=new JSONArray();
        for(Object raw:requested){
            if(!(raw instanceof JSONObject edit)||!Set.of("nodeId","objectId","field","value","mode","keyframe").containsAll(edit.keySet()))invalid();
            JSONObject edit=(JSONObject)raw;
            String nodeId=edit.getString("nodeId");if(nodeId==null)nodeId=defaultNode;
            JSONObject node=nodes.get(nodeId);String objectId=edit.getString("objectId"),field=edit.getString("field"),mode=edit.getString("mode");
            if(node==null||node.getBooleanValue("locked")||field==null||!FIELDS.contains(field))invalid();
            if(objectId!=null&&(objectId.isBlank()||objectId.length()>200||!"GLB".equals(node.getString("kind"))||field.equals("target")))invalid();
            if(field.equals("target")&&!"CAMERA".equals(node.getString("kind")))invalid();
            if(field.equals("position")&&node.getJSONObject("camera")!=null&&node.getJSONObject("camera").getBooleanValue("followPosition"))throw new IllegalArgumentException("机位正在跟随主体，请先调整跟随偏移或解除跟随");
            if(!fields.add(JSON.toJSONString(Arrays.asList(nodeId,objectId,field))))throw new IllegalArgumentException("同一批次不能重复修改同一字段");
            if(mode==null)mode="SET";if(!Set.of("SET","OFFSET").contains(mode)||!OFFSETS.containsKey(field)&&!mode.equals("SET"))invalid();
            Object value=edit.get("value"),base=value(scene,node,objectId,field,frame);
            if(OFFSETS.containsKey(field)||field.equals("target")){
                JSONArray vector=vector(value,field.equals("scale")),prior=vector(base,field.equals("scale"));
                if(mode.equals("OFFSET")){vector=JSON.parseArray(vector.toJSONString());for(int axis=0;axis<3;axis++)vector.set(axis,field.equals("scale")?prior.getDoubleValue(axis)*vector.getDoubleValue(axis):prior.getDoubleValue(axis)+vector.getDoubleValue(axis));}
                value=vector(vector,field.equals("scale"));
            }else if(field.equals("color")){if(!(value instanceof String color)||!color.matches("#[a-fA-F0-9]{6}"))invalid();}
            else if(!(value instanceof Boolean))invalid();
            if(edit.containsKey("keyframe")&&!(edit.get("keyframe") instanceof Boolean))invalid();
            if(edit.getBooleanValue("keyframe")&&!OFFSETS.containsKey(field)&&!field.equals("target"))invalid();
            JSONObject prepared=JSONObject.of("nodeId",nodeId,"field",field,"baseValue",base,"value",value,"frame",frame,"keyframe",edit.getBooleanValue("keyframe"),"existingTrack",track(scene,nodeId,objectId,field)!=null,"targetKind",node.get("kind"),"assetId",node.get("assetId"),"parentId",node.get("parentId"),"assetObjectIds",node.get("assetObjectIds"));
            if(objectId!=null)prepared.put("objectId",objectId);result.add(prepared);
        }
        return result;
    }
    public static JSONObject apply(JSONObject original,JSONArray prepared,BiFunction<JSONObject,String,Boolean> hasObject,Function<JSONObject,Set<String>> roots) {
        JSONObject scene=JSON.parseObject(original.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));Map<String,JSONObject> nodes=nodes(scene);
        for(Object raw:prepared){
            JSONObject edit=(JSONObject)raw;JSONObject node=nodes.get(edit.getString("nodeId"));String id=edit.getString("objectId"),field=edit.getString("field");
            if(node==null||node.getBooleanValue("locked"))throw new IllegalArgumentException("编辑目标已删除或锁定");
            if(!Objects.equals(node.get("kind"),edit.get("targetKind"))||!Objects.equals(node.get("assetId"),edit.get("assetId"))||!Objects.equals(node.get("parentId"),edit.get("parentId"))||!Objects.equals(node.get("assetObjectIds"),edit.get("assetObjectIds")))throw new IllegalArgumentException("编辑目标或来源模型已变化，原编辑未覆盖新版本");
            if(edit.getIntValue("frame")>=scene.getJSONObject("timeline").getIntValue("frameCount"))throw new IllegalArgumentException("时间轴范围已变化，未写入超出范围的关键帧");
            if(field.equals("position")&&node.getJSONObject("camera")!=null&&node.getJSONObject("camera").getBooleanValue("followPosition"))throw new IllegalArgumentException("机位跟随关系已变化，原编辑未覆盖跟随设置");
            if(id!=null&&!hasObject.apply(node,id))throw new IllegalArgumentException("内部对象不存在或已不属于当前采用范围");
            Object current=value(scene,node,id,field,edit.getIntValue("frame")),base=edit.get("baseValue"),desired=edit.get("value");
            if(desired instanceof JSONArray values){
                JSONArray merged=JSON.parseArray(((JSONArray)current).toJSONString());
                for(int axis=0;axis<3;axis++){
                    double before=((JSONArray)base).getDoubleValue(axis),now=merged.getDoubleValue(axis),next=values.getDoubleValue(axis);
                    if(Math.abs(next-before)<1e-6)continue;
                    if(Math.abs(now-before)>1e-6&&Math.abs(now-next)>1e-6)throw new IllegalArgumentException("同一坐标已被其他编辑修改，未覆盖当前结果");merged.set(axis,next);
                }desired=merged;
            }else if(!Objects.equals(current,base)&&!Objects.equals(current,desired))throw new IllegalArgumentException("同一属性已被其他编辑修改，未覆盖当前结果");
            JSONObject track=track(scene,node.getString("id"),id,field);
            if(track==null&&edit.getBooleanValue("existingTrack"))throw new IllegalArgumentException("原动画轨道已被删除，未将旧编辑重新写入静态姿态");
            if(track!=null||edit.getBooleanValue("keyframe")){
                if(track==null){track=JSONObject.of("id",UUID.randomUUID().toString(),"nodeId",node.getString("id"),"channel",field,"interpolation","linear","keys",new JSONArray());if(id!=null)track.put("objectId",id);scene.getJSONObject("timeline").getJSONArray("tracks").add(track);}
                JSONArray keys=track.getJSONArray("keys");int frame=edit.getIntValue("frame");keys.removeIf(k->((JSONObject)k).getIntValue("frame")==frame);keys.add(JSONObject.of("frame",frame,"value",desired));keys.sort(Comparator.comparingInt(k->((JSONObject)k).getIntValue("frame")));
                if(id!=null)promote(scene);
            }else if(id!=null){patch(node,id).put(OFFSETS.getOrDefault(field,field),desired);}
            else if(OFFSETS.containsKey(field))node.getJSONObject("localTransform").put(field,desired);
            else if(field.equals("target"))node.getJSONObject("camera").put("target",desired);
            else {node.put(field,desired);if(field.equals("color")&&"GLB".equals(node.getString("kind")))for(String root:roots.apply(node))patch(node,root).put("color",desired);}
            if(field.equals("rotation")&&id==null&&"ACTOR".equals(node.getString("kind")))node.put("motionFacing","manual");
        }
        return scene;
    }
    /** Verify the requested effect, allowing independent axes and document metadata to advance. */
    public static boolean satisfied(JSONObject scene,JSONArray edits) {
        Map<String,JSONObject> nodes=nodes(scene);
        for(Object raw:edits){JSONObject edit=(JSONObject)raw;JSONObject node=nodes.get(edit.getString("nodeId"));String id=edit.getString("objectId"),field=edit.getString("field");int frame=edit.getIntValue("frame");
            if(node==null||frame>=scene.getJSONObject("timeline").getIntValue("frameCount")||!Objects.equals(node.get("kind"),edit.get("targetKind"))||!Objects.equals(node.get("assetId"),edit.get("assetId"))||!Objects.equals(node.get("parentId"),edit.get("parentId"))||!Objects.equals(node.get("assetObjectIds"),edit.get("assetObjectIds")))return false;
            Object actual=value(scene,node,id,field,frame),desired=edit.get("value");
            if(desired instanceof JSONArray values){if(!(actual instanceof JSONArray current))return false;JSONArray base=edit.getJSONArray("baseValue"),required=edit.getJSONArray("requiredAxes");for(int axis=0;axis<3;axis++)if((required==null?Math.abs(values.getDoubleValue(axis)-base.getDoubleValue(axis))>1e-6:required.getBooleanValue(axis))&&Math.abs(current.getDoubleValue(axis)-values.getDoubleValue(axis))>1e-6)return false;}
            else if(!Objects.equals(actual,desired))return false;
            if(edit.getBooleanValue("keyframe")){JSONObject track=track(scene,node.getString("id"),id,field);if(track==null||track.getJSONArray("keys").stream().noneMatch(k->((JSONObject)k).getIntValue("frame")==frame))return false;}
        }return true;
    }
    private static void promote(JSONObject scene){scene.put("schemaVersion",4);scene.put("sdkVersion","aid-previz-web@1.1.0");}
    private static JSONObject patch(JSONObject node,String id){JSONObject patches=node.getJSONObject("assetOverrides");if(patches==null){patches=new JSONObject();node.put("assetOverrides",patches);}JSONObject patch=patches.getJSONObject(id);if(patch==null){patch=new JSONObject();patches.put(id,patch);}return patch;}
    private static Map<String,JSONObject> nodes(JSONObject scene){Map<String,JSONObject> result=new HashMap<>();for(Object raw:scene.getJSONArray("nodes")){JSONObject node=(JSONObject)raw;result.put(node.getString("id"),node);}return result;}
    private static JSONObject track(JSONObject scene,String node,String id,String field){for(Object raw:scene.getJSONObject("timeline").getJSONArray("tracks")){JSONObject track=(JSONObject)raw;if(node.equals(track.getString("nodeId"))&&Objects.equals(id,track.getString("objectId"))&&field.equals(track.getString("channel")))return track;}return null;}
    private static Object value(JSONObject scene,JSONObject node,String id,String field,int frame){
        JSONObject track=track(scene,node.getString("id"),id,field);
        if(track!=null){JSONArray keys=track.getJSONArray("keys");JSONObject first=keys.getJSONObject(0);if(frame<=first.getIntValue("frame"))return first.getJSONArray("value");
            for(int index=1;index<keys.size();index++){JSONObject next=keys.getJSONObject(index),before=keys.getJSONObject(index-1);if(frame<next.getIntValue("frame")){double fraction="step".equals(track.getString("interpolation"))?0:(frame-before.getDoubleValue("frame"))/(next.getDoubleValue("frame")-before.getDoubleValue("frame"));JSONArray values=new JSONArray();for(int axis=0;axis<3;axis++)values.add(before.getJSONArray("value").getDoubleValue(axis)+(next.getJSONArray("value").getDoubleValue(axis)-before.getJSONArray("value").getDoubleValue(axis))*fraction);return values;}}
            return keys.getJSONObject(keys.size()-1).getJSONArray("value");
        }
        if(id==null){if(OFFSETS.containsKey(field))return node.getJSONObject("localTransform").getJSONArray(field);if(field.equals("target"))return node.getJSONObject("camera").getJSONArray("target");return node.get(field);}
        JSONObject patches=node.getJSONObject("assetOverrides"),patch=patches==null?null:patches.getJSONObject(id);String key=OFFSETS.getOrDefault(field,field);
        if(patch!=null&&patch.containsKey(key))return patch.get(key);
        return field.equals("scale")?JSONArray.of(1,1,1):OFFSETS.containsKey(field)?JSONArray.of(0,0,0):field.equals("visible")?true:null;
    }
    private static JSONArray vector(Object raw,boolean scale){if(!(raw instanceof JSONArray values)||values.size()!=3)invalid();JSONArray values=(JSONArray)raw;for(Object item:values){if(!(item instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>10000||scale&&(n.doubleValue()<=0||n.doubleValue()>100))invalid();}return values;}
    private static void invalid(){throw new IllegalArgumentException("场景编辑字段、数值或目标无效，未写入工程");}
}
