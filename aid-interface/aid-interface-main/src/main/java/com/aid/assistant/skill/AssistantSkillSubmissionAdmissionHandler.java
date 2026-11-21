package com.aid.assistant.skill;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.billing.dto.BillingBudgetRequests.SubmissionClaimRequest;
import com.aid.billing.dto.BillingBudgetViews.SubmissionClaimResult;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.MediaSubmissionAdmissionDecision;
import com.aid.media.admission.MediaSubmissionAdmissionHandler;
import com.aid.media.admission.MediaTaskCancellationAdmissionDecision;
import com.aid.media.admission.MediaTaskCancellationAdmissionHandler;
import com.aid.media.admission.MediaTaskCancellationPurpose;
import com.aid.media.admission.MediaTaskCancellationRequest;
import com.aid.media.admission.MediaTaskIntentAdmissionDecision;
import com.aid.media.admission.MediaTaskIntentAdmissionHandler;
import com.aid.media.enums.MediaTaskStatus;
import cn.hutool.core.util.StrUtil;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.assistant.replan.RuntimeReplanCancellationAuthorizationPort;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;
import java.util.Set;

/** Transactional Skill step CAS and budget claim at the Provider boundary. */
@Component
@RequiredArgsConstructor
public class AssistantSkillSubmissionAdmissionHandler
        implements MediaSubmissionAdmissionHandler, MediaTaskIntentAdmissionHandler,
        MediaTaskCancellationAdmissionHandler {
    private static final String NORMAL = "0";
    private static final Set<String> TERMINAL_STEP = Set.of("OUTPUT_READY", "FAILED", "CANCELLED");
    private final AidSkillRunMapper runMapper;
    private final AidSkillRunStepMapper stepMapper;
    private final AidSkillRunTaskLinkMapper taskLinkMapper;
    private final AidRuntimeRunMapper runtimeRunMapper;
    private final AidRuntimeApprovalMapper runtimeApprovalMapper;
    private final AidRuntimeRunStepMapper runtimeStepMapper;
    private final AssistantBillingBudgetService budgetService;
    private final AssistantBudgetPolicyService budgetPolicyService;
    private final RuntimeReplanCancellationAuthorizationPort replanCancellationAuthorization;

    @Override
    public String supportedBizTaskType() {
        return AssistantSkillBudgetIdentity.BIZ_TASK_TYPE;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MediaTaskIntentAdmissionDecision claimBeforeCommit(AidMediaTask task,
                                                               BillingBudgetContext context) {
        validateIntent(task, context);
        AssistantSkillBudgetEnvelope routing = routeEnvelope(task.getBizTaskId());
        AidRuntimeRun outerRun = lockOuterRun(context.getRuntimeRunId());
        AidRuntimeApproval approval = lockOuterApproval(outerRun.getId(), routing.getApprovalId());
        AidRuntimeRunStep outerStep = lockOuterStep(outerRun.getId(), routing.getAssistantParentStepId(),
                approval.getId());
        AidSkillRun run = lockRun(task.getBizTaskId());
        AssistantSkillBudgetEnvelope envelope = envelope(run);
        AidSkillRunStep step = lockStep(run.getId(), context.getRuntimeStepId());
        validateIntentIdentity(task, context, outerRun, approval, outerStep, run, step, envelope);

        AidSkillRunTaskLink existing = lockLinkByLogicalCall(step.getId(), context.getLogicalCallKey());
        if (existing != null) {
            return sameTaskLink(existing, run, step, task, context)
                    ? MediaTaskIntentAdmissionDecision.REPLAY_EXISTING
                    : MediaTaskIntentAdmissionDecision.RECONCILE_ONLY;
        }
        if (!"RUNNING".equals(run.getStatus()) || !"PLANNED".equals(step.getOrchestrationStatus())) {
            return MediaTaskIntentAdmissionDecision.RECONCILE_ONLY;
        }

        Date now = new Date();
        AidSkillRunTaskLink link = new AidSkillRunTaskLink();
        link.setRunId(run.getId());
        link.setStepId(step.getId());
        link.setStepExecutionId(step.getStepExecutionId());
        link.setWorkflowAttempt(step.getWorkflowAttempt());
        link.setLogicalCallKey(context.getLogicalCallKey());
        link.setMediaTaskId(task.getId());
        link.setDelFlag(NORMAL);
        link.setCreateBy("skill:" + run.getId());
        link.setCreateTime(now);
        if (taskLinkMapper.insert(link) != 1) {
            throw new ServiceException("Skill task intent was not persisted");
        }
        step.setOrchestrationStatus("WAITING_TASK");
        step.setUpdateBy("skill:" + run.getId());
        step.setUpdateTime(now);
        if (stepMapper.updateById(step) != 1) {
            throw new ServiceException("Skill task intent state has changed");
        }
        return MediaTaskIntentAdmissionDecision.PERSIST_NEW;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaSubmissionAdmissionDecision claimBeforeProvider(AidMediaTask task, BillingSnapshot snapshot) {
        validateSnapshot(task, snapshot);
        AssistantSkillBudgetEnvelope routing = routeEnvelope(task.getBizTaskId());
        AidRuntimeRun outerRun = lockOuterRun(snapshot.getRuntimeRunId());
        AidRuntimeApproval outerApproval = lockOuterApproval(outerRun.getId(), routing.getApprovalId());
        AidRuntimeRunStep outerStep = lockOuterStep(outerRun.getId(), routing.getAssistantParentStepId(),
                outerApproval.getId());
        AidSkillRun run = lockRun(task.getBizTaskId());
        AssistantSkillBudgetEnvelope envelope = envelope(run);
        AidSkillRunStep step = lockStep(run.getId(), snapshot.getRuntimeStepId());
        AidSkillRunTaskLink link = lockLink(step.getId(), task.getId());
        validateIdentity(task, snapshot, run, step, link, envelope);
        validateOuterProviderIdentity(outerRun, outerApproval, outerStep, envelope, task);
        AssistantBudgetOperationScope approvedScope = providerScope(envelope, task, run, snapshot);
        budgetPolicyService.assertProviderSubmission(envelope.getAssistantRuntimeRunId(),
                envelope.getBillingAuthorizationId(), envelope.getBillingAuthorizationRevision(), approvedScope);
        if ("INFLIGHT".equals(step.getOrchestrationStatus())
                || "RECONCILING".equals(step.getOrchestrationStatus())) {
            return MediaSubmissionAdmissionDecision.RECONCILE_ONLY;
        }
        if (Boolean.TRUE.equals(outerRun.getStopRequested())) {
            return MediaSubmissionAdmissionDecision.RECONCILE_ONLY;
        }
        if (!Boolean.TRUE.equals(outerRun.getStopRequested())
                && Set.of("PAUSING", "PAUSED").contains(outerRun.getStatus())
                && "WAITING_TASK".equals(step.getOrchestrationStatus())) {
            return MediaSubmissionAdmissionDecision.DEFER_UNTIL_RESUME;
        }
        if (!"RUNNING".equals(run.getStatus()) || !"WAITING_TASK".equals(step.getOrchestrationStatus())) {
            throw new ServiceException("Skill submission state has changed");
        }
        step.setOrchestrationStatus("INFLIGHT");
        step.setUpdateBy("skill:" + run.getId());
        step.setUpdateTime(new Date());
        if (stepMapper.updateById(step) != 1) {
            throw new ServiceException("Skill submission state has changed");
        }

        SubmissionClaimRequest claim = new SubmissionClaimRequest();
        claim.setAuthorizationId(snapshot.getBillingAuthorizationId());
        claim.setAuthorizationRevision(snapshot.getBillingAuthorizationRevision());
        claim.setReservationId(snapshot.getBillingReservationId());
        claim.setExpectedReservationRevision(snapshot.getBillingReservationRevision());
        claim.setRuntimeRunId(snapshot.getRuntimeRunId());
        claim.setRuntimeStepId(step.getId());
        claim.setStableStepKey(AssistantSkillBudgetIdentity.stableStepKey(envelope, step));
        claim.setAttemptNo(step.getWorkflowAttempt());
        claim.setLogicalCallKey(link.getLogicalCallKey());
        claim.setMediaTaskId(task.getId());
        claim.setOperator("skill:" + run.getId());
        SubmissionClaimResult result = budgetService.claimSubmission(claim);
        if (result == null || (!result.isNewlyClaimed() && !result.isAlreadyInflight())) {
            throw new ServiceException("Skill budget claim failed");
        }
        return result.isNewlyClaimed() ? MediaSubmissionAdmissionDecision.PROCEED_NEW_CLAIM
                : MediaSubmissionAdmissionDecision.RECONCILE_ONLY;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MediaTaskCancellationAdmissionDecision claimCancellation(AidMediaTask task,
                                                                      BillingSnapshot snapshot,
                                                                      MediaTaskCancellationRequest request) {
        validateSnapshot(task, snapshot);
        AssistantSkillBudgetEnvelope routing = routeEnvelope(task.getBizTaskId());
        AidRuntimeRun discoveredOuterRun = runtimeRunMapper.selectById(snapshot.getRuntimeRunId());
        RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity replanIdentity =
                authorizeReplanCancellation(task, snapshot, request, discoveredOuterRun);
        AidRuntimeRun outerRun = lockOuterRun(snapshot.getRuntimeRunId());
        AidRuntimeApproval outerApproval = lockOuterApproval(outerRun.getId(), routing.getApprovalId());
        AidRuntimeRunStep outerStep = lockOuterStep(outerRun.getId(), routing.getAssistantParentStepId(),
                outerApproval.getId());
        AidSkillRun run = lockRun(task.getBizTaskId());
        AssistantSkillBudgetEnvelope envelope = envelope(run);
        AidSkillRunStep step = lockStep(run.getId(), snapshot.getRuntimeStepId());
        AidSkillRunTaskLink link = lockLink(step.getId(), task.getId());
        validateIdentity(task, snapshot, run, step, link, envelope);
        validateOuterProviderIdentity(outerRun, outerApproval, outerStep, envelope, task);
        if (!purposeAllowsCancellation(outerRun, request, replanIdentity)
                || !Set.of("RUNNING", "RECONCILING").contains(run.getStatus())
                || !Set.of("WAITING_TASK", "RECONCILING").contains(step.getOrchestrationStatus())
                || !Set.of(MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name())
                .contains(task.getStatus())
                || StrUtil.isNotBlank(task.getProviderTaskId())
                || task.getUpstreamAcceptTime() != null) {
            return MediaTaskCancellationAdmissionDecision.RECONCILE_ONLY;
        }
        if (!budgetService.lockUnsubmittedCancellation(task)) {
            return MediaTaskCancellationAdmissionDecision.RECONCILE_ONLY;
        }
        return MediaTaskCancellationAdmissionDecision.CANCEL_NOT_SUBMITTED;
    }

    private RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity
            authorizeReplanCancellation(AidMediaTask task, BillingSnapshot snapshot,
                                        MediaTaskCancellationRequest request,
                                        AidRuntimeRun discoveredRun) {
        if (request == null || request.purpose() == null) {
            throw new ServiceException("Skill cancellation purpose is unavailable");
        }
        if (request.purpose() == MediaTaskCancellationPurpose.STOP) return null;
        if (!request.isReplan() || discoveredRun == null || discoveredRun.getSessionId() == null
                || !Objects.equals(request.expectedRuntimeRunId(), snapshot.getRuntimeRunId())) {
            return null;
        }
        try {
            return replanCancellationAuthorization.lockAndVerify(
                    new RuntimeReplanCancellationAuthorizationPort.ReplanCancellationAuthorizationRequest(
                            snapshot.getRuntimeRunId(), discoveredRun.getSessionId(), task.getUserId(),
                            request.leaseOwner(), request.expectedRuntimeRunFencingToken(),
                            request.replanIntentId(), request.replanIntentStateRevision(),
                            request.replanCommandId()));
        } catch (RuntimeException stale) {
            return null;
        }
    }

    private boolean purposeAllowsCancellation(
            AidRuntimeRun run, MediaTaskCancellationRequest request,
            RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity identity) {
        if (request == null || request.purpose() == null) return false;
        if (request.purpose() == MediaTaskCancellationPurpose.STOP) {
            return Boolean.TRUE.equals(run.getStopRequested());
        }
        return identity != null
                && !Boolean.TRUE.equals(run.getStopRequested())
                && "PAUSING".equals(run.getStatus())
                && "USER_REQUEST".equals(run.getPauseReason())
                && Set.of("CANCEL_FOR_REPLAN", "WAIT_REPLAN_RECONCILIATION")
                .contains(run.getNextAction())
                && Objects.equals(run.getId(), request.expectedRuntimeRunId())
                && Objects.equals(run.getId(), identity.runId())
                && Objects.equals(run.getSessionId(), identity.sessionId())
                && Objects.equals(run.getFencingToken(), request.expectedRuntimeRunFencingToken())
                && Objects.equals(run.getLeaseOwner(), request.leaseOwner())
                && run.getLeaseUntil() != null && run.getLeaseUntil().after(new Date())
                && Objects.equals(identity.intentId(), request.replanIntentId())
                && Objects.equals(identity.intentStateRevision(), request.replanIntentStateRevision())
                && Objects.equals(identity.commandId(), request.replanCommandId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markReconciliationRequired(AidMediaTask task, BillingSnapshot snapshot) {
        validateSnapshot(task, snapshot);
        AidSkillRun run = lockRun(task.getBizTaskId());
        AssistantSkillBudgetEnvelope envelope = envelope(run);
        AidSkillRunStep step = lockStep(run.getId(), snapshot.getRuntimeStepId());
        AidSkillRunTaskLink link = lockLink(step.getId(), task.getId());
        validateIdentity(task, snapshot, run, step, link, envelope);
        if (TERMINAL_STEP.contains(step.getOrchestrationStatus())) {
            return;
        }
        if (!Set.of("INFLIGHT", "RECONCILING").contains(step.getOrchestrationStatus())) {
            throw new ServiceException("Skill reconciliation state has changed");
        }
        Date now = new Date();
        if (!"RECONCILING".equals(step.getOrchestrationStatus())) {
            step.setOrchestrationStatus("RECONCILING");
            step.setUpdateBy("skill:" + run.getId());
            step.setUpdateTime(now);
            if (stepMapper.updateById(step) != 1) {
                throw new ServiceException("Skill reconciliation state has changed");
            }
        }
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELED", "RECONCILING").contains(run.getStatus())) {
            run.setStatus("RECONCILING");
            run.setStage("WAIT_TASK_RECONCILIATION");
            run.setUpdateBy("skill:" + run.getId());
            run.setUpdateTime(now);
            if (runMapper.updateById(run) != 1) {
                throw new ServiceException("Skill reconciliation state has changed");
            }
        }
    }

    private AidRuntimeRun lockOuterRun(Long runId) {
        AidRuntimeRun run = runtimeRunMapper.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getId, runId).eq(AidRuntimeRun::getDelFlag, NORMAL)
                .last("FOR UPDATE"));
        if (run == null) throw new ServiceException("Assistant runtime is unavailable");
        return run;
    }

    private AidRuntimeApproval lockOuterApproval(Long runId, Long approvalId) {
        AidRuntimeApproval approval = runtimeApprovalMapper.selectOne(Wrappers.<AidRuntimeApproval>lambdaQuery()
                .eq(AidRuntimeApproval::getId, approvalId).eq(AidRuntimeApproval::getRunId, runId)
                .eq(AidRuntimeApproval::getScopeKind, "PLAN_EXECUTION")
                .eq(AidRuntimeApproval::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (approval == null) throw new ServiceException("Assistant Skill approval is unavailable");
        return approval;
    }

    private AidRuntimeRunStep lockOuterStep(Long runId, Long stepId, Long approvalId) {
        AidRuntimeRunStep step = runtimeStepMapper.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getApprovalId, approvalId)
                .eq(AidRuntimeRunStep::getOperationKind, "ASSISTANT_SKILL")
                .eq(AidRuntimeRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (step == null) throw new ServiceException("Assistant Skill outer step is unavailable");
        return step;
    }

    private AssistantSkillBudgetEnvelope routeEnvelope(Long skillRunId) {
        AidSkillRun run = runMapper.selectById(skillRunId);
        if (run == null || !NORMAL.equals(run.getDelFlag())) {
            throw new ServiceException("Skill run is unavailable");
        }
        return envelope(run);
    }

    private AidSkillRunTaskLink lockLinkByLogicalCall(Long stepId, String logicalCallKey) {
        return taskLinkMapper.selectOne(Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                .eq(AidSkillRunTaskLink::getStepId, stepId)
                .eq(AidSkillRunTaskLink::getLogicalCallKey, logicalCallKey)
                .eq(AidSkillRunTaskLink::getDelFlag, NORMAL).last("LIMIT 1 FOR UPDATE"));
    }

    private boolean sameTaskLink(AidSkillRunTaskLink link, AidSkillRun run, AidSkillRunStep step,
                                 AidMediaTask task, BillingBudgetContext context) {
        return Objects.equals(link.getRunId(), run.getId())
                && Objects.equals(link.getStepId(), step.getId())
                && Objects.equals(link.getStepExecutionId(), step.getStepExecutionId())
                && Objects.equals(link.getWorkflowAttempt(), step.getWorkflowAttempt())
                && Objects.equals(link.getLogicalCallKey(), context.getLogicalCallKey())
                && Objects.equals(link.getMediaTaskId(), task.getId())
                && Objects.equals(task.getIdempotencyKey(), context.getLogicalCallKey());
    }

    private void validateIntentIdentity(AidMediaTask task, BillingBudgetContext context,
                                        AidRuntimeRun outerRun, AidRuntimeApproval approval,
                                        AidRuntimeRunStep outerStep, AidSkillRun run,
                                        AidSkillRunStep step, AssistantSkillBudgetEnvelope envelope) {
        if (!Objects.equals(outerRun.getUserId(), task.getUserId())
                || Boolean.TRUE.equals(outerRun.getStopRequested())
                || !Objects.equals(outerRun.getFencingToken(), context.getRuntimeRunFencingToken())
                || !Objects.equals(outerStep.getFencingToken(), context.getRuntimeStepFencingToken())
                || !Objects.equals(outerStep.getId(), envelope.getAssistantParentStepId())
                || !Objects.equals(outerStep.getStableStepId(), envelope.getAssistantOuterStableStepKey())
                || !Objects.equals(outerStep.getAttemptNo(), envelope.getAssistantAttemptNo())
                || !Objects.equals(outerStep.getApprovalId(), envelope.getApprovalId())
                || !"SUBMITTING".equals(outerStep.getStatus())
                || !"ACCEPTED".equals(outerStep.getProviderAcceptanceState())
                || !"ACCEPTED".equals(approval.getStatus())
                || !com.aid.runtime.assistant.AssistantApprovalAcceptance.acceptedWithinQuoteWindow(approval, new Date())
                || !Objects.equals(approval.getId(), envelope.getApprovalId())
                || !Objects.equals(approval.getBillingAuthorizationId(), context.getAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(), context.getAuthorizationRevision())
                || !Objects.equals(envelope.getAssistantRuntimeRunFencingToken(), context.getRuntimeRunFencingToken())
                || !Objects.equals(envelope.getAssistantParentStepFencingToken(), context.getRuntimeStepFencingToken())
                || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(run.getUserId(), envelope.getPayerUserId())
                || !Objects.equals(context.getRuntimeStepId(), step.getId())
                || !Objects.equals(context.getStableStepKey(), AssistantSkillBudgetIdentity.stableStepKey(envelope, step))
                || !Objects.equals(context.getAttemptNo(), step.getWorkflowAttempt())
                || !Objects.equals(context.getLogicalCallKey(), task.getIdempotencyKey())) {
            throw new ServiceException("Skill task intent identity has changed");
        }
    }

    private void validateOuterProviderIdentity(AidRuntimeRun run, AidRuntimeApproval approval,
                                               AidRuntimeRunStep step,
                                               AssistantSkillBudgetEnvelope envelope,
                                               AidMediaTask task) {
        if (!Objects.equals(run.getId(), envelope.getAssistantRuntimeRunId())
                || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(approval.getId(), envelope.getApprovalId())
                || !Objects.equals(step.getId(), envelope.getAssistantParentStepId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(step.getStableStepId(), envelope.getAssistantOuterStableStepKey())
                || !Objects.equals(step.getAttemptNo(), envelope.getAssistantAttemptNo())
                || !"ACCEPTED".equals(approval.getStatus())) {
            throw new ServiceException("Skill outer submission identity has changed");
        }
    }

    private void validateIntent(AidMediaTask task, BillingBudgetContext context) {
        if (task == null || task.getId() == null || task.getBizTaskId() == null
                || !supportedBizTaskType().equals(task.getBizTaskType())
                || context == null || !context.isRunAuthorizationMode()
                || context.getRuntimeRunId() == null || context.getRuntimeStepId() == null
                || context.getRuntimeRunFencingToken() == null
                || context.getRuntimeStepFencingToken() == null
                || context.getAuthorizationId() == null || context.getAuthorizationRevision() == null
                || context.getReservationId() == null || context.getReservationRevision() == null
                || context.getLogicalCallKey() == null || task.getIdempotencyKey() == null) {
            throw new ServiceException("Skill task intent is invalid");
        }
    }

    private AidSkillRun lockRun(Long runId) {
        AidSkillRun run = runMapper.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).eq(AidSkillRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (run == null) throw new ServiceException("Skill run is unavailable");
        return run;
    }

    private AidSkillRunStep lockStep(Long runId, Long stepId) {
        AidSkillRunStep step = stepMapper.selectOne(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getId, stepId).eq(AidSkillRunStep::getRunId, runId)
                .eq(AidSkillRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (step == null) throw new ServiceException("Skill step is unavailable");
        return step;
    }

    private AidSkillRunTaskLink lockLink(Long stepId, Long mediaTaskId) {
        AidSkillRunTaskLink link = taskLinkMapper.selectOne(Wrappers.<AidSkillRunTaskLink>lambdaQuery()
                .eq(AidSkillRunTaskLink::getStepId, stepId)
                .eq(AidSkillRunTaskLink::getMediaTaskId, mediaTaskId)
                .eq(AidSkillRunTaskLink::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (link == null) throw new ServiceException("Skill task binding is unavailable");
        return link;
    }

    private AssistantSkillBudgetEnvelope envelope(AidSkillRun run) {
        try {
            JSONObject input = JSON.parseObject(run.getInputJson());
            AssistantSkillBudgetEnvelope result = input == null ? null
                    : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
            AssistantSkillBudgetIdentity.validate(result);
            return result;
        } catch (RuntimeException error) {
            throw new ServiceException("Skill budget identity is invalid");
        }
    }

    private AssistantBudgetOperationScope providerScope(AssistantSkillBudgetEnvelope envelope,
                                                        AidMediaTask task, AidSkillRun run,
                                                        BillingSnapshot snapshot) {
        if (snapshot == null || !"TEXT".equalsIgnoreCase(snapshot.getModelType())
                || task == null || !"TEXT".equals(task.getMediaType())
                || run == null || StrUtil.isBlank(task.getModelName())
                || !Objects.equals(task.getModelName(), run.getModelCode())
                || snapshot.getAuthorizedChargeCap() == null) {
            throw new ServiceException("Skill Provider billing scope is invalid");
        }
        // BillingSnapshot.modelName is a display label; task.modelName is the frozen routing code.
        // Bind that code to the pinned Skill run before rechecking the actual authorized charge.
        return AssistantSkillBudgetIdentity.providerScope(envelope, task.getModelName(),
                snapshot.getAuthorizedChargeCap());
    }

    private void validateIdentity(AidMediaTask task, BillingSnapshot snapshot, AidSkillRun run,
                                  AidSkillRunStep step, AidSkillRunTaskLink link,
                                  AssistantSkillBudgetEnvelope envelope) {
        if (!Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(run.getUserId(), envelope.getPayerUserId())
                || !Objects.equals(run.getSkillVersionId(), envelope.getSkillVersionId())
                || !Objects.equals(run.getSkillConfigHash(), envelope.getPackageDigest())
                || !Objects.equals(snapshot.getRuntimeRunId(), envelope.getAssistantRuntimeRunId())
                || !Objects.equals(snapshot.getRuntimeRunFencingToken(),
                envelope.getAssistantRuntimeRunFencingToken())
                || !Objects.equals(snapshot.getRuntimeStepFencingToken(),
                envelope.getAssistantParentStepFencingToken())
                || !Objects.equals(snapshot.getBillingAuthorizationId(), envelope.getBillingAuthorizationId())
                || !Objects.equals(snapshot.getBillingAuthorizationRevision(), envelope.getBillingAuthorizationRevision())
                || !Objects.equals(snapshot.getRuntimeStepId(), step.getId())
                || !Objects.equals(snapshot.getStableStepKey(), AssistantSkillBudgetIdentity.stableStepKey(envelope, step))
                || !Objects.equals(snapshot.getBillingAttemptNo(), step.getWorkflowAttempt())
                || !Objects.equals(snapshot.getLogicalCallKey(), link.getLogicalCallKey())
                || !Objects.equals(task.getIdempotencyKey(), link.getLogicalCallKey())
                || !Objects.equals(link.getRunId(), run.getId())
                || !Objects.equals(link.getStepExecutionId(), step.getStepExecutionId())
                || !Objects.equals(link.getWorkflowAttempt(), step.getWorkflowAttempt())) {
            throw new ServiceException("Skill submission identity has changed");
        }
    }

    private void validateSnapshot(AidMediaTask task, BillingSnapshot snapshot) {
        if (task == null || task.getId() == null || task.getBizTaskId() == null
                || !supportedBizTaskType().equals(task.getBizTaskType()) || snapshot == null
                || !"RUN_AUTHORIZATION".equals(snapshot.getBudgetMode())
                || snapshot.getRuntimeRunId() == null || snapshot.getRuntimeStepId() == null
                || snapshot.getRuntimeRunFencingToken() == null
                || snapshot.getRuntimeStepFencingToken() == null
                || snapshot.getBillingAuthorizationId() == null
                || snapshot.getBillingAuthorizationRevision() == null
                || snapshot.getBillingReservationId() == null
                || snapshot.getBillingReservationRevision() == null) {
            throw new ServiceException("Skill budget snapshot is invalid");
        }
    }
}
