package com.aid.canvas.mapper;

import com.aid.aid.domain.AidComicProject;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.EntitlementOverride;
import com.aid.canvas.domain.CanvasEntities.JoinApplication;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.OperationObject;
import com.aid.canvas.domain.CanvasEntities.ProjectSetting;
import com.aid.canvas.domain.CanvasEntities.ProjectInvite;
import com.aid.canvas.domain.CanvasEntities.ProjectMember;
import com.aid.canvas.domain.CanvasEntities.ProjectShare;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.UserView;
import com.aid.canvas.domain.CanvasEntities.NodeDraft;
import com.aid.canvas.domain.CanvasEntities.NodeResultSelection;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface CanvasMapper {
    int reserveProjectRequest(@Param("userId") Long userId, @Param("operationId") String operationId,
                              @Param("requestHash") String requestHash);
    int completeProjectRequest(@Param("userId") Long userId, @Param("operationId") String operationId, @Param("projectId") Long projectId);
    Long selectProjectRequest(@Param("userId") Long userId, @Param("operationId") String operationId);
    String selectProjectRequestHash(@Param("userId") Long userId, @Param("operationId") String operationId);
    int lockCanvasOwnerQuota(@Param("userId") Long userId);
    int countOwnedCanvasProjects(@Param("userId") Long userId);
    AidComicProject selectCanvasProject(@Param("projectId") Long projectId);
    AidComicProject selectAccessibleCanvasProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
    List<Map<String, Object>> selectUserProjects(@Param("userId") Long userId, @Param("projectName") String projectName,
                                                  @Param("sortBy") String sortBy, @Param("sortDirection") String sortDirection,
                                                  @Param("offset") int offset, @Param("limit") int limit);
    long countUserProjects(@Param("userId") Long userId, @Param("projectName") String projectName);
    int updateCanvasProject(@Param("projectId") Long projectId, @Param("userId") Long userId,
                            @Param("projectName") String projectName, @Param("projectDesc") String projectDesc,
                            @Param("coverUrl") String coverUrl);
    int softDeleteCanvasProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
    int countRunningCanvasTasks(@Param("projectId") Long projectId);
    int deactivateProjectWorkspaces(@Param("projectId") Long projectId);
    int deactivateProjectMembers(@Param("projectId") Long projectId);
    int revokeProjectInvites(@Param("projectId") Long projectId);
    int revokeProjectShares(@Param("projectId") Long projectId);

    int insertWorkspace(Workspace workspace);
    Workspace selectWorkspaceByProject(@Param("projectId") Long projectId);
    Workspace selectWorkspace(@Param("workspaceId") Long workspaceId);
    Workspace selectWorkspaceForUpdate(@Param("workspaceId") Long workspaceId);
    int applyWorkspaceDelta(@Param("workspaceId") Long workspaceId,
                            @Param("nodeDelta") int nodeDelta,
                            @Param("edgeDelta") int edgeDelta,
                            @Param("groupDelta") int groupDelta,
                            @Param("storageByteDelta") long storageByteDelta);
    List<CanvasObject> selectWorkspaceObjects(@Param("workspaceId") Long workspaceId, @Param("includeDeleted") boolean includeDeleted);
    List<Map<String, Object>> selectAssistantWorkspaceNodeSummaries(@Param("projectId") Long projectId,
                                                                    @Param("workspaceId") Long workspaceId);
    List<CanvasObject> selectIncomingReferenceEdges(@Param("workspaceId") Long workspaceId,
                                                   @Param("targetObjectId") String targetObjectId);
    /** Layout does not need prompts, resource metadata or node document bodies. */
    List<java.util.Map<String, String>> selectNodePlacementBounds(@Param("workspaceId") Long workspaceId);
    CanvasObject selectCanvasObject(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    CanvasObject selectCanvasObjectVersionState(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    CanvasObject selectCanvasObjectForUpdate(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    int upsertObjectFence(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                          @Param("lockFence") Long lockFence);
    Long selectObjectFence(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    Long selectObjectFenceForUpdate(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    int insertCanvasObject(CanvasObject object);
    int updateCanvasObject(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                           @Param("expectedVersion") Long expectedVersion, @Param("contentJson") String contentJson,
                           @Param("contentHash") String contentHash, @Param("deleted") Integer deleted,
                           @Param("actorUserId") Long actorUserId, @Param("lockFence") Long lockFence);
    int insertOperation(Operation operation);
    Operation selectOperation(@Param("workspaceId") Long workspaceId, @Param("operationId") String operationId);
    List<Operation> selectOperationsAfter(@Param("workspaceId") Long workspaceId, @Param("afterSeq") Long afterSeq, @Param("limit") int limit);
    List<Operation> selectOperationsAfterRevision(@Param("workspaceId") Long workspaceId,
                                                  @Param("afterRevision") Long afterRevision, @Param("limit") int limit);
    int insertOperationObjects(@Param("items") List<OperationObject> items);
    List<OperationObject> selectOperationObjects(@Param("operationRowId") Long operationRowId);

    ProjectSetting selectProjectSetting(@Param("projectId") Long projectId, @Param("settingKey") String settingKey);
    int insertProjectSetting(ProjectSetting setting);
    int updateProjectSetting(@Param("projectId") Long projectId, @Param("settingKey") String settingKey,
                             @Param("expectedVersion") Long expectedVersion, @Param("settingValue") String settingValue,
                             @Param("actorUserId") Long actorUserId);

    ProjectMember selectMember(@Param("projectId") Long projectId, @Param("userId") Long userId);
    List<Map<String, Object>> selectMembers(@Param("projectId") Long projectId);
    int countActiveMembers(@Param("projectId") Long projectId);
    int lockProjectMemberQuota(@Param("projectId") Long projectId);
    int insertMember(ProjectMember member);
    int reactivateMember(@Param("projectId") Long projectId, @Param("userId") Long userId,
                         @Param("roleCode") String roleCode, @Param("invitedBy") Long invitedBy);
    int updateMemberRole(@Param("projectId") Long projectId, @Param("userId") Long userId, @Param("roleCode") String roleCode);
    int deactivateMember(@Param("projectId") Long projectId, @Param("userId") Long userId);

    int insertInvite(ProjectInvite invite);
    ProjectInvite selectInviteByTokenHash(@Param("tokenHash") String tokenHash);
    ProjectInvite selectInviteForUpdate(@Param("inviteId") Long inviteId);
    List<ProjectInvite> selectInvites(@Param("projectId") Long projectId);
    int incrementInviteUse(@Param("inviteId") Long inviteId, @Param("expectedUsed") Integer expectedUsed);
    int revokeInvite(@Param("inviteId") Long inviteId, @Param("projectId") Long projectId);
    int insertApplication(JoinApplication application);
    JoinApplication selectApplication(@Param("id") Long id);
    JoinApplication selectApplicationForUpdate(@Param("id") Long id);
    JoinApplication selectApplicationByApplicant(@Param("inviteId") Long inviteId, @Param("userId") Long userId);
    int resetApplication(@Param("id") Long id, @Param("message") String message);
    List<Map<String, Object>> selectApplications(@Param("projectId") Long projectId);
    int reviewApplication(@Param("id") Long id, @Param("status") String status, @Param("reviewerUserId") Long reviewerUserId,
                          @Param("remark") String remark);

    int insertShare(ProjectShare share);
    ProjectShare selectShareByTokenHash(@Param("tokenHash") String tokenHash);
    ProjectShare selectActiveShare(@Param("shareId") Long shareId, @Param("projectId") Long projectId);
    List<ProjectShare> selectShares(@Param("projectId") Long projectId);
    int updateShareSnapshot(@Param("shareId") Long shareId, @Param("projectId") Long projectId,
                            @Param("revision") Long revision, @Param("snapshotJson") String snapshotJson);
    int revokeShare(@Param("shareId") Long shareId, @Param("projectId") Long projectId);

    int insertTaskBinding(TaskBinding binding);
    int bindMediaTask(@Param("id") Long id, @Param("taskId") Long taskId, @Param("status") String status);
    int failTaskIntent(@Param("id") Long id);
    int failStaleTaskIntent(@Param("id") Long id, @Param("cutoff") Date cutoff);
    int touchTaskBinding(@Param("id") Long id);
    TaskBinding selectTaskBinding(@Param("taskId") Long taskId);
    TaskBinding selectTaskBindingByOperation(@Param("workspaceId") Long workspaceId, @Param("operationId") String operationId);
    TaskBinding selectTaskBindingByOperationForUpdate(@Param("workspaceId") Long workspaceId, @Param("operationId") String operationId);
    List<Long> selectAssistantMediaEntryNamespace(@Param("operationId") String operationId, @Param("callId") String callId);
    int completeTaskBinding(@Param("taskId") Long taskId, @Param("status") String status, @Param("resultUrl") String resultUrl);
    List<TaskBinding> selectCanvasTaskReconcileCandidates(@Param("limit") int limit);
    TaskBinding selectActiveTaskBindingByObject(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    List<TaskBinding> selectWorkspaceActiveTaskBindings(@Param("workspaceId") Long workspaceId);
    List<TaskBinding> selectWorkspaceTaskBindings(@Param("workspaceId") Long workspaceId, @Param("limit") int limit);
    List<TaskBinding> selectWorkspaceLatestTaskBindings(@Param("workspaceId") Long workspaceId);
    List<TaskBinding> selectObjectTaskBindings(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                                               @Param("offset") int offset, @Param("limit") int limit);
    long countObjectTaskBindings(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    List<Map<String, Object>> selectMediaResultsByTaskIds(@Param("taskIds") List<Long> taskIds);
    List<Map<String, Object>> selectAssistantMediaCandidates(@Param("workspaceId") Long workspaceId,
            @Param("objectId") String objectId, @Param("objectVersion") Long objectVersion, @Param("limit") int limit);
    Map<String, Object> selectMediaResultForNode(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                                                 @Param("resultId") Long resultId);
    /** Identity and source revision only; do not add media bodies, URLs or request snapshots. */
    Map<String, Object> selectAssistantMediaResultIdentity(@Param("workspaceId") Long workspaceId,
            @Param("objectId") String objectId, @Param("resultId") Long resultId);
    NodeResultSelection selectNodeResultSelection(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    /** 当前结果的素材身份；真实选择存在时不回退到发布导入结果。 */
    Long selectNodeCurrentResourceId(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId);
    List<Map<String, Object>> selectWorkspaceCurrentResults(@Param("workspaceId") Long workspaceId);
    int insertNodeResultSelection(NodeResultSelection selection);
    int updateNodeResultSelection(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                                  @Param("taskId") Long taskId, @Param("resultId") Long resultId,
                                  @Param("inputObjectVersion") Long inputObjectVersion,
                                  @Param("actorUserId") Long actorUserId);
    Map<String, Object> selectAssistantRuntimeScope(@Param("runId") Long runId);

    int insertCanvasResource(CanvasResource resource);
    CanvasResource selectCanvasResource(@Param("id") Long id);
    int updateCanvasResourceMetadata(@Param("resource") CanvasResource resource,
                                     @Param("expectedMetadataJson") String expectedMetadataJson);
    CanvasResource selectCanvasResourceBySource(@Param("workspaceId") Long workspaceId,
                                                @Param("sourceType") String sourceType,
                                                @Param("sourceId") Long sourceId);
    List<CanvasResource> selectCanvasResources(@Param("workspaceId") Long workspaceId, @Param("ids") List<Long> ids);
    UserView selectUserView(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
    int insertUserView(UserView view);
    int updateUserView(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId,
                       @Param("expectedVersion") Long expectedVersion, @Param("viewJson") String viewJson);
    NodeDraft selectNodeDraft(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                              @Param("userId") Long userId);
    List<NodeDraft> selectNodeDrafts(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
    int insertNodeDraft(NodeDraft draft);
    int updateNodeDraft(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                        @Param("userId") Long userId, @Param("expectedVersion") Long expectedVersion,
                        @Param("baseObjectVersion") Long baseObjectVersion, @Param("draftJson") String draftJson,
                        @Param("draftHash") String draftHash);
    int deleteNodeDraft(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                        @Param("userId") Long userId, @Param("expectedVersion") Long expectedVersion);

    Map<String, Object> selectCanvasConfig();
    int updateCanvasConfig(Map<String, Object> config);
    List<Map<String, Object>> selectDefaultEntitlements();
    int upsertDefaultEntitlement(@Param("code") String code, @Param("valueType") String valueType,
                                 @Param("value") String value, @Param("enabled") Boolean enabled);
    List<EntitlementOverride> selectActiveOverrides(@Param("userId") Long userId, @Param("now") Date now);
    int insertEntitlementOverride(EntitlementOverride override);
    EntitlementOverride selectEntitlementOverride(@Param("id") Long id);
    int revokeEntitlementOverride(@Param("id") Long id, @Param("revokeBy") String revokeBy,
                                  @Param("revokeReason") String revokeReason);

    List<Map<String, Object>> selectAdminProjects(@Param("projectName") String projectName, @Param("ownerUserId") Long ownerUserId,
                                                  @Param("status") Integer status);
    Map<String, Object> selectAdminProjectDetail(@Param("projectId") Long projectId);
    List<Map<String, Object>> selectAdminOverrides(@Param("userId") Long userId, @Param("entitlementCode") String entitlementCode,
                                                   @Param("status") String status);
    int insertAudit(@Param("operator") String operator, @Param("operatorName") String operatorName,
                    @Param("targetType") String targetType, @Param("targetId") String targetId,
                    @Param("targetUserId") Long targetUserId, @Param("action") String action,
                    @Param("detailJson") String detailJson);
    List<Map<String, Object>> selectAudits(@Param("operator") String operator, @Param("targetUserId") Long targetUserId,
                                           @Param("action") String action);
}
