package com.aid.canvas.publication.runtime;

import com.aid.aid.domain.AidComicProject;
import com.aid.runtime.domain.RuntimeRunLifecycle;
import com.aid.canvas.domain.CanvasEntities.*;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.mapper.CanvasPublicationRuntimeMapper;
import com.aid.canvas.publication.domain.CanvasPublicationState;
import com.aid.canvas.publication.mapper.CanvasPublicationMapper;
import com.aid.canvas.publication.service.CanvasPublicationPort;
import com.aid.canvas.publication.service.CanvasPublicationBundles;
import com.aid.canvas.publication.service.CanvasSceneRecordingPort;
import org.springframework.beans.factory.ObjectProvider;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasProjectService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 将审核目录与画布权限、冻结资源和独立副本相连。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CanvasPublicationRuntimeAdapter implements CanvasPublicationPort {
    private final CanvasMapper canvas;
    private final CanvasPublicationRuntimeMapper runtime;
    private final CanvasPublicationMapper publications;
    private final CanvasAccessService access;
    private final CanvasProjectService projects;
    private final com.aid.canvas.publication.service.CanvasPublicationCapabilityPolicy capabilities;
    private final MediaUrlResolver urls;
    private final ObjectProvider<CanvasSceneRecordingPort> recordingReferences;
    @Value("${canvas.publication.max-copy-resources:2000}") private int maxCopyResources=2000;
    @Value("${canvas.publication.max-total-bytes:1073741824}") private long maxCopyBytes=1073741824L;
    private static final Set<String> NODE_TYPES = Set.of("IMAGE", "VIDEO", "FRAME_BREAKDOWN", "SMART_EDIT", "TEXT",
            "STORYBOARD_SCRIPT", "DIRECTOR", "STYLE_LIBRARY", "OFFICIAL_ASSET", "VOICE", "NOTE");
    private static final Set<String> RUNTIME_KEYS = Set.of("taskid", "remotetaskid", "taskstatus", "status", "progress",
            "selectedresult", "currentresult", "operationrunid", "audiorunid", "storyboardprompttaskid", "storyboardpromptstatus",
            "errormessage", "createdat", "updatedat", "modelcostcredits", "modelisfree", "canvascurrenttaskid",
            "canvascurrentresultid", "canvaslatesttaskstatus", "canvasaudioselectionversion", "jobid", "referenceid");
    private static final Set<String> NODE_ROOT = Set.of("nodeType", "title", "position", "size", "prompt", "negativePrompt",
            "textContent", "parameters", "resourceIds", "inputPorts", "metadata");

    @Override public void requireOwner(Long projectId, Long userId) { access.requireOwner(projectId, userId); }
    @Override public void requireEditor(Long projectId,Long userId) { access.requireRole(projectId,userId,"OWNER","EDITOR"); }
    @Override public void requireReader(Long projectId, Long userId) { access.requireProject(projectId, userId); }
    @Override public boolean projectExists(Long projectId) { return canvas.selectCanvasProject(projectId) != null; }
    @Override public long revision(Long projectId) { return workspace(projectId).getRevision(); }
    @Override public void requireCopyCapacity(Long userId,String snapshotJson) {
        JSONObject source=snapshot(snapshotJson);
        capabilities.requirePortable(source.getJSONArray("objects"));
        access.requireFeature(userId,null,"canvas.enabled");
        Map<String,Object> configuration=access.config();
        if(configuration==null) configuration=Map.of();
        int configuredProjects=integer(configuration.get("maxProjectsPerOwner"),20);
        int projectLimit=Math.min(configuredProjects,access.entitlementLimit(userId,null,"canvas.project.max",configuredProjects));
        if(projectLimit<=0||canvas.countOwnedCanvasProjects(userId)>=projectLimit) fail("自由画布项目数量已达当前权益上限");
        int configuredNodes=integer(configuration.get("maxNodesPerWorkspace"),1000);
        int nodeLimit=Math.min(configuredNodes,access.entitlementLimit(userId,null,"canvas.node.max",configuredNodes));
        long nodes=source.getJSONArray("objects").stream().map(CanvasPublicationSceneValidator::object).filter(o->"NODE".equals(o.getString("objectType"))).count();
        if(nodes>nodeLimit) fail("复制画布节点数超过当前权益上限");
        if(maxCopyResources<=0||source.getJSONArray("resources").size()>maxCopyResources) fail("复制素材数量超过当前环境上限");
        long bytes=0;
        for(Object raw:source.getJSONArray("resources")) {
            JSONObject resource=CanvasPublicationSceneValidator.object(raw);
            Long size=resource.getLong("fileSize");
            if(size==null||size<=0||maxCopyBytes<=0||size>maxCopyBytes-bytes) fail("复制素材大小缺失或超过当前环境容量");
            bytes+=size;
        }
    }
    @Override public JSONObject authorizeResource(Long resourceId,Long userId) {
        CanvasResource resource=canvas.selectCanvasResource(resourceId);
        if(resource==null) fail("素材不存在或已删除");
        access.requireWorkspace(resource.getWorkspaceId(),userId,"OWNER","EDITOR","VIEWER");
        if(resource.getStorageUrl()==null||!resource.getStorageUrl().matches("publication-file:[0-9a-f]{32}/[0-9a-f]{64}"))
            fail("该素材不是独立复制文件");
        return JSONObject.of("storageUrl",resource.getStorageUrl(),"mimeType",resource.getMimeType()==null?"application/octet-stream":resource.getMimeType());
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Capture capture(Long projectId, Long userId, long expectedRevision, Long videoResourceId,
                           Long coverResourceId, String sceneDocumentJson) {
        AidComicProject project = access.requireOwner(projectId, userId);
        Workspace workspace = lock(projectId);
        if (workspace.getRevision() != expectedRevision) fail("画布版本已变化，请重新保存后发布");
        if (canvas.countRunningCanvasTasks(projectId) > 0) fail("画布仍有运行中的生成任务，不能冻结发布");
        if (runtime.countUnfinishedRuns(workspace.getId(), RuntimeRunLifecycle.TERMINAL_STATUSES) > 0) fail("画布仍有未结束的助手执行，不能冻结发布");
        runtime.lockSelections(workspace.getId());
        List<CanvasObject> objects = canvas.selectWorkspaceObjects(workspace.getId(), false);
        JSONObject document = sceneDocumentJson == null ? null : JSON.parseObject(sceneDocumentJson);
        Set<String> nodeIds = nodeIds(objects), scenes = sceneIds(objects);
        if (!scenes.isEmpty() && document == null) fail("预演台完整场景尚未保存，不能发布");
        if (document == null) document = JSONObject.of("schemaVersion", 1, "scenes", new JSONArray());
        Set<String> resourceIds = CanvasPublicationSceneValidator.validate(document, nodeIds, scenes);
        requireRecordingReferences(projectId,workspace.getId(),document);
        for (JSONObject scene : sceneVersions(document)) {
            JSONObject modeling = scene.getJSONObject("modeling");
            if (modeling != null) modeling.remove("adoption");
        }
        JSONArray frozenObjects = new JSONArray();
        for (CanvasObject object : objects) {
            JSONObject content = clean(JSON.parseObject(object.getContentJson()));
            validateObject(object.getObjectType(), content, nodeIds);
            collectReferences(content, resourceIds);
            frozenObjects.add(JSONObject.of("objectId", object.getObjectId(), "objectType", object.getObjectType(), "content", content));
        }
        JSONArray selected = new JSONArray();
        capabilities.requirePortable(frozenObjects);
        for (Map<String,Object> row : canvas.selectWorkspaceCurrentResults(workspace.getId())) {
            String nodeId = Objects.toString(row.get("objectId"), "");
            if (!nodeIds.contains(nodeId)) fail("当前结果引用了无效节点");
            if (row.get("resourceId") == null) fail("当前选中结果尚未登记素材，请等待结果持久化完成");
            String id = decimal(row.get("resourceId")); resourceIds.add(id);
            selected.add(JSONObject.of("objectId", nodeId, "resourceId", id, "mediaType", row.get("mediaType")));
        }
        resourceIds.add(decimal(videoResourceId)); resourceIds.add(decimal(coverResourceId));
        expandBundles(workspace.getId(),resourceIds);
        Map<String,CanvasResource> resources = resources(workspace.getId(), resourceIds);
        validateBundles(resources);validateSceneModels(document,resources);
        for(CanvasResource resource:resources.values()) {
            if(!Objects.equals(userId,resource.getOwnerUserId())) fail("存在缺少发布授权的成员素材："+resource.getId());
            if(!Set.of("UPLOAD","MEDIA_TASK","MEDIA_RESULT","PUBLICATION_COPY").contains(Objects.toString(resource.getSourceType(),"")))
                fail("素材来源缺少明确的公开与复制授权："+resource.getId());
        }
        CanvasResource video = resources.get(decimal(videoResourceId)), cover = resources.get(decimal(coverResourceId));
        if (!"VIDEO".equals(video.getMediaType()) || !"IMAGE".equals(cover.getMediaType())) fail("发布视频和封面素材类型不匹配");
        for(Object raw:selected) { JSONObject selection=CanvasPublicationSceneValidator.object(raw);
            if(!Objects.equals(resources.get(selection.getString("resourceId")).getMediaType(),selection.getString("mediaType")))
                fail("当前选中结果与已登记素材类型不一致");
        }
        JSONArray frozenResources = new JSONArray();
        resources.forEach((id,r) -> frozenResources.add(JSONObject.of("resourceId", id, "mediaType", r.getMediaType(),
                "storageUrl", r.getStorageUrl(), "fileSize", r.getFileSize(), "mimeType", r.getMimeType(), "metadataJson", mediaMetadata(r.getMetadataJson(),r.getMediaType()))));
        JSONObject snapshot = JSONObject.of("schemaVersion",1,"sourceProjectId",Long.toString(projectId),
                "projectName",project.getProjectName(),"projectDesc",Objects.toString(project.getProjectDesc(),""),"objects",frozenObjects,"resources",frozenResources,
                "currentResults",selected,"sceneDocument",document,"videoResourceId",decimal(videoResourceId),"coverResourceId",decimal(coverResourceId));
        // 校验嵌套对象引用，避免复制时才发现断裂的旧引用。
        validateReferences(snapshot.getJSONArray("objects"),nodeIds,groupIds(objects),resourceIds,scenes);
        String json = snapshot.toJSONString();
        if (json.getBytes(StandardCharsets.UTF_8).length > 16*1024*1024) fail("发布快照不能超过16MB");
        return new Capture(expectedRevision, project.getProjectName(), json,
                internalUrl(video.getStorageUrl()), internalUrl(cover.getStorageUrl()));
    }

    @Override
    public void lockSceneEditor(Long projectId, Long userId) {
        requireEditor(projectId,userId);
        if(runtime.lockSceneEditor(projectId,userId)==null) fail("项目不存在或场景编辑权限已被撤销");
    }

    @Override
    public Map<String, String> workspaceSceneReferences(Long projectId) {
        return sceneReferences(canvas.selectWorkspaceObjects(workspace(projectId).getId(), false));
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void validateSceneDocument(Long projectId, Long userId, JSONObject document, Map<String, String> legacyGaps) {
        lockSceneEditor(projectId,userId);
        Workspace workspace = lock(projectId);
        List<CanvasObject> objects = canvas.selectWorkspaceObjects(workspace.getId(),false);
        Set<String> included = new HashSet<>();
        JSONArray scenes = document.getJSONArray("scenes");
        if (scenes != null) for (Object row : scenes) {
            included.add(CanvasPublicationSceneValidator.key(CanvasPublicationSceneValidator.object(row).get("id")));
        }
        Set<String> required = new HashSet<>();
        sceneReferences(objects).forEach((node, scene) -> {
            if (!included.contains(scene) && Objects.equals(legacyGaps.get(node), scene)) return;
            required.add(CanvasPublicationSceneValidator.key(scene));
        });
        Set<String> references = CanvasPublicationSceneValidator.validate(document,nodeIds(objects),required);
        if (JSON.toJSONBytes(document).length>8*1024*1024) fail("完整场景文档不能超过8MB");
        requireRecordingReferences(projectId,workspace.getId(),document);
        expandBundles(workspace.getId(),references);Map<String,CanvasResource> assets=resources(workspace.getId(),references);validateBundles(assets);validateSceneModels(document,assets);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long copy(String snapshotJson, Long userId, String projectName, String operationId) {
        requireCopyCapacity(userId,snapshotJson);
        JSONObject source = snapshot(snapshotJson);
        JSONArray sourceObjects = source.getJSONArray("objects"), sourceResources = source.getJSONArray("resources");
        for(Object raw:sourceResources) {
            String storage=CanvasPublicationSceneValidator.object(raw).getString("storageUrl");
            if(!storage.startsWith("publication-file:")&&!urls.isSiteMediaUrl(storage)) fail("复制准备资源不属于本站可信存储");
        }
        CanvasRequests.CreateProject request = new CanvasRequests.CreateProject();
        String name = projectName == null || projectName.isBlank() ? source.getString("projectName") : projectName.trim();
        if(name==null||name.isBlank()||name.length()>100) fail("复制后的项目名称无效");
        String description=Objects.toString(source.getString("projectDesc"),"");
        if(description.length()>500) fail("复制后的项目简介超出长度限制");
        request.setProjectName(name); request.setProjectDesc(description);
        request.setOperationId("publication_"+hash(operationId+":"+snapshotJson).substring(0,48));
        Map<String,Object> created = projects.createProject(request,userId);
        Long projectId = Long.valueOf(created.get("projectId").toString());
        Workspace workspace = lock(projectId);
        if(workspace.getRevision()!=0) fail("复制目标已存在内容，不能覆盖");
        long nodeCount = sourceObjects.stream().map(CanvasPublicationSceneValidator::object).filter(o->"NODE".equals(o.getString("objectType"))).count();
        int limit=access.entitlementLimit(userId,projectId,"canvas.node.max",1000);
        if(nodeCount>limit) fail("复制画布节点数超过当前权益上限");
        Map<String,String> resourceMap = new LinkedHashMap<>(), objectMap=new LinkedHashMap<>(), sceneMap=new LinkedHashMap<>();
        String coverUrl=null;
        for(Object raw:sourceResources) {
            JSONObject old = CanvasPublicationSceneValidator.object(raw);
            String oldId=decimal(old.get("resourceId"));
            if(resourceMap.containsKey(oldId)) fail("冻结资源标识重复");
            CanvasResource resource = new CanvasResource(); resource.setProjectId(projectId); resource.setWorkspaceId(workspace.getId());
            resource.setOwnerUserId(userId); resource.setSourceType("PUBLICATION_COPY"); resource.setSourceId(Long.valueOf(oldId));
            resource.setMediaType(old.getString("mediaType")); resource.setStorageUrl(old.getString("storageUrl"));
            resource.setFileSize(old.getLong("fileSize")); resource.setMimeType(old.getString("mimeType"));
            resource.setMetadataJson(old.getString("metadataJson")); resource.setStatus("ACTIVE");
            if(canvas.insertCanvasResource(resource)!=1) fail("复制资源写入失败");
            resourceMap.put(oldId,Long.toString(resource.getId()));
            if(oldId.equals(source.getString("coverResourceId"))) coverUrl=CanvasPublicationResourceUrl.resolve(resource.getId(),resource.getStorageUrl());
        }
        for(Object raw:sourceResources){JSONObject old=(JSONObject)raw;if(CanvasPublicationBundles.read(old.getString("metadataJson"))!=null&&runtime.bundleMetadata(Long.valueOf(resourceMap.get(old.getString("resourceId"))),workspace.getId(),CanvasPublicationBundles.remap(old.getString("metadataJson"),resourceMap))!=1)fail("模型依赖标识重映射失败");}
        if(coverUrl==null||canvas.updateCanvasProject(projectId,userId,null,null,coverUrl)!=1) fail("复制项目封面写入失败");
        for(Object raw:sourceObjects) {
            JSONObject old = CanvasPublicationSceneValidator.object(raw);
            String id=old.getString("objectId");
            if(id==null||objectMap.putIfAbsent(id,uuid())!=null) fail("冻结对象标识重复或无效");
        }
        JSONObject document=source.getJSONObject("sceneDocument");
        for(Object raw:document.getJSONArray("scenes")) { String id=CanvasPublicationSceneValidator.object(raw).getString("id"); sceneMap.put(id,uuid()); }
        JSONArray copiedObjects = JSON.parseArray(sourceObjects.toJSONString());
        remap(copiedObjects,objectMap,resourceMap,sceneMap,false);
        int nodes=0,edges=0,groups=0; long bytes=0;
        for(Object raw:copiedObjects) {
            JSONObject old=CanvasPublicationSceneValidator.object(raw); String type=old.getString("objectType");
            CanvasObject object=new CanvasObject(); object.setProjectId(projectId); object.setWorkspaceId(workspace.getId());
            object.setObjectId(objectMap.get(old.getString("objectId"))); object.setObjectType(type); object.setObjectVersion(1L);
            object.setLockFence(0L); object.setDeleted(0); object.setLastActorUserId(userId);
            object.setContentJson(old.getJSONObject("content").toJSONString()); object.setContentHash(hash(object.getContentJson()));
            canvas.upsertObjectFence(workspace.getId(),object.getObjectId(),0L);
            if(canvas.insertCanvasObject(object)!=1) fail("复制对象写入失败");
            bytes+=object.getContentJson().getBytes(StandardCharsets.UTF_8).length;
            switch(type) { case "NODE"->nodes++; case "EDGE"->edges++; case "GROUP"->groups++; default->fail("冻结对象类型不支持"); }
        }
        for(Object raw:source.getJSONArray("currentResults")) {
            JSONObject old=CanvasPublicationSceneValidator.object(raw);
            String node=mapped(objectMap,old.getString("objectId")), resource=mapped(resourceMap,decimal(old.get("resourceId")));
            if(runtime.insertResult(workspace.getId(),node,Long.valueOf(resource),old.getString("mediaType"))!=1) fail("复制当前素材失败");
        }
        JSONObject copiedDocument=JSON.parseObject(document.toJSONString());
        remap(copiedDocument,objectMap,resourceMap,sceneMap,true);
        CanvasPublicationSceneValidator.stripRecordingBindings(copiedDocument);
        for(Object raw:copiedDocument.getJSONArray("scenes")) { JSONObject scene=CanvasPublicationSceneValidator.object(raw); scene.put("id",mapped(sceneMap,scene.getString("id"))); }
        if(canvas.applyWorkspaceDelta(workspace.getId(),nodes,edges,groups,bytes)!=1) fail("复制工作区统计失败");
        publications.ensure(projectId); CanvasPublicationState state=publications.lock(projectId);
        state.setSceneDocumentJson(copiedDocument.toJSONString()); state.setSceneVersion(1L);
        if(publications.save(state)!=1) fail("复制场景文档写入冲突");
        saveIdentities(workspace.getId(),"OBJECT",objectMap);
        saveIdentities(workspace.getId(),"RESOURCE",resourceMap);
        saveIdentities(workspace.getId(),"SCENE",sceneMap);
        return projectId;
    }

    @Override public JSONObject preview(String snapshotJson) {
        JSONObject snapshot=snapshot(snapshotJson); JSONArray resources=new JSONArray();
        for(Object raw:snapshot.getJSONArray("resources")) {
            JSONObject r=CanvasPublicationSceneValidator.object(raw);
            JSONObject row=JSONObject.of("resourceId",r.getString("resourceId"),"mediaType",r.getString("mediaType"),"fileSize",r.get("fileSize"),"mimeType",r.get("mimeType"));
            JSONObject bundle=CanvasPublicationBundles.read(r.getString("metadataJson"));if(bundle!=null)row.put("bundle",bundle);resources.add(row);
        }
        return JSONObject.of("schemaVersion",1,"projectName",snapshot.getString("projectName"),"videoResourceId",snapshot.getString("videoResourceId"),"coverResourceId",snapshot.getString("coverResourceId"),"objects",snapshot.getJSONArray("objects"),
                "resources",resources,"currentResults",snapshot.getJSONArray("currentResults"),"sceneDocument",snapshot.getJSONObject("sceneDocument"));
    }

    /** 仅比较服务端提供的快照；身份映射缺失或产生歧义时明确返回不可比较。 */
    @Override public JSONObject compare(String referenceSnapshot,String currentSnapshot,Long currentProjectId) {
        JSONObject reference=snapshot(referenceSnapshot), current=snapshot(currentSnapshot);
        if(!Long.toString(currentProjectId).equals(current.getString("sourceProjectId")))
            return JSONObject.of("available",false,"reason","当前快照项目不匹配");
        Map<String,String> objects=identity(snapshotIds(current,"objects","objectId"));
        Map<String,String> resources=identity(snapshotIds(current,"resources","resourceId"));
        Set<String> sceneIds=new HashSet<>();
        for(Object raw:current.getJSONObject("sceneDocument").getJSONArray("scenes")) sceneIds.add(CanvasPublicationSceneValidator.object(raw).getString("id"));
        Map<String,String> scenes=identity(sceneIds);
        if(!Long.toString(currentProjectId).equals(reference.getString("sourceProjectId"))) {
            List<Map<String,Object>> mappings=runtime.identities(currentProjectId);
            if(mappings==null||mappings.isEmpty()) return JSONObject.of("available",false,"reason","缺少复制身份映射");
            for(Map<String,Object> row:mappings) {
                String source=Objects.toString(row.get("sourceId"),null), target=Objects.toString(row.get("targetId"),null);
                Map<String,String> index=switch(Objects.toString(row.get("identityType"),"")) {case "OBJECT"->objects;case "RESOURCE"->resources;case "SCENE"->scenes;default->null;};
                if(index==null||source==null||target==null) return JSONObject.of("available",false,"reason","复制映射格式不支持");
                if(index.containsKey(target)) index.put(target,source);
            }
        }
        if(new HashSet<>(objects.values()).size()!=objects.size()||new HashSet<>(resources.values()).size()!=resources.size()
                ||new HashSet<>(scenes.values()).size()!=scenes.size()) return JSONObject.of("available",false,"reason","新旧身份映射发生冲突");
        JSONObject normalized=JSON.parseObject(current.toJSONString());
        remap(normalized.getJSONArray("objects"),objects,resources,scenes,false);
        for(Object raw:normalized.getJSONArray("objects")) {JSONObject row=CanvasPublicationSceneValidator.object(raw);row.put("objectId",mapped(objects,row.getString("objectId")));}
        remap(normalized.getJSONObject("sceneDocument"),objects,resources,scenes,true);
        for(Object raw:normalized.getJSONObject("sceneDocument").getJSONArray("scenes")) {JSONObject row=CanvasPublicationSceneValidator.object(raw);row.put("id",mapped(scenes,row.getString("id")));}
        for(Object raw:normalized.getJSONArray("currentResults")) {
            JSONObject row=CanvasPublicationSceneValidator.object(raw);row.put("objectId",mapped(objects,row.getString("objectId")));
            row.put("resourceId",mapped(resources,row.getString("resourceId")));
        }
        Map<String,JSONObject> before=byId(reference.getJSONArray("objects"),"objectId"), after=byId(normalized.getJSONArray("objects"),"objectId");
        List<String> added=new ArrayList<>(),modified=new ArrayList<>(),removed=new ArrayList<>();
        after.forEach((id,value)->{if(!before.containsKey(id)) added.add(id);else if(!value.equals(before.get(id))) modified.add(id);});
        before.keySet().forEach(id->{if(!after.containsKey(id))removed.add(id);});
        Collections.sort(added);Collections.sort(modified);Collections.sort(removed);
        CanvasPublicationSceneValidator.stripRecordingBindings(reference.getJSONObject("sceneDocument"));
        CanvasPublicationSceneValidator.stripRecordingBindings(normalized.getJSONObject("sceneDocument"));
        boolean sameScenes=reference.getJSONObject("sceneDocument").equals(normalized.getJSONObject("sceneDocument"));
        boolean sameResults=byId(reference.getJSONArray("currentResults"),"objectId").equals(byId(normalized.getJSONArray("currentResults"),"objectId"));
        Map<String,JSONObject> referenceHashes=resourceHashes(reference,null), currentHashes=resourceHashes(normalized,resources);
        if(referenceHashes==null||currentHashes==null) return JSONObject.of("available",false,"reason","资源缺少可信内容摘要");
        boolean sameResources=referenceHashes.equals(currentHashes);
        boolean samePresentation=Objects.equals(reference.getString("videoResourceId"),mapped(resources,current.getString("videoResourceId")))
                &&Objects.equals(reference.getString("coverResourceId"),mapped(resources,current.getString("coverResourceId")));
        return JSONObject.of("available",true,"sameContent",added.isEmpty()&&modified.isEmpty()&&removed.isEmpty()&&sameScenes&&sameResults&&sameResources&&samePresentation,
                "addedObjectIds",added,"modifiedObjectIds",modified,"removedObjectIds",removed,
                "sameScenes",sameScenes,"sameCurrentResults",sameResults,"sameResources",sameResources,"samePresentation",samePresentation);
    }
    private static Set<String> snapshotIds(JSONObject snapshot,String array,String field) {
        Set<String> ids=new HashSet<>();for(Object raw:snapshot.getJSONArray(array)) ids.add(CanvasPublicationSceneValidator.object(raw).getString(field));return ids;
    }
    private static Map<String,JSONObject> byId(JSONArray rows,String field) {
        Map<String,JSONObject> result=new TreeMap<>();for(Object raw:rows) {JSONObject row=CanvasPublicationSceneValidator.object(raw);result.put(row.getString(field),row);}return result;
    }
    private static Map<String,JSONObject> resourceHashes(JSONObject snapshot,Map<String,String> mapping) {
        Map<String,JSONObject> result=new TreeMap<>();
        for(Object raw:snapshot.getJSONArray("resources")) {
            JSONObject row=CanvasPublicationSceneValidator.object(raw);String hash=row.getString("contentHash");
            if(hash==null||!hash.matches("[a-f0-9]{64}"))return null;
            String metadata=row.getString("metadataJson");
            if(mapping!=null&&CanvasPublicationBundles.read(metadata)!=null)metadata=CanvasPublicationBundles.remap(metadata,mapping);
            String id=row.getString("resourceId");result.put(mapping==null?id:mapped(mapping,id),JSONObject.of("mediaType",row.getString("mediaType"),"hash",hash,"metadata",metadata==null?null:JSON.parseObject(metadata)));
        }
        return result;
    }

    private void requireRecordingReferences(Long projectId,Long workspaceId,JSONObject document) {
        List<CanvasSceneRecordingPort.Reference> references=CanvasPublicationSceneValidator.recordingReferences(document);
        if(references.isEmpty())return;
        CanvasSceneRecordingPort port=recordingReferences.getIfAvailable();
        if(port==null)fail("当前服务无法核验录制引用");
        port.requireAppliedTakes(projectId,workspaceId,references);
    }
    @Override public void requireBundleEditor(Long workspaceId,Long userId){access.requireWorkspace(workspaceId,userId,"OWNER","EDITOR");}
    @Override @Transactional(readOnly=true)
    public JSONArray readBundles(Long workspaceId,Long userId,List<Long> resourceIds){
        Workspace workspace=access.requireWorkspace(workspaceId,userId,"OWNER","EDITOR","VIEWER");
        List<Long> requested=new ArrayList<>(new LinkedHashSet<>(resourceIds));
        Map<Long,CanvasResource> roots=new LinkedHashMap<>();
        for(CanvasResource row:canvas.selectCanvasResources(workspaceId,requested))roots.put(row.getId(),row);
        Map<Long,JSONObject> bundles=new LinkedHashMap<>();Set<Long> allIds=new LinkedHashSet<>();
        for(Long id:requested){
            CanvasResource row=roots.get(id);
            if(row==null||!Objects.equals(row.getWorkspaceId(),workspaceId)||!Objects.equals(row.getProjectId(),workspace.getProjectId())||!"ACTIVE".equals(row.getStatus())||!Set.of("MODEL","SCENE_PACKAGE").contains(Objects.toString(row.getMediaType(),"")))fail("模型根资源不存在、已停用或不属于当前工作区");
            JSONObject bundle=CanvasPublicationBundles.read(row.getMetadataJson());if(bundle==null)fail("模型资源闭包缺失");
            JSONObject entry=null;
            for(Object raw:bundle.getJSONArray("files")){
                JSONObject file=(JSONObject)raw;allIds.add(Long.valueOf(file.getString("resourceId")));
                if(Objects.equals(file.getString("path"),bundle.getString("rootPath")))entry=file;
            }
            if(entry==null||!id.toString().equals(entry.getString("resourceId")))fail("模型入口资源身份不一致");
            if(allIds.size()>maxCopyResources)fail("模型依赖读取数量超限，请分批读取");
            bundles.put(id,bundle);
        }
        Map<Long,CanvasResource> files=new HashMap<>();List<Long> ids=new ArrayList<>(allIds);
        for(int offset=0;offset<ids.size();offset+=500)for(CanvasResource row:canvas.selectCanvasResources(workspaceId,ids.subList(offset,Math.min(offset+500,ids.size()))))files.put(row.getId(),row);
        JSONArray result=new JSONArray();Map<Long,String> signed=new HashMap<>();
        for(Long id:requested){
            CanvasResource root=roots.get(id);JSONObject bundle=bundles.get(id);
            for(Object raw:bundle.getJSONArray("files")){
                JSONObject file=(JSONObject)raw;Long fileId=Long.valueOf(file.getString("resourceId"));CanvasResource row=files.get(fileId);
                if(row==null||!Objects.equals(row.getWorkspaceId(),workspaceId)||!Objects.equals(row.getProjectId(),workspace.getProjectId())||!"ACTIVE".equals(row.getStatus())||(!fileId.equals(id)&&!"MODEL_DEP".equals(row.getMediaType())))fail("模型依赖资源不存在或归属不一致");
                file.put("resourceUrl",signed.computeIfAbsent(fileId,key->com.aid.canvas.runtime.CanvasModelAccessUrl.resolve(key,row.getStorageUrl(),row.getMediaType())));
            }
            result.add(JSONObject.of("resourceId",id.toString(),"projectId",workspace.getProjectId().toString(),"workspaceId",workspaceId.toString(),"mediaType",root.getMediaType(),"bundle",bundle,"replayed",true));
        }
        return result;
    }
    @Override public JSONObject existingBundle(Long workspaceId,Long userId,String operation,String hash){
        requireBundleEditor(workspaceId,userId);String previous=runtime.bundleHash(workspaceId,userId,operation);if(previous!=null&&!previous.equals(hash))fail("operationId 已用于其他模型包");Long root=runtime.bundle(workspaceId,userId,operation,hash);return root==null?null:bundleResult(root,true);
    }
    @Override @Transactional(rollbackFor=Exception.class) public JSONObject registerBundle(Long workspaceId,Long userId,String operation,String hash,JSONObject input){
        Workspace workspace=canvas.selectWorkspace(workspaceId);if(workspace==null||runtime.lockSceneEditor(workspace.getProjectId(),userId)==null)fail("模型登记权限已变化");
        workspace=canvas.selectWorkspaceForUpdate(workspaceId);if(workspace==null)fail("工作区已失效");
        String existing=runtime.bundleHash(workspaceId,userId,operation);if(existing!=null){if(!existing.equals(hash))fail("operationId 已用于其他模型包");return bundleResult(runtime.bundle(workspaceId,userId,operation,hash),true);}
        Map<String,String> ids=new LinkedHashMap<>();Long rootId=null;
        for(Object raw:input.getJSONArray("files")){
            JSONObject file=(JSONObject)raw;CanvasResource r=new CanvasResource();r.setProjectId(workspace.getProjectId());r.setWorkspaceId(workspaceId);r.setOwnerUserId(userId);r.setSourceType("UPLOAD");r.setStatus("ACTIVE");
            r.setMediaType(file.getString("path").equals(input.getString("rootPath"))?input.getString("mediaType"):"MODEL_DEP");r.setStorageUrl(urls.toRelativePath(file.getString("storageUrl")));r.setFileSize(file.getLong("fileSize"));r.setMimeType(file.getString("mimeType"));
            if(!urls.isSiteMediaUrl(r.getStorageUrl())||canvas.insertCanvasResource(r)!=1)fail("模型资源登记失败");ids.put(file.getString("path"),r.getId().toString());if(!r.getMediaType().equals("MODEL_DEP"))rootId=r.getId();
        }
        if(rootId==null)fail("模型根资源不存在");JSONArray files=new JSONArray();ids.forEach((path,id)->files.add(JSONObject.of("path",path,"resourceId",id)));
        JSONObject bundle=JSONObject.of("format",input.getString("format"),"rootPath",input.getString("rootPath"),"files",files,"references",input.getJSONArray("references"));
        JSONObject resourceMetadata=JSONObject.of("bundle",bundle);
        if(input.containsKey("glbScene")) resourceMetadata.put("glbScene",input.getJSONObject("glbScene"));
        String metadata=resourceMetadata.toJSONString();CanvasPublicationBundles.read(metadata);
        if(runtime.bundleMetadata(rootId,workspaceId,metadata)!=1||runtime.bundleReceipt(workspaceId,userId,operation,hash,rootId)!=1)fail("模型闭包登记失败");return bundleResult(rootId,false);
    }
    private JSONObject bundleResult(Long rootId,boolean replayed){
        CanvasResource root=canvas.selectCanvasResource(rootId);if(root==null)fail("模型根资源已删除");JSONObject bundle=CanvasPublicationBundles.read(root.getMetadataJson());if(bundle==null)fail("模型闭包缺失");
        for(Object raw:bundle.getJSONArray("files")){JSONObject file=(JSONObject)raw;CanvasResource r=canvas.selectCanvasResource(Long.valueOf(file.getString("resourceId")));if(r==null||!Objects.equals(r.getWorkspaceId(),root.getWorkspaceId())||!"ACTIVE".equals(r.getStatus()))fail("模型依赖已删除");file.put("resourceUrl",com.aid.canvas.runtime.CanvasModelAccessUrl.resolve(r.getId(),r.getStorageUrl(),r.getMediaType()));}
        return JSONObject.of("resourceId",rootId.toString(),"projectId",root.getProjectId().toString(),"workspaceId",root.getWorkspaceId().toString(),"mediaType",root.getMediaType(),"bundle",bundle,"replayed",replayed);
    }
    private void expandBundles(Long workspaceId,Set<String> ids){
        Set<String> checked=new HashSet<>();for(int round=0;round<256;round++){
            List<Long> pending=ids.stream().filter(id->!checked.contains(id)).map(Long::valueOf).toList();if(pending.isEmpty())return;
            if(ids.size()>maxCopyResources)fail("模型闭包资源数超限");
            for(int i=0;i<pending.size();i+=500)for(CanvasResource r:canvas.selectCanvasResources(workspaceId,pending.subList(i,Math.min(i+500,pending.size())))){
                checked.add(r.getId().toString());if(Set.of("MODEL","SCENE_PACKAGE").contains(r.getMediaType())){JSONObject b=CanvasPublicationBundles.read(r.getMetadataJson());if(b==null)fail("模型未登记完整依赖");for(Object f:b.getJSONArray("files"))ids.add(((JSONObject)f).getString("resourceId"));}
            }
            if(pending.stream().anyMatch(id->!checked.contains(id.toString())))fail("模型闭包资源缺失");
        }fail("模型闭包层级过深");
    }
    private static void validateBundles(Map<String,CanvasResource> resources){
        for(CanvasResource r:resources.values())if(Set.of("MODEL","SCENE_PACKAGE").contains(r.getMediaType())){
            JSONObject b=CanvasPublicationBundles.read(r.getMetadataJson());if(b==null)fail("模型闭包无效");boolean root=false;
            for(Object raw:b.getJSONArray("files")){JSONObject file=(JSONObject)raw;CanvasResource part=resources.get(file.getString("resourceId"));if(part==null||!Objects.equals(part.getOwnerUserId(),r.getOwnerUserId()))fail("模型依赖无效或没有完整发布权");if(file.getString("path").equals(b.getString("rootPath"))){root=Objects.equals(part.getId(),r.getId());}}
            if(!root)fail("模型根文件不属于闭包");
        }
    }
    private static void validateSceneModels(JSONObject document,Map<String,CanvasResource> resources){
        Set<String> bound=new HashSet<>();
        for (JSONObject scene : sceneVersions(document)) {
            JSONObject modeling = scene.getJSONObject("modeling");
            modelingResources(modeling).forEach((id, type) -> {
                CanvasResource resource = resources.get(id);
                if (resource == null || !type.equals(resource.getMediaType())) fail("建模素材类型与场景不匹配");
                if ("MODEL".equals(type)) {
                    JSONObject bundle = CanvasPublicationBundles.read(resource.getMetadataJson());
                    if (bundle == null || !"GLB".equalsIgnoreCase(bundle.getString("format"))) fail("建模模型须使用完整 GLB");
                    bound.add(id);
                }
            });
            if (modeling != null) CanvasSceneModelingValidator.validateGlbReferences(modeling, id -> {
                CanvasResource resource = resources.get(id);
                return JSON.parseObject(resource.getMetadataJson()).getJSONObject("glbScene");
            });
            if(scene.getJSONArray("modelAssets")!=null)for(Object entry:scene.getJSONArray("modelAssets"))bound.add(((JSONObject)entry).getString("resourceId"));
            if(scene.getJSONArray("modelAssets")!=null)for(Object entry:scene.getJSONArray("modelAssets")){JSONObject model=(JSONObject)entry;CanvasResource resource=resources.get(model.getString("resourceId"));if(resource==null||!"MODEL".equals(resource.getMediaType())||!Objects.equals(CanvasPublicationBundles.read(resource.getMetadataJson()).getString("format"),model.getString("format")))fail("模型资源类型或格式与场景不匹配");}
            List<JSONObject> packages=new ArrayList<>();if(scene.getJSONObject("scenePackage")!=null)packages.add(scene.getJSONObject("scenePackage"));if(scene.getJSONArray("scenePackages")!=null)for(Object p:scene.getJSONArray("scenePackages"))packages.add((JSONObject)p);
            for(JSONObject p:packages){bound.add(p.getString("resourceId"));CanvasResource resource=resources.get(p.getString("resourceId"));if(resource==null||!"SCENE_PACKAGE".equals(resource.getMediaType())||!Objects.equals(CanvasPublicationBundles.read(resource.getMetadataJson()).getString("format"),p.getString("format")))fail("场景包类型或格式不匹配");}
            for(Object entry:scene.getJSONArray("objects")){JSONObject object=(JSONObject)entry;if(object.getJSONObject("scenery")!=null){CanvasResource resource=resources.get(object.getJSONObject("scenery").getString("scenePackageResourceId"));if(resource==null||!"SCENE_PACKAGE".equals(resource.getMediaType()))fail("场景物体未关联完整场景包");}}
        }
        for(var entry:resources.entrySet())if(Set.of("MODEL","SCENE_PACKAGE").contains(entry.getValue().getMediaType())&&!bound.contains(entry.getKey()))fail("模型或场景包必须关联完整场景，不能作为普通节点裸资源发布");
    }
    private static List<JSONObject> sceneVersions(JSONObject document) {
        List<JSONObject> result = new ArrayList<>();
        for (Object raw : document.getJSONArray("scenes")) {
            JSONObject scene = (JSONObject)raw;
            result.add(scene);
            JSONObject modeling = scene.getJSONObject("modeling");
            if (modeling == null) continue;
            for (Object row : modeling.getJSONArray("versions")) {
                JSONObject snapshot = ((JSONObject)row).getJSONObject("directorSnapshot");
                if (snapshot != null) result.add(snapshot);
            }
        }
        return result;
    }
    private static Map<String,String> modelingResources(JSONObject modeling) {
        Map<String,String> result = new LinkedHashMap<>();
        if (modeling == null) return result;
        List<JSONObject> graphs = new ArrayList<>();
        graphs.add(modeling.getJSONObject("scene"));
        for (Object raw : modeling.getJSONArray("versions")) graphs.add(((JSONObject)raw).getJSONObject("scene"));
        for (JSONObject graph : graphs) for (Object raw : graph.getJSONArray("assets")) {
            JSONObject asset = (JSONObject)raw;
            result.put(asset.getString("resourceId"), "GLB".equals(asset.getString("kind")) ? "MODEL" : "IMAGE");
        }
        for (Object raw : modeling.getJSONArray("annotations")) result.put(((JSONObject)raw).getString("resourceId"), "IMAGE");
        if (modeling.containsKey("thumbnailResourceId")) result.put(modeling.getString("thumbnailResourceId"), "IMAGE");
        return result;
    }
    private Workspace workspace(Long projectId) { Workspace w=canvas.selectWorkspaceByProject(projectId); if(w==null) fail("画布工作区不存在"); return w; }
    private String internalUrl(String storage) { return storage!=null&&storage.startsWith("publication-file:")?storage:urls.toFullUrl(storage); }
    private Workspace lock(Long projectId) { Workspace w=canvas.selectWorkspaceForUpdate(workspace(projectId).getId()); if(w==null) fail("画布工作区已删除"); return w; }
    private Map<String,CanvasResource> resources(Long workspaceId,Set<String> ids) {
        Map<String,CanvasResource> result=new LinkedHashMap<>(); List<Long> all=ids.stream().map(Long::valueOf).sorted().toList();
        for(int i=0;i<all.size();i+=500) for(CanvasResource r:runtime.lockResources(workspaceId,all.subList(i,Math.min(i+500,all.size())))) {
            if(r.getStorageUrl()==null||r.getStorageUrl().isBlank()||r.getStorageUrl().matches("(?is)^\\s*(blob:|data:|file:).*")) fail("引用素材尚未持久化");
            result.put(Long.toString(r.getId()),r);
        }
        if(result.size()!=ids.size()) fail("引用素材不存在、已删除或不属于当前画布"); return result;
    }
    private static Set<String> objectIds(List<CanvasObject> objects) { Set<String> ids=new HashSet<>(); for(CanvasObject o:objects) if(!ids.add(o.getObjectId())) fail("对象标识重复"); return ids; }
    private static Set<String> nodeIds(List<CanvasObject> objects) { Set<String> ids=new HashSet<>(); for(CanvasObject o:objects) if("NODE".equals(o.getObjectType())) ids.add(o.getObjectId()); return ids; }
    private static Set<String> groupIds(List<CanvasObject> objects) { Set<String> ids=new HashSet<>(); for(CanvasObject o:objects) if("GROUP".equals(o.getObjectType())) ids.add(o.getObjectId()); return ids; }
    private void saveIdentities(Long workspace,String type,Map<String,String> mapping) {
        mapping.forEach((source,target)->{ if(runtime.insertIdentity(workspace,type,source,target)!=1) fail("复制身份映射写入失败"); });
    }
    private static Set<String> sceneIds(List<CanvasObject> objects) {
        Set<String> ids=new HashSet<>();
        sceneReferences(objects).values().forEach(scene -> {
            if (scene.isBlank()) fail("预演台缺少完整场景关联");
            ids.add(CanvasPublicationSceneValidator.key(scene));
        });
        return ids;
    }

    private static Map<String, String> sceneReferences(List<CanvasObject> objects) {
        Map<String, String> references = new LinkedHashMap<>();
        for(CanvasObject o:objects) if("NODE".equals(o.getObjectType())) {
            JSONObject c=JSON.parseObject(o.getContentJson());
            if("DIRECTOR".equals(c.getString("nodeType"))) {
                JSONObject metadata=c.getJSONObject("metadata"), studio=metadata==null?null:metadata.getJSONObject("studioData");
                Object scene = studio == null ? null : studio.get("directorSceneId");
                references.put(o.getObjectId(), scene instanceof String id ? id : "");
            }
        }
        return references;
    }
    private static JSONObject clean(JSONObject content) {
        JSONObject result=JSON.parseObject(content.toJSONString()); scrub(result); return result;
    }
    private static void scrub(Object value) {
        if(value instanceof Map<?,?> raw) {
            @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)raw;
            map.keySet().removeIf(k->RUNTIME_KEYS.contains(k.toLowerCase(Locale.ROOT)));
            for(String key:new ArrayList<>(map.keySet())) if("canvasTransient".equalsIgnoreCase(key)) {
                if(!Boolean.FALSE.equals(map.get(key))) {
                    log.error("发布快照包含未完成派生节点");
                    fail("派生节点尚未完成");
                }
                map.remove(key);
            }
            for(var e:map.entrySet()) {
                String key=e.getKey().toLowerCase(Locale.ROOT);
                if(key.matches(".*(token|password|secret|api[_-]?key|authorization|sessionid|userid|ownerid|billing|balance|permissions|memberrole).*")) fail("画布含有不能发布的身份或权限字段："+e.getKey());
                if(key.endsWith("url")||key.endsWith("urls")) fail("画布含有未登记地址，请先转为资源引用："+e.getKey());
                scrub(e.getValue());
            }
        } else if(value instanceof Iterable<?> rows) for(Object row:rows) scrub(row);
    }
    private static void validateObject(String type,JSONObject c,Set<String> nodes) {
        CanvasPublicationContentSchema.validate(type,c);
        if("NODE".equals(type)) {
            if(!NODE_TYPES.contains(Objects.toString(c.getString("nodeType"),""))) fail("节点类型尚不支持完整发布："+c.getString("nodeType"));
            if(!NODE_ROOT.containsAll(c.keySet())) fail("节点包含尚未支持的字段");
        } else if("EDGE".equals(type)) {
            CanvasPublicationSceneValidator.fields(c,"edge","sourceObjectId targetObjectId sourceHandle targetHandle label style metadata");
            if(!nodes.contains(Objects.toString(c.getString("sourceObjectId"),""))||!nodes.contains(Objects.toString(c.getString("targetObjectId"),""))) fail("连线端点不存在");
        } else if("GROUP".equals(type)) {
            CanvasPublicationSceneValidator.fields(c,"group","title position size childObjectIds style metadata");
            JSONArray children=c.getJSONArray("childObjectIds"); if(children==null) fail("分组缺少成员列表");
            Set<String> unique=new HashSet<>(); for(Object id:children) if(!nodes.contains(id.toString())||!unique.add(id.toString())) fail("分组成员无效或重复");
        } else fail("对象类型尚不支持发布："+type);
    }
    private static final Set<String> RESOURCE_KEYS=Set.of("resourceId","mediaId","sourceResourceId","backgroundResourceId","canvasPrimaryResourceId",
            "primaryResourceId","thumbnailResourceId","maskResourceId","replacementResourceId","panoramaResourceId","previewImageResourceId","referenceImageResourceId","scenePackageResourceId");
    private static final Set<String> RESOURCE_ARRAY_KEYS=Set.of("resourceIds","canvasResourceIds","audioResourceIds","panoramaHistoryResourceIds","sourceImageResourceIds");
    private static final Set<String> NODE_KEYS=Set.of("sourceObjectId","targetObjectId","sourceNodeId","targetNodeId","nodeId","previzOfNodeId",
            "highlightRefNodeId","gridNodeId","pairedNodeId","replacementNodeId");
    private static final Set<String> NODE_ARRAY_KEYS=Set.of("childObjectIds","sourceNodeIds","inputNodeIds","dismissedSourceIds");
    private static final Set<String> SCENE_KEYS=Set.of("directorSceneId","previzOfSceneId");
    private static void collectReferences(Object value,Set<String> ids) {
        if(value instanceof Map<?,?> map) for(var e:map.entrySet()) {
            String k=e.getKey().toString(); Object v=e.getValue();
            if(RESOURCE_KEYS.contains(k)) ids.add(decimal(v));
            else if(RESOURCE_ARRAY_KEYS.contains(k)) { if(!(v instanceof List<?>)) fail("资源引用必须为数组"); for(Object id:(List<?>)v) ids.add(decimal(id)); }
            else collectReferences(v,ids);
        } else if(value instanceof Iterable<?> rows) for(Object row:rows) collectReferences(row,ids);
    }
    private static void remap(Object value,Map<String,String> objects,Map<String,String> resources,Map<String,String> scenes,boolean sceneDocument) {
        if(value instanceof JSONObject map) for(String key:new ArrayList<>(map.keySet())) {
            Object child=map.get(key); Map<String,String> index=null;
            if (sceneDocument && "modeling".equals(key)) {
                remapModelingResources(CanvasPublicationSceneValidator.object(child), objects, resources, scenes);
                continue;
            }
            if(RESOURCE_KEYS.contains(key)||RESOURCE_ARRAY_KEYS.contains(key)) index=resources;
            else if(SCENE_KEYS.contains(key)) index=scenes;
            else if((NODE_KEYS.contains(key)&&(!sceneDocument||!Set.of("sourceObjectId","targetObjectId").contains(key)))
                    ||NODE_ARRAY_KEYS.contains(key)||(!sceneDocument&&key.equals("groupId")&&!map.containsKey("outputRole"))) index=objects;
            // 外层 objectId 在插入时处理；这里只改内容引用。
            if(index!=null&&!(key.equals("objectId")&&map.containsKey("objectType"))) {
                if(child instanceof List<?> list) { JSONArray out=new JSONArray(); for(Object id:list) out.add(mapped(index,Objects.toString(id,null))); map.put(key,out); }
                else map.put(key,mapped(index,Objects.toString(child,null)));
            } else if(key.equals("sourceKey")&&child instanceof String source) {
                if(source.startsWith("node:")) map.put(key,"node:"+mapped(objects,source.substring(5)));
                else if(source.startsWith("resource:")) map.put(key,"resource:"+mapped(resources,source.substring(9)));
                else fail("引用来源键未转换为持久引用");
            } else remap(child,objects,resources,scenes,sceneDocument);
        } else if(value instanceof Iterable<?> list) for(Object row:list) remap(row,objects,resources,scenes,sceneDocument);
    }
    private static void remapModelingResources(JSONObject modeling, Map<String,String> objects, Map<String,String> resources, Map<String,String> scenes) {
        remapModelingGraph(modeling.getJSONObject("scene"), resources);
        for (Object raw : modeling.getJSONArray("versions")) {
            JSONObject version = (JSONObject)raw;
            remapModelingGraph(version.getJSONObject("scene"), resources);
            remap(version.getJSONObject("directorSnapshot"), objects, resources, scenes, true);
        }
        for (Object raw : modeling.getJSONArray("annotations")) {
            JSONObject annotation = (JSONObject)raw;
            annotation.put("resourceId", mapped(resources, annotation.getString("resourceId")));
        }
        if (modeling.containsKey("thumbnailResourceId")) {
            modeling.put("thumbnailResourceId", mapped(resources, modeling.getString("thumbnailResourceId")));
        }
    }
    private static void remapModelingGraph(JSONObject graph, Map<String,String> resources) {
        for (Object raw : graph.getJSONArray("assets")) {
            JSONObject asset = (JSONObject)raw;
            asset.put("resourceId", mapped(resources, asset.getString("resourceId")));
        }
    }
    private static void validateReferences(Object value,Set<String> nodes,Set<String> groups,Set<String> resources,Set<String> scenes) {
        if(value instanceof JSONObject map) for(var e:map.entrySet()) {
            String key=e.getKey(); Object child=e.getValue(); Set<String> allowed=null;
            if(NODE_KEYS.contains(key)||NODE_ARRAY_KEYS.contains(key)) allowed=nodes;
            else if(RESOURCE_KEYS.contains(key)||RESOURCE_ARRAY_KEYS.contains(key)) allowed=resources;
            else if(SCENE_KEYS.contains(key)) allowed=scenes;
            else if(key.equals("groupId")&&!map.containsKey("outputRole")) allowed=groups;
            if(allowed!=null) {
                if(child instanceof List<?> list) { for(Object id:list) if(!allowed.contains(Objects.toString(id,null))) fail("引用不存在："+key); }
                else if(!allowed.contains(Objects.toString(child,null))) fail("引用不存在："+key);
            } else validateReferences(child,nodes,groups,resources,scenes);
        } else if(value instanceof Iterable<?> rows) for(Object row:rows) validateReferences(row,nodes,groups,resources,scenes);
    }
    private static JSONObject snapshot(String json) {
        JSONObject s=JSON.parseObject(json);
        if(s==null||!(s.get("schemaVersion") instanceof Number version)||version.doubleValue()!=1||s.getJSONArray("objects")==null||s.getJSONArray("resources")==null||s.getJSONArray("currentResults")==null||s.getJSONObject("sceneDocument")==null) fail("发布快照格式不支持");
        CanvasPublicationSceneValidator.fields(s,"snapshot","schemaVersion sourceProjectId projectName projectDesc objects resources currentResults sceneDocument videoResourceId coverResourceId");
        List<CanvasObject> objects=new ArrayList<>(); Set<String> resources=new HashSet<>(); Map<String,String> mediaTypes=new HashMap<>();
        for(Object raw:s.getJSONArray("resources")) {
            JSONObject r=CanvasPublicationSceneValidator.object(raw);
            CanvasPublicationSceneValidator.fields(r,"resource","resourceId mediaType storageUrl fileSize mimeType metadataJson contentHash fileExtension");
            if(r.containsKey("fileExtension")&&(!(r.get("fileExtension") instanceof String ext)||!ext.matches("[a-zA-Z0-9]{1,8}"))) fail("冻结文件扩展名无效");
            String id=decimal(r.get("resourceId")); if(!resources.add(id)) fail("冻结资源重复");
            String storage=r.getString("storageUrl");
            if(storage==null||!(storage.matches("publication-file:[0-9a-f]{32}/[0-9a-f]{64}")||storage.matches("https?://[^\\s]+")||storage.startsWith("/profile/"))) fail("发布快照包含未持久化文件");
            mediaTypes.put(id,r.getString("mediaType"));
        }
        for(Object raw:s.getJSONArray("objects")) {
            JSONObject o=CanvasPublicationSceneValidator.object(raw);
            CanvasPublicationSceneValidator.fields(o,"object","objectId objectType content");
            CanvasObject item=new CanvasObject(); item.setObjectId(CanvasPublicationSceneValidator.key(o.get("objectId")));
            item.setObjectType(o.getString("objectType")); item.setContentJson(CanvasPublicationSceneValidator.object(o.get("content")).toJSONString()); objects.add(item);
        }
        objectIds(objects); Set<String> nodes=nodeIds(objects),scenes=sceneIds(objects);
        Set<String> sceneResources=CanvasPublicationSceneValidator.validate(s.getJSONObject("sceneDocument"),nodes,scenes);
        if(!resources.containsAll(sceneResources)) fail("冻结场景资源缺失");
        for (Object raw : s.getJSONObject("sceneDocument").getJSONArray("scenes")) {
            JSONObject modeling = ((JSONObject)raw).getJSONObject("modeling");
            if (modeling != null && modeling.containsKey("adoption")) fail("冻结场景不能包含私有采纳引用");
            modelingResources(modeling).forEach((id, type) -> {
                if (!type.equals(mediaTypes.get(id))) fail("冻结建模素材类型与场景不匹配");
            });
        }
        for(CanvasObject o:objects) validateObject(o.getObjectType(),JSON.parseObject(o.getContentJson()),nodes);
        validateReferences(s.getJSONArray("objects"),nodes,groupIds(objects),resources,scenes);
        Set<String> selected=new HashSet<>();
        for(Object raw:s.getJSONArray("currentResults")) {
            JSONObject r=CanvasPublicationSceneValidator.object(raw); CanvasPublicationSceneValidator.fields(r,"currentResult","objectId resourceId mediaType");
            String node=r.getString("objectId"),resource=decimal(r.get("resourceId"));
            if(!nodes.contains(node)||!selected.add(node)||!resources.contains(resource)||!Objects.equals(mediaTypes.get(resource),r.getString("mediaType"))) fail("冻结当前结果引用无效");
        }
        if(!"VIDEO".equals(mediaTypes.get(decimal(s.get("videoResourceId"))))||!"IMAGE".equals(mediaTypes.get(decimal(s.get("coverResourceId"))))) fail("冻结视频或封面无效");
        return s;
    }
    private static Map<String,String> identity(Set<String> ids) { Map<String,String> map=new HashMap<>(); ids.forEach(id->map.put(id,id)); return map; }
    private static String mapped(Map<String,String> ids,String old) { String value=ids.get(old); if(value==null) fail("存在不能完整重映射的引用："+old); return value; }
    private static String decimal(Object id) { if(id==null) fail("缺少资源编号"); return CanvasPublicationSceneValidator.resourceId(id.toString()); }
    private static String uuid() { return UUID.randomUUID().toString().replace("-",""); }
    private static int integer(Object value,int fallback) { try { return Integer.parseInt(Objects.toString(value)); } catch(Exception ignored) { return fallback; } }
    private static String mediaMetadata(String json,String type) {
        if(json==null) return null;
        if(Set.of("MODEL","SCENE_PACKAGE").contains(type)){if(CanvasPublicationBundles.read(json)==null)fail("模型未登记完整依赖");return json;}
        return com.aid.canvas.publication.service.CanvasPublicationMediaMetadata.project(json);
    }
    private static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static void fail(String message) { throw new ServiceException(message); }
}
