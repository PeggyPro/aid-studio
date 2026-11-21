package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelCapability;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.mapper.AidAiModelCapabilityMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.billing.service.IBillingDetailQueryService;
import com.aid.common.exception.ServiceException;
import com.aid.model.vo.CapabilityVO;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.vo.SkillAdminVO;
import com.aid.skill.vo.SkillCatalogVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Skill 模型选择、停用回退及管理/C端模型投影的单一事实源。 */
@Service
@RequiredArgsConstructor
public class SkillModelService {
    private static final String NORMAL = "0";
    private static final String TEXT = "text";

    private final IAidAiModelService modelService;
    private final IAidAiProviderService providerService;
    private final IBillingDetailQueryService billingDetailQueryService;
    private final AidAiModelCapabilityMapper capabilityMapper;

    public Selection resolve(AidSkillVersion version, String requestedModelCode) {
        SkillModelConfiguration config = SkillModelConfiguration.from(version);
        if (!config.isValid()) {
            throw new ServiceException("Skill版本模型配置无效");
        }
        Map<String, AidAiModel> active = activeModels(config.selectableModelCodes(), outputKind(version));
        String requested = StrUtil.trim(requestedModelCode);
        if (StrUtil.isNotBlank(requested)) {
            if (!config.selectableModelCodes().contains(requested)) {
                throw new ServiceException("所选模型不属于当前Skill版本");
            }
            if (!active.containsKey(requested)) {
                throw new ServiceException("所选模型已停用，请重新选择");
            }
            return new Selection(requested, requested.equals(config.defaultModelCode()), config, active);
        }
        if (!active.containsKey(config.defaultModelCode()))
            throw new ServiceException("Skill 默认模型已停用，请刷新目录并明确选择其他模型");
        return new Selection(config.defaultModelCode(), true, config, active);
    }

    /** Assistant execution validates only its chosen model; discovery is a separate explicit operation. */
    public Selection resolveForAssistant(AidSkillVersion version, String requestedModelCode) {
        SkillModelConfiguration config = SkillModelConfiguration.from(version);
        if (!config.isValid()) throw new ServiceException("Skill版本模型配置无效");
        String requested = StrUtil.trim(requestedModelCode);
        String selected = StrUtil.isBlank(requested) ? config.defaultModelCode() : requested;
        if (!config.selectableModelCodes().contains(selected))
            throw new ServiceException("所选模型不属于当前Skill版本");
        Map<String, AidAiModel> active = activeModels(List.of(selected), outputKind(version));
        if (!active.containsKey(selected))
            throw new ServiceException("所选模型不可用，请重新选择");
        return new Selection(selected, selected.equals(config.defaultModelCode()), config, active);
    }

    public boolean hasActiveCandidate(AidSkillVersion version) {
        SkillModelConfiguration config = SkillModelConfiguration.from(version);
        return config.isValid() && !activeModels(config.selectableModelCodes(), outputKind(version)).isEmpty();
    }

    /** 一次模型 IN 查询和一次供应商 IN 查询，返回配置顺序下的 C 端模型视图。 */
    public CatalogProjection catalogProjection(AidSkillVersion version) {
        return catalogProjections(Map.of(0L, version)).getOrDefault(0L,
                new CatalogProjection(null, List.of()));
    }

    public Map<Long, CatalogProjection> catalogProjections(Map<Long, AidSkillVersion> versionsBySkillId) {
        if (versionsBySkillId == null || versionsBySkillId.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = versionsBySkillId.values().stream()
                .flatMap(version -> SkillModelConfiguration.from(version).selectableModelCodes().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, AidAiModel> active = activeModels(codes);
        Map<Long, AidAiProvider> providers = providers(active.values().stream()
                .map(AidAiModel::getProviderId).filter(Objects::nonNull).collect(Collectors.toSet()), true);
        BigDecimal globalFactor = billingDetailQueryService.readGlobalPriceFactor();
        Map<Long, CatalogProjection> result = new LinkedHashMap<>();
        versionsBySkillId.forEach((skillId, version) -> result.put(skillId,
                catalogProjection(version, active, providers, globalFactor)));
        return result;
    }

    private CatalogProjection catalogProjection(AidSkillVersion version,
                                                Map<String, AidAiModel> active,
                                                Map<Long, AidAiProvider> providers,
                                                BigDecimal globalFactor) {
        SkillModelConfiguration config = SkillModelConfiguration.from(version);
        if (!config.isValid()) {
            return new CatalogProjection(null, List.of());
        }
        AidAiModel defaultModel = active.get(config.defaultModelCode());
        String effectiveDefault = defaultModel != null
                && compatible(defaultModel, outputKind(version))
                && providers.containsKey(defaultModel.getProviderId())
                ? config.defaultModelCode() : null;
        List<SkillCatalogVO.ModelItem> items = new ArrayList<>();
        for (String code : config.selectableModelCodes()) {
            AidAiModel model = active.get(code);
            if (model != null && !compatible(model, outputKind(version))) model = null;
            AidAiProvider provider = model == null ? null : providers.get(model.getProviderId());
            if (model == null || provider == null) {
                continue;
            }
            SkillCatalogVO.ModelItem item = new SkillCatalogVO.ModelItem();
            item.setModelCode(code);
            item.setModelName(model.getModelName());
            item.setModelLogo(model.getLogoUrl());
            item.setProviderName(provider.getProviderName());
            item.setProviderLogo(provider.getLogoUrl());
            item.setDefaultModel(code.equals(effectiveDefault));
            item.setCapability(capability(model));
            item.setBilling(billingDetailQueryService.buildModelBillingDetail(
                    model, provider.getProviderName(), provider.getLogoUrl(), globalFactor));
            items.add(item);
        }
        return new CatalogProjection(effectiveDefault, List.copyOf(items));
    }

    /** Only enabled text models may seed built-in assistant/method drafts. */
    public String firstAvailableTextModelCode() {
        return firstAvailableModelCode("TEXT");
    }

    /** Selects from the live enabled catalog; built-in resources never pin a stale provider/model code. */
    public String firstAvailableModelCode(String outputKind) {
        String requiredType = StrUtil.blankToDefault(outputKind, "TEXT");
        return adminOptionsForOutputKind(requiredType).stream()
                .filter(option -> Boolean.TRUE.equals(option.getAvailable()))
                .map(SkillAdminVO.TextModelOption::getModelCode)
                .filter(StrUtil::isNotBlank).findFirst().orElse(null);
    }

    /** 管理端保留停用/软删除模型，确保旧版本引用可见但不可继续选中。 */
    public List<SkillAdminVO.TextModelOption> adminOptions() {
        List<AidAiModel> models = modelService.list(Wrappers.<AidAiModel>lambdaQuery()
                .orderByDesc(AidAiModel::getPriority).orderByAsc(AidAiModel::getId));
        if (models.isEmpty()) {
            return List.of();
        }
        Map<Long, AidAiProvider> providers = providers(models.stream().map(AidAiModel::getProviderId)
                .filter(Objects::nonNull).collect(Collectors.toSet()), false);
        Map<Long, List<AidAiModelCapability>> capabilities = capabilityMapper.selectList(
                Wrappers.<AidAiModelCapability>lambdaQuery()
                        .in(AidAiModelCapability::getModelId, models.stream().map(AidAiModel::getId).toList())
                        .orderByAsc(AidAiModelCapability::getSortOrder)
                        .orderByAsc(AidAiModelCapability::getId))
                .stream().collect(Collectors.groupingBy(AidAiModelCapability::getModelId));
        BigDecimal globalFactor = billingDetailQueryService.readGlobalPriceFactor();
        return models.stream().map(model -> {
            AidAiProvider provider = providers.get(model.getProviderId());
            boolean providerAvailable = provider != null && NORMAL.equals(provider.getStatus())
                    && NORMAL.equals(provider.getDelFlag());
            boolean available = NORMAL.equals(model.getStatus()) && NORMAL.equals(model.getDelFlag())
                    && providerAvailable;
            SkillAdminVO.TextModelOption item = new SkillAdminVO.TextModelOption();
            item.setModelCode(model.getModelCode());
            item.setModelName(model.getModelName());
            item.setModelType(model.getModelType());
            item.setCapabilityJson(model.getCapabilityJson());
            item.setCapability(capability(model));
            item.setCapabilities(capabilities.getOrDefault(model.getId(), List.of()).stream().map(row -> {
                SkillAdminVO.ModelCapabilityOption option = new SkillAdminVO.ModelCapabilityOption();
                option.setCode(row.getCapabilityCode());
                option.setLabel(StrUtil.blankToDefault(row.getGenerateMode(), row.getCapabilityCode()));
                boolean capabilityEnabled = true;
                try {
                    JSONObject definition = JSON.parseObject(StrUtil.blankToDefault(row.getDefinitionJson(), "{}"));
                    capabilityEnabled = definition == null || !Boolean.FALSE.equals(definition.getBoolean("enabled"));
                } catch (RuntimeException ignored) {
                    capabilityEnabled = false;
                }
                option.setEnabled(available && capabilityEnabled);
                return option;
            }).toList());
            item.setModelLogo(model.getLogoUrl());
            item.setProviderName(provider == null ? null : provider.getProviderName());
            item.setProviderLogo(provider == null ? null : provider.getLogoUrl());
            item.setStatus(model.getStatus());
            item.setDelFlag(model.getDelFlag());
            item.setAvailable(available);
            item.setUnavailableReason(available ? null : unavailableReason(model, provider));
            if (provider != null) {
                item.setBilling(billingDetailQueryService.buildModelBillingDetail(model,
                        provider.getProviderName(), provider.getLogoUrl(), globalFactor));
            }
            return item;
        }).toList();
    }

    /** 按输出类型投影管理目录；停用和软删除项仍保留给历史配置展示。 */
    public List<SkillAdminVO.TextModelOption> adminOptionsForOutputKind(String outputKind) {
        String requiredType = StrUtil.trim(outputKind);
        if (StrUtil.isBlank(requiredType)) return List.of();
        return adminOptions().stream()
                .filter(option -> requiredType.equalsIgnoreCase(StrUtil.trim(option.getModelType())))
                .toList();
    }

    public Map<String, AidAiModel> activeModels(Collection<String> modelCodes) {
        return activeModels(modelCodes, null);
    }

    public Map<String, AidAiModel> activeModels(Collection<String> modelCodes, String outputKind) {
        Set<String> codes = modelCodes == null ? Set.of() : modelCodes.stream()
                .map(StrUtil::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<AidAiModel> models = modelService.list(Wrappers.<AidAiModel>lambdaQuery()
                .in(AidAiModel::getModelCode, codes)
                .eq(AidAiModel::getStatus, NORMAL).eq(AidAiModel::getDelFlag, NORMAL));
        Set<Long> providerIds = models.stream().map(AidAiModel::getProviderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> activeProviderIds = providers(providerIds, true).keySet();
        return models.stream().filter(model -> activeProviderIds.contains(model.getProviderId()))
                .filter(model -> compatible(model, outputKind))
                .collect(Collectors.toMap(AidAiModel::getModelCode, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    public static String outputKind(AidSkillVersion version) {
        if (version == null || StrUtil.isBlank(version.getDefinitionJson())) return "TEXT";
        try {
            String value = JSON.parseObject(version.getDefinitionJson()).getString("outputKind");
            return StrUtil.isBlank(value) ? "TEXT" : value.toUpperCase(java.util.Locale.ROOT);
        } catch (RuntimeException error) {
            return "INVALID";
        }
    }

    public static boolean compatible(AidAiModel model, String outputKind) {
        if (outputKind == null) return true;
        String modelType = StrUtil.blankToDefault(model.getModelType(), "").toUpperCase(java.util.Locale.ROOT);
        return outputKind.equals(modelType);
    }

    private Map<Long, AidAiProvider> providers(Set<Long> ids, boolean activeOnly) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        var query = Wrappers.<AidAiProvider>lambdaQuery().select(AidAiProvider::getId,
                AidAiProvider::getProviderName, AidAiProvider::getLogoUrl,
                AidAiProvider::getStatus, AidAiProvider::getDelFlag).in(AidAiProvider::getId, ids);
        if (activeOnly) {
            query.eq(AidAiProvider::getStatus, NORMAL).eq(AidAiProvider::getDelFlag, NORMAL);
        }
        return providerService.list(query).stream().collect(Collectors.toMap(
                AidAiProvider::getId, Function.identity(), (left, right) -> left));
    }

    private CapabilityVO capability(AidAiModel model) {
        try {
            CapabilityVO capability = JSON.parseObject(
                    StrUtil.blankToDefault(model.getCapabilityJson(), "{}"), CapabilityVO.class);
            return capability == null ? new CapabilityVO() : capability;
        } catch (RuntimeException ignored) {
            return new CapabilityVO();
        }
    }

    private String unavailableReason(AidAiModel model, AidAiProvider provider) {
        if (!NORMAL.equals(model.getDelFlag())) return "模型已删除、需替换";
        if (!NORMAL.equals(model.getStatus())) return "模型已停用、需替换";
        if (provider == null || !NORMAL.equals(provider.getDelFlag())) return "供应商已删除、需替换";
        return "供应商已停用、需替换";
    }

    public record Selection(String modelCode, boolean effectiveDefault,
                            SkillModelConfiguration configuration,
                            Map<String, AidAiModel> activeModels) { }

    public record CatalogProjection(String defaultModelCode, List<SkillCatalogVO.ModelItem> models) { }
}
