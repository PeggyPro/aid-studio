package com.aid.skill.service;

import com.aid.common.aid.image.ManagedImageIO;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Skill 封面必须由专用上传入口解码、上传并绑定到稳定身份。 */
@Component
@RequiredArgsConstructor
public class SkillCoverPolicy {
    private static final long MAX_COVER_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final Map<String, String> MIME_BY_FORMAT = Map.of(
            "jpeg", "image/jpeg", "jpg", "image/jpeg", "png", "image/png",
            "gif", "image/gif", "bmp", "image/bmp", "webp", "image/webp");
    private final OssTemplate ossTemplate;

    public UploadEvidence inspect(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_COVER_BYTES) {
            throw new ServiceException("Skill封面文件无效");
        }
        String extension = extension(file.getOriginalFilename());
        if (!IMAGE_EXTENSIONS.contains(extension)) throw new ServiceException("Skill封面格式无效");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new ServiceException("Skill封面读取失败");
        }
        try (ImageInputStream input = ManagedImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new ServiceException("Skill封面无法解码");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ServiceException("Skill封面无法解码");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mimeType = MIME_BY_FORMAT.get(format);
                if (mimeType == null || !extensionMatches(extension, format)) {
                    throw new ServiceException("Skill封面格式与内容不一致");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS || reader.read(0) == null) {
                    throw new ServiceException("Skill封面尺寸或内容无效");
                }
                return new UploadEvidence(SecureUtil.sha256(new ByteArrayInputStream(bytes)),
                        mimeType, width, height);
            } finally {
                reader.dispose();
            }
        } catch (ServiceException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ServiceException("Skill封面无法解码");
        }
    }

    public boolean validUploadedCover(AidSkill skill) {
        if (skill == null || !validManagedImageUrl(skill.getIconUrl())
                || StrUtil.isBlank(skill.getCoverDigest())
                || !skill.getCoverDigest().matches("[0-9a-f]{64}")
                || StrUtil.isBlank(skill.getCoverMimeType())
                || !MIME_BY_FORMAT.containsValue(skill.getCoverMimeType())
                || skill.getCoverUploadedBy() == null || skill.getCoverVerifiedAt() == null) {
            return false;
        }
        return true;
    }

    public boolean validManagedImageUrl(String value) {
        if (StrUtil.isBlank(value) || value.startsWith("data:") || value.startsWith("file:")) return false;
        try {
            if (!ossTemplate.isManagedResourceUrl(value)) return false;
            String path = value.startsWith("/") ? value : URI.create(value).getPath();
            return IMAGE_EXTENSIONS.contains(extension(path));
        } catch (RuntimeException error) {
            return false;
        }
    }

    public void deleteUploaded(String value) {
        if (validManagedImageUrl(value)) ossTemplate.deleteByUrl(value);
    }

    private static boolean extensionMatches(String extension, String format) {
        if (Set.of("jpg", "jpeg").contains(extension)) return Set.of("jpg", "jpeg").contains(format);
        return extension.equals(format);
    }

    private static String extension(String value) {
        if (StrUtil.isBlank(value)) return "";
        String source = value;
        int query = source.indexOf('?');
        if (query >= 0) source = source.substring(0, query);
        int dot = source.lastIndexOf('.');
        return dot < 0 || dot == source.length() - 1
                ? "" : source.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record UploadEvidence(String digest, String mimeType, int width, int height) { }
}
