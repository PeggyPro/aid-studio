package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.support.ConfiguredModelReferences;
import com.aid.aid.service.support.ModelReferenceContributor;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.media.enums.MediaTaskStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 保护仍在调度和等待上游的媒体任务所需模型。 */
@Component
@RequiredArgsConstructor
public class ActiveTaskModelReferences implements ModelReferenceContributor {
    private final AidAiModelMapper models;
    private final AidAiModelAliasMapper aliases;
    private final AidMediaTaskMapper tasks;

    @Override
    public List<DependencyReference> references(Set<Long> ids, Set<String> capabilities) {
        return page(ids, capabilities, 0, 100).items();
    }

    @Override
    public ReferencePage page(Set<Long> ids, Set<String> capabilities, long offset, int limit) {
        if (ids.isEmpty()) return new ReferencePage(0, List.of());
        Map<String, Long> codes = new LinkedHashMap<>();
        models.selectList(ConfiguredModelReferences.current(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getModelCode).in(AidAiModel::getId, ids)))
                .forEach(model -> codes.put(model.getModelCode(), model.getId()));
        aliases.selectList(ConfiguredModelReferences.current(Wrappers.<AidAiModelAlias>lambdaQuery()
                .select(AidAiModelAlias::getModelId, AidAiModelAlias::getLegacyModelCode).in(AidAiModelAlias::getModelId, ids)))
                .forEach(alias -> codes.put(alias.getLegacyModelCode(), alias.getModelId()));
        if (codes.isEmpty()) return new ReferencePage(0, List.of());
        List<DependencyReference> refs = new ArrayList<>();
        long total = tasks.selectCount(ConfiguredModelReferences.current(query(codes.keySet())));
        if (limit == 0 || offset >= total) return new ReferencePage(total, refs);
        // 不扫描终态历史或加载请求大字段；活动任务期间保守保护模型的协议配置。
        for (var task : tasks.selectList(query(codes.keySet())
                .select(AidMediaTask::getId, AidMediaTask::getModelName, AidMediaTask::getStatus)
                .orderByAsc(AidMediaTask::getId).last("LIMIT " + offset + ", " + limit
                        + (TransactionSynchronizationManager.isActualTransactionActive() ? " LOCK IN SHARE MODE" : "")))) {
            refs.add(ConfiguredModelReferences.reference("ACTIVE_TASK", task.getId(), String.valueOf(task.getId()),
                    "生成任务 " + task.getId(), codes.get(task.getModelName()), null,
                    "EXECUTION_MODEL", "任务仍处于 " + task.getStatus() + "，完成后可重新检查"));
        }
        return new ReferencePage(total, refs);
    }

    private LambdaQueryWrapper<AidMediaTask> query(Set<String> codes) {
        return Wrappers.<AidMediaTask>lambdaQuery().in(AidMediaTask::getModelName, codes)
                .in(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name(), MediaTaskStatus.QUEUED.name(),
                        MediaTaskStatus.PROCESSING.name(), MediaTaskStatus.WAIT_CALLBACK.name(), MediaTaskStatus.WAIT_POLL.name());
    }
}
