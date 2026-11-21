package com.aid.canvas.service.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.canvas.service.CanvasVideoSubjectDetectionService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.image.detection.DetectImageObjectsInput;
import com.aid.common.image.detection.DetectImageObjectsResult;
import com.aid.common.image.detection.ImageDetectionConfig;
import com.aid.common.image.detection.ImageDetectionConfigManager;
import com.aid.common.image.detection.ImageDetectionException;
import com.aid.common.image.detection.ImageDetectionSourceProbe;
import com.aid.common.image.detection.ImageObjectDetectionService;
import com.aid.common.image.detection.PixelBox;

import lombok.RequiredArgsConstructor;

/** A transient video frame is registered as IMAGE before calling the shared Tencent CI detector. */
@Service
@RequiredArgsConstructor
public class CanvasVideoSubjectDetectionServiceImpl implements CanvasVideoSubjectDetectionService {
    private final CanvasAccessService accessService;
    private final CanvasResourceService resourceService;
    private final ImageDetectionConfigManager configManager;
    private final ImageDetectionSourceProbe sourceProbe;
    private final ImageObjectDetectionService detector;
    private final MediaUrlResolver mediaUrlResolver;
    private final ConfigService configService;

    @Override
    public Map<String, Object> detect(CanvasRequests.VideoSubjectDetect request, Long userId) {
        accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        List<CanvasResource> resources = resourceService.requireResources(request.getWorkspaceId(),
                List.of(request.getFrameResourceId()), userId);
        CanvasResource frame = resources.get(0);
        if (!"IMAGE".equals(frame.getMediaType()) || !"ACTIVE".equals(frame.getStatus())) {
            throw new ServiceException("识别帧必须是当前画布已登记的图片");
        }
        String traceId = UUID.randomUUID().toString();
        ImageDetectionConfig config = configManager.current();
        if (!config.enabled()) throw new ServiceException("图像检测未启用");
        DetectImageObjectsInput source;
        try {
            if ("COS_OBJECT".equals(config.cosImageAccessMode())) {
                source = sourceProbe.cosObject(cosKey(frame.getStorageUrl(), config), config, traceId);
            } else {
                source = sourceProbe.verifiedUrl(mediaUrlResolver.toFullUrl(frame.getStorageUrl()), traceId);
            }
            DetectImageObjectsResult result = detector.detect(source, "canvas-video:" + userId);
            List<Map<String, Object>> candidates = new ArrayList<>();
            int index = 0;
            for (var object : result.objects()) {
                PixelBox box = object.box();
                if (box == null || box.width() <= 0 || box.height() <= 0) continue;
                double x = clamp((double) box.x() / source.imageWidth());
                double y = clamp((double) box.y() / source.imageHeight());
                double width = Math.min((double) box.width() / source.imageWidth(), 1 - x);
                double height = Math.min((double) box.height() / source.imageHeight(), 1 - y);
                if (width <= 0 || height <= 0) continue;
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("id", "object-" + ++index);
                candidate.put("label", Objects.toString(object.label(), "识别主体"));
                candidate.put("confidence", object.confidence());
                candidate.put("rect", Map.of("x", x, "y", y, "w", width, "h", height));
                candidate.put("thumbnailUrl", mediaUrlResolver.toFullUrl(frame.getStorageUrl()));
                candidates.add(candidate);
            }
            return Map.of("candidates", candidates, "frameResourceId", request.getFrameResourceId());
        } catch (ImageDetectionException error) {
            throw new ServiceException(error.getMessage());
        }
    }

    private String cosKey(String storageUrl, ImageDetectionConfig config) {
        if (storageUrl == null || storageUrl.isBlank()) throw new ServiceException("识别帧地址无效");
        try {
            URI uri = URI.create(mediaUrlResolver.toFullUrl(storageUrl));
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getQuery() != null || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException();
            }
            Map<String, String> oss = configService.getConfigValues("oss");
            URI publicDomain = URI.create(oss.getOrDefault("resourceAccessDomain", ""));
            String cosHost = config.bucketName() + ".cos." + config.region() + ".myqcloud.com";
            if (!Objects.equals(uri.getHost(), publicDomain.getHost())
                    && !Objects.equals(uri.getHost(), cosHost)) throw new IllegalArgumentException();
            String key = uri.getPath().replaceFirst("^/", "");
            if (key.isBlank() || key.contains("..") || key.contains("\\")) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException error) {
            throw new ServiceException("识别帧不属于已配置的 COS 存储域名");
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
