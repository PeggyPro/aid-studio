package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantModelReadinessException;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.assistant.execution.AssistantPackageReadReceiptService;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Selective reads from the immutable formal package of the Agent version running this turn. */
@Component
@RequiredArgsConstructor
public class AgentPackageRuntimeToolProvider implements RuntimeToolProvider {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleRegistry;
    public static final String READ_RESOURCE = "aid.agent.read-resource";
    private static final String VERSION = "2.0.0";
    private static final String INPUT = """
            {"type":"object","required":["path"],"additionalProperties":false,"properties":{
              "path":{"type":"string","pattern":"^(SP\\.md|references/[a-z0-9][a-z0-9._/-]{0,180})$"},
              "masterDigest":{"type":"string","pattern":"^[0-9a-f]{64}$"}
            }}""";
    private static final String OUTPUT = """
            {"type":"object","required":["agentVersionId","packageDigest","path","resourceDigest","content"],"properties":{
              "agentVersionId":{"type":"integer"},"packageDigest":{"type":"string"},"path":{"type":"string"},
              "resourceDigest":{"type":"string"},"content":{"type":"string"},
              "resourcePaths":{"type":"array","items":{"type":"string"}}
            }}""";

    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;
    private final AssistantModuleAgentResolver moduleAgents;
    private final AssistantPackageReadReceiptService packageReadReceipts;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(new RuntimeToolRegistry.Descriptor(READ_RESOURCE, VERSION,
                SecureUtil.sha256(INPUT + "\n" + OUTPUT), "Read Agent package resource",
                "Read only SP.md or a path returned by SP.md from this run's fixed Agent package. Never use this tool for Skill package files; use aid.skill.read-resource for SKILL.md and Skill references. Read SP.md first; later reads are bound to that verified server receipt. masterDigest is compatibility metadata and never grants access.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                List.of("agent.package")));
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        return context != null && context.getUserId() != null && context.getUserId() > 0
                && (moduleRegistry == null ? List.of("UNIVERSAL", "SCREENWRITER", "SPATIAL_PREVIS").contains(context.getModuleCode()) : moduleRegistry.moduleCodes().contains(context.getModuleCode()))
                ? RuntimeToolAvailability.enabled()
                : RuntimeToolAvailability.unavailable("ASSISTANT_AGENT_PACKAGE_REQUIRED");
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.OBSERVATION;
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (invocation != null && READ_RESOURCE.equals(invocation.getToolCode())) {
            JSONObject arguments = object(invocation.getArgumentsJson());
            String path = arguments.getString("path");
            if (!"SP.md".equals(path) && !safeReference(path)) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_AGENT_RESOURCE_PATH_INVALID);
            }
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null) {
            return failed("AGENT_PACKAGE_RUN_IDENTITY_INVALID", "Agent包运行身份无效");
        }
        AidRuntimeRun run = runs.selectById(invocation.getRunId());
        if (run == null || !Objects.equals(run.getUserId(), invocation.getUserId())
                || run.getSessionId() == null || run.getVersionId() == null
                || !"ASSISTANT_LOOP".equals(run.getExecutorStrategy())
                || run.getMemberType() != null || run.getMemberId() != null
                || !"0".equals(run.getDelFlag())) {
            return failed("AGENT_PACKAGE_RUN_IDENTITY_INVALID", "Agent包运行身份无效");
        }
        AidAssistantSession session = sessions.selectById(run.getSessionId());
        if (session == null || !Objects.equals(session.getUserId(), run.getUserId())
                || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !"0".equals(session.getDelFlag())) {
            return failed("AGENT_PACKAGE_SESSION_IDENTITY_INVALID", "Agent包会话身份无效");
        }
        AssistantLoopBinding binding;
        try {
            binding = moduleAgents.requirePinnedForPackageRead(session.getModuleCode(), run.getVersionId(),
                    run.getVersionDigest());
        } catch (AssistantModelReadinessException unavailable) {
            return failed("AGENT_PACKAGE_MODEL_UNAVAILABLE", "固定版本的Agent模型不可用");
        } catch (AssistantModuleAgentResolver.PinnedBindingRejectedException unavailable) {
            return failed("AGENT_PACKAGE_BINDING_INVALID", "固定版本的Agent包不可用");
        }
        if (binding == null || !Objects.equals(binding.getAgentVersionId(), run.getVersionId())
                || !Objects.equals(binding.getPackageDigest(), run.getVersionDigest())
                || binding.getAgentId() == null) {
            return failed("AGENT_PACKAGE_BINDING_INVALID", "固定版本的Agent包不可用");
        }
        JSONObject arguments;
        JSONObject definition;
        String path;
        String master;
        List<RuntimeAgentRequests.PackageResource> resources;
        try {
            arguments = object(invocation.getArgumentsJson());
            definition = object(binding.getDefinitionJson());
            path = arguments.getString("path");
            master = definition.getString("systemPrompt");
            resources = definition.getList("resources", RuntimeAgentRequests.PackageResource.class);
            if (resources == null) resources = List.of();
        } catch (RuntimeException invalid) {
            return failed("AGENT_PACKAGE_DATA_INVALID", "固定版本的Agent包数据无效");
        }
        if (!"SP.md".equals(path) && !safeReference(path)) {
            return failed("TOOL_PREPARATION_AGENT_RESOURCE_PATH_INVALID", "Agent资源路径无效");
        }
        if (master == null || master.isBlank()) {
            return failed("AGENT_MASTER_RESOURCE_MISSING", "固定版本的Agent主文件不存在");
        }
        String masterDigest = SecureUtil.sha256(master);
        if (!"SP.md".equals(path)) {
            final boolean masterRead;
            try {
                masterRead = hasVerifiedMasterRead(invocation, run, binding, master, masterDigest);
            } catch (AssistantPackageReadReceiptService.InvalidReceiptException invalid) {
                return failed("PACKAGE_READ_RECEIPT_INVALID", "固定包读取回执无效，请重新开始本次任务");
            }
            if (!masterRead)
                return failed("AGENT_MASTER_READ_REQUIRED", "读取Agent子文件前必须先读取同一固定版本的主文件");
        }
        String content;
        if ("SP.md".equals(path)) content = master;
        else content = resources.stream().filter(Objects::nonNull)
                    .filter(resource -> Objects.equals(path, resource.getFilePath()))
                    .filter(resource -> resource.getContent() != null)
                    .map(RuntimeAgentRequests.PackageResource::getContent).findFirst().orElse(null);
        if (content == null) {
            return failed("AGENT_RESOURCE_MISSING", "固定版本的Agent参考资源不存在");
        }
        JSONObject output = new JSONObject();
        output.put("agentVersionId", binding.getAgentVersionId());
        output.put("packageDigest", binding.getPackageDigest());
        output.put("path", path); output.put("resourceDigest", SecureUtil.sha256(content));
        output.put("content", content);
        if ("SP.md".equals(path)) {
            output.put("resourcePaths", resources.stream()
                    .filter(Objects::nonNull)
                    .filter(resource -> safeReference(resource.getFilePath()))
                    .map(RuntimeAgentRequests.PackageResource::getFilePath).toList());
        }
        return RuntimeToolResult.succeeded(output.toJSONString());
    }

    private boolean hasVerifiedMasterRead(RuntimeToolInvocation invocation, AidRuntimeRun run,
                                          AssistantLoopBinding binding, String master,
                                          String masterDigest) {
        if (invocation.getRuntimeStepId() == null)
            throw new AssistantPackageReadReceiptService.InvalidReceiptException(
                    "PACKAGE_READ_RECEIPT_STEP_INVALID");
        for (AssistantPackageReadReceiptService.VerifiedPackageRead read :
                packageReadReceipts.contextReadsBefore(invocation.getUserId(), run,
                        invocation.getRuntimeStepId())) {
            JSONObject output = read.observation();
            if (READ_RESOURCE.equals(read.toolCode())
                    && Objects.equals(binding.getAgentVersionId(), output.getLong("agentVersionId"))
                    && Objects.equals(binding.getPackageDigest(), output.getString("packageDigest"))
                    && "SP.md".equals(output.getString("path"))
                    && Objects.equals(masterDigest, output.getString("resourceDigest"))
                    && Objects.equals(master, output.getString("content"))) return true;
        }
        return false;
    }

    private RuntimeToolResult failed(String errorCode, String safeSummary) {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED");
        result.setErrorCode(errorCode);
        result.setSafeSummary(safeSummary);
        result.setRetryable(false);
        return result;
    }

    private boolean safeReference(String path) {
        return path != null && path.matches("references/[a-z0-9][a-z0-9._/-]{0,180}")
                && !path.contains("..") && !path.contains("//");
    }

    private JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new ServiceException("Agent包数据无效");
        }
    }
}
