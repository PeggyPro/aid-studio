package com.aid.aid.service.support;

import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidGenAgentPool;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.mapper.AidAgentMapper;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.aid.mapper.AidAiVoiceLibraryMapper;
import com.aid.aid.mapper.AidGenAgentPoolMapper;
import com.aid.aid.mapper.AidProjectGenConfigMapper;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 查询既有智能体、策略、项目偏好和音色的有效模型依赖。 */
@Component
@RequiredArgsConstructor
public class LegacyModelReferences implements ModelReferenceContributor {
    private final AidAiModelMapper models;
    private final AidAgentMapper agents;
    private final AidGenAgentPoolMapper matrix;
    private final AidProjectGenConfigMapper projects;
    private final AidAiVoiceLibraryMapper voices;

    @Override
    public List<DependencyReference> references(Set<Long> ids, Set<String> capabilities) {
        return page(ids, capabilities, 0, 100).items();
    }

    @Override
    public ReferencePage page(Set<Long> ids, Set<String> capabilities, long offset, int limit) {
        if (ids.isEmpty() || !capabilities.isEmpty()) return new ReferencePage(0, List.of());
        Map<String, Long> codes = models.selectList(ConfiguredModelReferences.current(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getModelCode).in(AidAiModel::getId, ids)))
                .stream().collect(Collectors.toMap(AidAiModel::getModelCode, AidAiModel::getId));
        if (codes.isEmpty()) return new ReferencePage(0, List.of());
        List<DependencyReference> refs = new ArrayList<>();
        long total = 0;
        long count = agents.selectCount(ConfiguredModelReferences.current(agentQuery(codes.keySet())));
        if (offset < count && refs.size() < limit) for (var row : agents.selectList(agentQuery(codes.keySet())
                .select(AidAgent::getId, AidAgent::getAgentCode, AidAgent::getName, AidAgent::getModelCode)
                .orderByAsc(AidAgent::getId).last(pageSql(offset, limit - refs.size())))) {
            refs.add(ConfiguredModelReferences.reference("LEGACY_AGENT", row.getId(), row.getAgentCode(), row.getName(),
                    codes.get(row.getModelCode()), null, "DEFAULT_MODEL", "智能体仍配置此默认模型"));
        }
        total += count; offset = Math.max(0, offset - count);
        count = matrix.selectCount(ConfiguredModelReferences.current(matrixQuery(codes.keySet())));
        if (offset < count && refs.size() < limit) for (var row : matrix.selectList(matrixQuery(codes.keySet())
                .select(AidGenAgentPool::getId, AidGenAgentPool::getAgentCode, AidGenAgentPool::getBizCategoryCode,
                        AidGenAgentPool::getModelCode, AidGenAgentPool::getStrategy)
                .orderByAsc(AidGenAgentPool::getId).last(pageSql(offset, limit - refs.size())))) {
            refs.add(ConfiguredModelReferences.reference("GENERATION_MATRIX", row.getId(), row.getBizCategoryCode(),
                    row.getAgentCode() + " / " + row.getStrategy(), codes.get(row.getModelCode()), null,
                    "DEFAULT_MODEL", "生成策略仍配置此默认模型"));
        }
        total += count; offset = Math.max(0, offset - count);
        count = projects.selectCount(ConfiguredModelReferences.current(projectQuery(codes.keySet())));
        if (offset < count && refs.size() < limit) for (var row : projects.selectList(projectQuery(codes.keySet())
                .select(AidProjectGenConfig::getId, AidProjectGenConfig::getProjectId, AidProjectGenConfig::getSceneCode,
                        AidProjectGenConfig::getModelCode)
                .orderByAsc(AidProjectGenConfig::getId).last(pageSql(offset, limit - refs.size())))) {
            refs.add(ConfiguredModelReferences.reference("PROJECT_CONFIG", row.getId(), row.getSceneCode(),
                    "项目 " + row.getProjectId(), codes.get(row.getModelCode()), null,
                    "DEFAULT_MODEL", "项目生成配置仍引用此模型"));
        }
        total += count; offset = Math.max(0, offset - count);
        count = voices.selectCount(ConfiguredModelReferences.current(voiceQuery(ids)));
        if (offset < count && refs.size() < limit) for (var row : voices.selectList(voiceQuery(ids)
                .select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getVoiceCode, AidAiVoiceLibrary::getVoiceName,
                        AidAiVoiceLibrary::getModelId).orderByAsc(AidAiVoiceLibrary::getId)
                .last(pageSql(offset, limit - refs.size())))) {
            refs.add(ConfiguredModelReferences.reference("VOICE", row.getId(), row.getVoiceCode(), row.getVoiceName(),
                    row.getModelId(), null, "OWNER_MODEL", "启用音色仍归属于此模型"));
        }
        return new ReferencePage(total + count, refs);
    }

    private static String pageSql(long offset, int count) {
        return "LIMIT " + offset + ", " + count
                + (TransactionSynchronizationManager.isActualTransactionActive() ? " LOCK IN SHARE MODE" : "");
    }

    private static LambdaQueryWrapper<AidAgent> agentQuery(Set<String> codes) {
        return Wrappers.<AidAgent>lambdaQuery().in(AidAgent::getModelCode, codes).eq(AidAgent::getDelFlag, "0");
    }
    private static LambdaQueryWrapper<AidGenAgentPool> matrixQuery(Set<String> codes) {
        return Wrappers.<AidGenAgentPool>lambdaQuery().in(AidGenAgentPool::getModelCode, codes)
                .eq(AidGenAgentPool::getDelFlag, "0");
    }
    private static LambdaQueryWrapper<AidProjectGenConfig> projectQuery(Set<String> codes) {
        return Wrappers.<AidProjectGenConfig>lambdaQuery().in(AidProjectGenConfig::getModelCode, codes)
                .eq(AidProjectGenConfig::getDelFlag, "0");
    }
    private static LambdaQueryWrapper<AidAiVoiceLibrary> voiceQuery(Set<Long> ids) {
        return Wrappers.<AidAiVoiceLibrary>lambdaQuery().in(AidAiVoiceLibrary::getModelId, ids)
                .eq(AidAiVoiceLibrary::getStatus, "0").eq(AidAiVoiceLibrary::getDelFlag, "0");
    }
}
