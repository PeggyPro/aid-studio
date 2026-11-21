package com.aid.media.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.utils.spring.SpringUtils;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体字节抓取工具：带体积上限与硬超时的一次性下载，供时长探测、音频补齐等旁路能力复用。
 * 失败返回空结果，绝不抛异常阻断主流程。
 *
 * @author 视觉AID
 */
@Slf4j
public final class MediaBytesFetcher {

    /** 默认下载上限：TTS 音频通常几百 KB，超出部分直接丢弃不再读取，避免大文件撑爆内存 */
    public static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;

    /** 单次读取缓冲区 */
    private static final int READ_BUFFER_BYTES = 8 * 1024;

    /** 下载超时毫秒：旁路能力必须有硬超时，不允许拖住出片主流程 */
    private static final int TIMEOUT_MS = 5000;

    /** 空结果：下载失败或响应为空时统一返回 */
    private static final Content EMPTY = new Content(new byte[0], false);

    private MediaBytesFetcher() {
    }

    /**
     * 抓取结果。
     *
     * @param bytes     已读字节
     * @param truncated 是否在体积上限处截断（截断字节不构成完整文件）
     */
    public record Content(byte[] bytes, boolean truncated, Integer httpStatus, String failureKind) {
        public Content(byte[] bytes, boolean truncated) { this(bytes, truncated, null, null); }

        /**
         * @return true=未抓到任何字节
         */
        public boolean isEmpty() {
            return bytes.length == 0;
        }
    }

    private static Content failed(Integer status, String kind) {
        return new Content(new byte[0], false, status, kind);
    }

    /**
     * 按默认上限抓取媒体字节。
     *
     * @param fullUrl 完整可访问 URL
     * @return 抓取结果；失败返回空结果
     */
    public static Content fetch(String fullUrl) {
        return fetch(fullUrl, DEFAULT_MAX_BYTES);
    }

    /**
     * 抓取媒体字节。
     *
     * @param fullUrl  完整可访问 URL
     * @param maxBytes 体积上限（字节），超出部分丢弃并置截断标记
     * @return 抓取结果；失败返回空结果
     */
    public static Content fetch(String fullUrl, int maxBytes) {
        return fetch(fullUrl, maxBytes, TIMEOUT_MS);
    }

    /** Use a caller-specific timeout for required assets while retaining the short default for optional probes. */
    public static Content fetch(String fullUrl, int maxBytes, int timeoutMs) {
        if (StrUtil.isBlank(fullUrl) || maxBytes <= 0 || timeoutMs <= 0) {
            return EMPTY;
        }
        // 本站已授权的上传相对路径不依赖资源域名；绝不能把外部 URL 映射成本地文件。
        if (fullUrl.startsWith(Constants.RESOURCE_PREFIX + "/")) {
            return fetchLocalUpload(fullUrl, maxBytes);
        }
        try {
            return PublicMediaHttp.get(fullUrl, timeoutMs, response -> {
                if (response.getCode() != HttpURLConnection.HTTP_OK || response.getEntity() == null) {
                    return failed(response.getCode(), "HTTP_STATUS_OR_EMPTY_BODY");
                }
                return readLimited(response.getEntity().getContent(), maxBytes);
            });
        } catch (Exception ex) {
            log.warn("媒体字节下载异常, errorType={}", ex.getClass().getSimpleName());
            return failed(null, ex.getClass().getSimpleName());
        }
    }

    /**
     * Read a server-authorized site media locator through the configured storage backend.
     *
     * <p>This method is intentionally separate from {@link #fetch(String, int, int)}. Callers may
     * use it only after restoring a server-owned authorization snapshot; arbitrary external URLs
     * and unmanaged relative paths remain rejected.</p>
     */
    public static Content fetchAuthorized(String locator, int maxBytes, int timeoutMs) {
        try {
            var result = SpringUtils.getBean(com.aid.common.aid.oss.core.ManagedStorageResourceReader.class)
                    .read(locator, maxBytes, timeoutMs);
            return new Content(result.bytes(), result.truncated(), result.httpStatus(), result.failureKind());
        } catch (Exception failure) {
            return failed(null, failure.getClass().getSimpleName());
        }
    }

    private static Content fetchLocalUpload(String source, int maxBytes) {
        try {
            URI uri = URI.create(source);
            String path = uri.getPath();
            String prefix = Constants.RESOURCE_PREFIX + "/upload/";
            if (uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.startsWith(prefix)
                    || path.contains("..") || path.contains("\\")
                    || StrUtil.isBlank(AidAppConfig.getProfile())) {
                log.warn("本地媒体路径校验失败");
                return EMPTY;
            }
            Path base = Path.of(AidAppConfig.getProfile()).toRealPath();
            Path file = base.resolve(path.substring(Constants.RESOURCE_PREFIX.length() + 1))
                    .normalize().toRealPath();
            // realPath 校验阻止上传目录内的符号链接越界，与网络下载共用流式体积限制。
            if (!file.startsWith(base.resolve("upload")) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("本地媒体路径越界或文件不可用");
                return EMPTY;
            }
            try (InputStream stream = Files.newInputStream(file)) {
                return readLimited(stream, maxBytes);
            }
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("本地媒体读取失败, errorType={}", ex.getClass().getSimpleName());
            return EMPTY;
        }
    }

    /**
     * 流式读取并在上限处截断，避免整文件进内存。
     *
     * @param stream   响应体流
     * @param maxBytes 体积上限
     * @return 已读字节与截断标记
     */
    private static Content readLimited(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_BUFFER_BYTES];
        boolean truncated = false;
        int read;
        while ((read = stream.read(chunk)) > 0) {
            int remaining = maxBytes - buffer.size();
            // 严格大于才算截断：体积恰好等于上限的完整文件不能被误判为残缺
            if (read > remaining) {
                buffer.write(chunk, 0, remaining);
                truncated = true;
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return new Content(buffer.toByteArray(), truncated);
    }
}
