package com.aid.generation.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.mapper.AidComicEpisodeMapper;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.runtime.CanvasResourceRuntimeScopeAccess;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.exception.ServiceException;
import com.aid.generation.dto.GenerationRequests;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.AuthorizedImageAssetSnapshot;
import com.aid.media.service.MediaPreparationErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 批量鉴权资源编号并转换为媒体服务可消费的可信引用。 */
@Component
@RequiredArgsConstructor
public class GenerationResourceResolver {
    private static final int MAX_PARAMETER_DEPTH = 8;
    private static final int MAX_PARAMETER_ITEMS = 512;
    private static final int MAX_PARAMETER_STRING_LENGTH = 100000;
    private static final Set<String> FORBIDDEN_PARAMETER_KEYS = Set.of(
            "userid", "user_id", "system", "systemprompt", "system_prompt", "billingexempt",
            "billing_exempt", "billing", "businessfunccode", "business_func_code", "funccode",
            "func_code", "callid", "call_id", "biztaskid", "biz_task_id", "biztasktype",
            "biz_task_type", "baseurl", "base_url", "authorization", "apikey", "api_key",
            "token", "secret", "endpoint", "callback", "callbackurl", "callback_url", "webhook",
            "referenceimages", "reference_images", "referencevideos", "reference_videos",
            "imageurl", "image_url", "videourl", "video_url", "audiourl", "audio_url");

    private final CanvasResourceService canvasResourceService;
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService canvasAccessService;
    private final AidComicProjectMapper projectMapper;
    private final AidComicEpisodeMapper episodeMapper;

    public Long resolveProjectScope(Long workspaceId, Long projectId, Long episodeId, Long userId) {
        Long resolvedProjectId = projectId;
        if (workspaceId != null) {
            Workspace workspace = canvasAccessService.requireWorkspace(workspaceId, userId,
                    "OWNER", "EDITOR", "VIEWER");
            if (projectId != null && !Objects.equals(projectId, workspace.getProjectId())) {
                throw new ServiceException("工作区与项目不一致");
            }
            resolvedProjectId = workspace.getProjectId();
        } else if (projectId != null) {
            AidComicProject project = projectMapper.selectOne(Wrappers.<AidComicProject>lambdaQuery()
                    .select(AidComicProject::getId, AidComicProject::getUserId, AidComicProject::getProjectType)
                    .eq(AidComicProject::getId, projectId).eq(AidComicProject::getDelFlag, "0"));
            if (project == null) throw new ServiceException("项目不存在或无权访问");
            if (!Objects.equals(project.getUserId(), userId)) {
                if (!"canvas".equalsIgnoreCase(project.getProjectType())) {
                    throw new ServiceException("项目不存在或无权访问");
                }
                canvasAccessService.requireProject(projectId, userId);
            }
        }
        if (episodeId != null && episodeId > 0) {
            if (resolvedProjectId == null) throw new ServiceException("指定剧集时项目必填");
            AidComicEpisode episode = episodeMapper.selectOne(Wrappers.<AidComicEpisode>lambdaQuery()
                    .select(AidComicEpisode::getId)
                    .eq(AidComicEpisode::getId, episodeId)
                    .eq(AidComicEpisode::getProjectId, resolvedProjectId)
                    .eq(AidComicEpisode::getDelFlag, "0"));
            if (episode == null) throw new ServiceException("剧集不存在或不属于该项目");
        }
        return resolvedProjectId;
    }

    public List<ResolvedResource> resolve(Long workspaceId,
                                          List<GenerationRequests.ResourceReference> references,
                                          Long userId) {
        if (CollectionUtil.isEmpty(references)) return List.of();
        if (workspaceId == null) throw new ServiceException("引用素材时工作区必填");
        Set<String> mentions = new LinkedHashSet<>();
        List<Long> ids = new ArrayList<>(references.size());
        for (GenerationRequests.ResourceReference reference : references) {
            String mention = StrUtil.trim(reference.getMention());
            if (!mentions.add(mention)) throw new ServiceException("引用别名不能重复");
            ids.add(reference.getResourceId());
        }
        List<CanvasResource> resources = canvasResourceService.requireResources(workspaceId, ids, userId);
        List<ResolvedResource> result = new ArrayList<>(resources.size());
        for (int i = 0; i < resources.size(); i++) {
            CanvasResource resource = resources.get(i);
            GenerationRequests.ResourceReference reference = references.get(i);
            if (!"ACTIVE".equals(resource.getStatus()) || StrUtil.isBlank(resource.getStorageUrl())) {
                throw new ServiceException("引用素材不可用");
            }
            if (StrUtil.isBlank(resource.getMediaType())) throw new ServiceException("引用素材类型缺失");
            result.add(new ResolvedResource(reference.getResourceId(), reference.getMention().trim(),
                    reference.getRole().trim().toUpperCase(Locale.ROOT),
                    resource.getMediaType().toUpperCase(Locale.ROOT), resource.getStorageUrl(),
                    resource.getFileSize(), resource.getMimeType(), resource.getMetadataJson(),
                    CanvasResourceRuntimeScopeAccess.identityHash(resource)));
        }
        return result;
    }

    /** 解析图片编辑专用资源；资源归属、工作区权限及 ACTIVE 状态与普通引用共用同一校验。 */
    public ResolvedResource resolveImage(Long workspaceId, Long resourceId, String role, Long userId) {
        if (workspaceId == null || resourceId == null) throw new ServiceException("图片资源缺失");
        GenerationRequests.ResourceReference reference = new GenerationRequests.ResourceReference();
        reference.setResourceId(resourceId);
        reference.setMention(role.toLowerCase(Locale.ROOT));
        reference.setRole("REFERENCE");
        ResolvedResource resolved = resolve(workspaceId, List.of(reference), userId).get(0);
        if (!"IMAGE".equals(resolved.mediaType())) throw new ServiceException("图片资源类型错误");
        return new ResolvedResource(resolved.resourceId(), resolved.mention(), role, resolved.mediaType(),
                resolved.url(), resolved.fileSize(), resolved.mimeType(), resolved.metadataJson(),
                resolved.contentHash());
    }

    /** 校验节点版本后立即冻结可见文字；偏移量按 JavaScript UTF-16 字符位置定义。 */
    public String resolveCanvasTextReferences(Long workspaceId,
            List<GenerationRequests.CanvasTextReference> references, Long userId) {
        if (CollectionUtil.isEmpty(references)) return "";
        if (workspaceId == null) throw new ServiceException("节点引用缺少工作区", com.aid.common.constant.HttpStatus.BAD_REQUEST);
        canvasAccessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR", "VIEWER");
        Set<String> mentions = new LinkedHashSet<>();
        StringBuilder context = new StringBuilder();
        for (GenerationRequests.CanvasTextReference reference : references) {
            if (reference == null || reference.getObjectId() == null || reference.getExpectedVersion() == null
                    || reference.getMention() == null) throw new ServiceException("节点引用字段缺失", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            if (!mentions.add(reference.getMention())) throw new ServiceException("节点引用别名不能重复", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            CanvasObject object = canvasMapper.selectCanvasObject(workspaceId, reference.getObjectId());
            if (object == null || object.getDeleted() == 1 || !"NODE".equals(object.getObjectType())
                    || !Objects.equals(object.getObjectVersion(), reference.getExpectedVersion())) {
                throw new ServiceException("引用节点不存在或版本已变化", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            }
            JSONObject payload = JSON.parseObject(object.getContentJson());
            if (!"TEXT".equalsIgnoreCase(payload.getString("nodeType"))) throw new ServiceException("引用节点不是文本节点", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            Object raw = payload.containsKey("textContent") ? payload.get("textContent") : payload.get("content");
            if (!(raw instanceof String body)) throw new ServiceException("引用节点正文无效", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            String plain = CanvasPlainText.normalize(body, payload.getString("textFormat"));
            Integer start = reference.getSelectionStart();
            Integer end = reference.getSelectionEnd();
            if ((start == null) != (end == null) || (start != null && (start < 0 || start >= end || end > plain.length()
                    || splitsSurrogate(plain, start) || splitsSurrogate(plain, end)))) {
                throw new ServiceException("节点引用选区越界", com.aid.common.constant.HttpStatus.BAD_REQUEST);
            }
            if (start != null) plain = plain.substring(start, end);
            context.append("\n[节点 @").append(reference.getMention()).append(" 版本 ")
                    .append(reference.getExpectedVersion()).append("]\n").append(plain).append("\n");
            if (context.length() > 100000) throw new ServiceException("节点引用文字超过上下文限制", com.aid.common.constant.HttpStatus.BAD_REQUEST);
        }
        return context.toString();
    }

    private static boolean splitsSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    public ResolvedMessages resolveMessages(
            Long workspaceId, List<GenerationRequests.Message> messages, Long userId) {
        if (CollectionUtil.isEmpty(messages)) return new ResolvedMessages(List.of(), List.of());
        List<Long> resourceIds = new ArrayList<>();
        for (GenerationRequests.Message message : messages) {
            if (CollectionUtil.isEmpty(message.getParts())) continue;
            for (GenerationRequests.Part part : message.getParts()) {
                if (!"text".equalsIgnoreCase(part.getType())) {
                    if (part.getResourceId() == null) throw new ServiceException("媒体内容缺少资源ID");
                    resourceIds.add(part.getResourceId());
                } else if (part.getResourceId() != null) {
                    throw new ServiceException("文本内容不能引用资源");
                }
            }
        }
        Map<Long, CanvasResource> indexed = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(resourceIds)) {
            if (workspaceId == null) throw new ServiceException("引用素材时工作区必填");
            List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(resourceIds));
            for (CanvasResource resource : canvasResourceService.requireResources(workspaceId, uniqueIds, userId)) {
                indexed.put(resource.getId(), resource);
            }
        }
        List<MediaTextGenerateRequest.TextMessageItem> result = new ArrayList<>(messages.size());
        List<GenerationConversationStore.StoredMessage> stored = new ArrayList<>(messages.size());
        for (GenerationRequests.Message message : messages) {
            if ("system".equalsIgnoreCase(message.getRole())) throw new ServiceException("系统提示词不可覆盖");
            MediaTextGenerateRequest.TextMessageItem target = new MediaTextGenerateRequest.TextMessageItem();
            target.setRole(message.getRole().trim().toLowerCase(Locale.ROOT));
            target.setContent(message.getContent());
            target.setToolCallId(message.getToolCallId());
            List<MediaTextGenerateRequest.TextContentPart> parts = new ArrayList<>();
            List<GenerationConversationStore.StoredPart> storedParts = new ArrayList<>();
            if (CollectionUtil.isNotEmpty(message.getParts())) {
                for (GenerationRequests.Part part : message.getParts()) {
                    ResolvedPart resolved = resolvePart(part, indexed);
                    parts.add(resolved.providerPart());
                    storedParts.add(resolved.storedPart());
                }
            }
            target.setParts(parts);
            result.add(target);
            stored.add(new GenerationConversationStore.StoredMessage(target.getRole(), target.getContent(),
                    target.getToolCallId(), storedParts));
        }
        return new ResolvedMessages(result, stored);
    }

    public Map<String, Object> sanitizeParameters(Map<String, Object> parameters) {
        Map<String, Object> result = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        inspectParameters(result, 0, new int[]{0});
        return result;
    }

    public List<MediaTextGenerateRequest.TextContentPart> toTextParts(List<ResolvedResource> resources) {
        List<MediaTextGenerateRequest.TextContentPart> result = new ArrayList<>();
        for (ResolvedResource resource : resources) {
            if (!"REFERENCE".equals(resource.role())) throw new ServiceException("文本素材角色错误");
            MediaTextGenerateRequest.TextContentPart part = new MediaTextGenerateRequest.TextContentPart();
            part.setType(resource.mediaType().toLowerCase(Locale.ROOT));
            part.setUrl(resource.url());
            part.setSizeBytes(resource.fileSize());
            part.setMimeType(resource.mimeType());
            applyTrustedMetadata(part, resource.metadataJson());
            result.add(part);
        }
        return result;
    }

    public String resolveMentions(String prompt, List<ResolvedResource> resources) {
        if (StrUtil.isBlank(prompt) || CollectionUtil.isEmpty(resources)) return prompt;
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (ResolvedResource resource : resources) {
            int index = counters.merge(resource.mediaType(), 1, Integer::sum);
            String type = switch (resource.mediaType()) {
                case "IMAGE" -> "图片";
                case "VIDEO" -> "视频";
                case "AUDIO" -> "音频";
                case "DOCUMENT" -> "文档";
                default -> "素材";
            };
            labels.put(resource.mention(), type + index);
        }
        String resolved = prompt;
        List<String> aliases = new ArrayList<>(labels.keySet());
        aliases.sort(Comparator.comparingInt(String::length).reversed());
        for (String alias : aliases) {
            Pattern token = Pattern.compile("(?<![\\p{L}\\p{N}_-])@" + Pattern.quote(alias)
                    + "(?![\\p{L}\\p{N}_-])");
            resolved = token.matcher(resolved).replaceAll(Matcher.quoteReplacement(labels.get(alias)));
        }
        return resolved;
    }

    private ResolvedPart resolvePart(GenerationRequests.Part part, Map<Long, CanvasResource> indexed) {
        MediaTextGenerateRequest.TextContentPart target = new MediaTextGenerateRequest.TextContentPart();
        String type = part.getType().trim().toLowerCase(Locale.ROOT);
        target.setType(type);
        if ("text".equals(type)) {
            if (StrUtil.isBlank(part.getText())) throw new ServiceException("文本内容不能为空");
            target.setText(part.getText());
        } else {
            CanvasResource resource = indexed.get(part.getResourceId());
            if (resource == null || !type.equalsIgnoreCase(resource.getMediaType())) {
                throw new ServiceException("内容类型与资源不一致");
            }
            if (!"ACTIVE".equals(resource.getStatus()) || StrUtil.isBlank(resource.getStorageUrl())) {
                throw new ServiceException("引用素材不可用");
            }
            target.setUrl(resource.getStorageUrl());
            target.setMimeType(resource.getMimeType());
            target.setDetail(part.getDetail());
            target.setMediaResolution(part.getMediaResolution());
            target.setSizeBytes(resource.getFileSize());
            applyTrustedMetadata(target, resource.getMetadataJson());
        }
        CanvasResource resource = "text".equals(type) ? null : indexed.get(part.getResourceId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "detail", part.getDetail());
        put(metadata, "mediaResolution", target.getMediaResolution());
        put(metadata, "fps", target.getFps());
        put(metadata, "durationSeconds", target.getDurationSeconds());
        put(metadata, "sizeBytes", target.getSizeBytes());
        put(metadata, "pageCount", target.getPageCount());
        put(metadata, "width", target.getWidth());
        put(metadata, "height", target.getHeight());
        GenerationConversationStore.StoredPart stored = new GenerationConversationStore.StoredPart(
                type.toUpperCase(Locale.ROOT), target.getText(), part.getResourceId(), "REFERENCE",
                resource == null ? null : resource.getStorageUrl(), target.getMimeType(), metadata);
        return new ResolvedPart(target, stored);
    }

    private void inspectParameters(Object value, int depth, int[] count) {
        if (depth > MAX_PARAMETER_DEPTH) throw new ServiceException("参数嵌套过深");
        if (++count[0] > MAX_PARAMETER_ITEMS) throw new ServiceException("参数项数过多");
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "").toLowerCase(Locale.ROOT);
                if (FORBIDDEN_PARAMETER_KEYS.contains(key) || key.endsWith("url") || key.endsWith("_url")) {
                    throw new ServiceException("参数包含受保护字段");
                }
                inspectParameters(entry.getValue(), depth + 1, count);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) inspectParameters(item, depth + 1, count);
        } else if (value instanceof String text) {
            if (text.length() > MAX_PARAMETER_STRING_LENGTH) throw new ServiceException("参数字符串过长");
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("http://") || normalized.startsWith("https://")
                    || normalized.startsWith("data:")) {
                throw new ServiceException("参数不能包含裸资源地址");
            }
        }
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static void applyTrustedMetadata(MediaTextGenerateRequest.TextContentPart target, String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) return;
        JSONObject metadata;
        try {
            metadata = JSON.parseObject(metadataJson);
        } catch (RuntimeException error) {
            throw new ServiceException("素材元数据无效");
        }
        target.setPageCount(metadataInteger(metadata, "pageCount"));
        java.math.BigDecimal seconds = metadataNumber(metadata, "durationSeconds");
        if (seconds == null) {
            java.math.BigDecimal millis = metadataNumber(metadata, "durationMs");
            if (millis != null) seconds = millis.movePointLeft(3);
        }
        target.setDurationSeconds(finiteMetadata(seconds));
        target.setFps(finiteMetadata(metadataNumber(metadata, "fps")));
        target.setWidth(metadataInteger(metadata, "width"));
        target.setHeight(metadataInteger(metadata, "height"));
    }

    /** Canonical fields or the existing persisted output metadata, never request estimates. */
    private static java.math.BigDecimal metadataNumber(JSONObject metadata, String key) {
        if (metadata == null) return null;
        Object raw = metadata.get(key);
        if (raw == null && metadata.get("metadata") instanceof JSONObject nested) raw = nested.get(key);
        if (raw == null) return null;
        try {
            if (!(raw instanceof Number || raw instanceof String)) return null;
            java.math.BigDecimal value = new java.math.BigDecimal(String.valueOf(raw));
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    private static Integer metadataInteger(JSONObject metadata, String key) {
        java.math.BigDecimal value = metadataNumber(metadata, key);
        if (value == null || value.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
        try { return value.intValueExact(); } catch (ArithmeticException invalid) { return null; }
    }

    private static Double finiteMetadata(java.math.BigDecimal value) {
        if (value == null) return null;
        double number = value.doubleValue();
        return Double.isFinite(number) && number > 0 ? number : null;
    }

    /** Quote and submit consume the same authorized metadata; missing bytes are prepared separately. */
    public static Map<String, AuthorizedImageAssetSnapshot> authorizedImageSnapshots(List<ResolvedResource> resources) {
        Map<String, AuthorizedImageAssetSnapshot> snapshots = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (ResolvedResource resource : resources) {
            AuthorizedImageAssetSnapshot snapshot = resource.imageSnapshot();
            try {
                snapshot.requireValid(resource.url(), "图片素材");
            } catch (ServiceException rejected) {
                if (MediaPreparationErrorCode.read(rejected.getDetailMessage())
                        != MediaPreparationErrorCode.MEDIA_INPUT_METADATA_REQUIRED) throw rejected;
                missing.add(Long.toString(resource.resourceId()));
            }
            snapshots.put(resource.url(), snapshot);
        }
        if (!missing.isEmpty()) throw MediaPreparationErrorCode.imageMetadataRequired("references", new ArrayList<>(missing));
        return snapshots;
    }

    public record ResolvedResource(Long resourceId, String mention, String role, String mediaType,
                                   String url, Long fileSize, String mimeType, String metadataJson,
                                   String contentHash) {
        /** Project server-read metadata without making it a requirement for unrelated providers. */
        public AuthorizedImageAssetSnapshot imageSnapshot() {
            JSONObject metadata;
            Integer width;
            Integer height;
            try {
                metadata = StrUtil.isBlank(metadataJson) ? null : JSON.parseObject(metadataJson);
                width = metadataInteger(metadata, "width");
                height = metadataInteger(metadata, "height");
            } catch (RuntimeException error) {
                metadata = null;
                width = null;
                height = null;
            }
            AuthorizedImageAssetSnapshot snapshot = new AuthorizedImageAssetSnapshot();
            snapshot.setResourceId(resourceId);
            snapshot.setLocator(url);
            snapshot.setFileSize(fileSize);
            snapshot.setMimeType(mimeType);
            snapshot.setWidth(width);
            snapshot.setHeight(height);
            snapshot.setResourceIdentityDigest(contentHash);
            return snapshot;
        }

        public AuthorizedImageAssetSnapshot authorizedImageSnapshot() {
            AuthorizedImageAssetSnapshot snapshot = imageSnapshot();
            try {
                snapshot.requireValid(url, "图片素材");
            } catch (ServiceException rejected) {
                if (MediaPreparationErrorCode.read(rejected.getDetailMessage())
                        != MediaPreparationErrorCode.MEDIA_INPUT_METADATA_REQUIRED) throw rejected;
                String field = switch (role) {
                    case "SOURCE" -> "imageEdit.sourceResourceId";
                    case "MASK" -> "imageEdit.maskResourceId";
                    default -> throw new ServiceException("图片素材角色无效");
                };
                throw MediaPreparationErrorCode.imageMetadataRequired(field, List.of(Long.toString(resourceId)));
            }
            return snapshot;
        }
    }

    public record ResolvedMessages(List<MediaTextGenerateRequest.TextMessageItem> providerMessages,
                                   List<GenerationConversationStore.StoredMessage> storedMessages) { }

    private record ResolvedPart(MediaTextGenerateRequest.TextContentPart providerPart,
                                GenerationConversationStore.StoredPart storedPart) { }
}
