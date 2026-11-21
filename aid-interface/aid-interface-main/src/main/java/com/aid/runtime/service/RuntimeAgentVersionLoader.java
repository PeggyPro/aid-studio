package com.aid.runtime.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Loads immutable published Runtime Agent version bodies from the database. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeAgentVersionLoader
{
    private static final String DETERMINISTIC_VERSION_REJECTION = "RUNTIME_AGENT_VERSION_REJECTED";
    private static final String NORMAL = "0";
    private static final String FROZEN = "FROZEN";
    private static final String ASSISTANT_FAMILY = "ASSISTANT_LOOP";
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final long MAX_CACHE_BYTES = 16L * 1024L * 1024L;
    private static final int PRELOAD_BATCH_SIZE = 32;
    private static final int PRELOAD_SCAN_LIMIT = MAX_CACHE_ENTRIES * 4;
    private static final long ENTRY_OVERHEAD_BYTES = 256L;

    private final AidRuntimeAgentMapper agents;
    private final AidRuntimeAgentVersionMapper versions;
    private final Object cacheMonitor = new Object();
    private final LinkedHashMap<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75F, true);
    private final ConcurrentHashMap<LoadKey, CompletableFuture<VersionBody>> inFlight =
            new ConcurrentHashMap<>();
    private final Map<Long, AgentLoadState> activeLoadStates = new LinkedHashMap<>();
    private long cachedBytes;

    /** Reads only mutable authority metadata; version bodies are never served by this query. */
    public AidRuntimeAgentVersion selectMetadata(Long versionId)
    {
        if (versionId == null) return null;
        return versions.selectOne(Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                .select(AidRuntimeAgentVersion::getId, AidRuntimeAgentVersion::getAgentId,
                        AidRuntimeAgentVersion::getVersionCode,
                        AidRuntimeAgentVersion::getPackageDigest, AidRuntimeAgentVersion::getPublishStatus,
                        AidRuntimeAgentVersion::getStatus, AidRuntimeAgentVersion::getDelFlag,
                        AidRuntimeAgentVersion::getUpdateTime)
                .eq(AidRuntimeAgentVersion::getId, versionId)
                .last("LIMIT 1"));
    }

    /** Loads a published body after its status and identity metadata has been read from the database. */
    public AidRuntimeAgentVersion load(AidRuntimeAgentVersion metadata)
    {
        requirePublishedMetadata(metadata);
        if (writeTransactionActive()) return loadWithoutSharing(metadata);
        CacheKey cacheKey = new CacheKey(metadata.getId(), metadata.getPackageDigest());
        long updateFingerprint = updateFingerprint(metadata.getUpdateTime());
        LoadAccess access = beginLoad(cacheKey, metadata, updateFingerprint);
        if (access.cached() != null) return access.cached();
        LoadLease lease = access.lease();

        LoadKey loadKey = new LoadKey(cacheKey, metadata.getAgentId(), updateFingerprint, lease.generation());
        CompletableFuture<VersionBody> created = new CompletableFuture<>();
        CompletableFuture<VersionBody> existing = inFlight.putIfAbsent(loadKey, created);
        if (existing != null)
        {
            try
            {
                VersionBody shared = await(existing);
                AidRuntimeAgentVersion current = revalidateMetadata(metadata);
                requireGeneration(lease);
                return materialize(current, shared);
            }
            finally
            {
                endLoad(lease);
            }
        }

        try
        {
            AidRuntimeAgentVersion loaded = versions.selectById(metadata.getId());
            requireMatchingBody(metadata, loaded, updateFingerprint);
            VersionBody body = body(loaded);
            put(cacheKey, body, updateFingerprint, lease);
            created.complete(body);
            requireGeneration(lease);
            return materialize(metadata, body);
        }
        catch (RuntimeException error)
        {
            created.completeExceptionally(error);
            throw error;
        }
        finally
        {
            inFlight.remove(loadKey, created);
            endLoad(lease);
        }
    }

    /** True only for a local immutable-version shape or digest rejection, never for a read failure. */
    public static boolean isDeterministicVersionRejection(ServiceException error)
    {
        return error != null && DETERMINISTIC_VERSION_REJECTION.equals(error.getDetailMessage());
    }

    private AidRuntimeAgentVersion loadWithoutSharing(AidRuntimeAgentVersion metadata)
    {
        AidRuntimeAgentVersion loaded = versions.selectById(metadata.getId());
        requireMatchingBody(metadata, loaded, updateFingerprint(metadata.getUpdateTime()));
        return materialize(metadata, body(loaded));
    }

    private boolean writeTransactionActive()
    {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    /** Invalidates cached bodies only after the surrounding database transaction commits. */
    public void refreshAfterCommit(Long agentId)
    {
        if (agentId == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive())
        {
            if (!TransactionSynchronizationManager.isSynchronizationActive())
            {
                log.error("Runtime Agent版本缓存未注册事务后失效，agentId={}", agentId);
                return;
            }
            try
            {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        invalidateSafely(agentId);
                    }
                });
            }
            catch (RuntimeException error)
            {
                log.error("注册Runtime Agent版本缓存失效失败，agentId={}", agentId, error);
            }
            return;
        }
        invalidateSafely(agentId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preloadPublishedVersions()
    {
        int scanned = 0;
        int loaded = 0;
        int skipped = 0;
        int failed = 0;
        long lastAgentId = 0L;
        while (scanned < PRELOAD_SCAN_LIMIT && loaded < MAX_CACHE_ENTRIES)
        {
            int limit = Math.min(PRELOAD_BATCH_SIZE, PRELOAD_SCAN_LIMIT - scanned);
            List<AidRuntimeAgent> batch;
            try
            {
                batch = agents.selectList(Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .select(AidRuntimeAgent::getId, AidRuntimeAgent::getCurrentVersionId)
                        .eq(AidRuntimeAgent::getAgentFamily, ASSISTANT_FAMILY)
                        .eq(AidRuntimeAgent::getStatus, NORMAL)
                        .eq(AidRuntimeAgent::getDelFlag, NORMAL)
                        .isNotNull(AidRuntimeAgent::getCurrentVersionId)
                        .gt(AidRuntimeAgent::getId, lastAgentId)
                        .orderByAsc(AidRuntimeAgent::getId)
                        .last("LIMIT " + limit));
            }
            catch (RuntimeException error)
            {
                failed++;
                break;
            }
            if (batch.isEmpty()) break;
            scanned += batch.size();
            lastAgentId = batch.get(batch.size() - 1).getId();

            Map<Long, Long> ownersByVersion = new LinkedHashMap<>();
            for (AidRuntimeAgent agent : batch)
                ownersByVersion.putIfAbsent(agent.getCurrentVersionId(), agent.getId());
            Map<Long, LoadLease> leasesByAgent = new LinkedHashMap<>();
            for (Long agentId : ownersByVersion.values())
                leasesByAgent.put(agentId, beginLoad(agentId));
            Map<Long, AidRuntimeAgentVersion> bodies = new LinkedHashMap<>();
            try
            {
                versions.selectList(Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                                .in(AidRuntimeAgentVersion::getId, ownersByVersion.keySet())
                                .eq(AidRuntimeAgentVersion::getPublishStatus, FROZEN)
                                .eq(AidRuntimeAgentVersion::getStatus, NORMAL)
                                .eq(AidRuntimeAgentVersion::getDelFlag, NORMAL))
                        .forEach(version -> bodies.put(version.getId(), version));
            }
            catch (RuntimeException error)
            {
                failed += batch.size();
                leasesByAgent.values().forEach(this::endLoad);
                continue;
            }
            for (Map.Entry<Long, Long> expected : ownersByVersion.entrySet())
            {
                AidRuntimeAgentVersion version = bodies.get(expected.getKey());
                if (version == null || !Objects.equals(expected.getValue(), version.getAgentId()))
                {
                    skipped++;
                    continue;
                }
                try
                {
                    requireMatchingBody(version, version, updateFingerprint(version.getUpdateTime()));
                    if (put(new CacheKey(version.getId(), version.getPackageDigest()), body(version),
                            updateFingerprint(version.getUpdateTime()), leasesByAgent.get(version.getAgentId()))) loaded++;
                    else skipped++;
                }
                catch (RuntimeException invalid)
                {
                    failed++;
                }
            }
            leasesByAgent.values().forEach(this::endLoad);
        }
        log.info("Runtime Agent版本缓存预加载完成：scanned={}, loaded={}, skipped={}, failed={}",
                scanned, loaded, skipped, failed);
    }

    static String calculatePackageDigest(AidRuntimeAgentVersion version)
    {
        return SecureUtil.sha256(version.getInstruction() + "\n" + version.getModelConfigJson()
                + "\n" + version.getInputSchemaJson() + "\n" + version.getOutputSchemaJson()
                + "\n" + version.getDefinitionJson());
    }

    private LoadAccess beginLoad(CacheKey key, AidRuntimeAgentVersion metadata, long updateFingerprint)
    {
        synchronized (cacheMonitor)
        {
            CacheEntry entry = cache.get(key);
            if (entry != null && Objects.equals(metadata.getAgentId(), entry.agentId())
                    && updateFingerprint == entry.updateFingerprint())
                return new LoadAccess(materialize(metadata, entry.body()), null);
            return new LoadAccess(null, beginLoadLocked(metadata.getAgentId()));
        }
    }

    private LoadLease beginLoad(Long agentId)
    {
        synchronized (cacheMonitor)
        {
            return beginLoadLocked(agentId);
        }
    }

    private LoadLease beginLoadLocked(Long agentId)
    {
        AgentLoadState state = activeLoadStates.computeIfAbsent(agentId, ignored -> new AgentLoadState());
        state.references++;
        return new LoadLease(agentId, state, state.generation);
    }

    private void endLoad(LoadLease lease)
    {
        synchronized (cacheMonitor)
        {
            AgentLoadState state = activeLoadStates.get(lease.agentId());
            if (state != lease.state()) return;
            state.references--;
            if (state.references == 0) activeLoadStates.remove(lease.agentId());
        }
    }

    private boolean put(CacheKey key, VersionBody body, long updateFingerprint, LoadLease lease)
    {
        long bytes = payloadBytes(body);
        synchronized (cacheMonitor)
        {
            requireGenerationLocked(lease);
            if (bytes > MAX_CACHE_BYTES) return false;
            CacheEntry previous = cache.remove(key);
            if (previous != null) cachedBytes -= previous.bytes();
            cache.put(key, new CacheEntry(body.agentId(), updateFingerprint, bytes, body));
            cachedBytes += bytes;
            while (cache.size() > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES)
            {
                Map.Entry<CacheKey, CacheEntry> eldest = cache.entrySet().iterator().next();
                cache.remove(eldest.getKey());
                cachedBytes -= eldest.getValue().bytes();
            }
            return cache.containsKey(key);
        }
    }

    private void invalidateSafely(Long agentId)
    {
        try
        {
            synchronized (cacheMonitor)
            {
                AgentLoadState state = activeLoadStates.get(agentId);
                if (state != null) state.generation++;
                List<CacheKey> removals = new ArrayList<>();
                for (Map.Entry<CacheKey, CacheEntry> entry : cache.entrySet())
                    if (Objects.equals(agentId, entry.getValue().agentId())) removals.add(entry.getKey());
                for (CacheKey key : removals)
                {
                    CacheEntry removed = cache.remove(key);
                    if (removed != null) cachedBytes -= removed.bytes();
                }
            }
        }
        catch (RuntimeException error)
        {
            log.error("Runtime Agent版本缓存失效失败，agentId={}", agentId, error);
        }
    }

    private void requirePublishedMetadata(AidRuntimeAgentVersion metadata)
    {
        if (metadata == null || metadata.getId() == null || metadata.getAgentId() == null
                || StrUtil.isBlank(metadata.getVersionCode()) || StrUtil.isBlank(metadata.getPackageDigest())
                || metadata.getUpdateTime() == null || !FROZEN.equals(metadata.getPublishStatus())
                || !NORMAL.equals(metadata.getStatus()) || !NORMAL.equals(metadata.getDelFlag()))
            throw deterministicVersionRejection("Agent版本不可用");
    }

    private void requireMatchingBody(AidRuntimeAgentVersion metadata, AidRuntimeAgentVersion body,
                                     long updateFingerprint)
    {
        requirePublishedMetadata(body);
        if (!Objects.equals(metadata.getId(), body.getId())
                || !Objects.equals(metadata.getAgentId(), body.getAgentId())
                || !Objects.equals(metadata.getVersionCode(), body.getVersionCode())
                || !Objects.equals(metadata.getPackageDigest(), body.getPackageDigest())
                || !Objects.equals(metadata.getPublishStatus(), body.getPublishStatus())
                || !Objects.equals(metadata.getStatus(), body.getStatus())
                || !Objects.equals(metadata.getDelFlag(), body.getDelFlag())
                || updateFingerprint != updateFingerprint(body.getUpdateTime())
                || !Objects.equals(body.getPackageDigest(), calculatePackageDigest(body)))
            throw deterministicVersionRejection("Agent版本包损坏");
    }

    private AidRuntimeAgentVersion revalidateMetadata(AidRuntimeAgentVersion expected)
    {
        AidRuntimeAgentVersion current = selectMetadata(expected.getId());
        requirePublishedMetadata(current);
        if (!Objects.equals(expected.getId(), current.getId())
                || !Objects.equals(expected.getAgentId(), current.getAgentId())
                || !Objects.equals(expected.getVersionCode(), current.getVersionCode())
                || !Objects.equals(expected.getPackageDigest(), current.getPackageDigest())
                || !Objects.equals(expected.getPublishStatus(), current.getPublishStatus())
                || !Objects.equals(expected.getStatus(), current.getStatus())
                || !Objects.equals(expected.getDelFlag(), current.getDelFlag())
                || updateFingerprint(expected.getUpdateTime()) != updateFingerprint(current.getUpdateTime()))
            throw new ServiceException("Agent版本已更新");
        return current;
    }

    private static ServiceException deterministicVersionRejection(String message)
    {
        return new ServiceException(message).setDetailMessage(DETERMINISTIC_VERSION_REJECTION);
    }

    private VersionBody await(CompletableFuture<VersionBody> future)
    {
        try
        {
            return future.join();
        }
        catch (CompletionException error)
        {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new ServiceException("Agent版本不可用");
        }
    }

    private long payloadBytes(VersionBody body)
    {
        return ENTRY_OVERHEAD_BYTES + utf8Bytes(body.instruction()) + utf8Bytes(body.modelConfigJson())
                + utf8Bytes(body.inputSchemaJson()) + utf8Bytes(body.outputSchemaJson())
                + utf8Bytes(body.definitionJson());
    }

    private long utf8Bytes(String value)
    {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void requireGeneration(LoadLease lease)
    {
        synchronized (cacheMonitor)
        {
            requireGenerationLocked(lease);
        }
    }

    private void requireGenerationLocked(LoadLease lease)
    {
        AgentLoadState current = activeLoadStates.get(lease.agentId());
        if (current != lease.state() || current.generation != lease.generation())
            throw new ServiceException("Agent版本已更新");
    }

    private long updateFingerprint(Date updateTime)
    {
        return updateTime == null ? Long.MIN_VALUE : updateTime.getTime();
    }

    private VersionBody body(AidRuntimeAgentVersion source)
    {
        return new VersionBody(source.getId(), source.getAgentId(), source.getVersionCode(),
                source.getInstruction(), source.getModelConfigJson(), source.getInputSchemaJson(),
                source.getOutputSchemaJson(), source.getDefinitionJson(), source.getPackageDigest());
    }

    private AidRuntimeAgentVersion materialize(AidRuntimeAgentVersion metadata, VersionBody body)
    {
        AidRuntimeAgentVersion target = new AidRuntimeAgentVersion();
        target.setId(body.versionId());
        target.setAgentId(body.agentId());
        target.setVersionCode(body.versionCode());
        target.setPublishStatus(metadata.getPublishStatus());
        target.setInstruction(body.instruction());
        target.setModelConfigJson(body.modelConfigJson());
        target.setInputSchemaJson(body.inputSchemaJson());
        target.setOutputSchemaJson(body.outputSchemaJson());
        target.setDefinitionJson(body.definitionJson());
        target.setPackageDigest(body.packageDigest());
        target.setStatus(metadata.getStatus());
        target.setDelFlag(metadata.getDelFlag());
        target.setUpdateTime(copy(metadata.getUpdateTime()));
        return target;
    }

    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }

    private record CacheKey(Long versionId, String packageDigest)
    {
    }

    private record LoadKey(CacheKey cacheKey, Long agentId, long updateFingerprint, long generation)
    {
    }

    private record LoadAccess(AidRuntimeAgentVersion cached, LoadLease lease)
    {
    }

    private record LoadLease(Long agentId, AgentLoadState state, long generation)
    {
    }

    private record CacheEntry(Long agentId, long updateFingerprint, long bytes, VersionBody body)
    {
    }

    private record VersionBody(Long versionId, Long agentId, String versionCode, String instruction,
                               String modelConfigJson, String inputSchemaJson, String outputSchemaJson,
                               String definitionJson, String packageDigest)
    {
    }

    private static final class AgentLoadState
    {
        private long generation;
        private int references;
    }
}
