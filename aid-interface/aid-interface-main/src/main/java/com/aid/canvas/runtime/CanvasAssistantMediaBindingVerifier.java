package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.model.BillingSnapshot;
import com.aid.canvas.domain.CanvasEntities.Operation;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.impl.CanvasGenerationServiceImpl;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantPreparedToolAuthorityVerifier;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Proves real own media bindings/events from immutable Runtime approval and persisted task identities. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantMediaBindingVerifier {
    private static final Set<String> EVENTS = Set.of("TASK_ACCEPTED", "TASK_TERMINAL", "RESULT_AUTO_SELECTED");
    private final CanvasMapper canvas;
    private final AidMediaTaskMapper tasks;
    private final AidRuntimeRunStepMapper steps;
    private final AssistantPreparedToolAuthorityVerifier authorities;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.aid.runtime.service.RuntimeToolRegistry> toolRegistries;

    public Proof require(Long userId, AidRuntimeRun run, AidRuntimeRunStep step, Long taskId, boolean taskIntent) {
        return requireBinding(userId, run, step, taskId, taskIntent, false);
    }

    public Proof requireHistoricalCompleted(Long userId, AidRuntimeRun run, AidRuntimeRunStep step, Long taskId) {
        return requireBinding(userId, run, step, taskId, false, true);
    }

    private Proof requireBinding(Long userId, AidRuntimeRun run, AidRuntimeRunStep step,
                                 Long taskId, boolean taskIntent, boolean historical) {
        check(step != null && step.getToolCode() != null && CanvasAssistantMediaToolProvider.CODES.contains(step.getToolCode()), "CANVAS_MEDIA_BINDING_TOOL_INVALID");
        var authority = historical ? authorities.requireHistoricalCompleted(userId, run, step) : authorities.require(userId, run, step);
        JSONObject arguments = JSON.parseObject(authority.argumentsJson());
        JSONObject targetArgs = arguments == null ? null : arguments.getJSONObject("target");
        check(targetArgs != null, "CANVAS_MEDIA_BINDING_TARGET_INVALID");
        var invocation = new com.aid.runtime.tool.RuntimeToolInvocation();
        invocation.setContextManifestJson(authority.contextManifestJson());
        invocation.setRunId(run.getId());
        invocation.setUserId(userId);
        var target = CanvasMediaPresentationTarget.restore(invocation, arguments, authority.preparedPayloadJson());
        String mediaType = CanvasAssistantMediaToolProvider.logicalMediaType(step.getToolCode());
        String canvasType = CanvasAssistantMediaToolProvider.physicalMediaType(step.getToolCode());
        String persistedType = toolRegistries == null ? canvasType : toolRegistries.getObject().persistedMediaType(step.getToolCode());
        check(authority.billingIntent() != null
                && persistedType.equals(authority.billingIntent().getMediaType())
                && Objects.equals(authority.operationScope().getTargetRefs(), CanvasAssistantMediaToolProvider.scopeTargetRefs(target))
                && Objects.equals(authority.operationScope().getMediaTypes(), java.util.List.of(mediaType))
                && Objects.equals(run.getWorkspaceId(), target.workspaceId())
                && "1.0.0".equals(authority.contractVersion()), "CANVAS_MEDIA_BINDING_SCOPE_CHANGED");
        String operationId = CanvasGenerationServiceImpl.assistantMediaOperationId(run.getId(), step.getIdempotencyKey());
        TaskBinding binding = canvas.selectTaskBindingByOperation(target.workspaceId(), operationId);
        AidMediaTask task = taskId == null ? null : tasks.selectById(taskId);
        check(binding != null && Objects.equals(binding.getProjectId(), target.projectId())
                && Objects.equals(binding.getObjectId(), target.objectId())
                && Objects.equals(binding.getInputObjectVersion(), target.objectVersion())
                && Objects.equals(binding.getActorUserId(), userId)
                && Objects.equals(binding.getCallId(), step.getIdempotencyKey())
                && Objects.equals(binding.getRequestHash(), authority.argumentsDigest())
                && canvasType.equals(binding.getMediaType())
                && task != null && Objects.equals(task.getUserId(), userId)
                && Objects.equals(task.getProjectId(), target.projectId())
                && com.aid.billing.dto.BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(task.getBizTaskType())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())
                && persistedType.equals(task.getMediaType())
                && Objects.equals(task.getModelName(), authority.operationScope().getModelCode())
                && (binding.getTaskId() == null && "SUBMITTING".equals(binding.getStatus())
                    || Objects.equals(binding.getTaskId(), taskId))
                && (taskIntent && step.getMediaTaskId() == null || Objects.equals(step.getMediaTaskId(), taskId)),
                "CANVAS_MEDIA_BINDING_TASK_CHANGED");
        if (!taskIntent) {
            BillingSnapshot billing = JSON.parseObject(task.getBillingSnapshotJson(), BillingSnapshot.class);
            check(billing != null && Objects.equals(billing.getRuntimeRunId(), run.getId())
                    && Objects.equals(billing.getRuntimeStepId(), step.getId())
                    && Objects.equals(billing.getStableStepKey(), step.getStableStepId())
                    && Objects.equals(billing.getLogicalCallKey(), step.getIdempotencyKey())
                    && Objects.equals(billing.getRequestDigest(), authority.billingIntent().getRequestDigest())
                    && Objects.equals(billing.getQuoteId(), authority.billingIntent().getQuoteId())
                    && Objects.equals(billing.getQuoteDigest(), authority.billingIntent().getQuoteDigest()),
                    "CANVAS_MEDIA_BINDING_BILLING_CHANGED");
        }
        return new Proof(target, authority.contextManifestJson(), binding, task, step);
    }

    public Proof requireEvent(Long userId, AidRuntimeRun run, Operation operation) {
        check(operation != null && operation.getOperationType() != null && EVENTS.contains(operation.getOperationType()), "CANVAS_MEDIA_EVENT_UNSUPPORTED");
        JSONObject payload = JSON.parseObject(operation.getPayloadJson());
        check(payload != null, "CANVAS_MEDIA_EVENT_INVALID");
        Long taskId = payload.getLong("taskId");
        AidMediaTask task = taskId == null ? null : tasks.selectById(taskId);
        AidRuntimeRunStep step = task == null || task.getBizTaskId() == null ? null : steps.selectById(task.getBizTaskId());
        Proof proof = require(userId, run, step, taskId, false);
        check(Objects.equals(operation.getWorkspaceId(), proof.target().workspaceId())
                && Objects.equals(operation.getProjectId(), proof.target().projectId())
                && Objects.equals(operation.getActorUserId(), userId)
                && Objects.equals(payload.getString("objectId"), proof.target().objectId())
                && Objects.equals(payload.getLong("inputObjectVersion"), proof.target().objectVersion())
                && Objects.equals(payload.getLong("revision"), operation.getWorkspaceRevision())
                && Objects.equals(payload.getLong("eventSeq"), operation.getEventSeq())
                && Objects.equals(proof.binding().getTaskId(), taskId), "CANVAS_MEDIA_EVENT_SCOPE_CHANGED");
        if ("RESULT_AUTO_SELECTED".equals(operation.getOperationType())) {
            Long resultId = payload.getLong("resultId");
            String operationId = "RESULT_AUTO:" + taskId + ":" + resultId;
            var result = resultId == null ? null : canvas.selectMediaResultForNode(proof.target().workspaceId(), proof.target().objectId(), resultId);
            check("SUCCEEDED".equals(proof.task().getStatus()) && result != null
                    && Objects.equals(resultId, numericId(result.get("resultId")))
                    && Objects.equals(taskId, numericId(result.get("taskId")))
                    && Objects.equals(proof.target().objectVersion(), numericId(result.get("inputObjectVersion")))
                    && result.get("ossUrl") instanceof String url && !url.isBlank()
                    && Objects.equals(operationId, operation.getOperationId())
                    && Objects.equals(SecureUtil.sha256(operationId), operation.getPayloadHash())
                    && payload.getLong("selectionVersion") != null && payload.getLong("selectionVersion") > 0,
                    // The complete own operation chain verifies its final selection, after later own adoptions.
                    // An immutable historical automatic selection need not still be the current selection.
                    "CANVAS_MEDIA_EVENT_SELECTION_CHANGED");
        } else {
            check(Objects.equals(operation.getOperationType() + ":" + taskId, operation.getOperationId())
                    && Objects.equals(proof.binding().getOperationId(), payload.getString("operationId"))
                    && Objects.equals(SecureUtil.sha256(operation.getPayloadJson()), operation.getPayloadHash()),
                    "CANVAS_MEDIA_EVENT_RECEIPT_CHANGED");
        }
        return proof;
    }

    /** Classifies a media event before the strict proof path validates every persisted identity. */
    public boolean isForRun(AidRuntimeRun run, Operation operation) {
        if (run == null || run.getId() == null || operation == null
                || operation.getOperationType() == null || !EVENTS.contains(operation.getOperationType())) {
            return false;
        }
        try {
            JSONObject payload = JSON.parseObject(operation.getPayloadJson());
            Long taskId = payload == null ? null : payload.getLong("taskId");
            AidMediaTask task = taskId == null ? null : tasks.selectById(taskId);
            AidRuntimeRunStep step = task == null || task.getBizTaskId() == null
                    ? null : steps.selectById(task.getBizTaskId());
            return step != null && Objects.equals(step.getRunId(), run.getId());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public record Proof(CanvasAssistantContextManifest.TargetRef target, String contextManifestJson,
                        TaskBinding binding, AidMediaTask task, AidRuntimeRunStep step) { }

    private static void check(boolean valid, String code) {
        if (!valid) throw new ServiceException("画布媒体任务校验失败").setDetailMessage(code);
    }

    private static Long numericId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (!(value instanceof String string) || !string.matches("[1-9][0-9]{0,18}")) return null;
        try {
            long parsed = Long.parseLong(string);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
