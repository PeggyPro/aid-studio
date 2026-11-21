package com.aid.canvas.runtime;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.service.AssistantContextReferenceProjector;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.NodeResultSelection;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasResourceService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Projects pinned Canvas nodes and resources without trusting client-provided content. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantContextReferenceProjector implements AssistantContextReferenceProjector {
    private static final Set<String> NODE_TYPES = Set.of("TEXT", "IMAGE", "VIDEO", "VOICE", "AUDIO", "MUSIC");
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService canvasAccessService;
    private final CanvasResourceService resourceService;
    private final CanvasStoredTextResourceReader textResourceReader;
    private final AssistantContextLimits contextLimits;

    @Override
    public boolean supports(String kind) {
        return "FREE_CANVAS".equals(kind) || "CANVAS_RESOURCE".equals(kind);
    }

    @Override
    public JSONObject project(Long userId, ResolvedContextRef reference, int ordinal) {
        if (reference == null || reference.contextRef() == null) invalid("CANVAS_REFERENCE_INVALID", ordinal);
        AssistantRequests.ContextRef ref = reference.contextRef();
        if ("CANVAS_RESOURCE".equals(ref.getKind())) return resourceReference(userId, reference, ordinal);
        if (!"FREE_CANVAS".equals(ref.getKind())) invalid("CANVAS_REFERENCE_KIND_INVALID", ordinal);
        if (ref.getObjectId() == null) return workspaceReference(userId, reference, ordinal);
        return nodeReference(userId, reference, ordinal);
    }

    private JSONObject workspaceReference(Long userId, ResolvedContextRef reference, int ordinal) {
        AssistantRequests.ContextRef ref = reference.contextRef();
        var workspace = canvasAccessService.requireWorkspace(ref.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        String version = String.valueOf(workspace.getRevision());
        if (!Objects.equals(workspace.getProjectId(), ref.getProjectId()) || !version.equals(ref.getVersion())
                || !Objects.equals(reference.contentRef(), "canvas:workspace:" + ref.getWorkspaceId() + ":" + version))
            invalid("CANVAS_REFERENCE_WORKSPACE_CHANGED", ordinal);
        var nodes = canvasMapper.selectAssistantWorkspaceNodeSummaries(ref.getProjectId(), ref.getWorkspaceId());
        var current = canvasAccessService.requireWorkspace(ref.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        if (!Objects.equals(workspace.getProjectId(), current.getProjectId())
                || !Objects.equals(workspace.getRevision(), current.getRevision()))
            invalid("CANVAS_REFERENCE_WORKSPACE_CHANGED", ordinal);
        JSONObject result = base(ordinal, "CANVAS_WORKSPACE_NODE_SUMMARY", ref);
        result.put("version", version);
        result.put("hasNodes", !nodes.isEmpty());
        result.put("hasMore", nodes.size() > 10);
        result.put("nodes", nodes.stream().limit(10).map(node -> JSONObject.of(
                "objectId", node.get("objectId"), "objectVersion", Objects.toString(node.get("objectVersion"), ""),
                "nodeType", node.get("nodeType"), "title", node.get("title"))).toList());
        result.put("untrustedData", true);
        result.put("semanticSource", "CURRENT_WORKSPACE_NODES");
        requireProjectionSize(result, ordinal);
        return result;
    }

    private JSONObject nodeReference(Long userId, ResolvedContextRef reference, int ordinal) {
        AssistantRequests.ContextRef ref = reference.contextRef();
        CanvasObject node = canvasMapper.selectCanvasObject(ref.getWorkspaceId(), ref.getObjectId());
        Long version = positive(ref.getVersion(), "CANVAS_REFERENCE_VERSION_INVALID", ordinal);
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getProjectId(), ref.getProjectId())
                || !Objects.equals(node.getWorkspaceId(), ref.getWorkspaceId())
                || !Objects.equals(node.getObjectVersion(), version)
                || !Objects.equals(reference.contentRef(), "canvas:node:" + ref.getWorkspaceId() + ":"
                    + ref.getObjectId() + ":" + version)) {
            invalid("CANVAS_REFERENCE_NODE_CHANGED", ordinal);
        }
        JSONObject content;
        try {
            content = JSON.parseObject(node.getContentJson());
        } catch (RuntimeException error) {
            invalid("CANVAS_REFERENCE_NODE_CONTENT_INVALID", ordinal);
            return null;
        }
        if (content == null) invalid("CANVAS_REFERENCE_NODE_CONTENT_INVALID", ordinal);
        String nodeType = content.getString("nodeType");
        if (StrUtil.isBlank(nodeType)) invalid("CANVAS_REFERENCE_NODE_TYPE_MISSING", ordinal);
        nodeType = nodeType.trim().toUpperCase();
        if (!NODE_TYPES.contains(nodeType)) nodeType = "OTHER";

        JSONObject result = base(ordinal, "CANVAS_NODE", ref);
        result.put("objectId", ref.getObjectId());
        result.put("version", ref.getVersion());
        result.put("nodeType", nodeType);
        putText(result, "title", content.getString("title"));
        putText(result, "prompt", content.getString("prompt"));
        putText(result, "negativePrompt", content.getString("negativePrompt"));
        if ("TEXT".equals(nodeType)) {
            Object raw = content.containsKey("textContent") ? content.get("textContent") : content.get("content");
            if (!(raw instanceof String text)) invalid("CANVAS_REFERENCE_TEXT_MISSING", ordinal);
            result.put("text", CanvasPlainText.normalize((String) raw, content.getString("textFormat")));
            result.put("semanticSource", "NODE_TEXT");
        }

        List<Long> inputIds = resourceIds(content.getJSONArray("resourceIds"), ordinal);
        List<CanvasResource> inputResources = resources(ref.getWorkspaceId(), inputIds, userId, ordinal);
        if (inputResources.stream().anyMatch(resource -> !Objects.equals(resource.getProjectId(), ref.getProjectId())
                || !Objects.equals(resource.getWorkspaceId(), ref.getWorkspaceId()))) {
            invalid("CANVAS_REFERENCE_RESOURCE_SCOPE_CHANGED", ordinal);
        }
        if (!inputResources.isEmpty()) result.put("inputResources", summaries(inputResources));

        NodeResultSelection selection = canvasMapper.selectNodeResultSelection(ref.getWorkspaceId(), ref.getObjectId());
        if (selection != null) {
            if (!Objects.equals(selection.getProjectId(), ref.getProjectId())
                    || !Objects.equals(selection.getWorkspaceId(), ref.getWorkspaceId())
                    || !Objects.equals(selection.getObjectId(), ref.getObjectId())
                    || selection.getTaskId() == null || selection.getTaskId() < 1
                    || selection.getResultId() == null || selection.getResultId() < 1
                    || selection.getInputObjectVersion() == null || selection.getInputObjectVersion() < 1
                    || selection.getInputObjectVersion() > version
                    || selection.getSelectionVersion() == null || selection.getSelectionVersion() < 1) {
                invalid("CANVAS_REFERENCE_SELECTED_RESULT_CHANGED", ordinal);
            }
            Map<String, Object> selected = canvasMapper.selectMediaResultForNode(
                    ref.getWorkspaceId(), ref.getObjectId(), selection.getResultId());
            CanvasResource selectedResource = selected == null ? null
                    : canvasMapper.selectCanvasResourceBySource(ref.getWorkspaceId(), "MEDIA_RESULT", selection.getResultId());
            if (selected == null
                    || !Objects.equals(id(selected.get("resultId")), selection.getResultId())
                    || !Objects.equals(id(selected.get("taskId")), selection.getTaskId())
                    || !Objects.equals(id(selected.get("inputObjectVersion")), selection.getInputObjectVersion())
                    || selectedResource == null || !"ACTIVE".equals(selectedResource.getStatus())
                    || StrUtil.isBlank(selectedResource.getStorageUrl()) || StrUtil.isBlank(selectedResource.getMediaType())
                    || !Objects.equals(selectedResource.getProjectId(), ref.getProjectId())
                    || !Objects.equals(selectedResource.getWorkspaceId(), ref.getWorkspaceId())
                    || !"MEDIA_RESULT".equals(selectedResource.getSourceType())
                    || !Objects.equals(selectedResource.getSourceId(), selection.getResultId())) {
                invalid("CANVAS_REFERENCE_SELECTED_RESULT_UNAVAILABLE", ordinal);
            }
            result.put("selectedResult", summary(selectedResource));
            requireProjectionSize(result, ordinal);
        }
        if (!"TEXT".equals(nodeType)) {
            result.put("semanticSource", StrUtil.isBlank(result.getString("prompt"))
                    ? "MEDIA_REQUIRES_MODEL_OR_DESCRIPTION" : "AUTHOR_PROMPT_ONLY");
            if (!result.containsKey("selectedResult") && !result.containsKey("inputResources")) {
                result.put("modelDelivery", "NO_MEDIA_RESOURCE");
                result.put("semanticDescriptionRequired", StrUtil.isBlank(result.getString("prompt")));
                result.put("assistantAction", StrUtil.isBlank(result.getString("prompt"))
                        ? "ASK_USER_FOR_A_CONCISE_DESCRIPTION" : "USE_AUTHOR_PROMPT_AS_INTENT_ONLY");
            }
        }
        return result;
    }

    private JSONObject resourceReference(Long userId, ResolvedContextRef reference, int ordinal) {
        AssistantRequests.ContextRef ref = reference.contextRef();
        if (ref.getResourceId() == null || ref.getResourceId() < 1)
            invalid("CANVAS_REFERENCE_RESOURCE_ID_INVALID", ordinal);
        List<CanvasResource> resources = resources(ref.getWorkspaceId(), List.of(ref.getResourceId()), userId, ordinal);
        CanvasResource resource = resources.get(0);
        String expected = "canvas:resource:" + ref.getWorkspaceId() + ":" + ref.getResourceId()
                + ":" + ref.getContentHash();
        if (!Objects.equals(resource.getProjectId(), ref.getProjectId())
                || !Objects.equals(reference.contentRef(), expected)) {
            invalid("CANVAS_REFERENCE_RESOURCE_CHANGED", ordinal);
        }
        JSONObject result = base(ordinal, "CANVAS_RESOURCE", ref);
        result.putAll(summary(resource));
        result.put("semanticSource", result.containsKey("textContent")
                ? "RESOURCE_TEXT" : "MEDIA_REQUIRES_MODEL_OR_DESCRIPTION");
        requireProjectionSize(result, ordinal);
        return result;
    }

    private List<CanvasResource> resources(Long workspaceId, List<Long> ids, Long userId, int ordinal) {
        if (ids == null || ids.isEmpty()) return List.of();
        try {
            List<CanvasResource> rows = resourceService.requireResources(workspaceId, ids, userId);
            for (CanvasResource resource : rows) {
                if (resource == null || !"ACTIVE".equals(resource.getStatus())
                        || StrUtil.isBlank(resource.getStorageUrl()) || StrUtil.isBlank(resource.getMediaType())) {
                    invalid("CANVAS_REFERENCE_RESOURCE_UNAVAILABLE", ordinal);
                }
            }
            return rows;
        } catch (AssistantServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            invalid("CANVAS_REFERENCE_RESOURCE_UNAVAILABLE", ordinal);
            return List.of();
        }
    }

    private List<Long> resourceIds(JSONArray values, int ordinal) {
        if (values == null || values.isEmpty()) return List.of();
        contextLimits.checkRawRequestBudget(values.size());
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Object raw : values) {
            String value = String.valueOf(raw);
            if (!value.matches("[1-9][0-9]{0,18}")) invalid("CANVAS_REFERENCE_RESOURCE_ID_INVALID", ordinal);
            try {
                if (!unique.add(Long.valueOf(value))) invalid("CANVAS_REFERENCE_RESOURCE_DUPLICATE", ordinal);
            } catch (NumberFormatException error) {
                invalid("CANVAS_REFERENCE_RESOURCE_ID_INVALID", ordinal);
            }
        }
        return List.copyOf(unique);
    }

    private JSONArray summaries(List<CanvasResource> resources) {
        JSONArray result = new JSONArray();
        for (CanvasResource resource : resources) {
            result.add(summary(resource));
            requireProjectionSize(result, resource.getId());
        }
        return result;
    }

    private JSONObject summary(CanvasResource resource) {
        JSONObject result = new JSONObject();
        result.put("resourceId", String.valueOf(resource.getId()));
        result.put("mediaType", resource.getMediaType());
        result.put("mimeType", resource.getMimeType());
        result.put("fileSize", resource.getFileSize());
        result.put("contentHash", CanvasResourceRuntimeScopeAccess.identityHash(resource));
        if (StrUtil.isNotBlank(resource.getMetadataJson())) {
            JSONObject metadata;
            try {
                metadata = JSON.parseObject(resource.getMetadataJson());
            } catch (RuntimeException error) {
                throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resource.getId());
            }
            if (metadata == null) throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resource.getId());
            JSONObject safe = new JSONObject();
            putPositiveInteger(metadata, safe, "width", resource.getId());
            putPositiveInteger(metadata, safe, "height", resource.getId());
            putNonNegativeNumber(metadata, safe, "durationSeconds", resource.getId());
            putPositiveNumber(metadata, safe, "fps", resource.getId());
            putPositiveInteger(metadata, safe, "pageCount", resource.getId());
            if (!safe.isEmpty()) result.put("mediaFacts", safe);
        }
        if (textResourceReader.supports(resource)) {
            try {
                String text = textResourceReader.read(resource);
                if (StrUtil.isNotBlank(text)) result.put("textContent", text);
            } catch (AssistantServiceException known) {
                if ("SOURCE_PDF_OCR_REQUIRED".equals(known.getErrorCode())) return result;
                throw known;
            } catch (RuntimeException unreadable) {
                throw invalidException("CANVAS_REFERENCE_TEXT_RESOURCE_UNAVAILABLE", resource.getId());
            }
        }
        return result;
    }

    private void putPositiveInteger(JSONObject source, JSONObject target, String key, Long resourceId) {
        if (!source.containsKey(key)) return;
        Object raw = source.get(key);
        if (!(raw instanceof Number number))
            throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resourceId);
        long value = number.longValue();
        double exact = number.doubleValue();
        if (value < 1 || value > Integer.MAX_VALUE || !Double.isFinite(exact) || exact != value)
            throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resourceId);
        target.put(key, value);
    }

    private void putNonNegativeNumber(JSONObject source, JSONObject target, String key, Long resourceId) {
        putFiniteNumber(source, target, key, resourceId, false);
    }

    private void putPositiveNumber(JSONObject source, JSONObject target, String key, Long resourceId) {
        putFiniteNumber(source, target, key, resourceId, true);
    }

    private void putFiniteNumber(JSONObject source, JSONObject target, String key,
                                 Long resourceId, boolean positive) {
        if (!source.containsKey(key)) return;
        Object raw = source.get(key);
        if (!(raw instanceof Number number))
            throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resourceId);
        double value = number.doubleValue();
        if (!Double.isFinite(value) || (positive ? value <= 0D : value < 0D))
            throw invalidException("CANVAS_REFERENCE_RESOURCE_METADATA_INVALID", resourceId);
        target.put(key, value);
    }

    private void requireProjectionSize(Object value, Object details) {
        int bytes = JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > contextLimits.maxContextBytes()) {
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "ASSISTANT_CONTEXT_TOO_LARGE", "引用内容超过助手上下文限制",
                    Map.of("actualBytes", bytes, "maxBytes", contextLimits.maxContextBytes(),
                            "overflowPolicy", "REJECT", "reference", String.valueOf(details)));
        }
    }

    private JSONObject base(int ordinal, String source, AssistantRequests.ContextRef ref) {
        JSONObject result = new JSONObject();
        result.put("ordinal", ordinal);
        result.put("source", source);
        result.put("projectId", String.valueOf(ref.getProjectId()));
        result.put("workspaceId", String.valueOf(ref.getWorkspaceId()));
        return result;
    }

    private void putText(JSONObject target, String key, String value) {
        if (StrUtil.isNotBlank(value)) target.put(key, value);
    }

    private Long positive(String value, String code, int ordinal) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid(code, ordinal);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            invalid(code, ordinal);
            return null;
        }
    }

    private Long id(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || !value.toString().matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void invalid(String code, Object details) {
        throw invalidException(code, details);
    }

    private AssistantServiceException invalidException(String code, Object details) {
        return new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code,
                "引用内容不可用于助手", details);
    }
}
