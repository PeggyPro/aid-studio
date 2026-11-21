package com.aid.previz.service;

import com.aid.previz.workspace.PrevizProjectBlobStore;
import com.aid.previz.workspace.PrevizSceneEdits;
import com.aid.previz.workspace.PrevizSourceStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Reuses the authoritative document CAS; scalar edits never rebuild or execute model-authored code. */
@Service
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizSceneEditService {
    private final PrevizEditorStore editors;
    private final PrevizDocumentStore documents;
    private final PrevizProjectBlobStore blobs;
    private final PrevizProjectService projects;
    private final com.aid.previz.mapper.PrevizArtifactMapper receipts;
    private final Path journals;
    public PrevizSceneEditService(PrevizEditorStore editors,PrevizDocumentStore documents,PrevizProjectBlobStore blobs,
                                 PrevizProjectService projects,com.aid.previz.mapper.PrevizArtifactMapper receipts,@Value("${aid.previz.source-root}") String root){
        this.editors=editors;this.documents=documents;this.blobs=blobs;this.projects=projects;
        this.receipts=receipts;this.journals=Path.of(root).resolve(".editor-operations");
    }
    public JSONObject receipt(Long actor,String project,Long step)throws IOException {
        projects.requireRead(actor,project);String json=receipts.sceneEditReceipt(actor,step);if(json==null)throw new IllegalArgumentException("编辑步骤尚未成功或无权读取");
        JSONObject receipt=JSON.parseObject(json);if(!project.equals(receipt.getString("projectId"))||!"APPLIED".equals(receipt.getString("state")))throw new IllegalArgumentException("编辑回执与工程不一致");
        documents.read(actor,project,receipt.getString("documentVersion"));receipt.put("runtimeStepId",String.valueOf(step));return receipt;
    }
    public JSONObject inspect(Long actor,String project,String editorVersion,JSONObject request)throws IOException {
        projects.requireRead(actor,project);JSONObject editor=editors.read(actor,project,editorVersion);
        if(request.getString("nodeId")==null)return JSONObject.of("editorVersion",editorVersion,"frame",editor.get("frame"),"nodes",editor.get("nodes"),"selectedObjects",editor.get("selectedObjects"));
        if(editor.getString("documentVersion")==null)throw new IllegalArgumentException("请先保存工程后再查询内部对象");
        JSONObject scene=documents.read(actor,project,editor.getString("documentVersion")).getJSONObject("document").getJSONObject("scene"),node=null;
        for(Object raw:scene.getJSONArray("nodes"))if(raw instanceof JSONObject value&&request.getString("nodeId").equals(value.getString("id")))node=value;
        if(node==null||!"GLB".equals(node.getString("kind")))throw new IllegalArgumentException("请选择模型节点查询内部对象");
        JSONObject asset=null;for(Object raw:scene.getJSONArray("assets"))if(raw instanceof JSONObject value&&node.getString("assetId").equals(value.getString("id")))asset=value;
        if(asset==null)throw new IllegalArgumentException("模型资产不存在");
        var catalog=blobs.editableObjectCatalog(actor,project,asset.getJSONObject("blob").getString("sha256"),node.getList("assetObjectIds",String.class));
        int offset=integerOption(request,"offset",0),limit=integerOption(request,"limit",100);if(offset<0||offset>5000||limit<1||limit>200)throw new IllegalArgumentException("对象分页范围无效");
        var page=new com.alibaba.fastjson2.JSONArray();for(int i=offset;i<Math.min(catalog.size(),offset+limit);i++)page.add(catalog.get(i));
        return JSONObject.of("editorVersion",editorVersion,"nodeId",node.get("id"),"objects",page,"total",catalog.size(),"nextOffset",offset+limit<catalog.size()?offset+limit:null);
    }
    private int integerOption(JSONObject request,String key,int fallback){if(!request.containsKey(key))return fallback;Object value=request.get(key);if(!(value instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("对象分页必须为整数");return ((Number)value).intValue();}
    public JSONObject prepare(Long actor,String project,String editorVersion,JSONObject request)throws IOException {
        if(request==null||request.getJSONArray("edits")==null)throw new IllegalArgumentException("请明确需要修改的对象属性");
        projects.requireWrite(actor,project);JSONObject editor=editors.read(actor,project,editorVersion);
        String version=editor.getString("documentVersion");
        if(version==null)throw new IllegalArgumentException("当前场景尚未同步服务器，请先保存工程再执行编辑");
        JSONObject observed=documents.read(actor,project,version),scene=observed.getJSONObject("document").getJSONObject("scene");
        int frame=editor.getIntValue("frame");
        if(request.containsKey("frame")){Object raw=request.get("frame");if(!(raw instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("关键帧位置必须为整数");frame=((Number)raw).intValue();}
        var edits=JSON.parseArray(request.getJSONArray("edits").toJSONString());
        if(editor.getJSONArray("selectedObjects")!=null&&editor.getJSONArray("selectedObjects").size()==1){
            JSONObject selected=editor.getJSONArray("selectedObjects").getJSONObject(0);
            for(Object raw:edits){JSONObject edit=(JSONObject)raw;if(!edit.containsKey("nodeId")&&!edit.containsKey("objectId")){edit.put("nodeId",selected.getString("nodeId"));edit.put("objectId",selected.getString("objectId"));}}
        }
        String selected=editor.getJSONArray("selectedNodeIds")!=null&&editor.getJSONArray("selectedNodeIds").size()==1?editor.getJSONArray("selectedNodeIds").getString(0):editor.getString("targetNodeId");
        var prepared=PrevizSceneEdits.prepare(scene,edits,selected,frame);
        // Read-only full validation before an execution checkpoint or any document write.
        apply(actor,project,scene,prepared);
        return JSONObject.of("projectId",project,"editorVersion",editorVersion,"baseDocumentVersion",version,
                "sourceVersion",observed.get("sourceVersion"),"edits",prepared);
    }
    public JSONObject execute(Long actor,String project,JSONObject prepared,String operation,Runnable beforePublish)throws IOException {
        projects.requireWrite(actor,project);JSONObject existing=reconcile(actor,project,operation);if(existing!=null)return existing;
        var head=documents.current(actor,project);
        if(!Objects.equals(head.get("currentSourceVersion"),prepared.get("sourceVersion")))throw new IllegalArgumentException("生成源码已变化，原编辑请求未覆盖新版本");
        JSONObject current=documents.read(actor,project,null),document=current.getJSONObject("document");
        JSONObject scene=apply(actor,project,document.getJSONObject("scene"),prepared.getJSONArray("edits"));
        document.put("scene",scene);
        JSONObject result=documents.save(actor,project,current.getString("documentVersion"),prepared.getString("sourceVersion"),document,version->{
            beforePublish.run();
            try{writeJournal(actor,project,operation,JSONObject.of("state","PREPARED","projectId",project,"operationId",operation,
                    "editorVersion",prepared.get("editorVersion"),"baseDocumentVersion",prepared.get("baseDocumentVersion"),"documentVersion",version));}
            catch(IOException unavailable){throw new UncheckedIOException(unavailable);}
            beforePublish.run();
        });
        return JSONObject.of("state","APPLIED","projectId",project,"operationId",operation,"documentVersion",result.getString("documentVersion"),
                "baseDocumentVersion",prepared.get("baseDocumentVersion"),"editorVersion",prepared.get("editorVersion"),"editCount",prepared.getJSONArray("edits").size(),"rebuilt",false);
    }
    public JSONObject reconcile(Long actor,String project,String operation)throws IOException {
        PrevizSourceStore journal=journal(operation);String version=journal.currentVersion(actor,project);if(version==null)return null;
        JSONObject receipt=JSON.parseObject(journal.read(actor,project,version).get("receipt.json"));
        // read() checks publication history, so later edits or restoring another scene cannot erase this evidence.
        try{documents.read(actor,project,receipt.getString("documentVersion"));}
        catch(IOException absent){if("DOCUMENT_NOT_FOUND".equals(absent.getMessage()))return JSONObject.of("state","NOT_APPLIED","projectId",project,"operationId",operation);throw absent;}
        receipt.put("state","APPLIED");receipt.put("rebuilt",false);return receipt;
    }
    private JSONObject apply(Long actor,String project,JSONObject scene,com.alibaba.fastjson2.JSONArray edits)throws IOException {
        Map<String,JSONObject> assets=new HashMap<>();for(Object raw:scene.getJSONArray("assets")){JSONObject asset=(JSONObject)raw;assets.put(asset.getString("id"),asset);}
        Map<String,Set<String>> active=new HashMap<>(),roots=new HashMap<>();
        try{return PrevizSceneEdits.apply(scene,edits,(node,id)->{
            String assetId=node.getString("assetId");JSONObject asset=assets.get(assetId);if(asset==null)return false;
            String key=assetId+":"+JSON.toJSONString(node.get("assetObjectIds"));
            try{if(!active.containsKey(key))active.put(key,blobs.editableObjectIds(actor,project,asset.getJSONObject("blob").getString("sha256"),node.getList("assetObjectIds",String.class)));return active.get(key).contains(id);}
            catch(IOException invalid){throw new UncheckedIOException(invalid);}
        },node->{
            if(node.containsKey("assetObjectIds"))return Set.copyOf(node.getList("assetObjectIds",String.class));
            String id=node.getString("assetId");JSONObject asset=assets.get(id);if(asset==null)throw new IllegalArgumentException("模型资产不存在");
            try{if(!roots.containsKey(id))roots.put(id,blobs.nativeRootObjectIds(actor,project,asset.getJSONObject("blob").getString("sha256")));if(roots.get(id).isEmpty())throw new IllegalArgumentException("此模型缺少可编辑的对象标识");return roots.get(id);}
            catch(IOException invalid){throw new UncheckedIOException(invalid);}
        });}catch(UncheckedIOException invalid){throw invalid.getCause();}
    }
    private PrevizSourceStore journal(String operation)throws IOException {
        if(operation==null||!operation.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("编辑幂等标识无效");
        return new PrevizSourceStore(journals.resolve(operation),projects);
    }
    private void writeJournal(Long actor,String project,String operation,JSONObject receipt)throws IOException {
        PrevizSourceStore store=journal(operation);String current=store.currentVersion(actor,project);
        store.save(actor,project,current,Map.of("receipt.json",receipt.toJSONString().getBytes(StandardCharsets.UTF_8)));
    }
}
