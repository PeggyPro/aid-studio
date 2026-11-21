package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillResource;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillResourceMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 加载不可变 Skill 版本正文，并按版本包清单确定性选择参考资源。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillPackageResourceLoader {
    private static final int MAX_SELECTED_RESOURCES = 4;
    private static final int MAX_DATABASE_PACKAGES = 64;
    private static final long MAX_DATABASE_PACKAGE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_DATABASE_VERSIONS = 64;
    private static final long MAX_DATABASE_VERSION_BYTES = 8L * 1024L * 1024L;
    private static final int STARTUP_PAGE_SIZE = 16;
    private static final int VERSION_BATCH_SIZE = 100;
    private static final int MAX_STARTUP_SCANNED_SKILLS = MAX_DATABASE_VERSIONS * 4;
    private static final int MAX_VERSION_LOAD_ATTEMPTS = 3;
    private static final int DATABASE_LOCK_STRIPES = 64;
    private static final String NORMAL = "0";
    private static final String DATABASE_SOURCE = "DATABASE";
    private static final Set<String> PUBLISHED_STATUSES = Set.of("PRIVATE", "PUBLISHED");
    private static final String STANDARD_ROOT = "skills/";
    private static final String ARCHIVE_ROOT = "skills-archive/";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ARCHIVE_MANIFEST_URL = Pattern.compile(
            ".*/skills-archive/([0-9a-f]{64})/aid-skill\\.json$");
    private final AidSkillMapper skillMapper;
    private final AidSkillVersionMapper versionMapper;
    private final AidSkillResourceMapper resourceMapper;
    private final AidSkillRelationMapper relationMapper;
    private final SkillPackageDiskMirror diskMirror;
    private final Map<String, ClasspathPackage> classpathPackageCache = new ConcurrentHashMap<>();
    private final Object archiveCatalogLock = new Object();
    private volatile ArchiveCatalog archiveCatalog;
    private final ReentrantLock[] databaseLoadLocks = createDatabaseLoadLocks();
    private final Object databaseCacheLock = new Object();
    private final Map<Long, DatabasePackage> databaseCache = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<Long, CachedVersion> versionCache = new LinkedHashMap<>(16, 0.75F, true);
    private long databasePackageBytes;
    private long databaseVersionBytes;
    private long databaseCacheGeneration;

    /**
     * 读取不可变版本正文。每次命中都先读取小字段元数据，正文缓存仅在身份、状态、摘要和更新时间一致时复用。
     */
    public AidSkillVersion loadVersion(Long versionId) {
        if (versionId == null || versionId <= 0) {
            return null;
        }
        boolean cacheAllowed = false;
        boolean authoritativeMissing = !TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 0; attempt < MAX_VERSION_LOAD_ATTEMPTS; attempt++) {
            long observedGeneration = cacheGeneration();
            VersionMetadata metadata = selectVersionMetadata(versionId);
            if (metadata == null) {
                if (authoritativeMissing) {
                    invalidateCaches(null, versionId);
                }
                return null;
            }
            cacheAllowed = cacheAccessAllowed() && sharedCacheAllowed(metadata.skillId());
            ReentrantLock loadLock = databaseLoadLocks[databaseLoadLockIndex(versionId)];
            loadLock.lock();
            try {
                synchronized (databaseCacheLock) {
                    CachedVersion cached = cacheAllowed ? versionCache.get(versionId) : null;
                    if (cacheAllowed && cached != null && Objects.equals(cached.metadata(), metadata)) {
                        return copyVersion(cached.version());
                    }
                }
                AidSkillVersion loaded = versionMapper.selectById(versionId);
                if (loaded == null) {
                    if (authoritativeMissing) {
                        invalidateCaches(null, versionId);
                    }
                    return null;
                }
                VersionMetadata loadedMetadata = VersionMetadata.from(loaded);
                if (!Objects.equals(metadata, loadedMetadata)) {
                    continue;
                }
                CachedVersion snapshot = new CachedVersion(loadedMetadata, copyVersion(loaded),
                        versionSizeBytes(loaded));
                synchronized (databaseCacheLock) {
                    if (observedGeneration != databaseCacheGeneration || !cacheAllowed) {
                        return copyVersion(snapshot.version());
                    }
                    putCachedVersion(versionId, snapshot);
                }
                return copyVersion(snapshot.version());
            } finally {
                loadLock.unlock();
            }
        }
        log.warn("Skill版本并发变化, versionId={}", versionId);
        throw new ServiceException("Skill版本已变化");
    }

    /** 批量读取版本正文，目录热路径按固定批次查询元数据和未命中正文。 */
    public Map<Long, AidSkillVersion> loadVersions(Set<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> sortedIds = versionIds.stream().filter(Objects::nonNull)
                .filter(versionId -> versionId > 0).distinct().sorted().toList();
        if (sortedIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AidSkillVersion> result = new LinkedHashMap<>();
        for (int offset = 0; offset < sortedIds.size(); offset += VERSION_BATCH_SIZE) {
            int end = Math.min(sortedIds.size(), offset + VERSION_BATCH_SIZE);
            result.putAll(loadVersionBatch(sortedIds.subList(offset, end)));
        }
        return Map.copyOf(result);
    }

    /**
     * 动态权限读取入口；USER/PRIVATE 每次调用都以数据库身份行为准且不进入共享缓存。
     * admittedRun 只能由已校验持久化 run 归属与冻结版本的内部运行时调用方传入，不得暴露为客户端参数。
     */
    public AuthorizedPackage loadAuthorizedVersion(String skillCode, Long versionId,
                                                   Long requesterUserId, boolean admittedRun) {
        return loadAuthorizedVersion(selectIdentityByCode(skillCode), versionId, requesterUserId, admittedRun);
    }

    /** Reuse identity loaded by the server in this request; access and version checks still run. */
    public AuthorizedPackage loadAuthorizedVersion(AidSkill identity, Long versionId,
                                                   Long requesterUserId, boolean admittedRun) {
        if (identity == null) {
            throw new ServiceException("Skill不可用");
        }
        Long resolvedVersionId = versionId == null ? identity.getCurrentVersionId() : versionId;
        if (resolvedVersionId == null) throw new ServiceException("Skill尚未发布");
        if (!admittedRun) {
            if (!NORMAL.equals(identity.getStatus()) || !NORMAL.equals(identity.getDelFlag())) {
                throw new ServiceException("Skill不可用");
            }
            if (!Objects.equals(resolvedVersionId, identity.getCurrentVersionId())) {
                throw new ServiceException("Skill版本不可访问");
            }
            if ("USER".equals(identity.getOwnerType())) {
                if ("PRIVATE".equals(identity.getVisibility())) {
                    if (!Objects.equals(identity.getOwnerUserId(), requesterUserId)) {
                        throw new ServiceException("无权访问Skill");
                    }
                } else if (!"PUBLIC".equals(identity.getVisibility())) {
                    throw new ServiceException("Skill可见性无效");
                }
            } else if ("PLATFORM".equals(identity.getOwnerType())) {
                if (!"PUBLIC".equals(identity.getVisibility())) {
                    throw new ServiceException("无权访问Skill");
                }
            } else {
                throw new ServiceException("Skill归属无效");
            }
        } else if (!Set.of("PLATFORM", "USER").contains(identity.getOwnerType())) {
            throw new ServiceException("Skill归属无效");
        }
        AidSkillVersion version = loadVersion(resolvedVersionId);
        if (version == null || !Objects.equals(version.getSkillId(), identity.getId())
                || !PUBLISHED_STATUSES.contains(version.getPublishStatus())
                || (!admittedRun && "PLATFORM".equals(identity.getOwnerType())
                && (!"PUBLIC".equals(version.getVisibility())
                || !"PUBLISHED".equals(version.getPublishStatus())))
                || (!admittedRun && (!Objects.equals(version.getInvocationScope(), identity.getInvocationScope())
                || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())))) {
            throw new ServiceException("Skill版本不可用");
        }
        verifyVersionCached(identity.getSkillCode(), version);
        return new AuthorizedPackage(copySkill(identity), copyVersion(version));
    }

    /**
     * Resolve an immutable public platform package selected for a new fixed Agent binding.
     * Historical public versions remain valid when the identity's current pointer advances;
     * private/user packages and live-disabled identities must use their existing authorization paths.
     */
    public AuthorizedPackage loadAuthorizedFixedPublicPlatformVersion(String skillCode, Long versionId) {
        if (StrUtil.isBlank(skillCode) || versionId == null || versionId <= 0) {
            throw new ServiceException("Skill固定版本无效");
        }
        AidSkill identity = selectIdentityByCode(skillCode);
        if (identity == null || !"PLATFORM".equals(identity.getOwnerType())
                || !"PUBLIC".equals(identity.getVisibility())
                || !NORMAL.equals(identity.getStatus()) || !NORMAL.equals(identity.getDelFlag())) {
            throw new ServiceException("Skill不可用");
        }
        AidSkillVersion version = loadVersion(versionId);
        if (version == null || !Objects.equals(version.getSkillId(), identity.getId())
                || !"PUBLIC".equals(version.getVisibility())
                || !"PUBLISHED".equals(version.getPublishStatus())
                || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())) {
            throw new ServiceException("Skill固定版本不可用");
        }
        verifyVersionCached(identity.getSkillCode(), version);
        return new AuthorizedPackage(copySkill(identity), copyVersion(version));
    }

    /** 读取标准逻辑包文件；磁盘镜像只作为校验后的 PLATFORM/PUBLIC 加速层。 */
    public String readAuthorizedResource(String skillCode, Long versionId, String relativePath,
                                         Long requesterUserId, boolean admittedRun) {
        if (!safeRelativePath(relativePath)) throw new ServiceException("Skill资源路径无效");
        AuthorizedPackage authorized = loadAuthorizedVersion(
                skillCode, versionId, requesterUserId, admittedRun);
        AidSkill skill = authorized.skill();
        AidSkillVersion version = authorized.version();
        if ("system_prompt.md".equals(relativePath)) {
            return verifiedMirrorOrValue(skill, version, relativePath, version.getSystemPrompt());
        }
        if ("aid-skill.json".equals(relativePath)) {
            return verifiedMirrorOrValue(skill, version, relativePath, version.getManifestJson());
        }
        boolean database = isDatabasePackage(version);
        DatabasePackage databaseContent = database ? databasePackage(skillCode, version) : null;
        ClasspathPackage classpathContent = database ? null : classpathPackage(skillCode, version);
        if ("SKILL.md".equals(relativePath)) {
            String master = database ? databaseContent.skillMarkdown() : classpathContent.skillMarkdown();
            if (master == null) throw new ServiceException("Skill旧版本缺少SKILL.md，请重新发布版本");
            return database ? verifiedMirrorOrValue(skill, version, relativePath, master) : master;
        }
        List<RoutedResource> pathMatches = (database ? databaseContent.routes() : classpathContent.routes())
                .stream().filter(resource -> Objects.equals(relativePath, resource.route().getPath())).toList();
        if (pathMatches.size() != 1) throw new ServiceException("Skill资源不存在或路径冲突");
        SelectedResource matched = pathMatches.get(0).resource();
        String databaseValue = matched.content();
        String mirrored = diskMirror.read(skill, version, relativePath).orElse(null);
        if (mirrored != null && Objects.equals(SecureUtil.sha256(mirrored), matched.digest())) {
            return mirrored;
        }
        return databaseValue;
    }

    private Map<Long, AidSkillVersion> loadVersionBatch(List<Long> versionIds) {
        long observedGeneration = cacheGeneration();
        Map<Long, VersionMetadata> metadataById = selectVersionMetadata(versionIds);
        boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
        if (!transactionActive && metadataById.size() < versionIds.size()) {
            Set<Long> missing = versionIds.stream().filter(id -> !metadataById.containsKey(id))
                    .collect(Collectors.toSet());
            invalidateVersions(missing);
        }
        if (metadataById.isEmpty()) {
            return Map.of();
        }
        Set<Long> cacheableSkillIds = sharedCacheAllowedSkillIds(metadataById.values().stream()
                .map(VersionMetadata::skillId).collect(Collectors.toSet()));
        Set<Long> cacheableVersionIds = metadataById.entrySet().stream()
                .filter(entry -> cacheableSkillIds.contains(entry.getValue().skillId()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        boolean cacheAllowed = cacheAccessAllowed();
        List<ReentrantLock> loadLocks = metadataById.keySet().stream()
                .map(this::databaseLoadLockIndex).distinct().sorted()
                .map(index -> databaseLoadLocks[index]).toList();
        loadLocks.forEach(ReentrantLock::lock);
        Map<Long, AidSkillVersion> result = new LinkedHashMap<>();
        List<Long> retryIds = new ArrayList<>();
        try {
            List<Long> misses = new ArrayList<>();
            synchronized (databaseCacheLock) {
                for (Long versionId : versionIds) {
                    VersionMetadata metadata = metadataById.get(versionId);
                    if (metadata == null) {
                        continue;
                    }
                    boolean versionCacheAllowed = cacheAllowed && cacheableVersionIds.contains(versionId);
                    CachedVersion cached = versionCacheAllowed ? versionCache.get(versionId) : null;
                    if (versionCacheAllowed && cached != null && Objects.equals(cached.metadata(), metadata)) {
                        result.put(versionId, copyVersion(cached.version()));
                    } else {
                        misses.add(versionId);
                    }
                }
            }
            if (misses.isEmpty()) {
                return result;
            }
            Map<Long, AidSkillVersion> loadedById = versionMapper.selectBatchIds(misses).stream()
                    .collect(Collectors.toMap(AidSkillVersion::getId, version -> version));
            Map<Long, CachedVersion> snapshots = new LinkedHashMap<>();
            for (Long versionId : misses) {
                AidSkillVersion loaded = loadedById.get(versionId);
                VersionMetadata metadata = metadataById.get(versionId);
                if (loaded == null || !Objects.equals(metadata, VersionMetadata.from(loaded))) {
                    retryIds.add(versionId);
                    continue;
                }
                AidSkillVersion snapshot = copyVersion(loaded);
                result.put(versionId, copyVersion(snapshot));
                if (cacheableVersionIds.contains(versionId)) {
                    snapshots.put(versionId, new CachedVersion(metadata, snapshot, versionSizeBytes(loaded)));
                }
            }
            synchronized (databaseCacheLock) {
                if (cacheAllowed && observedGeneration == databaseCacheGeneration) {
                    snapshots.forEach(this::putCachedVersion);
                }
            }
        } finally {
            for (int index = loadLocks.size() - 1; index >= 0; index--) {
                loadLocks.get(index).unlock();
            }
        }
        for (Long retryId : retryIds) {
            AidSkillVersion retried = loadVersion(retryId);
            if (retried != null) {
                result.put(retryId, retried);
            }
        }
        return result;
    }

    /** 在事务提交后失效指定 Skill 的内存快照；回滚不触发，缓存故障不反向影响已提交写操作。 */
    public void refreshAfterCommit(Long skillId, Long versionId) {
        if (skillId == null && versionId == null) {
            return;
        }
        Runnable refresh = () -> {
            try {
                invalidateCaches(skillId, versionId);
                if (skillId != null) {
                    AidSkill identity = skillMapper.selectById(skillId);
                    if (identity != null) diskMirror.evictSkill(identity);
                }
            } catch (RuntimeException error) {
                log.error("Skill缓存提交后失效失败, skillId={}, versionId={}",
                        skillId, versionId, error);
            }
        };
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    log.warn("Skill缓存提交后回调不可登记, skillId={}, versionId={}",
                            skillId, versionId);
                    return;
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        refresh.run();
                    }
                });
                return;
            }
            refresh.run();
        } catch (RuntimeException error) {
            log.error("Skill缓存提交后回调登记失败, skillId={}, versionId={}",
                    skillId, versionId, error);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCurrentVersions() {
        diskMirror.resetAtStartup();
        int scanned = 0;
        int skipped = 0;
        int failed = 0;
        long lastSkillId = 0L;
        Map<String, AidSkillVersion> packages = new LinkedHashMap<>();
        Map<String, AidSkill> identities = new LinkedHashMap<>();
        while (packages.size() < MAX_DATABASE_VERSIONS && scanned < MAX_STARTUP_SCANNED_SKILLS) {
            int limit = Math.min(STARTUP_PAGE_SIZE, MAX_STARTUP_SCANNED_SKILLS - scanned);
            List<AidSkill> page;
            try {
                page = skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getCurrentVersionId,
                                AidSkill::getOwnerType, AidSkill::getOwnerUserId,
                                AidSkill::getVisibility, AidSkill::getCacheToDisk)
                        .gt(AidSkill::getId, lastSkillId)
                        .isNotNull(AidSkill::getCurrentVersionId)
                        .eq(AidSkill::getStatus, NORMAL)
                        .eq(AidSkill::getDelFlag, NORMAL)
                        .orderByAsc(AidSkill::getId)
                        .last("limit " + limit));
            } catch (RuntimeException error) {
                failed++;
                log.warn("Skill当前版本预热分页失败, afterSkillId={}, errorType={}",
                        lastSkillId, error.getClass().getSimpleName());
                break;
            }
            if (page.isEmpty()) {
                break;
            }
            lastSkillId = page.get(page.size() - 1).getId();
            scanned += page.size();
            List<AidSkill> warmable = new ArrayList<>();
            for (AidSkill skill : page) {
                if ("USER".equals(skill.getOwnerType()) && "PRIVATE".equals(skill.getVisibility())) {
                    skipped++;
                    invalidateCaches(skill.getId(), skill.getCurrentVersionId());
                    diskMirror.evictSkill(skill);
                } else {
                    warmable.add(skill);
                }
            }
            Set<Long> pageVersionIds = warmable.stream().map(AidSkill::getCurrentVersionId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, AidSkillVersion> loaded;
            try {
                loaded = loadVersions(pageVersionIds);
            } catch (RuntimeException batchError) {
                log.warn("Skill当前版本批量预热失败, afterSkillId={}, errorType={}",
                        lastSkillId, batchError.getClass().getSimpleName());
                loaded = new LinkedHashMap<>();
                for (Long versionId : pageVersionIds) {
                    try {
                        AidSkillVersion version = loadVersion(versionId);
                        if (version != null) {
                            loaded.put(versionId, version);
                        }
                    } catch (RuntimeException error) {
                        failed++;
                        log.warn("Skill当前版本正文预热失败, versionId={}, errorType={}",
                                versionId, error.getClass().getSimpleName());
                    }
                }
            }
            for (AidSkill skill : warmable) {
                if (packages.size() >= MAX_DATABASE_VERSIONS) {
                    break;
                }
                AidSkillVersion version = loaded.get(skill.getCurrentVersionId());
                if (version == null || !Objects.equals(version.getSkillId(), skill.getId())
                        || !PUBLISHED_STATUSES.contains(version.getPublishStatus())
                        || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                        || packages.containsKey(skill.getSkillCode())) {
                    skipped++;
                    invalidateCaches(skill.getId(), skill.getCurrentVersionId());
                    continue;
                }
                packages.put(skill.getSkillCode(), version);
                identities.put(skill.getSkillCode(), skill);
            }
            if (page.size() < limit) {
                break;
            }
        }
        int warmed = 0;
        if (!packages.isEmpty()) {
            try {
                verifyVersions(packages);
                warmed = packages.size();
            } catch (RuntimeException batchError) {
                log.warn("Skill当前版本包批量预热失败, candidateCount={}, errorType={}",
                        packages.size(), batchError.getClass().getSimpleName());
                for (Map.Entry<String, AidSkillVersion> entry : packages.entrySet()) {
                    try {
                        verifyVersionCached(entry.getKey(), entry.getValue());
                        warmed++;
                    } catch (RuntimeException error) {
                        failed++;
                        AidSkill skill = identities.get(entry.getKey());
                        invalidateCaches(skill == null ? null : skill.getId(), entry.getValue().getId());
                        log.warn("Skill当前版本包预热失败, skillCode={}, versionId={}, errorType={}",
                                entry.getKey(), entry.getValue().getId(), error.getClass().getSimpleName());
                    }
                }
            }
        }
        log.info("Skill当前版本缓存预热完成, warmed={}, skipped={}, failed={}, scanned={}",
                warmed, skipped, failed, scanned);
    }

    private VersionMetadata selectVersionMetadata(Long versionId) {
        AidSkillVersion metadata = versionMapper.selectOne(Wrappers.<AidSkillVersion>lambdaQuery()
                .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                        AidSkillVersion::getVersionCode, AidSkillVersion::getPublishStatus,
                        AidSkillVersion::getPackageDigest, AidSkillVersion::getStatus,
                        AidSkillVersion::getDelFlag, AidSkillVersion::getUpdateTime)
                .eq(AidSkillVersion::getId, versionId).last("limit 1"));
        return metadata == null ? null : VersionMetadata.from(metadata);
    }

    private Map<Long, VersionMetadata> selectVersionMetadata(List<Long> versionIds) {
        return versionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                                AidSkillVersion::getVersionCode, AidSkillVersion::getPublishStatus,
                                AidSkillVersion::getPackageDigest, AidSkillVersion::getStatus,
                                AidSkillVersion::getDelFlag, AidSkillVersion::getUpdateTime)
                        .in(AidSkillVersion::getId, versionIds)).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, VersionMetadata::from));
    }

    private void invalidateVersions(Set<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return;
        }
        synchronized (databaseCacheLock) {
            databaseCacheGeneration++;
            for (Long versionId : versionIds) {
                CachedVersion version = versionCache.remove(versionId);
                if (version != null) {
                    databaseVersionBytes -= version.sizeBytes();
                }
                DatabasePackage databasePackage = databaseCache.remove(versionId);
                if (databasePackage != null) {
                    databasePackageBytes -= databasePackage.sizeBytes();
                }
            }
        }
    }

    private void invalidateCaches(Long skillId, Long versionId) {
        synchronized (databaseCacheLock) {
            databaseCacheGeneration++;
            Iterator<Map.Entry<Long, CachedVersion>> versions = versionCache.entrySet().iterator();
            while (versions.hasNext()) {
                Map.Entry<Long, CachedVersion> entry = versions.next();
                if (matchesInvalidation(entry.getKey(), entry.getValue().metadata().skillId(),
                        skillId, versionId)) {
                    databaseVersionBytes -= entry.getValue().sizeBytes();
                    versions.remove();
                }
            }
            Iterator<Map.Entry<Long, DatabasePackage>> packages = databaseCache.entrySet().iterator();
            while (packages.hasNext()) {
                Map.Entry<Long, DatabasePackage> entry = packages.next();
                if (matchesInvalidation(entry.getKey(), entry.getValue().skillId(), skillId, versionId)) {
                    databasePackageBytes -= entry.getValue().sizeBytes();
                    packages.remove();
                }
            }
        }
    }

    private boolean matchesInvalidation(Long cachedVersionId, Long cachedSkillId,
                                        Long skillId, Long versionId) {
        return (versionId != null && Objects.equals(versionId, cachedVersionId))
                || (skillId != null && Objects.equals(skillId, cachedSkillId));
    }

    private boolean cacheAccessAllowed() {
        return !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private long cacheGeneration() {
        synchronized (databaseCacheLock) {
            return databaseCacheGeneration;
        }
    }

    private void putCachedVersion(Long versionId, CachedVersion cached) {
        CachedVersion previous = versionCache.remove(versionId);
        if (previous != null) {
            databaseVersionBytes -= previous.sizeBytes();
        }
        if (cached.sizeBytes() > MAX_DATABASE_VERSION_BYTES) {
            return;
        }
        versionCache.put(versionId, cached);
        databaseVersionBytes += cached.sizeBytes();
        Iterator<Map.Entry<Long, CachedVersion>> entries = versionCache.entrySet().iterator();
        while ((versionCache.size() > MAX_DATABASE_VERSIONS
                || databaseVersionBytes > MAX_DATABASE_VERSION_BYTES) && entries.hasNext()) {
            Map.Entry<Long, CachedVersion> eldest = entries.next();
            databaseVersionBytes -= eldest.getValue().sizeBytes();
            entries.remove();
        }
    }

    private void putDatabasePackage(Long versionId, DatabasePackage databasePackage) {
        DatabasePackage previous = databaseCache.remove(versionId);
        if (previous != null) {
            databasePackageBytes -= previous.sizeBytes();
        }
        if (databasePackage.sizeBytes() > MAX_DATABASE_PACKAGE_BYTES) {
            return;
        }
        databaseCache.put(versionId, databasePackage);
        databasePackageBytes += databasePackage.sizeBytes();
        Iterator<Map.Entry<Long, DatabasePackage>> entries = databaseCache.entrySet().iterator();
        while ((databaseCache.size() > MAX_DATABASE_PACKAGES
                || databasePackageBytes > MAX_DATABASE_PACKAGE_BYTES) && entries.hasNext()) {
            Map.Entry<Long, DatabasePackage> eldest = entries.next();
            databasePackageBytes -= eldest.getValue().sizeBytes();
            entries.remove();
        }
    }

    private long versionSizeBytes(AidSkillVersion version) {
        return utf8Size(version.getVersionCode()) + utf8Size(version.getVisibility())
                + utf8Size(version.getInvocationScope()) + utf8Size(version.getPublishStatus())
                + utf8Size(version.getExecutorType()) + utf8Size(version.getModelCode())
                + utf8Size(version.getModelConfigJson()) + utf8Size(version.getPackageDigest())
                + utf8Size(version.getManifestJson()) + utf8Size(version.getInputSchemaJson())
                + utf8Size(version.getOutputSchemaJson()) + utf8Size(version.getSystemPrompt())
                + utf8Size(version.getDefinitionJson()) + utf8Size(version.getStatus())
                + utf8Size(version.getDelFlag()) + utf8Size(version.getCreateBy())
                + utf8Size(version.getUpdateBy()) + utf8Size(version.getRemark());
    }

    private long databasePackageSizeBytes(String packageDigest, String versionFingerprint,
                                          List<RoutedResource> routes) {
        long bytes = utf8Size(packageDigest) + utf8Size(versionFingerprint);
        for (RoutedResource routed : routes) {
            bytes += utf8Size(JSON.toJSONString(routed.route()))
                    + utf8Size(routed.resource().resourceKey())
                    + utf8Size(routed.resource().digest())
                    + utf8Size(routed.resource().content());
        }
        return bytes;
    }

    private long utf8Size(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private AidSkillVersion copyVersion(AidSkillVersion source) {
        AidSkillVersion copy = new AidSkillVersion();
        copy.setId(source.getId());
        copy.setSkillId(source.getSkillId());
        copy.setVersionCode(source.getVersionCode());
        copy.setVisibility(source.getVisibility());
        copy.setInvocationScope(source.getInvocationScope());
        copy.setPublishStatus(source.getPublishStatus());
        copy.setExecutorType(source.getExecutorType());
        copy.setModelCode(source.getModelCode());
        copy.setModelConfigJson(source.getModelConfigJson());
        copy.setPackageDigest(source.getPackageDigest());
        copy.setManifestJson(source.getManifestJson());
        copy.setInputSchemaJson(source.getInputSchemaJson());
        copy.setOutputSchemaJson(source.getOutputSchemaJson());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setDefinitionJson(source.getDefinitionJson());
        copy.setMaxOutputTokens(source.getMaxOutputTokens());
        copy.setContextWindowTokens(source.getContextWindowTokens());
        copy.setSafetyMarginTokens(source.getSafetyMarginTokens());
        copy.setStatus(source.getStatus());
        copy.setDelFlag(source.getDelFlag());
        copy.setCreateBy(source.getCreateBy());
        copy.setCreateTime(copyDate(source.getCreateTime()));
        copy.setUpdateBy(source.getUpdateBy());
        copy.setUpdateTime(copyDate(source.getUpdateTime()));
        copy.setRemark(source.getRemark());
        return copy;
    }

    private Date copyDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    public List<SelectedResource> select(String skillCode, AidSkillVersion version,
                                         String operation, String intent) {
        if (isDatabasePackage(version)) {
            DatabasePackage databasePackage = databasePackage(skillCode, version);
            return select(databasePackage.routes(), operation, intent);
        }
        return select(classpathPackage(skillCode, version).routes(), operation, intent);
    }

    private List<SelectedResource> select(List<RoutedResource> routes, String operation, String intent) {
        String routeText = (StrUtil.blankToDefault(operation, "") + " "
                + StrUtil.blankToDefault(intent, "")).toLowerCase(Locale.ROOT);
        List<SelectedResource> selected = new ArrayList<>();
        for (RoutedResource routed : routes) {
            if (!matches(routed.route(), operation, routeText)) {
                continue;
            }
            selected.add(routed.resource());
            if (selected.size() >= MAX_SELECTED_RESOURCES) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    public void verifyVersion(String skillCode, AidSkillVersion version) {
        if (isDatabasePackage(version)) {
            verifyDatabasePackage(skillCode, version);
            return;
        }
        classpathPackage(skillCode, version);
    }

    public String readPublishedSkillMarkdown(String skillCode, AidSkillVersion version) {
        if (isDatabasePackage(version)) return databasePackage(skillCode, version).skillMarkdown();
        return classpathPackage(skillCode, version).skillMarkdown();
    }

    /**
     * 管理端从不可变正式版本准备升级草稿时读取主文件。
     *
     * <p>早期 DATABASE/V3 版本已经把提示词、Schema、模型配置、资源和包摘要写入数据库，
     * 但尚未保存 {@code __skill_md}。该入口仍完整验包和验资源，只对可明确识别的旧清单返回
     * {@code null}，供草稿层补入正式主文件；它不进入运行时缓存或磁盘镜像。运行时读取、发布、
     * 激活和模板复制继续使用严格入口。</p>
     */
    public String readVerifiedDraftUpgradeSkillMarkdown(String skillCode, AidSkillVersion version) {
        if (!isDatabasePackage(version)) {
            return classpathPackage(skillCode, version).skillMarkdown();
        }
        return loadDatabasePackageForDraftUpgrade(skillCode, version).skillMarkdown();
    }

    /**
     * Runtime 热路径校验。版本发布后不可变，命中时只比较版本行自带的摘要/配置指纹，
     * 不重复读取最多 512 KiB 的数据库资源正文；首次命中仍会完整验包。
     */
    public void verifyVersionCached(String skillCode, AidSkillVersion version) {
        if (isDatabasePackage(version)) {
            databasePackage(skillCode, version);
            return;
        }
        verifyVersion(skillCode, version);
    }

    public void verifyVersionsCached(Map<String, AidSkillVersion> versionsBySkillCode) {
        if (versionsBySkillCode == null || versionsBySkillCode.isEmpty()) {
            return;
        }
        versionsBySkillCode.forEach(this::verifyVersionCached);
    }

    /** 激活前批量校验固定版本，数据库包仅执行两次批量资源查询。 */
    public void verifyVersions(Map<String, AidSkillVersion> versionsBySkillCode) {
        if (versionsBySkillCode == null || versionsBySkillCode.isEmpty()) {
            return;
        }
        Map<String, AidSkillVersion> databaseVersions = new LinkedHashMap<>();
        for (Map.Entry<String, AidSkillVersion> entry : versionsBySkillCode.entrySet()) {
            if (!isDatabasePackage(entry.getValue())) {
                verifyVersion(entry.getKey(), entry.getValue());
                continue;
            }
            databaseVersions.put(entry.getKey(), entry.getValue());
        }
        if (databaseVersions.isEmpty()) {
            return;
        }
        long observedGeneration = cacheGeneration();
        Set<Long> versionSkillIds = databaseVersions.values().stream()
                .map(AidSkillVersion::getSkillId).collect(Collectors.toSet());
        boolean cacheAllowed = cacheAccessAllowed()
                && sharedCacheAllowedSkillIds(versionSkillIds).containsAll(versionSkillIds);
        Map<Long, DatabasePackage> observedCache = new LinkedHashMap<>();
        if (cacheAllowed) {
            synchronized (databaseCacheLock) {
                databaseVersions.values().stream().map(AidSkillVersion::getId).distinct()
                        .forEach(versionId -> observedCache.put(versionId, databaseCache.get(versionId)));
            }
        }
        List<ReentrantLock> loadLocks = databaseVersions.values().stream()
                .map(AidSkillVersion::getId).map(this::databaseLoadLockIndex).distinct().sorted()
                .map(index -> databaseLoadLocks[index]).toList();
        loadLocks.forEach(ReentrantLock::lock);
        try {
            Map<String, AidSkillVersion> pending = new LinkedHashMap<>();
            for (Map.Entry<String, AidSkillVersion> entry : databaseVersions.entrySet()) {
                if (cacheAllowed) {
                    synchronized (databaseCacheLock) {
                        DatabasePackage cached = databaseCache.get(entry.getValue().getId());
                        if (cached != null && cached != observedCache.get(entry.getValue().getId())) {
                            requireMatchingCache(entry.getKey(), entry.getValue(), cached);
                            continue;
                        }
                    }
                }
                pending.put(entry.getKey(), entry.getValue());
            }
            if (pending.isEmpty()) {
                return;
            }
            Set<Long> versionIds = pending.values().stream().map(AidSkillVersion::getId)
                    .collect(Collectors.toSet());
            Map<Long, List<AidSkillResource>> resourcesByVersion = resourceMapper.selectList(
                            Wrappers.<AidSkillResource>lambdaQuery()
                                    .select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                                            AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                                            AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                                            AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                                            AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                                            AidSkillResource::getStatus, AidSkillResource::getDelFlag)
                                    .in(AidSkillResource::getSkillVersionId, versionIds)
                                    .eq(AidSkillResource::getStatus, NORMAL)
                                    .eq(AidSkillResource::getDelFlag, NORMAL)
                                    .orderByAsc(AidSkillResource::getSkillVersionId)
                                    .orderByAsc(AidSkillResource::getId)).stream()
                    .collect(Collectors.groupingBy(AidSkillResource::getSkillVersionId));
            Map<Long, List<AidSkillRelation>> relationsByVersion = relationMapper.selectList(
                            Wrappers.<AidSkillRelation>lambdaQuery()
                                    .in(AidSkillRelation::getParentVersionId, versionIds)
                                    .eq(AidSkillRelation::getDelFlag, NORMAL)
                                    .orderByAsc(AidSkillRelation::getParentVersionId)
                                    .orderByAsc(AidSkillRelation::getId)).stream()
                    .collect(Collectors.groupingBy(AidSkillRelation::getParentVersionId));
            Map<Long, DatabasePackage> loadedPackages = new LinkedHashMap<>();
            for (Map.Entry<String, AidSkillVersion> entry : pending.entrySet()) {
                AidSkillVersion version = entry.getValue();
                DatabasePackage loaded = buildDatabasePackage(entry.getKey(), version,
                        resourcesByVersion.getOrDefault(version.getId(), List.of()),
                        relationsByVersion.getOrDefault(version.getId(), List.of()), observedGeneration);
                loadedPackages.put(version.getId(), loaded);
            }
            synchronized (databaseCacheLock) {
                if (observedGeneration == databaseCacheGeneration && cacheAllowed) {
                    loadedPackages.forEach(this::putDatabasePackage);
                }
            }
        } finally {
            for (int index = loadLocks.size() - 1; index >= 0; index--) {
                loadLocks.get(index).unlock();
            }
        }
    }

    private DatabasePackage databasePackage(String skillCode, AidSkillVersion version) {
        long observedGeneration = cacheGeneration();
        ReentrantLock loadLock = databaseLoadLocks[databaseLoadLockIndex(version.getId())];
        loadLock.lock();
        try {
            boolean cacheAllowed = cacheAccessAllowed() && sharedCacheAllowed(version.getSkillId());
            synchronized (databaseCacheLock) {
                DatabasePackage cached = cacheAllowed ? databaseCache.get(version.getId()) : null;
                if (cacheAllowed && cached != null) {
                    return requireMatchingCache(skillCode, version, cached);
                }
            }
            DatabasePackage loaded = loadDatabasePackage(skillCode, version, observedGeneration);
            synchronized (databaseCacheLock) {
                if (observedGeneration == databaseCacheGeneration && cacheAllowed) {
                    putDatabasePackage(version.getId(), loaded);
                }
            }
            return loaded;
        } finally {
            loadLock.unlock();
        }
    }

    private DatabasePackage verifyDatabasePackage(String skillCode, AidSkillVersion version) {
        long observedGeneration = cacheGeneration();
        boolean cacheAllowed = cacheAccessAllowed() && sharedCacheAllowed(version.getSkillId());
        DatabasePackage observed;
        synchronized (databaseCacheLock) {
            observed = cacheAllowed ? databaseCache.get(version.getId()) : null;
        }
        ReentrantLock loadLock = databaseLoadLocks[databaseLoadLockIndex(version.getId())];
        loadLock.lock();
        try {
            synchronized (databaseCacheLock) {
                DatabasePackage refreshed = cacheAllowed ? databaseCache.get(version.getId()) : null;
                if (cacheAllowed && refreshed != null && refreshed != observed) {
                    return requireMatchingCache(skillCode, version, refreshed);
                }
            }
            DatabasePackage loaded = loadDatabasePackage(skillCode, version, observedGeneration);
            synchronized (databaseCacheLock) {
                if (observedGeneration == databaseCacheGeneration && cacheAllowed) {
                    putDatabasePackage(version.getId(), loaded);
                }
            }
            return loaded;
        } finally {
            loadLock.unlock();
        }
    }

    private static ReentrantLock[] createDatabaseLoadLocks() {
        ReentrantLock[] locks = new ReentrantLock[DATABASE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private int databaseLoadLockIndex(Long versionId) {
        return Math.floorMod(Long.hashCode(versionId), DATABASE_LOCK_STRIPES);
    }

    private DatabasePackage requireMatchingCache(String skillCode, AidSkillVersion version,
                                                  DatabasePackage cached) {
        if (!Objects.equals(cached.skillId(), version.getSkillId())
                || !Objects.equals(cached.packageDigest(), version.getPackageDigest())
                || !Objects.equals(cached.versionFingerprint(), versionFingerprint(skillCode, version))) {
            log.error("Skill数据库包缓存摘要冲突, skillCode={}, versionId={}", skillCode, version.getId());
            throw new ServiceException("Skill版本包摘要不匹配");
        }
        return cached;
    }

    public String readPublishedResource(String skillCode, AidSkillVersion version, AidSkillResource resource) {
        if (version == null || version.getId() == null || resource == null
                || !Objects.equals(version.getId(), resource.getSkillVersionId())) {
            throw new ServiceException("Skill资源版本不匹配");
        }
        if (isDatabasePackage(version)) {
            return verifiedDatabaseContent(resource);
        }
        ClasspathPackage packageContent = classpathPackage(skillCode, version);
        List<RoutedResource> matches = packageContent.routes().stream()
                .filter(value -> Objects.equals(value.route().getKey(), resource.getResourceKey())).toList();
        if (matches.size() != 1) {
            throw new ServiceException("Skill资源清单不匹配");
        }
        RoutedResource matched = matches.get(0);
        String expectedObjectKey = "classpath:" + packageContent.logicalBasePath()
                + matched.route().getPath();
        if (!Objects.equals(expectedObjectKey, resource.getObjectKey())
                || !Objects.equals(matched.resource().digest(), resource.getContentDigest())) {
            throw new ServiceException("Skill资源身份不匹配");
        }
        verifyResourceDigest(resource, matched.resource().content());
        return matched.resource().content();
    }

    private DatabasePackage loadDatabasePackage(String skillCode, AidSkillVersion version,
                                                long observedGeneration) {
        List<AidSkillResource> resources = resourceMapper.selectList(Wrappers.<AidSkillResource>lambdaQuery()
                .select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                        AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                        AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                        AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                        AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                        AidSkillResource::getStatus, AidSkillResource::getDelFlag)
                .eq(AidSkillResource::getSkillVersionId, version.getId())
                .eq(AidSkillResource::getStatus, NORMAL).eq(AidSkillResource::getDelFlag, NORMAL)
                .orderByAsc(AidSkillResource::getId));
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .eq(AidSkillRelation::getParentVersionId, version.getId())
                .eq(AidSkillRelation::getDelFlag, NORMAL).orderByAsc(AidSkillRelation::getId));
        return buildDatabasePackage(skillCode, version, resources, relations, observedGeneration);
    }

    private DatabasePackage loadDatabasePackageForDraftUpgrade(String skillCode,
                                                                AidSkillVersion version) {
        List<AidSkillResource> resources = resourceMapper.selectList(Wrappers.<AidSkillResource>lambdaQuery()
                .select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                        AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                        AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                        AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                        AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                        AidSkillResource::getStatus, AidSkillResource::getDelFlag)
                .eq(AidSkillResource::getSkillVersionId, version.getId())
                .eq(AidSkillResource::getStatus, NORMAL).eq(AidSkillResource::getDelFlag, NORMAL)
                .orderByAsc(AidSkillResource::getId));
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .eq(AidSkillRelation::getParentVersionId, version.getId())
                .eq(AidSkillRelation::getDelFlag, NORMAL).orderByAsc(AidSkillRelation::getId));
        return buildDatabasePackage(skillCode, version, resources, relations,
                cacheGeneration(), true, false);
    }

    private DatabasePackage buildDatabasePackage(String skillCode, AidSkillVersion version,
                                                  List<AidSkillResource> resources,
                                                  List<AidSkillRelation> relations,
                                                  long observedGeneration) {
        return buildDatabasePackage(skillCode, version, resources, relations,
                observedGeneration, false, true);
    }

    private DatabasePackage buildDatabasePackage(String skillCode, AidSkillVersion version,
                                                  List<AidSkillResource> resources,
                                                  List<AidSkillRelation> relations,
                                                  long observedGeneration,
                                                  boolean allowLegacyMissingMaster,
                                                  boolean materializeMirror) {
        String calculated;
        try {
            calculated = SkillPackageDigestCalculator.calculate(skillCode, version, resources, relations);
        } catch (RuntimeException error) {
            log.error("Skill数据库包摘要算法无效, skillCode={}, versionId={}",
                    skillCode, version.getId(), error);
            throw new ServiceException("Skill版本包摘要算法无效");
        }
        if (!Objects.equals(calculated, version.getPackageDigest())) {
            log.error("Skill数据库包摘要错误, skillCode={}, versionId={}, expected={}, actual={}",
                    skillCode, version.getId(), version.getPackageDigest(), calculated);
            throw new ServiceException("Skill版本包摘要不匹配");
        }
        List<AidSkillResource> masters = resources.stream()
                .filter(resource -> "__skill_md".equals(resource.getResourceKey())).toList();
        if (masters.size() > 1) throw new ServiceException("Skill主文件重复");
        String skillMarkdown = masters.isEmpty()
                ? legacyDatabaseSkillMarkdownOrNull(skillCode, version, resources)
                : verifiedDatabaseContent(masters.get(0));
        if (skillMarkdown == null) {
            JSONObject manifest;
            try {
                manifest = JSON.parseObject(version.getManifestJson());
            } catch (RuntimeException invalid) {
                throw new ServiceException("Skill包清单无效");
            }
            if (manifest != null && SkillPackageDigestCalculator.ALGORITHM_V3.equals(
                    manifest.getString("digestAlgorithm"))
                    && (!allowLegacyMissingMaster
                    || !legacyV3DatabasePackageWithoutMaster(skillCode, version, manifest))) {
                throw new ServiceException("Skill版本缺少SKILL.md");
            }
        }
        Set<String> logicalPaths = new HashSet<>();
        List<RoutedResource> routes = new ArrayList<>();
        for (AidSkillResource resource : resources) {
            if ("__skill_md".equals(resource.getResourceKey())) continue;
            ResourceRoute route;
            try {
                route = StrUtil.isBlank(resource.getRouteJson())
                        ? new ResourceRoute() : JSON.parseObject(resource.getRouteJson(), ResourceRoute.class);
            } catch (RuntimeException error) {
                log.error("Skill数据库资源路由错误, versionId={}, resourceKey={}",
                        version.getId(), resource.getResourceKey(), error);
                throw new ServiceException("Skill资源路由无效");
            }
            if (route == null) {
                route = new ResourceRoute();
            }
            route.setKey(resource.getResourceKey());
            route.setDigest(resource.getContentDigest());
            String logicalPath = logicalPath(resource);
            if (!logicalPaths.add(logicalPath)) {
                throw new ServiceException("Skill资源路径重复");
            }
            route.setPath(logicalPath);
            String content = verifiedDatabaseContent(resource);
            routes.add(new RoutedResource(route,
                    new SelectedResource(resource.getResourceKey(), resource.getContentDigest(), content)));
        }
        List<RoutedResource> immutableRoutes = List.copyOf(routes);
        String fingerprint = versionFingerprint(skillCode, version);
        AidSkill identity = materializeMirror ? selectIdentityById(version.getSkillId()) : null;
        if (identity != null && Objects.equals(identity.getSkillCode(), skillCode)) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("aid-skill.json", version.getManifestJson());
            files.put("system_prompt.md", version.getSystemPrompt());
            for (RoutedResource routed : immutableRoutes) {
                files.put(routed.route().getPath(), routed.resource().content());
            }
            if (skillMarkdown != null) files.putIfAbsent("SKILL.md", skillMarkdown);
            diskMirror.materialize(identity, version, Map.copyOf(files),
                    () -> cacheGeneration() == observedGeneration);
        }
        return new DatabasePackage(version.getSkillId(), version.getPackageDigest(), fingerprint,
                skillMarkdown, immutableRoutes, databasePackageSizeBytes(version.getPackageDigest(), fingerprint,
                immutableRoutes) + utf8Size(skillMarkdown));
    }

    private boolean legacyV3DatabasePackageWithoutMaster(String skillCode, AidSkillVersion version,
                                                           JSONObject manifest) {
        return "2".equals(manifest.getString("formatVersion"))
                && DATABASE_SOURCE.equalsIgnoreCase(manifest.getString("source"))
                && SkillPackageDigestCalculator.ALGORITHM_V3.equals(
                manifest.getString("digestAlgorithm"))
                && Objects.equals(skillCode, manifest.getString("code"))
                && Objects.equals(version.getVersionCode(), manifest.getString("version"))
                && Objects.equals(version.getInvocationScope(), manifest.getString("invocationScope"))
                && manifest.getJSONArray("resources") != null
                && !manifest.containsKey("files");
    }

    private String versionFingerprint(String skillCode, AidSkillVersion version) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("skillCode", skillCode);
        basis.put("versionCode", version.getVersionCode());
        basis.put("visibility", version.getVisibility());
        basis.put("invocationScope", version.getInvocationScope());
        basis.put("executorType", version.getExecutorType());
        basis.put("modelCode", version.getModelCode());
        basis.put("modelConfigJson", value(version.getModelConfigJson()));
        basis.put("packageDigest", version.getPackageDigest());
        basis.put("manifestDigest", SecureUtil.sha256(value(version.getManifestJson())));
        basis.put("skillId", version.getSkillId());
        basis.put("publishStatus", version.getPublishStatus());
        basis.put("status", version.getStatus());
        basis.put("delFlag", version.getDelFlag());
        basis.put("systemPromptDigest", SecureUtil.sha256(value(version.getSystemPrompt())));
        basis.put("inputSchemaDigest", SecureUtil.sha256(value(version.getInputSchemaJson())));
        basis.put("outputSchemaDigest", SecureUtil.sha256(value(version.getOutputSchemaJson())));
        basis.put("definitionDigest", SecureUtil.sha256(value(version.getDefinitionJson())));
        basis.put("maxOutputTokens", version.getMaxOutputTokens());
        basis.put("contextWindowTokens", version.getContextWindowTokens());
        basis.put("safetyMarginTokens", version.getSafetyMarginTokens());
        return SecureUtil.sha256(JSON.toJSONString(basis));
    }

    private String value(String source) {
        return source == null ? "" : source;
    }

    private String verifiedDatabaseContent(AidSkillResource resource) {
        if (StrUtil.isBlank(resource.getObjectKey())) {
            log.error("Skill数据库资源地址错误, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源地址无效");
        }
        String content = resource.getContentText();
        if (content == null && inheritedScreenplayClasspathResource(resource.getObjectKey())) {
            content = inheritedArchiveContent(resource);
            if (content == null) {
                try {
                    content = read(resource.getObjectKey().substring("classpath:".length()));
                } catch (IOException error) {
                    log.error("Skill inherited resource is missing, versionId={}, resourceKey={}",
                            resource.getSkillVersionId(), resource.getResourceKey(), error);
                    throw new ServiceException("Skill资源内容缺失");
                }
            }
        }
        if (content != null && !resource.getObjectKey().startsWith("database:")
                && !resource.getObjectKey().startsWith("classpath:")) {
            throw new ServiceException("Skill资源地址无效");
        }
        if (content == null) {
            log.error("Skill数据库资源内容缺失, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源内容缺失");
        }
        verifyResourceDigest(resource, content);
        return content;
    }

    private boolean inheritedScreenplayClasspathResource(String objectKey) {
        return objectKey != null && objectKey.matches(
                "classpath:skills/(?:screenplay|screenplay_write|screenplay_review)/"
                        + "[0-9]+\\.[0-9]+\\.[0-9]+/references/[a-z0-9-]+\\.md");
    }

    private void verifyResourceDigest(AidSkillResource resource, String content) {
        String digest = SecureUtil.sha256(content);
        if (!Objects.equals(digest, resource.getContentDigest())
                || !Objects.equals((long) content.getBytes(StandardCharsets.UTF_8).length,
                resource.getSizeBytes())) {
            log.error("Skill资源完整性错误, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源摘要不匹配");
        }
    }

    public boolean isDatabasePackage(AidSkillVersion version) {
        if (version == null || version.getId() == null || StrUtil.isBlank(version.getManifestJson())) {
            return false;
        }
        try {
            return DATABASE_SOURCE.equalsIgnoreCase(
                    JSON.parseObject(version.getManifestJson()).getString("source"));
        } catch (RuntimeException error) {
            log.error("Skill包清单解析失败, versionId={}, manifestLength={}",
                    version.getId(), version.getManifestJson().length(), error);
            throw new ServiceException("Skill包清单无效");
        }
    }

    private boolean matches(ResourceRoute route, String operation, String routeText) {
        if (Boolean.TRUE.equals(route.getAlways())) {
            return true;
        }
        if (route.getOperations() != null && route.getOperations().stream()
                .anyMatch(value -> value.equalsIgnoreCase(StrUtil.blankToDefault(operation, "")))) {
            return true;
        }
        return route.getKeywords() != null && route.getKeywords().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(routeText::contains);
    }

    private ClasspathPackage classpathPackage(String skillCode, AidSkillVersion version) {
        if (version == null || StrUtil.isBlank(version.getVersionCode())) {
            throw new ServiceException("Skill版本无效");
        }
        validateIdentitySegment(skillCode, "Skill编码无效");
        validateIdentitySegment(version.getVersionCode(), "Skill版本无效");
        String packageDigest = version.getPackageDigest();
        if (StrUtil.isBlank(packageDigest) || !SHA256.matcher(packageDigest).matches()) {
            throw new ServiceException("Skill版本摘要无效");
        }
        ClasspathPackage packageContent = classpathPackageCache.computeIfAbsent(packageDigest,
                ignored -> loadVersionPackage(skillCode, version));
        if (!Objects.equals(packageContent.packageDigest(), packageDigest)
                || !Objects.equals(packageContent.manifest().getCode(), skillCode)
                || !Objects.equals(packageContent.manifest().getVersion(), version.getVersionCode())
                || !Objects.equals(packageContent.manifest().getSystemPrompt(), version.getSystemPrompt())
                || !Objects.equals(packageContent.logicalBasePath(), standardBase(skillCode, version.getVersionCode()))) {
            throw new ServiceException("Skill版本包身份不匹配");
        }
        return packageContent;
    }

    private ClasspathPackage loadVersionPackage(String skillCode, AidSkillVersion version) {
        String digest = version.getPackageDigest();
        String logicalBasePath = standardBase(skillCode, version.getVersionCode());
        Resource archiveManifest = new ClassPathResource(ARCHIVE_ROOT + digest + "/aid-skill.json");
        Resource manifest = archiveManifest.exists()
                ? archiveManifest : new ClassPathResource(logicalBasePath + "aid-skill.json");
        return loadCompletePackage(manifest, digest, logicalBasePath);
    }

    private ClasspathPackage loadCompletePackage(Resource manifestResource, String expectedDigest,
                                                   String logicalBasePath) {
        try {
            String manifestContent = read(manifestResource);
            PackageManifest manifest = JSON.parseObject(manifestContent, PackageManifest.class);
            if (manifest == null || StrUtil.isBlank(manifest.getCode()) || StrUtil.isBlank(manifest.getVersion())
                    || manifest.getResources() == null) {
                throw new ServiceException("Skill包清单无效");
            }
            validateIdentitySegment(manifest.getCode(), "Skill包编码无效");
            validateIdentitySegment(manifest.getVersion(), "Skill包版本无效");
            String effectiveLogicalBase = logicalBasePath == null
                    ? standardBase(manifest.getCode(), manifest.getVersion()) : logicalBasePath;
            if (!Objects.equals(effectiveLogicalBase, standardBase(manifest.getCode(), manifest.getVersion()))) {
                throw new ServiceException("Skill包身份不匹配");
            }

            Set<String> keys = new HashSet<>();
            Set<String> paths = new HashSet<>();
            paths.add("aid-skill.json");
            paths.add("SKILL.md");
            List<RoutedResource> routes = new ArrayList<>();
            for (ResourceRoute route : manifest.getResources()) {
                if (route == null || StrUtil.isBlank(route.getKey()) || StrUtil.isBlank(route.getDigest())
                        || !keys.add(route.getKey()) || !safeRelativePath(route.getPath())
                        || !paths.add(route.getPath())) {
                    throw new ServiceException("Skill资源清单无效");
                }
                String content = read(manifestResource.createRelative(route.getPath()));
                String digest = SecureUtil.sha256(content);
                if (!route.getDigest().equalsIgnoreCase(digest)) {
                    throw new ServiceException("Skill资源摘要不匹配");
                }
                routes.add(new RoutedResource(route, new SelectedResource(route.getKey(), digest, content)));
            }

            String skillMarkdown = read(manifestResource.createRelative("SKILL.md"));
            String skillDigest = SecureUtil.sha256(skillMarkdown);
            String manifestDigest = SecureUtil.sha256(manifestContent);
            String resourceLock = manifest.getResources().stream()
                    .sorted(Comparator.comparing(ResourceRoute::getKey))
                    .map(value -> value.getKey() + ":" + value.getDigest()).collect(Collectors.joining(","));
            String basis = "aid-package-v1|" + manifest.getCode() + "|" + manifest.getVersion()
                    + "|skill=" + skillDigest + "|manifest=" + manifestDigest + "|resources=" + resourceLock;
            String calculated = SecureUtil.sha256(basis);
            if (!Objects.equals(calculated, expectedDigest)) {
                throw new ServiceException("Skill版本包摘要不匹配");
            }
            manifest.setCalculatedPackageDigest(calculated);
            return new ClasspathPackage(calculated, effectiveLogicalBase, manifest,
                    skillMarkdown, List.copyOf(routes));
        } catch (IOException | RuntimeException error) {
            log.error("Skill classpath package loading failed, resource={}", manifestResource.getDescription(), error);
            if (error instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("Skill包资源不完整");
        }
    }

    private String inheritedArchiveContent(AidSkillResource resource) {
        if (StrUtil.isBlank(resource.getResourceKey()) || StrUtil.isBlank(resource.getContentDigest())
                || !SHA256.matcher(resource.getContentDigest()).matches() || resource.getSizeBytes() == null) {
            throw new ServiceException("Skill继承资源身份无效");
        }
        InheritedResourceIdentity identity = new InheritedResourceIdentity(resource.getObjectKey(),
                resource.getResourceKey(), resource.getContentDigest(), resource.getSizeBytes());
        List<ArchiveResource> matches = archiveCatalog().inheritedResources().getOrDefault(identity, List.of());
        if (matches.isEmpty()) return null;
        String content = matches.get(0).resource().content();
        // Different immutable packages may retain the same verified resource identity.
        // Reuse only byte-equivalent text; never choose between conflicting archives.
        for (int index = 1; index < matches.size(); index++) {
            if (!Objects.equals(content, matches.get(index).resource().content())) {
                throw new ServiceException("Skill继承资源归档内容冲突");
            }
        }
        return content;
    }

    private ArchiveCatalog archiveCatalog() {
        ArchiveCatalog cached = archiveCatalog;
        if (cached != null) {
            return cached;
        }
        synchronized (archiveCatalogLock) {
            if (archiveCatalog == null) {
                archiveCatalog = loadArchiveCatalog();
            }
            return archiveCatalog;
        }
    }

    private ArchiveCatalog loadArchiveCatalog() {
        try {
            Resource[] manifests = new PathMatchingResourcePatternResolver().getResources(
                    "classpath*:" + ARCHIVE_ROOT + "*/aid-skill.json");
            Map<InheritedResourceIdentity, List<ArchiveResource>> inherited = new HashMap<>();
            Set<String> locations = new HashSet<>();
            Set<String> packageDigests = new HashSet<>();
            for (Resource manifestResource : manifests) {
                String location = manifestResource.getURL().toExternalForm();
                if (!locations.add(location)) {
                    continue;
                }
                String packageDigest = archiveDigest(location);
                if (!packageDigests.add(packageDigest)) {
                    throw new ServiceException("Skill归档包摘要重复");
                }
                ClasspathPackage packageContent = loadCompletePackage(manifestResource, packageDigest, null);
                ClasspathPackage existing = classpathPackageCache.putIfAbsent(packageDigest, packageContent);
                if (existing != null && (!Objects.equals(existing.manifest().getCode(),
                        packageContent.manifest().getCode()) || !Objects.equals(existing.manifest().getVersion(),
                        packageContent.manifest().getVersion()))) {
                    throw new ServiceException("Skill归档缓存身份冲突");
                }
                for (RoutedResource routed : packageContent.routes()) {
                    String objectKey = "classpath:" + packageContent.logicalBasePath()
                            + routed.route().getPath();
                    long size = routed.resource().content().getBytes(StandardCharsets.UTF_8).length;
                    InheritedResourceIdentity identity = new InheritedResourceIdentity(objectKey,
                            routed.resource().resourceKey(), routed.resource().digest(), size);
                    inherited.computeIfAbsent(identity, ignored -> new ArrayList<>())
                            .add(new ArchiveResource(packageDigest, routed.resource()));
                }
            }
            Map<InheritedResourceIdentity, List<ArchiveResource>> immutable = new HashMap<>();
            inherited.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new ArchiveCatalog(Map.copyOf(immutable));
        } catch (IOException | RuntimeException error) {
            log.error("Skill archive catalog loading failed", error);
            if (error instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("Skill归档目录不可用");
        }
    }

    private String archiveDigest(String location) {
        Matcher matcher = ARCHIVE_MANIFEST_URL.matcher(location.replace('\\', '/'));
        if (!matcher.matches()) {
            throw new ServiceException("Skill归档路径无效");
        }
        return matcher.group(1);
    }

    private boolean safeRelativePath(String path) {
        if (StrUtil.isBlank(path) || path.startsWith("/") || path.contains("\\")) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return path.chars().noneMatch(value -> value < 0x20 || value == 0x7f);
    }

    /** 旧 DATABASE 版本没有主文件行；仅从它已固定的 classpath 资源根恢复主文件。 */
    private String legacyDatabaseSkillMarkdownOrNull(String skillCode, AidSkillVersion version,
                                                      List<AidSkillResource> resources) {
        Set<String> roots = resources.stream().map(AidSkillResource::getObjectKey)
                .filter(Objects::nonNull)
                .filter(key -> key.startsWith("classpath:skills/" + skillCode + "/"))
                .map(key -> {
                    int references = key.indexOf("references/");
                    return references < 0 ? null : key.substring("classpath:".length(), references);
                })
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (roots.size() == 1) {
            try {
                return read(roots.iterator().next() + "SKILL.md");
            } catch (IOException error) {
                log.warn("Skill旧数据库包主文件恢复失败, skillCode={}, versionId={}",
                        skillCode, version.getId());
            }
        }
        return null;
    }

    private boolean sharedCacheAllowed(Long skillId) {
        AidSkill identity = selectIdentityById(skillId);
        return identity != null && ("PLATFORM".equals(identity.getOwnerType())
                || ("USER".equals(identity.getOwnerType()) && "PUBLIC".equals(identity.getVisibility())));
    }

    private Set<Long> sharedCacheAllowedSkillIds(Set<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return Set.of();
        return skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getOwnerType, AidSkill::getVisibility)
                        .in(AidSkill::getId, skillIds)).stream()
                .filter(identity -> "PLATFORM".equals(identity.getOwnerType())
                        || ("USER".equals(identity.getOwnerType())
                        && "PUBLIC".equals(identity.getVisibility())))
                .map(AidSkill::getId).collect(Collectors.toSet());
    }

    private AidSkill selectIdentityById(Long skillId) {
        if (skillId == null) return null;
        return skillMapper.selectOne(identityProjection()
                .eq(AidSkill::getId, skillId).last("limit 1"));
    }

    private AidSkill selectIdentityByCode(String skillCode) {
        if (StrUtil.isBlank(skillCode)) return null;
        return skillMapper.selectOne(identityProjection()
                .eq(AidSkill::getSkillCode, skillCode).last("limit 1"));
    }

    /** Exact stable-identity snapshot used by authorization, cache policy and disk mirroring. */
    private LambdaQueryWrapper<AidSkill> identityProjection() {
        return Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getOwnerType,
                        AidSkill::getOwnerUserId, AidSkill::getVisibility, AidSkill::getCacheToDisk,
                        AidSkill::getInvocationScope, AidSkill::getCurrentVersionId,
                        AidSkill::getExecutorType,
                        AidSkill::getStatus, AidSkill::getDelFlag);
    }

    private String logicalPath(AidSkillResource resource) {
        if ("__skill_md".equals(resource.getResourceKey())) return "SKILL.md";
        String objectKey = resource.getObjectKey();
        int index = objectKey == null ? -1 : objectKey.indexOf("references/");
        String path;
        if (index < 0) {
            String key = resource.getResourceKey();
            if (StrUtil.isBlank(key)) throw new ServiceException("Skill资源路径无效");
            path = "references/" + key + (key.contains(".") ? "" : ".md");
        } else {
            path = objectKey.substring(index);
        }
        if (!safeRelativePath(path)) throw new ServiceException("Skill资源路径无效");
        return path;
    }

    private String verifiedMirrorOrValue(AidSkill skill, AidSkillVersion version,
                                         String path, String databaseValue) {
        String mirrorValue = diskMirror.read(skill, version, path).orElse(null);
        return mirrorValue != null && Objects.equals(SecureUtil.sha256(mirrorValue),
                SecureUtil.sha256(value(databaseValue))) ? mirrorValue : databaseValue;
    }

    private AidSkill copySkill(AidSkill source) {
        AidSkill copy = new AidSkill();
        copy.setId(source.getId());
        copy.setSkillCode(source.getSkillCode());
        copy.setOwnerType(source.getOwnerType());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setVisibility(source.getVisibility());
        copy.setCacheToDisk(source.getCacheToDisk());
        copy.setInvocationScope(source.getInvocationScope());
        copy.setCurrentVersionId(source.getCurrentVersionId());
        copy.setExecutorType(source.getExecutorType());
        copy.setStatus(source.getStatus());
        copy.setDelFlag(source.getDelFlag());
        return copy;
    }

    private void validateIdentitySegment(String value, String message) {
        if (StrUtil.isBlank(value) || value.contains("/") || value.contains("\\")
                || ".".equals(value) || "..".equals(value) || value.contains("@")) {
            throw new ServiceException(message);
        }
    }

    private String standardBase(String skillCode, String versionCode) {
        return STANDARD_ROOT + skillCode + "/" + versionCode + "/";
    }

    private String read(String path) throws IOException {
        return read(new ClassPathResource(path));
    }

    private String read(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    public record SelectedResource(String resourceKey, String digest, String content) { }

    public record AuthorizedPackage(AidSkill skill, AidSkillVersion version) { }

    @Data
    private static class PackageManifest {
        private String code;
        private String version;
        private String systemPrompt;
        private String calculatedPackageDigest;
        private List<ResourceRoute> resources;
    }

    @Data
    private static class ResourceRoute {
        private String key;
        private String path;
        private String digest;
        private Boolean always;
        private List<String> operations;
        private List<String> keywords;
    }

    private record RoutedResource(ResourceRoute route, SelectedResource resource) { }

    private record ClasspathPackage(String packageDigest, String logicalBasePath,
                                    PackageManifest manifest, String skillMarkdown,
                                    List<RoutedResource> routes) { }

    private record InheritedResourceIdentity(String objectKey, String resourceKey,
                                               String contentDigest, long sizeBytes) { }

    private record ArchiveResource(String packageDigest, SelectedResource resource) { }

    private record ArchiveCatalog(Map<InheritedResourceIdentity, List<ArchiveResource>> inheritedResources) { }

    private record VersionMetadata(Long id, Long skillId, String versionCode, String publishStatus,
                                   String packageDigest, String status, String delFlag,
                                   Long updateTimeMillis) {
        private static VersionMetadata from(AidSkillVersion version) {
            return new VersionMetadata(version.getId(), version.getSkillId(), version.getVersionCode(),
                    version.getPublishStatus(), version.getPackageDigest(), version.getStatus(),
                    version.getDelFlag(), version.getUpdateTime() == null
                    ? null : version.getUpdateTime().getTime());
        }
    }

    private record CachedVersion(VersionMetadata metadata, AidSkillVersion version, long sizeBytes) { }

    private record DatabasePackage(Long skillId, String packageDigest, String versionFingerprint,
                                   String skillMarkdown, List<RoutedResource> routes,
                                   long sizeBytes) { }
}
