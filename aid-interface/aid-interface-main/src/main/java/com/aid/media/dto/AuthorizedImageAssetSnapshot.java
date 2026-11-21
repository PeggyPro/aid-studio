package com.aid.media.dto;

import cn.hutool.core.util.StrUtil;
import com.aid.media.service.MediaPreparationErrorCode;
import lombok.Data;

import java.util.Locale;
import java.util.Objects;

/**
 * Server-owned identity and verified metadata for one authorized image resource.
 *
 * <p>The business authorization layer creates this value from a persisted resource record. Quotes
 * consume only this metadata; provider submission reads the same locator and verifies the real
 * bytes before accepting the task.</p>
 */
@Data
public class AuthorizedImageAssetSnapshot {
    private Long resourceId;
    private String locator;
    private Long fileSize;
    private String mimeType;
    private Integer width;
    private Integer height;
    /** Stable digest of the persisted resource identity and metadata; it is not a byte-content hash. */
    private String resourceIdentityDigest;

    public void requireValid(String expectedLocator, String label) {
        String name = StrUtil.blankToDefault(label, "图片素材");
        String normalizedMime = StrUtil.trimToEmpty(mimeType).toLowerCase(Locale.ROOT);
        if (resourceId == null || resourceId <= 0 || StrUtil.isBlank(locator)
                || !Objects.equals(locator, expectedLocator) || fileSize == null || fileSize <= 0
                || width == null || width <= 0 || height == null || height <= 0
                || !normalizedMime.startsWith("image/")
                || StrUtil.isBlank(resourceIdentityDigest)
                || !resourceIdentityDigest.matches("[0-9a-f]{64}")) {
            throw MediaPreparationErrorCode.MEDIA_INPUT_METADATA_REQUIRED.reject(name + "可信元数据缺失或已变化");
        }
    }
}
