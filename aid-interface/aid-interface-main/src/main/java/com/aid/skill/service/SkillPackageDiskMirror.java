package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** 为允许缓存的当前不可变 Skill 版本维护非静态目录磁盘镜像。 */
@Component
@Slf4j
public class SkillPackageDiskMirror {
    private static final String OWNED_SUBTREE = ".aid-skill-package-cache-v1";
    private static final String OWNER_MARKER = ".aid-skill-cache-owner";
    private static final String OWNER_MARKER_CONTENT = "AID_SKILL_PACKAGE_CACHE_V1\n";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[a-zA-Z0-9._-]{1,128}");
    private static final int LOCK_STRIPES = 64;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path root;
    private final int maxPackages;
    private final long maxBytes;
    private final ReentrantLock maintenanceLock = new ReentrantLock();
    private final ReentrantLock[] locks = createLocks();

    public SkillPackageDiskMirror(@Value("${aid.skill.mirror-root:}") String configuredRoot,
                                  @Value("${aid.skill.mirror-max-packages:128}") int maxPackages,
                                  @Value("${aid.skill.mirror-max-bytes:67108864}") long maxBytes) {
        String fallback = Path.of(System.getProperty("java.io.tmpdir"),
                "aid-private", "skill-packages").toString();
        Path configuredBase = Path.of(StrUtil.blankToDefault(StrUtil.trim(configuredRoot), fallback))
                .toAbsolutePath().normalize();
        rejectUnsafeBase(configuredBase);
        this.root = configuredBase.resolve(OWNED_SUBTREE).toAbsolutePath().normalize();
        this.maxPackages = Math.max(1, maxPackages);
        this.maxBytes = Math.max(1024L * 1024L, maxBytes);
    }

    public Optional<Path> materialize(AidSkill skill, AidSkillVersion version,
                                      Map<String, String> files, BooleanSupplier authorityFence) {
        if (!eligibleCurrent(skill, version) || files == null || files.isEmpty()) return Optional.empty();
        ReentrantLock lock = lock(skill.getId());
        maintenanceLock.lock();
        lock.lock();
        try {
            if (authorityFence == null || !authorityFence.getAsBoolean()) return Optional.empty();
            ensureOwnedRoot();
            Path skillRoot = skillRoot(skill);
            Path target = resolveUnder(skillRoot, version.getVersionCode(), version.getPackageDigest());
            long expectedBytes = utf8PayloadBytes(files);
            if (expectedBytes > maxBytes) {
                // The database remains authoritative. Never evict other valid mirrors for a
                // package that cannot fit even when it is the only cached package.
                safeDelete(skillRoot);
                log.info("Skill磁盘镜像超过单包上限，跳过落盘, skillId={}, versionId={}, bytes={}, maxBytes={}",
                        skill.getId(), version.getId(), expectedBytes, maxBytes);
                return Optional.empty();
            }
            if (secureDirectory(target)) {
                try {
                    TreeStats actual = treeStats(target);
                    if (actual.fileCount() == files.size() && actual.sizeBytes() == expectedBytes) {
                        removeOtherTargets(skillRoot, target);
                        return enforceQuota(target) ? Optional.of(target) : Optional.empty();
                    }
                } catch (IOException corrupt) {
                    log.warn("Skill磁盘镜像树无效，重新物化, skillId={}, versionId={}, errorType={}",
                            skill.getId(), version.getId(), corrupt.getClass().getSimpleName());
                }
            }
            safeDelete(skillRoot);
            ensureSecureDirectory(skillRoot);
            Path staging = resolveUnder(skillRoot, ".staging-" + UUID.randomUUID());
            try {
                ensureSecureDirectory(staging);
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    Path file = resolveRelative(staging, entry.getKey());
                    ensureSecureDirectory(file.getParent());
                    Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    privatePermissions(file, FILE_PERMISSIONS);
                }
                TreeStats staged = treeStats(staging);
                if (staged.fileCount() != files.size() || staged.sizeBytes() != expectedBytes
                        || staged.sizeBytes() > maxBytes) {
                    safeDelete(staging);
                    log.warn("Skill磁盘镜像写入大小不一致，跳过落盘, skillId={}, versionId={}, expectedBytes={}, actualBytes={}",
                            skill.getId(), version.getId(), expectedBytes, staged.sizeBytes());
                    return Optional.empty();
                }
                ensureSecureDirectory(target.getParent());
                moveDirectory(staging, target);
                TreeStats materialized = treeStats(target);
                if (materialized.fileCount() != files.size()
                        || materialized.sizeBytes() != expectedBytes
                        || materialized.sizeBytes() > maxBytes) {
                    safeDelete(skillRoot);
                    return Optional.empty();
                }
                removeOtherTargets(skillRoot, target);
                return enforceQuota(target) && secureDirectory(target)
                        ? Optional.of(target) : Optional.empty();
            } catch (IOException error) {
                safeDelete(staging);
                log.warn("Skill磁盘镜像创建失败, skillId={}, versionId={}, errorType={}",
                        skill.getId(), version.getId(), error.getClass().getSimpleName());
                return Optional.empty();
            }
        } catch (IOException | RuntimeException error) {
            log.warn("Skill磁盘镜像不可用, skillId={}, versionId={}, errorType={}",
                    skill.getId(), version.getId(), error.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            lock.unlock();
            maintenanceLock.unlock();
        }
    }

    public Optional<String> read(AidSkill skill, AidSkillVersion version, String relativePath) {
        if (!eligibleCurrent(skill, version)) return Optional.empty();
        maintenanceLock.lock();
        try {
            ensureOwnedRoot();
            Path base = resolveUnder(skillRoot(skill), version.getVersionCode(), version.getPackageDigest());
            Path file = resolveRelative(base, relativePath);
            if (!secureDirectory(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || hasSymbolicLinkBetween(root, file)) return Optional.empty();
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            log.warn("Skill磁盘镜像读取失败, skillId={}, versionId={}, path={}, errorType={}",
                    skill.getId(), version.getId(), relativePath, error.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            maintenanceLock.unlock();
        }
    }

    public void evictSkill(AidSkill skill) {
        if (skill == null || skill.getId() == null) return;
        ReentrantLock lock = lock(skill.getId());
        maintenanceLock.lock();
        lock.lock();
        try {
            ensureOwnedRoot();
            safeDelete(skillRoot(skill));
        } catch (IOException | RuntimeException error) {
            log.warn("Skill磁盘镜像清理不可用, skillId={}, errorType={}",
                    skill.getId(), error.getClass().getSimpleName());
        } finally {
            lock.unlock();
            maintenanceLock.unlock();
        }
    }

    /** 磁盘只是可重建缓存；启动时清空旧状态后只预热数据库当前版本。 */
    public void resetAtStartup() {
        maintenanceLock.lock();
        try {
            ensureOwnedRoot();
            try (var children = Files.list(root)) {
                for (Path child : children.toList()) {
                    if (!OWNER_MARKER.equals(child.getFileName().toString())) safeDelete(child);
                }
            }
        } catch (IOException | RuntimeException error) {
            log.warn("Skill磁盘镜像启动清理失败, errorType={}", error.getClass().getSimpleName());
        } finally {
            maintenanceLock.unlock();
        }
    }

    private boolean eligibleCurrent(AidSkill skill, AidSkillVersion version) {
        if (skill == null || version == null || !Boolean.TRUE.equals(skill.getCacheToDisk())
                || !"0".equals(skill.getStatus()) || !"0".equals(skill.getDelFlag())
                || !java.util.Objects.equals(skill.getCurrentVersionId(), version.getId())) return false;
        return "PLATFORM".equals(skill.getOwnerType());
    }

    private Path skillRoot(AidSkill skill) {
        String ownerType = safeSegment(StrUtil.blankToDefault(skill.getOwnerType(), "PLATFORM").toLowerCase());
        String owner = "USER".equals(skill.getOwnerType())
                ? safeSegment(String.valueOf(skill.getOwnerUserId())) : "platform";
        return resolveUnder(root, ownerType, owner, safeSegment(skill.getSkillCode()));
    }

    private void removeOtherTargets(Path skillRoot, Path keep) {
        if (!secureDirectory(skillRoot)) return;
        Path keepVersion = keep.getParent();
        try (var versions = Files.list(skillRoot)) {
            for (Path version : versions.toList()) {
                if (!version.equals(keepVersion)) {
                    safeDelete(version);
                    continue;
                }
                if (!secureDirectory(version)) continue;
                try (var digests = Files.list(version)) {
                    for (Path digest : digests.toList()) if (!digest.equals(keep)) safeDelete(digest);
                }
            }
        } catch (IOException error) {
            log.warn("Skill磁盘镜像历史清理失败, target={}, errorType={}",
                    skillRoot, error.getClass().getSimpleName());
        }
    }

    /** 不保留无界历史；只在私有根下按数量和字节上限清理最旧当前镜像。 */
    private boolean enforceQuota(Path keep) throws IOException {
        List<MirrorTarget> targets = scanTargets();
        MirrorTarget kept = targets.stream().filter(target -> target.path().equals(keep))
                .findFirst().orElse(null);
        if (kept == null || kept.sizeBytes() > maxBytes) {
            safeDelete(keep.getParent().getParent());
            pruneToQuota(scanTargets(), null);
            return false;
        }
        pruneToQuota(targets, keep);
        QuotaUsage usage = quotaUsage(scanTargets());
        if (usage.packageCount() <= maxPackages && usage.sizeBytes() <= maxBytes
                && secureDirectory(keep)) {
            return true;
        }
        // If another owned target could not be removed, do not return a mirror while the
        // aggregate hard boundary is exceeded. Drop this new target, then best-effort prune
        // the remaining owned cache without protecting any package.
        safeDelete(keep.getParent().getParent());
        pruneToQuota(scanTargets(), null);
        QuotaUsage residual = quotaUsage(scanTargets());
        if (residual.packageCount() > maxPackages || residual.sizeBytes() > maxBytes) {
            log.warn("Skill磁盘镜像清理后仍超过上限, packages={}, bytes={}, maxPackages={}, maxBytes={}",
                    residual.packageCount(), residual.sizeBytes(), maxPackages, maxBytes);
        }
        return false;
    }

    private List<MirrorTarget> scanTargets() throws IOException {
        List<MirrorTarget> targets = new ArrayList<>();
        List<Path> paths;
        try (var stream = Files.walk(root, 5)) {
            paths = stream.toList();
        }
        for (Path path : paths) {
            if (root.relativize(path).getNameCount() != 5
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || hasSymbolicLinkBetween(root, path)) continue;
            try {
                targets.add(new MirrorTarget(path, treeStats(path).sizeBytes(),
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()));
            } catch (IOException corrupt) {
                safeDelete(path.getParent().getParent());
                log.warn("清理无效Skill磁盘镜像树, target={}, errorType={}",
                        path, corrupt.getClass().getSimpleName());
            }
        }
        return targets;
    }

    private void pruneToQuota(List<MirrorTarget> targets, Path keep) {
        QuotaUsage usage = quotaUsage(targets);
        long totalBytes = usage.sizeBytes();
        int remaining = usage.packageCount();
        targets.sort(Comparator.comparingLong(MirrorTarget::lastModifiedMillis));
        Set<Path> removedSkillRoots = new java.util.HashSet<>();
        for (MirrorTarget target : targets) {
            if (remaining <= maxPackages && totalBytes <= maxBytes) break;
            if (target.path().equals(keep)) continue;
            Path skillRoot = target.path().getParent().getParent();
            if (!removedSkillRoots.add(skillRoot)) continue;
            safeDelete(skillRoot);
            for (MirrorTarget removed : targets) {
                if (removed.path().startsWith(skillRoot)) {
                    remaining--;
                    totalBytes = Math.max(0L, totalBytes - removed.sizeBytes());
                }
            }
        }
    }

    private QuotaUsage quotaUsage(List<MirrorTarget> targets) {
        long totalBytes = 0L;
        for (MirrorTarget target : targets) {
            if (Long.MAX_VALUE - totalBytes < target.sizeBytes()) {
                totalBytes = Long.MAX_VALUE;
                break;
            }
            totalBytes += target.sizeBytes();
        }
        return new QuotaUsage(targets.size(), totalBytes);
    }

    private long utf8PayloadBytes(Map<String, String> files) {
        long total = 0L;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getValue() == null) throw new ServiceException("Skill镜像文件内容无效");
            int bytes = entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (Long.MAX_VALUE - total < bytes) return Long.MAX_VALUE;
            total += bytes;
        }
        return total;
    }

    private TreeStats treeStats(Path target) throws IOException {
        final long[] total = {0L};
        final int[] files = {0};
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    throw new IOException("Unexpected file in Skill mirror");
                }
                if (Long.MAX_VALUE - total[0] < attrs.size()) total[0] = Long.MAX_VALUE;
                else total[0] += attrs.size();
                files[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return new TreeStats(files[0], total[0]);
    }

    private void moveDirectory(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ignored) {
            safeDelete(staging);
        }
    }

    private Path resolveUnder(Path base, String... segments) {
        Path result = base;
        for (String segment : segments) result = result.resolve(safeSegment(segment));
        result = result.toAbsolutePath().normalize();
        Path normalizedBase = base.toAbsolutePath().normalize();
        if (!result.startsWith(normalizedBase)) throw new ServiceException("Skill镜像路径无效");
        return result;
    }

    private Path resolveRelative(Path base, String relativePath) {
        if (StrUtil.isBlank(relativePath) || relativePath.startsWith("/") || relativePath.contains("\\"))
            throw new ServiceException("Skill资源路径无效");
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
                throw new ServiceException("Skill资源路径无效");
        }
        Path result = base.resolve(relativePath).toAbsolutePath().normalize();
        if (!result.startsWith(base.toAbsolutePath().normalize())) throw new ServiceException("Skill资源路径无效");
        return result;
    }

    private String safeSegment(String value) {
        if (StrUtil.isBlank(value) || !SAFE_SEGMENT.matcher(value).matches()
                || ".".equals(value) || "..".equals(value)) {
            throw new ServiceException("Skill镜像身份无效");
        }
        return value;
    }

    private void ensureSecureDirectory(Path directory) throws IOException {
        if (!directory.toAbsolutePath().normalize().startsWith(root)) {
            throw new ServiceException("Skill镜像路径无效");
        }
        if (hasSymbolicLinkInPath(directory)) throw new ServiceException("Skill镜像路径不安全");
        Files.createDirectories(directory);
        if (hasSymbolicLinkInPath(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("Skill镜像路径不安全");
        }
        privatePermissions(directory, DIRECTORY_PERMISSIONS);
    }

    /** 只在固定专用子树内写入；非空旧目录缺少归属标记时始终拒绝。 */
    private void ensureOwnedRoot() throws IOException {
        Path marker = root.resolve(OWNER_MARKER);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || hasSymbolicLinkInPath(root)) {
                throw new ServiceException("Skill镜像根目录不安全");
            }
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && Files.size(marker) == OWNER_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8).length
                    && OWNER_MARKER_CONTENT.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                privatePermissions(root, DIRECTORY_PERMISSIONS);
                return;
            }
            try (var children = Files.list(root)) {
                if (children.findAny().isPresent()) {
                    throw new ServiceException("Skill镜像专用目录缺少归属标记");
                }
            }
        } else {
            if (hasSymbolicLinkInPath(root.getParent())) throw new ServiceException("Skill镜像路径不安全");
            Files.createDirectories(root);
        }
        if (hasSymbolicLinkInPath(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("Skill镜像根目录不安全");
        }
        Files.writeString(marker, OWNER_MARKER_CONTENT, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        privatePermissions(root, DIRECTORY_PERMISSIONS);
        privatePermissions(marker, FILE_PERMISSIONS);
    }

    private boolean secureDirectory(Path directory) {
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                && !hasSymbolicLinkInPath(directory);
    }

    private boolean hasSymbolicLinkBetween(Path base, Path target) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedBase)) return true;
        Path current = normalizedBase;
        if (Files.isSymbolicLink(current)) return true;
        for (Path segment : normalizedBase.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private boolean hasSymbolicLinkInPath(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        if (current == null) return true;
        for (Path segment : normalized) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private void safeDelete(Path target) {
        if (target == null) return;
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root) || hasSymbolicLinkInPath(root)
                || hasSymbolicLinkBetween(root, normalized)) {
            log.error("拒绝删除越界Skill镜像, target={}", normalized);
            return;
        }
        try {
            deleteTree(normalized);
        } catch (IOException error) {
            log.warn("Skill磁盘镜像清理失败, target={}, errorType={}",
                    normalized, error.getClass().getSimpleName());
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void privatePermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX filesystems rely on the process-private configured root ACL.
        }
    }

    private ReentrantLock lock(Long skillId) {
        return locks[Math.floorMod(Long.hashCode(skillId), locks.length)];
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] result = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < result.length; index++) result[index] = new ReentrantLock();
        return result;
    }

    private record MirrorTarget(Path path, long sizeBytes, long lastModifiedMillis) { }
    private record TreeStats(int fileCount, long sizeBytes) { }
    private record QuotaUsage(int packageCount, long sizeBytes) { }

    private static void rejectUnsafeBase(Path base) {
        if (base.getParent() == null) {
            throw new IllegalArgumentException("aid.skill.mirror-root must not be a filesystem root");
        }
        String value = base.toString().replace('\\', '/').toLowerCase();
        if (value.endsWith("/static") || value.endsWith("/public") || value.endsWith("/webapp")
                || value.endsWith("/wwwroot") || value.contains("/src/main/resources/static/")
                || value.contains("/target/classes/static/") || value.contains("/resources/public/")) {
            throw new IllegalArgumentException("aid.skill.mirror-root must be a private non-static directory");
        }
    }
}
