package com.aid.previz.service;

import com.aid.common.aid.image.ManagedImageIO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.aid.common.exception.ServiceException;

/** Immutable reference images owned by a standalone project, never a browser-supplied path or URL. */
@Component
@ConditionalOnProperty(prefix = "aid.previz", name = "enabled", havingValue = "true")
public final class PrevizReferenceImageStore {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_PREVIEW_BYTES = 16 * 1024 * 1024;
    private final Path root;
    private final PrevizProjectService projects;
    public record Image(String id, String mimeType, int width, int height, byte[] bytes) {
        public String dataUrl() { return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes); }
    }
    public PrevizReferenceImageStore(@Value("${aid.previz.source-root}") String sourceRoot,
                                     PrevizProjectService projects) throws IOException {
        this.projects = projects;
        Path source = Path.of(sourceRoot).toAbsolutePath().normalize();
        Files.createDirectories(source);
        if (Files.isSymbolicLink(source)) throw new IOException("PREVIZ_IMAGE_ROOT_INVALID");
        root = source.toRealPath().resolve(".reference-images");
        directory(root);
    }
    public Image save(Long actor, String projectId, String base64) throws IOException {
        projects.requireWrite(actor, projectId);
        if (base64 == null || base64.length() > ((MAX_BYTES + 2L) / 3) * 4)
            throw new ServiceException("参考图最大5MB");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException invalid) { throw new ServiceException("参考图编码无效"); }
        return persist(actor, projectId, inspect(bytes, MAX_BYTES));
    }
    /** Internal renderer output; never used by the reference-image upload endpoint. */
    public Image saveGeneratedPreview(Long actor, String projectId, byte[] bytes) throws IOException {
        projects.requireWrite(actor, projectId);
        Image image = inspect(bytes, MAX_PREVIEW_BYTES);
        if (image.width() > 2048 || image.height() > 2048 || !"image/png".equals(image.mimeType()))
            throw new IOException("PREVIZ_PREVIEW_DIMENSIONS_OR_FORMAT_INVALID");
        return persist(actor, projectId, image);
    }
    private Image persist(Long actor, String projectId, Image image) throws IOException {
        byte[] bytes = image.bytes();
        Path folder = project(projectId); directory(folder);
        Path target = folder.resolve(image.id());
        synchronized (this) {
            projects.requireWrite(actor, projectId);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return read(actor, projectId, image.id());
            Path pending = folder.resolve(".pending-" + UUID.randomUUID());
            try {
                Files.write(pending, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(pending); }
        }
        return image;
    }
    public Image read(Long actor, String projectId, String id) throws IOException {
        projects.requireRead(actor, projectId);
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new ServiceException("参考图标识无效");
        Path folder = project(projectId);
        Path file = folder.resolve(id);
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_PREVIEW_BYTES)
            throw new IOException("PREVIZ_IMAGE_UNAVAILABLE");
        Image image = inspect(Files.readAllBytes(file), MAX_PREVIEW_BYTES);
        if (!id.equals(image.id())) throw new IOException("PREVIZ_IMAGE_CHANGED");
        return image;
    }
    private Path project(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IOException("PREVIZ_PROJECT_INVALID");
        if (Files.isSymbolicLink(root)) throw new IOException("PREVIZ_IMAGE_ROOT_INVALID");
        return root.resolve(id);
    }
    private static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("PREVIZ_IMAGE_DIRECTORY_INVALID");
    }
    private static Image inspect(byte[] bytes, int maximumBytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes) throw new IOException("PREVIZ_IMAGE_SIZE_INVALID");
        try (ImageInputStream input = ManagedImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("PREVIZ_IMAGE_FORMAT_INVALID");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw new IOException("PREVIZ_IMAGE_FORMAT_INVALID");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_777_216)
                    throw new IOException("PREVIZ_IMAGE_DIMENSIONS_INVALID");
                if (reader.read(0) == null) throw new IOException("PREVIZ_IMAGE_DECODE_FAILED");
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new Image(hash, "image/" + format, width, height, bytes);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            finally { reader.dispose(); }
        }
    }
}
