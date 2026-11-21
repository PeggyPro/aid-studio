package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.domain.AidAssistantMessage;
import com.aid.assistant.domain.AidAssistantMessagePart;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactReview;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactReviewMapper;
import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.mapper.AidAssistantMessageMapper;
import com.aid.assistant.mapper.AidAssistantMessagePartMapper;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.realtime.AssistantRealtimeEventPublisher;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.assistant.service.AssistantModelReferenceAdapter;
import com.aid.assistant.budget.AssistantBudgetAdmissionRejectedException;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.utils.DateUtils;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.admission.MediaTaskCancellationResult;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaTextStreamSink;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.provider.TextToolResultSupport;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionCommand;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionService;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionService;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionReceiptService;
import com.aid.runtime.assistant.execution.AssistantPlanDecisionRejectedException;
import com.aid.runtime.assistant.execution.AssistantPackageReadReceiptService;
import com.aid.runtime.assistant.recovery.AssistantRuntimeLeaseService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Durable coordinator for one paid main-Agent iteration and its local recovery. */
@Slf4j
@Service
public class AssistantLoopCoordinatorImpl implements AssistantLoopCoordinator
{
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final long RETRY_MILLIS = 30_000L;
    private static final Set<String> REPLANNING_REFERENCE_REJECTIONS = Set.of(
            "ASSISTANT_CONTEXT_STALE",
            "ASSISTANT_SCOPE_DENIED",
            "ASSISTANT_REFERENCE_REQUEST_CHANGED",
            "ASSISTANT_REFERENCE_RESOURCE_CHANGED",
            "ASSISTANT_REFERENCE_RESOURCE_UNAVAILABLE");

    @Resource private PlatformTransactionManager transactionManager;
    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidAssistantMessageMapper messages;
    @Resource private AidAssistantMessagePartMapper messageParts;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimePlanMapper plans;
    @Resource private AidRuntimeEventMapper events;
    @Resource private AssistantRealtimeEventPublisher realtimeEventPublisher;
    @Resource private AidRuntimeArtifactMapper artifacts;
    @Resource private AidRuntimeArtifactReviewMapper artifactReviews;
    @Resource private com.aid.runtime.assistant.execution.AssistantTextArchiveCompletionVerifier textArchiveCompletion;
    @Resource private AidMediaTaskMapper mediaTasks;
    @Resource private AssistantBillingBudgetService budgets;
    @Resource private IMediaGenerationService mediaGeneration;
    @Resource private TaskCompletionService taskCompletionService;
    @Resource private AssistantModuleAgentResolver moduleAgents;
    @Resource private AssistantLoopExecutorRegistry executorRegistry;
    @Resource private AssistantCoreService assistantCore;
    @Resource private AssistantBudgetPolicyService budgetPolicies;
    @Resource private AssistantImageSkillInvocationResolver imageSkills;
    @Resource private AssistantModelReferenceAdapter modelReferences;
    @Resource private AssistantPlanExecutionService planExecution;
    @Resource private AssistantDecisionCorrectionReceiptService decisionCorrections;
    @Resource private AssistantRuntimeLeaseService leaseService;
    @Resource private AssistantPackageReadReceiptService packageReadReceipts;
    @Resource private AidRuntimeAgentToolRefMapper agentToolRefs;
    @Resource private RuntimeToolRegistry toolRegistry;
    @Resource private com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionService targetWriteCompletion;
    @Resource private com.aid.runtime.assistant.execution.AssistantMethodResultCompletionService methodResultCompletion;
    @Resource private com.aid.runtime.assistant.replan.AssistantReplanRecoveryService replanRecovery;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<com.aid.runtime.assistant.execution.AssistantTechnicalArchiveVerifier> archiveExtensions = List.of();

    @Override
    public AssistantLoopAdvanceResult advance(Long runId, Long expectedStateRevision, RuntimeLease lease)
    {
        RuntimeLease stepLease = requireMainStepLease(runId, lease);
        SubmissionStage stage;
        try
        {
            stage = tx().execute(status -> prepareSubmission(
                    runId, expectedStateRevision, lease, stepLease));
        }
        catch (AssistantBudgetAdmissionRejectedException rejected)
        {
            SubmissionStage rejectedStage;
            try
            {
                rejectedStage = tx().execute(status -> budgetRejectedSubmissionStage(
                        runId, expectedStateRevision, lease, stepLease));
            }
            catch (RuntimeException uncertainEvidence)
            {
                rejected.addSuppressed(uncertainEvidence);
                throw rejected;
            }
            handleSubmissionNotEntered(rejectedStage, lease, new MediaSubmissionNotEnteredException(
                    rejected.getRuntimeErrorCode(), rejected));
            return advanceResult(runId, "BUDGET_REJECTED");
        }
        if (stage == null) throw new ServiceException("Assistant submission stage is unavailable");
        if (stage.contextRejected())
        {
            AssistantLoopAdvanceResult blocked = new AssistantLoopAdvanceResult();
            blocked.setRunId(stage.runId());
            blocked.setStateRevision(stage.runRevision());
            blocked.setStatus("PAUSED");
            blocked.setAction("CONTEXT_BLOCKED");
            blocked.setNextAction("REPLANNING_REQUIRED");
            return blocked;
        }
        if (Boolean.TRUE.equals(stage.request().getStream()))
        {
            try
            {
                return submitMainStream(stage, lease);
            }
            catch (MediaSubmissionNotEnteredException notEntered)
            {
                handleSubmissionNotEntered(stage, lease, notEntered);
                throw notEntered;
            }
            catch (RuntimeException unknown)
            {
                markSubmissionUnknown(stage, lease, unknown);
                throw unknown;
            }
        }
        MediaTaskResponse task;
        try
        {
            task = mediaGeneration.submitText(stage.request());
        }
        catch (MediaSubmissionNotEnteredException notEntered)
        {
            handleSubmissionNotEntered(stage, lease, notEntered);
            throw notEntered;
        }
        catch (RuntimeException unknown)
        {
            markSubmissionUnknown(stage, lease, unknown);
            throw unknown;
        }
        if (task == null || task.getTaskId() == null)
        {
            ServiceException unknown = new ServiceException("Assistant media task identity is unavailable");
            markSubmissionUnknown(stage, lease, unknown);
            throw unknown;
        }
        return tx().execute(status -> linkSubmittedTask(stage, task, lease));
    }

    private AssistantLoopAdvanceResult submitMainStream(SubmissionStage stage, RuntimeLease lease)
    {
        AtomicReference<AssistantLoopAdvanceResult> linked = new AtomicReference<>();
        AtomicReference<RuntimeException> attachFailure = new AtomicReference<>();
        AtomicReference<String> preTaskFailure = new AtomicReference<>();
        mediaGeneration.generateTextStream(stage.request(), new MediaTextStreamSink()
        {
            @Override
            public void onTaskPrepared(long taskId)
            {
                try
                {
                    AidMediaTask task = mediaTasks.selectById(taskId);
                    MediaTaskResponse response = MediaTaskResponse.builder()
                            .taskId(taskId)
                            .mediaType(task == null ? "TEXT" : task.getMediaType())
                            .status(task == null ? "PENDING" : task.getStatus())
                            .build();
                    linked.set(tx().execute(status -> linkSubmittedTask(stage, response, lease)));
                }
                catch (RuntimeException error)
                {
                    attachFailure.set(error);
                    throw error;
                }
            }

            @Override
            public void onDetached(long taskId)
            {
                // The committed task owns realtime delivery when the queue executes it.
            }

            @Override
            public void onDelta(String content)
            {
                // The main-Agent body is a structured decision and must never be exposed as reply text.
            }

            @Override
            public void onPublicReasoningSummaryDelta(String content)
            {
                // Media-worker events deliver only public reasoning, including queued attempts.
            }

            @Override
            public boolean supportsToolMessages()
            {
                return StrUtil.isNotBlank(stage.request().getAssistantNativeToolBindingsJson());
            }

            @Override
            public void onToolMessage(MediaTextGenerateRequest.TextMessageItem message)
            {
                // The media task persists the complete call receipt before this coordinator reconciles it.
            }

            @Override
            public void onDone(String fullText, String truncatedRawSnapshot)
            {
                // Committed MediaTaskCompletedEvent closes the task-owned stream.
            }

            @Override
            public void onFailed(String userMessage)
            {
                if (linked.get() == null && attachFailure.get() == null)
                {
                    preTaskFailure.compareAndSet(null, userMessage);
                }
            }
        });
        if (attachFailure.get() != null)
        {
            throw attachFailure.get();
        }
        if (linked.get() == null)
        {
            if (preTaskFailure.get() != null)
            {
                throw new MediaSubmissionNotEnteredException(
                        "MEDIA_TEXT_STREAM_PRETASK_CALLBACK_FAILURE",
                        new ServiceException("Assistant streaming submission was rejected"),
                        stage.request().getBillingBudgetContext());
            }
            throw new ServiceException("Assistant streaming media task identity is unavailable");
        }
        return linked.get();
    }

    private SubmissionStage budgetRejectedSubmissionStage(Long runId, Long expectedStateRevision,
                                                           RuntimeLease lease, RuntimeLease stepLease)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        Date now = new Date();
        requireLease(run, lease, now);
        if (!Objects.equals(expectedStateRevision, value(run.getStateRevision()))
                || !"READY".equals(run.getStatus()) || !"ADVANCE_AGENT".equals(run.getNextAction())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
            throw new ServiceException("Assistant budget rejection state changed");
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, runId).eq(AidRuntimeApproval::getStatus, "ACCEPTED")
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByDesc(AidRuntimeApproval::getId)
                .last("LIMIT 1 FOR UPDATE"));
        if (approval == null || !Objects.equals(approval.getBillingAuthorizationId(), run.getBudgetAuthorizationId()))
            throw new ServiceException("Assistant budget rejection approval changed");
        AidRuntimeRunStep step = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getApprovalId, approval.getId())
                .eq(AidRuntimeRunStep::getStatus, "READY").eq(AidRuntimeRunStep::getDelFlag, "0")
                .last("FOR UPDATE"));
        if (step == null || step.getReservationId() == null || step.getMediaTaskId() != null
                || authoritativeTask(run, step) != null
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || !Objects.equals(step.getLeaseOwner(), stepLease.getOwner())
                || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken())
                || step.getLeaseUntil() == null || !step.getLeaseUntil().after(now)
                || stepLease.getLeaseUntil() == null || !stepLease.getLeaseUntil().after(now))
            throw new ServiceException("Assistant budget rejection evidence changed");
        return new SubmissionStage(session.getId(), runId, step.getId(), run.getStateRevision(),
                run.getFencingToken(), stepLease.getFencingToken(), null, false);
    }

    private AssistantLoopAdvanceResult advanceResult(Long runId, String action)
    {
        AidRuntimeRun run = requireRun(runId, false);
        AssistantLoopAdvanceResult result = new AssistantLoopAdvanceResult();
        result.setRunId(run.getId());
        result.setStateRevision(value(run.getStateRevision()));
        result.setStatus(run.getStatus());
        result.setAction(action);
        result.setMediaTaskId(run.getMediaTaskId());
        result.setNextAction(run.getNextAction());
        return result;
    }

    @Override
    public AssistantLoopReconcileResult reconcile(Long runId, RuntimeLease lease)
    {
        AidRuntimeRun snapshot = runs.selectById(runId);
        if (snapshot == null || !"0".equals(snapshot.getDelFlag()))
            throw new ServiceException("Assistant run does not exist");
        requireLease(snapshot, lease, new Date());
        if (Arrays.asList("CANCEL_FOR_REPLAN","WAIT_REPLAN_RECONCILIATION").contains(snapshot.getNextAction()))
            return replanRecovery.reconcile(runId,lease);
        if ("READY".equals(snapshot.getStatus()) && "ADVANCE_AGENT".equals(snapshot.getNextAction()))
        {
            AssistantLoopAdvanceResult advanced = advance(runId, snapshot.getStateRevision(), lease);
            AidRuntimeRun current = requireRun(runId, false);
            return reconcileView(advanced.getRunId(), advanced.getStateRevision(), advanced.getStatus(), true,
                    current.getNextRetryAt());
        }

        if (Set.of("EXECUTE_PLAN_OPERATION", "WAIT_OPERATION_TASK", "RECONCILE_OPERATION")
                .contains(snapshot.getNextAction()))
        {
            AidRuntimeRunStep decisionStep = latestMainAgentStep(runId);
            if (decisionStep == null || StrUtil.isBlank(decisionStep.getOutputJson()))
                throw new ServiceException("Assistant operation decision is unavailable");
            try
            {
                if (NativeAssistantToolBatch.isBatch(decisionStep.getOutputJson()))
                    applyNativeToolBatch(runId, decisionStep.getId(), decisionStep.getOutputJson(), lease);
                else
                {
                    planExecution.applyDecision(new AssistantPlanExecutionCommand(runId, decisionStep.getId(),
                            decisionStep.getOutputJson(), lease));
                }
            }
            catch (AssistantPlanDecisionRejectedException rejected)
            {
                return failRejectedPlanDecision(runId, decisionStep.getId(), null, lease,
                        rejected, null);
            }
            catch (com.aid.assistant.error.AssistantServiceException rejected)
            {
                return pauseStaleDecision(runId, decisionStep.getId(), lease, rejected);
            }
            AidRuntimeRun current = requireRun(runId, false);
            return reconcileView(runId, value(current.getStateRevision()), current.getStatus(), true,
                    current.getNextRetryAt());
        }

        AidRuntimeRunStep step = latestMainAgentStep(runId);
        if (step == null)
            throw new ServiceException("Assistant main-Agent step is unavailable");
        Long mainStepId = step.getId();
        if (shouldReconcileSubmissionNotEntered(snapshot, step))
        {
            AssistantLoopReconcileResult recovered = reconcileSubmissionNotEntered(
                    runId, mainStepId, lease);
            if (recovered != null) return recovered;
            snapshot = requireRun(runId, false);
            step = requireStep(mainStepId, runId, false);
        }
        if (shouldCancelUnsubmittedMain(snapshot, step))
            return cancelUnsubmittedMain(runId, mainStepId, lease);
        AidMediaTask task = authoritativeTask(snapshot, step);
        if (task == null)
        {
            return tx().execute(status -> markMissingTaskUnknown(runId, mainStepId, lease));
        }
        if (shouldCancelDurableUnsubmittedMain(snapshot, step))
        {
            AssistantLoopReconcileResult cancelled = cancelDurableUnsubmittedMain(
                    runId, mainStepId, task.getId(), lease);
            if (cancelled != null) return cancelled;
            snapshot = requireRun(runId, false);
            step = requireStep(mainStepId, runId, false);
            task = authoritativeTask(snapshot, step);
            if (task == null)
                return tx().execute(status -> markMissingTaskUnknown(runId, mainStepId, lease));
        }
        AidRuntimeRun receiptRun = snapshot;
        AidRuntimeRunStep receiptStep = step;
        // A successful persisted task proves provider acceptance before its decision is parsed.
        // Checkpoint/storage failures remain recoverable; they are not invalid model output.
        AidMediaTask receiptTask = "SUCCEEDED".equals(task.getStatus())
                ? checkpointSuccessfulMainReceipt(runId, mainStepId, task.getId(), lease) : task;
        if (!isTerminal(receiptTask.getStatus()))
        {
            Date retryAt = plusMillis(new Date(), RETRY_MILLIS);
            tx().executeWithoutResult(status -> scheduleRetry(runId, lease, retryAt));
            return reconcileView(runId, receiptRun.getStateRevision(), receiptRun.getStatus(), false, retryAt);
        }
        if (!"SUCCEEDED".equals(receiptTask.getStatus()))
        {
            return tx().execute(status -> failTerminalTask(runId, mainStepId, receiptTask, lease));
        }

        AssistantLoopDecision parsedDecision = null;
        try
        {
            AidAssistantSession session = requireSession(receiptRun.getSessionId(), false);
            AssistantLoopBinding binding = moduleAgents.requirePinned(session.getModuleCode(),
                    receiptRun.getVersionId(), receiptRun.getVersionDigest());
            AssistantLoopModelResult modelResult = new AssistantLoopModelResult();
            modelResult.setMediaTaskId(receiptTask.getId());
            modelResult.setStatus(receiptTask.getStatus());
            modelResult.setResultJson(receiptTask.getResultText());
            modelResult.setResultDigest(AssistantLoopModelResult.persistedTextDigest(
                    receiptTask.getResultText()));
            modelResult.setRunId(receiptRun.getId());
            AidRuntimeApproval modelApproval = approvals.selectById(receiptStep.getApprovalId());
            JSONObject modelScope = approvalScope(modelApproval);
            modelResult.setObservedRunRevision(modelScope.getLong("acceptedRunRevision"));
            modelResult.setObservedPlanRevision(value(receiptRun.getPlanRevision()));
            MediaTextGenerateRequest approvedRequest = AssistantTrustedTextRequestSnapshot.restore(
                    modelScope.getJSONObject("normalizedRequest"), modelApproval.getRequestDigest());
            modelResult.setNativeToolBindingsJson(approvedRequest.getAssistantNativeToolBindingsJson());
            modelResult.setToolMessage(StrUtil.isNotBlank(approvedRequest.getAssistantNativeToolBindingsJson())
                    ? TextToolResultSupport.readNative(receiptTask)
                    : TextToolResultSupport.read(receiptTask));
            AssistantLoopExecutor executor = executorRegistry.require(binding.getProtocolVersion());
            parsedDecision = executor.parseDecision(modelResult, binding);
            validateDecisionObservation(receiptRun, receiptStep, parsedDecision);
        }
        catch (NativeDecisionValidationRejectedException rejected)
        {
            // Keep the rejected receipt; bounded recovery requests a fresh decision through
            // the normal balance/budget path before any tool action can be executed.
            return tx().execute(status -> failInvalidModelOutput(runId, mainStepId, receiptTask,
                    lease, rejected));
        }
        catch (RuntimeException invalidOutput)
        {
            return tx().execute(status -> failInvalidModelOutput(runId, mainStepId, receiptTask,
                    lease, invalidOutput));
        }
        AssistantLoopDecision decision = parsedDecision;
        boolean apply = persistDecisionReady(runId, mainStepId, receiptTask.getId(), decision, lease);
        if (apply)
        {
            try { applyDecision(runId, mainStepId, decision, lease); }
            catch (AssistantCompletionDecisionRejectedException rejected)
            {
                // The paid task already succeeded. Settle its rejected decision after rollback;
                // replaying this immutable payload cannot repair its completion evidence.
                return tx().execute(status -> failRejectedDecision(runId, mainStepId, receiptTask,
                        lease, "COMPLETION_DECISION_REJECTED", rejected, decision));
            }
            catch (AssistantPlanDecisionRejectedException rejected)
            {
                // Replaying the same paid decision cannot supply its missing dependency.
                // This typed rejection proves no operation/reservation/provider was created.
                return failRejectedPlanDecision(runId, mainStepId, receiptTask, lease,
                        rejected, decision);
            }
            catch (com.aid.assistant.error.AssistantServiceException rejected)
            {
                return pauseStaleDecision(runId, mainStepId, lease, rejected);
            }
        }
        AidRuntimeRun finished = runs.selectById(runId);
        return reconcileView(runId, value(finished.getStateRevision()), finished.getStatus(), true,
                finished.getNextRetryAt());
    }

    private AssistantLoopDecision persistedOperationDecision(AidRuntimeRun run,
                                                              AidRuntimeRunStep step,
                                                              AidMediaTask task,
                                                              AidRuntimeApproval approval)
    {
        requireTaskIdentity(run, step, task);
        if (!"SUCCEEDED".equals(task.getStatus()) || !"DECISION_READY".equals(step.getStatus())
                || !"ACCEPTED".equals(step.getProviderAcceptanceState()))
            throw new ServiceException("Assistant decision receipt is not terminal");
        JSONObject envelope;
        try { envelope = JSON.parseObject(step.getOutputJson()); }
        catch (RuntimeException invalid) { throw new ServiceException("Assistant decision receipt is invalid"); }
        JSONObject scope = approvalScope(approval);
        String protocol = envelope == null ? null : envelope.getString("protocolVersion");
        String decisionId = envelope == null ? null : envelope.getString("decisionId");
        String action = envelope == null ? null : envelope.getString("action");
        Long observedRun = envelope == null ? null : envelope.getLong("observedRunRevision");
        Long observedPlan = envelope == null ? null : envelope.getLong("observedPlanRevision");
        if (StrUtil.isBlank(protocol) || !executorRegistry.isAvailable(protocol)
                || StrUtil.isBlank(decisionId) || decisionId.length() > 128
                || !decisionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !List.of("TOOL", "SKILL").contains(action)
                || !Objects.equals(observedRun, scope.getLong("acceptedRunRevision"))
                || !Objects.equals(observedPlan, value(run.getPlanRevision()))
                || envelope.getJSONObject(action.toLowerCase()) == null)
            throw new ServiceException("Assistant decision receipt is invalid");
        AssistantLoopDecision decision = new AssistantLoopDecision();
        decision.setProtocolVersion(protocol);
        decision.setDecisionId(decisionId);
        decision.setObservedRunRevision(observedRun);
        decision.setObservedPlanRevision(observedPlan);
        decision.setAction(action);
        decision.setDecisionJson(step.getOutputJson());
        return decision;
    }

    private AidRuntimeApproval requirePersistedIterationApproval(AidRuntimeRun run,
                                                                  AidRuntimeRunStep step)
    {
        if (step == null || !Objects.equals(step.getRunId(), run.getId())
                || !"MAIN_AGENT".equals(step.getStepKind())
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                || !"DECISION_READY".equals(step.getStatus())
                || !"ACCEPTED".equals(step.getProviderAcceptanceState())
                || !Objects.equals(step.getPlanRevision(), value(run.getPlanRevision())))
            throw new ServiceException("Assistant iteration approval identity changed");
        return requireIterationApprovalIdentity(run, step);
    }

    /** Immutable approval identity shared by receipt acceptance and parsed-decision replay. */
    private AidRuntimeApproval requireIterationApprovalIdentity(AidRuntimeRun run,
                                                                AidRuntimeRunStep step)
    {
        AidRuntimeApproval approval = step.getApprovalId() == null ? null : approvals.selectOne(
                new LambdaQueryWrapper<AidRuntimeApproval>()
                        .eq(AidRuntimeApproval::getId, step.getApprovalId())
                        .eq(AidRuntimeApproval::getRunId, run.getId())
                        .eq(AidRuntimeApproval::getUserId, run.getUserId())
                        .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        if (approval == null || !"ASSISTANT_ITERATION".equals(approval.getScopeKind())
                || !"ACCEPTED".equals(approval.getStatus())
                || !Objects.equals(approval.getPlanRevision(), value(run.getPlanRevision()))
                || !Objects.equals(approval.getContextDigest(), run.getContextDigest())
                || !Objects.equals(approval.getBillingAuthorizationId(), run.getBudgetAuthorizationId())
                || step.getPlanPosition() == null || step.getPlanPosition() < 1
                || !Objects.equals(approval.getPlanningAttemptNo(), step.getPlanPosition())
                || !Objects.equals(approval.getRequestDigest(), step.getInputDigest())
                || approval.getAcceptedAt() == null || approval.getExpiresAt() == null
                || !approval.getAcceptedAt().before(approval.getExpiresAt()))
            throw new ServiceException("Assistant iteration approval identity changed");
        JSONObject scope = approvalScope(approval);
        Long acceptedRevision = scope.getLong("acceptedRunRevision");
        Long acceptedMessageSeq = scope.getLong("acceptedMessageSeq");
        String packageDigest = scope.getString("packageDigest");
        String expectedScopeDigest = SecureUtil.sha256(approval.getScopeJson() + "\n" + packageDigest
                + "\n" + value(run.getPlanRevision()) + "\n" + acceptedRevision);
        if (acceptedRevision == null || acceptedRevision < 0 || acceptedRevision == Long.MAX_VALUE
                || acceptedMessageSeq == null || acceptedMessageSeq < 0
                || !Objects.equals(scope.getLong("agentVersionId"), run.getVersionId())
                || !Objects.equals(packageDigest, run.getVersionDigest())
                || !Objects.equals(expectedScopeDigest, approval.getScopeDigest()))
            throw new ServiceException("Assistant iteration approval identity changed");
        MediaTextGenerateRequest request;
        try
        {
            request = AssistantTrustedTextRequestSnapshot.restore(
                    scope.getJSONObject("normalizedRequest"), approval.getRequestDigest());
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("Assistant iteration approval identity changed");
        }
        if (request == null || !Objects.equals(request.getBizTaskId(), step.getId())
                || !"ASSISTANT_RUNTIME_STEP".equals(request.getBizTaskType())
                || !Objects.equals(request.getUserId(), run.getUserId())
                || !Objects.equals(request.getCallId(), step.getIdempotencyKey())
                || !Objects.equals(request.getCallIdentity(),
                "assistant-loop:" + run.getId() + ":" + step.getStableStepId())
                || StrUtil.isBlank(request.getInvocationIdentity()) || !request.isStrictModelSelection())
            throw new ServiceException("Assistant iteration approval identity changed");
        return approval;
    }

    private RuntimeLease requireMainStepLease(Long runId, RuntimeLease runLease)
    {
        Date now = new Date();
        AidRuntimeRun run = requireRun(runId, false);
        requireLease(run, runLease, now);
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, runId).eq(AidRuntimeApproval::getStatus, "ACCEPTED")
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByDesc(AidRuntimeApproval::getId)
                .last("LIMIT 1"));
        AidRuntimeRunStep step = approval == null ? null : steps.selectOne(
                new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, runId)
                        .eq(AidRuntimeRunStep::getApprovalId, approval.getId())
                        .eq(AidRuntimeRunStep::getStatus, "READY")
                        .eq(AidRuntimeRunStep::getDelFlag, "0").last("LIMIT 1"));
        if (step == null) throw new ServiceException("Assistant reserved step is unavailable");
        if (Objects.equals(step.getLeaseOwner(), runLease.getOwner())
                && step.getFencingToken() != null && step.getFencingToken() > 0
                && step.getLeaseUntil() != null && step.getLeaseUntil().after(now))
        {
            RuntimeLease current = new RuntimeLease();
            current.setOwner(step.getLeaseOwner());
            current.setFencingToken(step.getFencingToken());
            current.setLeaseUntil(step.getLeaseUntil());
            return current;
        }
        Optional<RuntimeLease> claimed = leaseService.tryClaimStep(runId, step.getId(), runLease,
                now, runLease.getLeaseUntil());
        return claimed.orElseThrow(() -> new ServiceException("Assistant main-Agent step lease is unavailable"));
    }

    private SubmissionStage prepareSubmission(Long runId, Long expectedStateRevision,
                                              RuntimeLease lease, RuntimeLease stepLease)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        Date now = new Date();
        requireLease(run, lease, now);
        if (!Objects.equals(expectedStateRevision, value(run.getStateRevision()))
                || !"READY".equals(run.getStatus()) || !"ADVANCE_AGENT".equals(run.getNextAction())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
            throw new ServiceException("Assistant run is not ready to advance");

        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, runId).eq(AidRuntimeApproval::getStatus, "ACCEPTED")
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByDesc(AidRuntimeApproval::getId)
                .last("LIMIT 1 FOR UPDATE"));
        // This row is already ACCEPTED. Quote expiry bounds acceptance, not the later
        // execution time; independent run-budget authorization still gates submission below.
        if (approval == null || approval.getExpiresAt() == null || approval.getAcceptedAt() == null
                || !approval.getAcceptedAt().before(approval.getExpiresAt())
                || approval.getAcceptedAt().after(now)
                || !Objects.equals(approval.getBillingAuthorizationId(), run.getBudgetAuthorizationId()))
            throw new ServiceException("Assistant approval is unavailable or expired");
        AidRuntimeRunStep step = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getApprovalId, approval.getId())
                .eq(AidRuntimeRunStep::getStatus, "READY").eq(AidRuntimeRunStep::getDelFlag, "0")
                .last("FOR UPDATE"));
        if (step == null || step.getReservationId() == null)
            throw new ServiceException("Assistant reserved step is unavailable");
        if (!Objects.equals(step.getLeaseOwner(), stepLease.getOwner())
                || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken())
                || step.getLeaseUntil() == null || !step.getLeaseUntil().after(now)
                || stepLease.getLeaseUntil() == null || !stepLease.getLeaseUntil().after(now))
            throw new ServiceException("Assistant main-Agent step lease changed");
        BillingBudgetViews.ReservationView reservation = budgets.listReservations(run.getBudgetAuthorizationId())
                .stream().filter(item -> Objects.equals(item.getId(), step.getReservationId())).findFirst()
                .orElseThrow(() -> new ServiceException("Assistant reservation is unavailable"));

        JSONObject scope = approvalScope(approval);
        MediaTextGenerateRequest request = AssistantTrustedTextRequestSnapshot.restore(
                scope.getJSONObject("normalizedRequest"), approval.getRequestDigest());
        if (request == null || !Objects.equals(request.getBizTaskId(), step.getId())
                || !"ASSISTANT_RUNTIME_STEP".equals(request.getBizTaskType())
                || !Objects.equals(request.getUserId(), run.getUserId())
                || !Objects.equals(request.getCallId(), step.getIdempotencyKey())
                || !Objects.equals(request.getCallIdentity(),
                "assistant-loop:" + run.getId() + ":" + step.getStableStepId())
                || StrUtil.isBlank(request.getInvocationIdentity()) || !request.isStrictModelSelection())
            throw new ServiceException("Assistant normalized request changed");

        decisionCorrections.requireBoundForSubmission(session, run, step, approval, request);
        BillingBudgetContext context = new BillingBudgetContext();
        context.setMode(BillingBudgetContext.MODE_RUN_AUTHORIZATION);
        context.setAuthorizationId(run.getBudgetAuthorizationId());
        context.setAuthorizationRevision(approval.getBillingAuthorizationRevision());
        context.setReservationId(reservation.getId());
        context.setReservationRevision(reservation.getRevision());
        context.setRuntimeRunId(run.getId());
        context.setRuntimeStepId(step.getId());
        context.setRuntimeRunFencingToken(run.getFencingToken());
        context.setRuntimeStepFencingToken(stepLease.getFencingToken());
        context.setStableStepKey(step.getStableStepId());
        context.setAttemptNo(step.getAttemptNo());
        context.setLogicalCallKey(step.getIdempotencyKey());
        context.setRequestDigest(approval.getRequestDigest());
        context.setQuoteId(approval.getQuoteId());
        context.setQuoteDigest(approval.getQuoteDigest());
        context.setQuoteExpiresAt(approval.getExpiresAt());
        request.setBillingBudgetContext(context);

        // Approval freezes the exact provider request, but Canvas access and resource identity must
        // still be current at the final submission boundary.
        try
        {
            modelReferences.requireCurrentRequest(run.getUserId(), request);
        }
        catch (com.aid.assistant.error.AssistantServiceException rejected)
        {
            if (!pausesForReferenceRejection(rejected.getErrorCode())) throw rejected;
            return pauseRejectedReferenceSubmission(session, run, step, lease, stepLease,
                    rejected.getErrorCode());
        }

        if (!AssistantAutomaticTextExecution.WALLET_APPROVAL_MODE.equals(approval.getMode()))
            budgetPolicies.recordScheduledIteration(run.getId(), run.getBudgetAuthorizationId(),
                    approval.getBillingAuthorizationRevision(), request.getModelName(), step.getOperationKind(),
                    approval.getMaxCharge(), lease.getOwner());

        step.setLeaseOwner(stepLease.getOwner());
        step.setLeaseUntil(stepLease.getLeaseUntil());
        step.setFencingToken(stepLease.getFencingToken());
        step.setUpdateBy(stepLease.getOwner());
        step.setUpdateTime(now);
        steps.updateById(step);
        run.setStatus("RUNNING");
        run.setNextAction("WAIT_AGENT_TASK");
        run.setStateRevision(value(run.getStateRevision()) + 1);
        run.setNextRetryAt(plusMillis(now, RETRY_MILLIS));
        run.setUpdateBy(lease.getOwner());
        run.setUpdateTime(now);
        runs.updateById(run);
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), step.getIdempotencyKey(),
                Map.of("status", run.getStatus(), "nextAction", run.getNextAction()), now);
        return new SubmissionStage(session.getId(), run.getId(), step.getId(), run.getStateRevision(),
                run.getFencingToken(), stepLease.getFencingToken(), request, false);
    }

    /** This boundary precedes provider entry. Retain the reservation for the existing replan
     * reconciliation; neither a changed reference nor an access rejection authorizes a new task. */
    private SubmissionStage pauseRejectedReferenceSubmission(AidAssistantSession session,
            AidRuntimeRun run, AidRuntimeRunStep step, RuntimeLease lease, RuntimeLease stepLease,
            String code)
    {
        requireActiveMutation(session, run, lease);
        if (!"READY".equals(run.getStatus()) || !"ADVANCE_AGENT".equals(run.getNextAction())
                || !"READY".equals(step.getStatus()) || step.getMediaTaskId() != null
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken())
                || !Objects.equals(step.getLeaseOwner(), stepLease.getOwner())
                || step.getLeaseUntil() == null || !step.getLeaseUntil().after(new Date())
                || authoritativeTask(run, step) != null)
            throw new ServiceException("Assistant reference rejection evidence changed");
        step.setErrorMessage(code);
        updateStep(step, lease.getOwner());
        run.setStatus("PAUSED");
        run.setResumeStatus("WAITING_APPROVAL");
        run.setNextAction("REPLANNING_REQUIRED");
        run.setErrorMessage(code);
        run.setNextRetryAt(null);
        run.setStateRevision(value(run.getStateRevision()) + 1);
        updateRun(run, lease.getOwner());
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), step.getIdempotencyKey(),
                Map.of("status", "PAUSED", "nextAction", "REPLANNING_REQUIRED", "reason", code), new Date());
        return new SubmissionStage(session.getId(), run.getId(), step.getId(), run.getStateRevision(),
                run.getFencingToken(), stepLease.getFencingToken(), null, true);
    }

    static boolean pausesForReferenceRejection(String errorCode)
    {
        return errorCode != null && REPLANNING_REFERENCE_REJECTIONS.contains(errorCode);
    }

    private AssistantLoopAdvanceResult linkSubmittedTask(SubmissionStage stage, MediaTaskResponse response,
                                                          RuntimeLease lease)
    {
        if (response == null || response.getTaskId() == null)
            throw new ServiceException("MEDIA_TASK_RECEIPT_INVALID");
        AidRuntimeRun discovered = requireRun(stage.runId(), false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(stage.runId(), true);
        AidRuntimeRunStep step = requireStep(stage.stepId(), run.getId(), true);
        AidMediaTask task = mediaTasks.selectById(response.getTaskId());
        requireTaskIdentity(run, step, task);
        if (step.getMediaTaskId() != null && !Objects.equals(step.getMediaTaskId(), response.getTaskId()))
            throw new ServiceException("MEDIA_TASK_RECEIPT_CONFLICT");
        Date now = new Date();
        boolean newlyLinked = step.getMediaTaskId() == null;
        if (newlyLinked)
        {
            step.setMediaTaskId(response.getTaskId());
            updateStep(step, receiptActor(lease));
        }
        boolean activeWorker = isActiveMutation(session, run, lease, now);
        if (activeWorker)
        {
            run.setMediaTaskId(response.getTaskId());
            run.setNextRetryAt(plusMillis(now, RETRY_MILLIS));
            updateRun(run, lease.getOwner());
        }
        if (newlyLinked)
        {
            appendEvent(session, run, activeWorker ? "task.created" : "task.receipt_linked",
                    String.valueOf(response.getTaskId()), 0L, step.getIdempotencyKey(),
                    Map.of("taskId", String.valueOf(response.getTaskId()),
                            "status", Objects.toString(response.getStatus(), "QUEUED"),
                            "lateReceipt", !activeWorker), now);
        }
        AssistantLoopAdvanceResult result = new AssistantLoopAdvanceResult();
        result.setRunId(run.getId());
        result.setStateRevision(value(run.getStateRevision()));
        result.setStatus(run.getStatus());
        result.setAction(activeWorker ? "SUBMITTED" : "RECEIPT_LINKED");
        result.setMediaTaskId(response.getTaskId());
        result.setNextAction(run.getNextAction());
        return result;
    }

    /**
     * Provider admission records SUBMITTING before outbound entry. A durable successful task is
     * the authority to acknowledge that submission even when parsing rejects its decision.
     * No model, task, approval, reservation or wallet operation is repeated at this boundary.
     */
    private AidMediaTask checkpointSuccessfulMainReceipt(Long runId, Long stepId, Long taskId,
                                                        RuntimeLease lease)
    {
        return tx().execute(status -> {
            Locked locked = lockReceiptGraph(runId, stepId, lease);
            AidMediaTask persisted = mediaTasks.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, taskId).last("FOR UPDATE"));
            requireTaskIdentity(locked.run(), locked.step(), persisted);
            AidRuntimeRunStep source = locked.step();
            if (!Objects.equals(source.getMediaTaskId(), taskId)
                    || !"TEXT".equals(persisted.getMediaType())
                    || !"SUCCEEDED".equals(persisted.getStatus())
                    || !"SUCCESS".equals(persisted.getBillingStatus())
                    || !"MAIN_AGENT".equals(source.getStepKind())
                    || !"ASSISTANT_DECISION".equals(source.getOperationKind())
                    || !Objects.equals(source.getPlanRevision(), value(locked.run().getPlanRevision())))
                throw new ServiceException("Assistant successful receipt identity changed");
            if (List.of("DECISION_READY", "SUCCEEDED", "FAILED").contains(source.getStatus()))
                return persisted;
            if (!List.of("READY", "SUBMITTING", "INFLIGHT", "RECONCILING").contains(source.getStatus())
                    || !List.of("NOT_SUBMITTED", "SUBMITTING", "ACCEPTED", "UNKNOWN")
                    .contains(source.getProviderAcceptanceState()))
                throw new ServiceException("Assistant successful receipt state changed");
            requireIterationApprovalIdentity(locked.run(), source);
            if (!"RECONCILING".equals(source.getStatus())
                    || !"ACCEPTED".equals(source.getProviderAcceptanceState()))
            {
                source.setStatus("RECONCILING");
                source.setProviderAcceptanceState("ACCEPTED");
                source.setUpdateBy(lease.getOwner());
                source.setUpdateTime(new Date());
                if (steps.updateById(source) != 1)
                    throw new ServiceException("Assistant successful receipt checkpoint changed");
            }
            return persisted;
        });
    }

    private boolean persistDecisionReady(Long runId, Long stepId, Long taskId,
                                         AssistantLoopDecision decision, RuntimeLease lease)
    {
        Boolean result = tx().execute(status -> {
            AidRuntimeRun discovered = requireRun(runId, false);
            AidAssistantSession session = requireSession(discovered.getSessionId(), true);
            AidRuntimeRun run = requireRun(runId, true);
            requireReceiptMutation(session, run, lease);
            boolean receiptOnly = !isActiveMutation(session, run, lease, new Date());
            AidRuntimeRunStep step = requireStep(stepId, runId, true);
            if (!Objects.equals(step.getMediaTaskId(), taskId))
                throw new ServiceException("Assistant result task changed");
            if ("DECISION_READY".equals(step.getStatus()))
            {
                if (!matchesDecisionReceipt(step.getOutputJson(), decision.getDecisionJson()))
                    throw new ServiceException("Assistant decision changed");
                if (!Objects.equals(step.getOutputJson(), decision.getDecisionJson()))
                {
                    // Recover only the exact legacy null-omitting serialization of this validated receipt.
                    step.setOutputJson(decision.getDecisionJson());
                    step.setUpdateBy(lease.getOwner());
                    step.setUpdateTime(new Date());
                    steps.updateById(step);
                }
                if (receiptOnly)
                {
                    archiveLateDecision(session, run, step, decision, lease.getOwner(), new Date());
                    return false;
                }
                return true;
            }
            if ("SUCCEEDED".equals(step.getStatus()) || "FAILED".equals(step.getStatus()))
            {
                if (!matchesDecisionReceipt(step.getOutputJson(), decision.getDecisionJson()))
                    throw new ServiceException("Assistant decision receipt changed");
                return false;
            }
            if (!List.of("SUBMITTING", "INFLIGHT", "RECONCILING", "READY").contains(step.getStatus()))
                throw new ServiceException("Assistant result state changed");
            step.setStatus(receiptOnly ? "SUCCEEDED" : "DECISION_READY");
            step.setProviderAcceptanceState("ACCEPTED");
            step.setOutputJson(decision.getDecisionJson());
            step.setUpdateBy(lease.getOwner());
            step.setUpdateTime(new Date());
            steps.updateById(step);
            if (receiptOnly)
            {
                archiveLateDecision(session, run, step, decision, lease.getOwner(), new Date());
                return false;
            }
            String recoveredRequest = AssistantModelRecovery.clear(run.getRequestJson());
            if (!Objects.equals(recoveredRequest, run.getRequestJson()))
            {
                run.setRequestJson(recoveredRequest);
                updateRun(run, lease.getOwner());
            }
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    private boolean matchesDecisionReceipt(String stored, String validated)
    {
        if (Objects.equals(stored, validated)) return true;
        if (StrUtil.isBlank(stored) || StrUtil.isBlank(validated)) return false;
        try
        {
            // Older receipts used Fastjson's default writer, which omitted all null map values.
            // Compare that exact historical serialization; never infer or repair model fields.
            JSONObject original = JSON.parseObject(validated);
            return original != null && Objects.equals(stored, original.toJSONString());
        }
        catch (RuntimeException invalid) { return false; }
    }

    private void applyDecision(Long runId, Long stepId, AssistantLoopDecision decision, RuntimeLease lease)
    {
        if (persistNativeVisibleText(runId, stepId, decision, lease)) return;
        JSONObject envelope = JSON.parseObject(decision.getDecisionJson());
        JSONObject payload = envelope.getJSONObject(decision.getAction().toLowerCase());
        switch (decision.getAction())
        {
            case "ASK" -> applyAsk(runId, stepId, payload, lease);
            case "PLAN" -> tx().executeWithoutResult(status -> applyPlanLocked(runId, stepId, payload, decision, lease));
            case "CHAT" -> tx().executeWithoutResult(status -> applyMessageLocked(runId, stepId,
                    payload.getJSONObject("message").getString("content"), decision, lease, false, payload));
            case "COMPLETE" -> tx().executeWithoutResult(status -> applyMessageLocked(runId, stepId,
                    payload.getJSONObject("message").getString("content"), decision, lease, true, payload));
            case "CHECKPOINT" -> tx().executeWithoutResult(status -> applyCheckpointLocked(runId, stepId, payload, lease));
            case "FAIL" -> tx().executeWithoutResult(status -> applyFailureLocked(runId, stepId, payload, decision, lease));
            case "TOOL", "SKILL" -> applyPlannedExecution(runId, stepId, decision, lease);
            case NativeAssistantToolBatch.ACTION -> applyNativeToolBatch(
                    runId, stepId, decision.getDecisionJson(), lease);
            default -> throw new ServiceException("Assistant decision action is unsupported");
        }
    }

    private boolean persistNativeVisibleText(Long runId, Long stepId, AssistantLoopDecision decision,
                                             RuntimeLease lease)
    {
        if (decision == null || StrUtil.isBlank(decision.getVisibleText())
                || List.of("CHAT", "COMPLETE").contains(decision.getAction())) return false;
        return Boolean.TRUE.equals(tx().execute(status -> {
            Locked locked = lockRunGraph(runId, stepId, lease);
            requireDecisionReady(locked.step());
            if (supersedeForQueuedMessage(locked, lease.getOwner(), decision.getDecisionId())) return true;
            String messageKey = nativeVisibleMessageKey(decision.getDecisionId());
            AidAssistantMessage existing = messages.selectOne(new LambdaQueryWrapper<AidAssistantMessage>()
                    .eq(AidAssistantMessage::getSessionId, locked.session().getId())
                    .eq(AidAssistantMessage::getClientMessageId, messageKey)
                    .last("LIMIT 1 FOR UPDATE"));
            if (existing != null)
            {
                AidAssistantMessagePart part = messageParts.selectOne(
                        new LambdaQueryWrapper<AidAssistantMessagePart>()
                                .eq(AidAssistantMessagePart::getMessageId, existing.getId())
                                .eq(AidAssistantMessagePart::getPartSeq, 1)
                                .eq(AidAssistantMessagePart::getPartType, "TEXT")
                                .eq(AidAssistantMessagePart::getDelFlag, "0").last("LIMIT 1"));
                if (!matchesNativeVisibleMessage(existing, part, locked.run(), messageKey,
                        decision.getVisibleText()))
                    throw new ServiceException("Assistant native text receipt changed");
                return false;
            }
            Date now = new Date();
            AidAssistantMessage message = appendAssistantMessage(locked.session(), locked.run(),
                    messageKey, decision.getVisibleText(), now);
            appendEvent(locked.session(), locked.run(), "message.created", String.valueOf(message.getId()), 1L,
                    decision.getDecisionId(), Map.of("messageId", String.valueOf(message.getId()),
                            "seq", message.getSeqNo(), "role", "ASSISTANT", "nativeToolPreamble", true), now);
            return false;
        }));
    }

    /** Keep the durable idempotency key within aid_chat_message.client_message_id varchar(64). */
    static String nativeVisibleMessageKey(String decisionId)
    {
        if (StrUtil.isBlank(decisionId) || !SAFE_KEY.matcher(decisionId).matches())
            throw new ServiceException("Assistant native text identity invalid");
        String legacy = decisionId + ":visible";
        if (legacy.length() <= 64) return legacy;
        return SecureUtil.sha256("assistant-native-visible/v1\n" + decisionId);
    }

    /** An existing unique key is reusable only for the exact same run, turn and immutable text. */
    static boolean matchesNativeVisibleMessage(AidAssistantMessage message, AidAssistantMessagePart part,
                                               AidRuntimeRun run, String messageKey, String content)
    {
        return message != null && part != null && run != null && StrUtil.isNotBlank(content)
                && Objects.equals(message.getRuntimeRunId(), run.getId())
                && Objects.equals(message.getTurnId(), run.getTurnId())
                && Objects.equals(message.getClientMessageId(), messageKey)
                && Objects.equals(message.getRequestHash(), SecureUtil.sha256(content))
                && "ASSISTANT".equals(message.getRole()) && "COMPLETE".equals(message.getStatus())
                && "ASSISTANT_OUTPUT".equals(message.getMessageKind())
                && "TEXT".equals(message.getMediaType()) && "0".equals(message.getDelFlag())
                && Objects.equals(part.getMessageId(), message.getId())
                && Integer.valueOf(1).equals(part.getPartSeq()) && "TEXT".equals(part.getPartType())
                && "ASSISTANT_TEXT".equals(part.getSlotName())
                && Objects.equals(part.getTextContent(), content)
                && "CURRENT_TURN".equals(part.getContextScope()) && "0".equals(part.getDelFlag());
    }

    private void applyPlannedExecution(Long runId, Long stepId, AssistantLoopDecision decision,
                                       RuntimeLease lease)
    {
        Boolean superseded = tx().execute(status -> {
            Locked locked = lockRunGraph(runId, stepId, lease);
            requireDecisionReady(locked.step());
            return supersedeForQueuedMessage(locked, lease.getOwner(), decision.getDecisionId());
        });
        if (Boolean.TRUE.equals(superseded)) return;
        planExecution.applyDecision(new AssistantPlanExecutionCommand(runId, stepId,
                decision.getDecisionJson(), lease));
    }

    /** Executes a validated provider multi-call as an ordered sequence of durable safe reads. */
    private void applyNativeToolBatch(Long runId, Long stepId, String batchJson, RuntimeLease lease)
    {
        NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(batchJson);
        for (NativeAssistantToolBatch.Call call : batch.calls())
        {
            if (!prepareNativeToolBatchMember(runId, stepId, batchJson,
                    batch.decisionId(), call.index(), lease)) return;
            com.aid.runtime.assistant.execution.AssistantPlanExecutionResult result;
            try
            {
                result = planExecution.applyDecision(new AssistantPlanExecutionCommand(
                        runId, stepId, batchJson, call.decisionJson(),
                        call.index(), batch.calls().size(), lease));
            }
            catch (ServiceException changed)
            {
                if (nativeToolBatchAlreadySettled(
                        runId, stepId, batchJson, call.index(), lease)) return;
                throw changed;
            }
            if (result == null || !"SUCCEEDED".equals(result.status())) return;
        }
    }

    /** Rechecks the authoritative run and main step before creating the next batch member. */
    private boolean prepareNativeToolBatchMember(Long runId, Long stepId, String batchJson,
                                                 String batchDecisionId, int batchIndex,
                                                 RuntimeLease lease)
    {
        return Boolean.TRUE.equals(tx().execute(status -> {
            Locked locked = lockNativeToolBatchGraph(runId, stepId, batchJson, lease);
            if (isSettledNativeToolBatch(locked.run(), locked.step()))
            {
                return !shouldReturnFromNativeToolBatch(locked.run(), locked.step(),
                        nativeToolBatchMembersAbsent(runId, batchJson, batchIndex));
            }
            requireActiveMutation(locked.session(), locked.run(), lease);
            requireDecisionReady(locked.step());
            return !supersedeForQueuedMessage(locked, lease.getOwner(), batchDecisionId);
        }));
    }

    /** Suppresses only a race that has already reached an exact pause, stop or queued-input state. */
    private boolean nativeToolBatchAlreadySettled(Long runId, Long stepId, String batchJson,
                                                  int batchIndex, RuntimeLease lease)
    {
        return Boolean.TRUE.equals(tx().execute(status -> {
            Locked locked = lockNativeToolBatchGraph(runId, stepId, batchJson, lease);
            if (!isSettledNativeToolBatch(locked.run(), locked.step())) return false;
            return shouldReturnFromNativeToolBatch(locked.run(), locked.step(),
                    nativeToolBatchMembersAbsent(runId, batchJson, batchIndex));
        }));
    }

    private boolean nativeToolBatchMembersAbsent(Long runId, String batchJson, int fromIndex)
    {
        NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(batchJson);
        if (fromIndex < 0 || fromIndex >= batch.calls().size())
            throw new ServiceException("Assistant native tool batch cursor changed");
        for (int index = fromIndex; index < batch.calls().size(); index++)
        {
            JSONObject operation = JSON.parseObject(batch.calls().get(index).decisionJson());
            JSONObject tool = operation == null ? null : operation.getJSONObject("tool");
            Long planRevision = operation == null ? null : operation.getLong("observedPlanRevision");
            String stableStepId = tool == null ? null : tool.getString("stableStepId");
            Integer attemptNo = tool == null ? null : tool.getInteger("attemptNo");
            if (planRevision == null || StrUtil.isBlank(stableStepId) || attemptNo == null)
                throw new ServiceException("Assistant native tool batch cursor changed");
            Long count = steps.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, runId)
                    .eq(AidRuntimeRunStep::getPlanRevision, planRevision)
                    .eq(AidRuntimeRunStep::getStableStepId, stableStepId)
                    .eq(AidRuntimeRunStep::getAttemptNo, attemptNo)
                    .eq(AidRuntimeRunStep::getDelFlag, "0"));
            if (count == null || count != 0L) return false;
        }
        return true;
    }

    private Locked lockNativeToolBatchGraph(Long runId, Long stepId, String batchJson,
                                            RuntimeLease lease)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        requireLease(run, lease, new Date());
        AidRuntimeRunStep step = requireStep(stepId, runId, true);
        if (!Objects.equals(step.getOutputJson(), batchJson))
            throw new ServiceException("Assistant native tool batch changed");
        return new Locked(session, run, step);
    }

    static boolean isSettledNativeToolBatch(AidRuntimeRun run, AidRuntimeRunStep main)
    {
        if (run == null || main == null || !"SUCCEEDED".equals(main.getStatus())) return false;
        boolean stopped = Boolean.TRUE.equals(run.getStopRequested())
                && "STOPPED".equals(run.getStatus()) && run.getNextAction() == null;
        boolean paused = StrUtil.isNotBlank(run.getPauseReason())
                && "PAUSED".equals(run.getStatus())
                && "CONTINUE_AGENT_REQUIRED".equals(run.getNextAction());
        boolean superseded = "SUPERSEDED_BY_USER_MESSAGE".equals(main.getErrorMessage())
                && "WAITING_APPROVAL".equals(run.getStatus())
                && "CONTINUE_AGENT_REQUIRED".equals(run.getNextAction());
        return stopped || paused || superseded;
    }

    static boolean shouldReturnFromNativeToolBatch(AidRuntimeRun run, AidRuntimeRunStep main,
                                                   boolean remainingMembersAbsent)
    {
        if (!isSettledNativeToolBatch(run, main)) return false;
        if (!remainingMembersAbsent)
            throw new ServiceException("Assistant native tool batch cursor changed");
        return true;
    }

    private void applyAsk(Long runId, Long stepId, JSONObject payload, RuntimeLease lease)
    {
        tx().executeWithoutResult(status -> {
            Locked locked = lockRunGraph(runId, stepId, lease);
            requireDecisionReady(locked.step());
            if (supersedeForQueuedMessage(locked, lease.getOwner(), payload.getString("requestKey"))) return;
            if ("AUTO".equals(budgetPolicies.executionMode(locked.run()))) {
                AidMediaTask task = authoritativeTask(locked.run(), locked.step());
                if (!scheduleModelRecovery(locked, task, lease, "AUTO_INPUT_NOT_REQUIRED"))
                    failRejectedDecision(runId, stepId, task, lease, "AUTO_INPUT_UNRESOLVED",
                            new ServiceException("自动执行未能确定可行参数"), null);
                return;
            }
            String requestKey = payload.getString("requestKey");
            String contextDigest = payload.getString("contextDigest");
            if (StrUtil.isBlank(requestKey) || !SAFE_KEY.matcher(requestKey).matches()
                    || !Objects.equals(contextDigest, locked.run().getContextDigest()))
                throw new ServiceException("Assistant question context changed");
            AssistantRequests.InputRequestCreate request = new AssistantRequests.InputRequestCreate();
            request.setRunId(runId); request.setRequestKey(requestKey);
            request.setExpectedRunRevision(value(locked.run().getStateRevision()));
            request.setContextDigest(contextDigest);
            request.setQuestions(payload.getList("questions", AssistantRequests.Question.class));
            assistantCore.requestInput(locked.run().getUserId(), request);
            finishStep(runId, stepId, lease);
        });
    }

    @jakarta.annotation.Resource
    private AssistantRemainingPlanPolicy remainingPlanPolicy;

    private void applyPlanLocked(Long runId, Long stepId, JSONObject payload,
                                 AssistantLoopDecision decision, RuntimeLease lease)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        if (supersedeForQueuedMessage(locked, lease.getOwner(), decision.getDecisionId())) return;
        long newRevision = value(locked.run().getPlanRevision()) + 1;
        if (!Objects.equals(payload.getLong("parentRevision"), value(locked.run().getPlanRevision())))
            throw new ServiceException("Assistant plan parent changed");
        AidRuntimePlan previous = null;
        JSONObject previousDefinition = null;
        List<AidRuntimeRunStep> previousSteps = List.of();
        if (value(locked.run().getPlanRevision()) > 0)
        {
            previous = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                    .eq(AidRuntimePlan::getRunId, runId)
                    .eq(AidRuntimePlan::getRevision, locked.run().getPlanRevision())
                    .eq(AidRuntimePlan::getDelFlag, "0").last("FOR UPDATE"));
            try { previousDefinition = previous == null ? null : JSON.parseObject(previous.getDefinitionJson()); }
            catch (RuntimeException invalid) { throw new ServiceException("Assistant discovery plan invalid"); }
            boolean replaceableDiscovery = NativeAssistantDiscoveryPlanService.isDiscovery(previousDefinition);
            previousSteps = previous == null ? List.of()
                    : steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, runId)
                    .eq(AidRuntimeRunStep::getPlanRevision, previous.getRevision())
                    .ne(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                    .eq(AidRuntimeRunStep::getDelFlag, "0")
                    .orderByAsc(AidRuntimeRunStep::getAttemptNo)
                    .orderByAsc(AidRuntimeRunStep::getId));
            boolean replaceableSettledNative = previous != null
                    && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(previous.getProtocolVersion())
                    && !replaceableDiscovery
                    && (NativeAssistantPlanContinuationPolicy.isDurablyCompleted(previousDefinition, previousSteps)
                    || NativeAssistantPlanContinuationPolicy.isDurablyRecoverable(previousDefinition, previousSteps));
            if (previous == null || !"APPROVED".equals(previous.getStatus())
                    || !Objects.equals(previous.getDefinitionDigest(), SecureUtil.sha256(previous.getDefinitionJson()))
                    || !(replaceableDiscovery || replaceableSettledNative))
                throw new ServiceException("Assistant plan replacement is unavailable");
            if (NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion()))
            {
                ReusablePackageReadEvidence packageReads = reusablePackageReadEvidence(locked.run());
                requireNativePlanReadPolicy(locked.run(), payload, previousDefinition, previousSteps,
                        packageReads);
            }
        }
        else if (NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion()))
        {
            ReusablePackageReadEvidence packageReads = reusablePackageReadEvidence(locked.run());
            requireNativePlanReadPolicy(locked.run(), payload, null, List.of(), packageReads);
        }
        if (NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion()))
            requireNativeCompletionEvidenceFeasible(payload);
        requireSelectedImageRouteForPlan(locked.run(), payload);

        Date now = new Date();
        if (previous != null)
        {
            previous.setStatus("SUPERSEDED");
            previous.setUpdateBy(lease.getOwner());
            previous.setUpdateTime(now);
            if (plans.updateById(previous) != 1)
                throw new ServiceException("Assistant discovery plan changed");
        }
        if (remainingPlanPolicy.repeatsCompletedOperation(runId, locked.run().getPlanRevision(), payload))
            throw new ServiceException("PLAN_REPEATS_COMPLETED_OPERATION");
        String definition = payload.toJSONString();
        AidRuntimePlan plan = new AidRuntimePlan();
        plan.setRunId(runId);
        plan.setRevision(newRevision);
        plan.setParentRevision(value(locked.run().getPlanRevision()));
        plan.setAgentVersionId(locked.run().getVersionId());
        plan.setProtocolVersion(decision.getProtocolVersion());
        plan.setPlannerAttemptNo(locked.step().getPlanPosition());
        plan.setDefinitionJson(definition);
        plan.setInputContextDigest(locked.run().getContextDigest());
        plan.setDefinitionDigest(SecureUtil.sha256(definition));
        plan.setMediaTaskId(locked.step().getMediaTaskId());
        plan.setStatus("PROPOSED");
        plan.setDelFlag("0");
        setCreateAudit(plan, locked.run().getUserId(), now);
        plans.insert(plan);
        locked.step().setStatus("SUCCEEDED");
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        locked.run().setPlanRevision(newRevision);
        locked.run().setStatus("WAITING_APPROVAL");
        locked.run().setNextAction("PLAN_EXECUTION_APPROVAL_REQUIRED");
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "plan.created", String.valueOf(plan.getId()), newRevision,
                decision.getDecisionId(), Map.of("planRevision", newRevision,
                        "definitionDigest", plan.getDefinitionDigest()), now);
        if (!Boolean.TRUE.equals(locked.run().getStopRequested())
                && ("AUTO".equals(budgetPolicies.executionMode(locked.run()))
                    || AssistantAutomaticTextExecution.authorizesTextPlan(
                            JSON.parseObject(locked.run().getRequestJson()), payload, toolRegistry::isUnbilledRead)))
        {
            var confirmation = new AssistantExecutionRequests.PlanConfirm();
            confirmation.setRunId(runId);
            confirmation.setPlanRevision(newRevision);
            confirmation.setDefinitionDigest(plan.getDefinitionDigest());
            confirmation.setExpectedRunRevision(locked.run().getStateRevision());
            confirmation.setClientRequestId(SecureUtil.sha256("automatic-plan:" + runId + ":" + newRevision));
            assistantCore.confirmPlan(locked.run().getUserId(), confirmation);
        }
    }

    private void requireSelectedImageRouteForPlan(AidRuntimeRun run, JSONObject payload)
    {
        try
        {
            SelectedSkillToolRoutePolicy.Requirement requirement =
                    SelectedSkillToolRoutePolicy.resolveRequest(run.getRequestJson());
            if (!requirement.active()) return;
            AidRuntimeAgentToolRef published = agentToolRefs.selectOne(
                    new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                            .eq(AidRuntimeAgentToolRef::getAgentVersionId, run.getVersionId())
                            .eq(AidRuntimeAgentToolRef::getToolCode,
                                    AssistantImageSkillInvocationResolver.TOOL_CODE)
                            .last("LIMIT 1"));
            RuntimeToolRegistry.Descriptor descriptor = null;
            RuntimeToolProvider.BillingMediaType mediaType = null;
            if (toolRegistry.supports(AssistantImageSkillInvocationResolver.TOOL_CODE))
            {
                descriptor = toolRegistry.require(AssistantImageSkillInvocationResolver.TOOL_CODE);
                mediaType = toolRegistry.billingMediaType(AssistantImageSkillInvocationResolver.TOOL_CODE);
            }
            boolean requiredToolAvailable = SelectedSkillToolRoutePolicy.matchesRequiredToolBinding(
                    published, descriptor, mediaType);
            SelectedSkillToolRoutePolicy.requirePlan(requirement, requiredToolAvailable,
                    payload == null ? null : payload.getJSONArray("steps"), false,
                    toolRegistry::billingMediaType);
            imageSkills.requirePinned(run.getId(), run.getUserId());
        }
        catch (ServiceException rejected)
        {
            String code = SelectedSkillToolRoutePolicy.ownedRejectionCode(rejected);
            if (code == null) throw rejected;
            String reasonCode = switch (code)
            {
                case SelectedSkillToolRoutePolicy.TOOL_DOWNGRADE,
                     SelectedSkillToolRoutePolicy.TOOL_REQUIRED -> code;
                default -> SelectedSkillToolRoutePolicy.SELECTION_INVALID;
            };
            AssistantPlanDecisionRejectedException.Reason reason =
                    AssistantPlanDecisionRejectedException.Reason.fromCode(reasonCode);
            if (reason == null) throw rejected;
            throw new AssistantPlanDecisionRejectedException(reason, rejected);
        }
    }

    private ReusablePackageReadEvidence reusablePackageReadEvidence(AidRuntimeRun run)
    {
        List<AssistantPackageReadReceiptService.VerifiedPackageRead> reads =
                packageReadReceipts.currentContextReads(run.getUserId(), run);
        Set<String> toolCodes = reads.stream()
                .map(AssistantPackageReadReceiptService.VerifiedPackageRead::toolCode)
                .filter(code -> "aid.skill.read-resource".equals(code)
                        || "aid.agent.read-resource".equals(code))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        JSONObject selected = NativeAssistantPlanReadPolicy.selectedSkill(run.getRequestJson());
        boolean selectedMasterReady = !NativeAssistantPlanReadPolicy.requiresSelectedSkillMaster(selected)
                || reads.stream()
                .filter(read -> "aid.skill.read-resource".equals(read.toolCode()))
                .anyMatch(read -> NativeAssistantLoopExecutor.matchesSelectedSkillMaster(
                        selected, read.observation()));
        return new ReusablePackageReadEvidence(toolCodes, selectedMasterReady);
    }

    private record ReusablePackageReadEvidence(Set<String> toolCodes,
                                                boolean selectedSkillMasterReady) { }

    /** Rejects only criterion kinds that the proposed native plan cannot possibly evidence. */
    private void requireNativeCompletionEvidenceFeasible(JSONObject plan)
    {
        if (!AssistantPlanCompletionFeasibility.declaresArtifactOutputs(plan,toolRegistry::planningOutputReferenceKind))
            throw new AssistantPlanDecisionRejectedException(AssistantPlanDecisionRejectedException.Reason.PLAN_ARTIFACT_OUTPUT_INVALID,
                    new ServiceException("PLAN_ARTIFACT_OUTPUT_INVALID"));
        if (!AssistantPlanCompletionFeasibility.declaresBillableOutputs(plan,
                code -> toolRegistry.require(code).billable()))
            throw new AssistantPlanDecisionRejectedException(
                    AssistantPlanDecisionRejectedException.Reason.PLAN_BILLABLE_OUTPUT_INVALID,
                    new ServiceException("PLAN_BILLABLE_OUTPUT_INVALID"));
        if (!AssistantPlanCompletionFeasibility.canProduceEvidence(plan,
                toolRegistry::planningOutputReferenceKind, targetWriteCompletion.supportedToolCodes()))
            rejectUnsatisfiableCompletionEvidence();
    }

    private static void rejectUnsatisfiableCompletionEvidence()
    {
        throw new AssistantPlanDecisionRejectedException(
                AssistantPlanDecisionRejectedException.Reason.PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE,
                new ServiceException("PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE"));
    }

    /** Classifies only the policy's pure proposed-plan validation codes as definitive. */
    private void requireNativePlanReadPolicy(AidRuntimeRun run, JSONObject next,
                                             JSONObject previous, List<AidRuntimeRunStep> receipts,
                                             ReusablePackageReadEvidence packageReads)
    {
        try
        {
            NativeAssistantPlanReadPolicy.requireNextPlan(
                    run.getRequestJson(), next, previous, receipts,
                    packageReads.toolCodes(), packageReads.selectedSkillMasterReady());
        }
        catch (ServiceException rejected)
        {
            AssistantPlanDecisionRejectedException.Reason reason =
                    AssistantPlanDecisionRejectedException.Reason.fromCode(rejected.getMessage());
            if (reason == null || !reason.name().startsWith("NATIVE_PLAN_SAFE_READ_")) throw rejected;
            throw new AssistantPlanDecisionRejectedException(reason, rejected);
        }
    }

    private void applyMessageLocked(Long runId, Long stepId, String content,
                                    AssistantLoopDecision decision, RuntimeLease lease,
                                    boolean complete, JSONObject payload)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        if (supersedeForQueuedMessage(locked, lease.getOwner(), decision.getDecisionId())) return;
        if (StrUtil.isBlank(content)) throw new ServiceException("Assistant message is empty");
        Set<String> verifiedTargetWrites = complete ? validateCompletion(locked.run(), payload) : Set.of();
        if (complete && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion()))
        {
            content = methodResultCompletion.renderMethodDelivery(locked.run(),
                    payload.getJSONArray("completionCriteriaResults"), content);
            content = AssistantTargetWriteCompletionService.renderVerifiedDelivery(
                    payload.getJSONArray("completionCriteriaResults"), verifiedTargetWrites, content);
        }
        Date now = new Date();
        JSONObject draft = complete || !com.aid.assistant.creative.series.FlowScreenplaySourcePolicy.applies(locked.session())
                ? null : FlowScreenplayDecisionContract.draft(payload, JSON.parseObject(locked.run().getRequestJson())
                        .getJSONObject(com.aid.assistant.creative.series.FlowScreenplaySourcePolicy.PIN), content);
        AidAssistantMessage message = appendAssistantMessage(locked.session(), locked.run(),
                decision.getDecisionId(), content, now, draft);
        locked.step().setStatus("SUCCEEDED");
        updateStep(locked.step(), lease.getOwner());
        AidRuntimeApproval consumed = consumeApproval(locked.step(), lease.getOwner(), now);
        ChatCompletionDisposition chat = complete ? ChatCompletionDisposition.COMPLETE : chatCompletionDisposition(locked.run());
        boolean terminal = chat == ChatCompletionDisposition.COMPLETE;
        boolean automatic = "AUTO".equals(budgetPolicies.executionMode(locked.run()));
        boolean recoveryPause = !automatic && !terminal
                && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion())
                && hasRecoverableNativePlan(locked.run());
        boolean blockedReplyPause = !automatic && !terminal && !recoveryPause
                && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(decision.getProtocolVersion())
                && (chat == ChatCompletionDisposition.NEEDS_USER_SCOPE
                || NativeAssistantReplyContinuationPolicy.mustPauseUnfinishedReply(locked.run().getRequestJson(), consumed.getScopeJson(),
                locked.run().getId(), locked.run().getPlanRevision()));
        locked.run().setStatus(terminal ? "SUCCEEDED" : recoveryPause || blockedReplyPause ? "PAUSED" : "WAITING_APPROVAL");
        locked.run().setNextAction(terminal ? null : "CONTINUE_AGENT_REQUIRED");
        if (recoveryPause || blockedReplyPause)
        {
            locked.run().setResumeStatus("WAITING_APPROVAL");
            locked.run().setPauseReason(recoveryPause ? "OPERATION_FAILED" : "AWAITING_USER");
        }
        locked.run().setResultJson(decision.getDecisionJson());
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "message.created", String.valueOf(message.getId()), 1L,
                decision.getDecisionId(), Map.of("messageId", String.valueOf(message.getId()),
                        "seq", message.getSeqNo(), "role", "ASSISTANT"), now);
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), decision.getDecisionId(),
                Map.of("status", locked.run().getStatus(),
                        "nextAction", Objects.toString(locked.run().getNextAction(), "")), now);
    }

    private AidAssistantMessage appendAssistantMessage(AidAssistantSession session, AidRuntimeRun run,
                                                       String decisionId, String content, Date now)
    {
        return appendAssistantMessage(session, run, decisionId, content, now, null);
    }

    private AidAssistantMessage appendAssistantMessage(AidAssistantSession session, AidRuntimeRun run,
                                                       String decisionId, String content, Date now,
                                                       JSONObject flowScreenplayDraft)
    {
        long seq = value(session.getLastMessageSeq()) + 1;
        AidAssistantMessage message = new AidAssistantMessage();
        message.setSessionId(session.getId());
        message.setRuntimeRunId(run.getId());
        message.setTurnId(run.getTurnId());
        message.setSeqNo(seq);
        message.setClientMessageId(decisionId);
        message.setRequestHash(SecureUtil.sha256(content));
        message.setRole("ASSISTANT");
        message.setStatus("COMPLETE");
        message.setMessageKind("ASSISTANT_OUTPUT");
        message.setMediaType("TEXT");
        message.setContentText(content.substring(0, Math.min(content.length(), 1000)));
        message.setDelFlag("0");
        setCreateAudit(message, run.getUserId(), now);
        messages.insert(message);
        AidAssistantMessagePart part = new AidAssistantMessagePart();
        part.setMessageId(message.getId());
        part.setPartSeq(1);
        part.setPartType("TEXT");
        part.setSlotName(flowScreenplayDraft == null ? "ASSISTANT_TEXT" : "FLOW_SCREENPLAY_DRAFT");
        if (flowScreenplayDraft != null) part.setMetadataJson(flowScreenplayDraft.toJSONString());
        part.setTextContent(content);
        part.setContextScope("CURRENT_TURN");
        part.setDelFlag("0");
        setCreateAudit(part, run.getUserId(), now);
        messageParts.insert(part);
        session.setLastMessageSeq(seq);
        session.setStateRevision(value(session.getStateRevision()) + 1);
        session.setLastActiveTime(now);
        updateSession(session, run.getUserId(), now);
        return message;
    }

    /** A factual failure response must not schedule another paid explanation automatically. */
    private boolean hasRecoverableNativePlan(AidRuntimeRun run)
    {
        if (value(run.getPlanRevision()) <= 0) return false;
        AidRuntimePlan plan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, run.getId())
                .eq(AidRuntimePlan::getRevision, run.getPlanRevision())
                .eq(AidRuntimePlan::getDelFlag, "0").last("FOR UPDATE"));
        if (plan == null || !"APPROVED".equals(plan.getStatus())
                || !NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(plan.getProtocolVersion())
                || !Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                || !Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson())))
            return false;
        JSONObject definition = JSON.parseObject(plan.getDefinitionJson());
        if (NativeAssistantDiscoveryPlanService.isDiscovery(definition)) return false;
        List<AidRuntimeRunStep> receipts = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getPlanRevision, plan.getRevision())
                .ne(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getDelFlag, "0"));
        return NativeAssistantPlanContinuationPolicy.isDurablyRecoverable(definition, receipts);
    }

    private ChatCompletionDisposition chatCompletionDisposition(AidRuntimeRun run)
    {
        if (value(run.getPlanRevision()) > 0)
        {
            AidRuntimePlan plan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                    .eq(AidRuntimePlan::getRunId, run.getId())
                    .eq(AidRuntimePlan::getRevision, run.getPlanRevision())
                    .eq(AidRuntimePlan::getStatus, "APPROVED")
                    .eq(AidRuntimePlan::getDelFlag, "0").last("LIMIT 1"));
            JSONObject definition;
            try { definition = plan == null ? null : JSON.parseObject(plan.getDefinitionJson()); }
            catch (RuntimeException invalid) { return ChatCompletionDisposition.NEEDS_USER_SCOPE; }
            boolean discovery = plan != null
                    && Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                    && Objects.equals(plan.getInputContextDigest(), run.getContextDigest())
                    && Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson()))
                    && NativeAssistantDiscoveryPlanService.isDiscovery(definition);
            if (!discovery) return ChatCompletionDisposition.CONTINUE_EXECUTION;
            // Interjections can replace one discovery plan with another. Check the immutable
            // ancestry in one bounded run-scoped read; only execution ancestry requires COMPLETE.
            List<AidRuntimePlan> ancestors = definition.getLongValue("parentRevision") > 0
                    ? plans.selectList(new LambdaQueryWrapper<AidRuntimePlan>()
                        .select(AidRuntimePlan::getRunId, AidRuntimePlan::getRevision,
                                AidRuntimePlan::getParentRevision, AidRuntimePlan::getAgentVersionId,
                                AidRuntimePlan::getProtocolVersion, AidRuntimePlan::getStatus,
                                AidRuntimePlan::getDefinitionJson, AidRuntimePlan::getDefinitionDigest,
                                AidRuntimePlan::getInputContextDigest)
                        .eq(AidRuntimePlan::getRunId, run.getId())
                        .lt(AidRuntimePlan::getRevision, plan.getRevision())
                        .eq(AidRuntimePlan::getDelFlag, "0")) : List.of();
            if (!NativeAssistantDiscoveryPlanService.discoveryOnlyLineage(plan, ancestors))
                return canCompleteReadOnlyUserReplan(run, plan, ancestors)
                        ? ChatCompletionDisposition.COMPLETE : ChatCompletionDisposition.NEEDS_USER_SCOPE;
            // Selecting a package is not an execution plan. A completed ordinary
            // answer needs no unrelated package read; planning and execution retain
            // their own master-resource and completion-evidence checks.
            return ChatCompletionDisposition.COMPLETE;
        }
        Long childCount = steps.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .ne(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getDelFlag, "0"));
        return childCount == null || childCount == 0L
                ? ChatCompletionDisposition.COMPLETE : ChatCompletionDisposition.CONTINUE_EXECUTION;
    }

    private enum ChatCompletionDisposition { COMPLETE, CONTINUE_EXECUTION, NEEDS_USER_SCOPE }

    @jakarta.annotation.Resource
    private com.aid.runtime.mapper.AidRuntimeCommandMapper readOnlyReplanCommands;
    @jakarta.annotation.Resource
    private com.aid.runtime.assistant.execution.AssistantCompletedToolScopeVerifier readOnlyCompletionProofs;

    private boolean canCompleteReadOnlyUserReplan(AidRuntimeRun run, AidRuntimePlan plan,
                                                 List<AidRuntimePlan> ancestors)
    {
        AidRuntimePlan retiredExecution = ancestors.stream()
                .filter(row -> {
                    try { return !NativeAssistantDiscoveryPlanService.isDiscovery(JSON.parseObject(row.getDefinitionJson())); }
                    catch (RuntimeException invalid) { return false; }
                }).max(java.util.Comparator.comparing(AidRuntimePlan::getRevision)).orElse(null);
        if (retiredExecution == null) return false;
        AidRuntimeEvent event = events.selectOne(new LambdaQueryWrapper<AidRuntimeEvent>()
                .eq(AidRuntimeEvent::getRunId, run.getId())
                .eq(AidRuntimeEvent::getSessionId, run.getSessionId())
                .eq(AidRuntimeEvent::getEventType, "run.replanning_required")
                .orderByDesc(AidRuntimeEvent::getSeq).last("LIMIT 1"));
        if (event == null || StrUtil.isBlank(event.getCausationId())) return false;
        var command = readOnlyReplanCommands.selectOne(
                new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeCommand>()
                        .eq(com.aid.runtime.domain.AidRuntimeCommand::getActorId, run.getUserId())
                        .eq(com.aid.runtime.domain.AidRuntimeCommand::getSessionId, run.getSessionId())
                        .eq(com.aid.runtime.domain.AidRuntimeCommand::getClientRequestId, event.getCausationId())
                        .eq(com.aid.runtime.domain.AidRuntimeCommand::getDelFlag, "0").last("LIMIT 1"));
        var message = messages.selectOne(new LambdaQueryWrapper<AidAssistantMessage>()
                .select(AidAssistantMessage::getId, AidAssistantMessage::getSessionId,
                        AidAssistantMessage::getRuntimeRunId, AidAssistantMessage::getClientMessageId,
                        AidAssistantMessage::getRequestHash, AidAssistantMessage::getRole,
                        AidAssistantMessage::getStatus, AidAssistantMessage::getMessageKind,
                        AidAssistantMessage::getDelFlag)
                .eq(AidAssistantMessage::getRuntimeRunId, run.getId())
                .eq(AidAssistantMessage::getSessionId, run.getSessionId())
                .eq(AidAssistantMessage::getClientMessageId, event.getCausationId())
                .eq(AidAssistantMessage::getDelFlag, "0").last("LIMIT 1"));
        if (!AssistantReadOnlyReplanCompletionPolicy.permitsReply(run, plan, ancestors,
                event, command, message, NativeAssistantDiscoveryPlanService::isDiscovery)) return false;
        // A reply may retire the current read-only continuation, never hide in-flight/unknown work.
        if (!steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .and(query -> query.notIn(AidRuntimeRunStep::getStatus,
                        List.of("SUCCEEDED", "FAILED", "CANCELLED", "FAILED_PRETASK", "CANCELLED_PRETASK"))
                        .or().eq(AidRuntimeRunStep::getProviderAcceptanceState, "UNKNOWN"))
                .last("LIMIT 1")).isEmpty()) return false;
        var reads = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getPlanRevision, plan.getRevision())
                .eq(AidRuntimeRunStep::getStepKind, "TOOL")
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 1"));
        if (reads.isEmpty() || !toolRegistry.isUnbilledRead(reads.get(0).getToolCode())) return false;
        readOnlyCompletionProofs.require(run.getUserId(), run, reads.get(0));
        return true;
    }

    private void applyCheckpointLocked(Long runId, Long stepId, JSONObject payload, RuntimeLease lease)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        if (supersedeForQueuedMessage(locked, lease.getOwner(), payload.getString("checkpointId"))) return;
        locked.step().setStatus("SUCCEEDED");
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        locked.run().setCheckpointRef("step:" + stepId + ":" + payload.getString("checkpointId"));
        locked.run().setStatus("WAITING_APPROVAL");
        locked.run().setNextAction("CONTINUE_AGENT_REQUIRED");
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), payload.getString("checkpointId"),
                Map.of("status", locked.run().getStatus(), "nextAction", locked.run().getNextAction()), new Date());
    }

    private void applyFailureLocked(Long runId, Long stepId, JSONObject payload,
                                    AssistantLoopDecision decision, RuntimeLease lease)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        if (supersedeForQueuedMessage(locked, lease.getOwner(), payload.getString("code"))) return;
        locked.step().setStatus("FAILED");
        locked.step().setErrorMessage(payload.getString("code"));
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        locked.run().setStatus("FAILED");
        locked.run().setNextAction(null);
        locked.run().setErrorMessage(payload.getString("userMessage"));
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        Date now = new Date();
        AidAssistantMessage message = appendAssistantMessage(locked.session(), locked.run(),
                decision.getDecisionId(), payload.getString("userMessage"), now);
        appendEvent(locked.session(), locked.run(), "message.created", String.valueOf(message.getId()), 1L,
                decision.getDecisionId(), Map.of("messageId", String.valueOf(message.getId()),
                        "seq", message.getSeqNo(), "role", "ASSISTANT"), now);
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), decision.getDecisionId(),
                Map.of("status", locked.run().getStatus(), "reason", "AGENT_DECLARED_FAILURE"), now);
    }

    private void applyUnauthorizedExecutionLocked(Long runId, Long stepId, String action, RuntimeLease lease)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        if (supersedeForQueuedMessage(locked, lease.getOwner(), action)) return;
        locked.step().setStatus("FAILED");
        locked.step().setErrorMessage("UNAPPROVED_" + action);
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        locked.run().setStatus("FAILED");
        locked.run().setErrorMessage("Main Agent requested execution without an approved plan step");
        locked.run().setNextAction(null);
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), action,
                Map.of("status", locked.run().getStatus(), "reason", "UNAPPROVED_ACTION"), new Date());
    }

    private void finishStep(Long runId, Long stepId, RuntimeLease lease)
    {
        Locked locked = lockRunGraph(runId, stepId, lease);
        requireDecisionReady(locked.step());
        locked.step().setStatus("SUCCEEDED");
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
    }

    private boolean supersedeForQueuedMessage(Locked locked, String actor, String causationId)
    {
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, locked.step().getApprovalId())
                .eq(AidRuntimeApproval::getRunId, locked.run().getId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        JSONObject scope = approvalScope(approval);
        long acceptedMessageSeq = value(scope.getLong("acceptedMessageSeq"));
        Long queued = messages.selectCount(new LambdaQueryWrapper<AidAssistantMessage>()
                .eq(AidAssistantMessage::getRuntimeRunId, locked.run().getId())
                .eq(AidAssistantMessage::getMessageKind, "USER_INTERJECTION")
                .gt(AidAssistantMessage::getSeqNo, acceptedMessageSeq)
                .eq(AidAssistantMessage::getDelFlag, "0"));
        if (queued == null || queued == 0L) return false;
        Date now = new Date();
        locked.step().setStatus("SUCCEEDED");
        locked.step().setErrorMessage("SUPERSEDED_BY_USER_MESSAGE");
        updateStep(locked.step(), actor);
        consumeApproval(locked.step(), actor, now);
        locked.run().setStatus("WAITING_APPROVAL");
        locked.run().setNextAction("CONTINUE_AGENT_REQUIRED");
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1L);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), actor);
        appendEvent(locked.session(), locked.run(), "decision.superseded", String.valueOf(locked.step().getId()),
                locked.run().getStateRevision(), causationId,
                Map.of("reason", "USER_INTERJECTION", "queuedMessageCount", queued), now);
        return true;
    }

    private Locked lockRunGraph(Long runId, Long stepId, RuntimeLease lease)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        requireActiveMutation(session, run, lease);
        AidRuntimeRunStep step = requireStep(stepId, runId, true);
        return new Locked(session, run, step);
    }

    private Locked lockReceiptGraph(Long runId, Long stepId, RuntimeLease lease)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        requireReceiptMutation(session, run, lease);
        AidRuntimeRunStep step = requireStep(stepId, runId, true);
        return new Locked(session, run, step);
    }

    private void validateDecisionObservation(AidRuntimeRun run, AidRuntimeRunStep step, AssistantLoopDecision decision)
    {
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        JSONObject scope = approvalScope(approval);
        Long acceptedRevision = scope.getLong("acceptedRunRevision");
        if (!Objects.equals(decision.getObservedRunRevision(), acceptedRevision)
                || !Objects.equals(decision.getObservedPlanRevision(), value(run.getPlanRevision())))
            throw new ServiceException("Assistant decision observed a stale snapshot");
    }

    private void requireDecisionReady(AidRuntimeRunStep step)
    {
        if (!"DECISION_READY".equals(step.getStatus()))
            throw new ServiceException("Assistant decision state changed");
    }

    private AidRuntimeApproval consumeApproval(AidRuntimeRunStep step, String actor, Date now)
    {
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, step.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, step.getRunId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        if (approval == null) throw new ServiceException("Assistant approval is unavailable");
        if ("CONSUMED".equals(approval.getStatus())) return approval;
        if (!"ACCEPTED".equals(approval.getStatus()))
            throw new ServiceException("Assistant approval state changed");
        approval.setStatus("CONSUMED");
        approval.setStateRevision(value(approval.getStateRevision()) + 1);
        approval.setUpdateBy(actor);
        approval.setUpdateTime(now);
        approvals.updateById(approval);
        return approval;
    }

    private Set<String> validateCompletion(AidRuntimeRun run, JSONObject payload)
    {
        try { return validateCompletionEvidence(run, payload); }
        catch (ServiceException rejected)
        {
            AssistantCompletionDecisionRejectedException.Reason reason =
                    AssistantCompletionDecisionRejectedException.Reason.fromCode(rejected.getMessage());
            if (reason == null) throw rejected;
            throw new AssistantCompletionDecisionRejectedException(reason, rejected);
        }
    }

    private Set<String> validateCompletionEvidence(AidRuntimeRun run, JSONObject payload)
    {
        JSONArray criteria = payload == null ? null : payload.getJSONArray("completionCriteriaResults");
        if (criteria == null || criteria.isEmpty())
            throw new ServiceException("COMPLETION_EVIDENCE_REQUIRED");
        JSONArray refs = payload.getJSONArray("artifactRefs");
        if (refs == null) throw new ServiceException("COMPLETION_ARTIFACT_REQUIRED");
        AidRuntimePlan currentPlan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, run.getId()).eq(AidRuntimePlan::getRevision, run.getPlanRevision())
                .eq(AidRuntimePlan::getDelFlag, "0").last("LIMIT 1"));
        JSONObject definition = currentPlan == null ? null : JSON.parseObject(currentPlan.getDefinitionJson());
        JSONArray planned = definition == null ? null : definition.getJSONArray("completionCriteria");
        if (planned == null || planned.isEmpty()) throw new ServiceException("COMPLETION_PLAN_CRITERIA_MISSING");
        targetWriteCompletion.requireCompletedCurrentPlan(run, currentPlan);
        Set<String> targetWrites = targetWriteCompletion.requireDeclaredEvidence(run, currentPlan,
                payload.getJSONArray("targetWriteEvidenceRefs"));
        boolean hasMethodCriteria = criteria.stream().anyMatch(item -> item instanceof JSONObject criterion
                && "METHOD_RESULT".equals(criterion.getString("criterionKind")));
        Set<String> methodResults = hasMethodCriteria
                ? methodResultCompletion.requireDeclaredEvidence(run, currentPlan, criteria) : Set.of();
        if (refs.isEmpty() && targetWrites.isEmpty() && methodResults.isEmpty())
            throw new ServiceException("COMPLETION_ARTIFACT_REQUIRED");
        Map<String, String> plannedCriteria = new HashMap<>();
        for (Object raw : planned)
        {
            if (!(raw instanceof JSONObject criterion)
                    || StrUtil.isBlank(criterion.getString("criterionId"))
                    || !Set.of("TECHNICAL_ARCHIVE", "CREATIVE_QUALITY", "CONTENT_REQUIREMENT", "TARGET_WRITE", "METHOD_RESULT")
                    .contains(criterion.getString("criterionKind"))
                    || plannedCriteria.putIfAbsent(criterion.getString("criterionId"),
                    criterion.getString("criterionKind")) != null)
                throw new ServiceException("COMPLETION_PLAN_CRITERIA_INVALID");
        }
        Map<String, AidRuntimeArtifact> declaredArtifacts = new HashMap<>();
        Set<String> verifiedTechnicalArchives = new HashSet<>();
        for (int index = 0; index < refs.size(); index++)
        {
            String ref = refs.getString(index);
            if (StrUtil.isBlank(ref) || declaredArtifacts.containsKey(ref) || !ref.startsWith("artifact:"))
                throw new ServiceException("COMPLETION_ARTIFACT_INVALID");
            int revisionSeparator = ref.lastIndexOf(':');
            if (revisionSeparator <= "artifact:".length() || revisionSeparator == ref.length() - 1)
                throw new ServiceException("COMPLETION_ARTIFACT_INVALID");
            String logicalId = ref.substring("artifact:".length(), revisionSeparator);
            Long revision;
            try { revision = Long.valueOf(ref.substring(revisionSeparator + 1)); }
            catch (NumberFormatException invalid) { throw new ServiceException("COMPLETION_ARTIFACT_INVALID"); }
            AidRuntimeArtifact artifact = artifacts.selectOne(new LambdaQueryWrapper<AidRuntimeArtifact>()
                    .eq(AidRuntimeArtifact::getLogicalArtifactId, logicalId)
                    .eq(AidRuntimeArtifact::getRevision, revision)
                    .eq(AidRuntimeArtifact::getProducerRunId, run.getId())
                    .eq(AidRuntimeArtifact::getStatus, "FINALIZED")
                    .eq(AidRuntimeArtifact::getDelFlag, "0").last("LIMIT 1"));
            if (artifact == null
                    || !Objects.equals(artifact.getAgentVersionId(), run.getVersionId())
                    || !Objects.equals(artifact.getSourceContextDigest(), run.getContextDigest())
                    || "CHANGES_REQUESTED".equals(artifact.getReviewStatus())
                    || "REJECT".equals(artifact.getReviewStatus()))
                throw new ServiceException("COMPLETION_ARTIFACT_STALE_OR_UNREVIEWED");
            if (!Objects.equals(value(artifact.getPlanRevision()), value(run.getPlanRevision()))) {
                if (!AssistantTechnicalEvidenceScope.permitsEarlierPlan(artifact.getPlanRevision(),
                        run.getPlanRevision(), ref, criteria, plannedCriteria))
                    throw new ServiceException("COMPLETION_ARTIFACT_STALE_OR_UNREVIEWED");
                AidRuntimeRunStep producer = steps.selectById(artifact.getProducerStepId());
                if (producer == null || !"0".equals(producer.getDelFlag())
                        || !"SUCCEEDED".equals(producer.getStatus())
                        || !Objects.equals(producer.getRunId(), run.getId())
                        || !Objects.equals(producer.getPlanRevision(), artifact.getPlanRevision()))
                    throw new ServiceException("COMPLETION_ARTIFACT_STALE_OR_UNREVIEWED");
                // Do not relabel or copy an old artifact into the current plan. Validate
                // the original producer and actual archive before accepting this reference.
                requireTechnicalArchive(artifact);
                verifiedTechnicalArchives.add(ref);
            }
            declaredArtifacts.put(ref, artifact);
        }
        Set<String> completedCriteria = new HashSet<>();
        Set<String> usedTargetWrites = new HashSet<>();
        for (int index = 0; index < criteria.size(); index++)
        {
            JSONObject result = criteria.getJSONObject(index);
            if (result == null || !result.keySet().equals(Set.of("criterionId", "criterionKind", "passed", "evidenceRefs"))
                    || StrUtil.isBlank(result.getString("criterionId"))
                    || result.getString("criterionId").length() > 64
                    || !Boolean.TRUE.equals(result.getBoolean("passed")))
                throw new ServiceException("COMPLETION_CRITERION_FAILED");
            String criterionId = result.getString("criterionId");
            String criterionKind = result.getString("criterionKind");
            if (!completedCriteria.add(criterionId)
                    || !Objects.equals(plannedCriteria.get(criterionId), criterionKind))
                throw new ServiceException("COMPLETION_CRITERION_NOT_IN_PLAN");
            JSONArray evidenceRefs = result.getJSONArray("evidenceRefs");
            if (evidenceRefs == null || evidenceRefs.isEmpty())
                throw new ServiceException("COMPLETION_CRITERION_EVIDENCE_REQUIRED");
            Set<String> criterionEvidence = new HashSet<>();
            for (int evidenceIndex = 0; evidenceIndex < evidenceRefs.size(); evidenceIndex++)
            {
                String evidenceRef = evidenceRefs.getString(evidenceIndex);
                if ("METHOD_RESULT".equals(criterionKind))
                {
                    if (!methodResults.contains(evidenceRef) || !criterionEvidence.add(evidenceRef))
                        throw new ServiceException("COMPLETION_METHOD_EVIDENCE_INVALID");
                    continue;
                }
                if ("TARGET_WRITE".equals(criterionKind))
                {
                    if (!targetWrites.contains(evidenceRef) || !criterionEvidence.add(evidenceRef))
                        throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");
                    usedTargetWrites.add(evidenceRef);
                    continue;
                }
                AidRuntimeArtifact evidence = declaredArtifacts.get(evidenceRef);
                if (evidence == null || !criterionEvidence.add(evidenceRef))
                    throw new ServiceException("COMPLETION_CRITERION_EVIDENCE_INVALID");
                if ("TECHNICAL_ARCHIVE".equals(criterionKind))
                {
                    if (verifiedTechnicalArchives.add(evidenceRef)) requireTechnicalArchive(evidence);
                    continue;
                }
                if (!"PASS".equals(evidence.getReviewStatus()))
                    throw new ServiceException("COMPLETION_CRITERION_EVIDENCE_INVALID");
                AidRuntimeArtifactReview review = artifactReviews.selectOne(
                        new LambdaQueryWrapper<AidRuntimeArtifactReview>()
                                .eq(AidRuntimeArtifactReview::getArtifactId, evidence.getId())
                                .eq(AidRuntimeArtifactReview::getVerdict, "PASS")
                                .eq(AidRuntimeArtifactReview::getStatus, "ACTIVE")
                                .eq(AidRuntimeArtifactReview::getDelFlag, "0")
                                .orderByDesc(AidRuntimeArtifactReview::getId).last("LIMIT 1"));
                AidRuntimeArtifact reviewArtifact = review == null ? null : declaredArtifacts.values().stream()
                        .filter(candidate -> Objects.equals(candidate.getId(), review.getReviewArtifactId()))
                        .findFirst().orElse(null);
                boolean declaredReview = reviewArtifact != null
                        && "REVIEW_REPORT".equals(reviewArtifact.getArtifactType())
                        && reviewScopeCovers(run, review, reviewArtifact, evidence, evidenceRef);
                if (!declaredReview)
                    throw new ServiceException("COMPLETION_REVIEW_EVIDENCE_REQUIRED");
            }
        }
        if (!completedCriteria.equals(plannedCriteria.keySet()))
            throw new ServiceException("COMPLETION_CRITERIA_INCOMPLETE");
        if (!usedTargetWrites.equals(targetWrites))
            throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");
        return Set.copyOf(targetWrites);
    }

    private void requireTechnicalArchive(AidRuntimeArtifact artifact)
    {
        if (com.aid.runtime.assistant.execution.AssistantTextArchiveCompletionVerifier.supports(artifact.getArtifactType()))
        {
            textArchiveCompletion.requireArchived(artifact);
            return;
        }
        if (!Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC").contains(artifact.getArtifactType())) {
            var matches = archiveExtensions.stream().filter(verifier ->
                    Objects.equals(verifier.artifactType(), artifact.getArtifactType())).toList();
            if (matches.size() != 1) throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID");
            matches.get(0).requireArchived(artifact);
            return;
        }
        JSONObject metadata;
        try { metadata = JSON.parseObject(artifact.getMetadataJson()); }
        catch (RuntimeException invalid) { throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID"); }
        if (metadata == null || !"ARCHIVED".equals(metadata.getString("technicalStatus"))
                || !"MEDIA_ARCHIVE_RECEIPT_SHA256".equals(metadata.getString("contentHashKind"))
                || !Boolean.FALSE.equals(metadata.getBoolean("creativeQualityVerified")))
            throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID");
    }

    private boolean reviewScopeCovers(AidRuntimeRun run, AidRuntimeArtifactReview review,
                                      AidRuntimeArtifact reviewArtifact,
                                      AidRuntimeArtifact target, String targetRef)
    {
        if (review == null || reviewArtifact == null || target == null
                || !Objects.equals(review.getArtifactId(), target.getId())
                || !Objects.equals(review.getReviewArtifactId(), reviewArtifact.getId())
                || !Objects.equals(review.getReviewerRunId(), run.getId())
                || !Objects.equals(review.getContentHash(), reviewArtifact.getContentHash())
                || !Objects.equals(reviewArtifact.getProducerRunId(), run.getId())
                || !Objects.equals(reviewArtifact.getAgentVersionId(), run.getVersionId())
                || !Objects.equals(value(reviewArtifact.getPlanRevision()), value(run.getPlanRevision()))
                || !Objects.equals(reviewArtifact.getSourceContextDigest(), run.getContextDigest())) return false;
        JSONObject reviewMetadata;
        try { reviewMetadata = JSON.parseObject(reviewArtifact.getMetadataJson()); }
        catch (RuntimeException invalid) { return false; }
        if (reviewMetadata == null
                || !Objects.equals(targetRef, reviewMetadata.getString("reviewSubjectRef"))
                || !Objects.equals(target.getContentHash(), reviewMetadata.getString("reviewSubjectContentHash"))
                || !isSha256(reviewMetadata.getString("reviewSubmissionPromptDigest"))) return false;
        AidRuntimeRunStep reviewer = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, review.getReviewerStepId()).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getPlanRevision, run.getPlanRevision())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("LIMIT 1"));
        if (reviewer == null || !Objects.equals(reviewArtifact.getProducerStepId(), reviewer.getId())) return false;
        if (!Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC").contains(target.getArtifactType())) return true;
        AidRuntimeApproval approval = reviewer == null || reviewer.getApprovalId() == null ? null
                : approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, reviewer.getApprovalId()).eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("LIMIT 1"));
        JSONObject scope = approval == null ? null : JSON.parseObject(approval.getScopeJson());
        JSONObject operation = scope == null ? null : scope.getJSONObject("operationScope");
        List<String> mediaTypes = operation == null ? null : operation.getList("mediaTypes", String.class);
        return mediaTypes != null && mediaTypes.contains(target.getArtifactType());
    }

    private boolean isSha256(String value)
    {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private AidMediaTask authoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        AidMediaTask task = step.getMediaTaskId() == null ? null : mediaTasks.selectById(step.getMediaTaskId());
        if (task == null)
        {
            task = mediaTasks.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getUserId, run.getUserId())
                    .eq(AidMediaTask::getIdempotencyKey, step.getIdempotencyKey())
                    .eq(AidMediaTask::getBizTaskType, "ASSISTANT_RUNTIME_STEP")
                    .eq(AidMediaTask::getBizTaskId, step.getId()).orderByDesc(AidMediaTask::getId).last("LIMIT 1"));
        }
        if (task != null && (!Objects.equals(task.getUserId(), run.getUserId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !"ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())))
            throw new ServiceException("Assistant task identity changed");
        return task;
    }

    private boolean shouldReconcileSubmissionNotEntered(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        if (!List.of("RECONCILING", "PAUSING").contains(run.getStatus())
                || !"WAIT_TASK_RECONCILIATION".equals(run.getNextAction())
                || step.getMediaTaskId() != null
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())) return false;
        return "FAILED_PRETASK".equals(step.getStatus())
                && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState())
                || "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState())
                && List.of("MEDIA_TASK_IDENTITY_UNKNOWN", "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN")
                .contains(step.getErrorMessage());
    }

    private boolean shouldCancelUnsubmittedMain(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        if (!Boolean.TRUE.equals(run.getStopRequested())
                || !"RECONCILING".equals(run.getStatus())
                || !"WAIT_TASK_RECONCILIATION".equals(run.getNextAction())
                || step.getMediaTaskId() != null
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())) return false;
        return "READY".equals(step.getStatus())
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || "CANCELLED_PRETASK".equals(step.getStatus())
                && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState())
                || "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState());
    }

    private boolean shouldCancelDurableUnsubmittedMain(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        return Boolean.TRUE.equals(run.getStopRequested())
                && "RECONCILING".equals(run.getStatus())
                && "WAIT_TASK_RECONCILIATION".equals(run.getNextAction())
                && step.getMediaTaskId() != null
                && "ASSISTANT_DECISION".equals(step.getOperationKind())
                && List.of("READY", "INFLIGHT").contains(step.getStatus())
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState());
    }

    /**
     * Cancels a durable task through the Media-owned gate.  A trusted CANCELLED receipt is the
     * only outcome that completes STOP here; terminal Provider outcomes continue through the
     * normal receipt path, while ambiguity remains recoverable.
     *
     * @return a completed reconciliation result, or {@code null} when a non-cancel terminal task
     *         must continue through the normal receipt path
     */
    private AssistantLoopReconcileResult cancelDurableUnsubmittedMain(Long runId, Long stepId,
                                                                        Long taskId, RuntimeLease lease)
    {
        MediaTaskCancellationResult outcome;
        try
        {
            outcome = taskCompletionService.cancelBudgetManagedUnsubmittedTask(taskId);
        }
        catch (RuntimeException uncertain)
        {
            log.info("assistant durable main-task cancellation requires reconciliation, runId={}, stepId={}, taskId={}, cause={}",
                    runId, stepId, taskId, uncertain.getClass().getSimpleName());
            outcome = MediaTaskCancellationResult.RECONCILE_ONLY;
        }

        AidRuntimeRun currentRun = requireRun(runId, false);
        AidRuntimeRunStep currentStep = requireStep(stepId, runId, false);
        AidMediaTask currentTask = authoritativeTask(currentRun, currentStep);
        if (currentTask == null || !Objects.equals(currentTask.getId(), taskId))
            return tx().execute(status -> markDurableMainCancellationUnknown(
                    runId, stepId, taskId, lease, "TASK_IDENTITY_UNKNOWN"));

        boolean cancelled = List.of("CANCELLED", "CANCELED").contains(currentTask.getStatus());
        boolean terminal = isTerminal(currentTask.getStatus()) || "CANCELED".equals(currentTask.getStatus());
        if (outcome == MediaTaskCancellationResult.CANCELLED)
        {
            if (!cancelled)
                return tx().execute(status -> markDurableMainCancellationUnknown(
                        runId, stepId, taskId, lease, "TASK_CANCELLATION_STATE_CHANGED"));
            return tx().execute(status -> finishDurableMainCancellation(runId, stepId, taskId, lease));
        }
        if (outcome == MediaTaskCancellationResult.ALREADY_TERMINAL)
        {
            if (!terminal)
                return tx().execute(status -> markDurableMainCancellationUnknown(
                        runId, stepId, taskId, lease, "TASK_TERMINAL_STATE_CHANGED"));
            return cancelled
                    ? tx().execute(status -> finishDurableMainCancellation(runId, stepId, taskId, lease))
                    : null;
        }
        if (terminal)
        {
            return cancelled
                    ? tx().execute(status -> finishDurableMainCancellation(runId, stepId, taskId, lease))
                    : null;
        }
        return tx().execute(status -> markDurableMainCancellationUnknown(
                runId, stepId, taskId, lease, "TASK_CANCELLATION_RECONCILING"));
    }

    private AssistantLoopReconcileResult finishDurableMainCancellation(Long runId, Long stepId,
                                                                         Long taskId, RuntimeLease lease)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        AidRuntimeRunStep step = locked.step();
        AidMediaTask task = authoritativeTask(locked.run(), step);
        if (!Boolean.TRUE.equals(locked.run().getStopRequested())
                || !Objects.equals(step.getMediaTaskId(), taskId)
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                || !List.of("READY", "INFLIGHT").contains(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || task == null || !Objects.equals(task.getId(), taskId)
                || !List.of("CANCELLED", "CANCELED").contains(task.getStatus()))
            throw new ServiceException("Assistant durable task cancellation outcome changed");

        Date now = new Date();
        step.setStatus("CANCELLED_PRETASK");
        step.setErrorMessage("USER_STOP_BEFORE_PROVIDER_SUBMISSION");
        updateStep(step, lease.getOwner());
        consumeApproval(step, lease.getOwner(), now);
        locked.run().setStatus("STOPPED");
        locked.run().setNextAction(null);
        locked.run().setNextRetryAt(null);
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        updateRun(locked.run(), lease.getOwner());
        if (Objects.equals(locked.session().getActiveRuntimeRunId(), runId))
        {
            locked.session().setActiveRuntimeRunId(null);
            locked.session().setStateRevision(value(locked.session().getStateRevision()) + 1);
        }
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), step.getIdempotencyKey(),
                Map.of("status", "STOPPED", "reason", "USER_STOP_BEFORE_PROVIDER_SUBMISSION",
                        "mediaTaskId", String.valueOf(taskId)), now);
        return reconcileView(runId, locked.run().getStateRevision(), "STOPPED", true, null);
    }

    private AssistantLoopReconcileResult markDurableMainCancellationUnknown(Long runId, Long stepId,
                                                                              Long taskId, RuntimeLease lease,
                                                                              String code)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        if (!Boolean.TRUE.equals(locked.run().getStopRequested())
                || !Objects.equals(locked.step().getMediaTaskId(), taskId)
                || !"ASSISTANT_DECISION".equals(locked.step().getOperationKind()))
            throw new ServiceException("Assistant durable task cancellation state changed");
        if (!List.of("SUBMITTING", "ACCEPTED", "UNKNOWN")
                .contains(locked.step().getProviderAcceptanceState()))
        {
            locked.step().setStatus("RECONCILING");
            locked.step().setProviderAcceptanceState("UNKNOWN");
            locked.step().setErrorMessage(code);
            updateStep(locked.step(), lease.getOwner());
        }
        locked.run().setStatus("RECONCILING");
        locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
        locked.run().setNextRetryAt(plusMillis(new Date(), RETRY_MILLIS));
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        updateRun(locked.run(), lease.getOwner());
        return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(), true,
                locked.run().getNextRetryAt());
    }

    /**
     * Stop is allowed to cancel a durable READY iteration only before media-task creation.  The
     * runtime terminal state is written after the budget service has proved the reservation was
     * released.  An unknown release outcome stays on the recoverable coordinator route and is
     * never treated as a refund.
     */
    private AssistantLoopReconcileResult cancelUnsubmittedMain(Long runId, Long stepId, RuntimeLease lease)
    {
        MainCancellation stage = tx().execute(status -> markMainCancelledBeforeSubmission(runId, stepId, lease));
        if (stage == null) throw new ServiceException("Assistant cancellation stage is unavailable");
        if (stage.reservationId() == null)
            return tx().execute(status -> finishMainCancellation(runId, stepId, lease, false));
        try
        {
            BillingBudgetViews.ReservationView reservation = budgets.listReservations(stage.authorizationId())
                    .stream().filter(value -> Objects.equals(value.getId(), stage.reservationId()))
                    .findFirst().orElseThrow(() -> new ServiceException("Assistant reservation is unavailable"));
            requireMainReservation(stage, reservation);
            BillingBudgetViews.ReservationView released = budgets.releaseUnsubmittedReservation(
                    mainReleaseRequest(stage, reservation, lease));
            if (released != null && "RELEASED".equals(released.getState()))
                return tx().execute(status -> finishMainCancellation(runId, stepId, lease, true));
            scheduleMainCancellationRetry(runId, stepId, lease);
        }
        catch (RuntimeException uncertain)
        {
            log.info("assistant main reservation release requires reconciliation, runId={}, stepId={}, cause={}",
                    runId, stepId, uncertain.getClass().getSimpleName());
            scheduleMainCancellationRetry(runId, stepId, lease);
        }
        AidRuntimeRun current = requireRun(runId, false);
        return reconcileView(runId, value(current.getStateRevision()), current.getStatus(), false,
                current.getNextRetryAt());
    }

    private MainCancellation markMainCancelledBeforeSubmission(Long runId, Long stepId, RuntimeLease lease)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        AidRuntimeRunStep step = locked.step();
        if (!Boolean.TRUE.equals(locked.run().getStopRequested()) || step.getMediaTaskId() != null
                || !"ASSISTANT_DECISION".equals(step.getOperationKind()))
            throw new ServiceException("Assistant cancellation state changed");
        if ("READY".equals(step.getStatus()) && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
        {
            step.setStatus("CANCELLED_PRETASK");
            step.setErrorMessage("USER_STOP_BEFORE_SUBMISSION");
            updateStep(step, lease.getOwner());
        }
        else if (!("CANCELLED_PRETASK".equals(step.getStatus())
                && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState())
                || "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState())))
        {
            throw new ServiceException("Assistant cancellation state changed");
        }
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        if (approval == null || !Objects.equals(approval.getRunId(), runId)
                || !"0".equals(approval.getDelFlag()))
            throw new ServiceException("Assistant approval is unavailable");
        return new MainCancellation(runId, stepId, approval.getId(), step.getReservationId(),
                approval.getBillingAuthorizationId(), approval.getBillingAuthorizationRevision(),
                locked.run().getUserId(), step.getStableStepId(), step.getAttemptNo(),
                step.getOperationKind(), step.getIdempotencyKey());
    }

    private void requireMainReservation(MainCancellation stage, BillingBudgetViews.ReservationView reservation)
    {
        if (!Objects.equals(reservation.getAuthorizationId(), stage.authorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(), stage.authorizationRevision())
                || !Objects.equals(reservation.getRuntimeRunId(), stage.runId())
                || !Objects.equals(reservation.getRuntimeStepId(), stage.stepId())
                || !Objects.equals(reservation.getStableStepKey(), stage.stableStepKey())
                || !Objects.equals(reservation.getApprovalId(), stage.approvalId())
                || !Objects.equals(reservation.getAttemptNo(), stage.attemptNo())
                || !Objects.equals(reservation.getOperationKind(), stage.operationKind())
                || !Objects.equals(reservation.getLogicalCallKey(), stage.logicalCallKey()))
            throw new ServiceException("Assistant reservation identity changed");
    }

    private BillingBudgetRequests.ReleaseUnsubmittedReservationRequest mainReleaseRequest(
            MainCancellation stage, BillingBudgetViews.ReservationView reservation, RuntimeLease lease)
    {
        BillingBudgetRequests.ReleaseUnsubmittedReservationRequest request =
                new BillingBudgetRequests.ReleaseUnsubmittedReservationRequest();
        request.setAuthorizationId(reservation.getAuthorizationId());
        request.setAuthorizationRevision(reservation.getAuthorizationRevision());
        request.setReservationId(reservation.getId());
        request.setExpectedReservationRevision(reservation.getRevision());
        request.setPayerUserId(stage.userId());
        request.setRuntimeRunId(stage.runId());
        request.setRuntimeStepId(stage.stepId());
        request.setStableStepKey(stage.stableStepKey());
        request.setApprovalId(stage.approvalId());
        request.setAttemptNo(stage.attemptNo());
        request.setOperationKind(stage.operationKind());
        request.setLogicalCallKey(stage.logicalCallKey());
        request.setOperator(lease.getOwner());
        return request;
    }

    private AssistantLoopReconcileResult finishMainCancellation(Long runId, Long stepId, RuntimeLease lease,
                                                                 boolean releasedReservation)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        AidRuntimeRunStep step = locked.step();
        if (!Boolean.TRUE.equals(locked.run().getStopRequested())
                || !"CANCELLED_PRETASK".equals(step.getStatus()) || step.getMediaTaskId() != null
                || releasedReservation && !"RELEASED".equals(step.getProviderAcceptanceState())
                || !releasedReservation && step.getReservationId() != null)
            throw new ServiceException("Assistant cancellation outcome changed");
        Date now = new Date();
        consumeApproval(step, lease.getOwner(), now);
        locked.run().setStatus("STOPPED");
        locked.run().setNextAction(null);
        locked.run().setNextRetryAt(null);
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        updateRun(locked.run(), lease.getOwner());
        if (Objects.equals(locked.session().getActiveRuntimeRunId(), runId))
        {
            locked.session().setActiveRuntimeRunId(null);
            locked.session().setStateRevision(value(locked.session().getStateRevision()) + 1);
        }
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), step.getIdempotencyKey(),
                Map.of("status", "STOPPED", "reason", "USER_STOP_BEFORE_SUBMISSION"), now);
        return reconcileView(runId, locked.run().getStateRevision(), "STOPPED", true, null);
    }

    private void scheduleMainCancellationRetry(Long runId, Long stepId, RuntimeLease lease)
    {
        tx().executeWithoutResult(status -> {
            Locked locked = lockReceiptGraph(runId, stepId, lease);
            if (!Boolean.TRUE.equals(locked.run().getStopRequested()) || locked.step().getMediaTaskId() != null)
                throw new ServiceException("Assistant cancellation state changed");
            if (!"PAUSING".equals(locked.run().getStatus())) locked.run().setStatus("RECONCILING");
            locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
            locked.run().setNextRetryAt(plusMillis(new Date(), RETRY_MILLIS));
            updateRun(locked.run(), lease.getOwner());
        });
    }

    private void handleSubmissionNotEntered(SubmissionStage submission, RuntimeLease lease,
                                            MediaSubmissionNotEnteredException notEntered)
    {
        MainCancellation stage;
        try
        {
            stage = tx().execute(status -> markMainSubmissionNotEntered(submission, lease,
                    notEntered.getEvidenceCode()));
        }
        catch (RuntimeException evidenceChanged)
        {
            notEntered.addSuppressed(evidenceChanged);
            markSubmissionUnknown(submission, lease, notEntered);
            return;
        }
        if (stage == null)
        {
            markSubmissionUnknown(submission, lease, notEntered);
            return;
        }
        if (stage.reservationId() == null)
        {
            tx().execute(status -> finishMainSubmissionNotEntered(stage, lease, false,
                    notEntered.getEvidenceCode()));
            return;
        }
        try
        {
            BillingBudgetViews.ReservationView reservation = budgets.listReservations(stage.authorizationId())
                    .stream().filter(value -> Objects.equals(value.getId(), stage.reservationId()))
                    .findFirst().orElseThrow(() -> new ServiceException("Assistant reservation is unavailable"));
            requireMainReservation(stage, reservation);
            BillingBudgetViews.ReservationView released = budgets.releaseUnsubmittedReservation(
                    mainReleaseRequest(stage, reservation, lease));
            if (released != null && "RELEASED".equals(released.getState()))
            {
                tx().execute(status -> finishMainSubmissionNotEntered(stage, lease, true,
                        notEntered.getEvidenceCode()));
                return;
            }
        }
        catch (RuntimeException uncertainRelease)
        {
            notEntered.addSuppressed(uncertainRelease);
        }
        scheduleSubmissionNotEnteredRetry(submission.runId(), submission.stepId(), lease);
    }

    private AssistantLoopReconcileResult reconcileSubmissionNotEntered(Long runId, Long stepId,
                                                                         RuntimeLease lease)
    {
        try
        {
            AssistantLoopReconcileResult recovered = tx().execute(status -> {
                SubmissionRecovery recovery = lockSubmissionRecovery(runId, stepId, lease);
                if (recovery == null) return null;
                MainCancellation stage = recovery.stage();
                if (recovery.releasedReservation())
                    return finishMainSubmissionNotEntered(stage, lease, true, recovery.evidenceCode());
                if (stage.reservationId() == null)
                    return finishMainSubmissionNotEntered(stage, lease, false, recovery.evidenceCode());

                BillingBudgetViews.ReservationView reservation = budgets.listReservations(stage.authorizationId())
                        .stream().filter(value -> Objects.equals(value.getId(), stage.reservationId()))
                        .findFirst().orElseThrow(() -> new ServiceException("Assistant reservation is unavailable"));
                requireMainReservation(stage, reservation);
                if (recovery.legacyUnknown())
                    restoreLegacySubmissionNotEntered(recovery, reservation, lease);
                BillingBudgetViews.ReservationView released = budgets.releaseUnsubmittedReservation(
                        mainReleaseRequest(stage, reservation, lease));
                return released != null && "RELEASED".equals(released.getState())
                        ? finishMainSubmissionNotEntered(stage, lease, true, recovery.evidenceCode())
                        : null;
            });
            if (recovered != null) return recovered;
        }
        catch (RuntimeException uncertain)
        {
            log.info("assistant pre-task failure requires reconciliation, runId={}, stepId={}, cause={}",
                    runId, stepId, uncertain.getClass().getSimpleName());
        }
        scheduleSubmissionNotEnteredRetry(runId, stepId, lease);
        AidRuntimeRun current = requireRun(runId, false);
        return reconcileView(runId, value(current.getStateRevision()), current.getStatus(), false,
                current.getNextRetryAt());
    }

    private SubmissionRecovery lockSubmissionRecovery(Long runId, Long stepId, RuntimeLease lease)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        AidRuntimeRunStep step = locked.step();
        if (!List.of("RECONCILING", "PAUSING").contains(locked.run().getStatus())
                || !"WAIT_TASK_RECONCILIATION".equals(locked.run().getNextAction())
                || step.getMediaTaskId() != null || authoritativeTask(locked.run(), step) != null
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())) return null;
        boolean failedPreTask = "FAILED_PRETASK".equals(step.getStatus())
                && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState());
        boolean legacyUnknown = "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState())
                && List.of("MEDIA_TASK_IDENTITY_UNKNOWN", "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN")
                .contains(step.getErrorMessage());
        if (!failedPreTask && !legacyUnknown) return null;
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        if (approval == null || !Objects.equals(approval.getRunId(), runId)
                || !"0".equals(approval.getDelFlag()))
            throw new ServiceException("Assistant approval is unavailable");
        MainCancellation stage = new MainCancellation(runId, stepId, approval.getId(),
                step.getReservationId(), approval.getBillingAuthorizationId(),
                approval.getBillingAuthorizationRevision(), locked.run().getUserId(),
                step.getStableStepId(), step.getAttemptNo(), step.getOperationKind(),
                step.getIdempotencyKey());
        String evidenceCode = legacyUnknown ? TaskErrorCode.UNKNOWN.name()
                : safeEvidenceCode(step.getErrorMessage());
        return new SubmissionRecovery(stage, evidenceCode, legacyUnknown,
                "RELEASED".equals(step.getProviderAcceptanceState()));
    }

    private void restoreLegacySubmissionNotEntered(SubmissionRecovery recovery,
                                                    BillingBudgetViews.ReservationView reservation,
                                                    RuntimeLease lease)
    {
        BigDecimal reserved = safeAmount(reservation.getReservedAmount());
        BigDecimal walletFrozen = safeAmount(reservation.getWalletFrozenAmount());
        if (!"RELEASED".equals(reservation.getState())
                || !Objects.equals(reservation.getSettlementKey(), "release:" + reservation.getId())
                || safeAmount(reservation.getSettledAmount()).signum() != 0
                || safeAmount(reservation.getReleasedAmount()).compareTo(reserved) != 0
                || walletFrozen.signum() < 0
                || safeAmount(reservation.getWalletRefundedAmount()).compareTo(walletFrozen) != 0
                || reservation.getMediaTaskId() == null
                || mediaTasks.selectById(reservation.getMediaTaskId()) != null)
            throw new ServiceException("Assistant legacy pre-task proof is incomplete");
        Locked locked = lockReceiptGraph(recovery.stage().runId(), recovery.stage().stepId(), lease);
        if (!"RECONCILING".equals(locked.step().getStatus())
                || !"UNKNOWN".equals(locked.step().getProviderAcceptanceState())
                || !List.of("MEDIA_TASK_IDENTITY_UNKNOWN", "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN")
                .contains(locked.step().getErrorMessage())
                || locked.step().getMediaTaskId() != null
                || authoritativeTask(locked.run(), locked.step()) != null)
            throw new ServiceException("Assistant legacy pre-task state changed");
        locked.step().setStatus("FAILED_PRETASK");
        locked.step().setProviderAcceptanceState("NOT_SUBMITTED");
        locked.step().setErrorMessage(recovery.evidenceCode());
        updateStep(locked.step(), lease.getOwner());
    }

    private void scheduleSubmissionNotEnteredRetry(Long runId, Long stepId, RuntimeLease lease)
    {
        tx().executeWithoutResult(status -> {
            Locked locked = lockReceiptGraph(runId, stepId, lease);
            AidRuntimeRunStep step = locked.step();
            boolean failedPreTask = "FAILED_PRETASK".equals(step.getStatus())
                    && List.of("NOT_SUBMITTED", "RELEASED").contains(step.getProviderAcceptanceState());
            boolean unknown = "RECONCILING".equals(step.getStatus())
                    && "UNKNOWN".equals(step.getProviderAcceptanceState())
                    && List.of("MEDIA_TASK_IDENTITY_UNKNOWN", "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN")
                    .contains(step.getErrorMessage());
            if (step.getMediaTaskId() != null || authoritativeTask(locked.run(), step) != null
                    || !failedPreTask && !unknown)
                throw new ServiceException("Assistant pre-task recovery state changed");
            Date now = new Date();
            if (!"PAUSING".equals(locked.run().getStatus())) locked.run().setStatus("RECONCILING");
            locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
            locked.run().setNextRetryAt(plusMillis(now, RETRY_MILLIS));
            locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
            updateRun(locked.run(), lease.getOwner());
            appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                    locked.run().getStateRevision(), step.getIdempotencyKey(),
                    Map.of("status", locked.run().getStatus(),
                            "reason", safeEvidenceCode(step.getErrorMessage())), now);
        });
    }

    private MainCancellation markMainSubmissionNotEntered(SubmissionStage submission, RuntimeLease lease,
                                                            String evidenceCode)
    {
        Locked locked = lockReceiptGraph(submission.runId(), submission.stepId(), lease);
        AidRuntimeRunStep step = locked.step();
        if (!Objects.equals(locked.run().getFencingToken(), submission.runFencingToken())
                || !Objects.equals(step.getFencingToken(), submission.stepFencingToken())
                || step.getMediaTaskId() != null || authoritativeTask(locked.run(), step) != null
                || !"READY".equals(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
            throw new ServiceException("Assistant not-entered evidence changed");
        step.setStatus("FAILED_PRETASK");
        step.setErrorMessage(safeEvidenceCode(evidenceCode));
        updateStep(step, lease.getOwner());
        if (!"PAUSING".equals(locked.run().getStatus())) locked.run().setStatus("RECONCILING");
        locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(plusMillis(new Date(), RETRY_MILLIS));
        updateRun(locked.run(), lease.getOwner());
        AidRuntimeApproval approval = approvals.selectById(step.getApprovalId());
        if (approval == null || !Objects.equals(approval.getRunId(), submission.runId())
                || !"0".equals(approval.getDelFlag()))
            throw new ServiceException("Assistant approval is unavailable");
        return new MainCancellation(submission.runId(), submission.stepId(), approval.getId(),
                step.getReservationId(), approval.getBillingAuthorizationId(),
                approval.getBillingAuthorizationRevision(), locked.run().getUserId(),
                step.getStableStepId(), step.getAttemptNo(), step.getOperationKind(), step.getIdempotencyKey());
    }

    private AssistantLoopReconcileResult finishMainSubmissionNotEntered(MainCancellation stage,
                                                                         RuntimeLease lease,
                                                                         boolean releasedReservation,
                                                                         String evidenceCode)
    {
        Locked locked = lockReceiptGraph(stage.runId(), stage.stepId(), lease);
        AidRuntimeRunStep step = locked.step();
        if (!"FAILED_PRETASK".equals(step.getStatus()) || step.getMediaTaskId() != null
                || releasedReservation && !"RELEASED".equals(step.getProviderAcceptanceState())
                || !releasedReservation && step.getReservationId() != null)
            throw new ServiceException("Assistant not-entered outcome changed");
        Date now = new Date();
        consumeApproval(step, lease.getOwner(), now);
        String terminalStatus;
        String nextAction;
        if (Boolean.TRUE.equals(locked.run().getStopRequested()))
        {
            terminalStatus = "STOPPED";
            nextAction = null;
            if (Objects.equals(locked.session().getActiveRuntimeRunId(), stage.runId()))
            {
                locked.session().setActiveRuntimeRunId(null);
                locked.session().setStateRevision(value(locked.session().getStateRevision()) + 1);
            }
        }
        else if (AssistantBudgetAdmissionRejectedException.requiresNewRun(evidenceCode))
        {
            terminalStatus = "FAILED";
            nextAction = null;
            locked.run().setResumeStatus(null);
            locked.run().setPauseReason(null);
        }
        else if ("PAUSING".equals(locked.run().getStatus()))
        {
            terminalStatus = "PAUSED";
            nextAction = "CONTINUE_AGENT_REQUIRED";
            locked.run().setResumeStatus("WAITING_APPROVAL");
        }
        else
        {
            terminalStatus = "PAUSED";
            nextAction = "CONTINUE_AGENT_REQUIRED";
            locked.run().setResumeStatus("WAITING_APPROVAL");
        }
        locked.run().setStatus(terminalStatus);
        locked.run().setNextAction(nextAction);
        locked.run().setNextRetryAt(null);
        locked.run().setErrorMessage(safeEvidenceCode(evidenceCode));
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(stage.runId()),
                locked.run().getStateRevision(), step.getIdempotencyKey(),
                Map.of("status", terminalStatus, "reason", safeEvidenceCode(evidenceCode)), now);
        return reconcileView(stage.runId(), locked.run().getStateRevision(), terminalStatus, true, null);
    }

    private String safeEvidenceCode(String value)
    {
        return value != null && value.matches("^[A-Z][A-Z0-9_]{0,99}$")
                ? value : "MEDIA_SUBMISSION_NOT_ENTERED";
    }

    private AssistantLoopReconcileResult markMissingTaskUnknown(Long runId, Long stepId, RuntimeLease lease)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        locked.step().setStatus("RECONCILING");
        locked.step().setProviderAcceptanceState("UNKNOWN");
        locked.step().setErrorMessage("MEDIA_TASK_IDENTITY_UNKNOWN");
        updateStep(locked.step(), lease.getOwner());
        if (!"PAUSING".equals(locked.run().getStatus())) locked.run().setStatus("RECONCILING");
        locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(plusMillis(new Date(), RETRY_MILLIS));
        updateRun(locked.run(), lease.getOwner());
        return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(), true,
                locked.run().getNextRetryAt());
    }

    private AssistantLoopReconcileResult failTerminalTask(Long runId, Long stepId, AidMediaTask task,
                                                           RuntimeLease lease)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        boolean receiptOnly = !isActiveMutation(locked.session(), locked.run(), lease, new Date());
        if (!receiptOnly && scheduleModelRecovery(locked, task, lease,
                AssistantModelRecovery.failedTaskReason(task.getErrorDetailJson())))
            return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(),
                    true, locked.run().getNextRetryAt());
        var streamFailure = TaskErrorSnapshot.read(task.getErrorDetailJson());
        String interruption = TaskErrorCode.UPSTREAM_STREAM_INTERRUPTED.name();
        boolean canRecoverStream = !receiptOnly && "FAILED".equals(task.getStatus())
                && "TEXT".equals(task.getMediaType()) && "MAIN_AGENT".equals(locked.step().getStepKind())
                && streamFailure != null && interruption.equals(streamFailure.getErrorCode())
                && steps.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, runId)
                    .eq(AidRuntimeRunStep::getErrorMessage, interruption)) == 0;
        locked.step().setStatus("FAILED");
        locked.step().setErrorMessage(canRecoverStream ? interruption
                : Objects.toString(task.getErrorMessage(), "MEDIA_TASK_FAILED"));
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        if (receiptOnly) settleLateReceiptRun(locked.run(), locked.step().getErrorMessage());
        else if (canRecoverStream)
        {
            // A fresh continuation still uses the standard quotation and run budget.
            locked.run().setStatus("WAITING_APPROVAL");
            locked.run().setErrorMessage(null);
            locked.run().setNextAction("CONTINUE_AGENT_REQUIRED");
        }
        else
        {
            locked.run().setStatus("CANCELLED".equals(task.getStatus()) ? "STOPPED" : "FAILED");
            locked.run().setErrorMessage(locked.step().getErrorMessage());
            locked.run().setNextAction(null);
        }
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), locked.step().getIdempotencyKey(),
                Map.of("status", locked.run().getStatus(), "reason", canRecoverStream ? interruption : "MEDIA_TASK_FAILED"), new Date());
        return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(), true, null);
    }

    private AssistantLoopReconcileResult pauseStaleDecision(Long runId, Long stepId, RuntimeLease lease,
            com.aid.assistant.error.AssistantServiceException rejected)
    {
        if (!"ASSISTANT_CONTEXT_STALE".equals(rejected.getErrorCode())) throw rejected;
        return tx().execute(status -> {
            AidRuntimeRunStep snapshot = requireStep(stepId, runId, false);
            if (!"DECISION_READY".equals(snapshot.getStatus())
                    || !planExecution.hasNoOperationEvidence(new AssistantPlanExecutionCommand(
                            runId, stepId, snapshot.getOutputJson(), lease))) throw rejected;
            Locked locked = lockReceiptGraph(runId, stepId, lease);
            requireActiveMutation(locked.session(), locked.run(), lease);
            requireDecisionReady(locked.step());
            AidMediaTask task = authoritativeTask(locked.run(), locked.step());
            if (task == null || !"SUCCEEDED".equals(task.getStatus())) throw rejected;
            requireTaskIdentity(locked.run(), locked.step(), task);
            // The paid model response is retained. Only its unapplied, stale decision is retired.
            locked.step().setStatus("FAILED");
            locked.step().setErrorMessage("ASSISTANT_CONTEXT_STALE");
            updateStep(locked.step(), lease.getOwner());
            consumeApproval(locked.step(), lease.getOwner(), new Date());
            locked.run().setStatus("PAUSED");
            locked.run().setPauseReason("CONTEXT_CHANGED");
            locked.run().setResumeStatus("WAITING_APPROVAL");
            locked.run().setNextAction("REPLANNING_REQUIRED");
            locked.run().setErrorMessage("ASSISTANT_CONTEXT_STALE");
            locked.run().setNextRetryAt(null);
            locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
            updateRun(locked.run(), lease.getOwner());
            appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                    locked.run().getStateRevision(), locked.step().getIdempotencyKey(),
                    Map.of("status", "PAUSED", "reason", "ASSISTANT_CONTEXT_STALE",
                            "nextAction", "REPLANNING_REQUIRED"), new Date());
            return reconcileView(runId, locked.run().getStateRevision(), "PAUSED", true, null);
        });
    }

    private AssistantLoopReconcileResult failInvalidModelOutput(Long runId, Long stepId, AidMediaTask task,
                                                                 RuntimeLease lease, RuntimeException cause)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        if (isActiveMutation(locked.session(), locked.run(), lease, new Date())
                && scheduleModelRecovery(locked, task, lease, modelOutputFailureCode(cause)))
            return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(),
                    true, locked.run().getNextRetryAt());
        return failRejectedDecision(runId, stepId, task, lease, modelOutputFailureCode(cause), cause, null);
    }

    static String modelOutputFailureCode(RuntimeException cause)
    {
        if (cause instanceof AssistantEmptyReplyException) return AssistantEmptyReplyException.CODE;
        if (cause instanceof AssistantOutputContractException) return AssistantOutputContractException.CODE;
        if(cause instanceof NativeHostPlanInputContract.UnresolvedDependencyException)return "MODEL_PLAN_DEPENDENCY_INVALID";
        if(cause instanceof NativeHostPlanInputContract.ReviewProducerMissingException)return "MODEL_PLAN_REVIEW_UNAVAILABLE";
        if (cause instanceof NativeDecisionValidationRejectedException rejected
                && rejected.feedback().reason() == AssistantDecisionValidationFeedback.Reason.NATIVE_FUNCTION_NAME_NOT_EXPOSED)
            return "MODEL_FUNCTION_NOT_EXPOSED";
        return cause instanceof NativeDecisionValidationRejectedException rejected
                && rejected.feedback().reason() == AssistantDecisionValidationFeedback.Reason.PLAN_DEPENDENCY_NOT_DECLARED
                ? "MODEL_PLAN_DEPENDENCY_INVALID" : "MODEL_OUTPUT_INVALID";
    }

    private boolean scheduleModelRecovery(Locked locked, AidMediaTask task, RuntimeLease lease,
                                           String reason)
    {
        if (reason == null || Boolean.TRUE.equals(locked.run().getStopRequested())
                || (!AssistantAutomaticTextExecution.enabled(JSON.parseObject(locked.run().getRequestJson()))
                && locked.run().getBudgetAuthorizationId() == null)
                || !"MAIN_AGENT".equals(locked.step().getStepKind())
                || List.of("FAILED", "SUCCEEDED").contains(locked.step().getStatus())) return false;
        requireTaskIdentity(locked.run(), locked.step(), task);
        boolean noOperations;
        if ("DECISION_READY".equals(locked.step().getStatus()))
        {
            noOperations = planExecution.hasNoOperationEvidence(new AssistantPlanExecutionCommand(
                    locked.run().getId(), locked.step().getId(), locked.step().getOutputJson(), lease));
        }
        else
        {
            Long failedTaskId = "FAILED".equals(task.getStatus()) ? task.getId() : null;
            noOperations = planExecution.hasNoOperationEvidence(
                    new com.aid.runtime.assistant.execution.AssistantRejectedDecisionEvidenceCommand(
                            locked.run().getId(), locked.step().getId(), lease, failedTaskId));
        }
        if (!noOperations) return false;
        String request = AssistantModelRecovery.schedule(locked.run().getRequestJson(), locked.step().getId(), reason);
        if (request == null) return false;
        Date now = new Date();
        locked.step().setStatus("FAILED");
        locked.step().setErrorMessage(AssistantModelRecovery.stepMarker(reason));
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), now);
        locked.run().setRequestJson(request);
        locked.run().setStatus("WAITING_APPROVAL");
        locked.run().setNextAction("CONTINUE_AGENT_REQUIRED");
        locked.run().setErrorMessage(null);
        locked.run().setNextRetryAt(plusMillis(now, 1000L << (AssistantModelRecovery.attempt(request) - 1)));
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        updateRun(locked.run(), lease.getOwner());
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(locked.run().getId()),
                locked.run().getStateRevision(), locked.step().getIdempotencyKey(),
                Map.of("status", "WAITING_APPROVAL", "reason", reason, "retryAttempt",
                        AssistantModelRecovery.attempt(request), "maxRetries", AssistantModelRecovery.MAX_RETRIES), now);
        return true;
    }

    private AssistantLoopReconcileResult failRejectedPlanDecision(Long runId, Long stepId,
                                                                   AidMediaTask expectedTask,
                                                                   RuntimeLease lease,
                                                                   AssistantPlanDecisionRejectedException rejected,
                                                                   AssistantLoopDecision parsedDecision)
    {
        return tx().execute(status -> {
            AidRuntimeRun currentRun = requireRun(runId, false);
            AidRuntimeRunStep currentStep = requireStep(stepId, runId, false);
            AssistantPlanExecutionCommand command = new AssistantPlanExecutionCommand(runId, stepId,
                    currentStep.getOutputJson(), lease);
            AidMediaTask currentTask = authoritativeTask(currentRun, currentStep);
            if (expectedTask != null && (currentTask == null
                    || !Objects.equals(expectedTask.getId(), currentTask.getId())))
                throw new ServiceException("Assistant task identity changed");
            if (currentTask == null || !"SUCCEEDED".equals(currentTask.getStatus()))
                throw new ServiceException("Assistant decision receipt is not terminal");
            AidRuntimeApproval approval = requirePersistedIterationApproval(currentRun, currentStep);
            JSONObject scope = approvalScope(approval);
            AssistantLoopDecision decision = parsedDecision == null
                    ? persistedOperationDecision(currentRun, currentStep, currentTask, approval) : parsedDecision;
            if (!Objects.equals(decision.getObservedRunRevision(), scope.getLong("acceptedRunRevision"))
                    || !Objects.equals(decision.getObservedPlanRevision(), value(currentRun.getPlanRevision())))
                throw new ServiceException("Assistant decision receipt is invalid");

            if (!planExecution.hasNoOperationEvidence(command))
                throw new ServiceException("Assistant operation evidence changed");
            if ("AUTO".equals(budgetPolicies.executionMode(currentRun))
                    && !Boolean.TRUE.equals(currentRun.getStopRequested())) {
                Locked locked = lockReceiptGraph(runId, stepId, lease);
                if (scheduleModelRecovery(locked, currentTask, lease, "MODEL_PLAN_INVALID"))
                    return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(),
                            true, locked.run().getNextRetryAt());
            }
            return failRejectedDecision(runId, stepId, currentTask, lease,
                    rejected.getReason().name(), rejected, decision);
        });
    }

    private AssistantLoopReconcileResult failRejectedDecision(Long runId, Long stepId, AidMediaTask task,
                                                             RuntimeLease lease, String errorCode,
                                                             RuntimeException cause, AssistantLoopDecision decision)
    {
        Locked locked = lockReceiptGraph(runId, stepId, lease);
        boolean receiptOnly = !isActiveMutation(locked.session(), locked.run(), lease, new Date());
        if ("SUCCEEDED".equals(locked.step().getStatus()) || "FAILED".equals(locked.step().getStatus()))
            return reconcileView(runId, value(locked.run().getStateRevision()), locked.run().getStatus(), true, null);
        if (decision != null)
        {
            requireDecisionReady(locked.step());
            requireTaskIdentity(locked.run(), locked.step(), task);
            if (!receiptOnly && supersedeForQueuedMessage(locked, lease.getOwner(), decision.getDecisionId()))
                return reconcileView(runId, value(locked.run().getStateRevision()), locked.run().getStatus(), true, null);
        }
        locked.step().setStatus("FAILED");
        locked.step().setProviderAcceptanceState("ACCEPTED");
        locked.step().setErrorMessage(errorCode);
        if (task != null) locked.step().setMediaTaskId(task.getId());
        updateStep(locked.step(), lease.getOwner());
        consumeApproval(locked.step(), lease.getOwner(), new Date());
        if (receiptOnly) settleLateReceiptRun(locked.run(), errorCode);
        else
        {
            locked.run().setStatus("FAILED");
            locked.run().setNextAction(null);
            locked.run().setErrorMessage(errorCode);
        }
        locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
        locked.run().setNextRetryAt(null);
        updateRun(locked.run(), lease.getOwner());
        Map<String,Object> rejectionEvent = new java.util.LinkedHashMap<>();
        rejectionEvent.put("status", locked.run().getStatus());
        rejectionEvent.put("reason", errorCode);
        if (cause instanceof NativeDecisionValidationRejectedException rejected)
            rejectionEvent.put("validationFeedback", JSON.parseObject(rejected.feedback().canonicalJson()));
        appendEvent(locked.session(), locked.run(), "run.state_changed", String.valueOf(runId),
                locked.run().getStateRevision(), locked.step().getIdempotencyKey(),
                rejectionEvent, new Date());
        String rejectionReason = cause instanceof NativeDecisionValidationRejectedException nativeRejected
                ? nativeRejected.feedback().reason().name()
                : cause instanceof AssistantDecisionCorrectionReceiptService.NativeClaimSourceRejectedException nativeRejected
                ? nativeRejected.rejectionStage().name() + ":" + nativeRejected.rejectionCode().name()
                : cause instanceof AssistantCompletionDecisionRejectedException rejected
                ? rejected.getReason().name()
                : cause instanceof AssistantPlanDecisionRejectedException rejected
                    ? rejected.getReason().name() : cause.getClass().getSimpleName();
        log.info("assistant decision rejected, runId={}, stepId={}, code={}, reason={}", runId, stepId,
                errorCode, rejectionReason);
        return reconcileView(runId, locked.run().getStateRevision(), locked.run().getStatus(), true, null);
    }

    private void markSubmissionUnknown(SubmissionStage stage, RuntimeLease lease, RuntimeException cause)
    {
        try
        {
            tx().executeWithoutResult(status -> {
                Locked locked = lockReceiptGraph(stage.runId(), stage.stepId(), lease);
                locked.step().setStatus("RECONCILING");
                locked.step().setProviderAcceptanceState("UNKNOWN");
                locked.step().setErrorMessage("MEDIA_TASK_CREATION_UNKNOWN");
                updateStep(locked.step(), lease.getOwner());
                if (!"PAUSING".equals(locked.run().getStatus())) locked.run().setStatus("RECONCILING");
                locked.run().setNextAction("WAIT_TASK_RECONCILIATION");
                locked.run().setStateRevision(value(locked.run().getStateRevision()) + 1);
                locked.run().setNextRetryAt(plusMillis(new Date(), RETRY_MILLIS));
                updateRun(locked.run(), lease.getOwner());
            });
        }
        catch (RuntimeException persistenceFailure)
        {
            cause.addSuppressed(persistenceFailure);
        }
    }

    private void scheduleRetry(Long runId, RuntimeLease lease, Date retryAt)
    {
        AidRuntimeRun discovered = requireRun(runId, false);
        AidAssistantSession session = requireSession(discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(runId, true);
        requireReceiptMutation(session, run, lease);
        run.setNextRetryAt(retryAt);
        updateRun(run, lease.getOwner());
    }

    private void archiveLateDecision(AidAssistantSession session, AidRuntimeRun run, AidRuntimeRunStep step,
                                     AssistantLoopDecision decision, String actor, Date now)
    {
        step.setStatus("SUCCEEDED");
        step.setProviderAcceptanceState("ACCEPTED");
        step.setOutputJson(decision.getDecisionJson());
        step.setErrorMessage(null);
        updateStep(step, actor);
        consumeApproval(step, actor, now);
        settleLateReceiptRun(run, null);
        run.setStateRevision(value(run.getStateRevision()) + 1);
        run.setNextRetryAt(null);
        updateRun(run, actor);
        appendEvent(session, run, "decision.receipt_archived", String.valueOf(step.getId()),
                value(step.getFencingToken()), decision.getDecisionId(),
                Map.of("action", decision.getAction(), "runStatus", run.getStatus()), now);
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), decision.getDecisionId(),
                Map.of("status", run.getStatus(),
                        "nextAction", Objects.toString(run.getNextAction(), "")), now);
    }

    private void settleLateReceiptRun(AidRuntimeRun run, String errorCode)
    {
        if (Boolean.TRUE.equals(run.getStopRequested()))
        {
            run.setStatus("STOPPED");
            run.setNextAction(null);
        }
        else
        {
            run.setStatus("PAUSED");
            run.setResumeStatus("WAITING_APPROVAL");
            run.setNextAction("CONTINUE_AGENT_REQUIRED");
        }
        run.setErrorMessage(errorCode);
    }

    private void requireLease(AidRuntimeRun run, RuntimeLease lease, Date now)
    {
        requireFence(run, lease);
        if (run.getLeaseUntil() == null || !run.getLeaseUntil().after(now)
                || lease.getLeaseUntil() == null || lease.getLeaseUntil().before(now))
            throw new ServiceException("Assistant runtime lease expired");
    }

    private void requireActiveMutation(AidAssistantSession session, AidRuntimeRun run, RuntimeLease lease)
    {
        if (!isActiveMutation(session, run, lease, new Date()))
            throw new ServiceException("Assistant run state changed");
    }

    /** A claimed Provider task may settle after pause/stop, but no new model action may be applied. */
    private void requireReceiptMutation(AidAssistantSession session, AidRuntimeRun run, RuntimeLease lease)
    {
        Date now = new Date();
        requireLease(run, lease, now);
        if (!Objects.equals(session.getId(), run.getSessionId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
            throw new ServiceException("Assistant run state changed");
        if (isActiveMutation(session, run, lease, now)) return;
        if (!List.of("PAUSING", "RECONCILING").contains(run.getStatus())
                || !"WAIT_TASK_RECONCILIATION".equals(run.getNextAction()))
            throw new ServiceException("Assistant receipt state changed");
    }

    private boolean isActiveMutation(AidAssistantSession session, AidRuntimeRun run,
                                     RuntimeLease lease, Date now)
    {
        try { requireLease(run, lease, now); }
        catch (ServiceException inactive) { return false; }
        return Objects.equals(session.getId(), run.getSessionId())
                && Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                && !Boolean.TRUE.equals(run.getStopRequested())
                && !List.of("PAUSED", "PAUSING", "STOPPING", "STOPPED", "SUCCEEDED", "FAILED")
                .contains(run.getStatus());
    }

    private void requireTaskIdentity(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task)
    {
        if (task == null || !Objects.equals(task.getUserId(), run.getUserId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !"ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey()))
            throw new ServiceException("MEDIA_TASK_RECEIPT_INVALID");
    }

    private String receiptActor(RuntimeLease lease)
    {
        return lease == null || StrUtil.isBlank(lease.getOwner()) ? "assistant-receipt" : lease.getOwner();
    }

    private void requireFence(AidRuntimeRun run, RuntimeLease lease)
    {
        if (lease == null || StrUtil.isBlank(lease.getOwner()) || lease.getFencingToken() == null
                || !Objects.equals(run.getLeaseOwner(), lease.getOwner())
                || !Objects.equals(run.getFencingToken(), lease.getFencingToken()))
            throw new ServiceException("Assistant runtime fence changed");
    }

    private AidAssistantSession requireSession(Long id, boolean lock)
    {
        AidAssistantSession value = lock ? sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, id).eq(AidAssistantSession::getSessionType, "ASSISTANT")
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE")) : sessions.selectById(id);
        if (value == null || !"ASSISTANT".equals(value.getSessionType()) || !"0".equals(value.getDelFlag()))
            throw new ServiceException("Assistant session does not exist");
        return value;
    }

    private AidRuntimeRun requireRun(Long id, boolean lock)
    {
        AidRuntimeRun value = lock ? runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, id).eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"))
                : runs.selectById(id);
        if (value == null || !"0".equals(value.getDelFlag()))
            throw new ServiceException("Assistant run does not exist");
        return value;
    }

    private AidRuntimeRunStep requireStep(Long id, Long runId, boolean lock)
    {
        AidRuntimeRunStep value = lock ? steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, id).eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE")) : steps.selectById(id);
        if (value == null || !Objects.equals(value.getRunId(), runId) || !"0".equals(value.getDelFlag()))
            throw new ServiceException("Assistant step does not exist");
        return value;
    }

    private AidRuntimeRunStep latestMainAgentStep(Long runId)
    {
        return steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getDelFlag, "0").orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 1"));
    }

    private JSONObject approvalScope(AidRuntimeApproval approval)
    {
        try
        {
            JSONObject value = approval == null ? null : JSON.parseObject(approval.getScopeJson());
            if (value == null || !"assistant-approval-scope/v1".equals(value.getString("schemaVersion")))
                throw new IllegalArgumentException();
            return value;
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("Assistant approval scope is invalid");
        }
    }

    private boolean isTerminal(String status)
    {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private void appendEvent(AidAssistantSession session, AidRuntimeRun run, String type,
                             String entityId, Long revision, String causationId, Object payload, Date now)
    {
        session.setLastEventSeq(value(session.getLastEventSeq()) + 1);
        updateSession(session, run.getUserId(), now);
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(run.getId());
        event.setSeq(session.getLastEventSeq());
        event.setEventType(type);
        event.setEntityId(entityId);
        event.setEntityRevision(revision);
        event.setCausationId(causationId);
        event.setPayloadJson(payload == null ? null : JSON.toJSONString(payload));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        events.insert(event);
        realtimeEventPublisher.publishAfterCommit(event);
    }

    private void updateRun(AidRuntimeRun run, String actor)
    {
        run.setUpdateBy(actor);
        run.setUpdateTime(new Date());
        if ("SUCCEEDED".equals(run.getStatus()))
        {
            // The graph is locked by the caller. Clear only the current run error in the
            // same terminal write; failure steps and events remain the recovery history.
            // updateById omits null fields, so setting the entity to null is insufficient.
            run.setErrorMessage(null);
            runs.update(run, new LambdaUpdateWrapper<AidRuntimeRun>()
                    .eq(AidRuntimeRun::getId, run.getId())
                    .set(AidRuntimeRun::getErrorMessage, null));
        }
        else
        {
            runs.updateById(run);
        }
    }

    private void updateStep(AidRuntimeRunStep step, String actor)
    {
        AssistantRuntimeStepLifecycle.clearTerminalLease(step);
        step.setUpdateBy(actor);
        step.setUpdateTime(new Date());
        steps.updateById(step);
    }

    private void updateSession(AidAssistantSession session, Long actor, Date now)
    {
        session.setUpdateBy(String.valueOf(actor));
        session.setUpdateTime(now);
        sessions.updateById(session);
    }

    private void setCreateAudit(com.aid.common.core.domain.BaseEntity entity, Long actor, Date now)
    {
        entity.setCreateBy(String.valueOf(actor));
        entity.setCreateTime(now);
        entity.setUpdateBy(String.valueOf(actor));
        entity.setUpdateTime(now);
    }

    private AssistantLoopReconcileResult reconcileView(Long runId, Long revision, String status,
                                                        boolean recovered, Date nextRetryAt)
    {
        AssistantLoopReconcileResult result = new AssistantLoopReconcileResult();
        result.setRunId(runId);
        result.setStateRevision(revision);
        result.setStatus(status);
        result.setRecovered(recovered);
        result.setNextRetryAt(nextRetryAt);
        return result;
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private BigDecimal safeAmount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private Date plusMillis(Date base, long millis) { return new Date(base.getTime() + millis); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record SubmissionStage(Long sessionId, Long runId, Long stepId, Long runRevision,
                                   Long runFencingToken, Long stepFencingToken,
                                   MediaTextGenerateRequest request, boolean contextRejected) { }
    private record MainCancellation(Long runId, Long stepId, Long approvalId, Long reservationId,
                                    Long authorizationId, Long authorizationRevision, Long userId,
                                    String stableStepKey, Integer attemptNo, String operationKind,
                                    String logicalCallKey) { }
    private record SubmissionRecovery(MainCancellation stage, String evidenceCode,
                                      boolean legacyUnknown, boolean releasedReservation) { }
    private record Locked(AidAssistantSession session, AidRuntimeRun run, AidRuntimeRunStep step) { }
}
