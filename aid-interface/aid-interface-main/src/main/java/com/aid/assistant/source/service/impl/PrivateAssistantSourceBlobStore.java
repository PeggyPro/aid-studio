package com.aid.assistant.source.service.impl;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.source.service.AssistantSourceBlobStore;
import com.aid.common.config.AidAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** 将原始文档保存到公开资源目录之外的持久私有存储。 */
@Slf4j
@Service
public final class PrivateAssistantSourceBlobStore implements AssistantSourceBlobStore {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final String PREFIX = "assistant-source-private:";
    private final String configuredRoot;
    @Value("${aid.assistant.source.minimum-free-bytes:536870912}")
    private long minimumFreeBytes = 536_870_912L;
    @Value("${aid.assistant.source.maximum-stored-bytes:1073741824}")
    private long maximumStoredBytes = 1_073_741_824L;
    @Value("${aid.assistant.source.maximum-stored-files:1000}")
    private int maximumStoredFiles = 1000;

    public PrivateAssistantSourceBlobStore(@Value("${aid.assistant.source.private-storage-root:}") String root) {
        this.configuredRoot = root;
    }

    @Override
    public String store(byte[] bytes, String extension) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES) throw failure("SOURCE_UPLOAD_LIMIT_INVALID");
        if (extension == null || !extension.matches("txt|md|markdown|docx|pdf")) throw failure("SOURCE_FORMAT_UNSUPPORTED");
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path file = null;
        try {
            Path directory = root();
            Path lockPath=directory.resolve(".storage.lock");
            if (Files.isSymbolicLink(lockPath)) throw failure("SOURCE_STORAGE_IDENTITY_INVALID");
            // The lock covers the local/shared-directory capacity check and the write across JVMs.
            // It is independent of Runtime and wallet transactions, and never waits on a Provider.
            try (java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(lockPath,
                    StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                restrict(lockPath,false);
                java.nio.channels.FileLock lock;
                try {lock=channel.tryLock();}
                catch (java.nio.channels.OverlappingFileLockException busy) {throw failure("SOURCE_STORAGE_BUSY");}
                if (lock==null) throw failure("SOURCE_STORAGE_BUSY");
                try (lock) {
                    requireCapacity(directory,bytes.length);
                    file = directory.resolve(name);
                    Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    restrict(file, false);
                    return PREFIX + name;
                }
            }
        } catch (Exception failed) {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (Exception cleanup) {
                    log.info("source partial write cleanup deferred, type={}", cleanup.getClass().getSimpleName());
                }
            }
            if (failed instanceof AssistantServiceException known) throw known;
            log.error("source private storage write failed, type={}", failed.getClass().getSimpleName());
            throw failure("SOURCE_STORAGE_WRITE_FAILED");
        }
    }

    @Override
    public byte[] read(String locator, long byteCount) {
        if (byteCount < 1 || byteCount > MAX_BYTES) throw failure("SOURCE_STORAGE_LENGTH_CHANGED");
        try {
            Path file = resolve(locator);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != byteCount) {
                throw failure("SOURCE_STORAGE_LENGTH_CHANGED");
            }
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length != byteCount) throw failure("SOURCE_STORAGE_LENGTH_CHANGED");
            return bytes;
        } catch (AssistantServiceException known) {
            throw known;
        } catch (Exception failed) {
            log.info("source private storage read failed, type={}", failed.getClass().getSimpleName());
            throw failure("SOURCE_STORAGE_READ_FAILED");
        }
    }

    @Override
    public void deleteQuietly(String locator) {
        if (locator == null) return;
        try { Files.deleteIfExists(resolve(locator)); }
        catch (Exception failed) { log.info("source storage cleanup deferred, type={}", failed.getClass().getSimpleName()); }
    }

    private void requireCapacity(Path directory, long incoming) throws Exception {
        if (minimumFreeBytes<0 || maximumStoredBytes<MAX_BYTES || maximumStoredFiles<1 || maximumStoredFiles>100_000) {
            throw failure("SOURCE_STORAGE_CONFIGURATION_INVALID");
        }
        if (Files.getFileStore(directory).getUsableSpace()-incoming<minimumFreeBytes) {
            throw failure("SOURCE_STORAGE_CAPACITY_EXCEEDED");
        }
        long total=0;int count=0;
        try (java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for (Path entry:entries) {
                if (entry.getFileName().toString().equals(".storage.lock")) continue;
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("SOURCE_STORAGE_IDENTITY_INVALID");
                }
                count++;
                if (count>=maximumStoredFiles) throw failure("SOURCE_STORAGE_CAPACITY_EXCEEDED");
                long size=Files.size(entry);
                if (size>maximumStoredBytes-total) throw failure("SOURCE_STORAGE_CAPACITY_EXCEEDED");
                total+=size;
            }
        }
        if (incoming>maximumStoredBytes-total) throw failure("SOURCE_STORAGE_CAPACITY_EXCEEDED");
    }

    private Path resolve(String locator) throws Exception {
        if (locator == null || !locator.matches("assistant-source-private:[a-f0-9]{32}\\.(txt|md|markdown|docx|pdf)")) {
            throw failure("SOURCE_STORAGE_IDENTITY_INVALID");
        }
        Path directory = root();
        Path file = directory.resolve(locator.substring(PREFIX.length())).normalize();
        if (!directory.equals(file.getParent()) || Files.isSymbolicLink(file)) throw failure("SOURCE_STORAGE_IDENTITY_INVALID");
        return file;
    }

    private Path root() throws Exception {
        Path publicRoot = Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize();
        Path directory = configuredRoot == null || configuredRoot.isBlank()
                ? publicRoot.resolveSibling(".aid-private").resolve("assistant-source")
                : Path.of(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path real = directory.toRealPath();
        Path publicReal = Files.exists(publicRoot) ? publicRoot.toRealPath() : publicRoot;
        if (real.startsWith(publicReal)) throw failure("SOURCE_STORAGE_PUBLIC_ROOT_FORBIDDEN");
        restrict(real, true);
        return real;
    }

    private void restrict(Path path, boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
    }

    private AssistantServiceException failure(String code) {
        log.info("source private storage rejected, code={}", code);
        int status=code.equals("SOURCE_STORAGE_BUSY")?503:code.equals("SOURCE_STORAGE_CAPACITY_EXCEEDED")?507:422;
        return new AssistantServiceException(status, code, "文档存储不可用", null);
    }
}
