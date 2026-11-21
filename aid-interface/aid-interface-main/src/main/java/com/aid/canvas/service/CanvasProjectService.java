package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;

import java.util.Map;

/** 自由画布项目、工作区、成员、邀请与分享业务。 */
public interface CanvasProjectService {
    Map<String, Object> createProject(CanvasRequests.CreateProject request, Long userId);
    Map<String, Object> importProject(CanvasRequests.ImportProject request, Long userId);
    Map<String, Object> listProjects(CanvasRequests.ProjectList request, Long userId);
    Map<String, Object> projectDetail(Long projectId, Long userId);
    Map<String, Object> updateProject(CanvasRequests.UpdateProject request, Long userId);
    void deleteProject(Long projectId, Long userId);
    Map<String, Object> loadWorkspace(CanvasRequests.LoadWorkspace request, Long userId);
    Map<String, Object> mutate(CanvasRequests.Mutate request, Long userId);
    /** Server-owned, idempotent text result write through the normal workspace operation log. */
    Map<String, Object> createAgentTextNode(Long workspaceId, Long userId, String operationId,
                                            String objectId, String title, String content);
    /** Trusted assistant mutation after Runtime lease/scope validation; never accepts browser credentials. */
    Map<String, Object> mutateAssistantNode(Long workspaceId, Long userId, AssistantNodeMutation mutation);
    /** Read a durable assistant mutation receipt before reacquiring a node whose revision already advanced. */
    Map<String, Object> replayAssistantNodeMutation(Long workspaceId, Long userId, AssistantNodeMutation mutation);
    /** Trusted Runtime reconciliation. This only reads an exact durable receipt and never repeats the mutation. */
    Map<String, Object> reconcileAssistantNodeMutation(Long workspaceId, Long userId, AssistantNodeMutation mutation);
    Map<String, Object> copyObjects(CanvasRequests.CopyObjects request, Long userId);
    Map<String, Object> undo(CanvasRequests.UndoOperation request, Long userId);
    Map<String, Object> changes(CanvasRequests.Changes request, Long userId);
    Map<String, Object> loadView(Long workspaceId, Long userId);
    Map<String, Object> saveView(CanvasRequests.SaveView request, Long userId);
    Map<String, Object> loadDrafts(Long workspaceId, Long userId);
    Map<String, Object> saveDraft(CanvasRequests.SaveDraft request, Long userId);
    void deleteDraft(CanvasRequests.DeleteDraft request, Long userId);
    Map<String, Object> projectSettings(Long projectId, Long userId);
    Map<String, Object> updateProjectSetting(CanvasRequests.ProjectSettingUpdate request, Long userId);
    Map<String, Object> members(Long projectId, Long userId);
    void updateMemberRole(CanvasRequests.MemberRole request, Long userId);
    void removeMember(CanvasRequests.MemberUser request, Long userId);
    void exitProject(Long projectId, Long userId);
    Map<String, Object> createInvite(CanvasRequests.CreateInvite request, Long userId);
    Map<String, Object> invites(Long projectId, Long userId);
    void revokeInvite(Long projectId, Long inviteId, Long userId);
    Map<String, Object> applyInvite(CanvasRequests.ApplyInvite request, Long userId);
    Map<String, Object> applications(Long projectId, Long userId);
    void reviewApplication(CanvasRequests.ReviewApplication request, Long userId);
    Map<String, Object> createShare(CanvasRequests.CreateShare request, Long userId);
    Map<String, Object> shares(Long projectId, Long userId);
    Map<String, Object> shareDetail(String token, Long userId);
    Map<String, Object> updateShare(Long projectId, Long shareId, Long userId);
    void revokeShare(Long projectId, Long shareId, Long userId);

    record AssistantNodeMutation(Long runId, String runtimeLeaseOwner, java.util.Date runtimeLeaseUntil,
                                 Long runtimeFencingToken, Long nodeFence,
                                 String sourceObjectId, Long sourceObjectVersion,
                                 Long expectedWorkspaceRevision,
                                 String operationId, String targetObjectId, Long expectedVersion,
                                 String action, Map<String, Object> payload,
                                 RuntimeOperationSubmissionClaim submissionClaim,
                                 java.util.List<com.aid.canvas.runtime.CanvasAssistantRelationContract.Source> relations) {
        public AssistantNodeMutation {
            relations = relations == null ? java.util.List.of() : java.util.List.copyOf(relations);
        }
        public AssistantNodeMutation(Long runId, String runtimeLeaseOwner, java.util.Date runtimeLeaseUntil,
                                     Long runtimeFencingToken, Long nodeFence, String sourceObjectId,
                                     Long sourceObjectVersion, Long expectedWorkspaceRevision, String operationId,
                                     String targetObjectId, Long expectedVersion, String action,
                                     Map<String, Object> payload, RuntimeOperationSubmissionClaim submissionClaim) {
            this(runId, runtimeLeaseOwner, runtimeLeaseUntil, runtimeFencingToken, nodeFence, sourceObjectId,
                    sourceObjectVersion, expectedWorkspaceRevision, operationId, targetObjectId, expectedVersion,
                    action, payload, submissionClaim, java.util.List.of());
        }
    }
}
