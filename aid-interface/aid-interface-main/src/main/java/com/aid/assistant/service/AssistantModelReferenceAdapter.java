package com.aid.assistant.service;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccessRegistry;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.canvas.runtime.CanvasLocalTextImageResolver;
import com.aid.canvas.runtime.CanvasResourceRuntimeScopeAccess;
import com.aid.canvas.runtime.CanvasStoredTextResourceReader;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.OpenAiCompatibleConstants;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.impl.DeepSeekTextProviderClient;
import com.aid.service.IAiModelConfigService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts authorized assistant references into capability-gated provider media parts. */
@Component
@RequiredArgsConstructor
public class AssistantModelReferenceAdapter {
    private final IAiModelConfigService modelConfigService;
    private final CanvasResourceService resourceService;
    private final MediaUrlResolver mediaUrlResolver;
    private final AssistantContextLimits contextLimits;
    private final CanvasStoredTextResourceReader textResourceReader;
    private final RuntimeScopeAccessRegistry scopeAccessRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<AssistantReferenceMediaAdapter> moduleMediaAdapters = List.of();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<AssistantExecutionMediaAdapter> executionMediaAdapters = List.of();
    @jakarta.annotation.Resource
    private CanvasLocalTextImageResolver localTextImages;

    public List<MediaTextGenerateRequest.TextContentPart> adapt(
            Long userId, String modelCode, JSONObject assembledContext) {
        return adapt(userId, modelCode, assembledContext, true);
    }

    private List<MediaTextGenerateRequest.TextContentPart> adapt(
            Long userId, String modelCode, JSONObject assembledContext, boolean allowManagedImages) {
        JSONObject execution = assembledContext == null ? null : assembledContext.getJSONObject("executionContext");
        JSONArray references = execution == null ? null : execution.getJSONArray("referenceContents");
        if (references == null || references.isEmpty()) return List.of();
        AiModelConfigVo model;
        try {
            model = modelConfigService.selectByModelCodeForUser(modelCode, userId, "text");
        } catch (RuntimeException unavailable) {
            throw invalid("ASSISTANT_REFERENCE_MODEL_UNAVAILABLE", modelCode);
        }
        if (model == null) throw invalid("ASSISTANT_REFERENCE_MODEL_UNAVAILABLE", modelCode);
        Set<String> modalities = inputModalities(model.getCapabilityJson());
        boolean managedDelivery = allowManagedImages && localTextImages != null
                && (DeepSeekTextProviderClient.PROTOCOL.equals(model.getProtocol())
                || OpenAiCompatibleConstants.PROTOCOL_TEXT.equals(model.getProtocol()));
        List<MediaTextGenerateRequest.TextContentPart> parts = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            JSONObject reference = references.getJSONObject(index);
            if (reference == null) throw invalid("ASSISTANT_CONTEXT_REFERENCE_INVALID", index);
            if ("CANVAS_RESOURCE".equals(reference.getString("source"))) {
                addResource(userId, reference, reference, modalities, parts, managedDelivery);
            } else if ("CANVAS_NODE".equals(reference.getString("source"))) {
                JSONObject selected = reference.getJSONObject("selectedResult");
                if (selected != null) addResource(userId, reference, selected, modalities, parts, managedDelivery);
                JSONArray inputs = reference.getJSONArray("inputResources");
                if (inputs != null) for (int resourceIndex = 0; resourceIndex < inputs.size(); resourceIndex++) {
                    JSONObject input = inputs.getJSONObject(resourceIndex);
                    if (input == null) throw invalid("ASSISTANT_CONTEXT_REFERENCE_INVALID", index);
                    addResource(userId, reference, input, modalities, parts, managedDelivery);
                }
            } else {
                var adapters = moduleMediaAdapters.stream().filter(adapter -> adapter.supports(reference.getString("source"))).toList();
                if (adapters.size() > 1) throw invalid("ASSISTANT_CONTEXT_PROJECTOR_AMBIGUOUS", index);
                if (!adapters.isEmpty()) {
                    parts.addAll(adapters.get(0).adapt(userId, reference, modalities));
                    contextLimits.checkRawRequestBudget(parts.size());
                }
            }
        }
        for (AssistantExecutionMediaAdapter adapter : executionMediaAdapters) {
            parts.addAll(adapter.adapt(userId, assembledContext, modalities));
            contextLimits.checkRawRequestBudget(parts.size());
        }
        int actualBytes = assembledContext.toJSONString().getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > contextLimits.maxContextBytes()) {
            throw invalid("ASSISTANT_CONTEXT_TOO_LARGE", Map.of(
                    "actualBytes", actualBytes, "maxBytes", contextLimits.maxContextBytes(),
                    "overflowPolicy", "REJECT"));
        }
        return List.copyOf(parts);
    }

    /** Re-authorizes every persisted reference immediately before an approved main-Agent request is submitted. */
    public void requireCurrentRequest(Long userId, MediaTextGenerateRequest request) {
        if (userId == null || request == null || StrUtil.isBlank(request.getModelName())
                || request.getMessages() == null) {
            throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", null);
        }
        MediaTextGenerateRequest.TextMessageItem envelopeMessage = null;
        JSONObject assembledContext = null;
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
            if (message == null || !"user".equals(message.getRole()) || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            try {
                JSONObject envelope = JSON.parseObject(message.getContent());
                JSONObject candidate = envelope == null ? null : envelope.getJSONObject("input");
                JSONObject execution = candidate == null ? null : candidate.getJSONObject("executionContext");
                if (execution == null || !execution.containsKey("referenceContents")) continue;
                if (envelopeMessage != null) {
                    throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", "AMBIGUOUS_REFERENCE_ENVELOPE");
                }
                envelopeMessage = message;
                assembledContext = candidate;
            } catch (AssistantServiceException known) {
                throw known;
            } catch (RuntimeException ignored) {
                // Other user messages are not trusted assistant envelopes.
            }
        }
        if (envelopeMessage == null || assembledContext == null) {
            throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", "REFERENCE_ENVELOPE_MISSING");
        }
        requireCurrentManifest(userId, assembledContext);
        List<MediaTextGenerateRequest.TextContentPart> current = adapt(
                userId, request.getModelName(), assembledContext);
        List<MediaTextGenerateRequest.TextContentPart> approved = envelopeMessage.getParts() == null
                ? List.of() : envelopeMessage.getParts();
        // Quote preparation may replace server-recorded image facts with freshly inspected
        // metadata.  The trusted snapshot preserves those checked values and submission checks
        // them again, so the reference fence compares the resource-bearing fields only.
        if (!sameNativeReferences(approved, current)
                && !sameNativeReferences(approved, adapt(userId, request.getModelName(), assembledContext, false))) {
            throw invalid("ASSISTANT_REFERENCE_REQUEST_CHANGED",
                    Map.of("approvedNativeParts", approved.size(), "currentNativeParts", current.size()));
        }
    }

    private boolean sameNativeReferences(List<MediaTextGenerateRequest.TextContentPart> approved,
                                         List<MediaTextGenerateRequest.TextContentPart> current) {
        if (approved.size() != current.size()) return false;
        for (int index = 0; index < current.size(); index++) {
            MediaTextGenerateRequest.TextContentPart before = approved.get(index);
            MediaTextGenerateRequest.TextContentPart now = current.get(index);
            if (before == null || now == null
                    || !Objects.equals(before.getType(), now.getType())
                    || !Objects.equals(before.getUrl(), now.getUrl())) return false;
        }
        return true;
    }

    private void requireCurrentManifest(Long userId, JSONObject assembledContext) {
        JSONObject execution = assembledContext.getJSONObject("executionContext");
        JSONObject manifest = execution == null ? null : execution.getJSONObject("manifest");
        JSONArray rows = manifest == null ? null : manifest.getJSONArray("refs");
        if (manifest == null || !"assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                || rows == null || rows.size() > contextLimits.maxReferences() + 1) {
            throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", "REFERENCE_MANIFEST_INVALID");
        }
        try {
            for (int index = 0; index < rows.size(); index++) {
                JSONObject row = rows.getJSONObject(index);
                JSONObject canonical = row == null ? null : row.getJSONObject("canonicalRef");
                String kind = row == null ? null : row.getString("kind");
                String contentRef = row == null ? null : row.getString("contentRef");
                String access = row == null ? null : row.getString("access");
                if (canonical == null || StrUtil.isBlank(kind) || StrUtil.isBlank(contentRef)
                        || StrUtil.isBlank(access)) {
                    throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", "REFERENCE_MANIFEST_INVALID");
                }
                AssistantRequests.ContextRef identity = canonical.toJavaObject(AssistantRequests.ContextRef.class);
                identity.setKind(kind);
                ResolvedContextRef resolved;
                if (AssistantReferenceContextFence.isImplicitWorkspace(row)) {
                    identity.setVersion(null);
                    resolved = scopeAccessRegistry.resolveCurrentAll(userId, List.of(identity)).get(0);
                } else {
                    resolved = scopeAccessRegistry.resolvePinnedAll(userId, List.of(identity)).get(0);
                }
                JSONObject currentRow = new JSONObject();
                currentRow.put("kind", resolved.contextRef().getKind());
                currentRow.put("canonicalRef", canonicalIdentity(resolved.contextRef()));
                currentRow.put("contentRef", resolved.contentRef());
                currentRow.put("access", resolved.access());
                if (!AssistantReferenceContextFence.matchesPinnedRow(row, currentRow)) {
                    throw invalid("ASSISTANT_REFERENCE_REQUEST_CHANGED",
                            Map.of("ordinal", index, "reason", "REFERENCE_MANIFEST_CHANGED"));
                }
            }
        } catch (AssistantServiceException known) {
            throw known;
        } catch (RuntimeException malformed) {
            throw invalid("ASSISTANT_REFERENCE_REQUEST_INVALID", "REFERENCE_MANIFEST_INVALID");
        }
    }

    private JSONObject canonicalIdentity(AssistantRequests.ContextRef ref) {
        JSONObject value = new JSONObject();
        put(value, "previzProjectId", ref.getPrevizProjectId());
        put(value, "sourceVersion", ref.getSourceVersion());
        put(value, "workspaceId", ref.getWorkspaceId() == null ? null : String.valueOf(ref.getWorkspaceId()));
        put(value, "resourceId", ref.getResourceId() == null ? null : String.valueOf(ref.getResourceId()));
        put(value, "objectId", ref.getObjectId());
        put(value, "version", ref.getVersion());
        put(value, "projectId", ref.getProjectId() == null ? null : String.valueOf(ref.getProjectId()));
        put(value, "episodeId", ref.getEpisodeId() == null ? null : String.valueOf(ref.getEpisodeId()));
        put(value, "entityType", ref.getEntityType());
        put(value, "entityId", ref.getEntityId());
        put(value, "scriptId", ref.getScriptId() == null ? null : String.valueOf(ref.getScriptId()));
        put(value, "documentId", ref.getDocumentId() == null ? null : String.valueOf(ref.getDocumentId()));
        put(value, "documentSessionId", ref.getDocumentSessionId() == null
                ? null : String.valueOf(ref.getDocumentSessionId()));
        put(value, "contentHash", ref.getContentHash());
        if (ref.getSelection() != null) {
            JSONObject selection = new JSONObject();
            selection.put("start", ref.getSelection().getStart());
            selection.put("end", ref.getSelection().getEnd());
            selection.put("selectedTextHash", ref.getSelection().getSelectedTextHash());
            value.put("selection", selection);
        }
        return value;
    }

    private void put(JSONObject target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private void addResource(Long userId, JSONObject reference, JSONObject resourceView,
                             Set<String> modalities,
                             List<MediaTextGenerateRequest.TextContentPart> parts, boolean managedDelivery) {
        Long workspaceId = positive(reference.getString("workspaceId"));
        Long resourceId = positive(resourceView.getString("resourceId"));
        int ordinal = reference.getIntValue("ordinal", -1);
        if (workspaceId == null || resourceId == null || ordinal < 0)
            throw invalid("ASSISTANT_CONTEXT_REFERENCE_INVALID", ordinal);
        CanvasResource resource;
        try {
            resource = resourceService.requireResources(workspaceId, List.of(resourceId), userId).get(0);
        } catch (RuntimeException unavailable) {
            throw invalid("ASSISTANT_REFERENCE_RESOURCE_UNAVAILABLE",
                    Map.of("ordinal", ordinal, "resourceId", String.valueOf(resourceId)));
        }
        String mediaType = StrUtil.blankToDefault(resource.getMediaType(), "").trim().toUpperCase(Locale.ROOT);
        if (!Objects.equals(mediaType, resourceView.getString("mediaType"))
                || !Objects.equals(CanvasResourceRuntimeScopeAccess.identityHash(resource),
                    resourceView.getString("contentHash"))
                || !"ACTIVE".equals(resource.getStatus()) || StrUtil.isBlank(resource.getStorageUrl())) {
            throw invalid("ASSISTANT_REFERENCE_RESOURCE_CHANGED",
                    Map.of("ordinal", ordinal, "resourceId", String.valueOf(resourceId)));
        }
        if (resourceView.containsKey("textContent")) {
            if (!textResourceReader.supports(resource)
                    || StrUtil.isBlank(resourceView.getString("textContent"))) {
                throw invalid("ASSISTANT_REFERENCE_TEXT_RESOURCE_INVALID",
                        Map.of("ordinal", ordinal, "resourceId", String.valueOf(resourceId)));
            }
            resourceView.put("modelDelivery", "INLINE_EXTRACTED_TEXT");
            resourceView.put("semanticSource", "RESOURCE_TEXT");
            resourceView.put("semanticDescriptionRequired", false);
            return;
        }
        // The current outbound text stack signs and proxies IMAGE parts end to end. Other
        // advertised modalities remain metadata-only until their private-resource transport is
        // verified; a catalog flag alone must not imply that the Agent received the bytes.
        if (!"IMAGE".equals(mediaType) || !modalities.contains(mediaType)) {
            resourceView.put("modelDelivery", "METADATA_ONLY");
            resourceView.put("semanticSource", "MEDIA_REQUIRES_MODEL_OR_DESCRIPTION");
            resourceView.put("semanticDescriptionRequired", true);
            resourceView.put("assistantAction", "CALL_AN_AUTHORIZED_CATALOGUED_DESCRIPTION_TOOL_OR_ASK_USER");
            if ("IMAGE".equals(mediaType)) {
                resourceView.put("serverDescriptionCapabilityCode", "canvas.text.image_describe");
                resourceView.put("directAgentToolAvailability", "CATALOG_DEPENDENT");
            } else {
                resourceView.put("serverDescriptionCapabilityCode", "NONE");
                resourceView.put("directAgentToolAvailability", "UNAVAILABLE");
            }
            return;
        }
        MediaTextGenerateRequest.TextContentPart part = new MediaTextGenerateRequest.TextContentPart();
        part.setType(mediaType.toLowerCase(Locale.ROOT));
        String localReference = managedDelivery ? localTextImages.referenceIfAvailable(resource) : null;
        part.setUrl(localReference == null ? mediaUrlResolver.toFullUrl(resource.getStorageUrl()) : localReference);
        part.setMimeType(resource.getMimeType());
        part.setSizeBytes(resource.getFileSize());
        applyMetadata(part, resource.getMetadataJson(), ordinal, resourceId);
        parts.add(part);
        contextLimits.checkRawRequestBudget(parts.size());
        resourceView.put("modelDelivery", "PROVIDER_NATIVE_" + mediaType);
        resourceView.put("semanticSource", "PROVIDER_NATIVE_MEDIA");
        resourceView.put("semanticDescriptionRequired", false);
    }

    private Set<String> inputModalities(String json) {
        if (StrUtil.isBlank(json)) return Set.of("TEXT");
        try {
            JSONObject capability = JSON.parseObject(json);
            JSONArray values = capability == null ? null : capability.getJSONArray("inputModalities");
            LinkedHashSet<String> result = new LinkedHashSet<>();
            if (values != null) for (Object value : values)
                if (value instanceof String text && !text.isBlank()) result.add(text.trim().toUpperCase(Locale.ROOT));
            if (result.isEmpty() && capability != null) {
                for (String type : List.of("IMAGE", "VIDEO", "AUDIO", "DOCUMENT"))
                    if (capability.getBooleanValue("supports" + title(type) + "Input")) result.add(type);
            }
            result.add("TEXT");
            return Set.copyOf(result);
        } catch (RuntimeException invalid) {
            throw invalid("ASSISTANT_REFERENCE_MODEL_CAPABILITY_INVALID", null);
        }
    }

    /** Describes the selected model separately from media this assistant can actually deliver. */
    public String describeModelCapabilities(String modelCode, String capabilityJson,
                                            boolean streaming, boolean nativeTools) {
        Set<String> modalities;
        boolean valid = true;
        try {
            modalities = inputModalities(capabilityJson);
        } catch (AssistantServiceException invalid) {
            modalities = Set.of("TEXT");
            valid = false;
        }
        List<String> declared = new ArrayList<>();
        for (String type : List.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT")) {
            if (modalities.contains(type)) declared.add(switch (type) {
                case "IMAGE" -> "图片";
                case "VIDEO" -> "视频";
                case "AUDIO" -> "音频";
                case "DOCUMENT" -> "文档";
                default -> "文本";
            });
        }
        return "\n\n本轮实际模型：" + modelCode + "。模型声明的输入类型："
                + String.join("、", declared) + (valid ? "。" : "；媒体能力配置无效，不据此认定可解析媒体。")
                + "本次正文流：" + (streaming ? "开启" : "关闭")
                + "；原生函数调用：" + (nativeTools ? "开启" : "关闭") + "。"
                + (modalities.contains("IMAGE")
                ? "授权图片可通过本次请求的图片通道传入；是否实际送达以每份引用的 modelDelivery 为准。"
                : "本模型未声明图片输入，图片引用仅提供元数据，不能声称已看见图片。")
                + "当前助手尚未核验视频、音频及原始文档的直接媒体传输，不能把模型声明的能力等同于本轮已收到内容。"
                + "已提取的文本可作为文本读取；其他媒体应按引用回执使用本轮授权目录中真实存在的解析工具，"
                + "没有工具时说明缺口并向用户索取可用描述。"
                + (nativeTools ? "单轮最多调用一个函数，只能使用本轮 tools 中列出的函数。"
                + "正文中的工具控制标记只是文字，不会作为工具调用提交或执行；只有原生工具通道中的真实调用才可能执行。"
                + "展示工具标记示例时使用代码块或转义文本。若收到控制正文格式纠正反馈，上一轮文字被拒绝，不代表工具已经调用或执行失败；"
                + "不得声称这段文字产生了工具操作或副作用，只能依据真实工具回执说明执行结果。" : "");
    }

    private void applyMetadata(MediaTextGenerateRequest.TextContentPart part, String json,
                               int ordinal, Long resourceId) {
        if (StrUtil.isBlank(json)) return;
        try {
            JSONObject metadata = JSON.parseObject(json);
            if (metadata == null) throw new IllegalArgumentException();
            part.setWidth(metadata.getInteger("width"));
            part.setHeight(metadata.getInteger("height"));
            part.setDurationSeconds(metadata.getDouble("durationSeconds"));
            part.setFps(metadata.getDouble("fps"));
            part.setPageCount(metadata.getInteger("pageCount"));
        } catch (RuntimeException invalid) {
            throw invalid("ASSISTANT_REFERENCE_RESOURCE_METADATA_INVALID",
                    Map.of("ordinal", ordinal, "resourceId", String.valueOf(resourceId)));
        }
    }

    private String title(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private Long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private AssistantServiceException invalid(String code, Object details) {
        return new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code,
                "引用媒体无法提供给当前主 Agent", details);
    }
}
