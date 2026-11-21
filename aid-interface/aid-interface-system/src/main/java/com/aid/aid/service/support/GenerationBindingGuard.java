package com.aid.aid.service.support;

import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.mapper.AidAgentMapper;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 对既有生成偏好的模型和智能体绑定执行同序事务锁。 */
@Service
@RequiredArgsConstructor
public class GenerationBindingGuard {
    private final ModelReferenceGuard modelGuard;
    private final AidAiModelMapper models;
    private final AidAiModelAliasMapper aliases;
    private final AidAgentMapper agents;

    public void lock(Collection<String> modelCodes, Collection<String> agentCodes) {
        Set<String> requested = codes(modelCodes);
        if (!requested.isEmpty()) {
            var selected = models.selectList(Wrappers.<AidAiModel>lambdaQuery()
                    .select(AidAiModel::getId, AidAiModel::getModelCode).in(AidAiModel::getModelCode, requested));
            var legacy = aliases.selectList(Wrappers.<AidAiModelAlias>lambdaQuery()
                    .select(AidAiModelAlias::getModelId, AidAiModelAlias::getLegacyModelCode).in(AidAiModelAlias::getLegacyModelCode, requested));
            Set<String> found = selected.stream().map(AidAiModel::getModelCode).collect(Collectors.toSet());
            legacy.forEach(row -> found.add(row.getLegacyModelCode()));
            if (!found.containsAll(requested)) throw new ServiceException("绑定模型不存在", 400);
            Set<Long> ids = selected.stream().map(AidAiModel::getId).collect(Collectors.toCollection(LinkedHashSet::new));
            legacy.forEach(row -> ids.add(row.getModelId()));
            modelGuard.lockModels(ids);
        }
        Set<String> selectedAgents = codes(agentCodes);
        if (!selectedAgents.isEmpty()) {
            List<AidAgent> rows = agents.selectList(Wrappers.<AidAgent>lambdaQuery()
                    .select(AidAgent::getId, AidAgent::getDelFlag).in(AidAgent::getAgentCode, selectedAgents)
                    .orderByAsc(AidAgent::getId).last("FOR UPDATE"));
            if (rows.size() != selectedAgents.size() || rows.stream().anyMatch(row -> !"0".equals(row.getDelFlag())))
                throw new ServiceException("绑定智能体不存在", 400);
        }
    }

    private Set<String> codes(Collection<String> values) {
        if (values == null || values.size() > 100) throw new ServiceException("绑定数量无效", 400);
        return values.stream().filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
    }
}
