package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Resolves the exact persisted Tool contract used by one completed runtime step. */
@Component
public class AssistantToolObservationScopeVerifier {
    private static final Set<String> OBSERVABLE_APPROVAL_STATES = Set.of("ACCEPTED", "CONSUMED");

    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AssistantPlanExecutionScopeCodec scopeCodec;

    /** A past read is evidence only, never authorization to replay a past operation.
     * Returns null for an owned observation whose context or approval has been superseded.
     */
    public VerifiedToolScope requireReadHistory(Long userId, AidRuntimeRun run, AidRuntimeRunStep step,
            com.aid.runtime.service.RuntimeToolRegistry.Descriptor descriptor) {
        require(run != null && step != null && run.getPlanRevision() != null
                && step.getPlanRevision() != null && step.getPlanRevision() > 0
                && step.getPlanRevision() <= run.getPlanRevision()
                && "SUCCEEDED".equals(step.getStatus()) && "0".equals(step.getDelFlag())
                && descriptor != null && "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable()
                && Objects.equals(descriptor.code(), step.getToolCode()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_INVALID");
        require(userId != null && Objects.equals(run.getUserId(), userId)
                && Objects.equals(step.getRunId(), run.getId()) && step.getApprovalId() != null
                && "TOOL".equals(step.getStepKind()) && "ASSISTANT_TOOL".equals(step.getOperationKind()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_INVALID");
        AidRuntimeApproval original = approvals.selectById(step.getApprovalId());
        require(original != null && Objects.equals(original.getRunId(), run.getId())
                && Objects.equals(original.getUserId(), userId)
                && Objects.equals(original.getPlanRevision(), step.getPlanRevision())
                && "PLAN_EXECUTION".equals(original.getScopeKind()) && "0".equals(original.getDelFlag()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_INVALID");
        if (!Objects.equals(original.getContextDigest(), run.getContextDigest())
                || "INVALIDATED".equals(original.getStatus()) || "EXPIRED".equals(original.getStatus())) {
            return null;
        }
        AidRuntimeRun evidenceScope = new AidRuntimeRun();
        evidenceScope.setId(run.getId());
        evidenceScope.setUserId(run.getUserId());
        evidenceScope.setPlanRevision(step.getPlanRevision());
        // Keep CURRENT context digest: a past approval from a changed context is not reusable.
        evidenceScope.setContextDigest(run.getContextDigest());
        VerifiedToolScope result = require(userId, evidenceScope, step);
        require(Objects.equals(descriptor.contractVersion(), result.contractVersion())
                && Objects.equals(descriptor.schemaDigest(), result.schemaDigest()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_CHANGED");
        return result;
    }

    public VerifiedToolScope require(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        require(userId != null && userId > 0 && run != null && run.getId() != null
                && Objects.equals(run.getUserId(), userId) && step != null && step.getId() != null
                && Objects.equals(step.getRunId(), run.getId()) && step.getApprovalId() != null
                && "TOOL".equals(step.getStepKind()) && "ASSISTANT_TOOL".equals(step.getOperationKind())
                && StrUtil.isNotBlank(step.getToolCode()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_INVALID");
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        require(approval != null && Objects.equals(approval.getRunId(), run.getId())
                && Objects.equals(approval.getUserId(), userId)
                && Objects.equals(approval.getPlanRevision(), step.getPlanRevision())
                && Objects.equals(approval.getPlanRevision(), run.getPlanRevision())
                && Objects.equals(approval.getContextDigest(), run.getContextDigest())
                && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && OBSERVABLE_APPROVAL_STATES.contains(approval.getStatus())
                && "0".equals(approval.getDelFlag())
                && StrUtil.isNotBlank(approval.getScopeJson())
                && StrUtil.isNotBlank(approval.getScopeDigest()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_INVALID");

        AssistantPlanExecutionScopeCodec.Persisted persisted = scopeCodec.decode(approval.getScopeJson());
        require(Objects.equals(persisted.digest(), approval.getScopeDigest()) && persisted.isTool()
                && Objects.equals(persisted.requestDigest(), approval.getRequestDigest())
                && Objects.equals(persisted.context().getString("digest"), run.getContextDigest())
                && Objects.equals(persisted.plan().getString("stableStepId"), step.getStableStepId())
                && Objects.equals(persisted.plan().getInteger("position"), step.getPlanPosition())
                && StrUtil.isNotBlank(persisted.plan().getString("operationKind"))
                && Objects.equals(persisted.plan().getString("operationKind"),
                        persisted.operationScope().getOperationKind())
                && Objects.equals(persisted.plan().getString("executorKind"), step.getStepKind())
                && Objects.equals(persisted.plan().getString("bindingCode"), step.getToolCode())
                && Objects.equals(persisted.operationScope().getToolCode(), step.getToolCode()),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_CHANGED");

        String contractVersion = persisted.plan().getString("bindingVersion");
        String schemaDigest = persisted.tool().getString("schemaDigest");
        String argumentsJson = persisted.argumentsJson();
        require(StrUtil.isNotBlank(contractVersion)
                && schemaDigest != null && schemaDigest.matches("[0-9a-f]{64}")
                && StrUtil.isNotBlank(argumentsJson)
                && Objects.equals(argumentsJson, canonicalObject(argumentsJson)),
                "ASSISTANT_RESULT_OBSERVATION_SCOPE_CHANGED");
        return new VerifiedToolScope(step.getToolCode(), contractVersion, schemaDigest,
                argumentsJson, persisted.argumentsDigest());
    }

    private String canonicalObject(String value) {
        try {
            JSONObject object = JSON.parseObject(value);
            require(object != null, "ASSISTANT_RESULT_OBSERVATION_SCOPE_CHANGED");
            return AssistantPlanExecutionJson.canonicalExplicitNulls(object);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_SCOPE_CHANGED");
        }
    }

    private void require(boolean condition, String code) {
        if (!condition) throw new ServiceException(code);
    }

    public record VerifiedToolScope(String toolCode, String contractVersion, String schemaDigest,
                                    String argumentsJson, String argumentsDigest) {
    }
}
