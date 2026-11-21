package com.aid.assistant.skill;

import com.aid.billing.dto.BillingBudgetViews.AuthorizationView;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.service.ISkillInvocationService;
import com.aid.skill.vo.SkillInvocationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Validates the trusted authorization envelope before entering the legacy Skill state machine. */
@Service
@RequiredArgsConstructor
public class AssistantSkillInvocationBridgeImpl implements AssistantSkillInvocationBridge {
    private final ISkillInvocationService skillInvocationService;
    private final AssistantBillingBudgetService budgetService;
    private final AssistantSkillStopReconciliationService stopReconciliationService;

    @Override
    public SkillInvocationVO invokeFixedVersion(AssistantFixedSkillInvocation request, Long userId, String operator) {
        if (request == null || request.getInvocation() == null || userId == null || userId <= 0) {
            throw new ServiceException("Assistant Skill invocation is invalid");
        }
        if (!Boolean.TRUE.equals(request.getMethodOnly())) {
            throw new ServiceException("Assistant Skill method-only proof is required");
        }
        AssistantSkillBudgetEnvelope envelope = request.getBudgetEnvelope();
        RuntimeOperationSubmissionClaim claim = submissionClaim(request, envelope, userId);
        bindCreationFences(envelope, claim);
        AssistantSkillBudgetIdentity.validate(envelope);
        if (!Objects.equals(userId, envelope.getPayerUserId())) {
            throw new ServiceException("Assistant Skill payer identity is invalid");
        }
        AuthorizationView authorization = budgetService.getAuthorization(envelope.getBillingAuthorizationId());
        if (authorization == null
                || !Objects.equals(authorization.getRuntimeRunId(), envelope.getAssistantRuntimeRunId())
                || !Objects.equals(authorization.getPayerUserId(), envelope.getPayerUserId())
                || !Objects.equals(authorization.getRevision(), envelope.getBillingAuthorizationRevision())) {
            throw new ServiceException("Assistant Skill authorization is stale");
        }
        SkillInvocationRequests.InvokeRequest invocation = request.getInvocation();
        invocation.setAssistantBudgetEnvelope(envelope);
        invocation.setAssistantSubmissionClaim(claim);
        return skillInvocationService.invokeFixedVersion(invocation, envelope.getSkillVersionId(),
                envelope.getPackageDigest(), userId, operator, AssistantSkillBudgetIdentity.INVOKE_SOURCE);
    }

    private RuntimeOperationSubmissionClaim submissionClaim(AssistantFixedSkillInvocation request,
                                                              AssistantSkillBudgetEnvelope envelope,
                                                              Long userId) {
        RuntimeLease runLease = request.getRunLease();
        RuntimeLease stepLease = request.getStepLease();
        if (runLease == null || stepLease == null || runLease.getOwner() == null || stepLease.getOwner() == null
                || !Objects.equals(runLease.getOwner(), stepLease.getOwner())
                || runLease.getFencingToken() == null || stepLease.getFencingToken() == null) {
            throw new ServiceException("Assistant Skill runtime lease is invalid");
        }
        RuntimeOperationSubmissionClaim claim = new RuntimeOperationSubmissionClaim();
        claim.setRunId(envelope.getAssistantRuntimeRunId());
        claim.setUserId(userId);
        claim.setStepId(envelope.getAssistantParentStepId());
        claim.setApprovalId(envelope.getApprovalId());
        claim.setOperationKind("ASSISTANT_SKILL");
        claim.setIdempotencyKey(request.getInvocation().getIdempotencyKey());
        claim.setRunLease(runLease);
        claim.setStepLease(stepLease);
        return claim;
    }

    private void bindCreationFences(AssistantSkillBudgetEnvelope envelope,
                                    RuntimeOperationSubmissionClaim claim) {
        if (envelope == null || claim == null) {
            throw new ServiceException("Assistant Skill runtime fence is invalid");
        }
        Long runFence = claim.getRunLease().getFencingToken();
        Long stepFence = claim.getStepLease().getFencingToken();
        if (envelope.getAssistantRuntimeRunFencingToken() != null
                && !Objects.equals(envelope.getAssistantRuntimeRunFencingToken(), runFence)
                || envelope.getAssistantParentStepFencingToken() != null
                && !Objects.equals(envelope.getAssistantParentStepFencingToken(), stepFence)) {
            throw new ServiceException("Assistant Skill runtime fence is stale");
        }
        envelope.setAssistantRuntimeRunFencingToken(runFence);
        envelope.setAssistantParentStepFencingToken(stepFence);
    }

    @Override
    public SkillInvocationVO findByAssistantIdentity(Long assistantRuntimeRunId, Long assistantParentStepId,
                                                     String assistantOuterStableStepKey, Integer assistantAttemptNo,
                                                     Long userId) {
        return skillInvocationService.findByAssistantIdentity(assistantRuntimeRunId, assistantParentStepId,
                assistantOuterStableStepKey, assistantAttemptNo, userId);
    }

    @Override
    public SkillInvocationVO reconcileStoppedInvocation(Long assistantRuntimeRunId, Long assistantParentStepId,
                                                        String assistantOuterStableStepKey,
                                                        Integer assistantAttemptNo, Long userId, String operator) {
        stopReconciliationService.reconcile(assistantRuntimeRunId, assistantParentStepId,
                assistantOuterStableStepKey, assistantAttemptNo, userId, operator);
        return skillInvocationService.findByAssistantIdentity(assistantRuntimeRunId, assistantParentStepId,
                assistantOuterStableStepKey, assistantAttemptNo, userId);
    }
}
