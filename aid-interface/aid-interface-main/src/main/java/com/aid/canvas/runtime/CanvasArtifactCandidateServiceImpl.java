package com.aid.canvas.runtime;

import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.NodeResultSelection;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.OperationObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CanvasArtifactCandidateServiceImpl implements CanvasArtifactCandidateService {
    private static final int CANDIDATE_TASK_LIMIT = 50;

    private final CanvasMapper canvasMapper;
    private final CanvasCollaborationService collaborationService;
    private final RuntimeOperationSubmissionGuard submissionGuard;

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> list(RuntimeScopeAccess.Scope scope) {
        requireAuthorizedScope(scope);
        CanvasObject node = canvasMapper.selectCanvasObjectVersionState(scope.workspaceId(), scope.objectId());
        if (node == null || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), scope.objectVersion())) {
            throw failure("CANVAS_REVISION_STALE");
        }
        List<Candidate> result = new ArrayList<>();
        for (Map<String, Object> row : canvasMapper.selectAssistantMediaCandidates(scope.workspaceId(),
                scope.objectId(), scope.objectVersion(), CANDIDATE_TASK_LIMIT)) {
            Long resultId = number(row, "resultId");
            Long taskId = number(row, "taskId");
            Long inputObjectVersion = number(row, "inputObjectVersion");
            if (resultId == null || taskId == null || !eligibleInputVersion(inputObjectVersion, scope.objectVersion())) continue;
            result.add(new Candidate(artifactRef(resultId), resultId, taskId, text(row, "mediaType"),
                    text(row, "mimeType"), number(row, "resourceId"), inputObjectVersion));
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Adoption replay(RuntimeScopeAccess.Scope scope, Long resultId, String invocationId) {
        requireAuthorizedScope(scope);
        validateAdoptionRequest(resultId, invocationId);
        String operationId = operationId(scope.runId(), resultId, invocationId);
        Operation existing = canvasMapper.selectOperation(scope.workspaceId(), operationId);
        return existing == null ? null : replayOperation(existing, requestHash(scope, resultId, invocationId),
                scope.userId(), scope.runId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Adoption adopt(RuntimeScopeAccess.Scope scope, Long resultId, String invocationId,
                          RuntimeOperationSubmissionClaim submissionClaim) {
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(submissionClaim);
        if (submissionClaim == null || !Objects.equals(scope == null ? null : scope.runId(), submissionClaim.getRunId())
                || !Objects.equals(scope == null ? null : scope.userId(), submissionClaim.getUserId())
                || !Objects.equals(invocationId, submissionClaim.getIdempotencyKey())) {
            throw failure("RUNTIME_OPERATION_IDENTITY_MISMATCH");
        }
        requireScope(scope);
        validateAdoptionRequest(resultId, invocationId);
        String operationId = operationId(scope.runId(), resultId, invocationId);
        String requestHash = requestHash(scope, resultId, invocationId);
        Operation existing = canvasMapper.selectOperation(scope.workspaceId(), operationId);
        if (existing != null) return replayOperation(existing, requestHash, scope.userId(), scope.runId());
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            throw failure("CANVAS_ADOPT_RECEIPT_PENDING");
        }
        Workspace workspace = canvasMapper.selectWorkspaceForUpdate(scope.workspaceId());
        if (workspace == null || !Objects.equals(workspace.getProjectId(), scope.projectId())) {
            throw failure("CANVAS_WORKSPACE_MISSING");
        }

        long nodeFence = collaborationService.requireAssistantRuntimeFenceForMutation(scope.workspaceId(),
                scope.objectId(), scope.objectVersion(), scope.nodeFence(), scope.userId(), scope.runId());
        if (!Objects.equals(scope.nodeFence(), nodeFence)) {
            throw failure("CANVAS_FENCE_CHANGED");
        }
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(scope.workspaceId(), scope.objectId());
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), scope.objectVersion())) {
            throw failure("CANVAS_REVISION_STALE");
        }
        Map<String, Object> mediaResult = canvasMapper.selectAssistantMediaResultIdentity(scope.workspaceId(), scope.objectId(),
                resultId);
        Long taskId = number(mediaResult, "taskId");
        Long inputObjectVersion = number(mediaResult, "inputObjectVersion");
        if (mediaResult == null || taskId == null || !eligibleInputVersion(inputObjectVersion, scope.objectVersion())) {
            throw failure("CANVAS_CANDIDATE_SCOPE_MISMATCH");
        }

        NodeResultSelection selection = canvasMapper.selectNodeResultSelection(scope.workspaceId(), scope.objectId());
        if (selection == null) {
            selection = new NodeResultSelection();
            selection.setProjectId(scope.projectId());
            selection.setWorkspaceId(scope.workspaceId());
            selection.setObjectId(scope.objectId());
            selection.setTaskId(taskId);
            selection.setResultId(resultId);
            selection.setInputObjectVersion(inputObjectVersion);
            selection.setSelectionVersion(1L);
            selection.setLastActorUserId(scope.userId());
            if (canvasMapper.insertNodeResultSelection(selection) != 1) {
                throw failure("CANVAS_SELECTION_CONFLICT");
            }
        } else {
            if (canvasMapper.updateNodeResultSelection(scope.workspaceId(), scope.objectId(), taskId, resultId,
                    inputObjectVersion, scope.userId()) != 1) {
                throw failure("CANVAS_SELECTION_CONFLICT");
            }
            selection = canvasMapper.selectNodeResultSelection(scope.workspaceId(), scope.objectId());
        }

        if (canvasMapper.updateCanvasObject(scope.workspaceId(), scope.objectId(), scope.objectVersion(),
                node.getContentJson(), node.getContentHash(), 0, scope.userId(), nodeFence) != 1) {
            throw failure("CANVAS_ADOPT_CAS_CONFLICT");
        }
        CanvasObject updatedNode = canvasMapper.selectCanvasObject(scope.workspaceId(), scope.objectId());
        if (updatedNode == null || !Objects.equals(updatedNode.getObjectVersion(), scope.objectVersion() + 1)) {
            throw failure("CANVAS_ADOPT_NOT_PERSISTED");
        }
        if (canvasMapper.applyWorkspaceDelta(scope.workspaceId(), 0, 0, 0, 0L) != 1) {
            throw failure("CANVAS_WORKSPACE_CHANGED");
        }
        Workspace updatedWorkspace = canvasMapper.selectWorkspace(scope.workspaceId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactRef", artifactRef(resultId));
        payload.put("sourceType", "ASSISTANT_RUNTIME");
        payload.put("sourceRunId", scope.runId().toString());
        payload.put("resultId", resultId.toString());
        payload.put("taskId", taskId.toString());
        payload.put("inputObjectVersion", inputObjectVersion);
        payload.put("objectId", scope.objectId());
        payload.put("previousObjectVersion", scope.objectVersion());
        payload.put("objectVersion", updatedNode.getObjectVersion());
        payload.put("selectionVersion", selection.getSelectionVersion());
        payload.put("revision", updatedWorkspace.getRevision());
        payload.put("eventSeq", updatedWorkspace.getEventSeq());
        payload.put("operationId", operationId);
        payload.put("requestHash", requestHash);
        String payloadJson = JSON.toJSONString(payload);

        Operation operation = new Operation();
        operation.setProjectId(scope.projectId());
        operation.setWorkspaceId(scope.workspaceId());
        operation.setOperationId(operationId);
        operation.setActorUserId(scope.userId());
        operation.setWorkspaceRevision(updatedWorkspace.getRevision());
        operation.setEventSeq(updatedWorkspace.getEventSeq());
        operation.setOperationType("ASSISTANT_ARTIFACT_ADOPT");
        operation.setPayloadJson(payloadJson);
        operation.setPayloadHash(requestHash);
        operation.setCreateTime(new Date());
        if (canvasMapper.insertOperation(operation) != 1) {
            throw failure("CANVAS_ADOPT_LOG_FAILED");
        }
        OperationObject snapshot = new OperationObject();
        snapshot.setOperationRowId(operation.getId());
        snapshot.setProjectId(scope.projectId());
        snapshot.setWorkspaceId(scope.workspaceId());
        snapshot.setOperationId(operationId);
        snapshot.setObjectId(scope.objectId());
        snapshot.setObjectType("NODE");
        snapshot.setBeforeVersion(scope.objectVersion());
        snapshot.setAfterVersion(updatedNode.getObjectVersion());
        snapshot.setBeforeDeleted(0);
        snapshot.setAfterDeleted(0);
        snapshot.setBeforeContentJson(node.getContentJson());
        if (canvasMapper.insertOperationObjects(List.of(snapshot)) != 1) {
            throw failure("CANVAS_ADOPT_SNAPSHOT_FAILED");
        }
        afterCommit(() -> collaborationService.publishWorkspaceEvent(scope.workspaceId(),
                JSON.toJSONString(Map.of("type", "ASSISTANT_ARTIFACT_ADOPTED", "operationId", operationId,
                        "objectId", scope.objectId(), "objectVersion", updatedNode.getObjectVersion(),
                        "artifactRef", artifactRef(resultId)))));
        return adoption(payload);
    }

    // A result belongs to its original input revision. Selecting it advances the node,
    // so equality would hide every previous result after the first adoption or edit.
    static boolean eligibleInputVersion(Long inputVersion, Long currentVersion) {
        return inputVersion != null && currentVersion != null
                && inputVersion > 0 && inputVersion <= currentVersion;
    }

    private static void requireScope(RuntimeScopeAccess.Scope scope) {
        requireAuthorizedScope(scope);
        if (scope.nodeFence() == null) {
            throw failure("CANVAS_SCOPE_NOT_RESERVED");
        }
    }

    private static void requireAuthorizedScope(RuntimeScopeAccess.Scope scope) {
        if (scope == null || scope.runId() == null || scope.userId() == null || scope.projectId() == null
                || scope.workspaceId() == null || scope.objectId() == null || scope.objectVersion() == null
                || scope.runtimeFencingToken() == null) {
            throw failure("CANVAS_SCOPE_INCOMPLETE");
        }
    }

    private static Adoption replayOperation(Operation operation, String requestHash, Long userId, Long runId) {
        if (!"ASSISTANT_ARTIFACT_ADOPT".equals(operation.getOperationType())
                || !Objects.equals(requestHash, operation.getPayloadHash())
                || !Objects.equals(userId, operation.getActorUserId())) {
            throw failure("CANVAS_ADOPT_KEY_REUSED");
        }
        JSONObject payload = JSON.parseObject(operation.getPayloadJson());
        if (!Objects.equals(Objects.toString(runId, null), payload.getString("sourceRunId"))) {
            throw failure("CANVAS_ADOPT_RECEIPT_MISMATCH");
        }
        return adoption(payload);
    }

    private static void validateAdoptionRequest(Long resultId, String invocationId) {
        if (resultId == null || resultId < 1 || invocationId == null || invocationId.isBlank()
                || invocationId.length() > 100) {
            throw failure("CANVAS_ADOPT_REQUEST_INVALID");
        }
    }

    private static String requestHash(RuntimeScopeAccess.Scope scope, Long resultId, String invocationId) {
        return CanvasAssistantOperationCanonicalizer.candidateAdoptionRequestHash(scope.runId(), scope.userId(),
                scope.workspaceId(), scope.objectId(), scope.objectVersion(), resultId, invocationId);
    }

    private static Adoption adoption(Map<String, ?> payload) {
        return new Adoption(Objects.toString(payload.get("artifactRef"), null), number(payload, "resultId"),
                number(payload, "taskId"), Objects.toString(payload.get("objectId"), null),
                number(payload, "previousObjectVersion"), number(payload, "objectVersion"),
                number(payload, "selectionVersion"), number(payload, "revision"), number(payload, "eventSeq"),
                Objects.toString(payload.get("operationId"), null));
    }

    private static String artifactRef(Long resultId) {
        return "canvas:media-result:" + resultId;
    }

    private static String operationId(Long runId, Long resultId, String invocationId) {
        return CanvasAssistantOperationCanonicalizer.candidateAdoptionOperationId(runId, resultId, invocationId);
    }

    private static Long number(Map<String, ?> row, String key) {
        if (row == null) return null;
        Object value = row.get(key);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String text(Map<String, ?> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private static ServiceException failure(String code) {
        return new ServiceException("\u5019\u9009\u91c7\u7528\u5931\u8d25").setDetailMessage(code);
    }
}
