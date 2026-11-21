package com.aid.canvas.runtime;

import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.effect.VerifiedArtifactAdoptionEffect;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.OperationObject;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verifies that an Assistant effect is backed by the exact immutable Canvas operation receipt. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantOperationReceiptVerifier {
    private final CanvasMapper canvasMapper;
    private final AidRuntimeArtifactChunkMapper chunkMapper;

    public void requireRuntimeTool(VerifiedRuntimeToolEffect effect, JSONObject arguments, JSONObject output,
                                   Long projectId, Long workspaceId, String objectId,
                                   Long observedVersion, Long resultVersion, Long workspaceVersion) {
        if (effect == null || effect.runId() == null || effect.userId() == null || blank(effect.idempotencyKey())
                || arguments == null || output == null || projectId == null || workspaceId == null
                || blank(objectId) || resultVersion == null || resultVersion < 1) {
            invalid();
        }
        switch (effect.toolCode()) {
            case CanvasAssistantToolProvider.NODE_CREATE_RELATED -> requireRelatedCreate(effect, arguments, output,
                    projectId, workspaceId, objectId, resultVersion, workspaceVersion);
            case CanvasAssistantToolProvider.NODE_PATCH -> requireNodeMutation(effect, arguments, output,
                    projectId, workspaceId, objectId, observedVersion, resultVersion, null,
                    "asst_patch", "PATCH", arguments.getJSONObject("patch"), "ASSISTANT_NODE_PATCH");
            case CanvasAssistantToolProvider.NODE_CREATE_TEXT,
                    CanvasAssistantToolProvider.NODE_CREATE_TEXT_FROM_RESULT -> requireNodeMutation(effect, arguments, output,
                    projectId, workspaceId, objectId, 0L, resultVersion, workspaceVersion,
                    "asst_create_text", "CREATE_TEXT",
                    CanvasAssistantOperationCanonicalizer.textNodePayload(arguments), "ASSISTANT_NODE_CREATE");
            case CanvasAssistantToolProvider.NODE_CREATE_IMAGE,
                    CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
                    CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS -> requireMediaCreate(effect, arguments, output,
                    projectId, workspaceId, objectId, resultVersion, workspaceVersion,
                    "CREATE_IMAGE", "IMAGE", "image", "image", 480, 270);
            case CanvasAssistantToolProvider.NODE_CREATE_VIDEO,
                    CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
                    CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS -> requireMediaCreate(effect, arguments, output,
                    projectId, workspaceId, objectId, resultVersion, workspaceVersion,
                    "CREATE_VIDEO", "VIDEO", "video", "video", 480, 270);
            case CanvasAssistantToolProvider.NODE_CREATE_MUSIC -> requireMediaCreate(effect, arguments, output,
                    projectId, workspaceId, objectId, resultVersion, workspaceVersion,
                    "CREATE_MUSIC", "VOICE", "voice", "audio", 320, 180);
            case CanvasAssistantToolProvider.NODE_CREATE_AUDIO -> requireMediaCreate(effect, arguments, output,
                    projectId, workspaceId, objectId, resultVersion, workspaceVersion,
                    "CREATE_AUDIO", "VOICE", "voice", "audio", 320, 180);
            case CanvasAssistantToolProvider.ADOPT -> requireCandidateAdoption(effect, arguments, output,
                    projectId, workspaceId, objectId, observedVersion, resultVersion);
            default -> invalid();
        }
    }

    public void requireArtifactAdoption(VerifiedArtifactAdoptionEffect effect, JSONObject receipt) {
        if (effect == null || receipt == null || effect.userId() == null || effect.projectId() == null
                || effect.workspaceId() == null || effect.artifactId() == null || blank(effect.clientRequestId())
                || effect.requestDigest() == null || !effect.requestDigest().matches("[a-f0-9]{64}")
                || blank(effect.targetObjectId()) || blank(effect.artifactContentHash())) {
            invalid();
        }
        long before = positive(effect.expectedTargetRevision());
        long after = positive(effect.resultTargetRevision());
        if (after != before + 1) invalid();
        String operationId = CanvasAssistantOperationCanonicalizer.artifactAdoptionOperationId(
                effect.artifactId(), effect.clientRequestId());
        if (!Objects.equals(operationId, receipt.getString("operationId"))) invalid();

        String content = verifiedArtifactContent(effect.artifactId(), effect.artifactContentHash());
        CanvasRequests.Mutation mutation = mutation(effect.targetObjectId(), "PATCH", before,
                Map.of("textContent", content));
        Operation operation = requireOperation(effect.workspaceId(), operationId, effect.projectId(), effect.userId(),
                "MUTATE", CanvasAssistantOperationCanonicalizer.mutationRequestHash(List.of(mutation)));
        requireResult(operation, effect.targetObjectId(), after);
        requireObject(operation, effect.projectId(), effect.workspaceId(), effect.targetObjectId(), before, after,
                0, 0);
    }

    private void requireMediaCreate(VerifiedRuntimeToolEffect effect, JSONObject arguments, JSONObject output,
                                    Long projectId, Long workspaceId, String objectId, Long resultVersion,
                                    Long workspaceVersion, String action, String nodeType, String kind,
                                    String mediaKind, int width, int height) {
        requireNodeMutation(effect, arguments, output, projectId, workspaceId, objectId, 0L, resultVersion,
                workspaceVersion, "asst_" + action.toLowerCase(), action,
                CanvasAssistantOperationCanonicalizer.mediaNodePayload(
                        arguments, nodeType, kind, mediaKind, width, height), "ASSISTANT_NODE_CREATE");
    }

    private void requireRelatedCreate(VerifiedRuntimeToolEffect effect, JSONObject arguments, JSONObject output,
                                      Long projectId, Long workspaceId, String objectId, Long resultVersion,
                                      Long workspaceVersion) {
        if (!Long.valueOf(1).equals(resultVersion) || workspaceVersion == null
                || !Objects.equals(objectId, CanvasAssistantOperationCanonicalizer.toolCreateObjectId(
                effect.runId(), effect.toolCode(), effect.idempotencyKey()))) invalid();
        String operationId = CanvasAssistantOperationCanonicalizer.toolOperationId("asst_related",
                effect.runId(), effect.idempotencyKey());
        if (!Objects.equals(operationId, output.getString("receiptId"))) invalid();
        var sources = CanvasAssistantRelationContract.bind(arguments.getJSONArray("sources"),
                effect.contextManifestJson(), projectId, workspaceId);
        var mutations = CanvasAssistantRelationContract.creationBatch(mutation(objectId, "UPSERT", 0L,
                CanvasAssistantRelationContract.nodePayload(arguments)), sources);
        String hash = CanvasAssistantOperationCanonicalizer.assistantMutationRequestHash(workspaceId,
                effect.userId(), effect.runId(), null, null, workspaceVersion,
                CanvasAssistantRelationContract.nodeDefinition(arguments.getString("nodeKind")).action(), mutations);
        Operation operation = requireOperation(workspaceId, operationId, projectId, effect.userId(),
                "ASSISTANT_NODE_CREATE", hash);
        JSONObject receipt = JSON.parseObject(operation.getPayloadJson());
        if (receipt == null || !Objects.equals(operation.getWorkspaceRevision(), receipt.getLong("revision"))
                || !Objects.equals(operation.getEventSeq(), receipt.getLong("eventSeq"))
                || !Objects.equals("ASSISTANT_RUNTIME", receipt.getString("sourceType"))
                || !Objects.equals(String.valueOf(effect.runId()), receipt.getString("sourceRunId"))
                || receipt.get("sourceObjectId") != null || receipt.get("sourceObjectVersion") != null) invalid();
        JSONArray objects = receipt.getJSONArray("objects");
        List<OperationObject> rows = canvasMapper.selectOperationObjects(operation.getId());
        if (objects == null || rows == null || objects.size() != mutations.size() || rows.size() != mutations.size()) invalid();
        for (CanvasRequests.Mutation expected : mutations) {
            List<JSONObject> matching = objects.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                    .filter(value -> Objects.equals(expected.getObjectId(), value.getString("objectId"))).toList();
            if (matching.size() != 1 || !Long.valueOf(1).equals(matching.get(0).getLong("objectVersion"))) invalid();
            if ("EDGE".equals(expected.getObjectType())
                    && !Objects.equals(expected.getPayload(), matching.get(0).getJSONObject("content"))) invalid();
            var records = rows.stream().filter(value -> Objects.equals(expected.getObjectId(), value.getObjectId())).toList();
            if (records.size() != 1) invalid();
            OperationObject row = records.get(0);
            if (!Objects.equals(operation.getId(), row.getOperationRowId()) || !Objects.equals(projectId, row.getProjectId())
                    || !Objects.equals(workspaceId, row.getWorkspaceId()) || !Objects.equals(operationId, row.getOperationId())
                    || !Objects.equals(expected.getObjectType(), row.getObjectType())
                    || !Long.valueOf(0).equals(row.getBeforeVersion()) || !Long.valueOf(1).equals(row.getAfterVersion())
                    || !Integer.valueOf(1).equals(row.getBeforeDeleted()) || !Integer.valueOf(0).equals(row.getAfterDeleted())) invalid();
        }
    }

    private void requireNodeMutation(VerifiedRuntimeToolEffect effect, JSONObject arguments, JSONObject output,
                                     Long projectId, Long workspaceId, String objectId, Long observedVersion,
                                     Long resultVersion, Long workspaceVersion, String operationPrefix,
                                     String action, Object payload, String operationType) {
        if (observedVersion == null || observedVersion < 0 || resultVersion != observedVersion + 1
                || payload == null || (action.startsWith("CREATE_") && workspaceVersion == null)) {
            invalid();
        }
        String operationId = CanvasAssistantOperationCanonicalizer.toolOperationId(
                operationPrefix, effect.runId(), effect.idempotencyKey());
        String receiptId = output.getString("receiptId");
        if (!Objects.equals(operationId, receiptId)) invalid();
        if (action.startsWith("CREATE_") && !Objects.equals(objectId,
                CanvasAssistantOperationCanonicalizer.toolCreateObjectId(
                        effect.runId(), effect.toolCode(), effect.idempotencyKey()))) {
            invalid();
        }
        String mutationAction = "PATCH".equals(action) ? "PATCH" : "UPSERT";
        CanvasRequests.Mutation mutation = mutation(objectId, mutationAction, observedVersion, payload);
        String requestHash = CanvasAssistantOperationCanonicalizer.assistantMutationRequestHash(
                workspaceId, effect.userId(), effect.runId(), "PATCH".equals(action) ? objectId : null,
                "PATCH".equals(action) ? observedVersion : null,
                "PATCH".equals(action) ? null : workspaceVersion, action, List.of(mutation));
        if (action.startsWith("CREATE_")) {
            Operation stored = canvasMapper.selectOperation(workspaceId, operationId);
            if (stored != null && !Objects.equals(requestHash, stored.getPayloadHash())) {
                requestHash = CanvasAssistantOperationCanonicalizer.assistantCreateReceiptRequestHash(
                        workspaceId, effect.userId(), effect.runId(), workspaceVersion, action,
                        operationId, mutation, stored.getPayloadJson());
            }
        }
        Operation operation = requireOperation(workspaceId, operationId, projectId, effect.userId(),
                operationType, requestHash);
        JSONObject persisted = requireResult(operation, objectId, resultVersion);
        if (!Objects.equals("ASSISTANT_RUNTIME", persisted.getString("sourceType"))
                || !Objects.equals(String.valueOf(effect.runId()), persisted.getString("sourceRunId"))
                || !Objects.equals("PATCH".equals(action) ? objectId : null,
                persisted.getString("sourceObjectId"))
                || !Objects.equals("PATCH".equals(action) ? observedVersion : null,
                persisted.getLong("sourceObjectVersion"))) {
            invalid();
        }
        requireObject(operation, projectId, workspaceId, objectId, observedVersion, resultVersion,
                "PATCH".equals(action) ? 0 : 1, 0);
    }

    private void requireCandidateAdoption(VerifiedRuntimeToolEffect effect, JSONObject arguments, JSONObject output,
                                          Long projectId, Long workspaceId, String objectId,
                                          Long observedVersion, Long resultVersion) {
        Long resultId = positive(arguments.getString("resultId"));
        if (observedVersion == null || resultVersion != observedVersion + 1) invalid();
        String operationId = CanvasAssistantOperationCanonicalizer.candidateAdoptionOperationId(
                effect.runId(), resultId, effect.idempotencyKey());
        if (!Objects.equals(operationId, output.getString("operationId"))) invalid();
        String requestHash = CanvasAssistantOperationCanonicalizer.candidateAdoptionRequestHash(effect.runId(),
                effect.userId(), workspaceId, objectId, observedVersion, resultId, effect.idempotencyKey());
        Operation operation = requireOperation(workspaceId, operationId, projectId, effect.userId(),
                "ASSISTANT_ARTIFACT_ADOPT", requestHash);
        JSONObject persisted = requireResult(operation, objectId, resultVersion);
        if (!Objects.equals(String.valueOf(effect.runId()), persisted.getString("sourceRunId"))
                || !Objects.equals(String.valueOf(resultId), persisted.getString("resultId"))
                || !Objects.equals(objectId, persisted.getString("objectId"))
                || !Objects.equals(observedVersion, persisted.getLong("previousObjectVersion"))
                || !Objects.equals(resultVersion, persisted.getLong("objectVersion"))
                || !Objects.equals(operationId, persisted.getString("operationId"))
                || !Objects.equals(requestHash, persisted.getString("requestHash"))) {
            invalid();
        }
        requireObject(operation, projectId, workspaceId, objectId, observedVersion, resultVersion, 0, 0);
    }

    private Operation requireOperation(Long workspaceId, String operationId, Long projectId, Long actorUserId,
                                       String operationType, String payloadHash) {
        Operation operation = canvasMapper.selectOperation(workspaceId, operationId);
        if (operation == null || operation.getId() == null
                || !Objects.equals(projectId, operation.getProjectId())
                || !Objects.equals(workspaceId, operation.getWorkspaceId())
                || !Objects.equals(operationId, operation.getOperationId())
                || !Objects.equals(actorUserId, operation.getActorUserId())
                || !Objects.equals(operationType, operation.getOperationType())
                || !Objects.equals(payloadHash, operation.getPayloadHash())
                || operation.getWorkspaceRevision() == null || operation.getEventSeq() == null) {
            invalid();
        }
        return operation;
    }

    private JSONObject requireResult(Operation operation, String objectId, Long resultVersion) {
        JSONObject payload;
        try {
            payload = JSON.parseObject(operation.getPayloadJson());
        } catch (RuntimeException invalid) {
            payload = null;
        }
        if (payload == null || !Objects.equals(operation.getWorkspaceRevision(), payload.getLong("revision"))
                || !Objects.equals(operation.getEventSeq(), payload.getLong("eventSeq"))) {
            invalid();
        }
        JSONArray objects = payload.getJSONArray("objects");
        if (objects != null) {
            if (objects.size() != 1 || !(objects.get(0) instanceof JSONObject object)
                    || !Objects.equals(objectId, object.getString("objectId"))
                    || !Objects.equals(resultVersion, object.getLong("objectVersion"))) {
                invalid();
            }
        } else if (!Objects.equals("ASSISTANT_ARTIFACT_ADOPT", operation.getOperationType())) {
            invalid();
        }
        return payload;
    }

    private void requireObject(Operation operation, Long projectId, Long workspaceId, String objectId,
                               Long beforeVersion, Long afterVersion, Integer beforeDeleted, Integer afterDeleted) {
        List<OperationObject> values = canvasMapper.selectOperationObjects(operation.getId());
        if (values == null || values.size() != 1) invalid();
        OperationObject value = values.get(0);
        if (!Objects.equals(operation.getId(), value.getOperationRowId())
                || !Objects.equals(projectId, value.getProjectId())
                || !Objects.equals(workspaceId, value.getWorkspaceId())
                || !Objects.equals(operation.getOperationId(), value.getOperationId())
                || !Objects.equals(objectId, value.getObjectId()) || !Objects.equals("NODE", value.getObjectType())
                || !Objects.equals(beforeVersion, value.getBeforeVersion())
                || !Objects.equals(afterVersion, value.getAfterVersion())
                || !Objects.equals(beforeDeleted, value.getBeforeDeleted())
                || !Objects.equals(afterDeleted, value.getAfterDeleted())) {
            invalid();
        }
    }

    private String verifiedArtifactContent(Long artifactId, String expectedHash) {
        List<AidRuntimeArtifactChunk> chunks = chunkMapper.selectAllActive(artifactId);
        if (chunks == null || chunks.size() > 1_000_000) invalid();
        StringBuilder content = new StringBuilder();
        int charStart = 0;
        for (int index = 0; index < chunks.size(); index++) {
            AidRuntimeArtifactChunk chunk = chunks.get(index);
            if (chunk == null || !Objects.equals(index, chunk.getChunkIndex())
                    || !Objects.equals(charStart, chunk.getCharStart()) || chunk.getCharEnd() == null
                    || chunk.getCharCount() == null || chunk.getContentText() == null
                    || chunk.getCharEnd() - chunk.getCharStart() != chunk.getCharCount()
                    || chunk.getContentText().length() != chunk.getCharCount()
                    || !Objects.equals(CanvasAssistantOperationCanonicalizer.sha256(chunk.getContentText()),
                    chunk.getContentHash())) {
                invalid();
            }
            content.append(chunk.getContentText());
            charStart = chunk.getCharEnd();
            if (content.length() > 65_536) invalid();
        }
        if (!Objects.equals(expectedHash, CanvasAssistantOperationCanonicalizer.sha256(content.toString()))) invalid();
        return content.toString();
    }

    private static CanvasRequests.Mutation mutation(String objectId, String action, Long expectedVersion,
                                                     Object payload) {
        CanvasRequests.Mutation mutation = new CanvasRequests.Mutation();
        mutation.setObjectId(objectId);
        mutation.setObjectType("NODE");
        mutation.setAction(action);
        mutation.setExpectedVersion(expectedVersion);
        mutation.setPayload(payload);
        return mutation;
    }

    private static Long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid();
        try {
            long result = Long.parseLong(value);
            if (result < 1) invalid();
            return result;
        } catch (NumberFormatException invalid) {
            invalid();
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void invalid() {
        throw new ServiceException("Canvas operation receipt is invalid");
    }
}
