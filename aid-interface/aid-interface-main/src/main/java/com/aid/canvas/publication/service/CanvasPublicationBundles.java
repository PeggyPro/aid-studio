package com.aid.canvas.publication.service;
import com.alibaba.fastjson2.*;
import com.aid.common.exception.ServiceException;
import java.util.*;
/** 模型闭包只通过声明的资源编号与路径关联，正文及几何数据不改写。 */
public final class CanvasPublicationBundles {
    private CanvasPublicationBundles(){}
    public static JSONObject read(String metadata){
        if(metadata==null)return null;JSONObject value=JSON.parseObject(metadata);JSONObject bundle=value.getJSONObject("bundle");if(bundle==null)return null;
        if(!Set.of("bundle","glbScene").containsAll(value.keySet())||!bundle.keySet().equals(Set.of("format","rootPath","files","references")))fail();
        if(!Set.of("glb","gltf","fbx","obj").contains(bundle.getString("format")))fail();String root=CanvasModelBundleInspector.path(bundle.getString("rootPath"));
        JSONArray files=bundle.getJSONArray("files"),refs=bundle.getJSONArray("references");if(files==null||files.isEmpty()||files.size()>256||refs==null||refs.size()>4096)fail();Set<String> paths=new HashSet<>();Set<String> ids=new HashSet<>();
        for(Object raw:files){JSONObject file=(JSONObject)raw;if(!file.keySet().equals(Set.of("path","resourceId")))fail();String path=CanvasModelBundleInspector.path(file.getString("path")),id=file.getString("resourceId");if(id==null||!id.matches("[1-9][0-9]{0,18}")||!paths.add(path)||!ids.add(id))fail();}
        if(!paths.contains(root))fail();
        for(Object raw:refs){JSONObject ref=(JSONObject)raw;if(!ref.keySet().equals(Set.of("ownerPath","uri","path"))||!paths.contains(ref.getString("ownerPath"))||!paths.contains(ref.getString("path"))||ref.getString("uri")==null||ref.getString("uri").length()>2048)fail();}
        return bundle;
    }
    public static String remap(String metadata,Map<String,String> map){JSONObject bundle=read(metadata);if(bundle==null)return metadata;for(Object raw:bundle.getJSONArray("files")){JSONObject file=(JSONObject)raw;String id=map.get(file.getString("resourceId"));if(id==null)fail();file.put("resourceId",id);}JSONObject result=JSON.parseObject(metadata);result.put("bundle",bundle);return result.toJSONString();}
    public static void resolveUrls(JSONArray resources){Map<String,String> urls=new HashMap<>();for(Object raw:resources){JSONObject row=(JSONObject)raw;urls.put(row.getString("resourceId"),row.getString("resourceUrl"));}for(Object raw:resources){JSONObject bundle=((JSONObject)raw).getJSONObject("bundle");if(bundle!=null)for(Object file:bundle.getJSONArray("files")){JSONObject f=(JSONObject)file;String url=urls.get(f.getString("resourceId"));if(url==null)fail();f.put("resourceUrl",url);}}}
    private static void fail(){throw new ServiceException("模型资源闭包不完整或标识无效");}
}
