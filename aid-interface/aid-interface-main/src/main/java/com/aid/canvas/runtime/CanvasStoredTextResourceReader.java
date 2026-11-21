package com.aid.canvas.runtime;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.source.parser.DocxSourceDocumentParser;
import com.aid.assistant.source.parser.PdfSourceDocumentParser;
import com.aid.assistant.source.parser.Utf8TextSourceDocumentParser;
import com.aid.assistant.source.service.SourceDocumentParser;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads a bounded UTF-8 text upload from a server-registered Canvas storage location. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasStoredTextResourceReader {
    private static final int FETCH_TIMEOUT_MS = 8_000;
    private static final int MAX_CONTAINER_SOURCE_BYTES = 10 * 1024 * 1024;

    private final OssTemplate ossTemplate;
    private final OssConfigManager ossConfigManager;
    private final MediaUrlResolver mediaUrlResolver;
    private final AssistantContextLimits contextLimits;
    private final Utf8TextSourceDocumentParser utf8TextParser;
    private final DocxSourceDocumentParser docxParser;
    private final PdfSourceDocumentParser pdfParser;

    public boolean supports(CanvasResource resource) {
        return resource != null && "DOCUMENT".equals(resource.getMediaType())
                && documentType(resource.getMimeType()) != null;
    }

    public String read(CanvasResource resource) {
        if (!supports(resource) || StrUtil.isBlank(resource.getStorageUrl())) {
            throw new ServiceException("文本素材无效");
        }
        DocumentType type = documentType(resource.getMimeType());
        if (type == null) throw new ServiceException("文本素材格式不支持");
        int sourceLimit = type.compressedContainer()
                ? MAX_CONTAINER_SOURCE_BYTES : contextLimits.maxContextBytes();
        long declaredSize = resource.getFileSize() == null ? -1L : resource.getFileSize();
        if (declaredSize < 1) throw new ServiceException("文本素材大小无效");
        if (declaredSize > sourceLimit)
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    type.compressedContainer() ? "ASSISTANT_REFERENCE_DOCUMENT_LIMIT" : "ASSISTANT_CONTEXT_TOO_LARGE",
                    "引用文档超过可解析限制", Map.of("actualBytes", declaredSize,
                            "maxBytes", sourceLimit, "overflowPolicy", "REJECT"));
        byte[] bytes = readStored(resource.getStorageUrl(), sourceLimit);
        String text;
        SourceDocumentParser parser = switch (type) {
            case TEXT, MARKDOWN -> utf8TextParser;
            case DOCX -> docxParser;
            case PDF -> pdfParser;
        };
        String separator = type == DocumentType.PDF ? "\n\n" : "";
        text = parser.parse(bytes, Set.of()).stream()
                .map(part -> part.content() == null ? "" : part.content())
                .collect(Collectors.joining(separator));
        text = CanvasPlainText.lineFeeds(text);
        if (StrUtil.isBlank(text)) {
            if (type == DocumentType.PDF) return null;
            throw new ServiceException("文本素材不能为空");
        }
        int actualBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > contextLimits.maxContextBytes())
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "ASSISTANT_CONTEXT_TOO_LARGE", "引用内容超过助手上下文限制",
                    Map.of("actualBytes", actualBytes, "maxBytes", contextLimits.maxContextBytes(),
                            "overflowPolicy", "REJECT"));
        return text;
    }

    private byte[] readStored(String storedUrl, int sourceLimit) {
        if (!mediaUrlResolver.isSiteMediaUrl(storedUrl)) throw new ServiceException("文本素材不在本站存储");
        OssProperties settings = ossConfigManager.getOssProperties();
        if (settings != null && "local".equalsIgnoreCase(settings.getUploadMode())) {
            String relative = mediaUrlResolver.toRelativePath(storedUrl);
            if (relative == null || !relative.startsWith("/profile/") || relative.contains("..")) {
                throw new ServiceException("文本素材路径无效");
            }
            try {
                Path root = Path.of(AidAppConfig.getProfile()).toRealPath();
                Path path = root.resolve(relative.substring("/profile/".length())).toRealPath();
                if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new IOException("invalid path");
                try (InputStream input = Files.newInputStream(path)) {
                    return readLimited(input, sourceLimit);
                }
            } catch (IOException error) {
                log.error("画布本地文本素材读取失败, url={}", storedUrl, error);
                throw new ServiceException("文本素材读取失败");
            }
        }
        String signed = ossTemplate.getSignedUrl(mediaUrlResolver.toFullUrl(storedUrl), 120);
        try {
            URI uri = URI.create(signed);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("invalid signed URL");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setConnectTimeout(FETCH_TIMEOUT_MS);
                connection.setReadTimeout(FETCH_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                if (connection.getContentLengthLong() > sourceLimit) tooLarge(sourceLimit);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("invalid source response");
                }
                try (InputStream input = connection.getInputStream()) {
                    return readLimited(input, sourceLimit);
                }
            } finally {
                connection.disconnect();
            }
        } catch (AssistantServiceException known) {
            throw known;
        } catch (Exception error) {
            log.error("画布对象存储文本素材读取失败, url={}", storedUrl, error);
            throw new ServiceException("文本素材读取失败");
        }
    }

    private byte[] readLimited(InputStream input, int sourceLimit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() + read > sourceLimit) tooLarge(sourceLimit);
            bytes.write(buffer, 0, read);
        }
        if (bytes.size() == 0) throw new IOException("empty source");
        return bytes.toByteArray();
    }

    private void tooLarge(int sourceLimit) {
        throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                sourceLimit == MAX_CONTAINER_SOURCE_BYTES
                        ? "ASSISTANT_REFERENCE_DOCUMENT_LIMIT" : "ASSISTANT_CONTEXT_TOO_LARGE",
                "引用文档超过可解析限制",
                Map.of("maxBytes", sourceLimit, "overflowPolicy", "REJECT"));
    }

    private DocumentType documentType(String mimeType) {
        if (mimeType == null) return null;
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        if (parameter >= 0) normalized = normalized.substring(0, parameter);
        normalized = normalized.trim();
        return switch (normalized) {
            case "text/plain" -> DocumentType.TEXT;
            case "text/markdown" -> DocumentType.MARKDOWN;
            case "application/pdf" -> DocumentType.PDF;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentType.DOCX;
            default -> null;
        };
    }

    private enum DocumentType {
        TEXT(false), MARKDOWN(false), PDF(true), DOCX(true);

        private final boolean compressedContainer;

        DocumentType(boolean compressedContainer) {
            this.compressedContainer = compressedContainer;
        }

        private boolean compressedContainer() {
            return compressedContainer;
        }
    }
}
