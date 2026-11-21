package com.aid.media.provider;

import com.aid.common.aid.image.ManagedImageIO;

import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Protocol encoding over the common verified image reader; never downloads or drops inputs. */
public final class ReferenceImageBase64Support {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private ReferenceImageBase64Support() { }

    public static boolean isBase64Enabled(AiModelConfigVo config) {
        if (config == null || config.getCapabilityJson() == null) return false;
        try {
            var root = MAPPER.readTree(config.getCapabilityJson());
            return root != null && root.path("supportsBase64Image").asBoolean(false)
                    && root.path("base64ImageEnabled").asBoolean(false);
        } catch (Exception invalid) { return false; }
    }

    public static List<String> toDataUris(List<String> sources) {
        return encode(sources, true);
    }

    public static void verifyInputs(List<String> sources) {
        encode(sources, false);
    }

    private static List<String> encode(List<String> sources, boolean recordDelivery) {
        List<String> result = new ArrayList<>();
        if (sources == null) return result;
        for (String source : sources) {
            if (source == null || source.isBlank())
                throw ProviderSubmissionFailures.notSent("参考图不能为空");
            try {
                ImageEditAssetSupport.BinaryImage image;
                var scope = ImageInputExecutionScope.current();
                boolean encoded = !source.startsWith("/") && !source.regionMatches(true, 0, "http://", 0, 7)
                        && !source.regionMatches(true, 0, "https://", 0, 8)
                        && !source.regionMatches(true, 0, "data:", 0, 5)
                        && (scope == null || !scope.contains(source));
                if (encoded) {
                    if (source.length() > (MAX_IMAGE_BYTES + 2L) / 3L * 4L)
                        throw ProviderSubmissionFailures.notSent("参考图超过10MB内联限制");
                    image = ImageEditAssetSupport.decodeBinary(Base64.getDecoder().decode(source), "参考图");
                } else image = ImageEditAssetSupport.read(source, "参考图");
                byte[] bytes = image.bytes();
                if (bytes.length > MAX_IMAGE_BYTES)
                    throw ProviderSubmissionFailures.notSent("参考图超过10MB内联限制");
                String mime;
                try (var input = ManagedImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported image");
                    var reader = readers.next();
                    try {
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                        mime = "image/" + ("jpg".equals(format) ? "jpeg" : format);
                    } finally { reader.dispose(); }
                }
                if (recordDelivery && scope != null) scope.recordDelivery(source, "INLINE", ImageInputExecutionScope.sha256(bytes),
                        image.image().getWidth(), image.image().getHeight());
                result.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            } catch (RuntimeException failure) {
                if (ProviderSubmissionFailures.definitelyRejected(null, failure)) throw failure;
                throw ProviderSubmissionFailures.notSent("参考图读取或编码失败，请检查图片素材");
            } catch (Exception failure) {
                throw ProviderSubmissionFailures.notSent("参考图读取或编码失败，请检查图片素材");
            }
        }
        return result;
    }

    public static List<String> toRawBase64s(List<String> sources) {
        return toDataUris(sources).stream().map(uri -> uri.substring(uri.indexOf(',') + 1)).toList();
    }
}
