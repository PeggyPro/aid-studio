package com.aid.director;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** 不向静态资源目录公开的不可变业务对象。 */
@Component
@RequiredArgsConstructor
public class DirectorObjectStore {
    private final DirectorProperties properties;
    private Path root;
    private final Object[] stripes = new Object[64];
    private java.nio.file.DirectoryStream<Path> scan;
    private java.util.Iterator<Path> scanIterator;
    private int scanPrefix;

    @PostConstruct
    public void initialize() {
        root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Object();
    }

    public String put(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw DirectorException.invalid();
        String key = DirectorIds.hash(bytes);
        synchronized (stripe(key)) {
            return lockedPut(key, bytes);
        }
    }

    private String lockedPut(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectories(root.resolve(".locks"));
            try (var lockChannel = java.nio.channels.FileChannel.open(lockFile(key),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var ignoredLock = lockChannel.lock()) {
            if (Files.exists(target)) {
                // 同摘要复用也必须校验实际文件，磁盘损坏不能得到DURABLE回执。
                if (Files.size(target) != bytes.length || !java.security.MessageDigest.isEqual(bytes, Files.readAllBytes(target)))
                    throw new IOException("director object checksum mismatch");
                Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                return key;
            }
            if (Files.getFileStore(root).getUsableSpace() < (long) properties.getMinFreeStorageMb() * 1024 * 1024 + bytes.length)
                throw new DirectorException("STORAGE_CAPACITY_REACHED", 503, "存储空间不足");
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".part");
            try {
                try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.FileAlreadyExistsException ignored) { }
                try (var directory = java.nio.channels.FileChannel.open(target.getParent(), java.nio.file.StandardOpenOption.READ)) {
                    directory.force(true);
                } catch (java.nio.file.AccessDeniedException ignored) { }
            } finally { Files.deleteIfExists(temporary); }
            return key;
            }
        } catch (IOException e) {
            throw new DirectorException("STORAGE_UNAVAILABLE", 503, "保存暂不可用");
        }
    }

    public byte[] read(String key, int limit) {
        Path target = resolve(key);
        try {
            if (!Files.isRegularFile(target) || Files.size(target) > limit) throw new IOException();
            byte[] bytes = Files.readAllBytes(target);
            if (!DirectorIds.hash(bytes).equals(key)) throw new IOException();
            return bytes;
        } catch (IOException e) { throw new DirectorException("STORAGE_UNAVAILABLE", 503, "文件暂不可用"); }
    }

    public String text(String key, int limit) { return new String(read(key, limit), StandardCharsets.UTF_8); }

    public boolean removeOrphan(String key, java.util.function.Predicate<String> referenced) {
        synchronized (stripe(key)) {
            Path target = resolve(key);
            Path lockFile = lockFile(key);
            try {
                if (!Files.exists(target) || !Files.isDirectory(lockFile.getParent())) return false;
                try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE); var ignoredLock = channel.lock()) {
                    if (!Files.exists(target) || Files.getLastModifiedTime(target).toMillis() > System.currentTimeMillis() - 86400000
                            || referenced.test(key)) return false;
                    return Files.deleteIfExists(target);
                }
            } catch (IOException e) { return false; }
        }
    }

    public synchronized java.util.List<String> candidates(int prefix, int limit) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            if (scan == null) {
                Path directory = root.resolve(String.format("%02x", scanPrefix++ & 255));
                if (!Files.isDirectory(directory)) return result;
                scan = Files.newDirectoryStream(directory); scanIterator = scan.iterator();
            }
            for (int examined = 0; examined < Math.min(100, limit) && scanIterator.hasNext(); examined++) {
                Path path = scanIterator.next(); String name = path.getFileName().toString();
                if (Files.getLastModifiedTime(path).toMillis() > System.currentTimeMillis() - 86400000) continue;
                if (name.matches("[0-9a-f]{64}\\.json")) result.add(name.substring(0, 64));
                else if (name.startsWith(".upload-") && name.endsWith(".part")) Files.deleteIfExists(path);
            }
            if (!scanIterator.hasNext()) closeScan();
        } catch (IOException | java.nio.file.DirectoryIteratorException e) { closeScan(); }
        return result;
    }

    @jakarta.annotation.PreDestroy
    public synchronized void closeScan() {
        try { if (scan != null) scan.close(); } catch (IOException ignored) { }
        scan = null; scanIterator = null;
    }

    private Object stripe(String key) { return stripes[Math.floorMod(key.hashCode(), stripes.length)]; }
    private Path lockFile(String key) { return root.resolve(".locks").resolve(Math.floorMod(key.hashCode(), stripes.length) + ".lock"); }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw DirectorException.invalid();
        Path target = root.resolve(key.substring(0, 2)).resolve(key + ".json").normalize();
        if (!target.startsWith(root)) throw DirectorException.invalid();
        return target;
    }
}
