package com.aid.director;

import com.aid.director.domain.DirectorEntities.Scene;
import com.aid.director.domain.DirectorEntities.Take;
import com.aid.director.domain.DirectorEntities.Chunk;
import com.aid.director.domain.DirectorEntities.Camera;
import com.aid.director.mapper.DirectorCameraMapper;
import com.aid.director.mapper.DirectorSceneMapper;
import com.aid.director.mapper.DirectorTakeMapper;
import com.aid.director.mapper.DirectorChunkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 与既有全局定时任务隔离的有界录制维护。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectorMaintenance {
    private final DirectorProperties properties;
    private final DirectorTakeMapper takes;
    private final DirectorChunkMapper chunks;
    private final DirectorSceneMapper scenes;
    private final DirectorCameraMapper cameras;
    private final DirectorObjectStore objects;
    private final RedissonClient redisson;
    private final TransactionTemplate transactions;
    private ScheduledExecutorService executor;
    private int prefix;

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> { Thread t = new Thread(task, "director-maintenance"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::run, 60, 60, TimeUnit.SECONDS);
    }

    private void run() {
        var lock = redisson.getLock("aid:director:maintenance");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!acquired) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            markDeletedProjects();
            for (String state : List.of("PREPARING", "RECORDING", "STOPPING")) {
                var rows = takes.selectList(Wrappers.<Take>lambdaQuery().select(Take::getId)
                        .eq(Take::getState, state).lt(Take::getLastActivityAt, new Date(System.currentTimeMillis() - 45000))
                        .orderByAsc(Take::getLastActivityAt, Take::getId).last("LIMIT 100"));
                for (Take row : rows) takes.update(null, Wrappers.<Take>lambdaUpdate().eq(Take::getId, row.getId()).eq(Take::getState, state)
                        .lt(Take::getLastActivityAt, new Date(System.currentTimeMillis() - 45000))
                        .set(Take::getState, "INTERRUPTED").set(Take::getHostStopped, true).set(Take::getUpdateTime, new Date()));
            }
            for (String state : List.of("PREPARING", "RECORDING", "STOPPING", "INTERRUPTED", "READY", "DISCARDED")) {
                if (System.nanoTime() > deadline) break;
                var expired = takes.selectList(Wrappers.<Take>lambdaQuery().select(Take::getId).eq(Take::getState, state)
                        .le(Take::getExpireAt, new Date()).orderByAsc(Take::getExpireAt, Take::getId).last("LIMIT 100"));
                for (Take row : expired) {
                    if (System.nanoTime() > deadline) break;
                    transactions.executeWithoutResult(status -> {
                        Take take = takes.lock(row.getId());
                        if (take == null || "APPLIED".equals(take.getState()) || take.getExpireAt().after(new Date())) return;
                        chunks.delete(Wrappers.<Chunk>lambdaQuery().eq(Chunk::getTakeId, take.getId()));
                        takes.deleteById(take.getId());
                    });
                }
            }
            for (String key : objects.candidates(prefix++ & 255, 100)) {
                if (System.nanoTime() > deadline) break;
                objects.removeOrphan(key, this::referenced);
            }
            if (System.nanoTime() < deadline) reapDeletedScenes(deadline);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { log.warn("虚拟相机维护延后 type={}", e.getClass().getSimpleName()); }
        finally { if (acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private void markDeletedProjects() {
        var cursor = redisson.getAtomicLong("aid:director:project-lifecycle-cursor");
        long after = cursor.get();
        var rows = scenes.scanProjectLifecycle(after);
        if (rows.isEmpty() && after != 0) rows = scenes.scanProjectLifecycle(0L);
        var deleted = rows.stream().filter(row -> Integer.valueOf(1).equals(row.getDeleted())).map(Scene::getId).toList();
        if (!deleted.isEmpty()) scenes.update(null, Wrappers.<Scene>lambdaUpdate().in(Scene::getId, deleted)
                .eq(Scene::getDeleted, 0).set(Scene::getDeleted, 1).setSql("revision=revision+1")
                .set(Scene::getUpdateTime, new Date()).set(Scene::getUpdateBy, "system"));
        cursor.set(rows.isEmpty() ? 0 : rows.get(rows.size() - 1).getId());
    }

    private void reapDeletedScenes(long deadline) {
        var rows = scenes.selectList(Wrappers.<Scene>lambdaQuery().select(Scene::getId).eq(Scene::getDeleted, 1)
                .lt(Scene::getUpdateTime, new Date(System.currentTimeMillis() - properties.getDraftDays() * 86400000L))
                .orderByAsc(Scene::getUpdateTime, Scene::getId).last("LIMIT 10"));
        for (Scene row : rows) {
            if (System.nanoTime() > deadline) break;
            transactions.executeWithoutResult(status -> {
                Scene scene = scenes.lock(row.getId());
                if (scene == null || scene.getDeleted() != 1) return;
                var batch = takes.selectList(Wrappers.<Take>lambdaQuery().select(Take::getId)
                        .eq(Take::getSceneId, scene.getId()).orderByAsc(Take::getId).last("LIMIT 20"));
                for (Take take : batch) {
                    chunks.delete(Wrappers.<Chunk>lambdaQuery().eq(Chunk::getTakeId, take.getId()));
                    takes.deleteById(take.getId());
                }
                if (batch.isEmpty()) {
                    cameras.delete(Wrappers.<Camera>lambdaQuery().eq(Camera::getSceneId, scene.getId()));
                    scenes.deleteById(scene.getId());
                }
            });
        }
    }

    private boolean referenced(String hash) {
        return !scenes.selectList(Wrappers.<Scene>lambdaQuery().select(Scene::getId).eq(Scene::getObjectKey, hash).last("LIMIT 1")).isEmpty()
                || !takes.selectList(Wrappers.<Take>lambdaQuery().select(Take::getId).eq(Take::getManifestKey, hash).last("LIMIT 1")).isEmpty()
                || !chunks.selectList(Wrappers.<Chunk>lambdaQuery().select(Chunk::getId).eq(Chunk::getObjectKey, hash).last("LIMIT 1")).isEmpty();
    }

    @PreDestroy
    public void close() { if (executor != null) executor.shutdownNow(); }
}
