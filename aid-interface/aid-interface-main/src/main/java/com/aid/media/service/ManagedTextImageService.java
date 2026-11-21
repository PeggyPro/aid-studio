package com.aid.media.service;

import com.aid.common.exception.ServiceException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Internal references stay in persisted requests; bytes are materialized only for provider delivery. */
@Service
public final class ManagedTextImageService {
    private final List<ManagedTextImageResolver> resolvers;
    public ManagedTextImageService(List<ManagedTextImageResolver> resolvers) { this.resolvers = List.copyOf(resolvers); }
    public static boolean isReference(String value) {
        return value != null && value.matches("aid-image://[a-z][a-z0-9-]{0,31}/[A-Za-z0-9_-]{1,100}/[a-f0-9]{64}");
    }
    public ManagedTextImageResolver.Image resolve(Long actor, String reference) {
        if (actor == null || !isReference(reference)) throw new ServiceException("内部图片引用无效");
        var matching = resolvers.stream().filter(resolver -> resolver.supports(reference)).toList();
        if (matching.size() != 1) throw new ServiceException("内部图片读取器不可用");
        var image = matching.get(0).resolve(actor, reference);
        if (image == null || image.bytes() == null || image.bytes().length == 0 || image.bytes().length > 5 * 1024 * 1024
                || !List.of("image/png", "image/jpeg").contains(image.mimeType())
                || image.width() < 1 || image.height() < 1 || image.width() > 8192 || image.height() > 8192
                || (long) image.width() * image.height() > 16777216) throw new ServiceException("内部图片内容无效");
        return image;
    }
    public String toDataUri(Long actor, String reference) {
        var image = resolve(actor, reference);
        return "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
    }
    public void materialize(Long actor, List<Map<String, Object>> messages) {
        for (var message : messages) {
            if (!(message.get("content") instanceof List<?> parts)) continue;
            for (var part : parts) {
                if (!(part instanceof Map<?, ?> row) || !"image_url".equals(row.get("type"))
                        || !(row.get("image_url") instanceof Map<?, ?> image)
                        || !(image.get("url") instanceof String url) || !isReference(url)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> writable = (Map<String, Object>) image;
                writable.put("url", toDataUri(actor, url));
            }
        }
    }
}
