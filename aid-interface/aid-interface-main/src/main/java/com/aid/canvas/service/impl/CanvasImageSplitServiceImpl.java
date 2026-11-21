package com.aid.canvas.service.impl;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.service.IOssBusinessService;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasImageSplitService;
import com.aid.common.aid.image.ImageRegionProcessor;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
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
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/** 只执行本地像素裁切，结果通过已有对象存储与画布素材表保存。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasImageSplitServiceImpl implements CanvasImageSplitService {
    private static final String SOURCE_TYPE = "SPLIT";
    private static final Semaphore PROCESSING_SLOTS = new Semaphore(2);
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final IOssBusinessService ossBusinessService;
    private final CanvasImageSourceReader imageSourceReader;
    private final OssConfigManager ossConfigManager;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public Map<String, Object> split(CanvasRequests.ImageSplit request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        List<ImageRegionProcessor.Cell> cells = gridCells(request);
        List<ImageRegionProcessor.Region> regions = pixelRegions(request);
        String requestHash = requestHash(request, userId, cells, regions);
        RLock lock = redissonClient.getLock("canvas:image-split:" + workspace.getId() + ":" + userId
                + ":" + request.getClientRequestId());
        try {
            if (!lock.tryLock(10, TimeUnit.SECONDS)) throw new ServiceException("切图正在处理");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException("切图等待已中断");
        }
        try {
            requireSourceNode(request, false);
            CanvasResource sourceResource = canvasMapper.selectCanvasResource(request.getSourceResourceId());
            if (sourceResource == null || !Objects.equals(sourceResource.getWorkspaceId(), workspace.getId())
                    || !"IMAGE".equals(sourceResource.getMediaType())) {
                throw new ServiceException("源图片素材无效");
            }
            int expectedCount = "GRID".equals(request.getMode()) ? cells.size() : regions.size();
            Map<String, Object> recovered = recover(request, userId, requestHash, expectedCount);
            if (recovered != null) return recovered;

            try {
                if (!PROCESSING_SLOTS.tryAcquire(10, TimeUnit.SECONDS)) {
                    throw new ServiceException("切图繁忙，请重试");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ServiceException("切图等待已中断");
            }
            try {
                return processAndRegister(workspace, request, userId, requestHash, cells, regions, sourceResource);
            } finally {
                PROCESSING_SLOTS.release();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private Map<String, Object> processAndRegister(Workspace workspace, CanvasRequests.ImageSplit request,
            Long userId, String requestHash, List<ImageRegionProcessor.Cell> cells,
            List<ImageRegionProcessor.Region> regions, CanvasResource sourceResource) {
        byte[] sourceBytes = imageSourceReader.read(sourceResource);
        ImageRegionProcessor.Result cropped;
        try {
            cropped = "GRID".equals(request.getMode())
                    ? ImageRegionProcessor.cropGrid(sourceBytes, request.getRows(), request.getColumns(), cells)
                    : ImageRegionProcessor.crop(sourceBytes, regions);
        } catch (IllegalArgumentException error) {
            String message = error.getMessage();
            throw new ServiceException(message != null && message.startsWith("原图") ? message : "裁切区域无效");
        } catch (IOException error) {
            log.error("画布切图编码失败, sourceResourceId={}", sourceResource.getId(), error);
            throw new ServiceException("切图失败，请重试");
        } catch (RuntimeException error) {
            log.error("画布源图解码失败, sourceResourceId={}", sourceResource.getId(), error);
            throw new ServiceException("源图格式无效");
        }

        List<OssUploadResponse> uploads = new ArrayList<>(cropped.outputs().size());
        try {
            int batchSize = uploadBatchSize();
            for (int start = 0; start < cropped.outputs().size(); start += batchSize) {
                int end = Math.min(start + batchSize, cropped.outputs().size());
                List<MultipartFile> files = new ArrayList<>(end - start);
                for (int index = start; index < end; index++) {
                    files.add(new PngFile("split-" + request.getClientRequestId() + "-" + index + ".png",
                            cropped.outputs().get(index).png()));
                }
                uploads.addAll(ossBusinessService.uploadList(files, "canvas/" + userId + "/split"));
            }
            Map<String, Object> response = transactionTemplate.execute(status -> registerOutputs(
                    workspace, request, userId, requestHash, cells, cropped, uploads));
            if (response == null) throw new ServiceException("切图登记失败");
            return response;
        } catch (RuntimeException error) {
            ossBusinessService.deleteListByUrl(uploads.stream().map(OssUploadResponse::getUrl).toList());
            throw error;
        }
    }

    private List<ImageRegionProcessor.Cell> gridCells(CanvasRequests.ImageSplit request) {
        if (!"GRID".equals(request.getMode())) return List.of();
        if (request.getRows() == null || request.getColumns() == null || request.getRegions() != null
                || request.getRows() < 1 || request.getRows() > 5 || request.getColumns() < 1
                || request.getColumns() > 5 || request.getRows() * request.getColumns() < 2) {
            throw new ServiceException("宫格参数无效");
        }
        List<ImageRegionProcessor.Cell> cells = new ArrayList<>();
        if (request.getSelectedCells() == null) {
            for (int row = 0; row < request.getRows(); row++) {
                for (int col = 0; col < request.getColumns(); col++) cells.add(new ImageRegionProcessor.Cell(row, col));
            }
        } else {
            for (CanvasRequests.GridCell cell : request.getSelectedCells()) {
                if (cell == null || cell.getRow() == null || cell.getCol() == null
                        || cell.getRow() >= request.getRows() || cell.getCol() >= request.getColumns()) {
                    throw new ServiceException("宫格选区越界");
                }
                cells.add(new ImageRegionProcessor.Cell(cell.getRow(), cell.getCol()));
            }
        }
        if (cells.isEmpty() || cells.size() > ImageRegionProcessor.MAX_OUTPUTS
                || new HashSet<>(cells).size() != cells.size()) throw new ServiceException("宫格选区无效");
        cells.sort(Comparator.comparingInt(ImageRegionProcessor.Cell::row)
                .thenComparingInt(ImageRegionProcessor.Cell::column));
        return cells;
    }

    private List<ImageRegionProcessor.Region> pixelRegions(CanvasRequests.ImageSplit request) {
        if (!"REGIONS".equals(request.getMode())) return List.of();
        if (request.getRows() != null || request.getColumns() != null || request.getSelectedCells() != null
                || request.getRegions() == null || request.getRegions().isEmpty()
                || request.getRegions().size() > ImageRegionProcessor.MAX_OUTPUTS) {
            throw new ServiceException("裁切区域参数无效");
        }
        List<ImageRegionProcessor.Region> regions = new ArrayList<>();
        for (CanvasRequests.PixelRegion region : request.getRegions()) {
            if (region == null || region.getX() == null || region.getY() == null
                    || region.getWidth() == null || region.getHeight() == null) {
                throw new ServiceException("裁切区域参数无效");
            }
            regions.add(new ImageRegionProcessor.Region(region.getX(), region.getY(),
                    region.getWidth(), region.getHeight()));
        }
        if (new HashSet<>(regions).size() != regions.size()) throw new ServiceException("裁切区域重复");
        return regions;
    }

    private Map<String, Object> recover(CanvasRequests.ImageSplit request, Long userId, String hash, int count) {
        List<CanvasResource> existing = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            CanvasResource resource = canvasMapper.selectCanvasResourceBySource(request.getWorkspaceId(), SOURCE_TYPE,
                    sourceId(request, userId, index));
            if (resource != null) {
                JSONObject metadata = JSON.parseObject(resource.getMetadataJson());
                if (!Objects.equals(resource.getOwnerUserId(), userId)
                        || !Objects.equals(metadata.getString("splitRequestHash"), hash)) {
                    throw new ServiceException("切图请求标识已被使用");
                }
                existing.add(resource);
            }
        }
        if (existing.isEmpty()) return null;
        if (existing.size() != count) throw new ServiceException("切图登记不完整，请联系管理员");
        List<Map<String, Object>> outputs = new ArrayList<>(count);
        for (CanvasResource resource : existing) outputs.add(outputView(resource));
        JSONObject metadata = JSON.parseObject(existing.get(0).getMetadataJson());
        return result(metadata.getIntValue("sourceWidth"), metadata.getIntValue("sourceHeight"), outputs);
    }

    private Map<String, Object> registerOutputs(Workspace workspace, CanvasRequests.ImageSplit request,
            Long userId, String hash, List<ImageRegionProcessor.Cell> cells,
            ImageRegionProcessor.Result cropped, List<OssUploadResponse> uploads) {
        requireSourceNode(request, true);
        if (uploads.size() != cropped.outputs().size()) throw new ServiceException("切图上传数量不匹配");
        List<Map<String, Object>> outputs = new ArrayList<>(uploads.size());
        for (int index = 0; index < uploads.size(); index++) {
            ImageRegionProcessor.Region region = cropped.outputs().get(index).region();
            OssUploadResponse upload = uploads.get(index);
            if (upload == null || upload.getUrl() == null || upload.getUrl().isBlank()) {
                throw new ServiceException("切图上传地址无效");
            }
            JSONObject metadata = new JSONObject();
            metadata.put("splitRequestHash", hash);
            metadata.put("sourceWidth", cropped.sourceWidth());
            metadata.put("sourceHeight", cropped.sourceHeight());
            metadata.put("index", index);
            metadata.put("x", region.x());
            metadata.put("y", region.y());
            metadata.put("width", region.width());
            metadata.put("height", region.height());
            if ("GRID".equals(request.getMode())) {
                metadata.put("row", cells.get(index).row());
                metadata.put("col", cells.get(index).column());
            } else {
                metadata.put("regionIndex", index);
            }
            CanvasResource resource = new CanvasResource();
            resource.setProjectId(workspace.getProjectId());
            resource.setWorkspaceId(workspace.getId());
            resource.setOwnerUserId(userId);
            resource.setSourceType(SOURCE_TYPE);
            resource.setSourceId(sourceId(request, userId, index));
            resource.setMediaType("IMAGE");
            resource.setStorageUrl(upload.getUrl());
            resource.setFileSize(upload.getFileSize());
            resource.setMimeType("image/png");
            resource.setMetadataJson(JSON.toJSONString(metadata));
            resource.setStatus("ACTIVE");
            resource.setCreateTime(new Date());
            resource.setUpdateTime(new Date());
            if (canvasMapper.insertCanvasResource(resource) != 1) throw new ServiceException("切图登记失败");
            outputs.add(outputView(resource));
        }
        return result(cropped.sourceWidth(), cropped.sourceHeight(), outputs);
    }

    private void requireSourceNode(CanvasRequests.ImageSplit request, boolean lockRow) {
        CanvasObject sourceObject = lockRow
                ? canvasMapper.selectCanvasObjectForUpdate(request.getWorkspaceId(), request.getSourceObjectId())
                : canvasMapper.selectCanvasObject(request.getWorkspaceId(), request.getSourceObjectId());
        if (sourceObject == null || !"NODE".equals(sourceObject.getObjectType())
                || !Objects.equals(sourceObject.getDeleted(), 0)
                || !Objects.equals(sourceObject.getObjectVersion(), request.getExpectedVersion())) {
            throw new ServiceException("源节点版本已变化");
        }
        JSONObject content;
        try {
            content = JSON.parseObject(sourceObject.getContentJson());
        } catch (RuntimeException error) {
            throw new ServiceException("源节点内容无效");
        }
        JSONArray resourceIds = content == null ? null : content.getJSONArray("resourceIds");
        if (resourceIds == null || resourceIds.stream().noneMatch(id ->
                Objects.equals(String.valueOf(id), String.valueOf(request.getSourceResourceId())))) {
            throw new ServiceException("源节点未引用图片");
        }
    }

    private static Map<String, Object> outputView(CanvasResource resource) {
        JSONObject metadata = JSON.parseObject(resource.getMetadataJson());
        Map<String, Object> output = new LinkedHashMap<>();
        for (String key : List.of("index", "row", "col", "regionIndex", "x", "y", "width", "height")) {
            if (metadata.containsKey(key)) output.put(key, metadata.get(key));
        }
        output.put("resourceId", Long.toString(resource.getId()));
        output.put("resourceUrl", resource.getStorageUrl());
        return output;
    }

    private static Map<String, Object> result(int width, int height, List<Map<String, Object>> outputs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sourceWidth", width);
        response.put("sourceHeight", height);
        response.put("outputs", outputs);
        return response;
    }

    private int uploadBatchSize() {
        OssProperties settings = ossConfigManager.getOssProperties();
        return settings == null || settings.getMaxBatchCount() == null || settings.getMaxBatchCount() <= 0
                ? 3 : Math.min(25, settings.getMaxBatchCount());
    }

    private static long sourceId(CanvasRequests.ImageSplit request, Long userId, int index) {
        String value = request.getWorkspaceId() + ":" + userId + ":" + request.getClientRequestId() + ":" + index;
        return Long.parseUnsignedLong(sha256(value).substring(0, 15), 16) + 1;
    }

    private static String requestHash(CanvasRequests.ImageSplit request, Long userId,
            List<ImageRegionProcessor.Cell> cells, List<ImageRegionProcessor.Region> regions) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("actor", userId);
        input.put("workspaceId", request.getWorkspaceId());
        input.put("sourceObjectId", request.getSourceObjectId());
        input.put("sourceResourceId", request.getSourceResourceId());
        input.put("expectedVersion", request.getExpectedVersion());
        input.put("mode", request.getMode());
        input.put("rows", request.getRows());
        input.put("columns", request.getColumns());
        input.put("cells", cells);
        input.put("regions", regions);
        return sha256(JSON.toJSONString(input));
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
