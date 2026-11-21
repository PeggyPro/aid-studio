package com.aid.canvas.service.impl;

import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.common.exception.ServiceException;
import com.aid.media.util.MediaBytesFetcher;
import org.springframework.stereotype.Component;

/** Read an authorized Canvas image through the shared managed-storage reader. */
@Component
public class CanvasImageSourceReader {
    private static final int MAX_SOURCE_BYTES = 25 * 1024 * 1024;
    private static final int FETCH_TIMEOUT_MS = 8_000;

    public byte[] read(CanvasResource resource) {
        if (resource == null || !"IMAGE".equals(resource.getMediaType()) || resource.getStorageUrl() == null) {
            throw new ServiceException("图片素材无效");
        }
        MediaBytesFetcher.Content content = MediaBytesFetcher.fetchAuthorized(
                resource.getStorageUrl(), MAX_SOURCE_BYTES, FETCH_TIMEOUT_MS);
        if (content.isEmpty() || content.truncated()) throw new ServiceException("图片读取失败");
        return content.bytes();
    }
}
