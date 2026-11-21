package com.aid.canvas.service.impl;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.JoinApplication;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.OperationObject;
import com.aid.canvas.domain.CanvasEntities.ProjectSetting;
import com.aid.canvas.domain.CanvasEntities.ProjectInvite;
import com.aid.canvas.domain.CanvasEntities.ProjectMember;
import com.aid.canvas.domain.CanvasEntities.ProjectShare;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.domain.CanvasEntities.UserView;
import com.aid.canvas.domain.CanvasEntities.NodeDraft;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.publication.mapper.CanvasPublicationMapper;
import com.aid.canvas.publication.domain.CanvasPublicationState;
import com.aid.canvas.publication.service.CanvasPublicationService;
import com.aid.canvas.runtime.CanvasAssistantOperationCanonicalizer;
import com.aid.canvas.runtime.RuntimeScopeAccess;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.canvas.service.CanvasProjectService;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.entitlement.service.EntitlementSubjectResolver;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CanvasProjectServiceImpl implements CanvasProjectService {
    private static final Set<String> TYPES = Set.of("NODE", "EDGE", "GROUP");
    private static final Set<String> ACTIONS = Set.of("UPSERT", "PATCH", "DELETE");
    private static final Set<String> EDGE_KEYS = Set.of("sourceObjectId", "targetObjectId", "sourceHandle",
            "targetHandle", "label", "style", "metadata");
    private static final Set<String> GROUP_KEYS = Set.of("title", "position", "size", "childObjectIds", "style", "metadata");
    private static final Set<String> FORBIDDEN_KEYS = Set.of("taskid", "remotetaskid", "taskstatus", "resulturl",
            "outputurl", "mediaurl", "billing", "balance", "userid", "ownerid", "memberrole", "permissions",
            "locktoken", "operationrunid", "audiorunid", "storyboardprompttaskid", "storyboardpromptstatus",
            "errormessage", "createdat", "updatedat", "modelcostcredits", "modelisfree", "status", "progress",
            "selectedresult", "currentresult");
    private static final Set<String> COPY_RESET_KEYS = Set.of("status", "progress", "selectedresult", "currentresult");
    private static final Set<String> ASSISTANT_CREATE_ACTIONS = Set.of(
            "CREATE_TEXT", "CREATE_IMAGE", "CREATE_VIDEO", "CREATE_MUSIC", "CREATE_AUDIO");
    private static final Set<String> ASSISTANT_CREATE_KEYS = Set.of(
            "nodeType", "title", "position", "size", "prompt", "textContent", "parameters",
            "resourceIds", "inputPorts", "metadata", com.aid.canvas.runtime.CanvasAssistantNodePlacement.AUTO);
    private static final Map<String, Object> PROJECT_SETTING_DEFAULTS = Map.of(
            "asset.transfer.member.enabled", Boolean.FALSE);
    private static final int MAX_NODE_RESOURCE_IDS = 25;
    private static final int RESOURCE_QUERY_BATCH = 500;
    private static final int MAX_DRAFT_BYTES = 256 * 1024;
    private static final int MAX_OBJECT_BYTES = 128 * 1024;
    private static final int MAX_JSON_DEPTH = 20;
    private static final int MAX_JSON_ENTRIES = 4000;

    private final CanvasMapper canvasMapper;
    private final CanvasPublicationMapper publicationMapper;
    private final CanvasPublicationService publicationService;
    private final AidComicProjectMapper projectMapper;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final CanvasAccessService accessService;
    private final CanvasCollaborationService collaborationService;
    private final CanvasResourceService resourceService;
    private final MediaUrlResolver mediaUrlResolver;
    private final EntitlementSubjectResolver entitlementSubjectResolver;
    private final RuntimeScopeAccess runtimeScopeAccess;
    private final RuntimeOperationSubmissionGuard runtimeSubmissionGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createProject(CanvasRequests.CreateProject request, Long userId) {
        String requestHash = sha256(JSON.toJSONString(request));
        Long existing = canvasMapper.selectProjectRequest(userId, request.getOperationId());
        if (existing != null) {
            if (!requestHash.equals(canvasMapper.selectProjectRequestHash(userId, request.getOperationId()))) {
                throw new ServiceException("operationId 已被不同创建内容使用");
            }
            if (existing > 0) return projectDetail(existing, userId);
        }
        if (canvasMapper.reserveProjectRequest(userId, request.getOperationId(), requestHash) == 0) {
            Long concurrent = canvasMapper.selectProjectRequest(userId, request.getOperationId());
            if (concurrent != null && concurrent > 0) return projectDetail(concurrent, userId);
            throw new ServiceException("相同创建请求正在处理中，请稍后查询");
        }
        accessService.requireFeature(userId, null, "canvas.enabled");
        canvasMapper.lockCanvasOwnerQuota(userId);
        int maxProjects = capacityLimit(userId, "canvas.project.max", "maxProjectsPerOwner", 20);
        if (maxProjects <= 0 || canvasMapper.countOwnedCanvasProjects(userId) >= maxProjects) {
            throw new ServiceException("自由画布项目数量已达当前权益上限");
        }
        AidComicProject project = new AidComicProject();
        project.setUserId(userId);
        project.setProjectName(request.getProjectName().trim());
        project.setProjectDesc(request.getProjectDesc() == null ? "" : request.getProjectDesc().trim());
        project.setProjectType("canvas");
        project.setCoverUrl(trimToNull(request.getCoverUrl()));
        project.setCurrentStep(-1);
        project.setStatus(0);
        project.setIsPublic("0");
        project.setAllowPreview("0");
        project.setAllowCopy("0");
        project.setCopyCount(0L);
        project.setDelFlag("0");
        project.setCreateTime(new Date());
        project.setUpdateTime(new Date());
        if (projectMapper.insert(project) != 1) throw new ServiceException("创建自由画布项目失败");

        Workspace workspace = new Workspace();
        workspace.setProjectId(project.getId());
        workspace.setWorkspaceType("FREE_CANVAS");
        workspace.setSchemaVersion(1);
        workspace.setRevision(0L);
        workspace.setEventSeq(0L);
        workspace.setNodeCount(0);
        workspace.setEdgeCount(0);
        workspace.setGroupCount(0);
        workspace.setStorageBytes(0L);
        workspace.setDelFlag("0");
        canvasMapper.insertWorkspace(workspace);

        ProjectMember owner = new ProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(userId);
        owner.setRoleCode("OWNER");
        owner.setStatus("ACTIVE");
        owner.setJoinedTime(new Date());
        canvasMapper.insertMember(owner);
        canvasMapper.completeProjectRequest(userId, request.getOperationId(), project.getId());
        return projectDetail(project.getId(), userId);
    }

    @Override
    public Map<String, Object> listProjects(CanvasRequests.ProjectList request, Long userId) {
        int page = request.getPageNum() == null ? 1 : request.getPageNum();
        int size = request.getPageSize() == null ? 20 : request.getPageSize();
        String sortBy = normalizeSort(request.getSortBy());
        String direction = "ASC".equalsIgnoreCase(request.getSortDirection()) ? "ASC" : "DESC";
        List<Map<String, Object>> items = canvasMapper.selectUserProjects(userId, trimToNull(request.getProjectName()),
                sortBy, direction, (page - 1) * size, size);
        return page(items.stream().map(item -> JsonSafeId.fields(item,
                        "projectId", "ownerUserId", "workspaceId")).toList(),
                canvasMapper.countUserProjects(userId, trimToNull(request.getProjectName())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importProject(CanvasRequests.ImportProject request, Long userId) {
        Map<String, Object> project = createProject(request, userId);
        Long projectId = parsePositiveLong(project.get("projectId"), "项目编号无效");
        Workspace workspace = canvasMapper.selectWorkspaceByProject(projectId);
        String importOperationId = request.getOperationId();
        Operation previousImport = canvasMapper.selectOperation(workspace.getId(), importOperationId);
        if (previousImport != null) return JSON.parseObject(previousImport.getPayloadJson(), LinkedHashMap.class);
        if (workspace.getRevision() > 0) throw new ServiceException("导入项目已存在非导入操作，不能覆盖");
        int limit = capacityLimit(userId, "canvas.node.max", "maxNodesPerWorkspace", 1000);
        long nodeCount = request.getObjects().stream().filter(o -> "NODE".equals(o.getObjectType())).count();
        if (nodeCount > limit) throw new ServiceException("导入画布的节点数量超过当前权益上限");
        Map<String, String> idMap = new LinkedHashMap<>();
        request.getObjects().forEach(item -> {
            if (!TYPES.contains(item.getObjectType())) throw new ServiceException("导入对象类型不支持");
            if (idMap.putIfAbsent(item.getLocalObjectId(), UUID.randomUUID().toString().replace("-", "")) != null)
                throw new ServiceException("导入对象标识重复");
        });
        List<CanvasRequests.ImportObject> ordered = request.getObjects().stream()
                .sorted((a, b) -> Integer.compare(importOrder(a.getObjectType()), importOrder(b.getObjectType()))).toList();
        List<Map<String, Object>> imported = new ArrayList<>();
        for (CanvasRequests.ImportObject item : ordered) {
            JSONObject payload = JSON.parseObject(JSON.toJSONString(item.getPayload()));
            remapReferences(payload, idMap);
            CanvasObject object = new CanvasObject();
            object.setProjectId(projectId);
            object.setWorkspaceId(workspace.getId());
            object.setObjectId(idMap.get(item.getLocalObjectId()));
            object.setObjectType(item.getObjectType());
            object.setObjectVersion(1L);
            object.setLockFence(0L);
            object.setContentJson(validatedContent(item.getObjectType(), payload, workspace.getId(), userId));
            object.setContentHash(sha256(object.getContentJson()));
            object.setDeleted(0);
            object.setLastActorUserId(userId);
            canvasMapper.upsertObjectFence(workspace.getId(), object.getObjectId(), 0L);
            if (canvasMapper.insertCanvasObject(object) != 1) throw new ServiceException("导入对象写入失败");
            imported.add(objectView(object));
        }
        validateGraph(workspace.getId());
        long storageBytes = canvasMapper.selectWorkspaceObjects(workspace.getId(), false).stream()
                .mapToLong(item -> utf8Length(item.getContentJson())).sum();
        int edges = (int) request.getObjects().stream().filter(o -> "EDGE".equals(o.getObjectType())).count();
        int groups = (int) request.getObjects().stream().filter(o -> "GROUP".equals(o.getObjectType())).count();
        canvasMapper.applyWorkspaceDelta(workspace.getId(), (int) nodeCount, edges, groups, storageBytes);
        Workspace updated = canvasMapper.selectWorkspace(workspace.getId());
        Map<String, Object> result = new LinkedHashMap<>(projectDetail(projectId, userId));
        result.put("idMap", idMap);
        result.put("objects", imported);
        result.put("revision", updated.getRevision());
        result.put("eventSeq", updated.getEventSeq());
        String resultJson = JSON.toJSONString(result);
        Operation operation = new Operation();
        operation.setProjectId(projectId);
        operation.setWorkspaceId(workspace.getId());
        operation.setOperationId(importOperationId);
        operation.setActorUserId(userId);
        operation.setWorkspaceRevision(updated.getRevision());
        operation.setEventSeq(updated.getEventSeq());
        operation.setOperationType("IMPORT");
        operation.setPayloadJson(resultJson);
        operation.setPayloadHash(sha256(JSON.toJSONString(request)));
        operation.setCreateTime(new Date());
        canvasMapper.insertOperation(operation);
        publishAfterCommit(workspace.getId(), JSON.toJSONString(Map.of("type", "WORKSPACE_IMPORTED",
                "operationId", request.getOperationId(), "revision", updated.getRevision(), "eventSeq", updated.getEventSeq())));
        return result;
    }

    @Override
    public Map<String, Object> projectDetail(Long projectId, Long userId) {
        AidComicProject project = accessService.requireProject(projectId, userId);
        Workspace workspace = canvasMapper.selectWorkspaceByProject(projectId);
        String role = Objects.equals(project.getUserId(), userId) ? "OWNER" : accessService.requireRole(projectId, userId, "EDITOR", "VIEWER");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", JsonSafeId.value(project.getId()));
        result.put("projectName", project.getProjectName());
        result.put("projectDesc", project.getProjectDesc());
        result.put("coverUrl", project.getCoverUrl());
        result.put("ownerUserId", JsonSafeId.value(project.getUserId()));
        result.put("roleCode", role);
        result.put("status", project.getStatus());
        result.put("workspace", workspaceView(workspace));
        result.put("settings", projectSettingViews(projectId));
        return result;
    }

    @Override
    public Map<String, Object> projectSettings(Long projectId, Long userId) {
        accessService.requireProject(projectId, userId);
        return Map.of("items", projectSettingViews(projectId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateProjectSetting(CanvasRequests.ProjectSettingUpdate request, Long userId) {
        accessService.requireOwner(request.getProjectId(), userId);
        if (!PROJECT_SETTING_DEFAULTS.containsKey(request.getSettingKey())) throw new ServiceException("项目设置项不支持客户端修改");
        if (!(request.getValue() instanceof Boolean)) throw new ServiceException("该项目设置值必须为布尔类型");
        canvasMapper.lockProjectMemberQuota(request.getProjectId());
        ProjectSetting existing = canvasMapper.selectProjectSetting(request.getProjectId(), request.getSettingKey());
        String json = JSON.toJSONString(request.getValue());
        long version;
        if (existing == null) {
            if (request.getExpectedVersion() != 0L) throw new ServiceException("项目设置版本冲突");
            ProjectSetting setting = new ProjectSetting();
            setting.setProjectId(request.getProjectId());
            setting.setSettingKey(request.getSettingKey());
            setting.setSettingValue(json);
            setting.setSettingVersion(1L);
            setting.setLastActorUserId(userId);
            if (canvasMapper.insertProjectSetting(setting) != 1) throw new ServiceException("项目设置保存失败");
            version = 1L;
        } else {
            if (canvasMapper.updateProjectSetting(request.getProjectId(), request.getSettingKey(), request.getExpectedVersion(), json, userId) != 1) {
                throw new ServiceException("项目设置版本冲突");
            }
            version = request.getExpectedVersion() + 1;
        }
        return Map.of("settingKey", request.getSettingKey(), "value", request.getValue(), "version", version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateProject(CanvasRequests.UpdateProject request, Long userId) {
        accessService.requireOwner(request.getProjectId(), userId);
        if(request.getPublicCanvas()!=null) throw new ServiceException("公开状态必须通过发布审核管理");
        if (request.getProjectName() != null && request.getProjectName().trim().isEmpty()) throw new ServiceException("项目名称不能为空");
        int updated = canvasMapper.updateCanvasProject(request.getProjectId(), userId,
                request.getProjectName() == null ? null : request.getProjectName().trim(),
                request.getProjectDesc() == null ? null : request.getProjectDesc().trim(),
                request.getCoverUrl() == null ? null : request.getCoverUrl().trim());
        if (updated != 1) throw new ServiceException("项目不存在或已被修改");
        return projectDetail(request.getProjectId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long projectId, Long userId) {
        accessService.requireOwner(projectId, userId);
        canvasMapper.lockProjectMemberQuota(projectId);
        if (canvasMapper.countRunningCanvasTasks(projectId) > 0) throw new ServiceException("项目仍有生成任务运行，请先取消并等待任务终态");
        canvasMapper.deactivateProjectWorkspaces(projectId);
        canvasMapper.deactivateProjectMembers(projectId);
        canvasMapper.revokeProjectInvites(projectId);
        canvasMapper.revokeProjectShares(projectId);
        if (canvasMapper.softDeleteCanvasProject(projectId, userId) != 1) throw new ServiceException("项目删除失败");
        afterCommit(() -> collaborationService.listSessions(projectId, null).forEach(s -> collaborationService.terminateSession(
                Objects.toString(s.get("sessionId")), "项目已删除")));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> loadWorkspace(CanvasRequests.LoadWorkspace request, Long userId) {
        accessService.requireProject(request.getProjectId(), userId);
        Workspace workspace = canvasMapper.selectWorkspaceByProject(request.getProjectId());
        if (workspace == null) throw new ServiceException("项目工作区不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", workspaceView(workspace));
        CanvasPublicationState publicationState = publicationMapper.state(request.getProjectId());
        result.put("atomicSceneCommit", true);
        result.put("sceneVersion", publicationState == null ? 0L : publicationState.getSceneVersion());
        result.put("sceneDocument", publicationState == null || publicationState.getSceneDocumentJson() == null
                ? Map.of("schemaVersion", 1, "scenes", List.of()) : JSON.parse(publicationState.getSceneDocumentJson()));
        result.put("tasks", taskViews(canvasMapper.selectWorkspaceLatestTaskBindings(workspace.getId())));
        List<Map<String, Object>> currentResults = currentResultViews(workspace.getId());
        result.put("currentResults", currentResults);
        List<CanvasObject> activeObjects = canvasMapper.selectWorkspaceObjects(workspace.getId(), false);
        List<com.aid.canvas.domain.CanvasEntities.CanvasResource> loadedResources = new ArrayList<>(referencedResources(workspace.getId(), activeObjects, currentResults));
        if (publicationState != null && publicationState.getSceneDocumentJson() != null) {
            Set<Long> sceneResourceIds = new java.util.LinkedHashSet<>();
            collectSceneResources(JSON.parse(publicationState.getSceneDocumentJson()), sceneResourceIds);
            loadedResources.forEach(resource -> sceneResourceIds.remove(resource.getId()));
            List<Long> ids = new ArrayList<>(sceneResourceIds);
            for (int from=0;from<ids.size();from+=RESOURCE_QUERY_BATCH) loadedResources.addAll(canvasMapper.selectCanvasResources(
                    workspace.getId(),ids.subList(from,Math.min(from+RESOURCE_QUERY_BATCH,ids.size()))));
        }
        Set<Long> seenBundleResources=new java.util.LinkedHashSet<>();loadedResources.forEach(r->seenBundleResources.add(r.getId()));
        for(int i=0;i<loadedResources.size();i++){
            var resource=loadedResources.get(i);
            if(!java.util.Set.of("MODEL","SCENE_PACKAGE").contains(resource.getMediaType()))continue;
            JSONObject bundle=com.aid.canvas.publication.service.CanvasPublicationBundles.read(resource.getMetadataJson());
            if(bundle==null)continue;
            List<Long> missing=new ArrayList<>();for(Object raw:bundle.getJSONArray("files")){Long id=((JSONObject)raw).getLong("resourceId");if(seenBundleResources.add(id))missing.add(id);}
            if(seenBundleResources.size()>10000)throw new ServiceException("模型闭包超限");
            for(int offset=0;offset<missing.size();offset+=RESOURCE_QUERY_BATCH)loadedResources.addAll(canvasMapper.selectCanvasResources(workspace.getId(),missing.subList(offset,Math.min(offset+RESOURCE_QUERY_BATCH,missing.size()))));
        }
        result.put("resources", resourceViews(loadedResources));
        if (request.getSinceSeq() != null && request.getSinceSeq() >= 0 && request.getSinceSeq() < workspace.getEventSeq()) {
            List<Operation> changes = canvasMapper.selectOperationsAfter(workspace.getId(), request.getSinceSeq(), 200);
            if (!changes.isEmpty() && Objects.equals(changes.get(0).getEventSeq(), request.getSinceSeq() + 1)) {
                result.put("mode", "CHANGES");
                result.put("changes", operationViews(changes));
                long lastSeq = changes.get(changes.size() - 1).getEventSeq();
                result.put("lastSeq", lastSeq);
                result.put("currentSeq", workspace.getEventSeq());
                result.put("hasMore", lastSeq < workspace.getEventSeq());
                return result;
            }
        }
        result.put("mode", "SNAPSHOT");
        result.put("objects", objectViews(activeObjects));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> mutate(CanvasRequests.Mutate request, Long userId) {
        Workspace allowed = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        if (request.getMutations() == null || (request.getMutations().isEmpty() && request.getSceneWrite() == null)) {
            throw new ServiceException("请提供节点或场景修改");
        }
        String requestHash = mutationRequestHash(request.getMutations());
        String operationType = "MUTATE";
        CanvasPublicationState sceneState = null;
        if (request.getSceneWrite() != null) {
            sceneState = publicationService.lockWorkspaceSceneWrite(allowed.getProjectId(), userId);
            CanvasRequests.SceneWrite write = request.getSceneWrite();
            Map<String, Object> sceneHashInput = new LinkedHashMap<>();
            sceneHashInput.put("document", write.getDocument());
            sceneHashInput.put("expectedVersion", write.getExpectedVersion());
            if (write.getAdoption() != null) sceneHashInput.put("adoption", write.getAdoption());
            requestHash = sha256(requestHash + ":" + CanvasAssistantOperationCanonicalizer.explicitNullJson(sceneHashInput));
            operationType = "SCENE_MUTATE";
        }
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(request.getWorkspaceId());
        if (workspace == null) throw new ServiceException("工作区不存在或已失效");
        if (request.getSceneWrite() != null && request.getSceneWrite().getAdoption() != null) {
            CanvasRequests.SceneAdoption adoption = request.getSceneWrite().getAdoption();
            if (!request.getMutations().isEmpty()) throw new ServiceException("采纳场景前请先保存其他画布修改");
            collaborationService.requireEditSession(workspace.getId(), request.getSessionId(), userId);
            JSONObject submitted = JSON.parseObject(JSON.toJSONString(request.getSceneWrite().getDocument()));
            if (!hasSceneAdoption(submitted, adoption)) throw new ServiceException("场景缺少对应采纳回执");
            String key = "adopt_" + sha256(JSON.toJSONString(List.of(adoption.getSceneId(), adoption.getProjectId(), adoption.getStepId()))).substring(0, 58);
            Operation receipt = canvasMapper.selectOperation(workspace.getId(), key);
            JSONObject current = JSON.parseObject(sceneState.getSceneDocumentJson());
            if (receipt != null) {
                if (!"SCENE_ADOPT".equals(receipt.getOperationType()) || !hasSceneAdoption(current, adoption)) {
                    throw new ServiceException("采纳已撤销或场景已变更，请重新核对");
                }
                Map<String, Object> replay = JSON.parseObject(receipt.getPayloadJson(), LinkedHashMap.class);
                replay.put("adoptionReplay", true);
                replay.put("adoptionDocument", current);
                return replay;
            }
            if (hasSceneAdoption(current, adoption)) throw new ServiceException("结果已经采纳，请重新读取场景");
            if (current == null || current.getJSONArray("scenes") == null
                    || !otherScenes(current, adoption.getSceneId()).equals(otherScenes(submitted, adoption.getSceneId()))) {
                throw new ServiceException("采纳前请先保存其他场景的修改");
            }
            request.setOperationId(key);
            return mutateInternal(request, userId, workspace, "SCENE_ADOPT", requestHash, null,
                    Map.of("adoptionOperationId", key));
        }
        return mutateInternal(request, userId, workspace, operationType, requestHash, null, Map.of());
    }

    private static boolean hasSceneAdoption(JSONObject document, CanvasRequests.SceneAdoption adoption) {
        if (document == null || document.getJSONArray("scenes") == null) return false;
        var scenes = document.getJSONArray("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            JSONObject scene = scenes.getJSONObject(index);
            if (scene == null || !adoption.getSceneId().equals(scene.getString("id"))) continue;
            JSONObject modeling = scene.getJSONObject("modeling");
            JSONObject receipt = modeling == null ? null : modeling.getJSONObject("adoption");
            return receipt != null && adoption.getProjectId().equals(receipt.getString("workspace"))
                    && receipt.getJSONArray("steps") != null && receipt.getJSONArray("steps").contains(adoption.getStepId());
        }
        return false;
    }

    private static List<Object> otherScenes(JSONObject document, String targetId) {
        var scenes = document.getJSONArray("scenes");
        List<Object> result = new ArrayList<>();
        for (int index = 0; index < scenes.size(); index++) {
            JSONObject scene = scenes.getJSONObject(index);
            if (scene == null || !targetId.equals(scene.getString("id"))) result.add(scene);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createAgentTextNode(Long workspaceId, Long userId, String operationId,
                                                   String objectId, String title, String content) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(workspaceId);
        if (workspace == null) throw new ServiceException("工作区不存在或已失效");
        Operation existing = canvasMapper.selectOperation(workspaceId, operationId);
        if (existing != null) return duplicateOperation(existing, existing.getPayloadHash(), "AGENT_TEXT", userId);
        CanvasRequests.Mutate request = new CanvasRequests.Mutate();
        request.setWorkspaceId(workspaceId);
        request.setOperationId(operationId);
        request.setSessionId("server-agent");
        CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
        mutation.setObjectId(objectId);
        mutation.setObjectType("NODE");
        mutation.setAction("UPSERT");
        mutation.setExpectedVersion(0L);
        mutation.setLockToken("server-agent");
        double resultX = 160;
        for (CanvasObject existingNode : canvasMapper.selectWorkspaceObjects(workspaceId, false)) {
            if (!"NODE".equals(existingNode.getObjectType())) continue;
            JSONObject payload = JSON.parseObject(existingNode.getContentJson());
            JSONObject position = payload == null ? null : payload.getJSONObject("position");
            JSONObject size = payload == null ? null : payload.getJSONObject("size");
            if (position != null) resultX = Math.max(resultX,
                    position.getDoubleValue("x") + (size == null ? 320 : size.getDoubleValue("width")) + 40);
        }
        mutation.setPayload(ordered("nodeType", "TEXT", "title", title,
                "position", ordered("x", resultX, "y", 180), "size", ordered("width", 360, "height", 260),
                "prompt", "", "textContent", content, "parameters", Map.of(), "resourceIds", List.of(),
                "inputPorts", List.of(), "metadata", ordered("studioData", ordered("kind", "text", "title", title,
                        "documentContent", content, "prompt", ""))));
        request.setMutations(List.of(mutation));
        // The operation ID is stable for retries. The new object has a server-owned fence and never
        // impersonates a browser edit lease; the regular mutation path still checks quotas and emits changes.
        canvasMapper.upsertObjectFence(workspaceId, objectId, 0L);
        return mutateInternal(request, userId, workspace, "AGENT_TEXT",
                mutationRequestHash(request.getMutations()), Map.of(objectId, 0L), Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mutateAssistantNode(Long workspaceId, Long userId,
                                                   CanvasProjectService.AssistantNodeMutation input) {
        RuntimeOperationSubmissionDecision decision = runtimeSubmissionGuard.claim(
                input == null ? null : input.submissionClaim());
        requireSubmissionIdentity(input, userId);
        AssistantPrepared prepared = assistantPrepared(workspaceId, userId, input);
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            Operation existing = canvasMapper.selectOperation(workspaceId, input.operationId());
            if (existing == null) throw assistantFailure("CANVAS_RECEIPT_PENDING");
            return duplicateAssistantOperation(existing, prepared, input, workspaceId, userId);
        }
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(workspaceId);
        if (workspace == null) throw assistantFailure("CANVAS_WORKSPACE_MISSING");
        Operation existing = canvasMapper.selectOperation(workspaceId, input.operationId());
        if (existing != null) {
            return duplicateAssistantOperation(existing, prepared, input, workspaceId, userId);
        }
        Map<String, Long> fences;
        if (ASSISTANT_CREATE_ACTIONS.contains(input.action())) {
            if (input.expectedWorkspaceRevision() == null
                    || !Objects.equals(workspace.getRevision(), input.expectedWorkspaceRevision())) {
                throw assistantFailure("CANVAS_WORKSPACE_REVISION_CHANGED");
            }
            runtimeScopeAccess.authorizeWorkspace(input.runId(), userId,
                    new RuntimeScopeAccess.Lease(input.runtimeLeaseOwner(), input.runtimeFencingToken(),
                            input.runtimeLeaseUntil()), workspace.getProjectId(), workspaceId);
            for (var source : input.relations()) {
                runtimeScopeAccess.authorizeExactTarget(input.runId(), userId,
                        new RuntimeScopeAccess.Lease(input.runtimeLeaseOwner(), input.runtimeFencingToken(),
                                input.runtimeLeaseUntil()), workspace.getProjectId(), workspaceId,
                        source.objectId(), source.objectVersion());
            }
            placeAssistantNode(prepared.request(), workspaceId, input.relations());
            fences = new LinkedHashMap<>();
            for (CanvasRequests.Mutation mutation : prepared.request().getMutations()) {
                canvasMapper.upsertObjectFence(workspaceId, mutation.getObjectId(), 0L);
                fences.put(mutation.getObjectId(), 0L);
            }
        } else {
            long currentFence = collaborationService.requireAssistantRuntimeFenceForMutation(workspaceId,
                    input.sourceObjectId(), input.sourceObjectVersion(), input.nodeFence(), userId, input.runId());
            if (input.nodeFence() == null || !Objects.equals(input.nodeFence(), currentFence)) {
                throw assistantFailure("CANVAS_NODE_FENCE_CHANGED");
            }
            fences = Map.of(input.targetObjectId(), currentFence);
        }
        return mutateInternal(prepared.request(), userId, workspace, prepared.operationType(),
                prepared.requestHash(), fences, prepared.receipt());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> replayAssistantNodeMutation(Long workspaceId, Long userId,
                                                           CanvasProjectService.AssistantNodeMutation input) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        AssistantPrepared prepared = assistantPrepared(workspaceId, userId, input);
        if (ASSISTANT_CREATE_ACTIONS.contains(input.action())) {
            Workspace workspace = canvasMapper.selectWorkspace(workspaceId);
            if (workspace == null) throw assistantFailure("CANVAS_WORKSPACE_MISSING");
            runtimeScopeAccess.authorizeWorkspace(input.runId(), userId,
                    new RuntimeScopeAccess.Lease(input.runtimeLeaseOwner(), input.runtimeFencingToken(),
                            input.runtimeLeaseUntil()), workspace.getProjectId(), workspaceId);
        }
        Operation existing = canvasMapper.selectOperation(workspaceId, input.operationId());
        return existing == null ? null
                : duplicateAssistantOperation(existing, prepared, input, workspaceId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> reconcileAssistantNodeMutation(Long workspaceId, Long userId,
                                                              CanvasProjectService.AssistantNodeMutation input) {
        AssistantPrepared prepared = assistantPrepared(workspaceId, userId, input);
        Operation existing = canvasMapper.selectOperation(workspaceId, input.operationId());
        return existing == null ? null
                : duplicateAssistantOperation(existing, prepared, input, workspaceId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> copyObjects(CanvasRequests.CopyObjects request, Long userId) {
        Workspace allowed = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        boolean includesScene = request.getSources().stream()
                .map(source -> canvasMapper.selectCanvasObject(allowed.getId(), source.getSourceObjectId()))
                .anyMatch(object -> object != null && "NODE".equals(object.getObjectType())
                        && directorReference(object.getContentJson()) != null);
        CanvasPublicationState sceneState = includesScene
                ? publicationService.lockWorkspaceSceneWrite(allowed.getProjectId(), userId) : null;
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(request.getWorkspaceId());
        if (workspace == null) throw new ServiceException("工作区不存在或已失效");
        String requestHash = sha256(JSON.toJSONString(request.getSources()));
        Operation existing = canvasMapper.selectOperation(workspace.getId(), request.getOperationId());
        if (existing != null) return duplicateOperation(existing, requestHash, "COPY", userId);

        Map<String, CanvasObject> sources = new LinkedHashMap<>();
        Map<String, String> idMap = new LinkedHashMap<>();
        for (CanvasRequests.CopySource source : request.getSources()) {
            if (sources.containsKey(source.getSourceObjectId())) throw new ServiceException("复制对象标识不能重复");
            CanvasObject object = canvasMapper.selectCanvasObjectForUpdate(workspace.getId(), source.getSourceObjectId());
            if (object == null || object.getDeleted() == 1) throw new ServiceException("复制源对象不存在: " + source.getSourceObjectId());
            if (!Objects.equals(object.getObjectVersion(), source.getExpectedVersion())) {
                throw new ServiceException("复制源对象版本冲突: " + source.getSourceObjectId());
            }
            sources.put(source.getSourceObjectId(), object);
            idMap.put(source.getSourceObjectId(), UUID.randomUUID().toString().replace("-", ""));
        }

        CanvasRequests.Mutate mutationRequest = new CanvasRequests.Mutate();
        mutationRequest.setWorkspaceId(workspace.getId());
        mutationRequest.setOperationId(request.getOperationId());
        mutationRequest.setSessionId("server-copy");
        List<CanvasRequests.Mutation> mutations = new ArrayList<>();
        Map<String, Long> serverFences = new LinkedHashMap<>();
        JSONObject sceneDocument = sceneState == null || blank(sceneState.getSceneDocumentJson())
                ? null : JSON.parseObject(sceneState.getSceneDocumentJson());
        Map<String, String> copiedScenes = new LinkedHashMap<>();
        sources.forEach((sourceId, source) -> {
            String targetId = idMap.get(sourceId);
            JSONObject content = JSON.parseObject(source.getContentJson());
            stripRuntimeIdentity(content);
            remapReferencesRecursive(content, idMap);
            if ("NODE".equals(source.getObjectType()) && "DIRECTOR".equals(content.getString("nodeType"))) {
                if (sceneDocument == null) throw new ServiceException("3D预演场景待恢复，无法复制");
                JSONObject metadata = content.getJSONObject("metadata");
                JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
                String sceneId = studio == null ? null : studio.getString("directorSceneId");
                if (blank(sceneId)) throw new ServiceException("3D预演场景待恢复，无法复制");
                String copiedId = copiedScenes.computeIfAbsent(sceneId, id -> copyScene(sceneDocument, id, idMap));
                studio.put("directorSceneId", copiedId);
            }
            CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
            mutation.setObjectId(targetId);
            mutation.setObjectType(source.getObjectType());
            mutation.setAction("UPSERT");
            mutation.setExpectedVersion(0L);
            mutation.setLockToken("server-copy");
            mutation.setPayload(content);
            mutations.add(mutation);
            canvasMapper.upsertObjectFence(workspace.getId(), targetId, 0L);
            serverFences.put(targetId, 0L);
        });
        mutationRequest.setMutations(mutations);
        if (!copiedScenes.isEmpty()) {
            CanvasRequests.SceneWrite sceneWrite = new CanvasRequests.SceneWrite();
            sceneWrite.setExpectedVersion(sceneState.getSceneVersion());
            sceneWrite.setDocument(sceneDocument);
            mutationRequest.setSceneWrite(sceneWrite);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("idMap", idMap);
        return mutateInternal(mutationRequest, userId, workspace, "COPY", requestHash, serverFences, extra);
    }

    private static String copyScene(JSONObject document, String sourceId, Map<String, String> nodeIds) {
        JSONArray scenes = document.getJSONArray("scenes");
        if (scenes == null) throw new ServiceException("3D预演场景待恢复，无法复制");
        JSONObject source = scenes.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .filter(scene -> sourceId.equals(scene.getString("id"))).findFirst()
                .orElseThrow(() -> new ServiceException("3D预演场景待恢复，无法复制"));
        JSONObject copy = JSON.parseObject(source.toJSONString());
        String id = UUID.randomUUID().toString();
        copy.put("id", id);
        JSONObject modeling = copy.getJSONObject("modeling");
        if (modeling != null) modeling.remove("adoption");
        JSONArray references = copy.getJSONArray("sourceNodeIds");
        if (references != null) {
            references.replaceAll(reference -> nodeIds.getOrDefault(reference.toString(), reference.toString()));
        }
        for (Object row : copy.getJSONArray("objects")) {
            if (row instanceof JSONObject object && nodeIds.containsKey(object.getString("sourceNodeId"))) {
                object.put("sourceNodeId", nodeIds.get(object.getString("sourceNodeId")));
            }
        }
        JSONObject timeline = copy.getJSONObject("timeline");
        JSONArray clips = timeline == null ? null : timeline.getJSONArray("pathClips");
        if (clips != null) {
            for (Object clip : clips) {
                if (clip instanceof JSONObject path) path.remove("takeId");
            }
        }
        scenes.add(copy);
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> undo(CanvasRequests.UndoOperation request, Long userId) {
        Workspace allowed = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        Operation target = canvasMapper.selectOperation(allowed.getId(), request.getTargetOperationId());
        if (target == null || !Objects.equals(target.getActorUserId(), userId)) {
            throw new ServiceException("只能撤销本人已提交的画布操作");
        }
        Long targetSceneVersion = JSON.parseObject(target.getPayloadJson()).getLong("sceneVersion");
        CanvasPublicationState sceneState = targetSceneVersion == null ? null
                : publicationService.lockWorkspaceSceneWrite(allowed.getProjectId(), userId);
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(request.getWorkspaceId());
        if (workspace == null) throw new ServiceException("工作区不存在或已失效");
        String requestHash = sha256(request.getTargetOperationId());
        Operation existing = canvasMapper.selectOperation(workspace.getId(), request.getOperationId());
        if (existing != null) return duplicateOperation(existing, requestHash, "UNDO", userId);
        List<OperationObject> records = canvasMapper.selectOperationObjects(target.getId());
        if (records.isEmpty() && sceneState == null) throw new ServiceException("该操作不支持撤销");
        Map<String, String> lockTokens = new LinkedHashMap<>();
        for (CanvasRequests.LockItem lock : request.getLocks()) {
            if (lockTokens.putIfAbsent(lock.getObjectId(), lock.getLockToken()) != null) {
                throw new ServiceException("撤销锁对象不能重复");
            }
        }
        List<CanvasRequests.Mutation> reverse = new ArrayList<>();
        for (OperationObject record : records) {
            CanvasObject current = canvasMapper.selectCanvasObjectForUpdate(workspace.getId(), record.getObjectId());
            boolean currentDeleted = current != null && current.getDeleted() != null && current.getDeleted() == 1;
            if (current == null || !Objects.equals(current.getObjectVersion(), record.getAfterVersion())
                    || currentDeleted != Objects.equals(record.getAfterDeleted(), 1)) {
                throw new ServiceException("对象已被后续操作修改，不能覆盖协作者内容: " + record.getObjectId());
            }
            String lockToken = lockTokens.get(record.getObjectId());
            if (blank(lockToken)) throw new ServiceException("撤销缺少对象编辑锁: " + record.getObjectId());
            CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
            mutation.setObjectId(record.getObjectId());
            mutation.setObjectType(record.getObjectType());
            mutation.setExpectedVersion(current.getObjectVersion());
            mutation.setLockToken(lockToken);
            if (record.getBeforeVersion() == 0L || Objects.equals(record.getBeforeDeleted(), 1)) {
                mutation.setAction("DELETE");
            } else {
                mutation.setAction("UPSERT");
                mutation.setPayload(JSON.parse(record.getBeforeContentJson()));
            }
            reverse.add(mutation);
        }
        if (lockTokens.size() != records.size()) throw new ServiceException("撤销锁集合与目标操作对象不一致");
        CanvasRequests.Mutate mutationRequest = new CanvasRequests.Mutate();
        mutationRequest.setWorkspaceId(workspace.getId());
        mutationRequest.setOperationId(request.getOperationId());
        mutationRequest.setSessionId(request.getSessionId());
        mutationRequest.setMutations(reverse);
        if (sceneState != null) {
            if (!Objects.equals(sceneState.getSceneVersion(), targetSceneVersion)) {
                throw new ServiceException("场景已被后续操作修改，不能覆盖当前场景");
            }
            String before = publicationMapper.sceneBefore(workspace.getProjectId(), targetSceneVersion);
            if (blank(before)) throw new ServiceException("该场景的撤销历史已过期");
            CanvasRequests.SceneWrite sceneWrite = new CanvasRequests.SceneWrite();
            sceneWrite.setExpectedVersion(targetSceneVersion);
            JSONObject restored = JSON.parseObject(before);
            if (JSON.parseObject(sceneState.getSceneDocumentJson()).getIntValue("schemaVersion") == 2) {
                restored.put("schemaVersion", 2);
            }
            sceneWrite.setDocument(restored);
            mutationRequest.setSceneWrite(sceneWrite);
        }
        return mutateInternal(mutationRequest, userId, workspace, "UNDO", requestHash, null,
                Map.of("targetOperationId", request.getTargetOperationId()));
    }

    private Map<String, Object> mutateInternal(CanvasRequests.Mutate request, Long userId, Workspace workspace,
                                               String operationType, String requestHash,
                                               Map<String, Long> prevalidatedFences,
                                               Map<String, Object> extraResult) {
        Operation existing = canvasMapper.selectOperation(workspace.getId(), request.getOperationId());
        if (existing != null) {
            if ("MUTATE".equals(operationType)
                    && Objects.equals(workspace.getId(), existing.getWorkspaceId())
                    && Objects.equals(request.getOperationId(), existing.getOperationId())
                    && CanvasAssistantOperationCanonicalizer.mutationsContainExplicitNull(request.getMutations())
                    && !Objects.equals(requestHash, existing.getPayloadHash())) {
                String legacyHash = CanvasAssistantOperationCanonicalizer
                        .legacyMutationRequestHash(request.getMutations());
                if (Objects.equals(legacyHash, existing.getPayloadHash())) {
                    return duplicateOperation(existing, legacyHash, operationType, userId);
                }
            }
            return duplicateOperation(existing, requestHash, operationType, userId);
        }
        String requestJson = CanvasAssistantOperationCanonicalizer.explicitNullJson(request.getMutations());
        Map<String, Object> config = accessService.config();
        int maxBatch = Math.min(100, asInt(config.get("operationBatchMax"), 50));
        int maxBytes = Math.min(1024 * 1024, asInt(config.get("operationPayloadMaxBytes"), 262144));
        if (request.getMutations().size() > maxBatch) throw new ServiceException("单次画布操作数量超限");
        if (requestJson.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new ServiceException("单次画布操作内容过大");
        if (request.getMutations().isEmpty() && request.getSceneWrite() != null) {
            collaborationService.requireEditSession(workspace.getId(), request.getSessionId(), userId);
        }
        Map<String, Long> fences = new LinkedHashMap<>();
        CanvasPublicationState state = request.getSceneWrite() == null ? publicationMapper.state(workspace.getProjectId()) : null;
        boolean needsSceneWrite = state != null && Boolean.TRUE.equals(state.getAtomicSceneWrite());
        Map<String, String> legacySceneGaps = request.getSceneWrite() == null ? Map.of()
                : publicationService.legacySceneGaps(workspace.getProjectId());
        Set<String> uniqueObjectIds = new java.util.HashSet<>();
        for (CanvasRequests.Mutation mutation : request.getMutations()) {
            validateMutation(mutation);
            if (!uniqueObjectIds.add(mutation.getObjectId())) throw new ServiceException("同一批次不能重复操作对象: " + mutation.getObjectId());
            Long fence = prevalidatedFences == null ? collaborationService.requireEditFence(workspace.getId(), mutation.getObjectId(),
                    request.getSessionId(), mutation.getLockToken(), userId) : prevalidatedFences.get(mutation.getObjectId());
            if (fence == null) throw new ServiceException("对象编辑凭证缺失: " + mutation.getObjectId());
            fences.put(mutation.getObjectId(), fence);
        }

        int currentNodeCount = workspace.getNodeCount() == null ? 0 : workspace.getNodeCount();
        int nodeDelta = 0, edgeDelta = 0, groupDelta = 0;
        long storageByteDelta = 0;
        List<Map<String, Object>> changed = new ArrayList<>();
        List<OperationObject> operationObjects = new ArrayList<>();
        for (CanvasRequests.Mutation mutation : request.getMutations()) {
            CanvasObject current = canvasMapper.selectCanvasObjectForUpdate(workspace.getId(), mutation.getObjectId());
            long actualVersion = current == null ? 0L : current.getObjectVersion();
            if (actualVersion != mutation.getExpectedVersion()) {
                throw new ServiceException("对象版本冲突: " + mutation.getObjectId());
            }
            boolean delete = "DELETE".equals(mutation.getAction());
            if ("PATCH".equals(mutation.getAction()) && (current == null || current.getDeleted() == 1)) {
                throw new ServiceException("PATCH 只能更新未删除的对象: " + mutation.getObjectId());
            }
            Object payload = mutation.getPayload();
            if ("PATCH".equals(mutation.getAction())) {
                JSONObject merged = JSON.parseObject(current.getContentJson());
                mergePatch(merged, JSON.parseObject(
                        CanvasAssistantOperationCanonicalizer.explicitNullJson(payload)));
                payload = merged;
            }
            String contentJson = delete ? null : validatedContent(mutation.getObjectType(), payload, workspace.getId(), userId);
            if (needsSceneWrite && "NODE".equals(mutation.getObjectType())) {
                String before = current == null || current.getDeleted() == 1 ? null : current.getContentJson();
                if (!Objects.equals(directorReference(before), directorReference(contentJson))) {
                    throw new ServiceException("预演台节点引用变更须同时提交场景，请更新客户端");
                }
            }
            int activeDelta = (delete ? 0 : 1) - (current != null && current.getDeleted() == 0 ? 1 : 0);
            if ("NODE".equals(mutation.getObjectType())) nodeDelta += activeDelta;
            if ("EDGE".equals(mutation.getObjectType())) edgeDelta += activeDelta;
            if ("GROUP".equals(mutation.getObjectType())) groupDelta += activeDelta;
            storageByteDelta += utf8Length(contentJson) - (current == null || current.getDeleted() == 1 ? 0 : utf8Length(current.getContentJson()));
            CanvasObject persisted;
            if (current == null) {
                if (delete) throw new ServiceException("不能删除不存在的对象: " + mutation.getObjectId());
                persisted = new CanvasObject();
                persisted.setProjectId(workspace.getProjectId());
                persisted.setWorkspaceId(workspace.getId());
                persisted.setObjectId(mutation.getObjectId());
                persisted.setObjectType(mutation.getObjectType());
                persisted.setObjectVersion(1L);
                persisted.setLockFence(fences.get(mutation.getObjectId()));
                persisted.setContentJson(contentJson);
                persisted.setContentHash(sha256(contentJson));
                persisted.setDeleted(0);
                persisted.setLastActorUserId(userId);
                if (canvasMapper.insertCanvasObject(persisted) != 1) throw new ServiceException("节点租约已变化，请重新提交");
            } else {
                if (!current.getObjectType().equals(mutation.getObjectType())) throw new ServiceException("对象类型不能变更");
                String hash = delete ? null : sha256(contentJson);
                if (canvasMapper.updateCanvasObject(workspace.getId(), mutation.getObjectId(), mutation.getExpectedVersion(),
                        contentJson, hash, delete ? 1 : 0, userId, fences.get(mutation.getObjectId())) != 1) {
                    throw new ServiceException("对象已被其他操作修改: " + mutation.getObjectId());
                }
                persisted = canvasMapper.selectCanvasObject(workspace.getId(), mutation.getObjectId());
            }
            changed.add(objectView(persisted));
            OperationObject record = new OperationObject();
            record.setProjectId(workspace.getProjectId());
            record.setWorkspaceId(workspace.getId());
            record.setOperationId(request.getOperationId());
            record.setObjectId(mutation.getObjectId());
            record.setObjectType(mutation.getObjectType());
            record.setBeforeVersion(actualVersion);
            record.setAfterVersion(persisted.getObjectVersion());
            record.setBeforeDeleted(current == null ? 1 : current.getDeleted());
            record.setAfterDeleted(persisted.getDeleted());
            record.setBeforeContentJson(current == null ? null : current.getContentJson());
            operationObjects.add(record);
        }
        Long ownerId = accessService.requireProject(workspace.getProjectId(), userId).getUserId();
        Long capacitySubjectId = entitlementSubjectResolver.resolve(EntitlementSubjectResolver.Strategy.PROJECT_OWNER,
                userId, ownerId, null).id();
        int ownerLimit = capacityLimit(capacitySubjectId, "canvas.node.max", "maxNodesPerWorkspace", 1000);
        if (currentNodeCount + nodeDelta > ownerLimit) throw new ServiceException("画布节点数量已达项目所有者权益上限");
        boolean graphMayChange = request.getMutations().stream().anyMatch(mutation ->
                !"NODE".equals(mutation.getObjectType()) || "DELETE".equals(mutation.getAction()));
        if (graphMayChange) validateGraph(workspace.getId());

        Map<String, Object> sceneResult = request.getSceneWrite() == null ? Map.of()
                : publicationService.saveWorkspaceScenes(workspace.getProjectId(), userId, request.getSceneWrite(), legacySceneGaps);

        if (canvasMapper.applyWorkspaceDelta(workspace.getId(), nodeDelta, edgeDelta, groupDelta, storageByteDelta) != 1)
            throw new ServiceException("工作区计数状态异常");
        Workspace updated = canvasMapper.selectWorkspace(workspace.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", updated.getRevision());
        result.put("eventSeq", updated.getEventSeq());
        result.put("objects", changed);
        result.putAll(extraResult);
        result.putAll(sceneResult);
        String resultJson = JSON.toJSONString(result);
        Operation operation = new Operation();
        operation.setProjectId(workspace.getProjectId());
        operation.setWorkspaceId(workspace.getId());
        operation.setOperationId(request.getOperationId());
        operation.setActorUserId(userId);
        operation.setWorkspaceRevision(updated.getRevision());
        operation.setEventSeq(updated.getEventSeq());
        operation.setOperationType(operationType);
        operation.setPayloadJson(resultJson);
        operation.setPayloadHash(requestHash);
        if (canvasMapper.insertOperation(operation) != 1) throw new ServiceException("画布操作日志写入失败");
        for (OperationObject record : operationObjects) {
            record.setOperationRowId(operation.getId());
        }
        if (!operationObjects.isEmpty() && canvasMapper.insertOperationObjects(operationObjects) != operationObjects.size()) {
            throw new ServiceException("画布撤销快照写入失败");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "WORKSPACE_COMMITTED");
        event.put("operationId", request.getOperationId());
        event.put("actorUserId", userId);
        event.putAll(result);
        publishAfterCommit(workspace.getId(), JSON.toJSONString(event));
        return result;
    }

    private static Map<String, Object> duplicateOperation(Operation operation, String requestHash, String operationType,
                                                          Long actorUserId) {
        if (!Objects.equals(actorUserId, operation.getActorUserId())
                || !requestHash.equals(operation.getPayloadHash())
                || !operationType.equals(operation.getOperationType())) {
            throw new ServiceException("operationId 已被不同内容使用");
        }
        return JSON.parseObject(operation.getPayloadJson(), LinkedHashMap.class);
    }

    private static String directorReference(String contentJson) {
        if (contentJson == null) return null;
        JSONObject content = JSON.parseObject(contentJson);
        if (!"DIRECTOR".equals(content.getString("nodeType"))) return null;
        JSONObject metadata = content.getJSONObject("metadata");
        JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
        return "DIRECTOR:" + (studio == null ? "" : Objects.toString(studio.get("directorSceneId"), ""));
    }

    @Override
    public Map<String, Object> changes(CanvasRequests.Changes request, Long userId) {
        accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        List<Operation> operations = canvasMapper.selectOperationsAfter(request.getWorkspaceId(), request.getAfterSeq(),
                request.getLimit() == null ? 200 : request.getLimit());
        Workspace workspace = canvasMapper.selectWorkspace(request.getWorkspaceId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", operationViews(operations));
        long lastSeq = operations.isEmpty() ? request.getAfterSeq() : operations.get(operations.size() - 1).getEventSeq();
        result.put("lastSeq", lastSeq);
        result.put("currentSeq", workspace.getEventSeq());
        result.put("hasMore", lastSeq < workspace.getEventSeq());
        return result;
    }

    @Override
    public Map<String, Object> loadView(Long workspaceId, Long userId) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR", "VIEWER");
        UserView view = canvasMapper.selectUserView(workspaceId, userId);
        if (view == null) return Map.of("viewVersion", 0L, "view", Map.of());
        return Map.of("viewVersion", view.getViewVersion(), "view", JSON.parse(view.getViewJson()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveView(CanvasRequests.SaveView request, Long userId) {
        accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        String json = JSON.toJSONString(request.getView());
        if (json.getBytes(StandardCharsets.UTF_8).length > 32 * 1024) throw new ServiceException("个人视角数据不能超过32KB");
        UserView existing = canvasMapper.selectUserView(request.getWorkspaceId(), userId);
        long version;
        if (existing == null) {
            if (request.getExpectedVersion() != 0) throw new ServiceException("个人视角版本冲突");
            UserView view = new UserView();
            view.setWorkspaceId(request.getWorkspaceId());
            view.setUserId(userId);
            view.setViewVersion(1L);
            view.setViewJson(json);
            canvasMapper.insertUserView(view);
            version = 1L;
        } else {
            if (canvasMapper.updateUserView(request.getWorkspaceId(), userId, request.getExpectedVersion(), json) != 1)
                throw new ServiceException("个人视角版本冲突");
            version = request.getExpectedVersion() + 1;
        }
        return Map.of("viewVersion", version);
    }

    @Override
    public Map<String, Object> loadDrafts(Long workspaceId, Long userId) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        List<NodeDraft> drafts = canvasMapper.selectNodeDrafts(workspaceId, userId);
        Map<String, Long> objectVersions = new LinkedHashMap<>();
        for (CanvasObject object : canvasMapper.selectWorkspaceObjects(workspaceId, true)) {
            objectVersions.put(object.getObjectId(), object.getObjectVersion());
        }
        List<Map<String, Object>> items = drafts.stream().map(draft -> draftView(draft, objectVersions.get(draft.getObjectId()))).toList();
        return Map.of("items", items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveDraft(CanvasRequests.SaveDraft request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        if (canvasMapper.selectWorkspaceForUpdate(workspace.getId()) == null) throw new ServiceException("工作区不存在或已失效");
        CanvasObject object = canvasMapper.selectCanvasObject(workspace.getId(), request.getObjectId());
        if (object == null || object.getDeleted() == 1 || !"NODE".equals(object.getObjectType())) {
            throw new ServiceException("草稿只能保存到未删除的节点");
        }
        String json;
        try {
            Object parsed = JSON.parse(JSON.toJSONString(request.getPayload()));
            if (parsed instanceof JSONObject textDraft) normalizeTextNode(textDraft);
            validateJsonShape(parsed, 0, new int[]{0});
            rejectForbiddenRecursive(parsed, parsed instanceof JSONObject draft
                    && "TEXT".equalsIgnoreCase(draft.getString("nodeType")));
            json = JSON.toJSONString(parsed);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("草稿内容不是合法 JSON");
        }
        if (utf8Length(json) > MAX_DRAFT_BYTES) throw new ServiceException("单个节点草稿不能超过256KB");
        NodeDraft existing = canvasMapper.selectNodeDraft(workspace.getId(), request.getObjectId(), userId);
        long version;
        if (existing == null) {
            if (request.getExpectedDraftVersion() != 0L) throw new ServiceException("草稿版本冲突");
            NodeDraft draft = new NodeDraft();
            draft.setProjectId(workspace.getProjectId());
            draft.setWorkspaceId(workspace.getId());
            draft.setObjectId(request.getObjectId());
            draft.setUserId(userId);
            draft.setDraftVersion(1L);
            draft.setBaseObjectVersion(request.getBaseObjectVersion());
            draft.setDraftJson(json);
            draft.setDraftHash(sha256(json));
            if (canvasMapper.insertNodeDraft(draft) != 1) throw new ServiceException("草稿保存失败");
            existing = draft;
            version = 1L;
        } else {
            if (canvasMapper.updateNodeDraft(workspace.getId(), request.getObjectId(), userId,
                    request.getExpectedDraftVersion(), request.getBaseObjectVersion(), json, sha256(json)) != 1) {
                throw new ServiceException("草稿版本冲突");
            }
            existing = canvasMapper.selectNodeDraft(workspace.getId(), request.getObjectId(), userId);
            version = request.getExpectedDraftVersion() + 1;
        }
        Map<String, Object> result = draftView(existing, object == null ? 0L : object.getObjectVersion());
        result.put("draftVersion", version);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(CanvasRequests.DeleteDraft request, Long userId) {
        accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        if (canvasMapper.deleteNodeDraft(request.getWorkspaceId(), request.getObjectId(), userId,
                request.getExpectedDraftVersion()) != 1) throw new ServiceException("草稿不存在或版本冲突");
    }

    @Override
    public Map<String, Object> members(Long projectId, Long userId) {
        accessService.requireProject(projectId, userId);
        return Map.of("items", canvasMapper.selectMembers(projectId).stream()
                .map(item -> JsonSafeId.fields(item, "id", "projectId", "userId")).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberRole(CanvasRequests.MemberRole request, Long userId) {
        accessService.requireOwner(request.getProjectId(), userId);
        String role = normalizeMemberRole(request.getRoleCode());
        if (canvasMapper.updateMemberRole(request.getProjectId(), request.getUserId(), role) != 1) throw new ServiceException("成员不存在或角色不可修改");
        if ("VIEWER".equals(role)) afterCommit(() -> closeMemberSessions(request.getProjectId(), request.getUserId(), "成员权限已调整为只读"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(CanvasRequests.MemberUser request, Long userId) {
        accessService.requireOwner(request.getProjectId(), userId);
        if (canvasMapper.deactivateMember(request.getProjectId(), request.getUserId()) != 1) throw new ServiceException("成员不存在或不可移除");
        afterCommit(() -> closeMemberSessions(request.getProjectId(), request.getUserId(), "项目权限已被移除"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void exitProject(Long projectId, Long userId) {
        AidComicProject project = accessService.requireProject(projectId, userId);
        if (Objects.equals(project.getUserId(), userId)) throw new ServiceException("项目所有者不能退出自己的项目");
        if (canvasMapper.deactivateMember(projectId, userId) != 1) throw new ServiceException("当前用户不是有效成员");
        afterCommit(() -> closeMemberSessions(projectId, userId, "已退出项目"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createInvite(CanvasRequests.CreateInvite request, Long userId) {
        AidComicProject project = accessService.requireOwner(request.getProjectId(), userId);
        accessService.requireFeature(userId, project.getId(), "canvas.invite");
        Map<String, Object> config = accessService.config();
        int days = request.getValidDays() == null ? asInt(config.get("inviteDefaultDays"), 7) : request.getValidDays();
        int uses = request.getMaxUses() == null ? asInt(config.get("inviteDefaultUses"), 10) : request.getMaxUses();
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        ProjectInvite invite = new ProjectInvite();
        invite.setProjectId(project.getId());
        invite.setTokenHash(sha256(rawToken));
        invite.setDefaultRole(normalizeMemberRole(request.getRoleCode()));
        invite.setMaxUses(uses);
        invite.setUsedCount(0);
        invite.setExpireTime(Date.from(Instant.now().plus(days, ChronoUnit.DAYS)));
        invite.setStatus("ACTIVE");
        invite.setCreatorUserId(userId);
        canvasMapper.insertInvite(invite);
        Map<String, Object> result = inviteView(invite);
        result.put("inviteToken", rawToken);
        return result;
    }

    @Override
    public Map<String, Object> invites(Long projectId, Long userId) {
        accessService.requireOwner(projectId, userId);
        return Map.of("items", canvasMapper.selectInvites(projectId).stream().map(this::inviteView).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeInvite(Long projectId, Long inviteId, Long userId) {
        accessService.requireOwner(projectId, userId);
        if (canvasMapper.revokeInvite(inviteId, projectId) != 1) throw new ServiceException("邀请不存在或已失效");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyInvite(CanvasRequests.ApplyInvite request, Long userId) {
        ProjectInvite invite = canvasMapper.selectInviteByTokenHash(sha256(request.getInviteToken()));
        validateInvite(invite);
        AidComicProject project = canvasMapper.selectCanvasProject(invite.getProjectId());
        if (project == null) throw new ServiceException("邀请对应项目不存在");
        if (Objects.equals(project.getUserId(), userId)) throw new ServiceException("项目所有者无需申请加入");
        ProjectMember member = canvasMapper.selectMember(project.getId(), userId);
        if (member != null && "ACTIVE".equals(member.getStatus())) throw new ServiceException("当前用户已经是项目成员");
        JoinApplication existing = canvasMapper.selectApplicationByApplicant(invite.getId(), userId);
        if (existing != null) {
            if ("PENDING".equals(existing.getStatus()) || "APPROVED".equals(existing.getStatus())) return applicationView(existing);
            if (canvasMapper.resetApplication(existing.getId(), trimToNull(request.getMessage())) != 1) throw new ServiceException("申请状态已变化");
            return applicationView(canvasMapper.selectApplication(existing.getId()));
        }
        JoinApplication application = new JoinApplication();
        application.setProjectId(invite.getProjectId());
        application.setInviteId(invite.getId());
        application.setApplicantUserId(userId);
        application.setRequestedRole(invite.getDefaultRole());
        application.setStatus("PENDING");
        application.setApplyMessage(trimToNull(request.getMessage()));
        canvasMapper.insertApplication(application);
        return applicationView(application);
    }

    @Override
    public Map<String, Object> applications(Long projectId, Long userId) {
        accessService.requireOwner(projectId, userId);
        return Map.of("items", canvasMapper.selectApplications(projectId).stream()
                .map(item -> JsonSafeId.fields(item, "id", "projectId", "inviteId",
                        "applicantUserId", "reviewerUserId")).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewApplication(CanvasRequests.ReviewApplication request, Long userId) {
        JoinApplication application = canvasMapper.selectApplicationForUpdate(request.getId());
        if (application == null || !"PENDING".equals(application.getStatus())) throw new ServiceException("申请不存在或已处理");
        AidComicProject project = accessService.requireOwner(application.getProjectId(), userId);
        if ("REJECT".equalsIgnoreCase(request.getDecision())) {
            if (canvasMapper.reviewApplication(application.getId(), "REJECTED", userId, trimToNull(request.getRemark())) != 1)
                throw new ServiceException("申请状态已变化");
            return;
        }
        if (!"APPROVE".equalsIgnoreCase(request.getDecision())) throw new ServiceException("decision 仅支持 APPROVE 或 REJECT");
        ProjectInvite invite = canvasMapper.selectInviteForUpdate(application.getInviteId());
        validateInvite(invite);
        canvasMapper.lockProjectMemberQuota(project.getId());
        Long capacitySubjectId = entitlementSubjectResolver.resolve(EntitlementSubjectResolver.Strategy.PROJECT_OWNER,
                userId, project.getUserId(), null).id();
        int memberLimit = capacityLimit(capacitySubjectId, "canvas.member.max", "maxMembersPerProject", 10);
        if (memberLimit <= 0 || canvasMapper.countActiveMembers(project.getId()) >= memberLimit) throw new ServiceException("项目成员数量已达上限");
        ProjectMember existing = canvasMapper.selectMember(project.getId(), application.getApplicantUserId());
        if (existing == null) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(project.getId());
            member.setUserId(application.getApplicantUserId());
            member.setRoleCode(application.getRequestedRole());
            member.setStatus("ACTIVE");
            member.setInvitedBy(userId);
            member.setJoinedTime(new Date());
            canvasMapper.insertMember(member);
        } else if (canvasMapper.reactivateMember(project.getId(), application.getApplicantUserId(),
                application.getRequestedRole(), userId) != 1) {
            throw new ServiceException("成员状态已变化");
        }
        if (canvasMapper.incrementInviteUse(invite.getId(), invite.getUsedCount()) != 1) throw new ServiceException("邀请名额已被其他审批占用");
        if (canvasMapper.reviewApplication(application.getId(), "APPROVED", userId, trimToNull(request.getRemark())) != 1)
            throw new ServiceException("申请状态已变化");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createShare(CanvasRequests.CreateShare request, Long userId) {
        accessService.requireOwner(request.getProjectId(), userId);
        accessService.requireFeature(userId, request.getProjectId(), "canvas.share");
        Workspace workspace = canvasMapper.selectWorkspaceByProject(request.getProjectId());
        if (workspace == null) throw new ServiceException("项目工作区不存在");
        int days = request.getValidDays() == null ? 7 : request.getValidDays();
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        ProjectShare share = new ProjectShare();
        share.setProjectId(request.getProjectId());
        share.setWorkspaceId(workspace.getId());
        share.setTokenHash(sha256(token));
        share.setSnapshotRevision(workspace.getRevision());
        share.setSnapshotJson(snapshotJson(workspace));
        share.setExpireTime(Date.from(Instant.now().plus(days, ChronoUnit.DAYS)));
        share.setStatus("ACTIVE");
        share.setCreatorUserId(userId);
        canvasMapper.insertShare(share);
        Map<String, Object> result = shareView(share, false);
        result.put("shareToken", token);
        return result;
    }

    @Override
    public Map<String, Object> shares(Long projectId, Long userId) {
        accessService.requireOwner(projectId, userId);
        return Map.of("items", canvasMapper.selectShares(projectId).stream().map(s -> shareView(s, false)).toList());
    }

    @Override
    public Map<String, Object> shareDetail(String token, Long userId) {
        ProjectShare share = canvasMapper.selectShareByTokenHash(sha256(token));
        if (share == null || !"ACTIVE".equals(share.getStatus()) || !share.getExpireTime().after(new Date())) {
            throw new ServiceException("分享链接不存在、已撤销或已过期");
        }
        if (canvasMapper.selectCanvasProject(share.getProjectId()) == null) throw new ServiceException("分享项目不存在");
        return shareView(share, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateShare(Long projectId, Long shareId, Long userId) {
        accessService.requireOwner(projectId, userId);
        ProjectShare share = canvasMapper.selectActiveShare(shareId, projectId);
        if (share == null || !share.getExpireTime().after(new Date())) throw new ServiceException("分享不存在或已过期");
        Workspace workspace = canvasMapper.selectWorkspace(share.getWorkspaceId());
        if (canvasMapper.updateShareSnapshot(shareId, projectId, workspace.getRevision(), snapshotJson(workspace)) != 1)
            throw new ServiceException("分享状态已变化");
        return shareView(canvasMapper.selectActiveShare(shareId, projectId), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(Long projectId, Long shareId, Long userId) {
        accessService.requireOwner(projectId, userId);
        if (canvasMapper.revokeShare(shareId, projectId) != 1) throw new ServiceException("分享不存在或已失效");
    }

    private String validatedContent(String objectType, Object payload, Long workspaceId, Long userId) {
        if (payload == null) throw new ServiceException("UPSERT/PATCH 操作必须提供 payload");
        JSONObject object;
        try { object = JSON.parseObject(JSON.toJSONString(payload)); }
        catch (Exception e) { throw new ServiceException("对象内容不是合法 JSON"); }
        Set<String> allowed = switch (objectType) {
            case "NODE" -> null;
            case "EDGE" -> EDGE_KEYS;
            case "GROUP" -> GROUP_KEYS;
            default -> throw new ServiceException("不支持的对象类型");
        };
        if (allowed != null) {
            for (String key : object.keySet()) if (!allowed.contains(key)) throw new ServiceException("对象包含不允许的字段: " + key);
        }
        if ("NODE".equals(objectType) && blank(object.getString("nodeType"))) throw new ServiceException("节点必须提供 nodeType");
        if ("NODE".equals(objectType)) {
            normalizeTextNode(object);
            JSONArray resourceIds = object.getJSONArray("resourceIds");
            if (resourceIds != null && !resourceIds.isEmpty()) {
                if (resourceIds.size() > MAX_NODE_RESOURCE_IDS) {
                    throw new ServiceException("单个节点最多引用" + MAX_NODE_RESOURCE_IDS + "个素材");
                }
                List<Long> ids = new ArrayList<>();
                JSONArray normalizedIds = new JSONArray();
                for (Object value : resourceIds) {
                    Long id = parsePositiveLong(value, "resourceIds 必须是十进制素材编号数组");
                    ids.add(id);
                    normalizedIds.add(Long.toString(id));
                }
                resourceService.requireResources(workspaceId, ids, userId);
                object.put("resourceIds", normalizedIds);
            }
        }
        validateJsonShape(object, 0, new int[]{0});
        rejectForbiddenRecursive(object, "NODE".equals(objectType)
                && "TEXT".equalsIgnoreCase(object.getString("nodeType")));
        if ("EDGE".equals(objectType)) {
            if (blank(object.getString("sourceObjectId"))) throw new ServiceException("连线起点不能为空");
            if (blank(object.getString("targetObjectId"))) throw new ServiceException("连线终点不能为空");
        }
        String json = object.toJSONString();
        if (utf8Length(json) > MAX_OBJECT_BYTES) throw new ServiceException("单个画布对象不能超过128KB");
        return json;
    }

    private static void normalizeTextNode(JSONObject object) {
        if (!"TEXT".equalsIgnoreCase(object.getString("nodeType"))) return;
        if ((object.containsKey("textContent") && object.get("textContent") == null)
                || (object.containsKey("content") && object.get("content") == null)) {
            throw new ServiceException("文本正文不能为 null");
        }
        Object body = object.containsKey("textContent") ? object.get("textContent") : object.get("content");
        if (body == null) body = "";
        if (!(body instanceof String text)) throw new ServiceException("文本正文必须是字符串");
        Object format = object.get("textFormat");
        if (object.containsKey("textFormat") && !(format instanceof String)) throw new ServiceException("文本格式无效");
        object.put("textContent", CanvasPlainText.normalize(text, (String) format));
        object.remove("textFormat");
        object.remove("content");
    }

    private static void normalizeTextNodeForRead(JSONObject object) {
        if (!"TEXT".equalsIgnoreCase(object.getString("nodeType"))) return;
        Object body = object.containsKey("textContent") ? object.get("textContent") : object.get("content");
        if (!(body instanceof String text)) return;
        try {
            object.put("textContent", CanvasPlainText.normalize(text, object.getString("textFormat")));
        } catch (ServiceException unknownLegacyFormat) {
            object.put("textContent", CanvasPlainText.lineFeeds(text));
        }
        object.remove("textFormat");
        object.remove("content");
    }

    private static void validateJsonShape(Object value, int depth, int[] entries) {
        if (depth > MAX_JSON_DEPTH) throw new ServiceException("画布对象 JSON 层级过深");
        if (value instanceof Map<?, ?> map) {
            entries[0] += map.size();
            if (entries[0] > MAX_JSON_ENTRIES) throw new ServiceException("画布对象 JSON 字段过多");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (Objects.toString(entry.getKey(), "").length() > 100) throw new ServiceException("画布对象字段名过长");
                validateJsonShape(entry.getValue(), depth + 1, entries);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (++entries[0] > MAX_JSON_ENTRIES) throw new ServiceException("画布对象 JSON 数组元素过多");
                validateJsonShape(item, depth + 1, entries);
            }
        } else if (value instanceof String text && text.length() > 64 * 1024) {
            throw new ServiceException("画布对象单个文本字段过长");
        }
    }

    private void rejectForbiddenRecursive(Object value, boolean rootTextNode) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey());
                String lowerKey = key.toLowerCase();
                if (FORBIDDEN_KEYS.contains(lowerKey) || lowerKey.endsWith("url") || lowerKey.endsWith("urls")) {
                    throw new ServiceException("客户端不能写入服务端权威字段或裸资源地址: " + key);
                }
                if (!(rootTextNode && "textContent".equals(key))) rejectForbiddenRecursive(entry.getValue(), false);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) rejectForbiddenRecursive(item, false);
        } else if (value instanceof String text) {
            String normalized = text.trim().toLowerCase();
            if (normalized.startsWith("http://") || normalized.startsWith("https://")
                    || normalized.startsWith("data:") || normalized.startsWith("blob:")
                    || normalized.startsWith("//") || normalized.startsWith("/profile/")) {
                throw new ServiceException("客户端不能在节点内容中保存裸资源地址，请使用 resourceIds");
            }
        }
    }

    private void validateGraph(Long workspaceId) {
        List<CanvasObject> objects = canvasMapper.selectWorkspaceObjects(workspaceId, false);
        Set<String> nodeIds = objects.stream().filter(o -> "NODE".equals(o.getObjectType()))
                .map(CanvasObject::getObjectId).collect(java.util.stream.Collectors.toSet());
        for (CanvasObject object : objects) {
            if ("EDGE".equals(object.getObjectType())) {
                JSONObject content = JSON.parseObject(object.getContentJson());
                if (!nodeIds.contains(content.getString("sourceObjectId")) || !nodeIds.contains(content.getString("targetObjectId"))) {
                    throw new ServiceException("连线必须引用当前工作区内未删除的节点: " + object.getObjectId());
                }
            }
            if ("GROUP".equals(object.getObjectType())) {
                JSONArray children = JSON.parseObject(object.getContentJson()).getJSONArray("childObjectIds");
                if (children == null) continue;
                Set<String> unique = new java.util.HashSet<>();
                for (Object child : children) {
                    String childId = Objects.toString(child, "");
                    if (!nodeIds.contains(childId)) throw new ServiceException("分组只能引用当前工作区内未删除的节点: " + childId);
                    if (!unique.add(childId)) throw new ServiceException("分组内节点不能重复: " + childId);
                }
            }
        }
    }

    private static void validateMutation(CanvasRequests.Mutation mutation) {
        if (!TYPES.contains(mutation.getObjectType())) throw new ServiceException("objectType 仅支持 NODE、EDGE、GROUP");
        if (!ACTIONS.contains(mutation.getAction())) throw new ServiceException("action 仅支持 UPSERT、PATCH、DELETE");
        if (!mutation.getObjectId().matches("[A-Za-z0-9_-]{1,64}")) throw new ServiceException("objectId 格式不合法");
    }

    private void validateInvite(ProjectInvite invite) {
        if (invite == null || !"ACTIVE".equals(invite.getStatus())) throw new ServiceException("邀请不存在或已撤销");
        if (invite.getExpireTime() == null || !invite.getExpireTime().after(new Date())) throw new ServiceException("邀请已过期");
        if (invite.getUsedCount() >= invite.getMaxUses()) throw new ServiceException("邀请加入名额已用完");
    }

    private String snapshotJson(Workspace workspace) {
        List<CanvasObject> objects = canvasMapper.selectWorkspaceObjects(workspace.getId(), false);
        Set<String> activeNodeIds = new java.util.HashSet<>();
        for (CanvasObject object : objects) {
            if (!"NODE".equals(object.getObjectType())) continue;
            activeNodeIds.add(object.getObjectId());
        }
        List<Map<String, Object>> currentResults = currentResultViews(workspace.getId());
        List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources =
                referencedResources(workspace.getId(), objects, currentResults);
        List<TaskBinding> tasks = canvasMapper.selectWorkspaceLatestTaskBindings(workspace.getId()).stream()
                .filter(binding -> activeNodeIds.contains(binding.getObjectId())).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workspace", workspaceView(workspace));
        snapshot.put("objects", objectViews(objects));
        snapshot.put("resources", resourceViews(resources));
        snapshot.put("tasks", shareTaskViews(tasks));
        snapshot.put("currentResults", currentResults);
        return JSON.toJSONString(snapshot);
    }

    private List<com.aid.canvas.domain.CanvasEntities.CanvasResource> referencedResources(Long workspaceId,
                                                                                            List<CanvasObject> objects,
                                                                                            List<Map<String, Object>> currentResults) {
        Set<Long> referencedResourceIds = new java.util.LinkedHashSet<>();
        for (CanvasObject object : objects) {
            if (!"NODE".equals(object.getObjectType()) || object.getContentJson() == null) continue;
            JSONArray resourceIds = JSON.parseObject(object.getContentJson()).getJSONArray("resourceIds");
            if (resourceIds == null) continue;
            for (Object value : resourceIds) {
                try { referencedResourceIds.add(parsePositiveLong(value, "")); }
                catch (ServiceException ignored) { /* 旧脏引用不阻断工作区读取，由下一次保存规范化。 */ }
            }
        }
        for (Map<String, Object> currentResult : currentResults) {
            try { referencedResourceIds.add(parsePositiveLong(currentResult.get("resourceId"), "")); }
            catch (ServiceException ignored) { /* 旧选择尚无资源登记时仍返回结果，由终态对账补齐。 */ }
        }
        if (referencedResourceIds.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>(referencedResourceIds);
        List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += RESOURCE_QUERY_BATCH) {
            resources.addAll(canvasMapper.selectCanvasResources(workspaceId,
                    ids.subList(from, Math.min(from + RESOURCE_QUERY_BATCH, ids.size()))));
        }
        return resources;
    }

    private Map<String, Object> workspaceView(Workspace workspace) {
        if (workspace == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", JsonSafeId.value(workspace.getId()));
        result.put("projectId", JsonSafeId.value(workspace.getProjectId()));
        result.put("workspaceType", workspace.getWorkspaceType());
        result.put("schemaVersion", workspace.getSchemaVersion());
        result.put("revision", workspace.getRevision());
        result.put("eventSeq", workspace.getEventSeq());
        result.put("nodeCount", workspace.getNodeCount());
        result.put("edgeCount", workspace.getEdgeCount());
        result.put("groupCount", workspace.getGroupCount());
        result.put("storageBytes", workspace.getStorageBytes());
        return result;
    }

    private List<Map<String, Object>> projectSettingViews(Long projectId) {
        List<Map<String, Object>> items = new ArrayList<>();
        PROJECT_SETTING_DEFAULTS.forEach((key, defaultValue) -> {
            ProjectSetting setting = canvasMapper.selectProjectSetting(projectId, key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("settingKey", key);
            item.put("value", setting == null ? defaultValue : JSON.parse(setting.getSettingValue()));
            item.put("version", setting == null ? 0L : setting.getSettingVersion());
            items.add(item);
        });
        return items;
    }

    private static Map<String, Object> draftView(NodeDraft draft, Long currentObjectVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectId", draft.getObjectId());
        result.put("draftVersion", draft.getDraftVersion());
        result.put("baseObjectVersion", draft.getBaseObjectVersion());
        result.put("currentObjectVersion", currentObjectVersion == null ? 0L : currentObjectVersion);
        result.put("stale", !Objects.equals(draft.getBaseObjectVersion(), currentObjectVersion == null ? 0L : currentObjectVersion));
        Object payload = JSON.parse(draft.getDraftJson());
        if (payload instanceof JSONObject textDraft) normalizeTextNodeForRead(textDraft);
        result.put("payload", payload);
        result.put("updateTime", draft.getUpdateTime());
        return result;
    }

    private List<Map<String, Object>> currentResultViews(Long workspaceId) {
        List<Map<String, Object>> results = canvasMapper.selectWorkspaceCurrentResults(workspaceId);
        for (Map<String, Object> result : results) {
            result.put("taskId", JsonSafeId.value(result.get("taskId")));
            result.put("resultId", JsonSafeId.value(result.get("resultId")));
            result.put("resourceId", JsonSafeId.value(result.get("resourceId")));
            String selectedUrl = firstNonBlank(
                    Objects.toString(result.get("ossUrl"), null),
                    Objects.toString(result.get("originUrl"), null));
            String resourceUrl = com.aid.canvas.publication.runtime.CanvasPublicationResourceUrl.resolve(
                    result.get("resourceId") == null ? null : Long.valueOf(result.get("resourceId").toString()), selectedUrl);
            result.put("resultUrl", resourceUrl != null && resourceUrl.startsWith("/api/user/canvas/publication/")
                    ? resourceUrl : mediaUrlResolver.toFullUrl(resourceUrl));
            result.remove("originUrl");
            result.remove("ossUrl");
        }
        return results;
    }

    private List<Map<String, Object>> objectViews(List<CanvasObject> objects) { return objects.stream().map(this::objectView).toList(); }
    private Map<String, Object> objectView(CanvasObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectId", object.getObjectId());
        result.put("objectType", object.getObjectType());
        result.put("objectVersion", object.getObjectVersion());
        result.put("deleted", object.getDeleted() != null && object.getDeleted() == 1);
        Object content = object.getContentJson() == null ? null : JSON.parse(object.getContentJson());
        if (content instanceof JSONObject textNode) normalizeTextNodeForRead(textNode);
        result.put("content", content);
        result.put("updateTime", object.getUpdateTime());
        return result;
    }

    private List<Map<String, Object>> operationViews(List<Operation> operations) {
        return operations.stream().map(operation -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("operationId", operation.getOperationId());
            view.put("actorUserId", JsonSafeId.value(operation.getActorUserId()));
            view.put("revision", operation.getWorkspaceRevision());
            view.put("eventSeq", operation.getEventSeq());
            view.put("operationType", operation.getOperationType());
            view.put("data", JSON.parse(operation.getPayloadJson()));
            view.put("createTime", operation.getCreateTime());
            return view;
        }).toList();
    }

    private List<Map<String, Object>> taskViews(List<TaskBinding> bindings) {
        List<Long> failedTaskIds = bindings.stream()
                .filter(binding -> "FAILED".equals(binding.getStatus()) && binding.getTaskId() != null)
                .map(TaskBinding::getTaskId).distinct().toList();
        Map<Long, String> errors = new LinkedHashMap<>();
        for (int offset = 0; offset < failedTaskIds.size(); offset += RESOURCE_QUERY_BATCH) {
            List<Long> batch = failedTaskIds.subList(offset, Math.min(offset + RESOURCE_QUERY_BATCH, failedTaskIds.size()));
            LambdaQueryWrapper<AidMediaTask> query = new LambdaQueryWrapper<AidMediaTask>()
                    .select(AidMediaTask::getId, AidMediaTask::getModelName,
                            AidMediaTask::getErrorMessage, AidMediaTask::getErrorDetailJson)
                    .in(AidMediaTask::getId, batch);
            for (AidMediaTask task : mediaTaskMapper.selectList(query)) {
                if (task.getErrorMessage() != null || task.getErrorDetailJson() != null) {
                    errors.put(task.getId(), TaskErrorSnapshot.fromTask(task).getUserMessage());
                }
            }
        }
        Map<String, Map<String, Object>> latestByObject = new LinkedHashMap<>();
        for (TaskBinding binding : bindings) {
            if (latestByObject.containsKey(binding.getObjectId())) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", JsonSafeId.value(binding.getTaskId()));
            view.put("objectId", binding.getObjectId());
            view.put("actorUserId", JsonSafeId.value(binding.getActorUserId()));
            view.put("mediaType", binding.getMediaType());
            view.put("operationId", binding.getOperationId());
            view.put("status", binding.getStatus());
            view.put("resultUrl", binding.getResultUrl());
            if (errors.containsKey(binding.getTaskId())) view.put("errorMessage", errors.get(binding.getTaskId()));
            view.put("updateTime", binding.getUpdateTime());
            latestByObject.put(binding.getObjectId(), view);
        }
        return new ArrayList<>(latestByObject.values());
    }

    private List<Map<String, Object>> shareTaskViews(List<TaskBinding> bindings) {
        return bindings.stream().map(binding -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", JsonSafeId.value(binding.getTaskId()));
            view.put("objectId", binding.getObjectId());
            view.put("mediaType", binding.getMediaType());
            view.put("status", binding.getStatus());
            view.put("resultUrl", binding.getResultUrl());
            view.put("updateTime", binding.getUpdateTime());
            return view;
        }).toList();
    }

    private List<Map<String, Object>> resourceViews(List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources) {
        Map<Long,com.aid.canvas.domain.CanvasEntities.CanvasResource> byId=new LinkedHashMap<>();
        for(var resource:resources)byId.put(resource.getId(),resource);
        return resources.stream().map(resource -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("resourceId", JsonSafeId.value(resource.getId()));
            view.put("sourceType", resource.getSourceType());
            view.put("sourceId", JsonSafeId.value(resource.getSourceId()));
            view.put("ownerUserId", JsonSafeId.value(resource.getOwnerUserId()));
            view.put("mediaType", resource.getMediaType());
            view.put("resourceUrl", com.aid.canvas.runtime.CanvasModelAccessUrl.resolve(resource.getId(),resource.getStorageUrl(),resource.getMediaType()));
            view.put("fileSize", resource.getFileSize());
            view.put("mimeType", resource.getMimeType());
            if("MODEL".equals(resource.getMediaType())||"SCENE_PACKAGE".equals(resource.getMediaType())) {
                JSONObject bundle=com.aid.canvas.publication.service.CanvasPublicationBundles.read(resource.getMetadataJson());
                if(bundle==null)throw new ServiceException("模型资源缺少完整依赖清单");
                for(Object raw:bundle.getJSONArray("files")) {
                    JSONObject file=(JSONObject)raw;
                    var dependency=byId.get(Long.valueOf(file.getString("resourceId")));
                    if(dependency==null)throw new ServiceException("模型依赖未完整载入，请刷新后重试");
                    file.put("resourceUrl",com.aid.canvas.runtime.CanvasModelAccessUrl.resolve(dependency.getId(),dependency.getStorageUrl(),dependency.getMediaType()));
                }
                view.put("bundle",bundle);
            }
            view.put("status", resource.getStatus());
            return view;
        }).toList();
    }

    private static void collectSceneResources(Object value, Set<Long> ids) {
        if (value instanceof Map<?,?> map) {
            for (Map.Entry<?,?> entry : map.entrySet()) {
                String key=entry.getKey().toString(); Object child=entry.getValue();
                if (Set.of("resourceId","thumbnailResourceId","maskResourceId","panoramaResourceId","previewImageResourceId",
                        "referenceImageResourceId","scenePackageResourceId").contains(key)) {
                    ids.add(parsePositiveLong(child,"场景资源编号无效"));
                } else if (Set.of("panoramaHistoryResourceIds","sourceImageResourceIds").contains(key) && child instanceof List<?> list) {
                    list.forEach(id -> ids.add(parsePositiveLong(id,"场景资源编号无效")));
                } else if (!"assetOverrides".equals(key)) collectSceneResources(child,ids);
            }
        } else if (value instanceof Iterable<?> list) for(Object child:list) collectSceneResources(child,ids);
    }

    private Map<String, Object> inviteView(ProjectInvite invite) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", JsonSafeId.value(invite.getId()));
        result.put("projectId", JsonSafeId.value(invite.getProjectId()));
        result.put("roleCode", invite.getDefaultRole());
        result.put("maxUses", invite.getMaxUses());
        result.put("usedCount", invite.getUsedCount());
        result.put("remainingUses", Math.max(0, invite.getMaxUses() - invite.getUsedCount()));
        result.put("expireTime", invite.getExpireTime());
        result.put("status", invite.getStatus());
        return result;
    }

    private Map<String, Object> applicationView(JoinApplication application) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", JsonSafeId.value(application.getId()));
        result.put("projectId", JsonSafeId.value(application.getProjectId()));
        result.put("requestedRole", application.getRequestedRole());
        result.put("status", application.getStatus());
        result.put("createTime", application.getCreateTime());
        return result;
    }

    private Map<String, Object> shareView(ProjectShare share, boolean includeSnapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", JsonSafeId.value(share.getId()));
        result.put("projectId", JsonSafeId.value(share.getProjectId()));
        result.put("workspaceId", JsonSafeId.value(share.getWorkspaceId()));
        result.put("snapshotRevision", share.getSnapshotRevision());
        result.put("expireTime", share.getExpireTime());
        result.put("status", share.getStatus());
        if (includeSnapshot) result.put("snapshot", JSON.parse(share.getSnapshotJson()));
        return result;
    }

    private void closeMemberSessions(Long projectId, Long memberUserId, String reason) {
        collaborationService.listSessions(projectId, memberUserId).forEach(s -> collaborationService.terminateSession(
                Objects.toString(s.get("sessionId")), reason));
    }

    private void publishAfterCommit(Long workspaceId, String event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { collaborationService.publishWorkspaceEvent(workspaceId, event); }
            });
        } else collaborationService.publishWorkspaceEvent(workspaceId, event);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private static Map<String, Object> page(List<?> items, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        return result;
    }
    private static String normalizeMemberRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!Set.of("EDITOR", "VIEWER").contains(normalized)) throw new ServiceException("成员角色仅支持 EDITOR 或 VIEWER");
        return normalized;
    }
    private static String normalizeSort(String sort) {
        String value = sort == null ? "UPDATED_AT" : sort.toUpperCase();
        return Set.of("UPDATED_AT", "CREATED_AT", "NAME").contains(value) ? value : "UPDATED_AT";
    }
    private static int importOrder(String type) { return "NODE".equals(type) ? 0 : "GROUP".equals(type) ? 1 : 2; }
    private static void remapReferences(JSONObject payload, Map<String, String> idMap) {
        for (String key : List.of("sourceObjectId", "targetObjectId")) {
            String old = payload.getString(key);
            if (old != null) {
                String mapped = idMap.get(old);
                if (mapped == null) throw new ServiceException("导入对象引用不存在: " + old);
                payload.put(key, mapped);
            }
        }
        JSONArray children = payload.getJSONArray("childObjectIds");
        if (children != null) {
            JSONArray mapped = new JSONArray();
            for (Object child : children) {
                String id = idMap.get(Objects.toString(child));
                if (id == null) throw new ServiceException("导入分组引用不存在: " + child);
                mapped.add(id);
            }
            payload.put("childObjectIds", mapped);
        }
    }
    private static void remapReferencesRecursive(Object value, Map<String, String> idMap) {
        if (value instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
                String key = entry.getKey();
                Object child = entry.getValue();
                String lower = key.toLowerCase();
                if (child instanceof String id && (lower.endsWith("objectid") || lower.endsWith("nodeid")
                        || "groupid".equals(lower))) {
                    if (idMap.containsKey(id)) map.put(key, idMap.get(id));
                } else if (child instanceof Iterable<?> values && (lower.endsWith("objectids") || lower.endsWith("nodeids"))) {
                    JSONArray remapped = new JSONArray();
                    for (Object item : values) {
                        String id = Objects.toString(item, "");
                        remapped.add(idMap.getOrDefault(id, id));
                    }
                    map.put(key, remapped);
                } else {
                    remapReferencesRecursive(child, idMap);
                }
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object child : values) remapReferencesRecursive(child, idMap);
        }
    }

    private static void stripRuntimeIdentity(Object value) {
        if (value instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
            map.keySet().removeIf(key -> {
                String lower = key.toLowerCase();
                return FORBIDDEN_KEYS.contains(lower) || COPY_RESET_KEYS.contains(lower)
                        || lower.endsWith("url") || lower.endsWith("urls");
            });
            for (Object child : map.values()) stripRuntimeIdentity(child);
        } else if (value instanceof Iterable<?> values) {
            for (Object child : values) stripRuntimeIdentity(child);
        }
    }

    private static void mergePatch(JSONObject target, JSONObject patch) {
        if (patch == null) throw new ServiceException("PATCH 操作必须提供 JSON 对象 payload");
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                target.remove(entry.getKey());
                continue;
            }
            Object current = target.get(entry.getKey());
            if (current instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                JSONObject nested = JSON.parseObject(
                        CanvasAssistantOperationCanonicalizer.explicitNullJson(current));
                mergePatch(nested, JSON.parseObject(
                        CanvasAssistantOperationCanonicalizer.explicitNullJson(entry.getValue())));
                target.put(entry.getKey(), nested);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Long parsePositiveLong(Object value, String message) {
        try {
            String text = value instanceof Number number ? number.toString() : Objects.toString(value, "").trim();
            if (!text.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long parsed = Long.parseLong(text);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (Exception e) {
            throw new ServiceException(message);
        }
    }
    private int capacityLimit(Long subjectId, String entitlementCode, String configKey, int fallback) {
        int configuredLimit = asInt(accessService.config().get(configKey), fallback);
        int entitlementLimit = accessService.entitlementLimit(subjectId, null, entitlementCode, configuredLimit);
        return Math.min(configuredLimit, entitlementLimit);
    }
    private static int asInt(Object value, int fallback) { try { return Integer.parseInt(Objects.toString(value)); } catch (Exception e) { return fallback; } }
    private static long utf8Length(String value) { return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ServiceException assistantFailure(String code) {
        return new ServiceException("助手请求无效").setDetailMessage(code);
    }
    private static String firstNonBlank(String first, String second) { return blank(first) ? second : first; }
    private static String trimToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private static AssistantPrepared assistantPrepared(Long workspaceId, Long userId,
                                                        CanvasProjectService.AssistantNodeMutation input) {
        if (workspaceId == null || userId == null || input == null || input.runId() == null
                || blank(input.runtimeLeaseOwner()) || input.runtimeLeaseUntil() == null
                || input.runtimeFencingToken() == null
                || blank(input.operationId()) || input.operationId().length() > 100
                || !input.operationId().matches("[A-Za-z0-9:_-]+")
                || blank(input.targetObjectId())
                || !input.targetObjectId().matches("[A-Za-z0-9_-]{1,64}")
                || input.expectedVersion() == null || input.payload() == null
                || !("PATCH".equals(input.action()) || ASSISTANT_CREATE_ACTIONS.contains(input.action()))) {
            throw assistantFailure("CANVAS_MUTATION_INVALID");
        }
        JSONObject payload;
        try {
            payload = JSON.parseObject(
                    CanvasAssistantOperationCanonicalizer.explicitNullJson(input.payload()));
        } catch (RuntimeException invalid) {
            throw assistantFailure("CANVAS_MUTATION_PAYLOAD_INVALID");
        }
        String mutationAction;
        String operationType;
        if ("PATCH".equals(input.action())) {
            if (!input.relations().isEmpty() || input.sourceObjectVersion() == null || input.sourceObjectVersion() < 1
                    || blank(input.sourceObjectId())
                    || !Objects.equals(input.sourceObjectId(), input.targetObjectId())
                    || !Objects.equals(input.sourceObjectVersion(), input.expectedVersion())
                    || payload.containsKey("nodeType")) {
                throw assistantFailure("CANVAS_PATCH_TARGET_INVALID");
            }
            mutationAction = "PATCH";
            operationType = "ASSISTANT_NODE_PATCH";
        } else {
            if (input.expectedWorkspaceRevision() == null || input.expectedWorkspaceRevision() < 0
                    || input.expectedWorkspaceRevision() == Long.MAX_VALUE
                    || input.expectedVersion() != 0L || input.sourceObjectId() != null
                    || input.sourceObjectVersion() != null
                    || !input.targetObjectId().matches("asst_[a-f0-9]{24}")
                    || !assistantCreatePayloadValid(input.action(), payload)) {
                throw assistantFailure("CANVAS_CREATE_NODE_INVALID");
            }
            mutationAction = "UPSERT";
            operationType = "ASSISTANT_NODE_CREATE";
        }
        CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
        mutation.setObjectId(input.targetObjectId());
        mutation.setObjectType("NODE");
        mutation.setAction(mutationAction);
        mutation.setExpectedVersion(input.expectedVersion());
        mutation.setLockToken("server-assistant");
        mutation.setPayload(payload);
        CanvasRequests.Mutate request = new CanvasRequests.Mutate();
        request.setWorkspaceId(workspaceId);
        request.setSessionId("server-assistant");
        request.setOperationId(input.operationId());
        request.setMutations(input.relations().isEmpty() ? List.of(mutation)
                : com.aid.canvas.runtime.CanvasAssistantRelationContract.creationBatch(mutation, input.relations()));
        String requestHash = CanvasAssistantOperationCanonicalizer.assistantMutationRequestHash(
                workspaceId, userId, input, request.getMutations());
        Map<String, Object> receipt = ordered("receiptId", input.operationId(),
                "sourceType", "ASSISTANT_RUNTIME", "sourceRunId", input.runId().toString(),
                "sourceObjectId", input.sourceObjectId(), "sourceObjectVersion", input.sourceObjectVersion());
        return new AssistantPrepared(request, operationType, requestHash, receipt);
    }

    private static void requireSubmissionIdentity(CanvasProjectService.AssistantNodeMutation input, Long userId) {
        com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim claim = input == null
                ? null : input.submissionClaim();
        if (claim == null || !Objects.equals(input.runId(), claim.getRunId())
                || !Objects.equals(userId, claim.getUserId())
                || blank(claim.getIdempotencyKey())) {
            throw assistantFailure("RUNTIME_OPERATION_IDENTITY_MISMATCH");
        }
    }

    /** Called only after workspace row locking and before mutation admission; requestHash keeps the original intent. */
    private void placeAssistantNode(CanvasRequests.Mutate request, Long workspaceId) {
        placeAssistantNode(request, workspaceId, List.of());
    }

    private void placeAssistantNode(CanvasRequests.Mutate request, Long workspaceId,
                                   List<com.aid.canvas.runtime.CanvasAssistantRelationContract.Source> sources) {
        CanvasRequests.Mutation mutation = request.getMutations().get(0);
        JSONObject payload = JSON.parseObject(CanvasAssistantOperationCanonicalizer.explicitNullJson(mutation.getPayload()));
        if (!Boolean.TRUE.equals(payload.remove(com.aid.canvas.runtime.CanvasAssistantNodePlacement.AUTO))) return;
        JSONObject position = payload.getJSONObject("position"), size = payload.getJSONObject("size");
        var preferred = new com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box(position.getDoubleValue("x"),
                position.getDoubleValue("y"), size.getDoubleValue("width"), size.getDoubleValue("height"));
        List<com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box> occupied = new ArrayList<>();
        Map<String, com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box> sourceBounds = new LinkedHashMap<>();
        Set<String> sourceIds = sources.stream().map(com.aid.canvas.runtime.CanvasAssistantRelationContract.Source::objectId)
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, String> object : canvasMapper.selectNodePlacementBounds(workspaceId)) {
            JSONObject p = JSON.parseObject(object.get("positionJson")), bounds = JSON.parseObject(object.get("sizeJson"));
            if (p == null) p = new JSONObject();
            double width = bounds == null || bounds.getDoubleValue("width") <= 0 ? 480 : bounds.getDoubleValue("width");
            double height = bounds == null || bounds.getDoubleValue("height") <= 0 ? 300 : bounds.getDoubleValue("height");
            var box = new com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box(
                    p.getDoubleValue("x"), p.getDoubleValue("y"), width, height);
            occupied.add(box);
            if (sourceIds.contains(object.get("objectId"))) sourceBounds.put(object.get("objectId"), box);
        }
        Map<String, Object> config = accessService.config();
        double gap = Math.max(0, Math.min(1000, asInt(config.get("assistantNodeGap"), 40)));
        double rowWidth = Math.max(preferred.width(), asInt(config.get("assistantLayoutWidth"), 1600));
        if (!sourceIds.isEmpty()) {
            if (!sourceBounds.keySet().equals(sourceIds)) throw assistantFailure("CANVAS_SOURCE_NODE_MISSING");
            double x = sourceBounds.values().stream().mapToDouble(box -> box.x() + box.width()).max().orElseThrow() + gap;
            double y = sourceBounds.values().stream().mapToDouble(com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box::y).min().orElseThrow();
            preferred = new com.aid.canvas.runtime.CanvasAssistantNodePlacement.Box(x, y, preferred.width(), preferred.height());
        }
        var placed = com.aid.canvas.runtime.CanvasAssistantNodePlacement.place(preferred, occupied, gap, rowWidth);
        payload.put("position", ordered("x", placed.x(), "y", placed.y()));
        mutation.setPayload(payload);
    }

    private static boolean assistantCreatePayloadValid(String action, JSONObject payload) {
        if (payload.containsKey(com.aid.canvas.runtime.CanvasAssistantNodePlacement.AUTO)
                && !Boolean.TRUE.equals(payload.get(com.aid.canvas.runtime.CanvasAssistantNodePlacement.AUTO))) return false;
        if (!ASSISTANT_CREATE_KEYS.containsAll(payload.keySet())
                || !(payload.get("title") instanceof String title) || title.length() > 256
                || !(payload.get("prompt") instanceof String prompt) || prompt.length() > 65_536
                || !(payload.get("position") instanceof JSONObject position)
                || !(position.get("x") instanceof Number) || !(position.get("y") instanceof Number)
                || !(payload.get("size") instanceof JSONObject size)
                || !(size.get("width") instanceof Number) || !(size.get("height") instanceof Number)
                || !(payload.get("parameters") instanceof JSONObject parameters)
                || !com.aid.canvas.runtime.CanvasAssistantGenerationSettings.valid(parameters)
                || !(payload.get("resourceIds") instanceof JSONArray resourceIds) || !resourceIds.isEmpty()
                || !(payload.get("inputPorts") instanceof JSONArray inputPorts) || !inputPorts.isEmpty()
                || !(payload.get("metadata") instanceof JSONObject metadata)
                || !Set.of("studioData").containsAll(metadata.keySet())
                || !(metadata.get("studioData") instanceof JSONObject studioData)
                || !Objects.equals(title, studioData.getString("title"))
                || !Objects.equals(prompt, studioData.getString("prompt"))) {
            return false;
        }
        if ("CREATE_TEXT".equals(action)) {
            return "TEXT".equals(payload.getString("nodeType"))
                    && parameters.isEmpty()
                    && payload.get("textContent") instanceof String text && text.length() <= 65_536
                    && Set.of("kind", "title", "documentContent", "prompt").containsAll(studioData.keySet())
                    && "text".equals(studioData.getString("kind"))
                    && Objects.equals(text, studioData.getString("documentContent"));
        }
        if (payload.containsKey("textContent")
                || !Set.of("kind", "title", "prompt", "mediaKind", "model", "generationOptions").containsAll(studioData.keySet())
                || !parameters.isEmpty() && !Objects.equals(parameters, studioData.getJSONObject("generationOptions"))
                || parameters.isEmpty() && studioData.containsKey("generationOptions")
                || studioData.containsKey("model") && (!(studioData.get("model") instanceof String model)
                    || model.isBlank() || model.length() > 100 || !model.matches("[A-Za-z0-9._:/-]+"))) {
            return false;
        }
        return switch (action) {
            case "CREATE_IMAGE" -> "IMAGE".equals(payload.getString("nodeType"))
                    && "image".equals(studioData.getString("kind"))
                    && "image".equals(studioData.getString("mediaKind"));
            case "CREATE_VIDEO" -> "VIDEO".equals(payload.getString("nodeType"))
                    && "video".equals(studioData.getString("kind"))
                    && "video".equals(studioData.getString("mediaKind"));
            case "CREATE_MUSIC", "CREATE_AUDIO" -> "VOICE".equals(payload.getString("nodeType"))
                    && "voice".equals(studioData.getString("kind"))
                    && "audio".equals(studioData.getString("mediaKind"));
            default -> false;
        };
    }

    private static Map<String, Object> duplicateAssistantOperation(Operation existing, AssistantPrepared prepared,
                                                                   CanvasProjectService.AssistantNodeMutation input,
                                                                   Long workspaceId, Long userId) {
        String hash = prepared.requestHash();
        if (input.relations().isEmpty() && "ASSISTANT_NODE_CREATE".equals(prepared.operationType())
                && !Objects.equals(hash, existing.getPayloadHash())) {
            if (!Objects.equals(workspaceId, existing.getWorkspaceId())
                    || !Objects.equals(input.operationId(), existing.getOperationId()))
                throw assistantFailure("CANVAS_RECEIPT_SCOPE_CHANGED");
            hash = CanvasAssistantOperationCanonicalizer.assistantCreateReceiptRequestHash(
                    workspaceId, userId, input.runId(), input.expectedWorkspaceRevision(), input.action(),
                    input.operationId(), prepared.request().getMutations().get(0), existing.getPayloadJson());
        }
        return duplicateOperation(existing, hash, prepared.operationType(), userId);
    }

    private record AssistantPrepared(CanvasRequests.Mutate request, String operationType,
                                     String requestHash, Map<String, Object> receipt) { }

    private static String mutationRequestHash(List<CanvasRequests.Mutation> mutations) {
        return CanvasAssistantOperationCanonicalizer.mutationRequestHash(mutations);
    }
}
