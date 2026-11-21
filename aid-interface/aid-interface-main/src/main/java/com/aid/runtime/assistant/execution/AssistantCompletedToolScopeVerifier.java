package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Historical completed Tool proof, anchored to this run's unchanged authority. Never executes a Tool. */
@Component
public class AssistantCompletedToolScopeVerifier {
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AssistantPlanExecutionScopeCodec scopeCodec;
    @Lazy @Resource private RuntimeToolRegistry toolRegistry;

    /**
     * Returns false only for a structurally valid receipt from an older run context. Invalid or
     * foreign receipts remain fail-closed and are never hidden as ordinary historical data.
     */
    public boolean belongsToCurrentContext(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        check(userId != null && userId > 0 && run != null && run.getId() != null
                && Objects.equals(userId, run.getUserId()) && run.getSessionId() != null
                && step != null && step.getId() != null && Objects.equals(step.getRunId(), run.getId())
                && "0".equals(step.getDelFlag()) && "SUCCEEDED".equals(step.getStatus())
                && "TOOL".equals(step.getStepKind()) && "ASSISTANT_TOOL".equals(step.getOperationKind())
                && step.getApprovalId() != null && step.getPlanRevision() != null
                && step.getPlanRevision() <= run.getPlanRevision(),
                "COMPLETED_TOOL_SCOPE_INVALID");
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        check(approval != null && "0".equals(approval.getDelFlag())
                && Objects.equals(approval.getRunId(), run.getId())
                && Objects.equals(approval.getUserId(), userId)
                && Objects.equals(approval.getPlanRevision(), step.getPlanRevision())
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && ("ACCEPTED".equals(approval.getStatus()) || "CONSUMED".equals(approval.getStatus())),
                "COMPLETED_TOOL_APPROVAL_INVALID");
        return Objects.equals(approval.getContextDigest(), run.getContextDigest());
    }

    public VerifiedRuntimeToolEffect require(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        check(userId != null && userId > 0 && run != null && run.getId() != null
                && Objects.equals(userId, run.getUserId()) && run.getSessionId() != null
                && step != null && step.getId() != null && Objects.equals(step.getRunId(), run.getId())
                && "0".equals(step.getDelFlag()) && "SUCCEEDED".equals(step.getStatus())
                && "TOOL".equals(step.getStepKind()) && "ASSISTANT_TOOL".equals(step.getOperationKind())
                && step.getApprovalId() != null && step.getAttemptNo() != null && step.getAttemptNo() > 0
                && StrUtil.isNotBlank(step.getToolCode())
                && StrUtil.isNotBlank(step.getIdempotencyKey()) && StrUtil.isNotBlank(step.getOutputJson()),
                "COMPLETED_TOOL_SCOPE_INVALID");
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        check(approval != null && "0".equals(approval.getDelFlag())
                && Objects.equals(approval.getRunId(), run.getId()) && Objects.equals(approval.getUserId(), userId)
                && Objects.equals(approval.getPlanRevision(), step.getPlanRevision())
                && Objects.equals(approval.getContextDigest(), run.getContextDigest())
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && ("ACCEPTED".equals(approval.getStatus()) || "CONSUMED".equals(approval.getStatus())),
                "COMPLETED_TOOL_APPROVAL_INVALID");
        AssistantPlanExecutionScopeCodec.Persisted scope = scopeCodec.decode(approval.getScopeJson());
        check(scope.isTool() && Objects.equals(scope.digest(), approval.getScopeDigest())
                && Objects.equals(scope.requestDigest(), approval.getRequestDigest())
                && Objects.equals(scope.context().getString("digest"), run.getContextDigest())
                && Objects.equals(scope.decision().getLong("observedPlanRevision"), step.getPlanRevision())
                && Objects.equals(scope.plan().getString("definitionDigest"), approval.getPlanDigest())
                && Objects.equals(scope.plan().getString("stableStepId"), step.getStableStepId())
                && Objects.equals(scope.plan().getInteger("position"), step.getPlanPosition())
                && StrUtil.isNotBlank(scope.plan().getString("operationKind"))
                && Objects.equals(scope.plan().getString("operationKind"), scope.operationScope().getOperationKind())
                && "TOOL".equals(scope.plan().getString("executorKind"))
                && Objects.equals(scope.plan().getString("bindingCode"), step.getToolCode())
                && Objects.equals(scope.operationScope().getToolCode(), step.getToolCode()),
                "COMPLETED_TOOL_SCOPE_CHANGED");
        String invocationId = scope.decision().getString("invocationId");
        String expectedKey = "asst-op:" + SecureUtil.sha256(run.getId() + "\n" + step.getPlanRevision()
                + "\n" + step.getStableStepId() + "\n" + step.getAttemptNo()
                + "\n" + invocationId).substring(0, 56);
        String version = scope.plan().getString("bindingVersion");
        String schemaDigest = scope.tool().getString("schemaDigest");
        check(StrUtil.isNotBlank(invocationId) && Objects.equals(expectedKey, step.getIdempotencyKey())
                && StrUtil.isNotBlank(version) && schemaDigest != null && schemaDigest.matches("[0-9a-f]{64}")
                && Objects.equals(scope.argumentsJson(),
                AssistantPlanExecutionJson.canonicalExplicitNulls(JSON.parseObject(scope.argumentsJson()))),
                "COMPLETED_TOOL_IDENTITY_CHANGED");
        toolRegistry.requirePinnedOutput(step.getToolCode(), version, schemaDigest, step.getOutputJson());
        List<String> refs = step.getOutputArtifactRef() == null ? List.of()
                : JSON.parseArray(step.getOutputArtifactRef(), String.class);
        return new VerifiedRuntimeToolEffect(userId, run.getSessionId(), run.getId(), step.getId(),
                step.getIdempotencyKey(), step.getToolCode(), version, schemaDigest,
                scope.executionManifestJson(), scope.argumentsJson(), step.getOutputJson(),
                refs == null ? List.of() : List.copyOf(refs), scope.preparedPayloadJson());
    }
    private void check(boolean valid, String code) { if (!valid) throw new ServiceException(code); }
}
