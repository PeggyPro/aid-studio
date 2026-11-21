package com.aid.assistant.service;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.vo.AssistantViews.SkillOption;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.aid.skill.service.SkillModelService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 提供全能助手固定版本中可执行的 Skill 选择。 */
@Service
@Slf4j
public class AssistantSkillSelectionService
{
    @Resource private AidRuntimeAgentSkillRefMapper references;
    @Resource private AidSkillMapper skills;
    @Resource private SkillRuntimeEntrypointReadinessService readiness;
    @Resource private AidRuntimeAgentToolRefMapper toolReferences;
    @Resource private RuntimeToolRegistry tools;
    @Resource private AssistantImageSkillInvocationResolver imageSkills;

    public List<SkillOption> available(AssistantLoopBinding binding, Long userId)
    {
        if (!"UNIVERSAL".equals(binding.getModuleCode())) return List.of();
        List<AidRuntimeAgentSkillRef> refs = references.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentSkillRef>()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, binding.getAgentVersionId())
                        .orderByAsc(AidRuntimeAgentSkillRef::getSkillCode));
        Map<Long, AidSkill> identities = refs.isEmpty() ? Map.of() : skills.selectBatchIds(
                refs.stream().map(AidRuntimeAgentSkillRef::getSkillId).toList())
                .stream().collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<String, SkillOption> result = new LinkedHashMap<>();
        for (AidRuntimeAgentSkillRef ref : refs)
        {
            AidSkill identity = identities.get(ref.getSkillId());
            if (identity == null || !ref.getSkillCode().equals(identity.getSkillCode())) continue;
            try
            {
                var ready = readiness.requireReadyMethod(
                        identity, ref.getSkillVersionId(), ref.getPackageDigest());
                if (!"TEXT".equals(SkillModelService.outputKind(ready.rootVersion()))) continue;
                result.put(ref.getSkillCode(), new SkillOption(ref.getSkillCode(), identity.getName(),
                        identity.getDescription(), String.valueOf(ref.getSkillVersionId()), ref.getPackageDigest(),
                        "TEXT", "SKILL"));
            }
            catch (RuntimeException unavailable) { /* 不向用户暴露不可执行的固定版本。 */ }
        }
        if (userId != null && userId > 0)
        {
            List<AidSkill> owned = skills.selectList(new LambdaQueryWrapper<AidSkill>()
                    .eq(AidSkill::getOwnerType, "USER").eq(AidSkill::getOwnerUserId, userId)
                    .in(AidSkill::getVisibility, List.of("PRIVATE", "PUBLIC"))
                    .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                    .eq(AidSkill::getStatus, "0").eq(AidSkill::getDelFlag, "0")
                    .isNotNull(AidSkill::getCurrentVersionId)
                    .orderByAsc(AidSkill::getSkillCode).last("LIMIT 101"));
            if (owned.size() > 100)
                throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                        "ASSISTANT_USER_SKILL_CATALOG_TOO_LARGE", "个人Skill数量超过助手目录上限",
                        Map.of("limit", 100));
            for (AidSkill identity : owned)
            {
                if (result.containsKey(identity.getSkillCode())) continue;
                try
                {
                    var ready = readiness.requireReadyMethod(identity, identity.getCurrentVersionId(), userId, false);
                    if (!"TEXT".equals(SkillModelService.outputKind(ready.rootVersion()))) continue;
                    result.put(identity.getSkillCode(), new SkillOption(identity.getSkillCode(), identity.getName(),
                            identity.getDescription(), String.valueOf(ready.rootVersion().getId()),
                            ready.rootVersion().getPackageDigest(), "TEXT", "SKILL"));
                }
                catch (RuntimeException unavailable) { /* Keep unauthorized/incomplete packages out. */ }
            }
        }
        if (hasPinnedImageTool(binding))
        {
            for (AssistantImageSkillInvocationResolver.ImageCatalogEntry entry :
                    imageSkills.availableCatalogEntries(userId))
            {
                result.put(entry.code(), new SkillOption(entry.code(), entry.name(), entry.description(),
                        String.valueOf(entry.versionId()), entry.packageDigest(),
                        AssistantImageSkillInvocationResolver.OUTPUT_KIND,
                        AssistantImageSkillInvocationResolver.EXECUTION_ROUTE));
            }
        }
        return List.copyOf(result.values());
    }

    private boolean hasPinnedImageTool(AssistantLoopBinding binding)
    {
        RuntimeToolRegistry.Descriptor descriptor;
        try { descriptor = tools.require(AssistantImageSkillInvocationResolver.TOOL_CODE); }
        catch (RuntimeException unavailable) { return false; }
        AidRuntimeAgentToolRef ref = toolReferences.selectOne(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, binding.getAgentVersionId())
                        .eq(AidRuntimeAgentToolRef::getToolCode, AssistantImageSkillInvocationResolver.TOOL_CODE)
                        .last("LIMIT 1"));
        return ref != null && descriptor.contractVersion().equals(ref.getContractVersion())
                && descriptor.schemaDigest().equals(ref.getSchemaDigest());
    }

    public SkillOption requireSelection(AssistantLoopBinding binding, String code, Long userId)
    {
        if (StrUtil.isBlank(code)) return null;
        if ("UNIVERSAL".equals(binding.getModuleCode()))
        {
            AidRuntimeAgentSkillRef ref = references.selectOne(new LambdaQueryWrapper<AidRuntimeAgentSkillRef>()
                    .eq(AidRuntimeAgentSkillRef::getAgentVersionId, binding.getAgentVersionId())
                    .eq(AidRuntimeAgentSkillRef::getSkillCode, code));
            AidSkill identity = skills.selectOne(new LambdaQueryWrapper<AidSkill>()
                    .eq(AidSkill::getSkillCode, code).eq(AidSkill::getDelFlag, "0"));
            if (identity != null)
            {
                try
                {
                    var ready = ref != null && java.util.Objects.equals(ref.getSkillId(), identity.getId())
                            ? readiness.requireReadyMethod(identity, ref.getSkillVersionId(), ref.getPackageDigest())
                            : "USER".equals(identity.getOwnerType())
                                && java.util.Objects.equals(userId, identity.getOwnerUserId())
                                ? readiness.requireReadyMethod(identity, identity.getCurrentVersionId(), userId, false)
                                : null;
                    if (ready != null && "TEXT".equals(SkillModelService.outputKind(ready.rootVersion())))
                        return new SkillOption(code, identity.getName(), identity.getDescription(),
                                String.valueOf(ready.rootVersion().getId()), ready.rootVersion().getPackageDigest(),
                                "TEXT", "SKILL");
                }
                catch (RuntimeException unavailable) { /* Selected package must pass its own execution checks. */ }
                if ("ENTRYPOINT".equals(identity.getInvocationScope()) && hasPinnedImageTool(binding))
                {
                    var image = imageSkills.findSelection(userId, identity);
                    if (image != null)
                        return new SkillOption(code, image.name(), image.description(),
                                String.valueOf(image.versionId()), image.packageDigest(),
                                AssistantImageSkillInvocationResolver.OUTPUT_KIND,
                                AssistantImageSkillInvocationResolver.EXECUTION_ROUTE);
                }
            }
        }
        log.warn("拒绝不可用的助手 Skill 选择, agentVersionId={}, moduleCode={}, skillCode={}",
                binding.getAgentVersionId(), binding.getModuleCode(), code);
        throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "ASSISTANT_SKILL_UNAVAILABLE", "Skill当前不可用", Map.of("skillCode", code));
    }

}
