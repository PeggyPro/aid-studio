package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.domain.AidRuntimeModuleAgentBinding;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeModuleAgentBindingMapper;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.runtime.service.RuntimeAgentVersionLoader;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Database-backed module binding resolver. Missing or drifting bindings fail closed. */
@Service
public class AssistantModuleAgentResolverImpl implements AssistantModuleAgentResolver
{
    private static final Map<String, String> MODULE_AGENT_CODES = Map.of(
            "UNIVERSAL", "universal_canvas_agent",
            "SCREENWRITER", "story_script_agent",
            "SPATIAL_PREVIS", "spatial_previs_agent");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssistantModuleExtensionRegistry moduleRegistry;

    @Resource
    private AidRuntimeModuleAgentBindingMapper bindings;
    @Resource
    private AidRuntimeAgentMapper agents;
    @Resource
    private AidRuntimeAgentSkillRefMapper skillRefs;
    @Resource
    private AidRuntimeAgentToolRefMapper toolRefs;
    @Resource
    private RuntimeAgentVersionLoader versionLoader;
    @Resource
    private ObjectProvider<AssistantLoopExecutorRegistry> executors;
    @Resource
    private AssistantAgentModelReadinessService modelReadiness;

    @Override
    @Transactional(readOnly = true)
    public AssistantLoopBinding requireActive(String moduleCode)
    {
        return resolveActive(moduleCode);
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantLoopBinding requireActiveForToolPreparation(String moduleCode)
    {
        Function<String, RuntimeException> rejected =
                ignored -> new AssistantModuleAgentResolver.ActiveBindingRejectedException();
        ActiveReadiness readiness = resolveActiveReadiness(moduleCode, rejected,
                ignored -> new AssistantModuleAgentResolver.ActiveBindingRejectedException());
        if (!readiness.executorAvailable()) throw rejected.apply("主Agent不可用");
        return readiness.binding();
    }

    private AssistantLoopBinding resolveActive(String moduleCode)
    {
        ActiveReadiness readiness = resolveActiveReadiness(moduleCode, ServiceException::new,
                AssistantModelReadinessException::new);
        if (!readiness.executorAvailable())
        {
            throw new ServiceException("\u4e3bAgent\u4e0d\u53ef\u7528");
        }
        return readiness.binding();
    }

    private ActiveReadiness resolveActiveReadiness(
            String moduleCode,
            Function<String, RuntimeException> rejected,
            Function<AssistantAgentModelReadinessService.Verification, RuntimeException> modelRejected)
    {
        String expectedAgentCode = moduleRegistry == null ? MODULE_AGENT_CODES.get(moduleCode) : moduleRegistry.agentCode(moduleCode);
        if (expectedAgentCode == null)
        {
            throw rejected.apply("\u52a9\u624b\u6a21\u5757\u65e0\u6548");
        }
        AidRuntimeModuleAgentBinding binding = bindings.selectOne(
                Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                        .eq(AidRuntimeModuleAgentBinding::getModuleCode, moduleCode)
                        .eq(AidRuntimeModuleAgentBinding::getStatus, "ACTIVE")
                        .eq(AidRuntimeModuleAgentBinding::getDelFlag, "0")
                        .last("LIMIT 1"));
        if (binding == null)
        {
            throw rejected.apply("\u4e3bAgent\u672a\u7ed1\u5b9a");
        }
        AidRuntimeAgent agent = agents.selectById(binding.getRuntimeAgentId());
        AidRuntimeAgentVersion metadata = versionLoader.selectMetadata(binding.getActiveVersionId());
        if (agent == null || metadata == null || !"ASSISTANT_LOOP".equals(agent.getAgentFamily())
                || !"0".equals(agent.getDelFlag())
                || !"0".equals(metadata.getDelFlag()) || !"0".equals(agent.getStatus())
                || !"0".equals(metadata.getStatus()) || !"FROZEN".equals(metadata.getPublishStatus())
                || !Objects.equals(agent.getId(), metadata.getAgentId())
                || !Objects.equals(agent.getCurrentVersionId(), metadata.getId())
                || !expectedAgentCode.equals(agent.getAgentCode()))
        {
            throw rejected.apply("\u4e3bAgent\u7ed1\u5b9a\u5931\u6548");
        }
        AidRuntimeAgentVersion version;
        try
        {
            version = versionLoader.load(metadata);
        }
        catch (ServiceException unavailable)
        {
            if (RuntimeAgentVersionLoader.isDeterministicVersionRejection(unavailable))
                throw rejected.apply("\u4e3bAgent\u7ed1\u5b9a\u5931\u6548");
            throw unavailable;
        }
        JSONObject definition = object(version.getDefinitionJson(), rejected);
        String executorType = definition.getString("executorType");
        String protocolVersion = definition.getString("protocolVersion");
        if (!"ASSISTANT_LOOP".equals(executorType)
                || !supportsProtocol(moduleCode, protocolVersion)
                || StrUtil.isBlank(version.getInstruction())
                || StrUtil.isBlank(version.getModelConfigJson()) || StrUtil.isBlank(version.getPackageDigest()))
        {
            throw rejected.apply("\u4e3bAgent\u4e0d\u53ef\u7528");
        }
        requireModulePolicy(moduleCode, version, definition, rejected);
        AssistantAgentModelReadinessService.Verification modelVerification = modelReadiness.verifyFrozen(
                version.getModelConfigJson(), version.getDefinitionJson());
        if (!modelVerification.ready())
        {
            throw modelRejected.apply(modelVerification);
        }
        AidRuntimeModuleAgentBinding other = bindings.selectOne(
                Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery()
                        .ne(AidRuntimeModuleAgentBinding::getModuleCode, moduleCode)
                        .eq(AidRuntimeModuleAgentBinding::getRuntimeAgentId, agent.getId())
                        .eq(AidRuntimeModuleAgentBinding::getStatus, "ACTIVE")
                        .eq(AidRuntimeModuleAgentBinding::getDelFlag, "0").last("LIMIT 1"));
        if (other != null)
        {
            throw rejected.apply("\u4e3bAgent\u8eab\u4efd\u51b2\u7a81");
        }

        AssistantLoopBinding resolved = toBinding(moduleCode, binding.getBindingRevision(), agent, version,
                protocolVersion);
        if(moduleRegistry!=null)resolved=moduleRegistry.constrainActiveBinding(resolved);
        return new ActiveReadiness(resolved, executors.getObject().isAvailable(protocolVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveReadiness inspectActiveReadiness(String moduleCode)
    {
        try
        {
            return resolveActiveReadiness(moduleCode, ServiceException::new,
                    AssistantModelReadinessException::new);
        }
        catch (ServiceException | AssistantModelReadinessException unavailable)
        {
            return ActiveReadiness.unavailable();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantLoopBinding> findAvailableActive(String moduleCode)
    {
        return findAvailableActiveInCurrentRead(moduleCode);
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantLoopBinding requirePinned(String moduleCode, Long agentVersionId, String packageDigest)
    {
        return resolvePinned(moduleCode, agentVersionId, packageDigest, ServiceException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantLoopBinding requirePinnedForPlanningPreparation(String moduleCode, Long agentVersionId,
                                                                    String packageDigest)
    {
        return resolvePinned(moduleCode, agentVersionId, packageDigest,
                ignored -> new AssistantModuleAgentResolver.PinnedBindingRejectedException());
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantLoopBinding requirePinnedForPackageRead(String moduleCode, Long agentVersionId,
                                                            String packageDigest)
    {
        return resolvePinned(moduleCode, agentVersionId, packageDigest,
                ignored -> new AssistantModuleAgentResolver.PinnedBindingRejectedException());
    }

    private AssistantLoopBinding resolvePinned(String moduleCode, Long agentVersionId, String packageDigest,
                                               Function<String, RuntimeException> rejected)
    {
        String expectedAgentCode = moduleRegistry == null ? MODULE_AGENT_CODES.get(moduleCode) : moduleRegistry.agentCode(moduleCode);
        if (expectedAgentCode == null || agentVersionId == null || StrUtil.isBlank(packageDigest))
        {
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u65e0\u6548");
        }
        AidRuntimeAgentVersion metadata = versionLoader.selectMetadata(agentVersionId);
        AidRuntimeAgent agent = metadata == null ? null : agents.selectById(metadata.getAgentId());
        if (agent == null || metadata == null || !"ASSISTANT_LOOP".equals(agent.getAgentFamily())
                || !"0".equals(agent.getDelFlag())
                || !"0".equals(metadata.getDelFlag()) || !"0".equals(agent.getStatus())
                || !"0".equals(metadata.getStatus()) || !"FROZEN".equals(metadata.getPublishStatus())
                || !expectedAgentCode.equals(agent.getAgentCode())
                || !Objects.equals(packageDigest, metadata.getPackageDigest()))
        {
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
        }
        AidRuntimeAgentVersion version;
        try
        {
            version = versionLoader.load(metadata);
        }
        catch (ServiceException unavailable)
        {
            if (RuntimeAgentVersionLoader.isDeterministicVersionRejection(unavailable))
                throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
            throw unavailable;
        }
        JSONObject definition;
        try
        {
            definition = object(version.getDefinitionJson());
        }
        catch (ServiceException invalid)
        {
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
        }
        String executorType = definition.getString("executorType");
        String protocolVersion = definition.getString("protocolVersion");
        if (!"ASSISTANT_LOOP".equals(executorType)
                || !supportsProtocol(moduleCode, protocolVersion)
                || StrUtil.isBlank(version.getInstruction()) || StrUtil.isBlank(version.getModelConfigJson()))
        {
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
        }
        if (!executors.getObject().isAvailable(protocolVersion))
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
        try
        {
            requireModulePolicy(moduleCode, version, definition);
        }
        catch (ServiceException invalid)
        {
            throw rejected.apply("\u4e3bAgent\u56fa\u5b9a\u7248\u672c\u4e0d\u53ef\u7528");
        }
        AssistantAgentModelReadinessService.Verification modelVerification = modelReadiness.verifyFrozen(
                version.getModelConfigJson(), version.getDefinitionJson());
        if (!modelVerification.ready())
        {
            throw new AssistantModelReadinessException(modelVerification);
        }
        return toBinding(moduleCode, null, agent, version, protocolVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable(String moduleCode)
    {
        return findAvailableActiveInCurrentRead(moduleCode).isPresent();
    }

    private Optional<AssistantLoopBinding> findAvailableActiveInCurrentRead(String moduleCode)
    {
        try
        {
            return Optional.of(resolveActive(moduleCode));
        }
        catch (ServiceException | AssistantModelReadinessException unavailable)
        {
            return Optional.empty();
        }
    }

    private JSONObject object(String json)
    {
        return object(json, ServiceException::new);
    }

    private JSONObject object(String json, Function<String, RuntimeException> rejected)
    {
        try
        {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        }
        catch (RuntimeException error)
        {
            throw rejected.apply("Agent\u5b9a\u4e49\u65e0\u6548");
        }
    }

    private void requireModulePolicy(String moduleCode, AidRuntimeAgentVersion version, JSONObject definition)
    {
        requireModulePolicy(moduleCode, version, definition, ServiceException::new);
    }

    private void requireModulePolicy(String moduleCode, AidRuntimeAgentVersion version, JSONObject definition,
                                     Function<String, RuntimeException> rejected)
    {
        if (!SpatialPrevisGuidancePolicy.appliesToModule(moduleCode)) return;
        RuntimeAgentRequests.DraftPayload payload;
        try
        {
            payload = definition.toJavaObject(RuntimeAgentRequests.DraftPayload.class);
        }
        catch (RuntimeException invalid)
        {
            throw rejected.apply("空间预演方案助手固定范围无效");
        }
        if (!SpatialPrevisGuidancePolicy.validDraftBindings(payload))
            throw rejected.apply("空间预演方案助手固定范围无效");
        List<AidRuntimeAgentSkillRef> publishedSkills = skillRefs.selectList(
                Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, version.getId()));
        List<AidRuntimeAgentToolRef> publishedTools = toolRefs.selectList(
                Wrappers.<AidRuntimeAgentToolRef>lambdaQuery()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, version.getId()));
        try
        {
            SpatialPrevisGuidancePolicy.requirePublishedBindings(publishedSkills, publishedTools);
        }
        catch (ServiceException invalid)
        {
            throw rejected.apply("空间预演方案助手固定范围无效");
        }
    }

    private boolean supportsProtocol(String moduleCode, String version)
    {
        if (NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(version)) return true;
        // Existing community packages only used the JSON executor for these two modules.
        return DefaultAssistantLoopExecutor.PROTOCOL_VERSION.equals(version)
                && ("UNIVERSAL".equals(moduleCode) || "SCREENWRITER".equals(moduleCode));
    }

    private AssistantLoopBinding toBinding(String moduleCode, Long bindingRevision, AidRuntimeAgent agent,
                                           AidRuntimeAgentVersion version, String protocolVersion)
    {
        AssistantLoopBinding resolved = new AssistantLoopBinding();
        resolved.setModuleCode(moduleCode);
        resolved.setAgentCode(agent.getAgentCode());
        resolved.setBindingRevision(bindingRevision);
        resolved.setAgentId(agent.getId());
        resolved.setAgentVersionId(version.getId());
        resolved.setVersionCode(version.getVersionCode());
        resolved.setPackageDigest(version.getPackageDigest());
        resolved.setProtocolVersion(protocolVersion);
        resolved.setInstruction(version.getInstruction());
        resolved.setModelConfigJson(version.getModelConfigJson());
        resolved.setInputSchemaJson(version.getInputSchemaJson());
        resolved.setOutputSchemaJson(version.getOutputSchemaJson());
        resolved.setDefinitionJson(version.getDefinitionJson());
        return resolved;
    }
}
