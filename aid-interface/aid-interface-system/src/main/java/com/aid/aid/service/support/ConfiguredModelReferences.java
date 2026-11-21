package com.aid.aid.service.support;

import com.aid.aid.domain.AidAiBusinessModelBinding;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.mapper.AidAiBusinessModelBindingMapper;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.mapper.AidAiModelFuncConfigMapper;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 查询功能池、能力绑定与兼容标识的有效依赖。 */
@Component
@RequiredArgsConstructor
public class ConfiguredModelReferences implements ModelReferenceContributor {
    private final AidAiModelMapper models;
    private final AidAiModelFuncConfigMapper functions;
    private final AidAiBusinessModelBindingMapper bindings;
    private final AidAiModelAliasMapper aliases;

    @Override
    public List<DependencyReference> references(Set<Long> modelIds, Set<String> capabilityCodes) {
        List<DependencyReference> result = new ArrayList<>();
        Map<Long, AidAiModel> selected = models.selectList(current(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getModelCode).in(AidAiModel::getId, modelIds)))
                .stream().collect(Collectors.toMap(AidAiModel::getId, Function.identity()));
        List<AidAiModelFuncConfig> pools = functions.selectList(current(Wrappers.<AidAiModelFuncConfig>lambdaQuery()
                .select(AidAiModelFuncConfig::getId, AidAiModelFuncConfig::getFuncCode,
                        AidAiModelFuncConfig::getFuncName, AidAiModelFuncConfig::getModelIds)
                .eq(AidAiModelFuncConfig::getDelFlag, "0").orderByAsc(AidAiModelFuncConfig::getId)));
        Map<String, AidAiModelFuncConfig> byCode = pools.stream().collect(Collectors.toMap(
                AidAiModelFuncConfig::getFuncCode, Function.identity(), (first, ignored) -> first));
        List<AidAiBusinessModelBinding> linked = bindings.selectList(current(Wrappers.<AidAiBusinessModelBinding>lambdaQuery()
                .select(AidAiBusinessModelBinding::getId, AidAiBusinessModelBinding::getModelId,
                        AidAiBusinessModelBinding::getFuncCode, AidAiBusinessModelBinding::getCapabilityCode,
                        AidAiBusinessModelBinding::getDefaultCapability).in(AidAiBusinessModelBinding::getModelId, modelIds)
                .in(!capabilityCodes.isEmpty(), AidAiBusinessModelBinding::getCapabilityCode, capabilityCodes)
                .orderByAsc(AidAiBusinessModelBinding::getId)));
        for (var link : linked) {
            var pool = byCode.get(link.getFuncCode());
            if (pool == null) continue;
            result.add(reference("MODEL_POOL", pool.getId(), pool.getFuncCode(), pool.getFuncName(),
                    link.getModelId(), link.getCapabilityCode(), Boolean.TRUE.equals(link.getDefaultCapability())
                            ? "DEFAULT_CAPABILITY" : "ALLOWED_CAPABILITY", "模型池仍绑定此能力"));
        }
        if (capabilityCodes.isEmpty()) {
            for (var pool : pools) {
                if (pool.getModelIds() == null || pool.getModelIds().isBlank()) continue;
                List<Long> ids;
                try { ids = JSON.parseArray(pool.getModelIds(), Long.class); }
                catch (RuntimeException exception) { throw new ServiceException("模型池配置异常，请修复", 409); }
                if (ids == null) throw new ServiceException("模型池配置异常，请修复", 409);
                for (Long id : ids.stream().distinct().toList()) {
                    if (!selected.containsKey(id) || linked.stream().anyMatch(link -> id.equals(link.getModelId())
                            && pool.getFuncCode().equals(link.getFuncCode()))) continue;
                    result.add(reference("MODEL_POOL", pool.getId(), pool.getFuncCode(), pool.getFuncName(),
                            id, null, "ALLOWED_MODEL", "模型池仍保留此模型"));
                }
            }
        }
        // 兼容标识仍是可调用入口，不属于只读的任务历史。
        for (var alias : aliases.selectList(current(Wrappers.<AidAiModelAlias>lambdaQuery()
                .select(AidAiModelAlias::getId, AidAiModelAlias::getLegacyModelCode, AidAiModelAlias::getModelId,
                        AidAiModelAlias::getCapabilityCode, AidAiModelAlias::getBindingCode)
                .in(AidAiModelAlias::getModelId, modelIds)
                .in(!capabilityCodes.isEmpty(), AidAiModelAlias::getCapabilityCode, capabilityCodes)
                .orderByAsc(AidAiModelAlias::getId)))) {
            result.add(reference("MODEL_ALIAS", alias.getId(), alias.getLegacyModelCode(), alias.getLegacyModelCode(),
                    alias.getModelId(), alias.getCapabilityCode(), "COMPATIBILITY_ROUTE", "旧模型标识仍映射到此能力"));
        }
        return result;
    }

    public static DependencyReference reference(String type, Long id, String code, String name, Long modelId,
                                                 String capabilityCode, String purpose, String reason) {
        DependencyReference reference = new DependencyReference();
        reference.setType(type);
        reference.setId(String.valueOf(id));
        reference.setCode(code);
        reference.setName(name);
        reference.setModelId(modelId == null ? null : String.valueOf(modelId));
        reference.setCapabilityCode(capabilityCode);
        reference.setPurpose(purpose);
        reference.setReason(reason);
        return reference;
    }

    public static <T> LambdaQueryWrapper<T> current(LambdaQueryWrapper<T> query) {
        return TransactionSynchronizationManager.isActualTransactionActive() ? query.last("LOCK IN SHARE MODE") : query;
    }
}
