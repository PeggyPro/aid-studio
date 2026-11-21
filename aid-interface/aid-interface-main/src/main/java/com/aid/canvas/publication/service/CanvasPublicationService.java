package com.aid.canvas.publication.service;

import com.aid.canvas.publication.domain.*;
import com.aid.canvas.publication.dto.CanvasPublicationRequests.*;
import com.aid.canvas.publication.mapper.CanvasPublicationMapper;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 草稿、审核快照和公开版本相互隔离；所有权限收紧与审核通过共用项目锁。 */
@Service
@RequiredArgsConstructor
public class CanvasPublicationService {
    private final CanvasPublicationMapper mapper;
    private final ObjectProvider<CanvasPublicationPort> ports;
    private final CanvasPublicationMediaStore media;
    private final PlatformTransactionManager transactions;
    private final CanvasPublicationCopyJobs copyJobs;
    private final CanvasPublicationFiles files;
    private final com.aid.canvas.publication.mapper.CanvasPublicationWorkMapper work;

    private CanvasPublicationPort port() {
        CanvasPublicationPort value=ports.getIfAvailable();
        if(value==null) throw new ServiceException("当前环境未启用画布发布");
        return value;
    }
    public Map<String,Object> channels() {
        return Map.of("items",List.of(Map.of("code","CANVAS","name","画布","enabled",ports.getIfAvailable()!=null&&media.ready(),
                "listApi","/api/public/canvas/list","detailApi","/api/public/canvas/detail")));
    }
    public Map<String,Object> submit(Submit request,Long userId) {
        if(!Boolean.TRUE.equals(request.getPublicCanvas())) fail("提交审核必须确认公开；关闭公开请使用撤回接口");
        port().requireOwner(request.getProjectId(),userId);
        String hash=hash(JSON.toJSONString(request));
        CanvasPublication previous=mapper.byOperation(request.getProjectId(),request.getOperationId());
        if(previous!=null) {
            if(!hash.equals(previous.getRequestHash())) fail("operationId 已用于其他提交内容");
            return Map.of("publicationId",id(previous.getId()),"state",stateView(mapper.state(request.getProjectId())));
        }
        TransactionTemplate tx=new TransactionTemplate(transactions);
        long[] capturedStateVersion=new long[1];
        CanvasPublicationPort.Capture capture=tx.execute(ignored->{
            CanvasPublicationState state=locked(request.getProjectId());
            checkSubmission(state,request);
            capturedStateVersion[0]=state.getVersion();
            return port().capture(request.getProjectId(),userId,request.getExpectedRevision(),request.getVideoResourceId(),
                    request.getCoverResourceId(),state.getSceneDocumentJson());
        });
        CanvasPublicationMediaStore.Frozen frozen=media.freeze(capture.snapshotJson());
        boolean committed=false;
        try {
            Map<String,Object> result=tx.execute(ignored->{
                port().requireOwner(request.getProjectId(),userId);
                CanvasPublicationState state=locked(request.getProjectId());
                if(state.getVersion()!=capturedStateVersion[0]) fail("发布权限或状态已变化，请重新确认后提交");
                checkSubmission(state,request);
                if(port().revision(request.getProjectId())!=request.getExpectedRevision()) fail("画布版本已变化，请重新提交");
                if(mapper.byOperation(request.getProjectId(),request.getOperationId())!=null) fail("相同请求已完成，请查询发布状态");
                return persistSubmission(request,userId,hash,state,capture,frozen.json());
            });
            committed=true;return result;
        } finally { if(!committed) media.discard(frozen.batch()); }
    }
    private void checkSubmission(CanvasPublicationState state,Submit request) {
        if(state.getPendingId()!=null) fail("已有待审核版本，请先撤销后重新提交");
        if(!Objects.equals(state.getSceneVersion(),request.getExpectedSceneVersion())) fail("场景文档已变化，请刷新后提交");
    }
    private Map<String,Object> persistSubmission(Submit request,Long userId,String hash,CanvasPublicationState state,
            CanvasPublicationPort.Capture capture,String snapshotJson) {
        CanvasPublication item=new CanvasPublication();
        item.setProjectId(request.getProjectId()); item.setOwnerUserId(userId);
        item.setWorkspaceRevision(capture.revision()); item.setSceneVersion(state.getSceneVersion());
        item.setOperationId(request.getOperationId()); item.setRequestHash(hash);
        item.setProjectName(blank(request.getProjectName())?capture.projectName():request.getProjectName().trim());
        if(blank(item.getProjectName())) fail("作品名称不能为空");
        item.setProjectDesc(request.getProjectDesc().trim());
        if(blank(item.getProjectDesc())) fail("作品简介不能为空");
        item.setVideoUrl(""); item.setCoverUrl("");
        JSONObject frozen=JSON.parseObject(snapshotJson);
        frozen.put("projectName",item.getProjectName());frozen.put("projectDesc",item.getProjectDesc());
        item.setSnapshotJson(frozen.toJSONString()); item.setContentHash(hash(item.getSnapshotJson()));
        item.setAllowCopy(request.isAllowCopy()); item.setReviewStatus("PENDING");
        item.setSourcePublicationId(state.getSourcePublicationId()); item.setRootPublicationId(state.getRootPublicationId());
        mapper.insert(item);
        files.attach(JSON.parseObject(snapshotJson).getJSONArray("resources").getJSONObject(0).getString("storageUrl").substring("publication-file:".length(),"publication-file:".length()+32),item.getId(),null);
        item.setVideoUrl(mediaUrl(item.getId(),request.getVideoResourceId().toString()));
        item.setCoverUrl(mediaUrl(item.getId(),request.getCoverResourceId().toString()));mapper.mediaUrls(item);
        state.setPendingId(item.getId()); save(state);
        audit(item,state,"USER:"+userId,"SUBMIT",null);
        return Map.of("publicationId",id(item.getId()),"state",stateView(state));
    }
    public Map<String,Object> prepare(Long projectId,Long userId) {
        port().requireOwner(projectId,userId);
        Map<String,Object> result=new LinkedHashMap<>(status(projectId,userId));
        result.put("draftRevision",Long.toString(port().revision(projectId)));
        result.put("storageReady",media.ready());result.put("requiresCompleteScenes",true);
        return result;
    }
    public Map<String,Object> status(Long projectId,Long userId) {
        port().requireReader(projectId,userId);
        CanvasPublicationState state=mapper.state(projectId);
        return stateView(state==null?empty(projectId):state);
    }
    public Map<String,Object> scenes(Long projectId,Long userId) {
        port().requireReader(projectId,userId);
        CanvasPublicationState state=mapper.state(projectId);
        return Map.of("sceneVersion",state==null?0L:state.getSceneVersion(),"document",
                state==null||blank(state.getSceneDocumentJson())?new JSONObject():JSON.parseObject(state.getSceneDocumentJson()));
    }
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> saveScenes(Scene request,Long userId) {
        port().requireEditor(request.getProjectId(),userId);
        CanvasPublicationState state=locked(request.getProjectId());
        if (Boolean.TRUE.equals(state.getAtomicSceneWrite())) fail("该工作区已启用原子场景保存，请更新客户端");
        return writeScenes(state, userId, request.getExpectedVersion(), request.getDocument(), false, Map.of());
    }

    /** Same lock order as legacy scene saves and publication capture: publication, editor, workspace. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CanvasPublicationState lockWorkspaceSceneWrite(Long projectId, Long userId) {
        port().requireEditor(projectId, userId);
        CanvasPublicationState state = locked(projectId);
        port().lockSceneEditor(projectId, userId);
        return state;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, String> legacySceneGaps(Long projectId) {
        CanvasPublicationState state = mapper.state(projectId);
        Set<String> saved = new HashSet<>();
        if (state != null && !blank(state.getSceneDocumentJson())) {
            var scenes = JSON.parseObject(state.getSceneDocumentJson()).getJSONArray("scenes");
            if (scenes != null) for (Object row : scenes) {
                if (row instanceof JSONObject scene) saved.add(scene.getString("id"));
            }
        }
        Map<String, String> gaps = new LinkedHashMap<>(port().workspaceSceneReferences(projectId));
        gaps.values().removeIf(saved::contains);
        return gaps;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> saveWorkspaceScenes(Long projectId, Long userId, CanvasRequests.SceneWrite request,
                                                  Map<String, String> legacyGaps) {
        return writeScenes(locked(projectId), userId, request.getExpectedVersion(), request.getDocument(), true, legacyGaps);
    }

    private Map<String,Object> writeScenes(CanvasPublicationState state, Long userId, Long expectedVersion, JSONObject document,
                                           boolean atomic, Map<String, String> legacyGaps) {
        if(!Objects.equals(state.getSceneVersion(),expectedVersion)) fail("场景版本冲突，请重新读取");
        if (!blank(state.getSceneDocumentJson())
                && JSON.parseObject(state.getSceneDocumentJson()).getIntValue("schemaVersion") == 2
                && document.getIntValue("schemaVersion") != 2) fail("场景已升级到完整建模格式，请更新客户端");
        String json=JSON.toJSONString(document);
        if(json.getBytes(StandardCharsets.UTF_8).length>8*1024*1024) fail("场景文档超过8MB");
        port().validateSceneDocument(state.getProjectId(),userId,JSON.parseObject(json),legacyGaps);
        if (atomic) {
            long version = state.getSceneVersion()+1;
            String previous = state.getSceneDocumentJson();
            if (blank(previous)) previous = "{\"schemaVersion\":1,\"scenes\":[]}";
            if (mapper.insertSceneHistory(state.getProjectId(), version, previous) != 1) fail("场景撤销历史写入失败");
            mapper.pruneSceneHistory(state.getProjectId(), version-20);
            state.setAtomicSceneWrite(true);
        }
        state.setSceneDocumentJson(json); state.setSceneVersion(state.getSceneVersion()+1); save(state);
        return Map.of("sceneVersion",state.getSceneVersion(),"stateVersion",state.getVersion());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> change(Change request,Long userId) {
        port().requireOwner(request.getProjectId(),userId);
        CanvasPublicationState state=locked(request.getProjectId());
        version(state,request.getExpectedVersion());
        switch(request.getAction()) {
            case "WITHDRAW" -> { cancelPending(state); state.setVisible(false); state.setCopyEnabled(false); }
            case "DISABLE_COPY" -> { cancelPending(state); state.setCopyEnabled(false); }
            case "CANCEL_REVIEW" -> cancelPending(state);
            default -> fail("不支持的发布操作");
        }
        save(state); audit(null,state,"USER:"+userId,request.getAction(),null);
        return stateView(state);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> review(Review request,String actor) {
        CanvasPublication item=required(request.getPublicationId());
        CanvasPublicationState state=locked(item.getProjectId()); version(state,request.getExpectedVersion());
        if("OFFLINE".equals(request.getAction())) {
            if(blank(request.getReason())) fail("下架必须填写原因");
            if(!Objects.equals(state.getPublishedId(),item.getId())||!Boolean.TRUE.equals(state.getVisible())
                    ||Boolean.TRUE.equals(state.getBlocked())||!port().projectExists(item.getProjectId())) fail("只能下架当前公开版本");
            cancelPending(state); state.setVisible(false); state.setCopyEnabled(false); state.setBlocked(true);
        } else {
            if(!Objects.equals(state.getPendingId(),item.getId())) fail("审核单已撤销或被替换");
            boolean approve="APPROVE".equals(request.getAction());
            if(!approve&&!"REJECT".equals(request.getAction())) fail("不支持的审核操作");
            if(!approve&&blank(request.getReason())) fail("驳回必须填写原因");
            if(approve&&!port().projectExists(item.getProjectId())) fail("项目已删除，不能发布");
            if(mapper.review(item.getId(),approve?"APPROVED":"REJECTED",request.getReason())!=1) fail("审核状态已变化");
            state.setPendingId(null);
            if(approve) {
                state.setPublishedId(item.getId()); state.setVisible(true);
                state.setCopyEnabled(Boolean.TRUE.equals(item.getAllowCopy())); state.setBlocked(false);
            }
        }
        save(state); audit(item,state,actor,request.getAction(),request.getReason()); return stateView(state);
    }
    public Map<String,Object> list(Page request,boolean publicOnly) {
        port();
        String status=publicOnly?null:request.getReviewStatus();
        int offset=Math.multiplyExact(request.getPageNum()-1,request.getPageSize());
        List<Map<String,Object>> items=mapper.list(publicOnly,status,request.getKeyword(),offset,request.getPageSize())
                .stream().map(item->card(item,!publicOnly)).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("total",mapper.count(publicOnly,status,request.getKeyword()));if(!publicOnly)result.put("cleanupPending",files.pendingCleanup());return result;
    }
    public Map<String,Object> detail(Long publicationId,boolean admin) {
        CanvasPublication item=required(publicationId);
        if(!admin) requirePublic(item,mapper.state(item.getProjectId()));
        Map<String,Object> result=new LinkedHashMap<>(card(item,admin));
        result.put("canvas",publicPreview(item,admin));
        if(admin) {
            result.put("auditRecords",mapper.history(item.getProjectId()));
            result.put("contentHash",item.getContentHash());
            result.put("source",source(item.getSourcePublicationId()));
            if(item.getSourcePublicationId()!=null) result.put("sourceComparison",port().compare(required(item.getSourcePublicationId()).getSnapshotJson(),item.getSnapshotJson(),item.getProjectId()));
            CanvasPublicationState state=mapper.state(item.getProjectId());
            result.put("publishedVersion",state.getPublishedId()==null?Map.of():card(required(state.getPublishedId()),true));
            if(state.getPublishedId()!=null) result.put("publishedComparison",port().compare(required(state.getPublishedId()).getSnapshotJson(),item.getSnapshotJson(),item.getProjectId()));
        }
        return result;
    }
    public Map<String,Object> ownVersion(Long publicationId,Long userId) {
        CanvasPublication item=required(publicationId);port().requireOwner(item.getProjectId(),userId);
        Map<String,Object> result=new LinkedHashMap<>(card(item,true));
        result.put("canvas",publicPreview(item,true));
        for(String key:List.of("videoUrl","coverUrl")) result.computeIfPresent(key,(k,v)->v.toString().replace("/aid/audit/canvas/media","/api/user/canvas/publication/media"));
        JSONObject canvas=(JSONObject)result.get("canvas");
        if(canvas.getJSONArray("resources")!=null) for(Object raw:canvas.getJSONArray("resources")) {
            JSONObject row=(JSONObject)raw;
            row.computeIfPresent("resourceUrl",(k,v)->v.toString().replace("/aid/audit/canvas/media","/api/user/canvas/publication/media"));
        }
        if(canvas.getJSONArray("resources")!=null)CanvasPublicationBundles.resolveUrls(canvas.getJSONArray("resources"));
        return result;
    }
    public List<Map<String,Object>> versions(Long projectId,Long userId) {
        port().requireOwner(projectId,userId);
        return mapper.versions(projectId,0,100).stream().map(item->{
            Map<String,Object> card=card(item,true);
            card.computeIfPresent("videoUrl",(key,value)->value.toString().replace("/aid/audit/canvas/media","/api/user/canvas/publication/media"));
            card.computeIfPresent("coverUrl",(key,value)->value.toString().replace("/aid/audit/canvas/media","/api/user/canvas/publication/media"));
            return card;
        }).toList();
    }
    public Map<String,Object> copyStatus(String operationId,Long userId) {
        return copyJobs.status(userId,operationId);
    }
    public Map<String,Object> copyControl(String operationId,Long userId,boolean retry){return copyJobs.control(userId,operationId,retry);}
    public ResponseEntity<Resource> content(Long publicationId,Long resourceId,Long userId,boolean admin) {
        CanvasPublication item=required(publicationId);
        if(admin) { /* Controller checks dedicated audit permission. */ }
        else if(userId!=null) port().requireOwner(item.getProjectId(),userId);
        else requirePublic(item,mapper.state(item.getProjectId()));
        JSONObject snapshot=JSON.parseObject(item.getSnapshotJson());
        for(Object raw:snapshot.getJSONArray("resources")) {
            JSONObject row=(JSONObject)raw;
            if(resourceId.toString().equals(row.getString("resourceId"))) return serve(row);
        }
        fail("资源不属于该发布版本");return null;
    }
    public ResponseEntity<Resource> copiedContent(Long resourceId,Long userId) {
        return serve(port().authorizeResource(resourceId,userId));
    }
    private ResponseEntity<Resource> serve(JSONObject row) {
        Resource file=new FileSystemResource(media.file(row.getString("storageUrl")));
        String mime=row.getString("mimeType");
        MediaType type=MediaType.APPLICATION_OCTET_STREAM;
        try { if(mime!=null) type=MediaType.parseMediaType(mime); } catch(IllegalArgumentException ignored) {}
        // 不允许HTML/SVG等主动内容以同站文档执行。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","attachment; filename=\"resource\"").contentType(type).body(file);
    }
    private JSONObject publicPreview(CanvasPublication item,boolean admin) {
        JSONObject preview=port().preview(item.getSnapshotJson());
        com.alibaba.fastjson2.JSONArray resources=preview.getJSONArray("resources");
        if(resources!=null) for(Object raw:resources) {
            JSONObject row=(JSONObject)raw;row.remove("storageUrl");
            row.put("resourceUrl",audienceUrl(mediaUrl(item.getId(),row.getString("resourceId")),admin));
        }
        if(resources!=null)CanvasPublicationBundles.resolveUrls(resources);
        return preview;
    }
    private static String mediaUrl(Long publicationId,String resourceId) {
        return "/api/public/canvas/media?publicationId="+publicationId+"&resourceId="+resourceId;
    }
    private static String audienceUrl(String url,boolean admin) {
        return admin&&url!=null?url.replace("/api/public/canvas/media","/aid/audit/canvas/media"):url;
    }
    public Map<String,Object> copy(Copy request,Long userId) {
        CanvasPublication item=required(request.getPublicationId());
        if(Objects.equals(userId,item.getOwnerUserId())) fail("不能复制自己的作品");
        String hash=hash(JSON.toJSONString(request));
        Map<String,Object> operation=mapper.copyOperation(userId,request.getOperationId());
        if(operation!=null&&!hash.equals(operation.get("request_hash"))) fail("operationId 已用于其他复制内容");
        Long previous=mapper.copied(userId,request.getOperationId(),item.getId(),hash);
        // 已成功创建的独立项目不受来源下架影响；重试仅返回原结果。
        if(previous!=null) return Map.of("projectId",id(previous),"replayed",true,"status","SUCCEEDED","progress",100);
        CanvasPublicationState state=mapper.state(item.getProjectId());
        requirePublic(item,state);
        if(!Boolean.TRUE.equals(state.getCopyEnabled())) fail("作者未允许复制画布");
        port().requireCopyCapacity(userId,item.getSnapshotJson());
        return copyJobs.enqueue(userId,item.getId(),request.getOperationId(),hash,request.getProjectName(),item.getSnapshotJson());
    }
    void performCopy(CanvasCopyJob job,String token,CanvasPublicationCopyJobs.Progress progress) {
        CanvasPublication item=required(job.getPublicationId());CanvasPublicationState state=mapper.state(item.getProjectId());
        requirePublic(item,state);if(!Boolean.TRUE.equals(state.getCopyEnabled()))fail("作者已关闭复制");
        port().requireCopyCapacity(job.getUserId(),item.getSnapshotJson());progress.update(0,0);
        CanvasPublicationMediaStore.PreparedCopy prepared=media.prepareCopy(item.getSnapshotJson(),token,progress);
        new TransactionTemplate(transactions).execute(ignored->{
            if(work.lockUser(job.getUserId())==null)fail("账号已不可用");
            CanvasPublicationState current=locked(item.getProjectId());copyJobs.requireLease(job,token);
            requirePublic(item,current);if(!Boolean.TRUE.equals(current.getCopyEnabled()))fail("作者已关闭复制");
            Copy request=new Copy();request.setPublicationId(job.getPublicationId());request.setOperationId(job.getOperationId());request.setProjectName(job.getProjectName());
            Map<String,Object> result=persistCopy(request,job.getUserId(),item,job.getRequestHash(),prepared.json());
            Long target=Long.valueOf(result.get("projectId").toString());files.attach(token,null,target);copyJobs.succeeded(job,token,target);return null;
        });
    }
    private Map<String,Object> persistCopy(Copy request,Long userId,CanvasPublication item,String hash,String snapshotJson) {
        Long target=port().copy(snapshotJson,userId,
                blank(request.getProjectName())?item.getProjectName():request.getProjectName().trim(),request.getOperationId());
        CanvasPublicationState targetState=locked(target);
        targetState.setSourcePublicationId(item.getId());
        targetState.setRootPublicationId(item.getRootPublicationId()==null?item.getId():item.getRootPublicationId());
        save(targetState);
        mapper.recordCopy(userId,request.getOperationId(),item.getId(),hash,target);
        return Map.of("projectId",id(target),"replayed",false,"sourcePublicationId",id(item.getId()));
    }
    private Map<String,Object> source(Long id) {
        if(id==null) return Map.of();
        CanvasPublication source=required(id);
        return Map.of("publicationId",id(source.getId()),"projectId",id(source.getProjectId()),
                "ownerUserId",id(source.getOwnerUserId()),"projectName",source.getProjectName(),
                "contentHash",source.getContentHash(),"canvas",publicPreview(source,true));
    }
    private Map<String,Object> card(CanvasPublication item,boolean admin) {
        CanvasPublicationState state=mapper.state(item.getProjectId());
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("contentType","CANVAS"); out.put("publicationId",id(item.getId())); out.put("projectId",id(item.getProjectId()));
        out.put("ownerUserId",id(item.getOwnerUserId())); out.put("projectName",item.getProjectName());
        out.put("projectDesc",item.getProjectDesc());
        out.put("videoUrl",audienceUrl(item.getVideoUrl(),admin));
        out.put("coverUrl",audienceUrl(item.getCoverUrl(),admin));
        boolean currentPublic=state!=null&&Boolean.TRUE.equals(state.getVisible())&&!Boolean.TRUE.equals(state.getBlocked())
                &&Objects.equals(state.getPublishedId(),item.getId())&&"APPROVED".equals(item.getReviewStatus())
                &&(!admin||port().projectExists(item.getProjectId()));
        out.put("isPublic",currentPublic);
        out.put("visibility",state!=null&&Boolean.TRUE.equals(state.getBlocked())?"ADMIN_OFFLINE":currentPublic?"PUBLIC":"PRIVATE");
        out.put("allowCopy",currentPublic&&Boolean.TRUE.equals(state.getCopyEnabled()));
        out.put("isDerivative",item.getSourcePublicationId()!=null);
        if(admin) {
            out.put("reviewStatus",item.getReviewStatus()); out.put("reviewReason",item.getReviewReason());
            out.put("requestedAllowCopy",item.getAllowCopy()); out.put("stateVersion",state.getVersion());
            out.put("sourcePublicationId",id(item.getSourcePublicationId())); out.put("rootPublicationId",id(item.getRootPublicationId()));
        }
        return out;
    }
    private Map<String,Object> stateView(CanvasPublicationState state) {
        Map<String,Object> out=new LinkedHashMap<>();
        CanvasPublication published=state.getPublishedId()==null?null:mapper.get(state.getPublishedId());
        CanvasPublication pending=state.getPendingId()==null?null:mapper.get(state.getPendingId());
        CanvasPublication latest=mapper.latest(state.getProjectId());
        boolean visible=Boolean.TRUE.equals(state.getVisible())&&!Boolean.TRUE.equals(state.getBlocked())&&published!=null;
        out.put("stateVersion",state.getVersion()); out.put("sceneVersion",state.getSceneVersion());
        out.put("visibility",Boolean.TRUE.equals(state.getBlocked())?"ADMIN_OFFLINE":visible?"PUBLIC":"PRIVATE");
        out.put("isPublic",visible); out.put("isApproved",published!=null&&"APPROVED".equals(published.getReviewStatus()));
        out.put("allowCopy",visible&&Boolean.TRUE.equals(state.getCopyEnabled())); out.put("blocked",state.getBlocked());
        out.put("publishedId",id(state.getPublishedId())); out.put("pendingId",id(state.getPendingId()));
        out.put("reviewStatus",pending!=null?"PENDING":latest!=null?latest.getReviewStatus():"DRAFT");
        out.put("reviewReason",latest==null?null:latest.getReviewReason());
        out.put("latestPublicationId",latest==null?null:id(latest.getId()));
        long draftRevision=port().revision(state.getProjectId());
        boolean dirty=published==null||published.getWorkspaceRevision()!=draftRevision||!Objects.equals(published.getSceneVersion(),state.getSceneVersion());
        out.put("draftRevision",Long.toString(draftRevision));out.put("hasUnpublishedChanges",dirty);
        out.put("hasApprovedVersion",published!=null);out.put("isCurrentDraftApproved",!dirty);
        out.put("isDerivative",state.getSourcePublicationId()!=null);
        out.put("sourcePublicationId",id(state.getSourcePublicationId())); out.put("rootPublicationId",id(state.getRootPublicationId()));
        return out;
    }
    private void requirePublic(CanvasPublication item,CanvasPublicationState state) {
        if(state==null||!Boolean.TRUE.equals(state.getVisible())||Boolean.TRUE.equals(state.getBlocked())
                ||!Objects.equals(state.getPublishedId(),item.getId())||!"APPROVED".equals(item.getReviewStatus())
                ||!port().projectExists(item.getProjectId())) fail("作品不存在或已停止公开");
    }
    private CanvasPublication required(Long id) { CanvasPublication value=mapper.get(id); if(value==null) fail("发布版本不存在"); return value; }
    private CanvasPublicationState locked(Long projectId) {
        // INSERT IGNORE can take a shared lock on the existing row. Concurrent
        // writers would then both try to upgrade it at SELECT FOR UPDATE.
        mapper.ensureForWrite(projectId);
        return mapper.lock(projectId);
    }
    private void save(CanvasPublicationState state) { if(mapper.save(state)!=1) fail("发布状态已变化，请刷新后重试"); state.setVersion(state.getVersion()+1); }
    private void version(CanvasPublicationState state,Long version) { if(!Objects.equals(state.getVersion(),version)) fail("发布状态版本冲突"); }
    private void cancelPending(CanvasPublicationState state) {
        if(state.getPendingId()!=null) { mapper.review(state.getPendingId(),"CANCELLED","发布权限收紧或用户撤销提审"); state.setPendingId(null); }
    }
    private void audit(CanvasPublication item,CanvasPublicationState state,String actor,String action,String reason) {
        mapper.audit(item==null?state.getPublishedId():item.getId(),state.getProjectId(),actor,action,reason);
    }
    private static CanvasPublicationState empty(Long projectId) {
        CanvasPublicationState s=new CanvasPublicationState(); s.setProjectId(projectId); s.setVersion(0L); s.setSceneVersion(0L);
        s.setVisible(false); s.setCopyEnabled(false); s.setBlocked(false); return s;
    }
    private static String id(Long id) { return id==null?null:id.toString(); }
    private static boolean blank(String value) { return value==null||value.isBlank(); }
    private static void fail(String message) { throw new ServiceException(message); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
