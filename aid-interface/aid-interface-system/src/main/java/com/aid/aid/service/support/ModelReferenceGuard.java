package com.aid.aid.service.support;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 统一模型删除与绑定写入的事务互斥和依赖检查。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelReferenceGuard {
    private static final int MAX_BATCH = 100;
    private static final int ERROR_REFERENCE_LIMIT = 100;
    private final AidAiModelMapper models;
    private final ObjectProvider<ModelReferenceContributor> contributors;

    public void lockModels(Collection<Long> modelIds) {
        Set<Long> ids = validateIds(modelIds);
        if (ids.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model reference lock requires a transaction");
        // 所有绑定写入和删除按相同主键顺序加锁；锁后重新查询，避免使用先前事务快照。
        List<AidAiModel> rows = models.selectList(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getDelFlag)
                .in(AidAiModel::getId, ids).orderByAsc(AidAiModel::getId).last("FOR UPDATE"));
        if (rows.size() != ids.size() || rows.stream().anyMatch(row -> !"0".equals(row.getDelFlag())))
            throw new ServiceException("部分模型不存在", 400);
    }

    public void assertDeletable(Collection<Long> modelIds) {
        lockModels(modelIds);
        reject(page(modelIds, Set.of(), 0, ERROR_REFERENCE_LIMIT), "MODEL_IN_USE");
    }

    public void assertInvocationCurrent(Long modelId, Long expectedVersion) {
        validateIds(java.util.Collections.singletonList(modelId));
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model invocation check requires a transaction");
        // 生成提交使用共享锁，允许同一模型并行建立任务；只有删除和配置变更需要等待。
        AidAiModel current = models.selectOne(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getConfigVersion, AidAiModel::getStatus, AidAiModel::getDelFlag)
                .eq(AidAiModel::getId, modelId).last("LOCK IN SHARE MODE"));
        if (current == null || !"0".equals(current.getDelFlag())) throw new ServiceException("模型已不可用", 400);
        long version = current.getConfigVersion() == null ? 0L : current.getConfigVersion();
        if (!"0".equals(current.getStatus()) || version != (expectedVersion == null ? 0L : expectedVersion)) {
            ApiErrorDetails detail = new ApiErrorDetails();
            detail.setErrorCode("MODEL_CONFIGURATION_CHANGED");
            detail.setField("modelName");
            detail.setReason("模型配置已变更，请刷新模型目录及报价后重新提交。");
            throw new ServiceException("模型配置已变更", 409).setClientErrorData(detail);
        }
    }

    public void assertCapabilitiesRemovable(Long modelId, Set<String> capabilityCodes) {
        if (capabilityCodes == null || capabilityCodes.isEmpty()) return;
        lockModels(List.of(modelId));
        reject(page(List.of(modelId), capabilityCodes, 0, ERROR_REFERENCE_LIMIT), "CAPABILITY_IN_USE");
    }

    public ApiErrorDetails page(Collection<Long> modelIds, String capabilityCode, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new ServiceException("分页参数无效", 400);
        if (capabilityCode != null && !capabilityCode.matches("[a-z][a-z0-9_-]{0,95}"))
            throw new ServiceException("能力编码格式无效", 400);
        Set<Long> ids = validateIds(modelIds);
        if (!ids.isEmpty() && models.selectCount(Wrappers.<AidAiModel>lambdaQuery()
                .in(AidAiModel::getId, ids).eq(AidAiModel::getDelFlag, "0")) != ids.size())
            throw new ServiceException("部分模型不存在", 400);
        return page(modelIds, capabilityCode == null || capabilityCode.isBlank() ? Set.of() : Set.of(capabilityCode),
                ((long) page - 1) * pageSize, pageSize);
    }

    private ApiErrorDetails page(Collection<Long> modelIds, Set<String> codes, long offset, int limit) {
        Set<Long> ids = validateIds(modelIds);
        long total = 0;
        long remainingOffset = offset;
        List<DependencyReference> items = new ArrayList<>();
        for (ModelReferenceContributor source : ids.isEmpty() ? List.<ModelReferenceContributor>of() : contributors.orderedStream().toList()) {
            var batch = source.page(ids, codes, remainingOffset, limit - items.size());
            total += batch.total();
            items.addAll(batch.items());
            remainingOffset = Math.max(0, remainingOffset - batch.total());
        }
        ApiErrorDetails result = new ApiErrorDetails();
        result.setTotalReferences(total);
        result.setReferences(items);
        return result;
    }

    private static void reject(ApiErrorDetails detail, String errorCode) {
        if (detail.getTotalReferences() == 0) return;
        detail.setErrorCode(errorCode);
        detail.setReason("请先在对应配置中调整绑定，再重新删除；系统不会自动解绑。");
        detail.setRetryable(false);
        log.info("删除被有效绑定阻止: code={}, references={}", errorCode, detail.getTotalReferences());
        throw new ServiceException("仍有绑定，无法删除", 409).setClientErrorData(detail);
    }

    private static Set<Long> validateIds(Collection<Long> modelIds) {
        if (modelIds == null) throw new ServiceException("模型编号不能为空", 400);
        if (modelIds.size() > MAX_BATCH || modelIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new ServiceException("模型编号无效", 400);
        return new LinkedHashSet<>(modelIds.stream().sorted().toList());
    }
}
