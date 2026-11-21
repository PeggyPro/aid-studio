package com.aid.canvas.runtime;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.AssistantContextProgression;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccessRegistry;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.OperationObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.runtime.assistant.execution.AssistantCompletedToolScopeVerifier;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Advances exact own writes and separately proven unrelated workspace writes. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantContextProgression implements AssistantContextProgression {
    private static final int OPERATION_PAGE_SIZE = 200;
    private static final Set<String> CREATES = Set.of(CanvasAssistantToolProvider.NODE_CREATE_TEXT,
            CanvasAssistantToolProvider.NODE_CREATE_RELATED,
            CanvasAssistantToolProvider.NODE_CREATE_TEXT_FROM_RESULT,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE, CanvasAssistantToolProvider.NODE_CREATE_VIDEO,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_MUSIC, CanvasAssistantToolProvider.NODE_CREATE_AUDIO);
    private static final Set<String> MEDIA_EVENTS = Set.of(
            "TASK_ACCEPTED", "TASK_TERMINAL", "RESULT_AUTO_SELECTED");
    private static final Set<String> OWN_WRITES = Set.of(
            "ASSISTANT_NODE_CREATE", "ASSISTANT_NODE_PATCH", "ASSISTANT_ARTIFACT_ADOPT");
    private static final Set<String> OBJECT_WRITES = Set.of(
            "MUTATE", "AGENT_TEXT", "COPY", "UNDO", "TEXT_AUTO_APPLIED",
            "ASSISTANT_NODE_CREATE", "ASSISTANT_NODE_PATCH", "ASSISTANT_ARTIFACT_ADOPT");
    private static final Set<String> WORKSPACE_ONLY_WRITES = Set.of("IMPORT", "ASSET_TRANSFER");
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final RuntimeScopeAccessRegistry scopeAccess;
    private final AidRuntimeRunStepMapper steps;
    private final AssistantCompletedToolScopeVerifier completedTools;
    private final CanvasAssistantTargetEffectProjector projector;
    private final CanvasAssistantMediaBindingVerifier mediaBindings;
    private final AssistantContextLimits contextLimits;

    @Override
    public boolean supports(AidAssistantSession session) {
        return session != null && session.getWorkspaceId() != null && session.getWorkspaceId() > 0;
    }

    @Override
    public Progressed resolvePinned(Long userId, AidAssistantSession session, AidRuntimeRun run,
                                    String authorityManifestJson) {
        check(userId != null && run != null && session != null
                && Objects.equals(userId, run.getUserId()) && Objects.equals(userId, session.getUserId())
                && Objects.equals(run.getSessionId(), session.getId()), "CANVAS_PROGRESSION_OWNER_CHANGED");
        JSONObject authority = object(authorityManifestJson);
        Workspace workspace = accessService.requireWorkspace(session.getWorkspaceId(), userId,
                "OWNER", "EDITOR", "VIEWER");
        State initial = state(authority, authority, workspace);
        String frozenWorkspace = initial.versions().get("");
        check(frozenWorkspace != null, "CANVAS_PROGRESSION_WORKSPACE_MISSING");
        long start = version(frozenWorkspace, true);
        Long current = workspace.getRevision();
        check(current != null && current >= start, "CANVAS_PROGRESSION_REVISION_CHANGED");
        Map<String, String> expected = new LinkedHashMap<>(initial.versions());
        if (current > start) {
            proveWrites(userId, run, authority, workspace, start, current, expected);
        }
        List<ResolvedContextRef> original = new ArrayList<>();
        List<ResolvedContextRef> execution = new ArrayList<>();
        JSONArray refs = authority.getJSONArray("refs");
        for (int index = 0; index < refs.size(); index++) {
            JSONObject row = refs.getJSONObject(index);
            AssistantRequests.ContextRef frozen = ref(row);
            if (!"FREE_CANVAS".equals(frozen.getKind())) {
                ResolvedContextRef pinned = scopeAccess.resolvePinnedAll(userId, List.of(frozen)).get(0);
                check(Objects.equals(pinned.contentRef(), row.getString("contentRef"))
                        && Objects.equals(pinned.access(), row.getString("access")),
                        "CANVAS_PROGRESSION_OTHER_CONTEXT_CHANGED");
                original.add(pinned);execution.add(pinned);continue;
            }
            String key = frozen.getObjectId() == null ? "" : frozen.getObjectId();
            ResolvedContextRef now = currentRef(userId, workspace, key);
            check(Objects.equals(now.contextRef().getVersion(), expected.get(key))
                    && Objects.equals(now.access(), row.getString("access")),
                    "CANVAS_PROGRESSION_TARGET_CHANGED");
            original.add(new ResolvedContextRef(frozen, row.getString("contentRef"), now.access()));
            execution.add(now);
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (initial.versions().containsKey(entry.getKey())) continue;
            ResolvedContextRef created = currentRef(userId, workspace, entry.getKey());
            check(Objects.equals(created.contextRef().getVersion(), entry.getValue())
                    && Objects.equals(created.access(), initial.access()), "CANVAS_PROGRESSION_CREATED_TARGET_CHANGED");
            execution.add(created);
        }
        check(execution.size() <= contextLimits.maxReferences() + 1, "CANVAS_PROGRESSION_REF_LIMIT");
        Workspace rechecked = accessService.requireWorkspace(workspace.getId(), userId,
                "OWNER", "EDITOR", "VIEWER");
        check(Objects.equals(rechecked.getRevision(), current), "CANVAS_PROGRESSION_CONCURRENT_CHANGE");
        ResolvedContextRef workspaceNow = currentRef(userId, workspace, "");
        check(Objects.equals(workspaceNow.access(), initial.access())
                && Objects.equals(workspaceNow.contextRef().getVersion(), String.valueOf(current)),
                "CANVAS_PROGRESSION_PERMISSION_CHANGED");
        return new Progressed(List.copyOf(original), List.copyOf(execution));
    }

    /** Prove every intervening write to referenced nodes was presentation-only. */
    @Override
    public boolean matchesExpectedRevisions(Long userId, AidAssistantSession session, AidRuntimeRun run,
                                            JSONArray observed, String executionManifestJson) {
        if (userId == null || session == null || run == null
                || !Objects.equals(userId, session.getUserId()) || !Objects.equals(userId, run.getUserId())
                || !Objects.equals(session.getId(), run.getSessionId()) || session.getWorkspaceId() == null)
            return false;
        if (com.aid.assistant.service.AssistantReferenceContextFence
                .matchesExpectedRevisions(observed, executionManifestJson)) return true;
        JSONObject normalized;
        try { normalized = JSON.parseObject(executionManifestJson); }
        catch (RuntimeException invalid) { return false; }
        JSONArray refs = normalized == null ? null : normalized.getJSONArray("refs");
        if (observed == null || refs == null || observed.size() != refs.size()) return false;
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, String> finalVersions = new LinkedHashMap<>();
        Long start = null, end = null, projectId = null;
        for (int index = 0; index < refs.size(); index++) {
            if (!(observed.get(index) instanceof JSONObject old)
                    || !(refs.get(index) instanceof JSONObject row)
                    || !(old.get("canonicalRef") instanceof JSONObject before)
                    || !(row.get("canonicalRef") instanceof JSONObject after)) return false;
            if (!"FREE_CANVAS".equals(row.getString("kind"))) continue;
            if (!Objects.equals(after.getLong("workspaceId"), session.getWorkspaceId())) return false;
            String objectId = after.getString("objectId");
            if (objectId == null) {
                if (start != null) return false;
                start = version(before.getString("version"), true);
                end = version(after.getString("version"), true);
                projectId = after.getLong("projectId");
            } else if (!Objects.equals(before.getString("version"), after.getString("version"))) {
                if (version(before.getString("version"), false) >= version(after.getString("version"), false)
                        || versions.putIfAbsent(objectId, before.getString("version")) != null) return false;
                finalVersions.put(objectId, after.getString("version"));
                after.put("version", before.getString("version"));
            }
        }
        // Removing only the candidate version differences must leave exactly the same identities.
        if (start == null || end == null || start >= end || versions.isEmpty()
                || !com.aid.assistant.service.AssistantReferenceContextFence
                .matchesExpectedRevisions(observed, normalized.toJSONString())) return false;
        Workspace workspace = accessService.requireWorkspace(session.getWorkspaceId(), userId,
                "OWNER", "EDITOR", "VIEWER");
        if (!Objects.equals(workspace.getRevision(), end)
                || !Objects.equals(workspace.getProjectId(), projectId)) return false;
        long revision = start;
        while (revision < end) {
            List<Operation> operations = canvasMapper.selectOperationsAfterRevision(
                    workspace.getId(), revision, OPERATION_PAGE_SIZE);
            if (operations == null || operations.isEmpty()) return false;
            for (Operation operation : operations) {
                if (revision >= end) break;
                if (operation == null || operation.getId() == null
                        || !Objects.equals(operation.getWorkspaceId(), workspace.getId())
                        || !Objects.equals(operation.getProjectId(), projectId)
                        || !Objects.equals(operation.getWorkspaceRevision(), revision + 1)) return false;
                List<OperationObject> changed = canvasMapper.selectOperationObjects(operation.getId());
                if (changed == null || changed.stream().anyMatch(item -> item == null || item.getObjectId() == null))
                    return false;
                if (changed.stream().anyMatch(item -> versions.containsKey(item.getObjectId()))
                        && !advancePresentationWrites(userId, operation, versions, Map.of())) return false;
                revision++;
            }
        }
        Workspace latest = accessService.requireWorkspace(workspace.getId(), userId,
                "OWNER", "EDITOR", "VIEWER");
        return Objects.equals(latest.getRevision(), end) && versions.equals(finalVersions);
    }

    private void proveWrites(Long userId, AidRuntimeRun run,
                             JSONObject authority, Workspace workspace, long start, long current,
                             Map<String, String> expected) {
        List<AidRuntimeRunStep> candidates = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getStepKind, "TOOL")
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED").eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByAsc(AidRuntimeRunStep::getId));
        check(candidates != null, "CANVAS_PROGRESSION_STEP_QUERY_FAILED");
        Map<String, AidRuntimeRunStep> byReceipt = new LinkedHashMap<>();
        for (AidRuntimeRunStep step : candidates) {
            if (step == null || step.getToolCode() == null) continue;
            if (!CREATES.contains(step.getToolCode())
                    && !Set.of(CanvasAssistantToolProvider.NODE_PATCH, CanvasAssistantToolProvider.ADOPT).contains(step.getToolCode())) continue;
            JSONObject output = object(step.getOutputJson());
            String receiptId = output.getString(CanvasAssistantToolProvider.ADOPT.equals(step.getToolCode())
                    ? "operationId" : "receiptId");
            check(receiptId != null && byReceipt.putIfAbsent(receiptId, step) == null,
                    "CANVAS_PROGRESSION_DUPLICATE_RECEIPT");
        }
        Map<String, SelectionState> finalSelections = new LinkedHashMap<>();
        String authorityAccess = state(authority, authority, workspace).access();
        long revision = start;
        long eventSeq = -1;
        while (revision < current) {
            List<Operation> operations = canvasMapper.selectOperationsAfterRevision(
                    workspace.getId(), revision, OPERATION_PAGE_SIZE);
            check(operations != null && !operations.isEmpty(), "CANVAS_PROGRESSION_RECEIPT_GAP");
            for (Operation operation : operations) {
                if (revision >= current) break;
                check(operation != null && operation.getId() != null
                        && Objects.equals(operation.getWorkspaceId(), workspace.getId())
                        && Objects.equals(operation.getProjectId(), workspace.getProjectId())
                        && Objects.equals(operation.getWorkspaceRevision(), revision + 1)
                        && operation.getEventSeq() != null && operation.getEventSeq() > eventSeq
                        && operation.getOperationType() != null
                        && operation.getOperationId() != null && !operation.getOperationId().isBlank()
                        && operation.getPayloadHash() != null
                        && operation.getPayloadHash().matches("[0-9a-f]{64}"),
                        "CANVAS_PROGRESSION_OPERATION_SEQUENCE_CHANGED");
                if (MEDIA_EVENTS.contains(operation.getOperationType())) {
                    if (!mediaBindings.isForRun(run, operation)) {
                        check(provesUnrelatedToTrackedReferences(operation, expected, run.getId()),
                                "CANVAS_PROGRESSION_REFERENCED_TARGET_CHANGED");
                        revision++; expected.put("", String.valueOf(revision)); eventSeq = operation.getEventSeq();
                        continue;
                    }
                    check(Objects.equals(operation.getActorUserId(), userId),
                            "CANVAS_PROGRESSION_MEDIA_OWNER_CHANGED");
                    check("MUTATE".equals(authorityAccess),
                            "CANVAS_PROGRESSION_WRITE_NOT_AUTHORIZED");
                    var proof = mediaBindings.requireEvent(userId, run, operation);
                    State before = state(object(proof.contextManifestJson()), authority, workspace);
                    String objectId = proof.target().objectId();
                    List<OperationObject> mediaObjects = canvasMapper.selectOperationObjects(operation.getId());
                    check(Objects.equals(before.access(), authorityAccess)
                            && Objects.equals(before.versions().get(objectId), expected.get(objectId))
                            && Objects.equals(expected.get(objectId), String.valueOf(proof.target().objectVersion()))
                            && version(before.versions().get(""), true) <= revision
                            && mediaObjects != null && mediaObjects.isEmpty(),
                            "CANVAS_PROGRESSION_MEDIA_SCOPE_CHANGED");
                    if ("RESULT_AUTO_SELECTED".equals(operation.getOperationType())) {
                        recordSelection(finalSelections, objectId, object(operation.getPayloadJson()), "inputObjectVersion");
                    }
                    // Task binding/selection events advance workspace sequence only. They never manufacture a node version.
                    revision++; expected.put("", String.valueOf(revision)); eventSeq = operation.getEventSeq();
                    continue;
                }
                JSONObject receipt = optionalObject(operation.getPayloadJson());
                boolean ownWrite = receipt != null
                        && "ASSISTANT_RUNTIME".equals(receipt.getString("sourceType"))
                        && Objects.equals(receipt.getString("sourceRunId"), String.valueOf(run.getId()));
                if (!ownWrite) {
                    check(provesUnrelatedToTrackedReferences(operation, expected, run.getId())
                                    || advancePresentationWrites(userId, operation, expected, finalSelections),
                            "CANVAS_PROGRESSION_REFERENCED_TARGET_CHANGED");
                    revision++; expected.put("", String.valueOf(revision)); eventSeq = operation.getEventSeq();
                    continue;
                }
                check(Objects.equals(operation.getActorUserId(), userId)
                        && "MUTATE".equals(authorityAccess)
                        && OWN_WRITES.contains(operation.getOperationType())
                        && Objects.equals(receipt.getString("ASSISTANT_ARTIFACT_ADOPT".equals(operation.getOperationType())
                            ? "operationId" : "receiptId"), operation.getOperationId()),
                        "CANVAS_PROGRESSION_RECEIPT_OWNER_CHANGED");
                AidRuntimeRunStep step = byReceipt.get(operation.getOperationId());
                check(step != null, "CANVAS_PROGRESSION_STEP_MISSING");
                VerifiedRuntimeToolEffect effect = completedTools.require(userId, run, step);
                State before = state(object(effect.contextManifestJson()), authority, workspace);
                check(before.versions().equals(expected)
                        && Objects.equals(before.access(), authorityAccess),
                        "CANVAS_PROGRESSION_EXECUTION_SCOPE_CHANGED");
                var projection = projector.project(effect);
                check(projection != null && projection.resultTargetRef() != null
                        && Objects.equals(projection.resultTargetRef().getWorkspaceId(), String.valueOf(workspace.getId()))
                        && Objects.equals(projection.resultTargetRef().getProjectId(), String.valueOf(workspace.getProjectId())),
                        "CANVAS_PROGRESSION_TARGET_SCOPE_CHANGED");
                String objectId = projection.resultTargetRef().getObjectId();
                boolean create = CREATES.contains(step.getToolCode());
                check(create == "ASSISTANT_NODE_CREATE".equals(operation.getOperationType())
                        && (create || Objects.equals(operation.getOperationType(),
                            CanvasAssistantToolProvider.ADOPT.equals(step.getToolCode())
                                    ? "ASSISTANT_ARTIFACT_ADOPT" : "ASSISTANT_NODE_PATCH")),
                        "CANVAS_PROGRESSION_OPERATION_CHANGED");
                long beforeVersion = create ? 0L : version(expected.get(objectId), false);
                check(!create || !expected.containsKey(objectId), "CANVAS_PROGRESSION_CREATE_ALREADY_PRESENT");
                List<OperationObject> objects = canvasMapper.selectOperationObjects(operation.getId());
                boolean related = CanvasAssistantToolProvider.NODE_CREATE_RELATED.equals(step.getToolCode());
                // The projector above already verifies every edge against the signed full-batch receipt.
                check(objects != null && (related ? objects.size() > 1 : objects.size() == 1),
                        "CANVAS_PROGRESSION_OBJECT_RECEIPT_CHANGED");
                List<OperationObject> nodes = objects.stream().filter(value -> "NODE".equals(value.getObjectType())).toList();
                check(nodes.size() == 1 && objects.stream().allMatch(value -> "NODE".equals(value.getObjectType())
                        || related && "EDGE".equals(value.getObjectType())), "CANVAS_PROGRESSION_OBJECT_RECEIPT_CHANGED");
                OperationObject changed = nodes.get(0);
                check(Objects.equals(changed.getObjectId(), objectId) && "NODE".equals(changed.getObjectType())
                        && Objects.equals(changed.getBeforeVersion(), beforeVersion)
                        && beforeVersion < Long.MAX_VALUE
                        && Objects.equals(changed.getAfterVersion(), beforeVersion + 1)
                        && Objects.equals(changed.getAfterDeleted(), 0)
                        && Objects.equals(projection.resultTargetRef().getVersion(),
                            String.valueOf(changed.getAfterVersion())),
                        "CANVAS_PROGRESSION_OBJECT_VERSION_CHANGED");
                if ("ASSISTANT_ARTIFACT_ADOPT".equals(operation.getOperationType())) {
                    recordAdoptedSelection(finalSelections, workspace.getId(), objectId, receipt);
                }
                expected.put(objectId, String.valueOf(changed.getAfterVersion()));
                revision++; expected.put("", String.valueOf(revision)); eventSeq = operation.getEventSeq();
            }
        }
        for (Map.Entry<String, SelectionState> entry : finalSelections.entrySet()) {
            var selection = canvasMapper.selectNodeResultSelection(workspace.getId(), entry.getKey());
            SelectionState wanted = entry.getValue();
            check(selection != null && Objects.equals(selection.getTaskId(), wanted.taskId())
                    && Objects.equals(selection.getResultId(), wanted.resultId())
                    && Objects.equals(selection.getInputObjectVersion(), wanted.inputVersion())
                    && Objects.equals(selection.getSelectionVersion(), wanted.selectionVersion()),
                    "CANVAS_PROGRESSION_FINAL_SELECTION_CHANGED");
        }
        check(revision == current && workspace.getEventSeq() != null && eventSeq <= workspace.getEventSeq(),
                "CANVAS_PROGRESSION_INCOMPLETE_CHAIN");
    }

    private boolean advancePresentationWrites(Long userId, Operation operation, Map<String, String> expected,
                                               Map<String, SelectionState> verifiedSelections) {
        if (!"MUTATE".equals(operation.getOperationType())) return false;
        JSONObject payload = optionalObject(operation.getPayloadJson());
        if (payload == null || "ASSISTANT_RUNTIME".equals(payload.getString("sourceType"))
                || !Objects.equals(payload.getLong("revision"), operation.getWorkspaceRevision())
                || !Objects.equals(payload.getLong("eventSeq"), operation.getEventSeq())) return false;
        List<OperationObject> changed = canvasMapper.selectOperationObjects(operation.getId());
        if (changed == null || !completeOperationObjects(operation, changed)) return false;
        JSONArray objects = payload.getJSONArray("objects");
        if (objects == null || objects.size() != changed.size()) return false;
        Map<String, JSONObject> afterById = new LinkedHashMap<>();
        for (Object raw : objects) {
            if (!(raw instanceof JSONObject row) || row.getString("objectId") == null
                    || afterById.putIfAbsent(row.getString("objectId"), row) != null) return false;
        }
        Map<String, String> advanced = new LinkedHashMap<>();
        for (OperationObject object : changed) {
            JSONObject after = afterById.get(object.getObjectId());
            if (after == null || !Objects.equals(after.getString("objectType"), object.getObjectType())
                    || !Objects.equals(after.getLong("objectVersion"), object.getAfterVersion())) return false;
            if (!expected.containsKey(object.getObjectId())) continue;
            if (!"NODE".equals(object.getObjectType())
                    || !Objects.equals(object.getBeforeDeleted(), 0) || !Objects.equals(object.getAfterDeleted(), 0)
                    || !Boolean.FALSE.equals(after.getBoolean("deleted"))
                    || !Objects.equals(expected.get(object.getObjectId()), String.valueOf(object.getBeforeVersion()))
                    || !(CanvasAssistantPresentationMutation.sameContent(object.getBeforeContentJson(),
                            after.getJSONObject("content"))
                        || advancesVerifiedMediaResource(userId, operation, object, after.getJSONObject("content"),
                                verifiedSelections.get(object.getObjectId())))) return false;
            advanced.put(object.getObjectId(), String.valueOf(object.getAfterVersion()));
        }
        if (advanced.isEmpty()) return false;
        expected.putAll(advanced);
        return true;
    }

    private boolean advancesVerifiedMediaResource(Long userId, Operation operation, OperationObject object,
                                                  JSONObject after, SelectionState selection) {
        // This map is populated only after the immutable own media event/adoption proof succeeds.
        // An arbitrary resource edit, another run's output or a later unproved selection is rejected.
        if (selection == null || after == null) return false;
        var resource = canvasMapper.selectCanvasResourceBySource(operation.getWorkspaceId(),
                "MEDIA_RESULT", selection.resultId());
        return resource != null && resource.getId() != null && resource.getId() > 0
                && "ACTIVE".equals(resource.getStatus()) && "MEDIA_RESULT".equals(resource.getSourceType())
                && Objects.equals(resource.getSourceId(), selection.resultId())
                && Objects.equals(resource.getWorkspaceId(), operation.getWorkspaceId())
                && Objects.equals(resource.getProjectId(), operation.getProjectId())
                && Objects.equals(resource.getOwnerUserId(), userId)
                && Objects.equals(resource.getMediaType(), CanvasAssistantPresentationMutation.mediaType(after.getString("nodeType")))
                && CanvasAssistantPresentationMutation.sameContentWithSelectedResource(
                        object.getBeforeContentJson(), after, resource.getId());
    }

    private boolean provesUnrelatedToTrackedReferences(Operation operation, Map<String, String> expected,
                                                        Long currentRunId) {
        JSONObject payload = optionalObject(operation.getPayloadJson());
        if (payload != null && "ASSISTANT_RUNTIME".equals(payload.getString("sourceType"))) {
            String sourceRunId = payload.getString("sourceRunId");
            if (sourceRunId == null || !sourceRunId.matches("[1-9][0-9]{0,18}")
                    || Objects.equals(sourceRunId, String.valueOf(currentRunId))) return false;
        }
        String payloadObjectId = payload == null ? null : payload.getString("objectId");
        boolean targetsTrackedReference = payloadObjectId != null && expected.containsKey(payloadObjectId);
        List<OperationObject> changed = canvasMapper.selectOperationObjects(operation.getId());
        if (changed == null || changed.stream().anyMatch(value -> value == null
                || value.getObjectId() == null || expected.containsKey(value.getObjectId()))) {
            return false;
        }
        String type = operation.getOperationType();
        if ("SCENE_MUTATE".equals(type) || "SCENE_ADOPT".equals(type)) {
            return !targetsTrackedReference && validSceneWrite(operation, payload, changed);
        }
        if (OBJECT_WRITES.contains(type)) {
            return !targetsTrackedReference && completeOperationObjects(operation, changed);
        }
        if (MEDIA_EVENTS.contains(type) || "RESULT_SELECTED".equals(type)) {
            boolean changesSelection = "RESULT_AUTO_SELECTED".equals(type)
                    || "RESULT_SELECTED".equals(type);
            return validSelectionOrTaskEvent(operation, payload) && changed.isEmpty()
                    && (!changesSelection || !targetsTrackedReference);
        }
        return WORKSPACE_ONLY_WRITES.contains(type) && changed.isEmpty()
                && validWorkspaceOnlyWrite(operation, payload, expected);
    }

    /** Scene documents have their own version fence; node writes still need the ordinary object proof. */
    private boolean validSceneWrite(Operation operation, JSONObject payload, List<OperationObject> changed) {
        if (payload == null || !Objects.equals(payload.getLong("revision"), operation.getWorkspaceRevision())
                || !Objects.equals(payload.getLong("eventSeq"), operation.getEventSeq())
                || positive(payload.get("sceneVersion")) == null
                || !Objects.equals(payload.getLong("stateVersion"), payload.getLong("sceneVersion"))) return false;
        JSONArray objects = payload.getJSONArray("objects");
        if (objects == null || objects.size() != changed.size()) return false;
        if ("SCENE_ADOPT".equals(operation.getOperationType())) {
            return changed.isEmpty() && Objects.equals(operation.getOperationId(), payload.getString("adoptionOperationId"));
        }
        if (changed.isEmpty()) return true;
        if (!completeOperationObjects(operation, changed)) return false;
        Map<String, OperationObject> byId = new LinkedHashMap<>();
        for (OperationObject item : changed) byId.put(item.getObjectId(), item);
        for (Object raw : objects) {
            if (!(raw instanceof JSONObject row)) return false;
            OperationObject item = byId.remove(row.getString("objectId"));
            if (item == null || !Objects.equals(item.getObjectType(), row.getString("objectType"))
                    || !Objects.equals(item.getAfterVersion(), row.getLong("objectVersion"))
                    || !Objects.equals(item.getAfterDeleted() == 1, row.getBoolean("deleted"))) return false;
        }
        return byId.isEmpty();
    }

    private boolean completeOperationObjects(Operation operation, List<OperationObject> changed) {
        if (changed.isEmpty()) return false;
        Set<String> objectIds = new HashSet<>();
        for (OperationObject value : changed) {
            if (value == null || !Objects.equals(value.getOperationRowId(), operation.getId())
                    || !Objects.equals(value.getProjectId(), operation.getProjectId())
                    || !Objects.equals(value.getWorkspaceId(), operation.getWorkspaceId())
                    || !Objects.equals(value.getOperationId(), operation.getOperationId())
                    || value.getObjectId() == null
                    || !value.getObjectId().matches("[A-Za-z0-9_-]{1,64}")
                    || !objectIds.add(value.getObjectId())
                    || !Set.of("NODE", "EDGE", "GROUP").contains(value.getObjectType())
                    || value.getBeforeVersion() == null || value.getBeforeVersion() < 0
                    || value.getBeforeVersion() == Long.MAX_VALUE
                    || !Objects.equals(value.getAfterVersion(), value.getBeforeVersion() + 1)
                    || !(Objects.equals(value.getBeforeDeleted(), 0)
                        || Objects.equals(value.getBeforeDeleted(), 1))
                    || !(Objects.equals(value.getAfterDeleted(), 0)
                        || Objects.equals(value.getAfterDeleted(), 1))
                    || value.getBeforeVersion() == 0
                    && (!Objects.equals(value.getBeforeDeleted(), 1)
                        || !Objects.equals(value.getAfterDeleted(), 0)
                        || value.getBeforeContentJson() != null)) {
                return false;
            }
        }
        return true;
    }

    private boolean validSelectionOrTaskEvent(Operation operation, JSONObject payload) {
        if (payload == null || !Objects.equals(payload.getLong("revision"), operation.getWorkspaceRevision())
                || !Objects.equals(payload.getLong("eventSeq"), operation.getEventSeq())
                || payload.getString("objectId") == null
                || !payload.getString("objectId").matches("[A-Za-z0-9_-]{1,64}")
                || positive(payload.get("taskId")) == null) return false;
        if ("TASK_ACCEPTED".equals(operation.getOperationType())
                || "TASK_TERMINAL".equals(operation.getOperationType())) {
            return positive(payload.get("inputObjectVersion")) != null;
        }
        return positive(payload.get("resultId")) != null
                && positive(payload.get("inputObjectVersion")) != null
                && positive(payload.get("selectionVersion")) != null;
    }

    private boolean validWorkspaceOnlyWrite(Operation operation, JSONObject payload,
                                            Map<String, String> expected) {
        if (payload == null || !Objects.equals(payload.getLong("revision"), operation.getWorkspaceRevision())
                || !Objects.equals(payload.getLong("eventSeq"), operation.getEventSeq())) return false;
        if ("ASSET_TRANSFER".equals(operation.getOperationType())) {
            return positive(payload.get("assetId")) != null && positive(payload.get("resourceId")) != null
                    && ("IMAGE".equals(payload.getString("mediaType"))
                        || "VIDEO".equals(payload.getString("mediaType")))
                    && payload.getString("targetCategory") != null
                    && !payload.getString("targetCategory").isBlank();
        }
        JSONArray objects = payload.getJSONArray("objects");
        JSONObject idMap = payload.getJSONObject("idMap");
        if (objects == null || objects.isEmpty() || objects.size() > 2_000
                || idMap == null || idMap.size() != objects.size()) return false;
        Set<String> importedIds = new HashSet<>();
        for (Object raw : objects) {
            if (!(raw instanceof JSONObject object)) return false;
            String objectId = object.getString("objectId");
            if (objectId == null || !objectId.matches("[A-Za-z0-9_-]{1,64}")
                    || expected.containsKey(objectId) || !importedIds.add(objectId)) return false;
        }
        Set<String> mappedIds = new HashSet<>();
        for (String key : idMap.keySet()) {
            String objectId = idMap.getString(key);
            if (key == null || key.isBlank() || objectId == null || !mappedIds.add(objectId)) return false;
        }
        return importedIds.equals(mappedIds);
    }

    private Long positive(Object raw) {
        String value = String.valueOf(raw);
        if (!value.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void recordAdoptedSelection(Map<String, SelectionState> selections, Long workspaceId,
                                        String objectId, JSONObject receipt) {
        // Input provenance is not the node revision at which a historical result was selected.
        // Read the scoped persisted result also for older receipts that predate this field.
        Map<String, Object> row = canvasMapper.selectAssistantMediaResultIdentity(workspaceId, objectId,
                receipt.getLong("resultId"));
        JSONObject source = row == null ? null : new JSONObject(row);
        Long inputVersion = source == null ? null : source.getLong("inputObjectVersion");
        check(source != null && Objects.equals(source.getLong("taskId"), receipt.getLong("taskId"))
                        && CanvasArtifactCandidateServiceImpl.eligibleInputVersion(
                                inputVersion, receipt.getLong("previousObjectVersion"))
                        && (!receipt.containsKey("inputObjectVersion")
                            || Objects.equals(inputVersion, receipt.getLong("inputObjectVersion"))),
                "CANVAS_PROGRESSION_SELECTION_SOURCE_CHANGED");
        JSONObject verified = new JSONObject(receipt);
        verified.put("inputObjectVersion", inputVersion);
        recordSelection(selections, objectId, verified, "inputObjectVersion");
    }

    private void recordSelection(Map<String, SelectionState> selections, String objectId,
                                 JSONObject payload, String inputVersionField) {
        Long taskId = payload.getLong("taskId"), resultId = payload.getLong("resultId");
        Long inputVersion = payload.getLong(inputVersionField), selectionVersion = payload.getLong("selectionVersion");
        check(taskId != null && taskId > 0 && resultId != null && resultId > 0
                && inputVersion != null && inputVersion > 0 && selectionVersion != null && selectionVersion > 0,
                "CANVAS_PROGRESSION_SELECTION_RECEIPT_INVALID");
        SelectionState previous = selections.get(objectId);
        check(previous == null || previous.selectionVersion() < Long.MAX_VALUE
                && selectionVersion == previous.selectionVersion() + 1,
                "CANVAS_PROGRESSION_SELECTION_VERSION_GAP");
        selections.put(objectId, new SelectionState(taskId, resultId, inputVersion, selectionVersion));
    }

    private record SelectionState(Long taskId, Long resultId, Long inputVersion, Long selectionVersion) { }

    private State state(JSONObject manifest, JSONObject authority, Workspace workspace) {
        check("assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                && Set.of("schemaVersion", "sessionScope", "refs").equals(manifest.keySet())
                && Objects.equals(manifest.getJSONObject("sessionScope"), authority.getJSONObject("sessionScope")),
                "CANVAS_PROGRESSION_MANIFEST_CHANGED");
        JSONArray rows = manifest.getJSONArray("refs");
        check(rows != null && !rows.isEmpty() && rows.size() <= contextLimits.maxReferences() + 1,
                "CANVAS_PROGRESSION_MANIFEST_INVALID");
        Map<String, String> versions = new LinkedHashMap<>();
        List<JSONObject> other = new ArrayList<>();String access = null;
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.getJSONObject(index);check(row != null, "CANVAS_PROGRESSION_REF_INVALID");
            if (!"FREE_CANVAS".equals(row.getString("kind"))) {other.add(row);continue;}
            JSONObject canonical = row.getJSONObject("canonicalRef");
            check(canonical != null && Set.of("projectId", "workspaceId", "objectId", "version").containsAll(canonical.keySet())
                    && Objects.equals(canonical.getString("workspaceId"), String.valueOf(workspace.getId()))
                    && Objects.equals(canonical.getString("projectId"), String.valueOf(workspace.getProjectId()))
                    && ("READ".equals(row.getString("access")) || "MUTATE".equals(row.getString("access"))), "CANVAS_PROGRESSION_REF_SCOPE_CHANGED");
            String objectId = canonical.getString("objectId");String key = objectId == null ? "" : objectId;
            String v = canonical.getString("version");version(v, objectId == null);
            check(objectId == null || objectId.matches("[A-Za-z0-9_-]{1,64}"), "CANVAS_PROGRESSION_OBJECT_ID_INVALID");
            check(versions.putIfAbsent(key, v) == null && Objects.equals(row.getString("contentRef"),
                    objectId == null ? "canvas:workspace:" + workspace.getId() + ":" + v
                            : "canvas:node:" + workspace.getId() + ":" + objectId + ":" + v),
                    "CANVAS_PROGRESSION_REF_CONTENT_CHANGED");
            if (objectId == null) access = row.getString("access");
        }
        List<JSONObject> originalOther = new ArrayList<>();
        for (Object raw : authority.getJSONArray("refs")) {
            JSONObject row = (JSONObject)raw;
            if (!"FREE_CANVAS".equals(row.getString("kind"))) originalOther.add(row);
        }
        check(other.equals(originalOther) && access != null, "CANVAS_PROGRESSION_OTHER_REFS_CHANGED");
        for (Object raw : rows) {
            JSONObject row = (JSONObject)raw;
            if ("FREE_CANVAS".equals(row.getString("kind")))
                check(Objects.equals(row.getString("access"), access), "CANVAS_PROGRESSION_ACCESS_CHANGED");
        }
        return new State(versions, access);
    }

    private ResolvedContextRef currentRef(Long userId, Workspace workspace, String key) {
        AssistantRequests.ContextRef identity = new AssistantRequests.ContextRef();
        identity.setKind("FREE_CANVAS");identity.setProjectId(workspace.getProjectId());
        identity.setWorkspaceId(workspace.getId());if (!key.isEmpty()) identity.setObjectId(key);
        return scopeAccess.resolveCurrentAll(userId, List.of(identity)).get(0);
    }
    private AssistantRequests.ContextRef ref(JSONObject row) {
        AssistantRequests.ContextRef ref = row.getJSONObject("canonicalRef").toJavaObject(AssistantRequests.ContextRef.class);
        ref.setKind(row.getString("kind"));return ref;
    }
    private JSONObject object(String json) {
        try {JSONObject result=JSON.parseObject(json);check(result != null, "CANVAS_PROGRESSION_JSON_INVALID");return result;}
        catch (RuntimeException invalid) {throw stale("CANVAS_PROGRESSION_JSON_INVALID");}
    }
    private JSONObject optionalObject(String json) {
        if (json == null) return null;
        try {return JSON.parseObject(json);} catch (RuntimeException invalid) {return null;}
    }
    private long version(String value, boolean workspace) {
        check(value != null && value.matches(workspace ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"),
                "CANVAS_PROGRESSION_VERSION_INVALID");
        try {return Long.parseLong(value);}catch (NumberFormatException invalid) {throw stale("CANVAS_PROGRESSION_VERSION_INVALID");}
    }
    private void check(boolean valid, String reason) {if (!valid) throw stale(reason);}
    private AssistantServiceException stale(String reason) {
        return new AssistantServiceException(HttpStatus.CONFLICT.value(), "ASSISTANT_CONTEXT_STALE", "画布上下文已变更", reason);
    }
    private record State(Map<String, String> versions, String access) { }
}
