package com.aid.previz.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/** 保存独立三维项目的不可变源码版本及当前版本指针。 */
public final class PrevizSourceStore
{
    /** Raised under the write lock, before creating any new source version. */
    public static final class SourceVersionConflictException extends IOException {
        public SourceVersionConflictException() { super("SOURCE_VERSION_CONFLICT: 工程源码已变化"); }
    }
    public static final class SourceQuotaExceededException extends IOException {
        public SourceQuotaExceededException() { super("SOURCE_STORAGE_QUOTA_EXCEEDED"); }
    }
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final int MAX_FILES = 125;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static
    {
        for (int index = 0; index < LOCKS.length; index++) LOCKS[index] = new ReentrantLock();
    }

    private final Path root;
    private final PrevizWorkspaceAccess access;
    private final VersionPointer pointer;
    private final long sourceQuota;
    private final int maximumVersions;
    private final PrevizStoragePolicy policy;

    /** A transactional project head may replace the legacy filesystem pointer. */
    public interface VersionPointer {
        String current(Long actor, String project, String legacyVersion) throws IOException;
        void compareAndSet(Long actor, String project, String expectedVersion, String version) throws IOException;
    }

    public int maximumFileBytes() { return MAX_FILE_BYTES; }
    public record FileInfo(String path, long size, long modifiedMillis) { }
    public List<FileInfo> inspect(Long userId,String projectId,String version) throws IOException {
        var files=read(userId,projectId,version);
        Path directory=projectPath(projectId).resolve("versions").resolve(version);
        List<FileInfo> result=new ArrayList<>();
        for(var file:files.entrySet())result.add(new FileInfo(file.getKey(),file.getValue().length,
                Files.getLastModifiedTime(directory.resolve(file.getKey()),LinkOption.NOFOLLOW_LINKS).toMillis()));
        return List.copyOf(result);
    }

    public PrevizSourceStore(Path root, PrevizWorkspaceAccess access) throws IOException
    {
        this(root, access, null);
    }

    public PrevizSourceStore(Path root, PrevizWorkspaceAccess access, VersionPointer pointer) throws IOException
    {
        this(root, access, pointer, 536870912L, 4096, PrevizStoragePolicy.DEFAULT);
    }

    public PrevizSourceStore(Path root, PrevizWorkspaceAccess access, VersionPointer pointer,
                             long sourceQuota, int maximumVersions, PrevizStoragePolicy policy) throws IOException
    {
        if (access == null) throw new IllegalArgumentException("必须提供项目权限校验器");
        if (sourceQuota < MAX_SOURCE_BYTES || maximumVersions < 1 || policy == null)
            throw new IllegalArgumentException("源码存储参数无效");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("工作区根目录不能是链接");
        this.root = root.toRealPath();
        this.access = access;
        this.pointer = pointer;
        this.sourceQuota = sourceQuota;
        this.maximumVersions = maximumVersions;
        this.policy = policy;
    }

    public String currentVersion(Long userId, String projectId) throws IOException
    {
        authorize(userId, projectId, false);
        Path project = projectPath(projectId);
        String legacy = Files.exists(project, NO_LINKS) ? readPointer(project) : null;
        return pointer == null ? legacy : pointer.current(userId, projectId, legacy);
    }

    public String save(Long userId, String projectId, String expectedVersion,
                       Map<String, byte[]> sourceFiles) throws IOException
    {
        authorize(userId, projectId, true);
        if (expectedVersion != null) validateVersion(expectedVersion);
        Map<String, byte[]> files = validateFiles(sourceFiles);
        String version = digest(files);
        ReentrantLock lock = LOCKS[Math.floorMod(root.resolve(projectId).hashCode(), LOCKS.length)];
        lock.lock();
        try
        {
            Path project = projectPath(projectId);
            createSafeDirectory(project);
            Path lockPath = project.resolve(".write-lock");
            rejectLink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock())
            {
                // Recheck permissions after waiting for a concurrent writer.
                authorize(userId, projectId, true);
                String legacy = readPointer(project);
                String current = pointer == null ? legacy : pointer.current(userId, projectId, legacy);
                if (version.equals(current))
                {
                    Path existing = project.resolve("versions").resolve(version);
                    rejectLink(existing);
                    if (!version.equals(digest(readVersionFiles(existing))))
                        throw new IOException("SOURCE_CORRUPT: 已有源码版本校验失败");
                    return version;
                }
                if (!java.util.Objects.equals(expectedVersion, current))
                    throw new SourceVersionConflictException();
                Path versions = project.resolve("versions");
                createSafeDirectory(versions);
                Path target = versions.resolve(version);
                if (!Files.exists(target, NO_LINKS))
                {
                    requireSourceCapacity(versions, files);
                    Path staging = versions.resolve(".pending-" + UUID.randomUUID());
                    Files.createDirectory(staging);
                    try
                    {
                        for (Map.Entry<String, byte[]> file : files.entrySet())
                        {
                            Path destination = staging.resolve(file.getKey());
                            Files.createDirectories(destination.getParent());
                            try (FileChannel output = FileChannel.open(destination,
                                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
                            {
                                ByteBuffer data = ByteBuffer.wrap(file.getValue());
                                while (data.hasRemaining()) output.write(data);
                                output.force(true);
                            }
                        }
                        atomicMove(staging, target, false);
                    }
                    finally
                    {
                        if (Files.exists(staging, NO_LINKS)) removeStaging(staging, versions);
                    }
                }
                else
                {
                    rejectLink(target);
                    if (!version.equals(digest(readVersionFiles(target))))
                        throw new IOException("SOURCE_CORRUPT: 已有源码版本校验失败");
                }
                if (pointer == null) writePointer(project, version);
                else pointer.compareAndSet(userId, projectId, current, version);
                return version;
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /** New-version admission shares the source write lock; historical versions and replays remain usable. */
    private void requireSourceCapacity(Path versions, Map<String, byte[]> files) throws IOException
    {
        long count = 0, bytes = 0;
        try (Stream<Path> entries = Files.list(versions)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                rejectLink(entry);
                if (!Files.isDirectory(entry, NO_LINKS)) throw new IOException("SOURCE_STORAGE_INVALID");
                // Pending directories consume space too. They are never silently removed during admission.
                if (entry.getFileName().toString().matches("[0-9a-f]{64}")) count++;
                try (Stream<Path> contents = Files.walk(entry)) {
                    var contentIterator = contents.iterator();
                    while (contentIterator.hasNext()) {
                        Path content = contentIterator.next();
                        rejectLink(content);
                        if (Files.isRegularFile(content, NO_LINKS)) bytes = Math.addExact(bytes, Files.size(content));
                        else if (!Files.isDirectory(content, NO_LINKS)) throw new IOException("SOURCE_STORAGE_INVALID");
                    }
                }
            }
        }
        long nextBytes = bytes;
        for (byte[] content : files.values()) nextBytes = Math.addExact(nextBytes, content.length);
        if (count >= policy.limit(PrevizStoragePolicy.Key.SOURCE_VERSIONS, maximumVersions)
                || nextBytes > policy.limit(PrevizStoragePolicy.Key.SOURCE_BYTES, sourceQuota))
            throw new SourceQuotaExceededException();
    }

    public Map<String, byte[]> read(Long userId, String projectId, String version) throws IOException
    {
        authorize(userId, projectId, false);
        validateVersion(version);
        Path project = projectPath(projectId);
        Path versions = project.resolve("versions");
        rejectLink(versions);
        Path directory = versions.resolve(version);
        rejectLink(directory);
        Map<String, byte[]> files = readVersionFiles(directory);
        if (!version.equals(digest(files))) throw new IOException("SOURCE_CORRUPT: 源码版本校验失败");
        return files;
    }

    /** Maintenance-only adjudication after the original executor has been stopped/fenced.
     * No HTTP/tool entry point may call this. It never changes a source pointer or task state. */
    public void recordVerifiedVersionConflict(Long actor,String projectId,String operationKey,String expected,
            Map<String,byte[]> desired,String observedCurrent,String auditReference)throws IOException {
        authorize(actor,projectId,true);validateVersion(expected);validateVersion(observedCurrent);
        if(auditReference==null||!auditReference.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("审计标识无效");
        String operation=operationDigest(operationKey),target=digest(validateFiles(desired));
        ReentrantLock lock=LOCKS[Math.floorMod(root.resolve(projectId).hashCode(),LOCKS.length)];lock.lock();
        try {
            Path project=projectPath(projectId),lockPath=project.resolve(".write-lock");rejectLink(lockPath);
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                authorize(actor,projectId,true);
                String current=currentVersion(actor,projectId);
                Path candidate=project.resolve("versions").resolve(target);rejectLink(candidate);
                if(!java.util.Objects.equals(current,observedCurrent)||java.util.Objects.equals(expected,current)
                        ||Files.exists(candidate,NO_LINKS))throw new IOException("SOURCE_CONFLICT_PROOF_UNAVAILABLE");
                Path directory=project.resolve("write-audits");createSafeDirectory(directory);
                Path receipt=directory.resolve(operation+".properties");rejectLink(receipt);
                if(Files.exists(receipt,NO_LINKS)){
                    if(!hasVerifiedVersionConflict(actor,projectId,operationKey,expected,desired))throw new IOException("SOURCE_AUDIT_IDENTITY_CONFLICT");
                    return;
                }
                var proof=new java.util.Properties();proof.setProperty("schema","aid.source-conflict-proof/1");
                proof.setProperty("operation",operation);proof.setProperty("expected",expected);proof.setProperty("target",target);
                proof.setProperty("observed",current);proof.setProperty("audit",auditReference);
                var bytes=new java.io.ByteArrayOutputStream();proof.store(bytes,null);
                Path temporary=Files.createTempFile(directory,".proof-",".tmp");
                try {
                    try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.WRITE)){
                        ByteBuffer data=ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())output.write(data);output.force(true);
                    }
                    atomicMove(temporary,receipt,false);
                }finally{Files.deleteIfExists(temporary);}
            }
        }finally{lock.unlock();}
    }

    public boolean hasVerifiedVersionConflict(Long actor,String projectId,String operationKey,String expected,
            Map<String,byte[]> desired)throws IOException {
        authorize(actor,projectId,true);validateVersion(expected);
        String operation=operationDigest(operationKey),target=digest(validateFiles(desired));
        Path directory=projectPath(projectId).resolve("write-audits");rejectLink(directory);
        Path receipt=directory.resolve(operation+".properties");rejectLink(receipt);
        if(!Files.exists(receipt,NO_LINKS))return false;
        if(Files.size(receipt)>4096)throw new IOException("SOURCE_AUDIT_INVALID");
        var proof=new java.util.Properties();try(var input=Files.newInputStream(receipt,LinkOption.NOFOLLOW_LINKS)){proof.load(input);}
        if(!proof.stringPropertyNames().equals(java.util.Set.of("schema","operation","expected","target","observed","audit"))
                ||!"aid.source-conflict-proof/1".equals(proof.getProperty("schema")))throw new IOException("SOURCE_AUDIT_INVALID");
        if(!operation.equals(proof.getProperty("operation"))||!expected.equals(proof.getProperty("expected"))||!target.equals(proof.getProperty("target")))
            throw new IOException("SOURCE_AUDIT_IDENTITY_CONFLICT");
        validateVersion(proof.getProperty("observed"));
        if(expected.equals(proof.getProperty("observed"))||!proof.getProperty("audit").matches("[A-Za-z0-9_.:-]{1,160}"))throw new IOException("SOURCE_AUDIT_INVALID");
        return true;
    }

    private static String operationDigest(String key){
        if(key==null||key.isBlank()||key.length()>256)throw new IllegalArgumentException("缺少操作身份");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }

    private void authorize(Long userId, String projectId, boolean write)
    {
        if (userId == null || userId <= 0 || projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("项目或用户身份无效");
        if (write) access.requireWrite(userId, projectId);
        else access.requireRead(userId, projectId);
    }

    private Path projectPath(String projectId) throws IOException
    {
        Path result = root.resolve(projectId);
        rejectLink(result);
        return result;
    }

    private static void validateVersion(String version)
    {
        if (version == null || !version.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("源码版本无效");
    }

    static Map<String, byte[]> validateFiles(Map<String, byte[]> input)
    {
        if (input == null || input.isEmpty() || input.size() > MAX_FILES)
            throw new IllegalArgumentException("源码文件数量无效");
        Map<String, byte[]> result = new TreeMap<>();
        List<String> folded = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, byte[]> entry : input.entrySet())
        {
            String name = entry.getKey();
            if (name == null || name.length() > 240 || name.startsWith("/") || name.contains("\\")
                    || name.contains(":") || name.indexOf('\0') >= 0)
                throw new IllegalArgumentException("源码路径无效");
            String[] segments = name.split("/", -1);
            if (segments.length > 16) throw new IllegalArgumentException("源码路径过深");
            for (String part : segments)
            {
                String lower = part.toLowerCase(java.util.Locale.ROOT);
                if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".")
                        || part.chars().anyMatch(character -> character < 32 || character == 127)
                        || part.endsWith(" ") || part.matches(".*[<>\"|?*].*")
                        || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")
                        || List.of("node_modules", ".git", "package.json", "package-lock.json").contains(lower))
                    throw new IllegalArgumentException("源码路径包含禁止项");
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (folded.stream().anyMatch(existing -> existing.equals(lower)
                    || existing.startsWith(lower + "/") || lower.startsWith(existing + "/")))
                throw new IllegalArgumentException("源码路径冲突");
            folded.add(lower);
            byte[] data = entry.getValue();
            if (data == null || data.length > MAX_FILE_BYTES || (total += data.length) > MAX_SOURCE_BYTES)
                throw new IllegalArgumentException("源码文件超过限额");
            result.put(name, data.clone());
        }
        return result;
    }

    private static String digest(Map<String, byte[]> files)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(files).forEach((name, data) -> {
                byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(8).putInt(encoded.length).putInt(data.length).array());
                digest.update(encoded);
                digest.update(data);
            });
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, byte[]> readVersionFiles(Path directory) throws IOException
    {
        if (!Files.isDirectory(directory, NO_LINKS)) throw new IOException("源码版本不存在");
        Map<String, byte[]> files = new TreeMap<>();
        long totalBytes = 0;
        int visited = 0;
        try (Stream<Path> paths = Files.walk(directory, 17))
        {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext())
            {
                Path file = iterator.next();
                if (++visited > MAX_FILES * 17) throw new IOException("源码目录数量超限");
                rejectLink(file);
                if (Files.isDirectory(file, NO_LINKS)) continue;
                if (!Files.isRegularFile(file, NO_LINKS) || Files.size(file) > MAX_FILE_BYTES)
                    throw new IOException("源码文件无效");
                if (files.size() >= MAX_FILES) throw new IOException("源码文件数量超限");
                if ((totalBytes += Files.size(file)) > MAX_SOURCE_BYTES) throw new IOException("源码总量超限");
                files.put(directory.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return validateFiles(files);
    }

    private static void rejectLink(Path path) throws IOException
    {
        if (Files.isSymbolicLink(path)) throw new IOException("工作区不能包含链接");
        if (Files.exists(path, NO_LINKS) && !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("工作区路径不能通过链接跳转");
    }

    private static void createSafeDirectory(Path path) throws IOException
    {
        rejectLink(path);
        if (!Files.exists(path, NO_LINKS)) Files.createDirectory(path);
        if (!Files.isDirectory(path, NO_LINKS)) throw new IOException("工作区路径不是目录");
        rejectLink(path);
    }

    private static String readPointer(Path project) throws IOException
    {
        Path pointer = project.resolve("CURRENT");
        rejectLink(pointer);
        if (!Files.exists(pointer, NO_LINKS)) return null;
        if (Files.size(pointer) != 64) throw new IOException("当前版本指针损坏");
        String version = Files.readString(pointer, StandardCharsets.US_ASCII);
        validateVersion(version);
        return version;
    }

    private static void writePointer(Path project, String version) throws IOException
    {
        Path staging = project.resolve(".head-" + UUID.randomUUID());
        try
        {
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            {
                ByteBuffer data = StandardCharsets.US_ASCII.encode(version);
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            atomicMove(staging, project.resolve("CURRENT"), true);
        }
        finally
        {
            Files.deleteIfExists(staging);
        }
    }

    private static void atomicMove(Path source, Path target, boolean replace) throws IOException
    {
        try
        {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException exception)
        {
            throw new IOException("工作区文件系统必须支持原子移动", exception);
        }
    }

    private static void removeStaging(Path staging, Path versions) throws IOException
    {
        if (!staging.getParent().equals(versions) || !staging.getFileName().toString().startsWith(".pending-"))
            throw new IOException("临时源码目录越界");
        rejectLink(staging);
        try (Stream<Path> paths = Files.walk(staging))
        {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
