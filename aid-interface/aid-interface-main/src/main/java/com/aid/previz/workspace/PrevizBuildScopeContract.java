package com.aid.previz.workspace;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bind creative operation intent to a pinned editor observation before executing any build. */
public final class PrevizBuildScopeContract {
    private PrevizBuildScopeContract() {}
    public static JSONObject bind(JSONObject request, String editorVersion, JSONObject editor) {
        if(request==null)return null; // Historical builds require explicit adoption selection in the editor.
        if(editor==null||editorVersion==null||!editorVersion.matches("[a-f0-9]{64}")
                ||!Set.of("kind","targetNodeIds","outputObjectIds").containsAll(request.keySet()))invalid();
        String kind=request.getString("kind");
        if(kind==null||!Set.of("ADD","MODIFY","REPLACE_SCOPE","REBUILD_SCENE").contains(kind)||editor.getJSONArray("nodes")==null)invalid();
        JSONArray outputs=identities(request.get("outputObjectIds"),200,5000);
        JSONArray targets=identities(request.get("targetNodeIds"),100,1000);
        Map<String,JSONObject> nodes=new java.util.LinkedHashMap<>();
        for(Object raw:editor.getJSONArray("nodes")){
            if(!(raw instanceof JSONObject node)||nodes.putIfAbsent(node.getString("id"),node)!=null)invalid();
        }
        if("ADD".equals(kind)) { if(!targets.isEmpty()||outputs.isEmpty())invalid(); }
        else if("REBUILD_SCENE".equals(kind)) {
            if(!targets.isEmpty())invalid();
            Set<String> geometry=new HashSet<>();
            for(JSONObject node:nodes.values())if(!Set.of("CAMERA","LIGHT").contains(node.getString("kind"))){
                if(node.getBooleanValue("locked"))invalid();geometry.add(node.getString("id"));
            }
            for(JSONObject node:nodes.values())if(geometry.contains(node.getString("id"))&&!geometry.contains(node.getString("parentId")))targets.add(node.getString("id"));
        } else if(targets.isEmpty()||"MODIFY".equals(kind)&&targets.size()!=1||"REPLACE_SCOPE".equals(kind)&&outputs.isEmpty())invalid();
        JSONArray baseline=new JSONArray();
        for(Object target:targets){
            JSONObject node=nodes.get(target);
            if(node==null||node.getBooleanValue("locked")||Set.of("CAMERA","LIGHT").contains(node.getString("kind")))invalid();
            if("MODIFY".equals(kind)&&!"GLB".equals(node.getString("kind")))invalid();
            baseline.add(JSONObject.parseObject(node.toJSONString()));
        }
        return JSONObject.of("version",1,"kind",kind,"editorVersion",editorVersion,"sceneDigest",editor.getString("sceneDigest"),
                "targetNodeIds",targets,"outputObjectIds",outputs);
    }
    private static JSONArray identities(Object raw,int length,int limit){
        if(raw==null)return new JSONArray();
        if(!(raw instanceof JSONArray values)||values.size()>limit)invalid();
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();
        for(Object value:(JSONArray)raw){if(!(value instanceof String id)||id.isBlank()||id.length()>length||!seen.add(id))invalid();result.add(value);}
        return result;
    }
    private static void invalid(){throw new IllegalArgumentException("三维操作范围与已观察对象不一致，尚未提交构建");}
}
