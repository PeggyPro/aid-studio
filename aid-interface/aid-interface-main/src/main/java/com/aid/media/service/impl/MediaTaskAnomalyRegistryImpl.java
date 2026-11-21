package com.aid.media.service.impl;

import com.aid.aid.domain.media.*;
import com.aid.aid.mapper.*;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.MediaTaskAnomalyRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MediaTaskAnomalyRegistryImpl implements MediaTaskAnomalyRegistry, com.aid.media.reconciliation.MediaSubmissionAnomalyObserver {
    private static final List<String> ACTIVE =
            List.of("WAIT_POLL", "WAIT_CALLBACK", "PROCESSING");
    private final AidMediaTaskAnomalyMapper mapper;
    private final AidMediaTaskAnomalyAuditMapper auditMapper;
    private final AidMediaTaskAnomalyConfigMapper configMapper;
    private final AidMediaTaskMapper taskMapper;
    private volatile AidMediaTaskAnomalyConfig cachedConfig;
    private volatile long configExpiresAt;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshSubmissionAnomalies(int maximumRows) {
        AidMediaTaskAnomalyConfig current = config();
        if (!Boolean.TRUE.equals(current.getEnabled())) return;
        int limit = Math.max(1, Math.min(maximumRows, 200));
        for (Long taskId : mapper.selectUnobservedSubmissions(current.getAnomalyThresholdSeconds(), limit)) {
            AidMediaTask task = taskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, taskId).last("FOR UPDATE"));
            if (task == null || !"PENDING".equals(task.getStatus())) continue;
            if (mapper.insertSubmissionObservation(taskId, current.getAnomalyThresholdSeconds()) == 1) {
                AidMediaTaskAnomaly inserted = find(taskId);
                if (inserted != null) audit(inserted, "OBSERVE_SUBMISSION", "提交结果待核实", "PENDING");
            }
        }
        for (Long taskId : mapper.selectRecoveredSubmissions(limit)) {
            AidMediaTask task = taskMapper.selectById(taskId);
            if (task != null) resolveTerminal(taskId, task.getStatus(), "SUBMISSION_EVIDENCE_RECOVERED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQueryFailure(AidMediaTask task, String reason, ProviderTaskResult result) {
        if (task == null || task.getId() == null) return;
        task = taskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, task.getId()).last("FOR UPDATE"));
        if (task == null || !List.of("PENDING", "WAIT_POLL", "WAIT_CALLBACK", "PROCESSING").contains(task.getStatus())) return;

        AidMediaTaskAnomalyConfig config = config();
        if (!Boolean.TRUE.equals(config.getEnabled())) return;
        if ("PENDING".equals(task.getStatus())) {
            AidMediaTask current = taskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, task.getId()).last("FOR UPDATE"));
            if (current != null && "PENDING".equals(current.getStatus())
                    && mapper.insertSubmissionObservation(task.getId(), config.getAnomalyThresholdSeconds()) == 1) {
                AidMediaTaskAnomaly inserted = find(task.getId());
                if (inserted != null) audit(inserted, "OBSERVE_SUBMISSION", "提交结果待核实", "PENDING");
            }
            return;
        }
        if (!ACTIVE.contains(task.getStatus())) return;
        Date now = new Date();
        AidMediaTaskAnomaly anomaly = find(task.getId());
        if (anomaly == null) {
            insertObservation(task, reason, result, config, now);
            return;
        }
        if ("CLOSED".equals(anomaly.getStatus())) {
            reopen(anomaly, reason, result, config, now);
            return;
        }
        boolean pending = now.getTime() - anomaly.getFirstDetectedTime().getTime()
                >= config.getAnomalyThresholdSeconds() * 1000L;
        Date next = new Date(now.getTime()
                + (pending ? config.getAutoRecheckIntervalSeconds() : 60) * 1000L);
        mapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getId, anomaly.getId())
                .ne(AidMediaTaskAnomaly::getStatus, "CLOSED")
                .set(AidMediaTaskAnomaly::getStatus, pending ? "PENDING" : "OBSERVING")
                .set(AidMediaTaskAnomaly::getFailureCount,
                        Objects.requireNonNullElse(anomaly.getFailureCount(), 0) + 1)
                .set(AidMediaTaskAnomaly::getLastDetectedTime, now)
                .set(AidMediaTaskAnomaly::getNextRecheckTime, next)
                .set(AidMediaTaskAnomaly::getLastErrorMessage, limit(reason))
                .set(AidMediaTaskAnomaly::getLastProviderStatus,
                        result == null ? null : limit(result.getProviderStatus()))
                .set(AidMediaTaskAnomaly::getUpdateBy, "scheduler")
                .set(AidMediaTaskAnomaly::getUpdateTime, now));
        if (pending) {
            taskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, task.getId())
                    .in(AidMediaTask::getStatus, ACTIVE)
                    .set(AidMediaTask::getNextPollTime, next));
        }
    }

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onUpstreamReachable(com.aid.media.event.MediaTaskUpstreamReachableEvent event) {
        resolveTerminal(event.taskId(), event.taskStatus(), "UPSTREAM_REACHABLE");
    }

    @Override
    public void invalidateConfig() {
        cachedConfig = null;
        configExpiresAt = 0L;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveTerminal(Long taskId, String taskStatus, String type) {
        AidMediaTaskAnomaly anomaly = find(taskId);
        if (anomaly == null || "CLOSED".equals(anomaly.getStatus())) return;
        AidMediaTask task = taskMapper.selectById(taskId);
        boolean compensationPending = task != null && "FAILED".equals(taskStatus)
                && List.of("FROZEN", "REFUNDING", "SETTLING").contains(task.getBillingStatus());
        String target = compensationPending ? "COMPENSATION_PENDING" : "CLOSED";
        Date now = new Date();
        int changed = mapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getId, anomaly.getId())
                .ne(AidMediaTaskAnomaly::getStatus, "CLOSED")
                .set(AidMediaTaskAnomaly::getStatus, target)
                .set(AidMediaTaskAnomaly::getResolutionType, type)
                .set(AidMediaTaskAnomaly::getResolvedTime, compensationPending ? null : now)
                .set(AidMediaTaskAnomaly::getUpdateBy, "scheduler")
                .set(AidMediaTaskAnomaly::getUpdateTime, now));
        if (changed > 0) audit(anomaly, "RESOLVED", taskStatus, target);
    }

    @EventListener
    public void onCompleted(MediaTaskCompletedEvent event) {
        AidMediaTask task = taskMapper.selectById(event.getTaskId());
        if (task != null) {
            resolveTerminal(task.getId(), task.getStatus(), "TASK_EVENT_" + task.getStatus());
        }
    }

    private void insertObservation(AidMediaTask task, String reason, ProviderTaskResult result,
                                   AidMediaTaskAnomalyConfig config, Date now) {
        AidMediaTaskAnomaly anomaly = new AidMediaTaskAnomaly();
        anomaly.setTaskId(task.getId());
        anomaly.setStatus("OBSERVING");
        anomaly.setAnomalyType("QUERY_UNAVAILABLE");
        anomaly.setFailureCount(1);
        anomaly.setFirstDetectedTime(now);
        anomaly.setLastDetectedTime(now);
        anomaly.setNextRecheckTime(
                new Date(now.getTime() + config.getAnomalyThresholdSeconds() * 1000L));
        anomaly.setLastErrorMessage(limit(reason));
        anomaly.setLastProviderStatus(result == null ? null : limit(result.getProviderStatus()));
        anomaly.setCreateBy("scheduler");
        anomaly.setCreateTime(now);
        if (mapper.insertObservation(anomaly) == 1) {
            audit(anomaly, "OBSERVE", reason, "OBSERVING");
        }
    }

    private void reopen(AidMediaTaskAnomaly anomaly, String reason, ProviderTaskResult result,
                        AidMediaTaskAnomalyConfig config, Date now) {
        int changed = mapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getId, anomaly.getId())
                .eq(AidMediaTaskAnomaly::getStatus, "CLOSED")
                .set(AidMediaTaskAnomaly::getStatus, "OBSERVING")
                .set(AidMediaTaskAnomaly::getFailureCount, 1)
                .set(AidMediaTaskAnomaly::getFirstDetectedTime, now)
                .set(AidMediaTaskAnomaly::getLastDetectedTime, now)
                .set(AidMediaTaskAnomaly::getNextRecheckTime,
                        new Date(now.getTime() + config.getAnomalyThresholdSeconds() * 1000L))
                .set(AidMediaTaskAnomaly::getResolvedTime, null)
                .set(AidMediaTaskAnomaly::getResolutionType, null)
                .set(AidMediaTaskAnomaly::getLastErrorMessage, limit(reason))
                .set(AidMediaTaskAnomaly::getLastProviderStatus,
                        result == null ? null : limit(result.getProviderStatus()))
                .set(AidMediaTaskAnomaly::getUpdateTime, now));
        if (changed > 0) audit(anomaly, "REOPEN", reason, "OBSERVING");
    }

    private AidMediaTaskAnomaly find(Long taskId) {
        return mapper.selectOne(new LambdaQueryWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getTaskId, taskId).last("LIMIT 1"));
    }

    private AidMediaTaskAnomalyConfig config() {
        long now = System.currentTimeMillis();
        AidMediaTaskAnomalyConfig local = cachedConfig;
        if (local != null && now < configExpiresAt) return local;
        AidMediaTaskAnomalyConfig config = configMapper.selectById(1L);
        if (config == null) {
            config = new AidMediaTaskAnomalyConfig();
            config.setId(1L);
            config.setEnabled(true);
            config.setAnomalyThresholdSeconds(1800);
            config.setAutoRecheckIntervalSeconds(300);
            config.setAdminRefreshSeconds(60);
        }
        cachedConfig = config;
        configExpiresAt = now + 30_000L;
        return config;
    }

    private void audit(AidMediaTaskAnomaly anomaly, String action, String reason, String after) {
        AidMediaTaskAnomalyAudit audit = new AidMediaTaskAnomalyAudit();
        audit.setAnomalyId(anomaly.getId());
        audit.setTaskId(anomaly.getTaskId());
        audit.setAction(action);
        audit.setOperatorName("scheduler");
        audit.setReason(limit(reason));
        audit.setBeforeStatus(anomaly.getStatus());
        audit.setAfterStatus(after);
        audit.setCreateBy("scheduler");
        audit.setCreateTime(new Date());
        auditMapper.insert(audit);
    }

    private static String limit(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
