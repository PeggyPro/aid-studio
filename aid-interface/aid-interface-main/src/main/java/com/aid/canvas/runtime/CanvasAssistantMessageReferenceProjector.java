package com.aid.canvas.runtime;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.service.AssistantMessageReferenceProjector;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.NodeResultSelection;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Presents frozen Canvas message references without exposing stale or off-site media URLs. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantMessageReferenceProjector implements AssistantMessageReferenceProjector {
    private static final Set<String> MEDIA_KINDS =
            Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT");

    private final CanvasMapper canvasMapper;
    private final CanvasResourceService resourceService;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public boolean supports(String kind) {
        return "FREE_CANVAS".equals(kind) || "CANVAS_RESOURCE".equals(kind);
    }

    @Override
    public Snapshot snapshot(Long userId, ResolvedContextRef frozen) {
        if (frozen == null || frozen.contextRef() == null) return null;
        AssistantRequests.ContextRef ref = frozen.contextRef();
        if ("CANVAS_RESOURCE".equals(ref.getKind())) {
            CanvasResource resource = authorizedResource(userId, ref.getWorkspaceId(), ref.getResourceId());
            if (resource == null || !Objects.equals(ref.getContentHash(),
                    CanvasResourceRuntimeScopeAccess.identityHash(resource))) return null;
            return new Snapshot(normalizedOrOther(resource.getMediaType()), resource.getId(), ref.getContentHash());
        }
        if (!"FREE_CANVAS".equals(ref.getKind()) || StrUtil.isBlank(ref.getObjectId())) return null;
        CanvasObject node = exactNode(ref);
        if (node == null) return null;
        JSONObject content = object(node.getContentJson());
        String kind = content == null ? "OTHER" : normalizedOrOther(content.getString("nodeType"));
        if (!"IMAGE".equals(kind)) return new Snapshot(kind, null, null);
        NodeResultSelection selection = canvasMapper.selectNodeResultSelection(ref.getWorkspaceId(), ref.getObjectId());
        CanvasResource selected;
        if (selection == null) {
            selected = firstNodeImage(userId, ref, content);
        } else {
            if (!Objects.equals(selection.getProjectId(), ref.getProjectId())
                    || !Objects.equals(selection.getWorkspaceId(), ref.getWorkspaceId())
                    || !Objects.equals(selection.getObjectId(), ref.getObjectId())
                    || selection.getTaskId() == null || selection.getTaskId() < 1
                    || selection.getResultId() == null || selection.getResultId() < 1
                    || selection.getInputObjectVersion() == null || selection.getInputObjectVersion() < 1
                    || selection.getInputObjectVersion() > node.getObjectVersion()
                    || selection.getSelectionVersion() == null || selection.getSelectionVersion() < 1) {
                return new Snapshot(kind, null, null);
            }
            var selectedResult = canvasMapper.selectMediaResultForNode(
                    ref.getWorkspaceId(), ref.getObjectId(), selection.getResultId());
            if (selectedResult == null
                    || !Objects.equals(selection.getResultId(), numericId(selectedResult.get("resultId")))
                    || !Objects.equals(selection.getTaskId(), numericId(selectedResult.get("taskId")))
                    || !Objects.equals(selection.getInputObjectVersion(),
                    numericId(selectedResult.get("inputObjectVersion")))) {
                return new Snapshot(kind, null, null);
            }
            selected = canvasMapper.selectCanvasResourceBySource(
                    ref.getWorkspaceId(), "MEDIA_RESULT", selection.getResultId());
            if (selected == null || !"MEDIA_RESULT".equals(selected.getSourceType())
                    || !Objects.equals(selection.getResultId(), selected.getSourceId())) {
                return new Snapshot(kind, null, null);
            }
        }
        if (!availableResource(selected, ref.getProjectId(), ref.getWorkspaceId())) {
            return new Snapshot(kind, null, null);
        }
        String hash = CanvasResourceRuntimeScopeAccess.identityHash(selected);
        return new Snapshot(kind, selected.getId(), hash);
    }

    private CanvasResource firstNodeImage(Long userId, AssistantRequests.ContextRef ref, JSONObject content) {
        JSONArray raw = content == null ? null : content.getJSONArray("resourceIds");
        if (raw == null || raw.isEmpty() || raw.size() > 25) return null;
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Object value : raw) {
            String id = String.valueOf(value);
            if (!id.matches("[1-9][0-9]{0,18}")) return null;
            try {
                if (!unique.add(Long.valueOf(id))) return null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        List<CanvasResource> resources;
        try {
            resources = resourceService.requireResources(
                    ref.getWorkspaceId(), new ArrayList<>(unique), userId);
        } catch (RuntimeException unavailable) {
            return null;
        }
        return resources.stream().filter(resource -> "IMAGE".equals(normalizedKind(resource.getMediaType())))
                .filter(resource -> availableResource(resource, ref.getProjectId(), ref.getWorkspaceId()))
                .findFirst().orElse(null);
    }

    @Override
    public Projection project(Long userId, AssistantRequests.ContextRef frozen,
                              ResolvedContextRef current, Snapshot snapshot) {
        if (frozen == null || current == null || current.contextRef() == null) return null;
        if ("FREE_CANVAS".equals(frozen.getKind())) return node(userId, frozen, current, snapshot);
        if ("CANVAS_RESOURCE".equals(frozen.getKind())) return resource(userId, frozen, current, snapshot);
        return null;
    }

    private Projection node(Long userId, AssistantRequests.ContextRef frozen,
                            ResolvedContextRef current, Snapshot snapshot) {
        if (StrUtil.isBlank(frozen.getObjectId())) return new Projection("OTHER", null);
        CanvasObject node = exactNode(current.contextRef());
        if (node == null || !Objects.equals(node.getProjectId(), current.contextRef().getProjectId())) return null;
        JSONObject content = object(node.getContentJson());
        String currentKind = content == null ? "OTHER" : normalizedOrOther(content.getString("nodeType"));
        if (snapshot == null) return new Projection(currentKind, null);
        if (snapshot.previewResourceId() == null) return new Projection(snapshot.mediaKind(), null);
        return new Projection(snapshot.mediaKind(), previewUrl(userId, frozen.getWorkspaceId(), snapshot));
    }

    private Projection resource(Long userId, AssistantRequests.ContextRef frozen,
                                ResolvedContextRef current, Snapshot snapshot) {
        if (frozen.getWorkspaceId() == null || frozen.getResourceId() == null) return null;
        CanvasResource resource = authorizedResource(userId, frozen.getWorkspaceId(), frozen.getResourceId());
        if (resource == null) return null;
        String kind = normalizedOrOther(resource.getMediaType());
        boolean exactFrozenResource = Objects.equals(frozen.getContentHash(),
                current.contextRef().getContentHash())
                && Objects.equals(frozen.getContentHash(),
                CanvasResourceRuntimeScopeAccess.identityHash(resource));
        if (!exactFrozenResource) return null;
        Snapshot exact = snapshot == null
                ? new Snapshot(kind, resource.getId(), frozen.getContentHash()) : snapshot;
        return new Projection(kind, "IMAGE".equals(kind)
                ? previewUrl(userId, frozen.getWorkspaceId(), exact) : null);
    }

    private String previewUrl(Long userId, Long workspaceId, Snapshot snapshot) {
        if (snapshot == null || snapshot.previewResourceId() == null
                || StrUtil.isBlank(snapshot.previewContentHash())) return null;
        CanvasResource resource = authorizedResource(userId, workspaceId, snapshot.previewResourceId());
        if (resource == null || !"IMAGE".equals(normalizedKind(resource.getMediaType()))
                || !Objects.equals(snapshot.previewContentHash(),
                CanvasResourceRuntimeScopeAccess.identityHash(resource))
                || !mediaUrlResolver.isSiteMediaUrl(resource.getStorageUrl())) return null;
        String candidate = mediaUrlResolver.toFullUrl(resource.getStorageUrl());
        return mediaUrlResolver.isSiteMediaUrl(candidate) ? candidate : null;
    }

    private CanvasResource authorizedResource(Long userId, Long workspaceId, Long resourceId) {
        if (workspaceId == null || resourceId == null) return null;
        try {
            return resourceService.requireResources(workspaceId, List.of(resourceId), userId).get(0);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private CanvasObject exactNode(AssistantRequests.ContextRef ref) {
        CanvasObject node = canvasMapper.selectCanvasObject(ref.getWorkspaceId(), ref.getObjectId());
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getWorkspaceId(), ref.getWorkspaceId())
                || !Objects.equals(node.getProjectId(), ref.getProjectId())
                || !Objects.equals(String.valueOf(node.getObjectVersion()), ref.getVersion())) return null;
        return node;
    }

    private boolean availableResource(CanvasResource resource, Long projectId, Long workspaceId) {
        return resource != null && "ACTIVE".equals(resource.getStatus())
                && Objects.equals(resource.getProjectId(), projectId)
                && Objects.equals(resource.getWorkspaceId(), workspaceId)
                && StrUtil.isNotBlank(resource.getStorageUrl())
                && StrUtil.isNotBlank(resource.getMediaType());
    }

    private JSONObject object(String value) {
        try {
            return JSON.parseObject(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Long numericId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || !value.toString().matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private String normalizedOrOther(String value) {
        String kind = normalizedKind(value);
        return kind == null ? "OTHER" : kind;
    }

    private String normalizedKind(String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
        if ("VOICE".equals(normalized) || "MUSIC".equals(normalized)) return "AUDIO";
        return MEDIA_KINDS.contains(normalized) ? normalized : null;
    }
}
