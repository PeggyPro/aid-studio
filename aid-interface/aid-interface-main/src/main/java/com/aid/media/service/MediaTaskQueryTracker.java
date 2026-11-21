package com.aid.media.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.model.MediaTaskFailureReason;
import com.aid.media.provider.ProviderTaskResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** One observation per executed query; provider progress and query availability are independent. */
@Service
@RequiredArgsConstructor
public class MediaTaskQueryTracker {
    private static final List<String> ACTIVE = List.of("PENDING", "WAIT_POLL", "WAIT_CALLBACK", "PROCESSING");
    private final AidMediaTaskMapper tasks;
    private final TaskCompletionService completion;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(rollbackFor = Exception.class)
    public boolean record(AidMediaTask queried, ProviderTaskResult result, int failureLimit) {
        if (failureLimit < 1) throw new IllegalArgumentException("Query failure limit must be positive");
        AidMediaTask current = tasks.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, queried.getId()).last("FOR UPDATE"));
        if (current == null || !ACTIVE.contains(current.getStatus())
                || !Objects.equals(current.getProviderTaskId(), queried.getProviderTaskId())) return false;
        boolean recognized = result != null && Boolean.TRUE.equals(result.getQuerySuccessful())
                && ("PROCESSING".equals(result.getStatus())
                || Boolean.TRUE.equals(result.getTerminalConfirmed())
                && List.of("SUCCEEDED", "FAILED").contains(result.getStatus()));
        int count = recognized ? 0 : Objects.requireNonNullElse(current.getQueryFailureCount(), 0) + 1;
        tasks.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, current.getId())
                .eq(AidMediaTask::getProviderTaskId, current.getProviderTaskId())
                .set(AidMediaTask::getQueryFailureCount, count)
                .set(AidMediaTask::getUpdateTime, new Date()));
        if (recognized) {
            events.publishEvent(new com.aid.media.event.MediaTaskUpstreamReachableEvent(
                    current.getId(), current.getStatus()));
            return false;
        }
        if (count < failureLimit) return false;
        return completion.completeExecutionFailure(current.getId(), current.getExecutionPermitToken(),
                current.getProviderTaskId(), MediaTaskFailureReason.UPSTREAM_QUERY_UNAVAILABLE,
                "上游连续" + count + "次查询失败，本次任务已结束并退还积分");
    }
}
