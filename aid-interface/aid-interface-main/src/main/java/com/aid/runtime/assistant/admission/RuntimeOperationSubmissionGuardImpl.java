package com.aid.runtime.assistant.admission;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.assistant.skill.AssistantSkillBudgetIdentity;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeBudgetPolicyMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;
import java.util.Set;

@Service
public class RuntimeOperationSubmissionGuardImpl implements RuntimeOperationSubmissionGuard
{
    private static final Set<String> ACTIVE_RUN_STATES = Set.of("READY", "RUNNING");

    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeBudgetPolicyMapper policies;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AssistantBudgetPolicyService budgetPolicies;
    @Resource private AidSkillRunMapper skillRuns;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public RuntimeOperationSubmissionDecision claim(RuntimeOperationSubmissionClaim claim)
    {
        return checkAndLock(claim, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public RuntimeOperationSubmissionDecision assertBeforeTaskBinding(RuntimeOperationSubmissionClaim claim)
    {
        return checkAndLock(claim, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void attachSkillRun(RuntimeOperationSubmissionClaim claim, Long childRunId)
    {
        requireClaim(claim);
        if (!"ASSISTANT_SKILL".equals(claim.getOperationKind()) || childRunId == null || childRunId <= 0)
            denied("OPERATION_SKILL_IDENTITY_INVALID");
        // The creation claim already holds the graph locks. Recheck that same graph;
        // a repeated receipt cannot replace a child or authorize another submission.
        checkAndLock(claim, false);
        AidRuntimeRun run = runs.selectById(claim.getRunId());
        AidRuntimeRunStep step = steps.selectById(claim.getStepId());
        AidRuntimeApproval approval = approvals.selectById(claim.getApprovalId());
        Date now = new Date();
        if (run == null || step == null || approval == null
                || !"SUBMITTING".equals(step.getStatus())
                || !"ACCEPTED".equals(step.getProviderAcceptanceState())
                || run.getStatus() == null || !ACTIVE_RUN_STATES.contains(run.getStatus())
                || Boolean.TRUE.equals(run.getStopRequested())
                || !"ACCEPTED".equals(approval.getStatus()) || approval.getExpiresAt() == null
                || !approval.getExpiresAt().after(now)
                || step.getChildRunId() != null && !Objects.equals(step.getChildRunId(), childRunId))
            denied("OPERATION_SKILL_BINDING_CHANGED");
        requireLease(run, step, claim.getRunLease(), claim.getStepLease(), now);
        var child = skillRuns.selectById(childRunId);
        if (child == null || !Objects.equals(child.getUserId(), claim.getUserId())
                || !AssistantSkillBudgetIdentity.INVOKE_SOURCE.equals(child.getInvokeSource())
                || !Objects.equals(child.getRootRunId(), child.getId()) || !"0".equals(child.getDelFlag()))
            denied("OPERATION_SKILL_IDENTITY_INVALID");
        JSONObject input = JSON.parseObject(child.getInputJson());
        AssistantSkillBudgetEnvelope envelope = input == null ? null
                : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
        AssistantSkillBudgetIdentity.validate(envelope);
        if (!Objects.equals(envelope.getAssistantRuntimeRunId(), run.getId())
                || !Objects.equals(envelope.getAssistantParentStepId(), step.getId())
                || !Objects.equals(envelope.getAssistantOuterStableStepKey(), step.getStableStepId())
                || !Objects.equals(envelope.getAssistantAttemptNo(), step.getAttemptNo())
                || !Objects.equals(envelope.getPayerUserId(), run.getUserId())
                || !Objects.equals(envelope.getApprovalId(), approval.getId())
                || !Objects.equals(envelope.getAssistantRuntimeRunFencingToken(), run.getFencingToken())
                || !Objects.equals(envelope.getAssistantParentStepFencingToken(), step.getFencingToken())
                || !Objects.equals(envelope.getBillingAuthorizationId(), approval.getBillingAuthorizationId())
                || !Objects.equals(envelope.getBillingAuthorizationRevision(), approval.getBillingAuthorizationRevision())
                || !Objects.equals(envelope.getSkillVersionId(), child.getSkillVersionId())
                || !Objects.equals(envelope.getPackageDigest(), child.getSkillConfigHash()))
            denied("OPERATION_SKILL_IDENTITY_INVALID");
        budgetPolicies.assertProviderSubmission(run.getId(), approval.getBillingAuthorizationId(),
                approval.getBillingAuthorizationRevision(), operationScope(approval));
        if (step.getChildRunId() != null) return;
        step.setChildRunId(childRunId);
        step.setUpdateBy(claim.getStepLease().getOwner());
        step.setUpdateTime(now);
        if (steps.updateById(step) != 1) denied("OPERATION_SKILL_BINDING_CHANGED");
    }

    private RuntimeOperationSubmissionDecision checkAndLock(RuntimeOperationSubmissionClaim claim,
                                                             boolean consumeClaim)
    {
        requireClaim(claim);
        AidRuntimeRun discovered = runs.selectById(claim.getRunId());
        if (discovered == null) denied("RUN_NOT_FOUND");
        AidAssistantSession session = sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, discovered.getSessionId()).eq(AidAssistantSession::getDelFlag, "0")
                .last("FOR UPDATE"));
        AidRuntimeRun run = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, claim.getRunId()).eq(AidRuntimeRun::getDelFlag, "0")
                .last("FOR UPDATE"));
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, claim.getRunId()).eq(AidRuntimeBudgetPolicy::getDelFlag, "0")
                .last("FOR UPDATE"));
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, claim.getApprovalId()).eq(AidRuntimeApproval::getRunId, claim.getRunId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRunStep step = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, claim.getStepId()).eq(AidRuntimeRunStep::getRunId, claim.getRunId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (session == null || run == null || policy == null || approval == null || step == null
                || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(session.getUserId(), claim.getUserId())
                || !Objects.equals(run.getUserId(), claim.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(step.getOperationKind(), claim.getOperationKind())
                || !Objects.equals(step.getIdempotencyKey(), claim.getIdempotencyKey()))
            denied("OPERATION_IDENTITY_CHANGED");

        if (Set.of("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING", "SUCCEEDED", "FAILED", "CANCELLED")
                .contains(step.getStatus())) return RuntimeOperationSubmissionDecision.RECONCILE_ONLY;
        Date now = new Date();
        requireLease(run, step, claim.getRunLease(), claim.getStepLease(), now);
        if (!ACTIVE_RUN_STATES.contains(run.getStatus()) || Boolean.TRUE.equals(run.getStopRequested())
                || !"READY".equals(step.getStatus()) || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || !"ACCEPTED".equals(approval.getStatus()) || approval.getExpiresAt() == null
                || !approval.getExpiresAt().after(now))
            denied("OPERATION_NOT_SUBMITTABLE");
        AssistantBudgetOperationScope operationScope = operationScope(approval);
        budgetPolicies.assertProviderSubmission(run.getId(), approval.getBillingAuthorizationId(),
                approval.getBillingAuthorizationRevision(), operationScope);
        // Canvas intent creation must not set SUBMITTING: the unified media admission handler
        // is the sole owner of READY -> INFLIGHT and its mediaTaskId/CAS transition.
        if (!consumeClaim) return RuntimeOperationSubmissionDecision.PROCEED_NEW_CLAIM;
        step.setStatus("SUBMITTING");
        step.setProviderAcceptanceState("ACCEPTED");
        step.setFencingToken(claim.getStepLease().getFencingToken());
        step.setUpdateBy(claim.getStepLease().getOwner());
        step.setUpdateTime(now);
        steps.updateById(step);
        return RuntimeOperationSubmissionDecision.PROCEED_NEW_CLAIM;
    }

    private AssistantBudgetOperationScope operationScope(AidRuntimeApproval approval)
    {
        try
        {
            JSONObject scope = JSON.parseObject(approval.getScopeJson());
            AssistantBudgetOperationScope value = scope == null ? null
                    : scope.getObject("operationScope", AssistantBudgetOperationScope.class);
            if (value == null) denied("OPERATION_SCOPE_MISSING");
            return value;
        }
        catch (RuntimeException invalid)
        {
            denied("OPERATION_SCOPE_INVALID");
            return null;
        }
    }

    private void requireLease(AidRuntimeRun run, AidRuntimeRunStep step, RuntimeLease runLease,
                              RuntimeLease stepLease, Date now)
    {
        if (runLease == null || stepLease == null || !Objects.equals(runLease.getOwner(), stepLease.getOwner())
                || !Objects.equals(run.getLeaseOwner(), runLease.getOwner()) || run.getLeaseUntil() == null
                || !run.getLeaseUntil().after(now) || !Objects.equals(run.getFencingToken(), runLease.getFencingToken())
                || !Objects.equals(step.getLeaseOwner(), stepLease.getOwner()) || step.getLeaseUntil() == null
                || !step.getLeaseUntil().after(now) || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken()))
            denied("OPERATION_LEASE_CHANGED");
    }

    private void requireClaim(RuntimeOperationSubmissionClaim claim)
    {
        if (claim == null || claim.getRunId() == null || claim.getUserId() == null || claim.getStepId() == null
                || claim.getApprovalId() == null || StrUtil.isBlank(claim.getOperationKind())
                || StrUtil.isBlank(claim.getIdempotencyKey()) || claim.getRunLease() == null
                || claim.getStepLease() == null || StrUtil.isBlank(claim.getRunLease().getOwner())
                || StrUtil.isBlank(claim.getStepLease().getOwner())
                || claim.getRunLease().getFencingToken() == null || claim.getStepLease().getFencingToken() == null)
            denied("OPERATION_CLAIM_INVALID");
    }

    private void denied(String code)
    {
        throw new ServiceException(code);
    }
}
