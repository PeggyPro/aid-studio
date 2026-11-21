package com.aid.canvas.runtime;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.effect.AssistantRuntimeToolEffectProjector;
import com.aid.assistant.effect.AssistantTargetEffectProjection;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Converts only contract-validated Canvas receipts into safe locate targets. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantTargetEffectProjector implements AssistantRuntimeToolEffectProjector {
    private static final Set<String> CODES = Set.of(
            CanvasAssistantToolProvider.NODE_CREATE_RELATED,
            CanvasAssistantToolProvider.NODE_READ,
            CanvasAssistantToolProvider.CANDIDATES,
            CanvasAssistantToolProvider.NODE_PATCH,
            CanvasAssistantToolProvider.NODE_CREATE_TEXT,
            CanvasAssistantToolProvider.NODE_CREATE_TEXT_FROM_RESULT,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_MUSIC,
            CanvasAssistantToolProvider.NODE_CREATE_AUDIO,
            CanvasAssistantToolProvider.ADOPT);
    private static final Set<String> CREATES = Set.of(
            CanvasAssistantToolProvider.NODE_CREATE_RELATED,
            CanvasAssistantToolProvider.NODE_CREATE_TEXT,
            CanvasAssistantToolProvider.NODE_CREATE_TEXT_FROM_RESULT,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_MUSIC,
            CanvasAssistantToolProvider.NODE_CREATE_AUDIO);
    private final CanvasAssistantOperationReceiptVerifier receiptVerifier;
    private final AssistantContextLimits contextLimits;

    /** Creation evidence and completion eligibility share the same receipt contract. */
    static Set<String> creationToolCodes() { return CREATES; }

    @Override
    public Set<String> supportedToolCodes() {
        return CODES;
    }

    /** Verify the payload actually executed, without resolving current model defaults again. */
    static JSONObject receiptArguments(VerifiedRuntimeToolEffect effect, JSONObject original) {
        if (!Set.of(CanvasAssistantToolProvider.NODE_PATCH, CanvasAssistantToolProvider.NODE_CREATE_RELATED,
                CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
                CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
                CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
                CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS).contains(effect.toolCode())
                || blank(effect.preparedPayloadJson())) return original;
        // The persisted scope codec already verifies the prepared payload digest and approval scope.
        JSONObject prepared = object(effect.preparedPayloadJson());
        if (!Objects.equals(original.get("target"), prepared.get("target"))
                || !Objects.equals(original.get("workspaceId"), prepared.get("workspaceId"))) invalid();
        return prepared;
    }

    @Override
    public AssistantTargetEffectProjection project(VerifiedRuntimeToolEffect effect) {
        if (effect == null || !CODES.contains(effect.toolCode())) invalid();
        JSONObject arguments = CanvasAssistantToolProvider.NODE_CREATE_TEXT_FROM_RESULT.equals(effect.toolCode())
                ? CanvasSkillResultTextPreparation.nodeArguments(effect.preparedPayloadJson(), effect.argumentsJson())
                : object(effect.argumentsJson());
        JSONObject output = object(effect.outputJson());
        CanvasRef scope;
        String objectId;
        String version;
        String observedVersion = null;
        String effectKind;
        if (CREATES.contains(effect.toolCode())) {
            String workspaceId = requiredId(arguments, "workspaceId");
            scope = requireWorkspace(effect.contextManifestJson(), workspaceId);
            objectId = requiredObjectId(output, "objectId");
            version = requiredId(output, "objectVersion");
            effectKind = "CREATED";
        } else {
            JSONObject target = arguments.getJSONObject("target");
            if (target == null) invalid();
            objectId = requiredObjectId(target, "objectId");
            observedVersion = requiredId(target, "objectVersion");
            scope = requireTarget(effect.contextManifestJson(), objectId, observedVersion);
            if (CanvasAssistantToolProvider.CANDIDATES.equals(effect.toolCode())) {
                version = observedVersion;
                effectKind = "OBSERVED";
            } else {
                if (!Objects.equals(objectId, requiredObjectId(output, "objectId"))) invalid();
                version = requiredId(output, "objectVersion");
                if (CanvasAssistantToolProvider.NODE_READ.equals(effect.toolCode())) {
                    if (!Objects.equals(version, observedVersion)) invalid();
                    effectKind = "OBSERVED";
                } else {
                    if (Long.parseLong(version) <= Long.parseLong(observedVersion)) invalid();
                    effectKind = CanvasAssistantToolProvider.ADOPT.equals(effect.toolCode())
                            ? "ADOPTED" : "UPDATED";
                }
            }
        }
        String nodeRef = "canvas:node:" + scope.workspaceId() + ":" + objectId + ":" + version;
        if (!CanvasAssistantToolProvider.CANDIDATES.equals(effect.toolCode())
                && (effect.outputArtifactRefs() == null || !effect.outputArtifactRefs().contains(nodeRef))) invalid();
        if (!Set.of(CanvasAssistantToolProvider.CANDIDATES, CanvasAssistantToolProvider.ADOPT)
                .contains(effect.toolCode()) && !Objects.equals(nodeRef, output.getString("artifactRef"))) invalid();
        if ((CREATES.contains(effect.toolCode()) || CanvasAssistantToolProvider.NODE_PATCH.equals(effect.toolCode()))
                && blank(output.getString("receiptId"))) invalid();
        if (CanvasAssistantToolProvider.ADOPT.equals(effect.toolCode())
                && blank(output.getString("operationId"))) invalid();
        if (CREATES.contains(effect.toolCode()) || CanvasAssistantToolProvider.NODE_PATCH.equals(effect.toolCode())
                || CanvasAssistantToolProvider.ADOPT.equals(effect.toolCode())) {
            receiptVerifier.requireRuntimeTool(effect, receiptArguments(effect, arguments), output, Long.valueOf(scope.projectId()),
                    Long.valueOf(scope.workspaceId()), objectId,
                    CREATES.contains(effect.toolCode()) ? 0L : Long.valueOf(observedVersion),
                    Long.valueOf(version), CREATES.contains(effect.toolCode()) ? Long.valueOf(scope.version()) : null);
        }
        AssistantTargetViews.TargetRef result = target(scope, objectId, version);
        AssistantRequests.ContextRef identity = new AssistantRequests.ContextRef();
        identity.setKind("FREE_CANVAS");
        identity.setProjectId(Long.valueOf(scope.projectId()));
        identity.setWorkspaceId(Long.valueOf(scope.workspaceId()));
        identity.setObjectId(objectId);
        return new AssistantTargetEffectProjection(effectKind, result, identity);
    }

    private CanvasRef requireWorkspace(String manifestJson, String workspaceId) {
        List<CanvasRef> values = canvasRefs(manifestJson).stream()
                .filter(value -> Objects.equals(value.workspaceId(), workspaceId) && value.objectId() == null)
                .toList();
        if (values.size() != 1) invalid();
        return values.get(0);
    }

    private CanvasRef requireTarget(String manifestJson, String objectId, String version) {
        List<CanvasRef> values = canvasRefs(manifestJson).stream()
                .filter(value -> Objects.equals(value.objectId(), objectId)
                        && Objects.equals(value.version(), version)).toList();
        if (values.size() != 1) invalid();
        return values.get(0);
    }

    private List<CanvasRef> canvasRefs(String manifestJson) {
        JSONObject root = object(manifestJson);
        if (!Set.of("schemaVersion", "sessionScope", "refs").equals(root.keySet())
                || !"assistant-context-manifest/v1".equals(root.getString("schemaVersion"))) invalid();
        JSONArray refs = root.getJSONArray("refs");
        if (refs == null || refs.isEmpty() || refs.size() > contextLimits.maxReferences() + 1) invalid();
        List<CanvasRef> result = new ArrayList<>();
        for (Object raw : refs) {
            if (!(raw instanceof JSONObject)) invalid();
            JSONObject ref = (JSONObject) raw;
            if (!Set.of("kind", "canonicalRef", "contentRef", "access").containsAll(ref.keySet())
                    || !ref.keySet().containsAll(Set.of("kind", "canonicalRef", "access"))) invalid();
            if (!"FREE_CANVAS".equals(ref.getString("kind"))) continue;
            if (!Set.of("READ", "MUTATE").contains(ref.getString("access"))) invalid();
            JSONObject canonical = ref.getJSONObject("canonicalRef");
            if (canonical == null || !Set.of("projectId", "workspaceId", "objectId", "version")
                    .containsAll(canonical.keySet())) invalid();
            String project = requiredId(canonical, "projectId");
            String workspace = requiredId(canonical, "workspaceId");
            String object = canonical.getString("objectId");
            String version = canonical.getString("version");
            String contentRef = ref.getString("contentRef");
            if (object == null) {
                if (version == null || !Objects.equals(contentRef,
                        "canvas:workspace:" + workspace + ":" + version)) invalid();
            } else if (!object.matches("[A-Za-z0-9_-]{1,64}") || version == null
                    || !version.matches("[1-9][0-9]{0,18}") || !Objects.equals(contentRef,
                    "canvas:node:" + workspace + ":" + object + ":" + version)) invalid();
            result.add(new CanvasRef(project, workspace, object, version));
        }
        return List.copyOf(result);
    }

    private static AssistantTargetViews.TargetRef target(CanvasRef scope, String objectId, String version) {
        AssistantTargetViews.TargetRef value = new AssistantTargetViews.TargetRef();
        value.setKind("FREE_CANVAS");
        value.setProjectId(scope.projectId());
        value.setWorkspaceId(scope.workspaceId());
        value.setObjectId(objectId);
        value.setVersion(version);
        return value;
    }

    private static JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) invalid();
            return value;
        } catch (RuntimeException error) {
            invalid();
            return null;
        }
    }

    private static String requiredId(JSONObject value, String key) {
        String result = value == null ? null : value.getString(key);
        if (result == null || !result.matches("[1-9][0-9]{0,18}")) invalid();
        try { if (Long.parseLong(result) < 1) invalid(); }
        catch (NumberFormatException error) { invalid(); }
        return result;
    }

    private static String requiredObjectId(JSONObject value, String key) {
        String result = value == null ? null : value.getString(key);
        if (result == null || !result.matches("[A-Za-z0-9_-]{1,64}")) invalid();
        return result;
    }

    private static void invalid() {
        throw new ServiceException("Canvas effect receipt is invalid");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CanvasRef(String projectId, String workspaceId, String objectId, String version) {
    }
}
