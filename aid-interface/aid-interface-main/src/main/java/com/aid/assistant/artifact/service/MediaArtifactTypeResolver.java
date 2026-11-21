package com.aid.assistant.artifact.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves the logical artifact media type from the immutable producer Tool scope. */
@Component
public class MediaArtifactTypeResolver {
    private static final Map<String, MediaBinding> BINDINGS = Map.of(
            "media.image.generate", new MediaBinding("IMAGE", "IMAGE"),
            "media.video.generate", new MediaBinding("VIDEO", "VIDEO"),
            "media.audio.generate", new MediaBinding("AUDIO", "AUDIO"),
            "media.music.generate", new MediaBinding("MUSIC", "AUDIO"));
    private static final Set<String> APPROVAL_STATES = Set.of("ACCEPTED", "CONSUMED");

    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;
    @Resource private AidRuntimeApprovalMapper approvalMapper;
    @Resource private RuntimeToolRegistry toolRegistry;

    public String requireForProduction(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task) {
        return resolve(run, step, task, run == null ? null : run.getContextDigest(),
                run == null ? null : run.getPlanRevision(), true);
    }

    /** Checks registered task storage without loading another approval or changing task state. */
    public String requireResultType(String toolCode, AidMediaTask task) {
        MediaBinding binding = BINDINGS.get(toolCode);
        if (binding == null) {
            try {
                binding = mediaBinding(toolRegistry.billingMediaType(toolCode),
                        toolRegistry.persistedMediaType(toolCode));
            } catch (RuntimeException unsupported) {
                throw new InvalidMediaArtifactIdentityException();
            }
        }
        if (binding == null || task == null
                || !Objects.equals(binding.storageType(), task.getMediaType())) invalid();
        return resultMediaType(binding.logicalType());
    }

    /** Physical result manifests use AUDIO for a logical MUSIC artifact. */
    public String resultMediaType(String logicalType) {
        MediaBinding binding = mediaBinding(logicalType);
        if (binding == null) invalid();
        return binding.storageType();
    }

    public String requireForRead(Long userId, AidRuntimeArtifact artifact, AidMediaTask task) {
        if (userId == null || artifact == null || artifact.getProducerRunId() == null
                || artifact.getProducerStepId() == null || artifact.getPlanRevision() == null
                || StrUtil.isBlank(artifact.getSourceContextDigest())) invalid();
        AidRuntimeRun run = runMapper.selectById(artifact.getProducerRunId());
        AidRuntimeRunStep step = stepMapper.selectById(artifact.getProducerStepId());
        if (run == null || step == null || !Objects.equals(run.getUserId(), userId)
                || !Objects.equals(step.getRunId(), run.getId())
                || !Objects.equals(artifact.getAgentVersionId(), run.getVersionId())) invalid();
        String logicalType = resolve(run, step, task, artifact.getSourceContextDigest(),
                artifact.getPlanRevision(), false);
        if (!Objects.equals(logicalType, artifact.getArtifactType())) invalid();
        return logicalType;
    }

    private String resolve(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task,
                           String expectedContextDigest, Long expectedPlanRevision,
                           boolean requireCurrentDescriptor) {
        if (run == null || step == null || task == null || !"0".equals(run.getDelFlag())
                || !"0".equals(step.getDelFlag()) || !Objects.equals(step.getRunId(), run.getId())
                || !"TOOL".equals(step.getStepKind())
                || !"ASSISTANT_TOOL".equals(step.getOperationKind())
                || StrUtil.isBlank(expectedContextDigest) || expectedPlanRevision == null
                || !Objects.equals(step.getPlanRevision(), expectedPlanRevision)
                || step.getApprovalId() == null
                || !Objects.equals(step.getMediaTaskId(), task.getId())
                || !Objects.equals(task.getUserId(), run.getUserId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !"ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())) invalid();
        MediaBinding binding = BINDINGS.get(step.getToolCode());
        RuntimeToolRegistry.Descriptor descriptor = null;
        if (requireCurrentDescriptor) {
            try {
                descriptor = toolRegistry.require(step.getToolCode());
                if (binding == null) {
                    binding = mediaBinding(toolRegistry.billingMediaType(step.getToolCode()),
                            toolRegistry.persistedMediaType(step.getToolCode()));
                }
            } catch (RuntimeException unsupported) {
                descriptor = null;
            }
        }

        AidRuntimeApproval approval = approvalMapper.selectById(step.getApprovalId());
        if (approval == null || !"0".equals(approval.getDelFlag())
                || !Objects.equals(approval.getRunId(), run.getId())
                || !Objects.equals(approval.getUserId(), run.getUserId())
                || !"PLAN_EXECUTION".equals(approval.getScopeKind())
                || !Objects.equals(approval.getPlanRevision(), expectedPlanRevision)
                || !APPROVAL_STATES.contains(approval.getStatus())) invalid();
        String scopeJson = approval.getScopeJson();
        if (StrUtil.isBlank(scopeJson)
                || !Objects.equals(approval.getScopeDigest(), SecureUtil.sha256(scopeJson))
                || !Objects.equals(approval.getContextDigest(), expectedContextDigest)) invalid();
        JSONObject root;
        try {
            root = JSON.parseObject(scopeJson);
        } catch (RuntimeException invalid) {
            throw new InvalidMediaArtifactIdentityException();
        }
        JSONObject plan = root == null ? null : root.getJSONObject("plan");
        JSONObject context = root == null ? null : root.getJSONObject("context");
        JSONObject scope = root == null ? null : root.getJSONObject("operationScope");
        JSONObject tool = root == null ? null : root.getJSONObject("tool");
        JSONObject billingIntent = tool == null ? null : tool.getJSONObject("billingIntent");
        JSONArray mediaTypes = scope == null ? null : scope.getJSONArray("mediaTypes");
        if (!requireCurrentDescriptor && binding == null && mediaTypes != null && mediaTypes.size() == 1
                && billingIntent != null) {
            MediaBinding logical = mediaBinding(mediaTypes.getString(0));
            if (logical != null) {
                binding = mediaBinding(RuntimeToolProvider.BillingMediaType.valueOf(logical.logicalType()),
                        billingIntent.getString("mediaType"));
            }
        }
        if (binding == null || !Objects.equals(binding.storageType(), task.getMediaType())) invalid();
        if (root == null || !"assistant-operation-scope/v1".equals(root.getString("schemaVersion"))
                || plan == null || context == null || scope == null || tool == null
                || root.getJSONObject("skill") != null
                || !Objects.equals(step.getStepKind(), plan.getString("executorKind"))
                || !Objects.equals(step.getStableStepId(), plan.getString("stableStepId"))
                || !Objects.equals(step.getPlanPosition(), plan.getInteger("position"))
                || !Objects.equals(approval.getPlanDigest(), plan.getString("definitionDigest"))
                || !Objects.equals(step.getToolCode(), plan.getString("bindingCode"))
                || requireCurrentDescriptor && (descriptor == null
                || !Objects.equals(descriptor.contractVersion(), plan.getString("bindingVersion"))
                || !Objects.equals(descriptor.schemaDigest(), tool.getString("schemaDigest")))
                || !Objects.equals(plan.getString("operationKind"), scope.getString("operationKind"))
                || !Objects.equals(step.getToolCode(), scope.getString("toolCode"))
                || mediaTypes == null || mediaTypes.size() != 1
                || !Objects.equals(binding.logicalType(), mediaTypes.getString(0))
                || !Objects.equals(expectedContextDigest, context.getString("digest"))
                || !digestMatches(context, "manifestJson", "digest")
                || !Boolean.TRUE.equals(tool.getBoolean("billable"))
                || !"BILLABLE".equals(tool.getString("sideEffectLevel"))
                || billingIntent == null
                || !Objects.equals(binding.storageType(), billingIntent.getString("mediaType"))
                || !digestMatches(tool, "argumentsJson", "argumentsDigest")
                || !digestMatches(tool, "preparedPayloadJson", "preparedPayloadDigest")) invalid();
        return binding.logicalType();
    }

    private MediaBinding mediaBinding(RuntimeToolProvider.BillingMediaType mediaType) {
        if (mediaType == null || mediaType == RuntimeToolProvider.BillingMediaType.TEXT) return null;
        return new MediaBinding(mediaType.name(),
                mediaType == RuntimeToolProvider.BillingMediaType.MUSIC ? "AUDIO" : mediaType.name());
    }

    private MediaBinding mediaBinding(RuntimeToolProvider.BillingMediaType mediaType, String storageType) {
        MediaBinding logical = mediaBinding(mediaType);
        if (logical == null || storageType == null
                || !Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC", "COMPOSE").contains(storageType)) return null;
        return new MediaBinding(logical.logicalType(), storageType);
    }

    private MediaBinding mediaBinding(String mediaType) {
        try {
            return mediaBinding(RuntimeToolProvider.BillingMediaType.valueOf(mediaType));
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private boolean digestMatches(JSONObject source, String valueField, String digestField) {
        String value = source.getString(valueField);
        return StrUtil.isNotBlank(value) && Objects.equals(source.getString(digestField), SecureUtil.sha256(value));
    }

    private void invalid() {
        throw new InvalidMediaArtifactIdentityException();
    }

    private record MediaBinding(String logicalType, String storageType) { }

    public static final class InvalidMediaArtifactIdentityException extends RuntimeException {
        public InvalidMediaArtifactIdentityException() {
            super("Media artifact producer identity is invalid");
        }
    }
}
