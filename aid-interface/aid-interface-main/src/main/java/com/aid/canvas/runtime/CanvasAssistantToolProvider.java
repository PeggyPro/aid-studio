package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasProjectService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeContextRevisionUpdate;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CanvasAssistantToolProvider implements RuntimeToolProvider {
    public static final String NODE_READ = "canvas.node.read";
    public static final String NODE_PATCH = "canvas.node.patch";
    public static final String NODE_CREATE_TEXT = "canvas.node.create_text";
    public static final String NODE_CREATE_TEXT_FROM_RESULT = "canvas.node.create_text_from_result";
    public static final String NODE_CREATE_IMAGE = "canvas.node.create_image";
    public static final String NODE_CREATE_VIDEO = "canvas.node.create_video";
    public static final String NODE_CREATE_IMAGE_WITH_SETTINGS = "canvas.node.create_image_with_settings";
    public static final String NODE_CREATE_VIDEO_WITH_SETTINGS = "canvas.node.create_video_with_settings";
    public static final String NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS =
            "canvas.node.create_image_with_validated_settings";
    public static final String NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS =
            "canvas.node.create_video_with_validated_settings";
    public static final String NODE_CREATE_MUSIC = "canvas.node.create_music";
    public static final String NODE_CREATE_AUDIO = "canvas.node.create_audio";
    public static final String NODE_CREATE_RELATED = "canvas.node.create_related";
    public static final String CANDIDATES = "canvas.artifact.candidates";
    public static final String ADOPT = "canvas.artifact.adopt";
    private static final String VERSION = "1.0.0";
    private static final String ID = "{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]{0,18}$\"}";
    private static final String TARGET = "{\"type\":\"object\",\"properties\":{\"objectId\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_-]{1,64}$\"},\"objectVersion\":" + ID + "},\"required\":[\"objectId\",\"objectVersion\"],\"additionalProperties\":false}";
    private static final String TARGET_INPUT = "{\"type\":\"object\",\"properties\":{\"target\":" + TARGET + "},\"required\":[\"target\"],\"additionalProperties\":false}";
    private static final String ADOPT_INPUT = "{\"type\":\"object\",\"properties\":{\"target\":" + TARGET + ",\"resultId\":" + ID + "},\"required\":[\"target\",\"resultId\"],\"additionalProperties\":false}";
    private static final String PATCH_INPUT = "{\"type\":\"object\",\"properties\":{\"target\":" + TARGET + ",\"patch\":{\"type\":\"object\",\"minProperties\":1}},\"required\":[\"target\",\"patch\"],\"additionalProperties\":false}";
    private static final String CREATE_TEXT_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID + ",\"title\":{\"type\":\"string\",\"maxLength\":256},\"textContent\":{\"type\":\"string\",\"maxLength\":65536},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}},\"required\":[\"workspaceId\",\"title\",\"textContent\"],\"additionalProperties\":false}";
    private static final String CREATE_TEXT_FROM_RESULT_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID
            + ",\"title\":{\"type\":\"string\",\"maxLength\":256},\"sourceStepId\":" + ID
            + ",\"resultPointer\":{\"type\":\"string\",\"maxLength\":1024,\"description\":\"RFC 6901 pointer within the completed Skill business result. Use an empty string for a TEXT result; for JSON select an exact string field. Never point to internal metadata.\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}},\"required\":[\"workspaceId\",\"title\",\"sourceStepId\",\"resultPointer\"],\"additionalProperties\":false}";
    private static final String CREATE_MEDIA_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID + ",\"title\":{\"type\":\"string\",\"maxLength\":256},\"prompt\":{\"type\":\"string\",\"maxLength\":65536},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}},\"required\":[\"workspaceId\",\"title\",\"prompt\"],\"additionalProperties\":false}";
    private static final String CREATE_MEDIA_SETTINGS_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID + """
            ,"title":{"type":"string","maxLength":256},"prompt":{"type":"string","maxLength":65536},
            "modelCode":{"type":"string","minLength":1,"maxLength":100},
            "generationSettings":{"type":"object","properties":{
                "aspectRatio":{"type":"string","minLength":1,"maxLength":100},
                "quality":{"type":"string","minLength":1,"maxLength":100,"description":"Resolution/quality displayed by the selected model, e.g. 1k, 720p, 1080p"},
                "count":{"type":"integer","minimum":1,"maximum":100},
                "duration":{"type":"number","exclusiveMinimum":0,"maximum":600},
                "audioEnabled":{"type":"boolean"},
                "videoMode":{"type":"string","minLength":1,"maxLength":100},
                "capabilityCode":{"type":"string","minLength":1,"maxLength":100}
            },"additionalProperties":false},
            "x":{"type":"number"},"y":{"type":"number"}},"required":["workspaceId","title","prompt"],"additionalProperties":false}
            """;
    private static final String CREATE_IMAGE_VALIDATED_SETTINGS_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID + """
            ,"title":{"type":"string","maxLength":256},"prompt":{"type":"string","maxLength":65536},
            "modelCode":{"type":"string","minLength":1,"maxLength":100},
            "generationSettings":{"type":"object","properties":{
                "aspectRatio":{"type":"string","minLength":1,"maxLength":100,"description":"Exact image aspect ratio returned by the selected model DETAIL, or display ratio matching an explicit pixel size"},
                "quality":{"type":"string","minLength":1,"maxLength":100,"description":"Exact model size choice returned by DETAIL, for example 1024x1536; a billing tier such as 1K is valid only when DETAIL itself declares that exact size choice"},
                "count":{"type":"integer","minimum":1,"maximum":100},
                "capabilityCode":{"type":"string","minLength":1,"maxLength":100}
            },"additionalProperties":false},
            "x":{"type":"number"},"y":{"type":"number"}},"required":["workspaceId","title","prompt"],"additionalProperties":false}
            """;
    private static final String CREATE_VIDEO_VALIDATED_SETTINGS_INPUT = "{\"type\":\"object\",\"properties\":{\"workspaceId\":" + ID + """
            ,"title":{"type":"string","maxLength":256},"prompt":{"type":"string","maxLength":65536},
            "modelCode":{"type":"string","minLength":1,"maxLength":100},
            "generationSettings":{"type":"object","properties":{
                "aspectRatio":{"type":"string","minLength":1,"maxLength":100,"description":"Exact aspect ratio choice returned by the selected model DETAIL"},
                "quality":{"type":"string","minLength":1,"maxLength":100,"description":"Exact video resolution choice returned by the selected model DETAIL"},
                "duration":{"type":"integer","minimum":1,"maximum":600},
                "audioEnabled":{"type":"boolean"},
                "videoMode":{"type":"string","enum":["imageToVideo","multiParam","gridVideo"]},
                "capabilityCode":{"type":"string","minLength":1,"maxLength":100}
            },"additionalProperties":false},
            "x":{"type":"number"},"y":{"type":"number"}},"required":["workspaceId","title","prompt"],"additionalProperties":false}
            """;
    private static final String NODE_OUTPUT = "{\"type\":\"object\",\"properties\":{\"artifactRef\":{\"type\":\"string\"},\"objectId\":{\"type\":\"string\"},\"objectVersion\":" + ID + ",\"content\":{}},\"required\":[\"artifactRef\",\"objectId\",\"objectVersion\",\"content\"],\"additionalProperties\":false}";
    private static final String CANDIDATE = "{\"type\":\"object\",\"properties\":{\"artifactRef\":{\"type\":\"string\"},\"resultId\":" + ID + ",\"taskId\":" + ID + ",\"mediaType\":{\"type\":\"string\"},\"mimeType\":{\"type\":\"string\"},\"resourceId\":{\"type\":[\"string\",\"null\"]},\"inputObjectVersion\":" + ID + "},\"required\":[\"artifactRef\",\"resultId\",\"taskId\",\"mediaType\",\"mimeType\",\"resourceId\",\"inputObjectVersion\"],\"additionalProperties\":false}";
    private static final String CANDIDATES_OUTPUT = "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"items\":" + CANDIDATE + "}},\"required\":[\"items\"],\"additionalProperties\":false}";
    private static final String ADOPT_OUTPUT = "{\"type\":\"object\",\"properties\":{\"artifactRef\":{\"type\":\"string\"},\"objectId\":{\"type\":\"string\"},\"objectVersion\":" + ID + ",\"operationId\":{\"type\":\"string\"}},\"required\":[\"artifactRef\",\"objectId\",\"objectVersion\",\"operationId\"],\"additionalProperties\":true}";
    private static final String MUTATE_OUTPUT = "{\"type\":\"object\",\"properties\":{\"artifactRef\":{\"type\":\"string\"},\"objectId\":{\"type\":\"string\"},\"objectVersion\":" + ID + ",\"receiptId\":{\"type\":\"string\"}},\"required\":[\"artifactRef\",\"objectId\",\"objectVersion\",\"receiptId\"],\"additionalProperties\":true}";

    private final RuntimeScopeAccess scopeAccess;
    private final CanvasArtifactCandidateService candidateService;
    private final CanvasMapper canvasMapper;
    private final CanvasProjectService projectService;
    private final CanvasAssistantGenerationSettingsResolver generationSettingsResolver;
    @Resource private CanvasSkillResultTextPreparation resultTextPreparation;
    @Resource private CanvasAssistantReferenceInputs referenceInputs;

    private static String relatedInputSchema() {
        var alternatives = new com.alibaba.fastjson2.JSONArray();
        for (String kind : List.of("TEXT", "IMAGE", "VIDEO", "MUSIC", "AUDIO")) {
            String original = switch (kind) {
                case "TEXT" -> CREATE_TEXT_INPUT;
                case "IMAGE" -> CREATE_IMAGE_VALIDATED_SETTINGS_INPUT;
                case "VIDEO" -> CREATE_VIDEO_VALIDATED_SETTINGS_INPUT;
                default -> CREATE_MEDIA_INPUT;
            };
            JSONObject branch = JSON.parseObject(original);
            var fields = branch.getJSONObject("properties");
            fields.put("nodeKind", JSONObject.of("type", "string", "enum", com.alibaba.fastjson2.JSONArray.of(kind)));
            JSONObject reference = JSONObject.of("type", "object", "properties", JSONObject.of(
                    "objectId", JSONObject.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$"),
                    "kind", JSONObject.of("type", "string", "enum", com.alibaba.fastjson2.JSONArray.of("reference")),
                    "role", JSONObject.of("type", "string", "enum", com.alibaba.fastjson2.JSONArray.of("reference", "first_frame", "last_frame", "text", "audio"))),
                    "required", com.alibaba.fastjson2.JSONArray.of("objectId", "kind", "role"), "additionalProperties", false);
            JSONObject dependency = JSONObject.of("type", "object", "properties", JSONObject.of(
                    "objectId", JSONObject.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$"),
                    "kind", JSONObject.of("type", "string", "enum", com.alibaba.fastjson2.JSONArray.of("sequence", "flow"))),
                    "required", com.alibaba.fastjson2.JSONArray.of("objectId", "kind"), "additionalProperties", false);
            fields.put("sources", JSONObject.of("type", "array", "minItems", 1, "maxItems", 100,
                    "items", JSONObject.of("type", "object", "oneOf", com.alibaba.fastjson2.JSONArray.of(reference, dependency))));
            branch.getJSONArray("required").addAll(List.of("nodeKind", "sources"));
            alternatives.add(branch);
        }
        return JSONObject.of("type", "object", "oneOf", alternatives).toJSONString();
    }

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(NODE_CREATE_RELATED, "Create related canvas node",
                        "Create a node and all source edges in one transaction. sources chooses already observed nodes; host binds their exact versions. For reference edges specify role; sequence/flow edges have no role. Source array order is explicit input order. TEXT requires textContent; media requires prompt. IMAGE/VIDEO may carry modelCode and generationSettings only as published for the verified model capability; first read that model DETAIL. Omit undeclared settings, including false-valued flags. This only creates nodes and grants no generation authority.",
                        relatedInputSchema(), MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_READ, "Read canvas node", "Read the input configuration of the exact node version bound to this run. generationReferences is a host-derived view of incoming generation links, not a persisted node field. For linked inputs the host resolves selected source resources and roles during quotation; do not infer missing reference media from an empty target resourceIds array, and do not query target output candidates merely to reconstruct its source links. To inspect the target's own generated outputs use canvas.artifact.candidates.",
                        TARGET_INPUT, NODE_OUTPUT, "READ", "REQUEST_DIGEST", "REPLAY_SAFE",
                        List.of("CANVAS_READ")),
                descriptor(NODE_PATCH, "Patch canvas node",
                        "Merge a validated patch into the exact node revision bound to this run.",
                        PATCH_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY", "RECONCILE_OPERATION_LOG",
                        List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_TEXT, "Create canvas text node",
                        "Create one validated text node in the bound Canvas workspace.",
                        CREATE_TEXT_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_TEXT_FROM_RESULT, "Save exact Skill result text",
                        "Create one text node by copying a completed Skill result string exactly, without retyping or changing quotes, whitespace, line breaks or code. Use only when the user wants the source saved unchanged. sourceStepId is the successful SKILL runtime step, not its child run ID. Earlier successful runs in this user conversation are valid sources; no SKILL execution step is required in the current plan. Use aid.skill.result.references to discover the existing sourceStepId and resultPointer, never rerun a Skill merely to manufacture a new source ID. For requested edits use the ordinary text writer instead.",
                        CREATE_TEXT_FROM_RESULT_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_IMAGE, "Create canvas image node",
                        "Create one empty image generation node in the bound Canvas workspace.",
                        CREATE_MEDIA_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_VIDEO, "Create canvas video node",
                        "Create one empty video generation node in the bound Canvas workspace.",
                        CREATE_MEDIA_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_IMAGE_WITH_SETTINGS, "Create canvas image node with settings",
                        "Create one empty image node. Keep prompt limited to visual content and creative instructions; never append model codes, resolution, aspect ratio, count, prices or a parameter-summary block to prompt. Save the selected catalog modelCode and generationSettings in their dedicated fields after reading model DETAIL, using only the fields and values published for that model capability, so the node controls restore them before approval. These settings grant no generation authority.",
                        CREATE_MEDIA_SETTINGS_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_VIDEO_WITH_SETTINGS, "Create canvas video node with settings",
                        "Create one empty video node. Keep prompt limited to visual content, action, camera and creative instructions; never append model codes, resolution, aspect ratio, duration, count, prices or a parameter-summary block to prompt. Save the selected catalog modelCode and generationSettings in their dedicated fields after reading model DETAIL, using only the fields and values published for that model capability; omit undeclared flags even when false, so the node controls restore them before approval. These settings grant no generation authority.",
                        CREATE_MEDIA_SETTINGS_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS,
                        "Create canvas image node with validated settings",
                        "Create one empty image node after validating the exact selected model capability. Use aid.model.query LIST then DETAIL and pass only its exact modelCode, capabilityCode, size, aspect ratio and count choices. A billing tier such as 1K is not a size unless DETAIL itself declares that exact size choice. These settings grant no generation authority.",
                        CREATE_IMAGE_VALIDATED_SETTINGS_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE",
                        "IDEMPOTENCY_KEY", "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
                        "Create canvas video node with validated settings",
                        "Create one empty video node after validating the exact selected model capability. Use aid.model.query LIST then DETAIL and pass only its exact modelCode, capabilityCode, resolution, aspect ratio, integral duration and audio choices. videoMode is a Canvas layout mode, not a Provider parameter. These settings grant no generation authority.",
                        CREATE_VIDEO_VALIDATED_SETTINGS_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE",
                        "IDEMPOTENCY_KEY", "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_MUSIC, "Create canvas music node",
                        "Create one empty music generation node using the Canvas audio node contract.",
                        CREATE_MEDIA_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(NODE_CREATE_AUDIO, "Create canvas audio node",
                        "Create one empty audio generation node using the Canvas audio node contract.",
                        CREATE_MEDIA_INPUT, MUTATE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY",
                        "RECONCILE_OPERATION_LOG", List.of("CANVAS_WRITE")),
                descriptor(CANDIDATES, "List canvas artifact candidates",
                        "List successful persisted media from this bound node, including earlier input revisions. The target must match the current bound node version. Returns resultId, taskId, resourceId and inputObjectVersion; an earlier input version does not mean the result is unavailable.",
                        TARGET_INPUT, CANDIDATES_OUTPUT, "READ", "REQUEST_DIGEST", "REPLAY_SAFE",
                        List.of("CANVAS_READ")),
                descriptor(ADOPT, "Adopt canvas artifact candidate",
                        "Select one successful persisted result from this same node, including an earlier input revision, and advance the current bound node with version CAS. Preserve result history; never regenerate to select an existing result.",
                        ADOPT_INPUT, ADOPT_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY", "RECONCILE_OPERATION_LOG",
                        List.of("CANVAS_WRITE")));
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return CANDIDATES.equals(descriptor.code()) ? OutputReferenceKind.OBSERVATION
                : OutputReferenceKind.TARGET_REFERENCE;
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null || context.getUserId() == null || context.getWorkspaceId() == null
                || descriptor == null) {
            return RuntimeToolAvailability.unavailable("CANVAS_SCOPE_MISSING");
        }
        CanvasAssistantContextManifest.AvailableRefs refs;
        try {
            refs = CanvasAssistantContextManifest.availableRefs(context.getContextManifestJson(),
                    context.getProjectId(), context.getWorkspaceId());
        } catch (ServiceException invalid) {
            return RuntimeToolAvailability.unavailable("CANVAS_SCOPE_INVALID");
        }
        String code = descriptor.code();
        if (context.isBudgetDiscovery() && refs.mutableWorkspace()
                && Set.of(NODE_READ, NODE_PATCH, CANDIDATES).contains(code)) {
            // Offering the Tool does not grant existing node access. Actual invocation still needs
            // an exact originally quoted target or a receipt-proven node created by this same run.
            return RuntimeToolAvailability.enabled();
        }
        if (Set.of(NODE_CREATE_RELATED, NODE_CREATE_TEXT, NODE_CREATE_TEXT_FROM_RESULT, NODE_CREATE_IMAGE, NODE_CREATE_VIDEO,
                NODE_CREATE_IMAGE_WITH_SETTINGS, NODE_CREATE_VIDEO_WITH_SETTINGS,
                NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS, NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS,
                NODE_CREATE_MUSIC, NODE_CREATE_AUDIO).contains(code)) {
            return refs.mutableWorkspace()
                    ? RuntimeToolAvailability.enabled()
                    : RuntimeToolAvailability.unavailable("CANVAS_WORKSPACE_READ_ONLY");
        }
        if (Set.of(NODE_PATCH, ADOPT).contains(code)) {
            return refs.mutableTargets() > 0
                    ? RuntimeToolAvailability.enabled()
                    : RuntimeToolAvailability.unavailable("CANVAS_TARGET_WRITE_MISSING");
        }
        if (Set.of(NODE_READ, CANDIDATES).contains(code)) {
            return refs.readableTargets() > 0
                    ? RuntimeToolAvailability.enabled()
                    : RuntimeToolAvailability.unavailable("CANVAS_TARGET_READ_MISSING");
        }
        return RuntimeToolAvailability.unavailable("CANVAS_TOOL_UNREGISTERED");
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        RuntimeToolRegistry.Descriptor descriptor = descriptors().stream()
                .filter(candidate -> candidate.code().equals(invocation.getToolCode())).findFirst().orElse(null);
        return executeGuarded(invocation, null, descriptor);
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (invocation != null && NODE_CREATE_TEXT_FROM_RESULT.equals(invocation.getToolCode())) {
            return new RuntimeToolPreparation(resultTextPreparation.prepare(invocation), null,
                    invocation.getOperationScope());
        }
        if (invocation == null || !Set.of(NODE_PATCH, NODE_CREATE_RELATED,
                NODE_CREATE_IMAGE_WITH_SETTINGS, NODE_CREATE_VIDEO_WITH_SETTINGS,
                NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS, NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS)
                .contains(invocation.getToolCode())) {
            return RuntimeToolProvider.super.prepare(invocation);
        }
        JSONObject arguments = arguments(invocation.getArgumentsJson());
        if (NODE_CREATE_RELATED.equals(invocation.getToolCode())) {
            try { relatedNodeSpec(invocation, arguments); }
            catch (ServiceException rejected) {
                throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                        com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason
                                .TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID, rejected);
            }
        }
        JSONObject prepared = generationSettingsResolver.prepare(invocation, arguments);
        return new RuntimeToolPreparation(CanvasAssistantOperationCanonicalizer.explicitNullJson(prepared),
                null, invocation.getOperationScope());
    }

    @Override
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext) {
        if (billingBudgetContext != null) throw failure("CANVAS_BILLING_CONTEXT_FORBIDDEN");
        RuntimeToolInvocation invocation = invocation(prepared);
        if (NODE_CREATE_TEXT_FROM_RESULT.equals(prepared.getToolCode())) {
            return createTextNode(invocation, CanvasSkillResultTextPreparation.nodeArguments(
                    prepared.getPreparedPayloadJson(), prepared.getArgumentsJson()), submissionClaim(prepared));
        }
        return executeGuarded(invocation, submissionClaim(prepared), prepared.getDescriptor());
    }

    private RuntimeToolResult executeGuarded(RuntimeToolInvocation invocation,
                                             RuntimeOperationSubmissionClaim claim,
                                             RuntimeToolRegistry.Descriptor descriptor) {
        try {
            return executeInternal(invocation, claim);
        } catch (ServiceException rejected) {
            RuntimeToolResult failure = readPreconditionFailure(descriptor, rejected);
            if (failure == null) throw rejected;
            return failure;
        }
    }

    static RuntimeToolResult readPreconditionFailure(RuntimeToolRegistry.Descriptor descriptor,
                                                     ServiceException rejected) {
        if (descriptor == null || !"READ".equals(descriptor.sideEffectLevel()) || descriptor.billable()
                || !"REPLAY_SAFE".equals(descriptor.recoveryPolicy()) || rejected == null
                || rejected.getDetailMessage() == null
                || (!(rejected.getCause() instanceof CanvasAssistantContextManifest.Rejected
                        && Objects.equals(rejected.getDetailMessage(), rejected.getCause().getMessage()))
                && !Set.of("RUNTIME_LEASE_INVALID", "RUNTIME_SCOPE_FENCED", "CANVAS_MANIFEST_INVALID",
                "CANVAS_PROJECT_SCOPE_CHANGED", "CANVAS_WORKSPACE_SCOPE_CHANGED", "CANVAS_SCOPE_INCOMPLETE",
                "CANVAS_TARGET_INVALID", "CANVAS_REVISION_CHANGED", "CANVAS_REVISION_STALE")
                .contains(rejected.getDetailMessage()))) return null;
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED");
        result.setErrorCode(rejected.getDetailMessage());
        result.setSafeSummary("当前读取的运行授权或节点版本已变化，请更新引用后继续；已有内容和结果保留。");
        result.setRetryable(false);
        return result;
    }

    @Override
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        RuntimeToolInvocation invocation = invocation(prepared);
        JSONObject arguments = NODE_CREATE_TEXT_FROM_RESULT.equals(prepared.getToolCode())
                ? CanvasSkillResultTextPreparation.nodeArguments(prepared.getPreparedPayloadJson(), prepared.getArgumentsJson())
                : arguments(invocation.getArgumentsJson());
        return switch (prepared.getToolCode()) {
            case NODE_CREATE_RELATED -> relatedNode(invocation, arguments, null, true);
            case NODE_PATCH -> reconcilePatch(invocation, arguments);
            case NODE_CREATE_TEXT, NODE_CREATE_TEXT_FROM_RESULT -> reconcileCreate(invocation, arguments, "CREATE_TEXT", "TEXT", "text",
                    null, 360, 260, "\u5df2\u521b\u5efa\u6587\u672c\u8282\u70b9");
            case NODE_CREATE_IMAGE, NODE_CREATE_IMAGE_WITH_SETTINGS,
                 NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS -> reconcileCreate(invocation, arguments, "CREATE_IMAGE", "IMAGE", "image",
                    "image", 480, 270, "\u5df2\u521b\u5efa\u56fe\u7247\u8282\u70b9");
            case NODE_CREATE_VIDEO, NODE_CREATE_VIDEO_WITH_SETTINGS,
                 NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS -> reconcileCreate(invocation, arguments, "CREATE_VIDEO", "VIDEO", "video",
                    "video", 480, 270, "\u5df2\u521b\u5efa\u89c6\u9891\u8282\u70b9");
            case NODE_CREATE_MUSIC -> reconcileCreate(invocation, arguments, "CREATE_MUSIC", "VOICE", "voice",
                    "audio", 320, 180, "\u5df2\u521b\u5efa\u97f3\u4e50\u8282\u70b9");
            case NODE_CREATE_AUDIO -> reconcileCreate(invocation, arguments, "CREATE_AUDIO", "VOICE", "voice",
                    "audio", 320, 180, "\u5df2\u521b\u5efa\u97f3\u9891\u8282\u70b9");
            case ADOPT -> reconcileAdoption(invocation, arguments);
            default -> RuntimeToolResult.unknown("TOOL_RECONCILIATION_UNSUPPORTED",
                    "\u8be5Tool\u65e0\u6301\u4e45\u56de\u6267");
        };
    }

    private RuntimeToolResult executeInternal(RuntimeToolInvocation invocation,
                                              RuntimeOperationSubmissionClaim submissionClaim) {
        JSONObject arguments = arguments(invocation.getArgumentsJson());
        return switch (invocation.getToolCode()) {
            case NODE_CREATE_RELATED -> relatedNode(invocation, arguments, submissionClaim, false);
            case NODE_READ -> readNode(authorizeExact(invocation, arguments, false), arguments);
            case NODE_PATCH -> patchNode(invocation, arguments, submissionClaim);
            case NODE_CREATE_TEXT -> createTextNode(invocation, arguments, submissionClaim);
            case NODE_CREATE_IMAGE, NODE_CREATE_IMAGE_WITH_SETTINGS,
                 NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS -> createMediaNode(invocation, arguments, submissionClaim,
                    "CREATE_IMAGE", "IMAGE", "image",
                    "image", 480, 270, "\u5df2\u521b\u5efa\u56fe\u7247\u8282\u70b9");
            case NODE_CREATE_VIDEO, NODE_CREATE_VIDEO_WITH_SETTINGS,
                 NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS -> createMediaNode(invocation, arguments, submissionClaim,
                    "CREATE_VIDEO", "VIDEO", "video",
                    "video", 480, 270, "\u5df2\u521b\u5efa\u89c6\u9891\u8282\u70b9");
            case NODE_CREATE_MUSIC -> createMediaNode(invocation, arguments, submissionClaim,
                    "CREATE_MUSIC", "VOICE", "voice",
                    "audio", 320, 180, "\u5df2\u521b\u5efa\u97f3\u4e50\u8282\u70b9");
            case NODE_CREATE_AUDIO -> createMediaNode(invocation, arguments, submissionClaim,
                    "CREATE_AUDIO", "VOICE", "voice",
                    "audio", 320, 180, "\u5df2\u521b\u5efa\u97f3\u9891\u8282\u70b9");
            case CANDIDATES -> listCandidates(authorizeExact(invocation, arguments, false), arguments);
            case ADOPT -> adopt(invocation, arguments, submissionClaim);
            default -> throw failure("CANVAS_TOOL_UNREGISTERED");
        };
    }

    private RuntimeToolResult readNode(RuntimeScopeAccess.Scope scope, JSONObject arguments) {
        rejectUnknown(arguments, Set.of("target"));
        CanvasObject node = canvasMapper.selectCanvasObject(scope.workspaceId(), scope.objectId());
        if (node == null || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), scope.objectVersion())) {
            throw failure("CANVAS_REVISION_STALE");
        }
        Object content;
        try {
            content = JSON.parse(node.getContentJson());
        } catch (RuntimeException invalid) {
            throw failure("CANVAS_CONTENT_INVALID");
        }
        if (content instanceof JSONObject nodeView) {
            var references = referenceInputs.describeView(scope.projectId(), scope.workspaceId(), scope.objectId());
            if (references.getIntValue("totalCount") > 0) nodeView.put("generationReferences", references);
        }
        String artifactRef = nodeArtifactRef(scope);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifactRef", artifactRef);
        output.put("objectId", scope.objectId());
        output.put("objectVersion", scope.objectVersion().toString());
        output.put("content", content);
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(output));
        result.setOutputArtifactRefs(List.of(artifactRef));
        result.setSafeSummary("\u5df2\u8bfb\u53d6\u753b\u5e03\u8282\u70b9");
        return result;
    }

    private RuntimeToolResult listCandidates(RuntimeScopeAccess.Scope scope, JSONObject arguments) {
        rejectUnknown(arguments, Set.of("target"));
        List<CanvasArtifactCandidateService.Candidate> items = candidateService.list(scope);
        List<Map<String, Object>> outputItems = items.stream().map(CanvasAssistantToolProvider::candidateOutput).toList();
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(Map.of("items", outputItems),
                com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        result.setSafeSummary("\u5df2\u8bfb\u53d6" + items.size() + "\u4e2a\u5019\u9009\u9879");
        return result;
    }

    private RuntimeToolResult adopt(RuntimeToolInvocation invocation, JSONObject arguments,
                                    RuntimeOperationSubmissionClaim submissionClaim) {
        requireSubmissionClaim(submissionClaim);
        rejectUnknown(arguments, Set.of("target", "resultId"));
        Long resultId = positiveId(arguments.getString("resultId"), "CANVAS_RESULT_ID_INVALID");
        RuntimeScopeAccess.Lease lease = lease(invocation.getLease());
        CanvasAssistantContextManifest.TargetRef target = manifestTarget(invocation, arguments, true);
        RuntimeScopeAccess.Scope scope = reserveTarget(invocation, lease, target);
        CanvasArtifactCandidateService.Adoption adoption;
        try {
            adoption = candidateService.adopt(scope, resultId, invocation.getIdempotencyKey(), submissionClaim);
        } finally {
            scopeAccess.release(scope);
        }
        return adoptionResult(adoption, scope);
    }

    private RuntimeToolResult patchNode(RuntimeToolInvocation invocation, JSONObject arguments,
                                        RuntimeOperationSubmissionClaim submissionClaim) {
        requireSubmissionClaim(submissionClaim);
        rejectUnknown(arguments, Set.of("target", "patch"));
        JSONObject patch = arguments.getJSONObject("patch");
        if (patch == null || patch.isEmpty()) throw failure("CANVAS_PATCH_EMPTY");
        RuntimeScopeAccess.Lease lease = lease(invocation.getLease());
        CanvasAssistantContextManifest.TargetRef target = manifestTarget(invocation, arguments, true);
        RuntimeScopeAccess.Scope scope = reserveTarget(invocation, lease, target);
        String operationId = operationId("asst_patch", scope.runId(), invocation.getIdempotencyKey());
        CanvasProjectService.AssistantNodeMutation mutation = mutation(scope, scope.nodeFence(), operationId,
                scope.objectId(), scope.objectVersion(),
                "PATCH", patch, submissionClaim);
        Map<String, Object> output;
        try {
            output = projectService.mutateAssistantNode(scope.workspaceId(), scope.userId(), mutation);
        } finally {
            scopeAccess.release(scope);
        }
        return mutationResult(output, scope.workspaceId(), scope.objectId(), scope.objectVersion(),
                "\u5df2\u66f4\u65b0\u753b\u5e03\u8282\u70b9");
    }

    private RuntimeToolResult createTextNode(RuntimeToolInvocation invocation, JSONObject arguments,
                                             RuntimeOperationSubmissionClaim submissionClaim) {
        requireSubmissionClaim(submissionClaim);
        rejectUnknown(arguments, Set.of("workspaceId", "title", "textContent", "x", "y"));
        String title = arguments.getString("title");
        String textContent = arguments.getString("textContent");
        if (title == null || title.length() > 256 || textContent == null || textContent.length() > 65_536) {
            throw failure("CANVAS_TEXT_INVALID");
        }
        RuntimeScopeAccess.Lease lease = lease(invocation.getLease());
        CanvasAssistantContextManifest.WorkspaceRef ref = CanvasAssistantContextManifest.requireWorkspace(
                invocation, arguments.getString("workspaceId"), true);
        RuntimeScopeAccess.WorkspaceScope authorized = scopeAccess.authorizeWorkspace(invocation.getRunId(),
                invocation.getUserId(), lease, ref.projectId(), ref.workspaceId());
        String operationId = operationId("asst_create_text", authorized.runId(), invocation.getIdempotencyKey());
        String objectId = createObjectId(invocation);
        Map<String, Object> payload = textPayload(arguments);
        CanvasProjectService.AssistantNodeMutation mutation = mutation(authorized, ref.workspaceRevision(), operationId,
                objectId, 0L, "CREATE_TEXT", payload, submissionClaim);
        Map<String, Object> output = projectService.mutateAssistantNode(authorized.workspaceId(),
                authorized.userId(), mutation);
        return mutationResult(output, authorized.workspaceId(), objectId, null,
                "\u5df2\u521b\u5efa\u6587\u672c\u8282\u70b9");
    }

    private record RelatedNodeSpec(CanvasAssistantRelationContract.NodeDefinition definition,
                                   CanvasAssistantContextManifest.WorkspaceRef workspace,
                                   List<CanvasAssistantRelationContract.Source> sources,
                                   Map<String, Object> payload) { }

    private static RelatedNodeSpec relatedNodeSpec(RuntimeToolInvocation invocation, JSONObject arguments) {
        rejectUnknown(arguments, Set.of("workspaceId", "title", "nodeKind", "sources", "textContent",
                "prompt", "modelCode", "generationSettings", "x", "y"));
        var definition = CanvasAssistantRelationContract.nodeDefinition(arguments.getString("nodeKind"));
        var ref = CanvasAssistantContextManifest.requireWorkspace(invocation, arguments.getString("workspaceId"), true);
        var sources = CanvasAssistantRelationContract.bind(arguments.getJSONArray("sources"),
                invocation.getContextManifestJson(), ref.projectId(), ref.workspaceId());
        return new RelatedNodeSpec(definition, ref, sources, CanvasAssistantRelationContract.nodePayload(arguments));
    }

    private RuntimeToolResult relatedNode(RuntimeToolInvocation invocation, JSONObject arguments,
                                          RuntimeOperationSubmissionClaim claim, boolean reconcile) {
        if (!reconcile) requireSubmissionClaim(claim);
        RelatedNodeSpec spec;
        try { spec = relatedNodeSpec(invocation, arguments); }
        catch (ServiceException rejected) {
            // This immutable input/manifest check runs before any mutation or receipt lookup.
            RuntimeToolResult result = new RuntimeToolResult();
            result.setStatus("FAILED");
            result.setErrorCode(rejected.getDetailMessage() == null ? "CANVAS_RELATED_NODE_PAYLOAD_INVALID" : rejected.getDetailMessage());
            result.setSafeSummary("节点来源或创建参数不符合当前授权，请重新引用后继续；尚未创建节点。");
            result.setSubmissionNotEntered(true);
            result.setRetryable(false);
            return result;
        }
        var definition = spec.definition();
        var ref = spec.workspace();
        var sources = spec.sources();
        var scope = reconcile ? receiptWorkspace(invocation, ref)
                : scopeAccess.authorizeWorkspace(invocation.getRunId(), invocation.getUserId(),
                lease(invocation.getLease()), ref.projectId(), ref.workspaceId());
        String objectId = createObjectId(invocation);
        String operationId = operationId("asst_related", scope.runId(), invocation.getIdempotencyKey());
        Map<String, Object> payload = spec.payload();
        var base = mutation(scope, ref.workspaceRevision(), operationId, objectId, 0L, definition.action(), payload, claim);
        var complete = new CanvasProjectService.AssistantNodeMutation(base.runId(), base.runtimeLeaseOwner(),
                base.runtimeLeaseUntil(), base.runtimeFencingToken(), base.nodeFence(), base.sourceObjectId(),
                base.sourceObjectVersion(), base.expectedWorkspaceRevision(), base.operationId(), base.targetObjectId(),
                base.expectedVersion(), base.action(), base.payload(), base.submissionClaim(), sources);
        Map<String, Object> output = reconcile
                ? projectService.reconcileAssistantNodeMutation(scope.workspaceId(), scope.userId(), complete)
                : projectService.mutateAssistantNode(scope.workspaceId(), scope.userId(), complete);
        return output == null ? receiptMissing() : mutationResult(output, scope.workspaceId(), objectId, null,
                "已创建节点及来源连线");
    }

    private RuntimeToolResult createMediaNode(RuntimeToolInvocation invocation, JSONObject arguments,
                                              RuntimeOperationSubmissionClaim submissionClaim,
                                              String action, String nodeType, String kind, String mediaKind,
                                              int width, int height, String summary) {
        requireSubmissionClaim(submissionClaim);
        rejectUnknown(arguments, mediaCreateFields(invocation));
        String title = arguments.getString("title");
        String prompt = arguments.getString("prompt");
        if (title == null || title.length() > 256 || prompt == null || prompt.length() > 65_536) {
            throw failure("CANVAS_MEDIA_NODE_INVALID");
        }
        RuntimeScopeAccess.Lease lease = lease(invocation.getLease());
        CanvasAssistantContextManifest.WorkspaceRef ref = CanvasAssistantContextManifest.requireWorkspace(
                invocation, arguments.getString("workspaceId"), true);
        RuntimeScopeAccess.WorkspaceScope authorized = scopeAccess.authorizeWorkspace(invocation.getRunId(),
                invocation.getUserId(), lease, ref.projectId(), ref.workspaceId());
        String operationId = operationId("asst_" + action.toLowerCase(), authorized.runId(),
                invocation.getIdempotencyKey());
        String objectId = createObjectId(invocation);
        Map<String, Object> payload = mediaPayload(arguments, nodeType, kind, mediaKind, width, height);
        CanvasProjectService.AssistantNodeMutation mutation = mutation(authorized, ref.workspaceRevision(), operationId,
                objectId, 0L, action, payload, submissionClaim);
        Map<String, Object> output = projectService.mutateAssistantNode(authorized.workspaceId(),
                authorized.userId(), mutation);
        return mutationResult(output, authorized.workspaceId(), objectId, null, summary);
    }

    private RuntimeToolResult reconcilePatch(RuntimeToolInvocation invocation, JSONObject arguments) {
        rejectUnknown(arguments, Set.of("target", "patch"));
        JSONObject patch = arguments.getJSONObject("patch");
        if (patch == null || patch.isEmpty()) throw failure("CANVAS_PATCH_EMPTY");
        CanvasAssistantContextManifest.TargetRef target = manifestTarget(invocation, arguments, true);
        RuntimeScopeAccess.Scope scope = receiptScope(invocation, target);
        String operationId = operationId("asst_patch", scope.runId(), invocation.getIdempotencyKey());
        CanvasProjectService.AssistantNodeMutation mutation = mutation(scope, null, operationId,
                scope.objectId(), scope.objectVersion(), "PATCH", patch, null);
        Map<String, Object> output = projectService.reconcileAssistantNodeMutation(scope.workspaceId(),
                scope.userId(), mutation);
        return output == null ? receiptMissing() : mutationResult(output, scope.workspaceId(), scope.objectId(),
                scope.objectVersion(), "\u5df2\u66f4\u65b0\u753b\u5e03\u8282\u70b9");
    }

    private RuntimeToolResult reconcileCreate(RuntimeToolInvocation invocation, JSONObject arguments,
                                              String action, String nodeType, String kind, String mediaKind,
                                              int width, int height, String summary) {
        boolean text = "CREATE_TEXT".equals(action);
        rejectUnknown(arguments, text
                ? Set.of("workspaceId", "title", "textContent", "x", "y")
                : mediaCreateFields(invocation));
        CanvasAssistantContextManifest.WorkspaceRef ref = CanvasAssistantContextManifest.requireWorkspace(
                invocation, arguments.getString("workspaceId"), true);
        RuntimeScopeAccess.WorkspaceScope scope = receiptWorkspace(invocation, ref);
        String operationId = operationId("asst_" + action.toLowerCase(), scope.runId(),
                invocation.getIdempotencyKey());
        String objectId = createObjectId(invocation);
        Map<String, Object> payload = text ? textPayload(arguments)
                : mediaPayload(arguments, nodeType, kind, mediaKind, width, height);
        CanvasProjectService.AssistantNodeMutation mutation = mutation(scope, ref.workspaceRevision(), operationId,
                objectId, 0L, action, payload, null);
        Map<String, Object> output = projectService.reconcileAssistantNodeMutation(scope.workspaceId(),
                scope.userId(), mutation);
        return output == null ? receiptMissing()
                : mutationResult(output, scope.workspaceId(), objectId, null, summary);
    }

    private RuntimeToolResult reconcileAdoption(RuntimeToolInvocation invocation, JSONObject arguments) {
        rejectUnknown(arguments, Set.of("target", "resultId"));
        Long resultId = positiveId(arguments.getString("resultId"), "CANVAS_RESULT_ID_INVALID");
        CanvasAssistantContextManifest.TargetRef target = manifestTarget(invocation, arguments, true);
        RuntimeScopeAccess.Scope scope = receiptScope(invocation, target);
        CanvasArtifactCandidateService.Adoption adoption = candidateService.replay(scope, resultId,
                invocation.getIdempotencyKey());
        return adoption == null ? receiptMissing() : adoptionResult(adoption, scope);
    }

    private static Set<String> mediaCreateFields(RuntimeToolInvocation invocation) {
        return Set.of(NODE_CREATE_IMAGE_WITH_SETTINGS, NODE_CREATE_VIDEO_WITH_SETTINGS,
                NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS, NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS)
                .contains(invocation.getToolCode())
                ? Set.of("workspaceId", "title", "prompt", "modelCode", "generationSettings", "x", "y")
                : Set.of("workspaceId", "title", "prompt", "x", "y");
    }

    private static Map<String, Object> textPayload(JSONObject arguments) {
        return CanvasAssistantOperationCanonicalizer.textNodePayload(arguments);
    }

    private static Map<String, Object> mediaPayload(JSONObject arguments, String nodeType, String kind,
                                                    String mediaKind, int width, int height) {
        return CanvasAssistantOperationCanonicalizer.mediaNodePayload(
                arguments, nodeType, kind, mediaKind, width, height);
    }

    private static String createObjectId(RuntimeToolInvocation invocation) {
        return CanvasAssistantOperationCanonicalizer.toolCreateObjectId(invocation.getRunId(),
                invocation.getToolCode(), invocation.getIdempotencyKey());
    }

    private static CanvasProjectService.AssistantNodeMutation mutation(RuntimeScopeAccess.Scope scope, Long nodeFence,
                                                                        String operationId, String targetObjectId,
                                                                        Long expectedVersion, String action,
                                                                        Map<String, Object> payload,
                                                                        RuntimeOperationSubmissionClaim claim) {
        return new CanvasProjectService.AssistantNodeMutation(scope.runId(), scope.leaseOwner(), scope.leaseUntil(),
                scope.runtimeFencingToken(), nodeFence,
                scope.objectId(), scope.objectVersion(), null, operationId, targetObjectId, expectedVersion,
                action, payload, claim);
    }

    private static CanvasProjectService.AssistantNodeMutation mutation(RuntimeScopeAccess.WorkspaceScope scope,
                                                                        Long expectedWorkspaceRevision,
                                                                        String operationId, String targetObjectId,
                                                                        Long expectedVersion, String action,
                                                                        Map<String, Object> payload,
                                                                        RuntimeOperationSubmissionClaim claim) {
        return new CanvasProjectService.AssistantNodeMutation(scope.runId(), scope.leaseOwner(), scope.leaseUntil(),
                scope.runtimeFencingToken(), null, null, null, expectedWorkspaceRevision, operationId, targetObjectId,
                expectedVersion, action, payload, claim);
    }

    private static RuntimeToolResult mutationResult(Map<String, Object> output, Long workspaceId, String objectId,
                                                    Long oldRevision, String summary) {
        Long objectVersion = changedObjectVersion(output, objectId);
        if (objectVersion == null) throw failure("CANVAS_RECEIPT_INVALID");
        String artifactRef = "canvas:node:" + workspaceId + ":" + objectId + ":" + objectVersion;
        String receiptId = Objects.toString(output.get("receiptId"), null);
        if (receiptId == null || receiptId.isBlank()) throw failure("CANVAS_RECEIPT_INVALID");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifactRef", artifactRef);
        value.put("objectId", objectId);
        value.put("objectVersion", objectVersion.toString());
        value.put("receiptId", receiptId);
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(value));
        result.setOutputArtifactRefs(List.of(artifactRef));
        if (oldRevision != null) {
            result.setContextRevisionUpdates(List.of(revisionUpdate(objectId, oldRevision, objectVersion, receiptId)));
        }
        result.setSafeSummary(summary);
        return result;
    }

    private static RuntimeToolResult adoptionResult(CanvasArtifactCandidateService.Adoption adoption,
                                                    RuntimeScopeAccess.Scope scope) {
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(adoptionOutput(adoption)));
        result.setOutputArtifactRefs(List.of(adoption.artifactRef(),
                "canvas:node:" + scope.workspaceId() + ":" + scope.objectId() + ":" + adoption.objectVersion()));
        result.setContextRevisionUpdates(List.of(revisionUpdate(scope.objectId(), adoption.previousObjectVersion(),
                adoption.objectVersion(), adoption.operationId())));
        result.setSafeSummary("\u5df2\u91c7\u7528\u5019\u9009\u9879");
        return result;
    }

    private static RuntimeToolResult receiptMissing() {
        return RuntimeToolResult.unknown("TOOL_RECEIPT_NOT_FOUND", "\u64cd\u4f5c\u56de\u6267\u5f85\u5bf9\u8d26");
    }

    private static RuntimeContextRevisionUpdate revisionUpdate(String objectId, Long oldRevision, Long newRevision,
                                                               String receiptId) {
        if (objectId == null || oldRevision == null || newRevision == null || newRevision <= oldRevision
                || receiptId == null || receiptId.isBlank()) {
            throw failure("CANVAS_REVISION_RECEIPT_INVALID");
        }
        RuntimeContextRevisionUpdate update = new RuntimeContextRevisionUpdate();
        update.setKind("FREE_CANVAS");
        update.setObjectId(objectId);
        update.setOldRevision(oldRevision.toString());
        update.setNewRevision(newRevision.toString());
        update.setReceiptId(receiptId);
        return update;
    }

    private static Long changedObjectVersion(Map<String, Object> output, String objectId) {
        if (output == null || !(output.get("objects") instanceof List<?> objects)) return null;
        for (Object value : objects) {
            if (!(value instanceof Map<?, ?> item) || !Objects.equals(objectId,
                    Objects.toString(item.get("objectId"), null))) continue;
            Object version = item.get("objectVersion");
            if (version instanceof Number number) return number.longValue();
            try { return version == null ? null : Long.valueOf(version.toString()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private RuntimeScopeAccess.Scope authorizeExact(RuntimeToolInvocation invocation, JSONObject arguments,
                                                    boolean mutate) {
        CanvasAssistantContextManifest.TargetRef target = manifestTarget(invocation, arguments, mutate);
        RuntimeScopeAccess.Lease lease = lease(invocation.getLease());
        return scopeAccess.authorizeExactTarget(invocation.getRunId(), invocation.getUserId(), lease,
                target.projectId(), target.workspaceId(), target.objectId(), target.objectVersion());
    }

    private RuntimeScopeAccess.Scope authorizeTarget(RuntimeToolInvocation invocation, RuntimeScopeAccess.Lease lease,
                                                      CanvasAssistantContextManifest.TargetRef target,
                                                      boolean exact) {
        return exact
                ? scopeAccess.authorizeExactTarget(invocation.getRunId(), invocation.getUserId(), lease,
                target.projectId(), target.workspaceId(), target.objectId(), target.objectVersion())
                : scopeAccess.authorizeTarget(invocation.getRunId(), invocation.getUserId(), lease,
                target.projectId(), target.workspaceId(), target.objectId(), target.objectVersion());
    }

    private RuntimeScopeAccess.Scope reserveTarget(RuntimeToolInvocation invocation, RuntimeScopeAccess.Lease lease,
                                                    CanvasAssistantContextManifest.TargetRef target) {
        return scopeAccess.reserveTarget(invocation.getRunId(), invocation.getUserId(), lease,
                target.projectId(), target.workspaceId(), target.objectId(), target.objectVersion());
    }

    private static RuntimeScopeAccess.Scope receiptScope(RuntimeToolInvocation invocation,
                                                          CanvasAssistantContextManifest.TargetRef target) {
        RuntimeLease lease = invocation.getLease();
        if (lease == null || lease.getFencingToken() == null) throw failure("RUNTIME_LEASE_MISSING");
        return new RuntimeScopeAccess.Scope(invocation.getRunId(), invocation.getUserId(), target.projectId(),
                target.workspaceId(), target.objectId(), target.objectVersion(), lease.getOwner(),
                lease.getFencingToken(), lease.getLeaseUntil(), null);
    }

    private static RuntimeScopeAccess.WorkspaceScope receiptWorkspace(RuntimeToolInvocation invocation,
                                                                       CanvasAssistantContextManifest.WorkspaceRef ref) {
        RuntimeLease lease = invocation.getLease();
        if (lease == null || lease.getFencingToken() == null) throw failure("RUNTIME_LEASE_MISSING");
        return new RuntimeScopeAccess.WorkspaceScope(invocation.getRunId(), invocation.getUserId(), ref.projectId(),
                ref.workspaceId(), ref.workspaceRevision(), lease.getOwner(), lease.getFencingToken(),
                lease.getLeaseUntil());
    }

    private static CanvasAssistantContextManifest.TargetRef manifestTarget(RuntimeToolInvocation invocation,
                                                                            JSONObject arguments,
                                                                            boolean mutate) {
        JSONObject target = arguments.getJSONObject("target");
        if (target == null) throw failure("CANVAS_TARGET_INVALID");
        rejectUnknown(target, Set.of("objectId", "objectVersion"));
        return CanvasAssistantContextManifest.requireTarget(invocation, target.getString("objectId"),
                target.getString("objectVersion"), mutate);
    }

    private static Map<String, Object> candidateOutput(CanvasArtifactCandidateService.Candidate item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifactRef", item.artifactRef());
        value.put("resultId", item.resultId().toString());
        value.put("taskId", item.taskId().toString());
        value.put("mediaType", item.mediaType());
        // Empty means unknown; old persisted media need not have MIME metadata.
        value.put("mimeType", Objects.toString(item.mimeType(), ""));
        value.put("resourceId", item.resourceId() == null ? null : item.resourceId().toString());
        value.put("inputObjectVersion", item.inputObjectVersion().toString());
        return value;
    }

    private static Map<String, Object> adoptionOutput(CanvasArtifactCandidateService.Adoption item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifactRef", item.artifactRef());
        value.put("resultId", item.resultId().toString());
        value.put("taskId", item.taskId().toString());
        value.put("objectId", item.objectId());
        value.put("previousObjectVersion", item.previousObjectVersion().toString());
        value.put("objectVersion", item.objectVersion().toString());
        value.put("selectionVersion", item.selectionVersion().toString());
        value.put("workspaceRevision", item.workspaceRevision().toString());
        value.put("eventSeq", item.eventSeq().toString());
        value.put("operationId", item.operationId());
        return value;
    }

    private static double number(JSONObject arguments, String key, double fallback) {
        Object value = arguments.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw failure("CANVAS_POSITION_INVALID");
        }
        return number.doubleValue();
    }

    private static String operationId(String prefix, Long runId, String idempotencyKey) {
        return CanvasAssistantOperationCanonicalizer.toolOperationId(prefix, runId, idempotencyKey);
    }

    private static RuntimeScopeAccess.Lease lease(RuntimeLease lease) {
        if (lease == null) throw failure("RUNTIME_LEASE_MISSING");
        return new RuntimeScopeAccess.Lease(lease.getOwner(), lease.getFencingToken(), lease.getLeaseUntil());
    }

    static RuntimeToolInvocation invocation(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || prepared.getRunLease() == null || prepared.getStepLease() == null) {
            throw failure("RUNTIME_PREPARED_LEASE_MISSING");
        }
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId());
        invocation.setUserId(prepared.getUserId());
        invocation.setRuntimeStepId(prepared.getRuntimeStepId());
        invocation.setPlanRevision(prepared.getPlanRevision());
        invocation.setStableStepId(prepared.getStableStepId());
        invocation.setAttemptNo(prepared.getAttemptNo());
        invocation.setOperationKind(prepared.getOperationKind());
        invocation.setToolCode(prepared.getToolCode());
        invocation.setContractVersion(prepared.getContractVersion());
        invocation.setSchemaDigest(prepared.getSchemaDigest());
        invocation.setArgumentsJson(prepared.getPreparedPayloadJson());
        invocation.setArgumentsDigest(prepared.getPreparedPayloadDigest());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        invocation.setApprovalId(prepared.getApprovalId());
        invocation.setApprovedScopeDigest(prepared.getApprovedScopeDigest());
        invocation.setIdempotencyKey(prepared.getIdempotencyKey());
        invocation.setLease(prepared.getRunLease());
        invocation.setOperationScope(prepared.getOperationScope());
        return invocation;
    }

    static RuntimeOperationSubmissionClaim submissionClaim(RuntimeToolPreparedInvocation prepared) {
        RuntimeOperationSubmissionClaim claim = new RuntimeOperationSubmissionClaim();
        claim.setRunId(prepared.getRunId());
        claim.setUserId(prepared.getUserId());
        claim.setStepId(prepared.getRuntimeStepId());
        claim.setApprovalId(prepared.getApprovalId());
        // 提交身份匹配运行步骤类型；具体创作操作仍由冻结审批范围校验。
        claim.setOperationKind("ASSISTANT_TOOL");
        claim.setIdempotencyKey(prepared.getIdempotencyKey());
        claim.setRunLease(prepared.getRunLease());
        claim.setStepLease(prepared.getStepLease());
        return claim;
    }

    private static void requireSubmissionClaim(RuntimeOperationSubmissionClaim claim) {
        if (claim == null || claim.getRunLease() == null || claim.getStepLease() == null) {
            throw failure("RUNTIME_OPERATION_CLAIM_REQUIRED");
        }
    }

    private static JSONObject arguments(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw failure("CANVAS_ARGUMENTS_INVALID");
            return value;
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure("CANVAS_ARGUMENTS_INVALID");
        }
    }

    private static void rejectUnknown(JSONObject value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw failure("CANVAS_ARGUMENT_UNKNOWN");
    }

    private static Long positiveId(String value, String code) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw failure(code);
        try { return Long.valueOf(value); }
        catch (NumberFormatException invalid) { throw failure(code); }
    }

    private static ServiceException failure(String code) {
        return new ServiceException("\u753b\u5e03\u8c03\u7528\u5931\u8d25").setDetailMessage(code);
    }

    private static RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                             String input, String output, String sideEffect,
                                                             String idempotency, String recovery,
                                                             List<String> capabilities) {
        return new RuntimeToolRegistry.Descriptor(code, VERSION, SecureUtil.sha256(input + "\n" + output),
                name, description, input, output, sideEffect, false, idempotency, false, recovery, capabilities);
    }

    private static String nodeArtifactRef(RuntimeScopeAccess.Scope scope) {
        return "canvas:node:" + scope.workspaceId() + ":" + scope.objectId() + ":" + scope.objectVersion();
    }
}
