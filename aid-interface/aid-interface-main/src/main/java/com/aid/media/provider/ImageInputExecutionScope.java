package com.aid.media.provider;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.media.dto.AuthorizedImageAssetSnapshot;
import com.aid.media.dto.MediaImageGenerateRequest;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** One execution owns its verified input files. No cross-user or cross-task byte cache. */
@Slf4j(topic = "media-image-input")
public final class ImageInputExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ImageInputExecutionScope> CURRENT = new ThreadLocal<>();
    private final Long taskId;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, Source> aliases = new LinkedHashMap<>();
    private final Map<String, String> deliveryLocators = new LinkedHashMap<>();
    private final Map<String, Cached> cached = new LinkedHashMap<>();
    private final List<Path> files = new ArrayList<>();
    private boolean closed;
    private record Source(String locator, AuthorizedImageAssetSnapshot snapshot,
                          Supplier<ImageEditAssetSupport.BinaryImage> loader) { }
    private record Cached(Path file, long size, int width, int height, String sha256) { }

    private ImageInputExecutionScope(Long taskId) { this.taskId = taskId; }

    public static ImageInputExecutionScope open(Long taskId) {
        if (CURRENT.get() != null) throw new IllegalStateException("IMAGE_INPUT_SCOPE_NESTED");
        var scope = new ImageInputExecutionScope(taskId);
        CURRENT.set(scope);
        return scope;
    }

    public static ImageInputExecutionScope current() { return CURRENT.get(); }

    public Long taskId() { return taskId; }

    public void register(MediaImageGenerateRequest request,
                         Map<String, Supplier<ImageEditAssetSupport.BinaryImage>> authorizedLoaders) {
        requireActive();
        Map<String, AuthorizedImageAssetSnapshot> snapshots = new LinkedHashMap<>();
        if (request.getAuthorizedReferenceImages() != null) snapshots.putAll(request.getAuthorizedReferenceImages());
        if (request.getDeliveryAuthorizedReferenceImages() != null) {
            var allowed = slots(request).values();
            request.getDeliveryAuthorizedReferenceImages().forEach((url, snapshot) -> {
                if (!allowed.contains(url) || snapshot == null) throw new IllegalArgumentException("IMAGE_DELIVERY_AUTHORIZATION_INVALID");
                snapshot.requireValid(url, "参考图");
                var original = snapshots.putIfAbsent(url, snapshot);
                if (original != null && !original.equals(snapshot)) throw new IllegalArgumentException("IMAGE_AUTHORIZATION_CHANGED");
            });
        }
        for (var snapshot : new AuthorizedImageAssetSnapshot[]{request.getAuthorizedReferenceImage(), request.getAuthorizedMaskImage()})
            if (snapshot != null) {
                var original = snapshots.putIfAbsent(snapshot.getLocator(), snapshot);
                if (original != null && !original.equals(snapshot)) throw new IllegalArgumentException("IMAGE_AUTHORIZATION_CHANGED");
            }
        for (var entry : snapshots.entrySet()) {
            AuthorizedImageAssetSnapshot snapshot = entry.getValue();
            if (snapshot == null) throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "图片授权记录无效");
            snapshot.requireValid(entry.getKey(), "图片素材");
            Supplier<ImageEditAssetSupport.BinaryImage> loader = authorizedLoaders.getOrDefault(entry.getKey(),
                    () -> ImageEditAssetSupport.readAuthorizedUncached(snapshot, "图片素材"));
            Source source = new Source(entry.getKey(), snapshot, loader);
            sources.put(entry.getKey(), source);
            aliases.put(entry.getKey(), source);
        }
        for (var entry : authorizedLoaders.entrySet()) {
            if (sources.containsKey(entry.getKey())) continue;
            Source source = new Source(entry.getKey(), null, entry.getValue());
            sources.put(entry.getKey(), source); aliases.put(entry.getKey(), source);
        }
    }

    public void verifyRegistered() {
        for (Source source : List.copyOf(sources.values())) readSource(source, "图片素材");
    }

    public static Map<String, String> slots(MediaImageGenerateRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        if (request.getReferenceImageUrl() != null) result.put("source", request.getReferenceImageUrl());
        if (request.getMaskImageUrl() != null) result.put("mask", request.getMaskImageUrl());
        if (request.getOptions() != null) for (String name : List.of("referenceImages", "images")) {
            if (request.getOptions().get(name) instanceof List<?> images) for (int i = 0; i < images.size(); i++)
                if (images.get(i) instanceof String url) result.put(name + ":" + i, url);
        }
        return result;
    }

    /** The host binds freshly signed URLs to the already verified storage input. */
    public void bindDelivery(Map<String, String> before, Map<String, String> after) {
        requireActive();
        if (!before.keySet().equals(after.keySet())) throw new IllegalStateException("IMAGE_INPUT_SLOTS_CHANGED");
        for (var entry : after.entrySet()) {
            Source source = sources.get(before.get(entry.getKey()));
            String delivered = entry.getValue();
            if (source == null) source = new Source(delivered, null,
                    () -> ImageEditAssetSupport.readUncached(delivered, "参考图"));
            deliveryLocators.put(before.get(entry.getKey()), delivered);
            aliases.put(before.get(entry.getKey()), source);
            aliases.put(delivered, source);
        }
    }

    /** Rendered protocol references retain the host-prepared locator, including fresh signatures. */
    public String deliveredLocator(String source) { requireActive(); return deliveryLocators.getOrDefault(source, source); }

    public boolean contains(String source) { return aliases.containsKey(source); }

    public ImageEditAssetSupport.BinaryImage read(String source, String label) {
        Source entry = aliases.get(source);
        return entry == null ? null : readSource(entry, label);
    }

    public ImageEditAssetSupport.BinaryImage readAuthorized(AuthorizedImageAssetSnapshot snapshot, String label) {
        snapshot.requireValid(snapshot.getLocator(), label);
        Source entry = sources.computeIfAbsent(snapshot.getLocator(), key -> new Source(key, snapshot,
                () -> ImageEditAssetSupport.readAuthorizedUncached(snapshot, label)));
        var result = readSource(entry, label);
        verify(snapshot, result, label);
        return result;
    }

    private ImageEditAssetSupport.BinaryImage readSource(Source source, String label) {
        requireActive();
        Cached ready = cached.get(source.locator());
        try {
            if (ready != null) {
                byte[] bytes = Files.readAllBytes(ready.file());
                if (!ready.sha256().equals(sha256(bytes))) throw new IllegalStateException("IMAGE_INPUT_CACHE_CHANGED");
                return ImageEditAssetSupport.decodeBinary(bytes, label);
            }
            var image = source.loader().get();
            verify(source.snapshot(), image, label);
            Path file = Files.createTempFile(com.aid.common.config.AidAppConfig.getTemporaryDirectory(), "aid-image-input-", ".bin");
            files.add(file);
            Files.write(file, image.bytes());
            String digest = sha256(image.bytes());
            cached.put(source.locator(), new Cached(file, image.bytes().length,
                    image.image().getWidth(), image.image().getHeight(), digest));
            log.info("image input verified, taskId={}, resourceId={}, resourceIdentityDigest={}, byteSha256={}, size={}, width={}, height={}",
                    taskId, source.snapshot() == null ? null : source.snapshot().getResourceId(),
                    source.snapshot() == null ? null : source.snapshot().getResourceIdentityDigest(), digest,
                    image.bytes().length, image.image().getWidth(), image.image().getHeight());
            return image;
        } catch (java.io.IOException failure) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.PERSIST_FAILED, "图片执行素材准备失败");
        }
    }

    private static void verify(AuthorizedImageAssetSnapshot snapshot, ImageEditAssetSupport.BinaryImage image, String label) {
        if (snapshot == null) return;
        if (!Long.valueOf(image.bytes().length).equals(snapshot.getFileSize())
                || !Integer.valueOf(image.image().getWidth()).equals(snapshot.getWidth())
                || !Integer.valueOf(image.image().getHeight()).equals(snapshot.getHeight()))
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, label + "内容已变化");
    }

    public void recordDelivery(String source, String representation, String digest, int width, int height) {
        Source entry = aliases.get(source);
        Cached original = entry == null ? null : cached.get(entry.locator());
        log.info("image input delivered, taskId={}, resourceId={}, representation={}, sourceByteSha256={}, deliveryByteSha256={}, width={}, height={}",
                taskId, entry == null || entry.snapshot() == null ? null : entry.snapshot().getResourceId(),
                representation, original == null ? null : original.sha256(), digest, width, height);
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireActive() { if (closed || CURRENT.get() != this) throw new IllegalStateException("IMAGE_INPUT_SCOPE_CLOSED"); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (CURRENT.get() == this) CURRENT.remove();
        for (Path file : files) try { Files.deleteIfExists(file); }
        catch (java.io.IOException failure) { log.warn("image input cleanup failed, taskId={}, file={}", taskId, file.getFileName()); }
        cached.clear(); aliases.clear(); deliveryLocators.clear(); sources.clear(); files.clear();
    }
}
