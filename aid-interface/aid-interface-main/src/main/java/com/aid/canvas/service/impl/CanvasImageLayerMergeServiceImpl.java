package com.aid.canvas.service.impl;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.service.IOssBusinessService;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasImageLayerMergeService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.aid.image.ImageLayerCompositor;
import com.aid.common.aid.image.ImageRegionProcessor;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 免费的确定性图层合并，不创建媒体任务、不冻结或扣除积分。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasImageLayerMergeServiceImpl implements CanvasImageLayerMergeService {
    private static final String SOURCE_TYPE = "LAYER_MERGE";
    private static final Semaphore PROCESSING_SLOTS = new Semaphore(2);

    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final CanvasResourceService resourceService;
    private final CanvasImageSourceReader imageSourceReader;
    private final IOssBusinessService ossBusinessService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public Map<String, Object> merge(CanvasRequests.ImageLayerMerge request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        validateRequest(request);
        List<Long> referencedIds = referencedIds(request);
        String hash = requestHash(request, userId);
        RLock lock = redissonClient.getLock("canvas:image-layer-merge:" + workspace.getId() + ":" + userId
                + ":" + request.getClientRequestId());
        try {
            if (!lock.tryLock(10, TimeUnit.SECONDS)) throw new ServiceException("相同图层合并正在处理，请稍后重试");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException("图层合并等待已中断");
        }
        try {
            Map<String, Object> recovered = recover(request, userId, hash);
            if (recovered != null) return recovered;
            requireSourceNode(request, referencedIds, false);
            Map<Long, CanvasResource> resources = resources(request.getWorkspaceId(), referencedIds, userId);
            try {
                if (!PROCESSING_SLOTS.tryAcquire(10, TimeUnit.SECONDS)) {
                    throw new ServiceException("图层合并繁忙，请稍后重试");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ServiceException("图层合并等待已中断");
            }
            try {
                return processAndRegister(workspace, request, userId, hash, resources, referencedIds);
            } finally {
                PROCESSING_SLOTS.release();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private Map<String, Object> processAndRegister(Workspace workspace, CanvasRequests.ImageLayerMerge request,
            Long userId, String hash, Map<Long, CanvasResource> resources, List<Long> referencedIds) {
        ImageLayerCompositor.ImageSource background = request.getBackgroundResourceId() == null ? null
                : () -> imageSourceReader.read(resources.get(request.getBackgroundResourceId()));
        List<ImageLayerCompositor.Layer> layers = request.getLayers().stream().map(layer ->
                new ImageLayerCompositor.Layer(layer.getLayerId(),
                        () -> imageSourceReader.read(resources.get(layer.getResourceId())),
                        new ImageLayerCompositor.Rect(layer.getRect().getX(), layer.getRect().getY(),
                                layer.getRect().getWidth(), layer.getRect().getHeight()),
                        layer.getRotationDeg(), layer.getFlipX(), layer.getFlipY(),
                        layer.getOpacity().floatValue(), layer.getVisible())).toList();
        ImageLayerCompositor.Result result;
        try {
            result = ImageLayerCompositor.compose(request.getCanvas().getWidth(), request.getCanvas().getHeight(),
                    background, layers);
        } catch (IllegalArgumentException error) {
            throw new ServiceException(error.getMessage() == null ? "图层合并参数无效" : error.getMessage());
        } catch (IOException error) {
            log.error("画布图层合并编码失败, workspaceId={}, sourceObjectId={}", request.getWorkspaceId(),
                    request.getSourceObjectId(), error);
            throw new ServiceException("图层合并失败，请重试");
        }

        List<OssUploadResponse> uploads = new ArrayList<>(1);
        try {
            uploads.addAll(ossBusinessService.uploadList(List.of(new PngFile(
                    "layer-merge-" + request.getClientRequestId() + ".png", result.png())),
                    "canvas/" + userId + "/layer-merge"));
            if (uploads.size() != 1 || uploads.get(0) == null || uploads.get(0).getUrl() == null
                    || uploads.get(0).getUrl().isBlank()) throw new ServiceException("图层合并上传失败");
            Map<String, Object> response = transactionTemplate.execute(status -> register(
                    workspace, request, userId, hash, result, uploads.get(0), referencedIds));
            if (response == null) throw new ServiceException("图层合并登记失败");
            return response;
        } catch (RuntimeException error) {
            ossBusinessService.deleteListByUrl(uploads.stream().filter(Objects::nonNull)
                    .map(OssUploadResponse::getUrl).filter(Objects::nonNull).toList());
            throw error;
        }
    }

    private Map<String, Object> register(Workspace workspace, CanvasRequests.ImageLayerMerge request,
            Long userId, String hash, ImageLayerCompositor.Result result, OssUploadResponse upload,
            List<Long> referencedIds) {
        Workspace currentWorkspace = accessService.requireWorkspace(request.getWorkspaceId(), userId,
                "OWNER", "EDITOR");
        if (!Objects.equals(currentWorkspace.getProjectId(), workspace.getProjectId())) {
            throw new ServiceException("工作区项目归属已变化");
        }
        requireSourceNode(request, referencedIds, true);
        CanvasResource resource = new CanvasResource();
        resource.setProjectId(currentWorkspace.getProjectId());
        resource.setWorkspaceId(currentWorkspace.getId());
        resource.setOwnerUserId(userId);
        resource.setSourceType(SOURCE_TYPE);
        resource.setSourceId(sourceId(request, userId));
        resource.setMediaType("IMAGE");
        resource.setStorageUrl(upload.getUrl());
        resource.setFileSize(upload.getFileSize());
        resource.setMimeType("image/png");
        resource.setMetadataJson(JSON.toJSONString(metadata(request, hash, result)));
        resource.setStatus("ACTIVE");
        resource.setCreateTime(new Date());
        resource.setUpdateTime(new Date());
        if (canvasMapper.insertCanvasResource(resource) != 1) throw new ServiceException("图层合并登记失败");
        return view(resource, request);
    }

    private Map<String, Object> recover(CanvasRequests.ImageLayerMerge request, Long userId, String hash) {
        CanvasResource resource = canvasMapper.selectCanvasResourceBySource(request.getWorkspaceId(), SOURCE_TYPE,
                sourceId(request, userId));
        if (resource == null) return null;
        if (!"ACTIVE".equals(resource.getStatus())) {
            throw new ServiceException("原图层合并结果已失效，请使用新的请求标识重试");
        }
        JSONObject metadata = JSON.parseObject(resource.getMetadataJson());
        if (metadata == null || !Objects.equals(resource.getOwnerUserId(), userId)
                || !Objects.equals(metadata.getString("mergeRequestHash"), hash)) {
            throw new ServiceException("图层合并请求标识已被其他参数使用");
        }
        return view(resource, request);
    }

    private void requireSourceNode(CanvasRequests.ImageLayerMerge request, List<Long> referencedIds, boolean lockRow) {
        CanvasObject source = lockRow
                ? canvasMapper.selectCanvasObjectForUpdate(request.getWorkspaceId(), request.getSourceObjectId())
                : canvasMapper.selectCanvasObject(request.getWorkspaceId(), request.getSourceObjectId());
        if (source == null || !"NODE".equals(source.getObjectType()) || !Objects.equals(source.getDeleted(), 0)
                || !Objects.equals(source.getObjectVersion(), request.getExpectedVersion())) {
            throw new ServiceException("源节点版本已变化");
        }
        JSONObject content;
        try {
            content = JSON.parseObject(source.getContentJson());
        } catch (RuntimeException error) {
            throw new ServiceException("源节点内容无效");
        }
        JSONArray stored = content == null ? null : content.getJSONArray("resourceIds");
        Set<String> storedIds = new LinkedHashSet<>();
        if (stored != null) stored.forEach(id -> storedIds.add(String.valueOf(id)));
        if (referencedIds.stream().anyMatch(id -> !storedIds.contains(String.valueOf(id)))) {
            throw new ServiceException("源节点未引用全部图层资源");
        }
    }

    private Map<Long, CanvasResource> resources(Long workspaceId, List<Long> ids, Long userId) {
        List<CanvasResource> values = resourceService.requireResources(workspaceId,
                new ArrayList<>(new LinkedHashSet<>(ids)), userId);
        Map<Long, CanvasResource> result = new LinkedHashMap<>();
        for (CanvasResource resource : values) {
            if (!"IMAGE".equals(resource.getMediaType()) || !"ACTIVE".equals(resource.getStatus())) {
                throw new ServiceException("图层资源不是有效图片");
            }
            result.put(resource.getId(), resource);
        }
        return result;
    }

    private static List<Long> referencedIds(CanvasRequests.ImageLayerMerge request) {
        List<Long> ids = new ArrayList<>();
        if (request.getBackgroundResourceId() != null) ids.add(request.getBackgroundResourceId());
        request.getLayers().forEach(layer -> ids.add(layer.getResourceId()));
        return ids;
    }

    private static void validateRequest(CanvasRequests.ImageLayerMerge request) {
        long pixels = (long) request.getCanvas().getWidth() * request.getCanvas().getHeight();
        if (new LinkedHashSet<>(referencedIds(request)).size() > 25) {
            throw new ServiceException("合并图片资源数量超过限制");
        }
        if (pixels > ImageRegionProcessor.MAX_PIXELS) throw new ServiceException("合成画布像素超过限制");
        Set<String> layerIds = new LinkedHashSet<>();
        for (CanvasRequests.ImageLayer layer : request.getLayers()) {
            if (!layerIds.add(layer.getLayerId())) throw new ServiceException("图层标识重复");
            double right = layer.getRect().getX() + layer.getRect().getWidth();
            double bottom = layer.getRect().getY() + layer.getRect().getHeight();
            if (right > 1.000001 || bottom > 1.000001) throw new ServiceException("图层位置超出画布");
        }
    }

    private static Map<String, Object> metadata(CanvasRequests.ImageLayerMerge request, String hash,
            ImageLayerCompositor.Result result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mergeRequestHash", hash);
        metadata.put("clientRequestId", request.getClientRequestId());
        metadata.put("sourceObjectId", request.getSourceObjectId());
        metadata.put("sourceObjectVersion", request.getExpectedVersion());
        metadata.put("backgroundResourceId", request.getBackgroundResourceId());
        metadata.put("width", result.width());
        metadata.put("height", result.height());
        metadata.put("layers", canonicalLayers(request));
        return metadata;
    }

    private static Map<String, Object> view(CanvasResource resource, CanvasRequests.ImageLayerMerge request) {
        JSONObject metadata = JSON.parseObject(resource.getMetadataJson());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resourceId", Long.toString(resource.getId()));
        response.put("resourceUrl", resource.getStorageUrl());
        response.put("mimeType", resource.getMimeType());
        response.put("width", metadata.getIntValue("width"));
        response.put("height", metadata.getIntValue("height"));
        response.put("sourceObjectId", request.getSourceObjectId());
        response.put("clientRequestId", request.getClientRequestId());
        return response;
    }

    private static long sourceId(CanvasRequests.ImageLayerMerge request, Long userId) {
        String value = request.getWorkspaceId() + ":" + userId + ":" + request.getClientRequestId();
        return Long.parseUnsignedLong(sha256(value).substring(0, 15), 16) + 1;
    }

    private static String requestHash(CanvasRequests.ImageLayerMerge request, Long userId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("actor", userId);
        input.put("workspaceId", request.getWorkspaceId());
        input.put("sourceObjectId", request.getSourceObjectId());
        input.put("expectedVersion", request.getExpectedVersion());
        input.put("canvas", Map.of("width", request.getCanvas().getWidth(), "height", request.getCanvas().getHeight()));
        input.put("backgroundResourceId", request.getBackgroundResourceId());
        input.put("layers", canonicalLayers(request));
        return sha256(JSON.toJSONString(input));
    }

    private static List<Map<String, Object>> canonicalLayers(CanvasRequests.ImageLayerMerge request) {
        return request.getLayers().stream().map(layer -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("layerId", layer.getLayerId());
            value.put("resourceId", layer.getResourceId());
            value.put("rect", Map.of("x", layer.getRect().getX(), "y", layer.getRect().getY(),
                    "width", layer.getRect().getWidth(), "height", layer.getRect().getHeight()));
            value.put("rotationDeg", layer.getRotationDeg());
            value.put("flipX", layer.getFlipX());
            value.put("flipY", layer.getFlipY());
            value.put("opacity", layer.getOpacity());
            value.put("visible", layer.getVisible());
            return value;
        }).toList();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record PngFile(String name, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "image/png"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
