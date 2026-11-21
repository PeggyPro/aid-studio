package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.budget.AssistantBudgetOperationAdmission;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.budget.AssistantBudgetAdmissionRejectedException;
import com.aid.assistant.creative.dto.CreativeMaterializationRequests;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.service.CreativeAssistantReferencePromptAdapter;
import com.aid.assistant.creative.service.CreativeArtifactMaterializer;
import com.aid.assistant.creative.tool.CreativeRuntimeToolProvider;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.realtime.AssistantRealtimeEventPublisher;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.service.AssistantReferenceContentService;
import com.aid.assistant.skill.AssistantFixedSkillInvocation;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.assistant.skill.AssistantSkillInvocationBridge;
import com.aid.assistant.skill.AssistantSkillReferenceAdapter;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.media.admission.MediaTaskCancellationResult;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.media.service.TaskCompletionService;
import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;
import com.aid.runtime.assistant.DefaultAssistantLoopExecutor;
import com.aid.runtime.assistant.AssistantModelRecovery;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.assistant.NativeAssistantToolBatch;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionPlanResolver.BoundOperation;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionScopeCodec.Encoded;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionScopeCodec.Persisted;
import com.aid.runtime.assistant.recovery.AssistantRuntimeLeaseService;
import com.aid.runtime.assistant.recovery.AssistantRuntimeDueRunWakeup;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeBudgetPolicyMapper;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.service.RuntimeToolOutputValidationException;
import com.aid.runtime.tool.RuntimeToolBillingIntent;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.runtime.tool.RuntimeToolResult;
import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.vo.SkillInvocationVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable child-operation state machine. It never mutates the main Agent step or run outcome. */
@Slf4j
@Service
public class AssistantPlanExecutionServiceImpl implements AssistantPlanExecutionService
{
    private static final List<String> ACTIVE_RUN = List.of("READY", "RUNNING", "PAUSING", "RECONCILING");
    private static final List<String> TERMINAL_STEP = List.of("SUCCEEDED", "FAILED", "CANCELLED",
            "FAILED_PRETASK", "CANCELLED_PRETASK");
    private static final long STEP_LEASE_MILLIS = 60_000L;

    @Resource private PlatformTransactionManager transactionManager;
    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimePlanMapper plans;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeBudgetPolicyMapper policies;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidRuntimeEventMapper events;
    @Resource private AssistantRealtimeEventPublisher realtimeEventPublisher;
    @Resource private AssistantContextLimits contextLimits;
    @Resource private AidMediaTaskMapper mediaTasks;
    @Resource private TaskCompletionService taskCompletionService;
    @Resource private AssistantRuntimeContextManifestService manifests;
    @Resource private AssistantReferenceContentService referenceContents;
    @Resource private AssistantPlanExecutionPlanResolver planResolver;
    @Resource private AssistantPlanExecutionScopeCodec scopeCodec;
    @Resource private com.aid.assistant.service.AssistantConversationMemoryContext conversationMemoryContext;
    @Resource private AssistantBudgetPolicyService budgetPolicies;
    @Resource private AssistantBillingBudgetService billingBudgets;
    @Resource private AssistantRuntimeLeaseService leaseService;
    @Resource private AssistantRuntimeDueRunWakeup dueRunWakeup;
    @Resource private RuntimeToolRegistry toolRegistry;
    @Resource private AssistantSkillInvocationBridge skillBridge;
    @Resource private CreativeArtifactMaterializer artifactMaterializer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<AssistantMediaTaskTargetVerifier> mediaTargetVerifiers = List.of();

    @Override
    public AssistantPlanExecutionResult applyDecision(AssistantPlanExecutionCommand command)
    {
        requireCommand(command);
        AssistantPlanExecutionDecision decision;
        try
        {
            decision = decisionParser().parse(command.operationDecisionJson());
        }
        catch (RuntimeException invalidDecision)
        {
            boolean unsubmitted;
            try
            {
                unsubmitted = hasNoOperationEvidence(command);
            }
            catch (RuntimeException uncertain)
            {
                // A malformed immutable decision is definitive only while the locked durable
                // graph proves that no child operation was ever entered. Preserve the original
                // recovery error when the graph, lease, or an approval receipt is uncertain.
                invalidDecision.addSuppressed(uncertain);
                throw invalidDecision;
            }
            if (!unsubmitted) throw invalidDecision;
            throw new AssistantPlanDecisionRejectedException(
                    AssistantPlanDecisionRejectedException.Reason.OPERATION_DECISION_INVALID,
                    invalidDecision);
        }
        AssistantPlanExecutionResult stoppedPreparation = tx().execute(status ->
                cancelStoppedPreparation(command, decision));
        if (stoppedPreparation != null) return stoppedPreparation;
        AssistantPlanExecutionResult rejectedPreparation = tx().execute(status -> preparationTerminalReplay(command, decision));
        if (rejectedPreparation != null) return rejectedPreparation;
        Dispatch existing = tx().execute(status -> existingDispatch(command, decision));
        if (existing != null) return executeOrReplay(command, existing);
        Preflight preflight;
        try
        {
            preflight = preflight(command, decision);
        }
        catch (AssistantPlanDecisionRejectedException rejected)
        {
            final boolean unsubmitted;
            try
            {
                unsubmitted = hasNoOperationEvidence(command);
            }
            catch (RuntimeException uncertain)
            {
                uncertain.addSuppressed(rejected);
                throw uncertain;
            }
            if (!unsubmitted)
            {
                if (rejected.getCause() instanceof RuntimeException original) throw original;
                throw new ServiceException("OPERATION_EVIDENCE_UNKNOWN");
            }
            throw rejected;
        }
        PreparationSlot slot;
        try
        {
            slot = tx().execute(status -> ensurePreparing(preflight));
        }
        catch (RuntimeException raced)
        {
            Dispatch winner = tx().execute(status -> existingDispatch(command, decision));
            if (winner != null) return executeOrReplay(command, winner);
            if (!(raced instanceof DuplicateKeyException)) throw raced;
            slot = tx().execute(status -> ensurePreparing(preflight));
        }
        if (slot == null || slot.stepId() == null) throw new ServiceException("OPERATION_PREPARATION_FAILED");

        RuntimeToolPreparedInvocation prepared = null;
        if (!slot.finalized() && decision.isTool())
        {
            try
            {
                RuntimeToolInvocation invocation = toolInvocation(preflight.run(), preflight.session(), decision,
                        preflight.bound(), preflight.manifest(), preflight.operationScope(), command.lease(),
                        slot.stepId(), null, null);
                prepared = toolRegistry.prepare(invocation);
                requirePreparedIdentity(prepared, preflight, slot.stepId());
                requireNoRepeatedCompletedOperation(preflight.run(), prepared);
            }
            catch (RuntimeToolPreparationRejectedException rejected)
            {
                Long rejectedStepId = slot.stepId();
                return tx().execute(status -> rejectPreparation(preflight, rejectedStepId, rejected.getMessage(),
                        false, rejected.getCompletedStepId()));
            }
        }
        RuntimeToolPreparedInvocation preparedSnapshot = prepared;
        Long preparedStepId = slot.stepId();
        Dispatch dispatch;
        try
        {
            dispatch = tx().execute(status -> persistPrepared(preflight, preparedStepId, preparedSnapshot));
        }
        catch (RuntimeToolPreparationRejectedException rejected)
        {
            return tx().execute(status -> rejectPreparation(preflight, preparedStepId, rejected.getMessage()));
        }
        catch (AssistantBudgetAdmissionRejectedException rejected)
        {
            return tx().execute(status -> rejectPreparation(preflight, preparedStepId,
                    rejected.getRuntimeErrorCode(), rejected.requiresNewRun()));
        }
        catch (RuntimeException raced)
        {
            dispatch = tx().execute(status -> existingDispatch(command, decision));
            if (dispatch == null) throw raced;
        }
        if (dispatch == null) throw new ServiceException("OPERATION_PERSISTENCE_FAILED");
        return executeOrReplay(command, dispatch);
    }

    @Override
    public boolean hasNoOperationEvidence(AssistantPlanExecutionCommand command)
    {
        requireCommand(command);
        return Boolean.TRUE.equals(tx().execute(status -> decisionHasNoOperationEvidence(command)));
    }

    @Override
    public boolean hasNoOperationEvidence(AssistantRejectedDecisionEvidenceCommand command)
    {
        require(command != null && command.runId() != null && command.mainAgentStepId() != null
                && command.lease() != null, "ASSISTANT_REJECTED_DECISION_IDENTITY_INVALID");
        return Boolean.TRUE.equals(tx().execute(status -> rejectedDecisionHasNoOperationEvidence(command)));
    }

    @Override
    public AssistantDecisionValidationFeedback requireRejectedDecisionFeedback(
            AssistantPlanExecutionCommand command,
            AssistantPlanDecisionRejectedException.Reason expectedReason)
    {
        requireCommand(command);
        require(expectedReason == AssistantPlanDecisionRejectedException.Reason.PLAN_INPUT_BINDINGS_UNRESOLVED,
                "ASSISTANT_DECISION_CORRECTION_REASON_UNSUPPORTED");
        return tx().execute(status -> {
            AidRuntimeRun snapshot = requireRun(command.runId(), false);
            AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
            AidRuntimeRun run = requireRun(command.runId(), true);
            requireActiveMutation(session, run, command.lease(), new Date());
            AidRuntimeRunStep main = requireMainStep(command.mainAgentStepId(), run,
                    command.mainDecisionJson(), true);
            require(main.getId() != null && main.getStepIndex() != null
                            && operationGraphHasNoEvidence(run, main),
                    "ASSISTANT_DECISION_CORRECTION_OPERATION_EXISTS");

            AssistantPlanExecutionDecision decision;
            try
            {
                decision = decisionParser().parse(command.operationDecisionJson());
            }
            catch (RuntimeException changed)
            {
                throw new ServiceException("ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
            }
            requireSubmittedIterationRevision(run, main, command, decision);
            AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                    manifests.requirePinned(run.getUserId(), session, run);
            requireExpectedContextRevisions(decision, manifest, run, session);
            try
            {
                planResolver.resolve(run, requirePlan(run.getId(), decision.observedPlanRevision(), true),
                        decision);
            }
            catch (ServiceException rejected)
            {
                AssistantPlanDecisionRejectedException.Reason actual =
                        AssistantPlanDecisionRejectedException.Reason.fromCode(rejected.getMessage());
                require(actual == expectedReason,
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                return AssistantDecisionValidationFeedback.planInputsNotReady(
                        DefaultAssistantLoopExecutor.validationContractDigest());
            }
            throw new ServiceException("ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        });
    }

    /** Cancels a durable preparation identity only while every later side-effect receipt is absent. */
    private AssistantPlanExecutionResult cancelStoppedPreparation(
            AssistantPlanExecutionCommand command, AssistantPlanExecutionDecision decision)
    {
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        AidRuntimeRunStep child = findChild(run.getId(), decision.observedPlanRevision(),
                decision.stableStepId(), decision.attemptNo(), true);
        if (child == null || !"PREPARING".equals(child.getStatus())) return null;
        if (!Boolean.TRUE.equals(run.getStopRequested())) return null;

        requireAcceptedReconciliation(session, run, command.lease(), new Date());
        require("RECONCILING".equals(run.getStatus())
                        && "RECONCILE_OPERATION".equals(run.getNextAction()),
                "OPERATION_CANCELLATION_NOT_REQUESTED");
        AidRuntimeRunStep main = requirePreparingMainStep(command.mainAgentStepId(), run,
                command.mainDecisionJson(), decision, false, true);
        requirePreparingIdentity(child, run, decision);
        require(noPreparingSideEffectEvidence(child)
                        && Objects.equals(child.getInputDigest(), decisionDigest(decision))
                        && findApproval(run.getUserId(), run.getId(), clientKey(decision.invocationId()), true) == null
                        && authoritativeTask(run, child, true) == null,
                "OPERATION_PREPARATION_CHANGED");

        String operator = command.lease().getOwner();
        Date now = new Date();
        child.setStatus("CANCELLED_PRETASK");
        child.setErrorMessage("OPERATION_STOPPED_DURING_PREPARATION");
        child.setOutputJson(JSON.toJSONString(Map.of("kind", "PREPARATION_CANCELLED",
                "mainAgentStepId", main.getId(), "decisionDigest", decisionDigest(decision))));
        child.setOutputArtifactRef("[]");
        child.setLeaseOwner(null);
        child.setLeaseUntil(null);
        touchUpdate(child, operator, now);
        require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");

        main.setStatus("SUCCEEDED");
        main.setLeaseOwner(null);
        main.setLeaseUntil(null);
        touchUpdate(main, operator, now);
        require(steps.updateById(main) == 1, "MAIN_STEP_CHANGED");
        consumeMainApproval(main, run, operator, now);

        run.setStatus("STOPPED");
        run.setNextAction(null);
        run.setNextRetryAt(null);
        run.setStateRevision(longValue(run.getStateRevision()) + 1);
        touchUpdate(run, operator, now);
        require(runs.updateById(run) == 1, "RUNTIME_RUN_CHANGED");
        if (Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
        {
            session.setActiveRuntimeRunId(null);
            session.setStateRevision(longValue(session.getStateRevision()) + 1);
            session.setLastActiveTime(now);
            touchUpdate(session, operator, now);
            require(sessions.updateById(session) == 1, "ASSISTANT_SESSION_CHANGED");
        }
        appendEvent(session, run, "operation.step_changed", String.valueOf(child.getId()),
                longValue(child.getFencingToken()), decision.decisionId(),
                Map.of("status", child.getStatus(), "providerAcceptanceState", "NOT_SUBMITTED",
                        "errorCode", child.getErrorMessage()), now, operator);
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), decision.decisionId(),
                Map.of("status", run.getStatus(), "nextAction", ""), now, operator);
        return view(child);
    }

    /**
     * Proves under the same session/run/main lock order used by operation creation that an
     * rejected decision has never entered the child-operation graph. Child identity is committed
     * before approval, reservation, or task creation, so a locked absence of every later
     * operation child plus any orphan approval for this main also proves those later effects
     * absent. Corrupt or undecodable graph evidence throws and therefore remains recoverable.
     */
    private boolean decisionHasNoOperationEvidence(AssistantPlanExecutionCommand command)
    {
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        requireActiveMutation(session, run, command.lease(), new Date());
        AidRuntimeRunStep main = requireMainStep(command.mainAgentStepId(), run,
                command.mainDecisionJson(), true);
        require(main.getId() != null && main.getStepIndex() != null, "MAIN_STEP_IDENTITY_INVALID");

        return operationGraphHasNoEvidence(run, main);
    }

    private boolean rejectedDecisionHasNoOperationEvidence(AssistantRejectedDecisionEvidenceCommand command)
    {
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        requireActiveMutation(session, run, command.lease(), new Date());
        AidRuntimeRunStep main = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, command.mainAgentStepId())
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        require(main != null && main.getId() != null && main.getStepIndex() != null
                        && "MAIN_AGENT".equals(main.getStepKind())
                        && "ASSISTANT_DECISION".equals(main.getOperationKind())
                        && Objects.equals(main.getPlanRevision(), longValue(run.getPlanRevision())),
                "ASSISTANT_REJECTED_DECISION_IDENTITY_INVALID");
        if (command.failedTextTaskId() != null)
        {
            AidMediaTask task = authoritativeTask(run, main, true);
            if (!hasFailedTextReceipt(main, task, command.failedTextTaskId())) return false;
        }
        else
        {
            require(List.of("INFLIGHT", "RECONCILING").contains(main.getStatus())
                            && "ACCEPTED".equals(main.getProviderAcceptanceState()),
                    "ASSISTANT_REJECTED_DECISION_IDENTITY_INVALID");
        }
        return operationGraphHasNoEvidence(run, main);
    }

    static boolean hasFailedTextReceipt(AidRuntimeRunStep main, AidMediaTask task, Long expectedId)
    {
        return List.of("SUBMITTING", "INFLIGHT", "RECONCILING").contains(main.getStatus())
                && task != null && Objects.equals(task.getId(), expectedId)
                && "TEXT".equals(task.getMediaType()) && "FAILED".equals(task.getStatus())
                && AssistantModelRecovery.failedTaskReason(task.getErrorDetailJson()) != null;
    }

    private boolean operationGraphHasNoEvidence(AidRuntimeRun run, AidRuntimeRunStep main)
    {

        List<AidRuntimeRunStep> children = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .in(AidRuntimeRunStep::getOperationKind, "ASSISTANT_TOOL", "ASSISTANT_SKILL")
                .last("FOR UPDATE"));
        for (AidRuntimeRunStep child : children)
        {
            require(child != null && child.getId() != null && child.getStepIndex() != null,
                    "OPERATION_STEP_IDENTITY_INVALID");
            if (child.getApprovalId() == null)
            {
                // PREPARING is committed before an approval exists. Auto-increment identity and
                // monotonic run step ordering make a later child part of this current main turn.
                if (child.getId() > main.getId() || child.getStepIndex() > main.getStepIndex())
                    return false;
                require(child.getId() < main.getId() && child.getStepIndex() < main.getStepIndex(),
                        "OPERATION_PARENT_IDENTITY_CHANGED");
                continue;
            }
            AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                    .eq(AidRuntimeApproval::getId, child.getApprovalId())
                    .eq(AidRuntimeApproval::getRunId, run.getId())
                    .eq(AidRuntimeApproval::getUserId, run.getUserId())
                    .last("FOR UPDATE"));
            require(approval != null && "PLAN_EXECUTION".equals(approval.getScopeKind()),
                    "OPERATION_APPROVAL_MISSING");
            Persisted persisted = scopeCodec.decode(approval.getScopeJson());
            require(Objects.equals(approval.getScopeDigest(), persisted.digest()),
                    "OPERATION_APPROVAL_CHANGED");
            Long parentMainStepId = persisted.decision().getLong("mainAgentStepId");
            require(parentMainStepId != null && parentMainStepId > 0,
                    "OPERATION_PARENT_IDENTITY_CHANGED");
            if (Objects.equals(parentMainStepId, main.getId()))
                return false;
            require(parentMainStepId < main.getId()
                            && child.getId() < main.getId()
                            && child.getStepIndex() < main.getStepIndex(),
                    "OPERATION_PARENT_IDENTITY_CHANGED");
        }

        // Approval insertion follows child insertion, but scan the whole run as an explicit
        // integrity guard so an orphaned committed receipt can never be classified unsubmitted.
        List<AidRuntimeApproval> planApprovals = approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getUserId, run.getUserId())
                .eq(AidRuntimeApproval::getScopeKind, "PLAN_EXECUTION")
                .last("FOR UPDATE"));
        for (AidRuntimeApproval approval : planApprovals)
        {
            Persisted persisted = scopeCodec.decode(approval.getScopeJson());
            require(Objects.equals(approval.getScopeDigest(), persisted.digest()),
                    "OPERATION_APPROVAL_CHANGED");
            Long parentMainStepId = persisted.decision().getLong("mainAgentStepId");
            require(parentMainStepId != null && parentMainStepId > 0,
                    "OPERATION_PARENT_IDENTITY_CHANGED");
            if (Objects.equals(parentMainStepId, main.getId()))
                return false;
            require(parentMainStepId < main.getId(), "OPERATION_PARENT_IDENTITY_CHANGED");
        }
        return true;
    }

    private AssistantPlanExecutionResult preparationTerminalReplay(AssistantPlanExecutionCommand command,
                                                                  AssistantPlanExecutionDecision decision)
    {
        AidRuntimeRun run = requireRun(command.runId(), false);
        requireSession(run.getSessionId(), run.getUserId(), false);
        AidRuntimeRunStep child = findChild(run.getId(), decision.observedPlanRevision(),
                decision.stableStepId(), decision.attemptNo(), false);
        if (child == null || !List.of("FAILED_PRETASK", "CANCELLED_PRETASK").contains(child.getStatus())
                || child.getApprovalId() != null)
            return null;
        require(child.getReservationId() == null && child.getMediaTaskId() == null
                        && child.getChildRunId() == null && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                        && Objects.equals(child.getInputDigest(), decisionDigest(decision))
                        && Objects.equals(child.getIdempotencyKey(), logicalKey(run.getId(), decision)),
                "OPERATION_PREPARATION_CHANGED");
        requirePreparingMainStep(command.mainAgentStepId(), run, command.mainDecisionJson(), decision,
                true, false);
        JSONObject proof = JSON.parseObject(child.getOutputJson());
        String expectedKind = "FAILED_PRETASK".equals(child.getStatus())
                ? "PREPARATION_REJECTED" : "PREPARATION_CANCELLED";
        require(proof != null && expectedKind.equals(proof.getString("kind"))
                        && Objects.equals(proof.getLong("mainAgentStepId"), command.mainAgentStepId())
                        && Objects.equals(proof.getString("decisionDigest"), decisionDigest(decision))
                        && authoritativeTask(run, child) == null,
                "OPERATION_PREPARATION_CHANGED");
        return view(child);
    }

    /** Only the typed pure-validation rejection can terminate a preparation with no side effects. */
    private AssistantPlanExecutionResult rejectPreparation(Preflight preflight, Long stepId, String errorCode)
    {
        return rejectPreparation(preflight, stepId, errorCode, false);
    }

    private AssistantPlanExecutionResult rejectPreparation(Preflight preflight, Long stepId, String errorCode,
                                                            boolean terminalBudgetRejection)
    {
        return rejectPreparation(preflight, stepId, errorCode, terminalBudgetRejection, null);
    }

    private AssistantPlanExecutionResult rejectPreparation(Preflight preflight, Long stepId, String errorCode,
                                                            boolean terminalBudgetRejection, Long completedStepId)
    {
        Date now = new Date();
        AidAssistantSession session = requireSession(preflight.run().getSessionId(), preflight.run().getUserId(), true);
        AidRuntimeRun run = requireRun(preflight.run().getId(), true);
        requireActiveMutation(session, run, preflight.command().lease(), now);
        require(Objects.equals(run.getStateRevision(), preflight.run().getStateRevision()), "RUN_REVISION_CHANGED");
        AidRuntimeRunStep main = requireMainStep(preflight.command().mainAgentStepId(), run,
                preflight.command().mainDecisionJson(), true);
        AidRuntimeRunStep child = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        require(child != null, "OPERATION_STEP_MISSING");
        requirePreparingIdentity(child, preflight, preflight.bound());
        require("PREPARING".equals(child.getStatus()) && child.getApprovalId() == null
                        && child.getReservationId() == null && child.getMediaTaskId() == null
                        && child.getChildRunId() == null && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                        && Objects.equals(child.getInputDigest(), decisionDigest(preflight.decision()))
                        && findApproval(run.getUserId(), run.getId(), preflight.clientKey(), true) == null
                        && authoritativeTask(run, child) == null,
                "OPERATION_PREPARATION_CHANGED");
        String operator = preflight.command().lease().getOwner();
        child.setStatus("FAILED_PRETASK");
        child.setErrorMessage(errorCode);
        Map<String, Object> rejection = new LinkedHashMap<>();
        rejection.put("kind", "PREPARATION_REJECTED");
        rejection.put("mainAgentStepId", main.getId());
        rejection.put("decisionDigest", decisionDigest(preflight.decision()));
        if (completedStepId != null) {
            rejection.put("completedStepId", completedStepId);
            rejection.put("nextAction", "REUSE_COMPLETED_RESULT_AND_EXECUTE_ONLY_REMAINING_STEPS");
        }
        child.setOutputJson(JSON.toJSONString(rejection));
        child.setOutputArtifactRef("[]");
        child.setLeaseOwner(null); child.setLeaseUntil(null);
        touchUpdate(child, operator, now);
        require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
        main.setStatus("SUCCEEDED"); main.setLeaseOwner(null); main.setLeaseUntil(null);
        touchUpdate(main, operator, now);
        require(steps.updateById(main) == 1, "MAIN_STEP_CHANGED");
        consumeMainApproval(main, run, operator, now);
        // Only nonrenewable authorization failures require a terminal run. Time expiry,
        // exhausted counts and renewal confirmation remain resumable in this conversation.
        run.setStatus(terminalBudgetRejection ? "FAILED" : "PAUSED");
        run.setResumeStatus(terminalBudgetRejection ? null : "WAITING_APPROVAL");
        if (terminalBudgetRejection) run.setPauseReason(null);
        else if (AssistantBudgetAdmissionRejectedException.isRuntimeErrorCode(errorCode))
            run.setPauseReason("BUDGET_EXCEEDED");
        else run.setPauseReason("OPERATION_PREPARATION_REJECTED");
        run.setErrorMessage(errorCode);
        run.setNextAction(terminalBudgetRejection ? null : "CONTINUE_AGENT_REQUIRED");
        run.setStateRevision(longValue(run.getStateRevision()) + 1); run.setNextRetryAt(null);
        touchUpdate(run, operator, now);
        require(runs.updateById(run) == 1, "RUNTIME_RUN_CHANGED");
        appendEvent(session, run, "operation.step_changed", String.valueOf(child.getId()),
                longValue(child.getFencingToken()), preflight.decision().decisionId(),
                Map.of("status", child.getStatus(), "providerAcceptanceState", "NOT_SUBMITTED", "errorCode", errorCode), now, operator);
        Map<String, Object> runEvent = new LinkedHashMap<>();
        runEvent.put("status", run.getStatus());
        runEvent.put("nextAction", run.getNextAction());
        runEvent.put("resumeStatus", run.getResumeStatus());
        runEvent.put("reason", terminalBudgetRejection ? errorCode : run.getPauseReason());
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()), run.getStateRevision(),
                preflight.decision().decisionId(), runEvent, now, operator);
        return view(child);
    }

    private void consumeMainApproval(AidRuntimeRunStep main, AidRuntimeRun run, String operator, Date now)
    {
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, main.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        require(approval != null, "MAIN_APPROVAL_MISSING");
        if ("CONSUMED".equals(approval.getStatus())) return;
        require("ACCEPTED".equals(approval.getStatus()), "MAIN_APPROVAL_CHANGED");
        approval.setStatus("CONSUMED");
        approval.setStateRevision(longValue(approval.getStateRevision()) + 1);
        touchUpdate(approval, operator, now);
        require(approvals.updateById(approval) == 1, "MAIN_APPROVAL_CHANGED");
    }

    private AssistantPlanExecutionResult executeOrReplay(AssistantPlanExecutionCommand command, Dispatch dispatch)
    {
        AssistantPlanExecutionResult replay = replay(dispatch);
        if (replay != null) return replay;

        Date now = new Date();
        Date requestedUntil = new Date(now.getTime() + STEP_LEASE_MILLIS);
        Optional<RuntimeLease> claimed = leaseService.tryClaimStep(dispatch.runId(), dispatch.stepId(),
                command.lease(), now, requestedUntil);
        if (claimed.isEmpty())
        {
            AidRuntimeRunStep current = steps.selectById(dispatch.stepId());
            return current == null ? reconciling(dispatch, "STEP_LEASE_UNAVAILABLE") : view(current);
        }
        Dispatch leased = dispatch.withStepLease(claimed.get());
        if (requiresPreSubmissionCancellation(leased))
            return cancelBeforeProvider(leased);
        return leased.persisted().isTool() ? executeTool(leased) : executeSkill(leased);
    }

    @Override
    public AssistantPlanExecutionConfirmation confirm(AssistantPlanExecutionConfirmCommand command)
    {
        requireConfirmCommand(command);
        AidRuntimeRun snapshot = runs.selectById(command.runId());
        confirmReject(snapshot != null && "0".equals(snapshot.getDelFlag())
                        && Objects.equals(snapshot.getUserId(), command.userId()),
                404, "RUNTIME_RUN_NOT_FOUND", "运行记录不存在或不可访问",
                confirmDetails(command, null, null, null, null));
        AssistantPlanExecutionConfirmation result = tx().execute(status -> confirmLocked(command, snapshot));
        if (result == null) throw new ServiceException("OPERATION_CONFIRMATION_FAILED");
        if ("EXPIRED".equals(result.status()))
            throw new AssistantServiceException(422, "OPERATION_QUOTE_EXPIRED",
                    "操作报价已过期，请重新发起", confirmationDetails(result));
        return result;
    }

    private AssistantPlanExecutionConfirmation confirmLocked(AssistantPlanExecutionConfirmCommand request,
                                                              AidRuntimeRun snapshot)
    {
        Date now = new Date();
        String operator = String.valueOf(request.userId());
        String payloadDigest = confirmPayloadDigest(request);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), request.userId(), true);
        AidRuntimeRun run = requireRun(request.runId(), true);
        confirmReject(Objects.equals(run.getUserId(), request.userId())
                        && Objects.equals(session.getActiveRuntimeRunId(), run.getId()),
                404, "RUNTIME_RUN_NOT_FOUND", "运行记录不存在或不可访问",
                confirmDetails(request, run, session, null, null));

        AidRuntimeCommand replay = findCommand(request.userId(), session.getId(),
                request.clientRequestId());
        AidRuntimeApproval approvalSnapshot = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, request.approvalId())
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getUserId, request.userId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("LIMIT 1"));
        confirmReject(replay == null || "OPERATION_APPROVAL_CONFIRM".equals(replay.getCommandType())
                        && Objects.equals(replay.getPayloadDigest(), payloadDigest),
                409, "IDEMPOTENCY_KEY_REUSED", "请求标识已用于其他确认内容",
                confirmDetails(request, run, session, approvalSnapshot, null));
        if (replay != null) return confirmation(replay);
        confirmReject(Objects.equals(run.getStateRevision(), request.expectedRunRevision()),
                409, "RUNTIME_REVISION_CONFLICT", "运行状态已变化，请刷新后重试",
                confirmDetails(request, run, session, approvalSnapshot, null));
        confirmReject("WAITING_APPROVAL".equals(run.getStatus()) && !Boolean.TRUE.equals(run.getStopRequested()),
                409, "OPERATION_APPROVAL_STATE_CHANGED", "操作审批状态已变化",
                confirmDetails(request, run, session, approvalSnapshot, null));

        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, run.getId())
                .eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("FOR UPDATE"));
        confirmReject(policy != null && "ACTIVE".equals(policy.getStatus())
                        && policy.getExpiresAt() != null && policy.getExpiresAt().after(now),
                422, "BUDGET_POLICY_INACTIVE", "预算策略已失效，请重新确认预算",
                confirmDetails(request, run, session, approvalSnapshot, null));
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, request.approvalId())
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getUserId, request.userId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        confirmReject(approval != null && "PLAN_EXECUTION".equals(approval.getScopeKind()),
                404, "OPERATION_APPROVAL_NOT_FOUND", "操作审批不存在或不可访问",
                confirmDetails(request, run, session, approval, null));
        Persisted persisted = decodeConfirmationScope(request, run, session, approval);
        Long mainStepId = persisted.decision().getLong("mainAgentStepId");
        confirmReject(mainStepId != null, 409, "OPERATION_SCOPE_INVALID", "操作审批范围已变化",
                confirmDetails(request, run, session, approval, null));
        AidRuntimeRunStep main = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, mainStepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        confirmReject(main != null && "MAIN_AGENT".equals(main.getStepKind())
                        && "ASSISTANT_DECISION".equals(main.getOperationKind())
                        && "DECISION_READY".equals(main.getStatus())
                        && StrUtil.isNotBlank(main.getOutputJson()),
                409, "MAIN_STEP_CHANGED", "主助手步骤已变化",
                confirmDetails(request, run, session, approval, null));
        AssistantPlanExecutionDecision decision = decisionForApproval(main.getOutputJson(), persisted);
        AidRuntimePlan plan = requirePlan(run.getId(), decision.observedPlanRevision(), true);
        BoundOperation bound = planResolver.resolve(run, plan, decision);
        requireConfirmationScopeIdentity(request, run, session, approval, persisted,
                mainStepId, decision, bound);
        confirmReject(Objects.equals(approval.getAnswerDigest(), decisionDigest(decision)),
                409, "OPERATION_APPROVAL_CHANGED", "操作审批内容已变化",
                confirmDetails(request, run, session, approval, null));
        AidRuntimeRunStep child = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getApprovalId, approval.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        confirmReject(child != null && Objects.equals(child.getPlanRevision(), approval.getPlanRevision())
                        && Objects.equals(child.getStableStepId(), persisted.plan().getString("stableStepId")),
                409, "OPERATION_STEP_CHANGED", "操作步骤已变化",
                confirmDetails(request, run, session, approval, child));

        AidRuntimeCommand receipt = startCommand(request.userId(), session.getId(),
                request.clientRequestId(), payloadDigest, now);
        if ("ACCEPTED".equals(approval.getStatus()))
        {
            confirmReject("READY".equals(child.getStatus())
                            && (!persisted.billable() || child.getReservationId() != null),
                    422, "OPERATION_APPROVAL_NOT_SCHEDULED", "操作审批尚未完成调度",
                    confirmDetails(request, run, session, approval, child));
            AssistantPlanExecutionConfirmation accepted = confirmation(run, session, approval, child);
            completeCommand(receipt, accepted, operator, now);
            return accepted;
        }

        confirmReject("QUOTED".equals(approval.getStatus()) && "MANUAL".equals(approval.getMode())
                        && "AWAITING_APPROVAL".equals(child.getStatus())
                        && Objects.equals(approval.getStateRevision(), request.expectedApprovalRevision()),
                409, "OPERATION_APPROVAL_STATE_CHANGED", "操作审批状态已变化",
                confirmDetails(request, run, session, approval, child));
        if (approval.getExpiresAt() == null || !approval.getExpiresAt().after(now)
                || persisted.quoteExpiresAt() != null && !persisted.quoteExpiresAt().after(now))
        {
            AssistantPlanExecutionConfirmation expired = expireQuotedApproval(
                    run, session, main, child, approval, receipt, operator, now);
            completeCommand(receipt, expired, operator, now);
            return expired;
        }
        confirmReject(Objects.equals(approval.getContextDigest(), run.getContextDigest())
                        && Objects.equals(approval.getPlanRevision(), run.getPlanRevision())
                        && Objects.equals(approval.getScopeDigest(), persisted.digest())
                        && Objects.equals(policy.getAuthorizationId(), approval.getBillingAuthorizationId())
                        && Objects.equals(policy.getAuthorizationRevision(), approval.getBillingAuthorizationRevision()),
                409, "OPERATION_APPROVAL_CHANGED", "操作审批内容已变化",
                confirmDetails(request, run, session, approval, child));

        operationQuoteLineage.requireApprovalKey(run,child,approval,decision.invocationId(),true);
        if(AssistantOperationQuoteLineage.block(approval)!=null) requireQuoteNoSubmission(run,child,approval);
        requireCurrentOperationReferences(request,run,session,approval,child,persisted,bound);
        AssistantBudgetOperationAdmission admission = budgetPolicies.admitScheduledOperation(
                run.getId(), persisted.operationScope(), operator,request.confirmRenewedExecution());
        confirmReject(admission != null
                        && Objects.equals(admission.authorizationId(), approval.getBillingAuthorizationId())
                        && Objects.equals(admission.authorizationRevision(), approval.getBillingAuthorizationRevision())
                        && (AssistantOperationQuoteLineage.block(approval)==null
                            && !requiresExplicitMedia(run, persisted.operationScope())
                            ? Objects.equals(admission.policyMode(), approval.getMode())
                            : "MANUAL".equals(approval.getMode()) && Objects.equals(admission.policyMode(),persisted.policy().getString("mode")))
                        && Objects.equals(admission.scopeDigest(), persisted.policy().getString("scopeDigest")),
                409, "BUDGET_POLICY_CHANGED", "预算策略已变化，请刷新后重试",
                confirmDetails(request, run, session, approval, child));
        if (persisted.billable())
        {
            BillingBudgetViews.ReservationView reserved = billingBudgets.reserveCall(
                    reserveRequest(run, child, approval, persisted, operator));
            require(reserved != null && reserved.getId() != null, "BUDGET_RESERVATION_FAILED");
            child.setReservationId(reserved.getId());
        }
        approval.setStatus("ACCEPTED");
        approval.setAcceptedAt(now);
        approval.setStateRevision(longValue(approval.getStateRevision()) + 1);
        touchUpdate(approval, operator, now);
        approvals.updateById(approval);
        child.setStatus("READY");
        touchUpdate(child, operator, now);
        steps.updateById(child);
        run.setStatus("READY");
        run.setNextAction("EXECUTE_PLAN_OPERATION");
        run.setStateRevision(longValue(run.getStateRevision()) + 1);
        run.setNextRetryAt(now);
        touchUpdate(run, operator, now);
        runs.updateById(run);
        appendEvent(session, run, "operation.approved", String.valueOf(approval.getId()),
                approval.getStateRevision(), String.valueOf(receipt.getId()),
                Map.of("approvalId", String.valueOf(approval.getId()),
                        "childStepId", String.valueOf(child.getId())), now, operator);
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), String.valueOf(receipt.getId()),
                Map.of("status", run.getStatus(), "nextAction", run.getNextAction()), now, operator);
        AssistantPlanExecutionConfirmation accepted = confirmation(run, session, approval, child);
        completeCommand(receipt, accepted, operator, now);
        dueRunWakeup.wakeAfterCommit(run.getId());
        return accepted;
    }

    /**
     * Quote expiry is a definitive pre-provider outcome. Persist it under the same session/run/
     * approval/step locks used by confirmation, then surface the 422 only after commit.
     */
    private AssistantPlanExecutionConfirmation expireQuotedApproval(AidRuntimeRun run,
                                                                     AidAssistantSession session,
                                                                     AidRuntimeRunStep main,
                                                                     AidRuntimeRunStep child,
                                                                     AidRuntimeApproval approval,
                                                                     AidRuntimeCommand receipt,
                                                                     String operator,
                                                                     Date now)
    {
        require("QUOTED".equals(approval.getStatus()) && "AWAITING_APPROVAL".equals(child.getStatus())
                        && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                        && child.getReservationId() == null && child.getMediaTaskId() == null
                        && child.getChildRunId() == null && authoritativeTask(run, child) == null,
                "OPERATION_EXPIRY_EVIDENCE_CHANGED");

        approval.setStatus("EXPIRED");
        approval.setInvalidatedAt(now);
        approval.setStateRevision(longValue(approval.getStateRevision()) + 1);
        touchUpdate(approval, operator, now);
        require(approvals.updateById(approval) == 1, "OPERATION_APPROVAL_STATE_CHANGED");

        child.setStatus("FAILED_PRETASK");
        child.setErrorMessage("OPERATION_QUOTE_EXPIRED");
        child.setLeaseOwner(null);
        child.setLeaseUntil(null);
        touchUpdate(child, operator, now);
        require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");

        main.setStatus("SUCCEEDED");
        main.setLeaseOwner(null);
        main.setLeaseUntil(null);
        touchUpdate(main, operator, now);
        require(steps.updateById(main) == 1, "MAIN_STEP_CHANGED");
        consumeMainApproval(main, run, operator, now);

        run.setStatus("PAUSED");
        run.setResumeStatus("WAITING_APPROVAL");
        run.setPauseReason("OPERATION_QUOTE_EXPIRED");
        run.setErrorMessage("OPERATION_QUOTE_EXPIRED");
        run.setNextAction("CONTINUE_AGENT_REQUIRED");
        run.setNextRetryAt(null);
        run.setStateRevision(longValue(run.getStateRevision()) + 1);
        touchUpdate(run, operator, now);
        require(runs.updateById(run) == 1, "RUNTIME_RUN_CHANGED");

        appendEvent(session, run, "operation.step_changed", String.valueOf(child.getId()),
                longValue(child.getFencingToken()), String.valueOf(receipt.getId()),
                Map.of("status", child.getStatus(), "providerAcceptanceState", "NOT_SUBMITTED",
                        "errorCode", "OPERATION_QUOTE_EXPIRED"), now, operator);
        appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), String.valueOf(receipt.getId()),
                Map.of("status", run.getStatus(), "nextAction", run.getNextAction(),
                        "resumeStatus", run.getResumeStatus(), "reason", run.getPauseReason()), now, operator);
        return confirmation(run, session, approval, child);
    }

    /** Existing durable identity wins before the original observed run revision is considered. */
    private Dispatch existingDispatch(AssistantPlanExecutionCommand command,
                                      AssistantPlanExecutionDecision decision)
    {
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        AidRuntimeRunStep discovered = findChild(run.getId(), decision.observedPlanRevision(),
                decision.stableStepId(), decision.attemptNo(), false);
        if (discovered == null)
        {
            requireActiveMutation(session, run, command.lease(), new Date());
            return null;
        }
        // A crash may leave only the committed PREPARING identity. No approval,
        // admission, reservation, or Provider side effect exists yet, so resume
        // through ensurePreparing and obtain a fresh pure preparation snapshot.
        if ("PREPARING".equals(discovered.getStatus()))
        {
            requireActiveMutation(session, run, command.lease(), new Date());
            require(discovered.getApprovalId() == null
                    && "NOT_SUBMITTED".equals(discovered.getProviderAcceptanceState())
                    && discovered.getReservationId() == null && discovered.getMediaTaskId() == null,
                    "OPERATION_PREPARATION_CHANGED");
            return null;
        }
        boolean passiveReplay = TERMINAL_STEP.contains(discovered.getStatus())
                || "AWAITING_APPROVAL".equals(discovered.getStatus());
        boolean acceptedReconciliation = List.of("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING")
                .contains(discovered.getStatus());
        boolean preSubmissionCancellation = "READY".equals(discovered.getStatus())
                && "NOT_SUBMITTED".equals(discovered.getProviderAcceptanceState())
                && discovered.getMediaTaskId() == null
                && Boolean.TRUE.equals(run.getStopRequested());
        if (acceptedReconciliation || preSubmissionCancellation)
            requireAcceptedReconciliation(session, run, command.lease(), new Date());
        else if (!passiveReplay)
            requireActiveMutation(session, run, command.lease(), new Date());
        require(discovered.getApprovalId() != null, "OPERATION_PREPARATION_INCOMPLETE");
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, discovered.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getUserId, run.getUserId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        require(approval != null, "OPERATION_APPROVAL_MISSING");
        Persisted persisted = scopeCodec.decode(approval.getScopeJson());
        Long mainStepId = persisted.decision().getLong("mainAgentStepId");
        require(Objects.equals(mainStepId, command.mainAgentStepId()), "MAIN_STEP_CHANGED");
        AidRuntimeRunStep main = requireExistingMainStep(mainStepId, run, command.mainDecisionJson(),
                TERMINAL_STEP.contains(discovered.getStatus()), true);
        AidRuntimeRunStep child = findChild(run.getId(), decision.observedPlanRevision(),
                decision.stableStepId(), decision.attemptNo(), true);
        require(child != null && Objects.equals(child.getApprovalId(), approval.getId()),
                "OPERATION_STEP_CHANGED");
        AidRuntimePlan plan = requirePlan(run.getId(), decision.observedPlanRevision(), true);
        BoundOperation bound = requiresOutputAdmission(acceptedReconciliation,child)
                ? planResolver.resolve(run,plan,decision) : planResolver.resolveAccepted(run,plan,decision);
        scopeCodec.requireIdentity(persisted, mainStepId, decision, bound, run.getContextDigest());
        // Existing media work must reconcile by its durable task identity. A newer canvas
        // revision cannot invalidate an already submitted call or cause it to be submitted again.
        // Pure replay and new submissions retain the original live-context validation below.
        boolean existingMediaTask = acceptedReconciliation && decision.isTool()
                && "RECONCILE_TASK".equals(toolRegistry.require(child.getToolCode()).recoveryPolicy())
                && authoritativeTask(run, child) != null;
        AssistantRuntimeContextManifestService.ResolvedManifest manifest = null;
        if (!existingMediaTask) {
            manifest = manifests.requirePinned(run.getUserId(), session, run);
            requireExpectedContextRevisions(decision, manifest, run, session);
        }
        Preflight identity = new Preflight(command, decision, run, session, plan,
                manifest, bound,
                persisted.operationScope(), clientKey(decision.invocationId()));
        requireChildIdentity(child, approval, identity, bound);
        operationQuoteLineage.requireApprovalKey(run,child,approval,decision.invocationId(),true);
        require(Objects.equals(approval.getScopeDigest(), persisted.digest())
                && Objects.equals(approval.getAnswerDigest(), decisionDigest(decision)),
                "OPERATION_APPROVAL_CHANGED");
        return dispatch(run, session, main, child, approval, persisted, bound, command.lease());
    }

    private Preflight preflight(AssistantPlanExecutionCommand command, AssistantPlanExecutionDecision decision)
    {
        AidRuntimeRun run = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(run.getSessionId(), run.getUserId(), false);
        AidRuntimePlan plan = requirePlan(run.getId(), decision.observedPlanRevision(), false);
        AidRuntimeRunStep main = requireMainStep(command.mainAgentStepId(), run, command.mainDecisionJson(), false);
        requireSubmittedIterationRevision(run, main, command, decision);
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                manifests.requirePinned(run.getUserId(), session, run);
        if (decision.isTool() && !manifests.matchesExpectedRevisions(run.getUserId(), session, run,
                decision.expectedContextRevisions(), manifest)) {
            // No child, approval, reservation or provider call exists at this preflight boundary.
            // Retire the immutable stale decision through the established context recovery path.
            throw new com.aid.assistant.error.AssistantServiceException(409, "ASSISTANT_CONTEXT_STALE",
                    "操作上下文已变化，请重新核对后继续", "OPERATION_CONTEXT_REVISIONS_CHANGED");
        }
        BoundOperation bound;
        try { bound = planResolver.resolve(run, plan, decision); }
        catch (ServiceException rejected)
        {
            AssistantPlanDecisionRejectedException.Reason reason =
                    AssistantPlanDecisionRejectedException.Reason.fromCode(rejected.getMessage());
            if (reason == null) throw rejected;
            // Only this initial read-only preflight classifies dependency rejection. The
            // resolver's later rechecks and any already-persisted dispatch remain recoverable.
            throw new AssistantPlanDecisionRejectedException(reason, rejected);
        }
        String clientKey = clientKey(decision.invocationId());
        AssistantBudgetOperationScope operationScope = operationScope(bound, decision, manifest);
        return new Preflight(command, decision, run, session, plan, manifest, bound,
                operationScope, clientKey);
    }

    /** A replan changes step keys, not the user's already completed business request. */
    private void requireNoRepeatedCompletedOperation(AidRuntimeRun run, RuntimeToolPreparedInvocation prepared) {
        if (!prepared.getDescriptor().billable() || prepared.getPlanRevision() <= 1) return;
        var previous = steps.selectCompletedToolComparisons(run.getId(), run.getUserId(),
                prepared.getPlanRevision(), prepared.getToolCode(),
                prepared.getContractVersion(), prepared.getSchemaDigest());
        if (previous == null || previous.isEmpty()) return;
        if (previous.size() > 100) throw new ServiceException("OPERATION_HISTORY_LIMIT_EXCEEDED");
        String current = toolRegistry.completedOperationIdentity(prepared.getToolCode(),
                prepared.getArgumentsJson(), prepared.getPreparedPayloadJson());
        for (var receipt : previous) {
            String arguments = (String) receipt.get("argumentsJson");
            String payload = (String) receipt.get("preparedPayloadJson");
            if (StrUtil.isBlank(arguments) || StrUtil.isBlank(payload)
                    || !Objects.equals(SecureUtil.sha256(arguments), receipt.get("argumentsDigest"))
                    || !Objects.equals(SecureUtil.sha256(payload), receipt.get("preparedPayloadDigest")))
                throw new ServiceException("OPERATION_HISTORY_IDENTITY_INVALID");
            if (Objects.equals(current, toolRegistry.completedOperationIdentity(prepared.getToolCode(), arguments, payload))) {
                if (!(receipt.get("stepId") instanceof Number id) || id.longValue() <= 0)
                    throw new ServiceException("OPERATION_HISTORY_IDENTITY_INVALID");
                throw RuntimeToolPreparationRejectedException.alreadyCompleted(id.longValue());
            }
        }
    }

    /** Correlates the observed planning snapshot with submission and its durable receipt. */
    private void requireSubmittedIterationRevision(AidRuntimeRun run, AidRuntimeRunStep main,
                                                    AssistantPlanExecutionCommand command,
                                                    AssistantPlanExecutionDecision decision)
    {
        AidRuntimeApproval accepted = main.getApprovalId() == null ? null : approvals.selectById(main.getApprovalId());
        require(accepted != null && Objects.equals(accepted.getRunId(), run.getId())
                && Objects.equals(accepted.getUserId(), run.getUserId())
                && Objects.equals(accepted.getPlanRevision(), run.getPlanRevision())
                && Objects.equals(accepted.getContextDigest(), run.getContextDigest())
                && "ASSISTANT_ITERATION".equals(accepted.getScopeKind())
                && "ACCEPTED".equals(accepted.getStatus()) && "0".equals(accepted.getDelFlag()),
                "ITERATION_OBSERVATION_CHANGED");
        JSONObject snapshot;
        try { snapshot = JSON.parseObject(accepted.getScopeJson()); }
        catch (RuntimeException invalid) { throw new ServiceException("ITERATION_OBSERVATION_CHANGED"); }
        Long observed = snapshot == null ? null : snapshot.getLong("acceptedRunRevision");
        require(observed != null && observed >= 0 && observed < Long.MAX_VALUE
                && Objects.equals(observed, decision.observedRunRevision()), "RUN_REVISION_CHANGED");
        if (!command.batchMember())
        {
            require(matchesSubmittedIterationRevision(run, main, observed), "RUN_REVISION_CHANGED");
            return;
        }
        requireNativeBatchCursor(run, main, command, decision, observed);
    }

    static boolean matchesSubmittedIterationRevision(AidRuntimeRun run, AidRuntimeRunStep main, long observed)
    {
        if (Objects.equals(run.getStateRevision(), observed + 1L)) return true;
        // An unknown acceptance advances the run once. A later authoritative receipt can
        // apply the original decision only across that exact transition.
        return observed < Long.MAX_VALUE - 1 && Objects.equals(run.getStateRevision(), observed + 2L)
                && "RECONCILING".equals(run.getStatus())
                && "WAIT_TASK_RECONCILIATION".equals(run.getNextAction())
                && !Boolean.TRUE.equals(run.getStopRequested()) && StrUtil.isBlank(run.getPauseReason())
                && "DECISION_READY".equals(main.getStatus()) && "ACCEPTED".equals(main.getProviderAcceptanceState())
                && "PROVIDER_ACCEPTANCE_UNKNOWN".equals(main.getErrorMessage()) && main.getMediaTaskId() != null;
    }

    private void requireNativeBatchCursor(AidRuntimeRun run, AidRuntimeRunStep main,
                                          AssistantPlanExecutionCommand command,
                                          AssistantPlanExecutionDecision decision,
                                          long acceptedRunRevision)
    {
        NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(command.mainDecisionJson());
        require(command.batchSize() == batch.calls().size()
                        && command.batchIndex() >= 0 && command.batchIndex() < command.batchSize()
                        && Objects.equals(batch.calls().get(command.batchIndex()).decisionJson(),
                        command.operationDecisionJson())
                        && Objects.equals(batch.observedRunRevision(), acceptedRunRevision)
                        && Objects.equals(batch.observedPlanRevision(), decision.observedPlanRevision())
                        && "DECISION_READY".equals(main.getStatus())
                        && !Boolean.TRUE.equals(run.getStopRequested())
                        && StrUtil.isBlank(run.getPauseReason())
                        && run.getStateRevision() != null
                        && (command.batchIndex() == 0
                        ? matchesSubmittedIterationRevision(run, main, acceptedRunRevision)
                        : run.getStateRevision() > acceptedRunRevision
                        && "READY".equals(run.getStatus())
                        && "EXECUTE_PLAN_OPERATION".equals(run.getNextAction())),
                "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
        for (int index = 0; index < command.batchIndex(); index++)
        {
            AssistantPlanExecutionDecision prior = decisionParser().parse(
                    batch.calls().get(index).decisionJson());
            AidRuntimeRunStep receipt = findChild(run.getId(), prior.observedPlanRevision(),
                    prior.stableStepId(), prior.attemptNo(), false);
            require(receipt != null && "SUCCEEDED".equals(receipt.getStatus())
                            && receipt.getApprovalId() != null
                            && Objects.equals(receipt.getIdempotencyKey(), logicalKey(run.getId(), prior)),
                    "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
            AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                    .eq(AidRuntimeApproval::getId, receipt.getApprovalId())
                    .eq(AidRuntimeApproval::getRunId, run.getId())
                    .eq(AidRuntimeApproval::getUserId, run.getUserId())
                    .eq(AidRuntimeApproval::getStatus, "CONSUMED")
                    .eq(AidRuntimeApproval::getDelFlag, "0").last("LIMIT 1"));
            // Finalization replaces the child's provisional decision digest with the
            // authoritative prepared-input digest. Verify each digest against its own
            // approval field; they intentionally cover different payloads.
            require(approval != null && StrUtil.isNotBlank(approval.getRequestDigest())
                            && Objects.equals(receipt.getInputDigest(), approval.getRequestDigest())
                            && Objects.equals(approval.getAnswerDigest(), decisionDigest(prior)),
                    "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
        }
        for (int index = command.batchIndex() + 1; index < command.batchSize(); index++)
        {
            AssistantPlanExecutionDecision later = decisionParser().parse(
                    batch.calls().get(index).decisionJson());
            require(findChild(run.getId(), later.observedPlanRevision(), later.stableStepId(),
                    later.attemptNo(), false) == null, "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
        }
    }

    /**
     * Binds a TOOL decision to the complete pinned context snapshot observed by the Agent.
     * The declaration is evidence only: requirePinned remains authoritative and Provider
     * preparation still derives the narrower operation target set.
     */
    private void requireExpectedContextRevisions(
            AssistantPlanExecutionDecision decision,
            AssistantRuntimeContextManifestService.ResolvedManifest manifest,
            AidRuntimeRun run, AidAssistantSession session)
    {
        if (!decision.isTool()) return;
        require(manifests.matchesExpectedRevisions(run.getUserId(), session, run,
                        decision.expectedContextRevisions(), manifest),
                "OPERATION_CONTEXT_REVISIONS_CHANGED");
    }

    /**
     * First short transaction. It commits only the stable child identity. There is no approval,
     * policy admission, reservation, task, Provider call, or prepared payload in this phase.
     */
    private PreparationSlot ensurePreparing(Preflight preflight)
    {
        Date now = new Date();
        AidAssistantSession session = requireSession(preflight.run().getSessionId(), preflight.run().getUserId(), true);
        AidRuntimeRun run = requireRun(preflight.run().getId(), true);
        requireActiveMutation(session, run, preflight.command().lease(), now);
        require(Objects.equals(run.getStateRevision(), preflight.run().getStateRevision()),
                "RUN_REVISION_CHANGED");
        AidRuntimePlan plan = requirePlan(run.getId(), preflight.decision().observedPlanRevision(), true);
        BoundOperation bound = planResolver.resolve(run, plan, preflight.decision());
        require(Objects.equals(preflight.manifest().digest(), run.getContextDigest()), "CONTEXT_CHANGED");
        requireMainStep(preflight.command().mainAgentStepId(), run,
                preflight.command().mainDecisionJson(), true);
        AidRuntimeRunStep child = findChild(run.getId(), plan.getRevision(),
                preflight.decision().stableStepId(), preflight.decision().attemptNo(), true);
        if (child == null)
        {
            AidRuntimeApproval orphan = findApproval(run.getUserId(), run.getId(), preflight.clientKey(), false);
            require(orphan == null, "OPERATION_APPROVAL_ORPHANED");
            child = newPreparingChild(run, plan, preflight, bound, now);
            steps.insert(child);
            return new PreparationSlot(child.getId(), false);
        }
        requirePreparingIdentity(child, preflight, bound);
        if (child.getApprovalId() == null)
        {
            require("PREPARING".equals(child.getStatus())
                    && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                    && child.getReservationId() == null && child.getMediaTaskId() == null,
                    "OPERATION_PREPARATION_CHANGED");
            return new PreparationSlot(child.getId(), false);
        }
        return new PreparationSlot(child.getId(), true);
    }

    /** Second short transaction. The first PREPARING winner freezes the exact prepared snapshot. */
    private Dispatch persistPrepared(Preflight preflight, Long stepId, RuntimeToolPreparedInvocation prepared)
    {
        Date now = new Date();
        AidAssistantSession session = requireSession(preflight.run().getSessionId(), preflight.run().getUserId(), true);
        AidRuntimeRun run = requireRun(preflight.run().getId(), true);
        requireActiveMutation(session, run, preflight.command().lease(), now);
        require(Objects.equals(run.getStateRevision(), preflight.run().getStateRevision()),
                "RUN_REVISION_CHANGED");
        AidRuntimePlan plan = requirePlan(run.getId(), preflight.decision().observedPlanRevision(), true);
        BoundOperation bound = planResolver.resolve(run, plan, preflight.decision());
        require(Objects.equals(preflight.manifest().digest(), run.getContextDigest()), "CONTEXT_CHANGED");
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, run.getId())
                .eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
        if (policy == null) throw new AssistantBudgetAdmissionRejectedException(
                AssistantBudgetAdmissionRejectedException.Reason.POLICY_MISSING);
        if (!Objects.equals(policy.getAuthorizationId(), run.getBudgetAuthorizationId()))
            throw new AssistantBudgetAdmissionRejectedException(
                    AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CHANGED);

        AidRuntimeApproval approval = findApproval(run.getUserId(), run.getId(), preflight.clientKey(), true);
        AidRuntimeRunStep main = requireMainStep(preflight.command().mainAgentStepId(), run,
                preflight.command().mainDecisionJson(), true);
        AidRuntimeRunStep child = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        require(child != null, "OPERATION_STEP_MISSING");
        requirePreparingIdentity(child, preflight, bound);
        if (approval != null)
        {
            Persisted persisted = scopeCodec.decode(approval.getScopeJson());
            scopeCodec.requireIdentity(persisted, preflight.command().mainAgentStepId(),
                    preflight.decision(), bound, run.getContextDigest());
            require(Objects.equals(approval.getScopeDigest(), persisted.digest())
                    && Objects.equals(approval.getAnswerDigest(), decisionDigest(preflight.decision())),
                    "OPERATION_APPROVAL_CHANGED");
            requireChildIdentity(child, approval, preflight, bound);
            require(List.of("QUOTED", "ACCEPTED").contains(approval.getStatus()),
                    "OPERATION_APPROVAL_INACTIVE");
            if ("QUOTED".equals(approval.getStatus()))
                require("AWAITING_APPROVAL".equals(child.getStatus()), "OPERATION_STEP_CHANGED");
            if ("ACCEPTED".equals(approval.getStatus()))
                require(!"AWAITING_APPROVAL".equals(child.getStatus())
                        && (!persisted.billable() || child.getReservationId() != null),
                        "OPERATION_APPROVAL_NOT_SCHEDULED");
            return dispatch(run, session, main, child, approval, persisted, bound, preflight.command().lease());
        }

        require(child.getApprovalId() == null && "PREPARING".equals(child.getStatus())
                && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                && child.getReservationId() == null && child.getMediaTaskId() == null,
                "OPERATION_PREPARATION_CHANGED");
        require(preflight.decision().isSkill() || prepared != null, "TOOL_PREPARATION_MISSING");
        if (prepared != null) requirePreparedIdentity(prepared, preflight, child.getId());
        AssistantBudgetOperationScope operationScope = preflight.decision().isTool()
                ? prepared.getOperationScope() : preflight.operationScope();
        requireOperationScope(operationScope, preflight, prepared);
        com.aid.assistant.service.AssistantRequestedModelPolicy.requireMatch(run.getRequestJson(), operationScope);
        AssistantBudgetOperationAdmission admission;
        boolean automatic = automaticallyAccepted(run, policy, operationScope);
        if (automatic)
        {
            admission = budgetPolicies.admitScheduledOperation(run.getId(), operationScope,
                    preflight.command().lease().getOwner());
        }
        else
        {
            admission = budgetPolicies.assertOperationSchedulable(run.getId(), operationScope);
        }
        Encoded encoded = scopeCodec.encode(preflight.command().mainAgentStepId(),
                preflight.decision(), bound, preflight.manifest(),
                operationScope, admission, prepared, preflight.decision().isSkill()
                        ? conversationMemoryContext.freeze(session, run) : null);
        Persisted persisted = scopeCodec.decode(encoded.json());
        approval = newApproval(run, plan, preflight, operationScope,
                admission, persisted, policy, now, automatic);
        approvals.insert(approval);
        child.setApprovalId(approval.getId());
        child.setInputDigest(approval.getRequestDigest());
        child.setStatus("ACCEPTED".equals(approval.getStatus()) ? "READY" : "AWAITING_APPROVAL");
        touchUpdate(child, preflight.command().lease().getOwner(), now);
        steps.updateById(child);
        if ("ACCEPTED".equals(approval.getStatus()) && persisted.billable())
        {
            BillingBudgetViews.ReservationView reserved = billingBudgets.reserveCall(
                    reserveRequest(run, child, approval, persisted, preflight.command().lease().getOwner()));
            require(reserved != null && reserved.getId() != null, "BUDGET_RESERVATION_FAILED");
            child.setReservationId(reserved.getId());
            touchUpdate(child, preflight.command().lease().getOwner(), now);
            steps.updateById(child);
        }
        if ("QUOTED".equals(approval.getStatus()))
        {
            run.setStatus("WAITING_APPROVAL");
            run.setNextAction("CONFIRM_OPERATION_APPROVAL");
            run.setNextRetryAt(null);
            run.setStateRevision(longValue(run.getStateRevision()) + 1);
            touchUpdate(run, preflight.command().lease().getOwner(), now);
            runs.updateById(run);
            appendEvent(session, run, "operation.approval_required", String.valueOf(approval.getId()),
                    approval.getStateRevision(), preflight.decision().decisionId(),
                    Map.of("approvalId", String.valueOf(approval.getId()),
                            "childStepId", String.valueOf(child.getId())), now,
                    preflight.command().lease().getOwner());
            appendEvent(session, run, "run.state_changed", String.valueOf(run.getId()),
                    run.getStateRevision(), preflight.decision().decisionId(),
                    Map.of("status", run.getStatus(), "nextAction", run.getNextAction()), now,
                    preflight.command().lease().getOwner());
        }
        return dispatch(run, session, main, child, approval, persisted, bound, preflight.command().lease());
    }

    private boolean automaticallyAccepted(AidRuntimeRun run, AidRuntimeBudgetPolicy policy,
            AssistantBudgetOperationScope operationScope)
    {
        if ("AUTO".equals(policy.getMode())) return true;
        if (requiresExplicitMedia(run, operationScope)) return false;
        if (operationScope != null && "creative.text.submit".equals(operationScope.getToolCode())
                && List.of("TEXT").equals(operationScope.getMediaTypes())
                && com.aid.assistant.service.AssistantAutomaticTextExecution.enabled(JSON.parseObject(run.getRequestJson())))
            return true;
        BigDecimal charge = operationScope == null ? null : operationScope.getQuotedUpperBound();
        return charge != null && charge.signum() == 0
                && !JSON.parseArray(policy.getAutomaticForbiddenOperationKindsJson())
                .contains(operationScope.getOperationKind());
    }

    private boolean requiresExplicitMedia(AidRuntimeRun run, AssistantBudgetOperationScope scope)
    {
        return scope != null && scope.getMediaTypes() != null
                && (scope.getMediaTypes().contains("IMAGE") || scope.getMediaTypes().contains("VIDEO"))
                && com.aid.assistant.service.AssistantAutomaticTextExecution.enabled(JSON.parseObject(run.getRequestJson()));
    }

    private AssistantPlanExecutionResult replay(Dispatch dispatch)
    {
        AidRuntimeRunStep step = steps.selectById(dispatch.stepId());
        if (step == null) throw new ServiceException("OPERATION_STEP_MISSING");
        if ("AWAITING_APPROVAL".equals(step.getStatus()))
            return new AssistantPlanExecutionResult(step.getId(), "AWAITING_APPROVAL",
                    "CONFIRM_OPERATION_APPROVAL", List.of(), step.getMediaTaskId(),
                    "Operation approval is required.", null, false);
        if (TERMINAL_STEP.contains(step.getStatus())) return view(step);
        if (List.of("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING").contains(step.getStatus()))
            return reconcileExisting(dispatch, step);
        require("READY".equals(step.getStatus()), "OPERATION_STEP_STATE_CHANGED");
        return null;
    }

    private AssistantPlanExecutionResult executeTool(Dispatch dispatch)
    {
        Date submissionTime = new Date();
        if (dispatch.approval().getExpiresAt() == null
                || !dispatch.approval().getExpiresAt().after(submissionTime)
                || dispatch.persisted().billable() && (dispatch.persisted().quoteExpiresAt() == null
                || !dispatch.persisted().quoteExpiresAt().after(submissionTime)))
            return settleToolRejectedBeforeEntry(dispatch, "OPERATION_QUOTE_EXPIRED");
        RuntimeToolPreparedInvocation prepared;
        BillingBudgetContext budget;
        try
        {
            prepared = prepared(dispatch);
            budget = dispatch.persisted().billable() ? budgetContext(dispatch) : null;
            requireToolSubmissionReferenceContext(dispatch.run().getUserId(), dispatch.session(),
                    dispatch.run(), dispatch.persisted().executionManifestJson());
            toolRegistry.validatePreparedSubmission(prepared, budget);
        }
        catch (RuntimeToolPreparationRejectedException rejectedBeforeProvider)
        {
            return settleToolRejectedBeforeEntry(dispatch, rejectedBeforeProvider.getMessage());
        }
        catch (InvalidPersistedSubmissionException invalidBudgetSnapshot)
        {
            log.error("assistant tool has an invalid persisted budget identity before provider submission, "
                            + "runId={}, stepId={}, reason={}", dispatch.runId(), dispatch.stepId(),
                    invalidBudgetSnapshot.reason());
            return settleInvalidToolSnapshot(dispatch, "BUDGET_AUTHORIZATION_CHANGED");
        }
        try
        {
            budgetPolicies.assertProviderSubmission(dispatch.runId(), dispatch.approval().getBillingAuthorizationId(),
                    dispatch.approval().getBillingAuthorizationRevision(), dispatch.persisted().operationScope());
        }
        catch (AssistantBudgetAdmissionRejectedException rejectedBeforeProvider)
        {
            log.info("assistant tool rejected before provider submission, runId={}, stepId={}, reason={}",
                    dispatch.runId(), dispatch.stepId(), rejectedBeforeProvider.getReason());
            return settleToolRejectedBeforeEntry(dispatch, rejectedBeforeProvider.getRuntimeErrorCode());
        }
        try
        {
            if (toolRegistry.requiresExecutionCheckpoint(prepared.getToolCode()))
                checkpointToolExecution(dispatch);
            RuntimeToolResult result = toolRegistry.executePrepared(prepared, budget);
            return finishTool(dispatch, result);
        }
        catch (MediaSubmissionNotEnteredException notEntered)
        {
            return settleToolRejectedBeforeEntry(dispatch, notEntered.getEvidenceCode());
        }
        catch (RuntimeToolOutputValidationException rejectedOutput)
        {
            if (!definiteReadOutputFailure(prepared, dispatch.persisted().billable(), rejectedOutput))
                return markUnknown(dispatch, "TOOL_RESULT_UNKNOWN");
            RuntimeToolResult failure = new RuntimeToolResult();
            failure.setStatus("FAILED");
            failure.setErrorCode(RuntimeToolOutputValidationException.ERROR_CODE);
            failure.setSafeSummary("只读工具输出不符合契约，请联系管理员修复后继续。");
            failure.setRetryable(false);
            try { return finishTool(dispatch, failure); }
            catch (RuntimeException settlementUnknown)
            {
                log.warn("assistant read output rejection settlement requires reconciliation, runId={}, stepId={}, cause={}",
                        dispatch.runId(), dispatch.stepId(), settlementUnknown.getClass().getSimpleName());
                return markUnknown(dispatch, "TOOL_RESULT_UNKNOWN");
            }
        }
        catch (RuntimeException uncertain)
        {
            if ("OPERATION_STOP_REQUESTED".equals(safeToolErrorCode(uncertain))) {
                // STOP may win after the resource-wait replay was claimed but before its checkpoint.
                // The locked cancellation below still requires durable NOT_SUBMITTED evidence.
                try { return cancelBeforeProvider(dispatch); }
                catch (RuntimeException notProven) { /* Stronger submission evidence remains unknown. */ }
            }
            log.warn("assistant tool result requires reconciliation, runId={}, stepId={}, cause={}, errorCode={}",
                    dispatch.runId(), dispatch.stepId(), uncertain.getClass().getSimpleName(), safeToolErrorCode(uncertain));
            return markUnknown(dispatch, "TOOL_RESULT_UNKNOWN");
        }
    }

    private void checkpointToolExecution(Dispatch dispatch)
    {
        tx().execute(status -> {
            Locked locked = lockDispatch(dispatch);
            AidRuntimeRunStep child = locked.child();
            Date now = new Date();
            require(matches(child, dispatch.stepLease(), now), "STEP_LEASE_CHANGED");
            require(!Boolean.TRUE.equals(locked.run().getStopRequested()), "OPERATION_STOP_REQUESTED");
            require("READY".equals(child.getStatus())||waitingResource(child), "OPERATION_STEP_STATE_CHANGED");
            child.setStatus("SUBMITTING");
            touchUpdate(child, dispatch.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            return null;
        });
    }

    static boolean definiteReadOutputFailure(RuntimeToolPreparedInvocation prepared, boolean operationBillable,
                                              RuntimeToolOutputValidationException rejected)
    {
        return prepared != null && rejected != null && !operationBillable
                && rejected.isDefiniteReadFailure()
                && Objects.equals(prepared.getToolCode(), rejected.getToolCode());
    }

    /** Keep only stable diagnostic codes; never log provider, request or SQL content. */
    private static String safeToolErrorCode(Throwable error)
    {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++)
        {
            if (current instanceof ServiceException service)
            {
                String detail = service.getDetailMessage();
                if (detail != null && detail.matches("(?:CANVAS|RUNTIME|OPERATION|BUDGET|ASSISTANT|TOOL|MEDIA|ARTIFACT|BILLING|SKILL|SOURCE)_[A-Z0-9_]{1,70}")) return detail;
                String message = service.getMessage();
                if (message != null && message.matches("(?:CANVAS|RUNTIME|OPERATION|BUDGET|ASSISTANT|TOOL|MEDIA|ARTIFACT|BILLING|SKILL|SOURCE)_[A-Z0-9_]{1,70}")) return message;
            }
            if (current instanceof java.sql.SQLException sql)
            {
                String state = sql.getSQLState();
                if (state != null && state.matches("[A-Z0-9]{5}")) return "SQL_STATE_" + state;
            }
            current = current.getCause();
        }
        return "UNCLASSIFIED";
    }

    private AssistantPlanExecutionResult executeSkill(Dispatch dispatch)
    {
        if (dispatch.approval().getExpiresAt() == null
                || !dispatch.approval().getExpiresAt().after(new Date()))
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK", "OPERATION_APPROVAL_EXPIRED", false);
        SkillInvocationVO existing;
        try
        {
            existing = skillBridge.findByAssistantIdentity(dispatch.runId(), dispatch.stepId(),
                    dispatch.step().getStableStepId(), dispatch.step().getAttemptNo(), dispatch.run().getUserId());
        }
        catch (RuntimeException lookupUnknown)
        {
            return markUnknown(dispatch, "SKILL_IDENTITY_UNKNOWN");
        }
        if (existing != null) return finishSkill(dispatch, existing);
        AssistantFixedSkillInvocation invocation;
        try
        {
            invocation = fixedSkillInvocation(dispatch);
        }
        catch (com.aid.assistant.skill.AssistantSkillPreparationRejectedException rejected)
        {
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK", rejected.getMessage(), false);
        }
        try
        {
            budgetPolicies.assertProviderSubmission(dispatch.runId(), dispatch.approval().getBillingAuthorizationId(),
                    dispatch.approval().getBillingAuthorizationRevision(), dispatch.persisted().operationScope());
            requireSkillSubmissionReferenceContext(dispatch.run().getUserId(), dispatch.session(),
                    dispatch.run(), dispatch.persisted().executionManifestJson());
        }
        catch (com.aid.assistant.skill.AssistantSkillPreparationRejectedException rejectedReference)
        {
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK", rejectedReference.getMessage(), false);
        }
        catch (AssistantBudgetAdmissionRejectedException rejectedBeforeProvider)
        {
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK",
                    rejectedBeforeProvider.getRuntimeErrorCode(), false);
        }
        try
        {
            SkillInvocationVO result = skillBridge.invokeFixedVersion(
                    invocation, dispatch.run().getUserId(), dispatch.runLease().getOwner());
            return finishSkill(dispatch, result);
        }
        catch (com.aid.assistant.skill.AssistantSkillPreparationRejectedException rejected)
        {
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK", rejected.getMessage(), false);
        }
        catch (RuntimeException uncertain)
        {
            try
            {
                existing = skillBridge.findByAssistantIdentity(dispatch.runId(), dispatch.stepId(),
                        dispatch.step().getStableStepId(), dispatch.step().getAttemptNo(), dispatch.run().getUserId());
            }
            catch (RuntimeException lookupUnknown)
            {
                existing = null;
            }
            if (existing != null) return finishSkill(dispatch, existing);
            log.info("assistant skill result requires reconciliation, runId={}, stepId={}, cause={}",
                    dispatch.runId(), dispatch.stepId(), uncertain.getClass().getSimpleName());
            return markUnknown(dispatch, "SKILL_RESULT_UNKNOWN");
        }
    }

    private AssistantPlanExecutionResult reconcileExisting(Dispatch dispatch, AidRuntimeRunStep step)
    {
        if (dispatch.persisted().isTool())
        {
            if(waitingResource(step)){
                Dispatch claimed=claimForReconciliation(dispatch);
                // executeTool repeats current permission, approval, budget and stop checks.
                // This is admission of an unsubmitted intent, never resubmission of an unknown job.
                return claimed==null?reconciling(dispatch,"STEP_LEASE_UNAVAILABLE")
                        :requiresPreSubmissionCancellation(claimed)?cancelBeforeProvider(claimed):executeTool(claimed);
            }
            RuntimeToolRegistry.Descriptor descriptor = toolRegistry.require(step.getToolCode());
            if ("REPLAY_SAFE".equals(descriptor.recoveryPolicy()))
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                return claimed == null ? reconciling(dispatch, "STEP_LEASE_UNAVAILABLE")
                        : executeTool(claimed);
            }
            if (!"RECONCILE_TASK".equals(descriptor.recoveryPolicy()))
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                if (claimed == null) return reconciling(dispatch, "STEP_LEASE_UNAVAILABLE");
                RuntimeToolResult reconciled;
                try { reconciled = toolRegistry.reconcilePrepared(prepared(claimed)); }
                catch (RuntimeException uncertain) { return markUnknown(claimed, "TOOL_RECEIPT_LOOKUP_UNKNOWN"); }
                return finishTool(claimed, reconciled);
            }
            AidMediaTask task = authoritativeTask(dispatch.run(), step);
            if (task == null)
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                return claimed == null ? reconciling(dispatch, "STEP_LEASE_UNAVAILABLE")
                        : reconcileBeforeMediaEntry(claimed);
            }
            MediaTaskCancellationResult cancellation = null;
            if (Boolean.TRUE.equals(dispatch.run().getStopRequested())
                    && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
            {
                try
                {
                    cancellation = taskCompletionService.cancelBudgetManagedUnsubmittedTask(task.getId());
                }
                catch (RuntimeException uncertain)
                {
                    log.info("assistant task cancellation requires reconciliation, runId={}, stepId={}, taskId={}, cause={}",
                            dispatch.runId(), dispatch.stepId(), task.getId(),
                            uncertain.getClass().getSimpleName());
                    cancellation = MediaTaskCancellationResult.RECONCILE_ONLY;
                }
                task = authoritativeTask(dispatch.run(), step);
                if (cancellation == MediaTaskCancellationResult.RECONCILE_ONLY)
                    return markCancellationUnknown(dispatch, "TASK_CANCELLATION_RECONCILING");
                if (task == null) return markCancellationUnknown(dispatch, "TASK_IDENTITY_UNKNOWN");
                boolean terminalTask = List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED")
                        .contains(task.getStatus());
                if (cancellation == MediaTaskCancellationResult.CANCELLED
                        && !List.of("CANCELLED", "CANCELED").contains(task.getStatus()))
                    return markCancellationUnknown(dispatch, "TASK_CANCELLATION_STATE_CHANGED");
                if (cancellation == MediaTaskCancellationResult.ALREADY_TERMINAL && !terminalTask)
                    return markCancellationUnknown(dispatch, "TASK_TERMINAL_STATE_CHANGED");
            }
            if (List.of("PENDING", "QUEUED", "WAIT_POLL", "WAIT_CALLBACK", "PROCESSING", "RUNNING").contains(task.getStatus()))
                return new AssistantPlanExecutionResult(step.getId(), "WAITING_TASK", "WAIT_TASK",
                        outputs(step), task.getId(), "Tool task is still running.", null, false);
            if ("SUCCEEDED".equals(task.getStatus()))
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                if (claimed == null) return reconciling(dispatch, "STEP_LEASE_UNAVAILABLE");
                if ("creative.text.submit".equals(step.getToolCode()))
                    return materializeCreative(claimed, task);
                return materializeMedia(claimed, task);
            }
            if (List.of("FAILED", "CANCELLED", "CANCELED").contains(task.getStatus()))
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                if (claimed == null) return reconciling(dispatch, "STEP_LEASE_UNAVAILABLE");
                if (List.of("CANCELLED", "CANCELED").contains(task.getStatus()) && (cancellation != null
                        || "RUNTIME_REPLAN_CANCELLED_BEFORE_PROVIDER_ENTRY".equals(task.getErrorMessage())))
                    return finishCancelledTask(claimed, task);
                return finishFailedTask(claimed, task);
            }
            return reconciling(dispatch, "TASK_STATUS_UNKNOWN");
        }
        SkillInvocationVO skill;
        try
        {
            skill = skillBridge.findByAssistantIdentity(dispatch.runId(), dispatch.stepId(),
                    step.getStableStepId(), step.getAttemptNo(), dispatch.run().getUserId());
            if (skill == null && Boolean.TRUE.equals(dispatch.run().getStopRequested()))
            {
                Dispatch claimed = claimForReconciliation(dispatch);
                return claimed == null ? reconciling(dispatch, "STEP_LEASE_UNAVAILABLE")
                        : cancelStoppedSkillWithNoInvocation(claimed);
            }
            if (skill != null && Boolean.TRUE.equals(dispatch.run().getStopRequested()))
                skill = skillBridge.reconcileStoppedInvocation(dispatch.runId(), dispatch.stepId(),
                        step.getStableStepId(), step.getAttemptNo(), dispatch.run().getUserId(),
                        dispatch.runLease().getOwner());
        }
        catch (RuntimeException uncertain)
        {
            return reconciling(dispatch, "SKILL_IDENTITY_UNKNOWN");
        }
        if (skill == null) return reconciling(dispatch, "SKILL_IDENTITY_UNKNOWN");
        Dispatch claimed = claimForReconciliation(dispatch);
        if (claimed != null && !"RECONCILING".equals(dispatch.run().getStatus())
                && !Boolean.TRUE.equals(dispatch.run().getStopRequested()) && needsMethodRecovery(skill)) {
            try { skill=skillBridge.invokeFixedVersion(fixedSkillInvocation(claimed),
                    claimed.run().getUserId(),claimed.runLease().getOwner()); }
            catch(RuntimeException staleAuthority){return reconciling(claimed,"SKILL_RECOVERY_AUTHORITY_PENDING");}
        }
        return claimed == null ? reconciling(dispatch, "STEP_LEASE_UNAVAILABLE") : finishSkill(claimed, skill);
    }

    private static boolean needsMethodRecovery(SkillInvocationVO skill) {
        if(skill==null||!"METHOD".equals(skill.getOperation())
                ||!List.of("CREATED","RUNNING").contains(Objects.toString(skill.getStatus(),"")))return false;
        if(List.of("METHOD_RETRY_PENDING","VALIDATING").contains(Objects.toString(skill.getStage(),"")))return true;
        if(skill.getTasks()==null||skill.getTasks().isEmpty())return true;
        SkillInvocationVO.TaskView last=skill.getTasks().stream().filter(item->"method".equals(item.getStepKey()))
                .max(java.util.Comparator.comparingInt(item->Objects.requireNonNullElse(item.getWorkflowAttempt(),0))).orElse(null);
        return last==null||last.getMediaTaskId()==null
                ||List.of("SUCCEEDED","FAILED").contains(Objects.toString(last.getMediaStatus(),""));
    }

    /**
     * The same session/run/operation locks fence the FIRST action of Skill creation.
     * An absent invocation is proof only inside these locks, after a stop request and
     * with no task/child/reservation. Unknown money or an existing invocation stays reconciled.
     */
    private AssistantPlanExecutionResult cancelStoppedSkillWithNoInvocation(Dispatch dispatch)
    {
        return tx().execute(status -> {
            Locked locked = lockDispatch(dispatch);
            AidRuntimeRun run = locked.run();
            AidRuntimeRunStep child = locked.child();
            require(Boolean.TRUE.equals(run.getStopRequested()), "OPERATION_CANCELLATION_NOT_REQUESTED");
            require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
            if (!"SKILL".equals(child.getStepKind())
                    || !"ASSISTANT_SKILL".equals(child.getOperationKind())
                    || !"RECONCILING".equals(child.getStatus())
                    || child.getChildRunId() != null || child.getMediaTaskId() != null
                    || child.getReservationId() != null || authoritativeTask(run, child) != null)
                return reconciling(dispatch, "SKILL_IDENTITY_UNKNOWN");
            SkillInvocationVO existing = skillBridge.findByAssistantIdentity(run.getId(), child.getId(),
                    child.getStableStepId(), child.getAttemptNo(), run.getUserId());
            if (existing != null) return reconciling(dispatch, "SKILL_IDENTITY_UNKNOWN");
            Date now = new Date();
            child.setStatus("CANCELLED_PRETASK");
            child.setProviderAcceptanceState("NOT_SUBMITTED");
            child.setErrorMessage("SKILL_STOPPED_BEFORE_INVOCATION");
            child.setLeaseOwner(null);
            child.setLeaseUntil(null);
            touchUpdate(child, dispatch.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            convergeRunGraph(locked, child, dispatch.runLease().getOwner(), now, true);
            return view(child);
        });
    }

    private Dispatch claimForReconciliation(Dispatch dispatch)
    {
        Date now = new Date();
        Optional<RuntimeLease> lease = leaseService.tryClaimStep(dispatch.runId(), dispatch.stepId(),
                dispatch.runLease(), now, new Date(now.getTime() + STEP_LEASE_MILLIS));
        return lease.map(dispatch::withStepLease).orElse(null);
    }

    private AssistantPlanExecutionResult finishTool(Dispatch dispatch, RuntimeToolResult result)
    {
        require(result != null && StrUtil.isNotBlank(result.getStatus()), "TOOL_RESULT_INVALID");
        if ("UNKNOWN".equals(result.getStatus())) return markUnknown(dispatch, "TOOL_RESULT_UNKNOWN");
        if("WAITING_RESOURCE".equals(result.getStatus())){
            require(!dispatch.persisted().billable()&&result.getMediaTaskId()==null,"TOOL_RESOURCE_WAIT_INVALID");
            require(StrUtil.isBlank(result.getOutputJson())||com.aid.runtime.tool.RuntimeResourceWait.isMetadata(result.getOutputJson()),"TOOL_RESOURCE_WAIT_INVALID");
            return tx().execute(status->updateChild(dispatch,"RECONCILING","NOT_SUBMITTED",result.getOutputJson(),List.of(),null,
                "TOOL_WAITING_RESOURCE",result.getSafeSummary(),true));
        }
        if ("WAITING_TASK".equals(result.getStatus()))
        {
            require(result.getMediaTaskId() != null, "TOOL_TASK_MISSING");
            return finishWaitingTask(dispatch, result);
        }
        if ("FAILED".equals(result.getStatus()))
        {
            if(result.isSubmissionNotEntered()) {
                require(!dispatch.persisted().billable()&&result.getMediaTaskId()==null
                        &&(result.getOutputArtifactRefs()==null||result.getOutputArtifactRefs().isEmpty()),"TOOL_NEGATIVE_ENTRY_INVALID");
                return tx().execute(status->{
                    Locked locked=lockDispatch(dispatch);AidRuntimeRunStep child=locked.child();
                    require(matches(child,dispatch.stepLease(),new Date()),"STEP_LEASE_CHANGED");
                    require(authoritativeTask(locked.run(),child)==null&&child.getChildRunId()==null
                            &&emptyArtifactRefs(child.getOutputArtifactRef())
                            &&(StrUtil.isBlank(child.getOutputJson())||com.aid.runtime.tool.RuntimeResourceWait.isMetadata(child.getOutputJson())),"TOOL_NEGATIVE_ENTRY_CONFLICT");
                    child.setProviderAcceptanceState("NOT_SUBMITTED");
                    boolean stopping=Boolean.TRUE.equals(locked.run().getStopRequested());
                    return settleUnsubmittedLocked(locked,dispatch,stopping?"CANCELLED_PRETASK":"FAILED_PRETASK",
                            stopping?"OPERATION_STOPPED_BEFORE_SUBMISSION":result.getErrorCode());
                });
            }
            if (dispatch.persisted().billable() && result.getMediaTaskId() == null)
                return markUnknown(dispatch, "BILLABLE_FAILURE_UNCERTAIN");
            return tx().execute(status -> updateChild(dispatch, "FAILED", "ACCEPTED", result.getOutputJson(),
                    result.getOutputArtifactRefs(), result.getMediaTaskId(), result.getErrorCode(),
                    result.getSafeSummary(), result.isRetryable()));
        }
        require("SUCCEEDED".equals(result.getStatus()), "TOOL_RESULT_INVALID");
        if (dispatch.persisted().billable())
        {
            require(result.getMediaTaskId() != null, "TOOL_TASK_MISSING");
            return finishWaitingTask(dispatch, result);
        }
        return tx().execute(status -> updateChild(dispatch, "SUCCEEDED",
                "NOT_APPLICABLE", result.getOutputJson(),
                result.getOutputArtifactRefs(), result.getMediaTaskId(), null,
                result.getSafeSummary(), false));
    }

    /**
     * Archives the durable task identity without claiming Provider acceptance. The media intent
     * transaction may race this outer receipt: if it has already advanced the child to
     * SUBMITTING/INFLIGHT, preserve that stronger state; otherwise expose the task as waiting
     * while leaving NOT_SUBMITTED for the provider-admission transaction to claim exactly once.
     */
    private AssistantPlanExecutionResult finishWaitingTask(Dispatch dispatch, RuntimeToolResult result)
    {
        return tx().execute(status -> {
            Locked locked = lockDispatch(dispatch);
            AidRuntimeRunStep child = locked.child();
            if (TERMINAL_STEP.contains(child.getStatus()))
            {
                convergeRunGraph(locked, child, dispatch.runLease().getOwner(), new Date(), false);
                return view(child);
            }
            require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
            require(Objects.equals(child.getMediaTaskId(), result.getMediaTaskId()),
                    "TASK_IDENTITY_CHANGED");

            if ("READY".equals(child.getStatus())
                    && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState()))
            {
                child.setStatus("INFLIGHT");
            }
            else
            {
                boolean monotonic = ("SUBMITTING".equals(child.getStatus())
                        && "SUBMITTING".equals(child.getProviderAcceptanceState()))
                        || ("INFLIGHT".equals(child.getStatus())
                        && List.of("NOT_SUBMITTED", "SUBMITTING", "ACCEPTED")
                        .contains(child.getProviderAcceptanceState()))
                        || ("RECONCILING".equals(child.getStatus())
                        && "UNKNOWN".equals(child.getProviderAcceptanceState()));
                require(monotonic, "OPERATION_SUBMISSION_STATE_CHANGED");
            }
            if (result.getOutputJson() != null) child.setOutputJson(result.getOutputJson());
            child.setOutputArtifactRef(JSON.toJSONString(List.of()));
            child.setErrorMessage(null);
            child.setLeaseOwner(null);
            child.setLeaseUntil(null);
            Date now = new Date();
            touchUpdate(child, dispatch.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            convergeRunGraph(locked, child, dispatch.runLease().getOwner(), now, true);
            return view(child);
        });
    }

    private AssistantPlanExecutionResult materializeCreative(Dispatch dispatch, AidMediaTask task)
    {
        CreativeRequests.TextSubmit input = JSON.parseObject(dispatch.persisted().argumentsJson(),
                CreativeRequests.TextSubmit.class);
        CreativeMaterializationRequests.MaterializeText text = new CreativeMaterializationRequests.MaterializeText();
        text.setRunId(dispatch.runId());
        text.setStepId(dispatch.stepId());
        text.setMediaTaskId(task.getId());
        text.setLogicalArtifactId(input.getLogicalArtifactId());
        text.setArtifactType(input.getArtifactType());
        text.setContextManifestJson(dispatch.persisted().context().getString("manifestJson"));
        text.setSources(input.getSources());
        ArtifactRef artifact;
        if ("REVIEW_REPORT".equals(input.getArtifactType()))
        {
            CreativeRequests.PreparedTextSubmit prepared = JSON.parseObject(
                    dispatch.persisted().preparedPayloadJson(), CreativeRequests.PreparedTextSubmit.class);
            require(prepared != null && prepared.getCreativeRequest() != null
                    && Objects.equals(input.getTargetArtifactId(),
                    prepared.getCreativeRequest().getTargetArtifactId()), "REVIEW_SUBJECT_IDENTITY_INVALID");
            CreativeMaterializationRequests.MaterializeReview review =
                    new CreativeMaterializationRequests.MaterializeReview();
            review.setText(text);
            review.setTargetArtifactId(input.getTargetArtifactId());
            review.setSubjectArtifactRef(prepared.getSubjectArtifactRef());
            review.setSubjectContentHash(prepared.getSubjectContentHash());
            review.setSubmissionPromptDigest(prepared.getSubmissionPromptDigest());
            CreativeViews.ReviewMaterialization result = artifactMaterializer.materializeReview(
                    review, dispatch.runLease(), dispatch.stepLease());
            artifact = result == null ? null : result.getArtifact();
        }
        else
        {
            artifact = artifactMaterializer.materializeText(text, dispatch.runLease(), dispatch.stepLease());
        }
        require(artifact != null && StrUtil.isNotBlank(artifact.getRef())
                && "FINALIZED".equals(artifact.getStatus()), "ARTIFACT_MATERIALIZATION_FAILED");
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(artifact));
        result.setOutputArtifactRefs(List.of(artifact.getRef()));
        result.setMediaTaskId(task.getId());
        result.setSafeSummary("Creative artifact was finalized.");
        return finishMaterialized(dispatch, result);
    }

    private AssistantPlanExecutionResult materializeMedia(Dispatch dispatch, AidMediaTask task)
    {
        JSONObject planStep = dispatch.persisted().plan().getJSONObject("planStep");
        JSONArray outputIds = planStep == null ? null : planStep.getJSONArray("outputLogicalArtifactIds");
        require(outputIds != null && outputIds.size() == 1
                && StrUtil.isNotBlank(outputIds.getString(0)), "MEDIA_ARTIFACT_OUTPUT_INVALID");
        CreativeMaterializationRequests.MaterializeMedia request =
                new CreativeMaterializationRequests.MaterializeMedia();
        request.setRunId(dispatch.runId());
        request.setStepId(dispatch.stepId());
        request.setMediaTaskId(task.getId());
        request.setLogicalArtifactId(outputIds.getString(0));
        request.setContextManifestJson(dispatch.persisted().context().getString("manifestJson"));
        request.setSources(List.of());
        request.setSummary("Media output created by " + dispatch.step().getToolCode() + ".");
        JSONObject metadata = new JSONObject();
        metadata.put("toolCode", dispatch.step().getToolCode());
        metadata.put("contractVersion", dispatch.bound().bindingVersion());
        metadata.put("argumentsDigest", dispatch.persisted().argumentsDigest());
        metadata.put("planDefinitionDigest", dispatch.bound().planDefinitionDigest());
        JSONObject preparedPayload = JSON.parseObject(dispatch.persisted().preparedPayloadJson());
        JSONObject skillProof = preparedPayload == null ? null
                : preparedPayload.getJSONObject("skillProvenance");
        if (skillProof != null) {
            JSONObject projected = new JSONObject();
            List.of("schemaVersion", "skillCode", "skillVersionId", "skillVersionCode",
                    "packageDigest", "capabilityCode", "modelCode", "inputDigest",
                    "promptDigest", "inputSchemaDigest", "outputSchemaDigest",
                    "resources", "resourceDigest").forEach(field -> {
                if (skillProof.containsKey(field)) projected.put(field, skillProof.get(field));
            });
            metadata.put("skillProvenance", projected);
        }
        request.setMetadataJson(AssistantPlanExecutionJson.canonical(metadata));
        CreativeViews.MediaMaterialization materialized = artifactMaterializer.materializeMedia(
                request, dispatch.runLease(), dispatch.stepLease());
        require(materialized != null && List.of("WAITING_TASK", "FINALIZED").contains(materialized.getStatus()),
                "MEDIA_ARTIFACT_MATERIALIZATION_FAILED");
        if ("WAITING_TASK".equals(materialized.getStatus()))
            return tx().execute(status -> updateChild(dispatch, "INFLIGHT", "ACCEPTED", null,
                    List.of(), task.getId(), null, "Media output is awaiting durable storage.", false));
        require(materialized.getArtifacts() != null && !materialized.getArtifacts().isEmpty()
                && materialized.getArtifacts().stream().allMatch(value -> value != null
                && StrUtil.isNotBlank(value.getRef()) && "FINALIZED".equals(value.getStatus())),
                "MEDIA_ARTIFACT_MATERIALIZATION_FAILED");
        List<AssistantMediaTaskTargetVerifier> targetChecks = mediaTargetVerifiers.stream()
                .filter(verifier -> verifier.supportedToolCodes().contains(dispatch.step().getToolCode())).toList();
        require(targetChecks.size() <= 1, "MEDIA_TARGET_VERIFIER_AMBIGUOUS");
        if (!targetChecks.isEmpty() && !targetChecks.get(0).isTargetPersisted(dispatch.run(), dispatch.step(), task.getId())) {
            return tx().execute(status -> updateChild(dispatch, "INFLIGHT", "ACCEPTED", null,
                    List.of(), task.getId(), null, "Media result is awaiting its target persistence.", false));
        }
        RuntimeToolResult result = RuntimeToolResult.succeeded(JSON.toJSONString(materialized));
        result.setOutputArtifactRefs(materialized.getArtifacts().stream().map(ArtifactRef::getRef).toList());
        result.setMediaTaskId(task.getId());
        result.setSafeSummary("Media artifacts were finalized.");
        return finishMaterialized(dispatch, result);
    }

    /** A billable task becomes successful only after the materializer returns durable ArtifactRefs. */
    private AssistantPlanExecutionResult finishMaterialized(Dispatch dispatch, RuntimeToolResult result)
    {
        require(dispatch.persisted().billable() && result != null
                && "SUCCEEDED".equals(result.getStatus())
                && result.getMediaTaskId() != null
                && result.getOutputArtifactRefs() != null
                && !result.getOutputArtifactRefs().isEmpty()
                && result.getOutputArtifactRefs().stream().allMatch(StrUtil::isNotBlank),
                "ARTIFACT_MATERIALIZATION_FAILED");
        return tx().execute(status -> updateChild(dispatch, "SUCCEEDED", "ACCEPTED",
                result.getOutputJson(), result.getOutputArtifactRefs(), result.getMediaTaskId(),
                null, result.getSafeSummary(), false));
    }

    private AssistantPlanExecutionResult finishSkill(Dispatch dispatch, SkillInvocationVO result)
    {
        if (result == null || result.getRunId() == null || StrUtil.isBlank(result.getStatus()))
            return markUnknown(dispatch, "SKILL_RESULT_UNKNOWN");
        String state;
        String next;
        String error = null;
        if ("SUCCEEDED".equals(result.getStatus())) { state = "SUCCEEDED"; next = "CONTINUE_AGENT"; }
        else if (Boolean.TRUE.equals(dispatch.run().getStopRequested())
                && List.of("CANCELED", "CANCELLED").contains(result.getStatus()))
        { state = "CANCELLED"; next = null; }
        else if (List.of("FAILED", "CANCELED", "CANCELLED").contains(result.getStatus()))
        { state = "FAILED"; next = "OPERATION_FAILED"; error = "SKILL_EXECUTION_FAILED"; }
        else if ("WAITING_INPUT".equals(result.getStatus()))
        { state = "RECONCILING"; next = "RECONCILE_OPERATION"; error = "SKILL_INPUT_HANDOFF_UNSUPPORTED"; }
        else if ("RECONCILING".equals(result.getStatus()))
        { state = "RECONCILING"; next = "RECONCILE_OPERATION"; error = "SKILL_RESULT_UNKNOWN"; }
        else { state = "INFLIGHT"; next = "WAIT_SKILL"; }
        String output = skillReceipt(result);
        String finalError = error;
        return tx().execute(status -> updateChild(dispatch, state,
                "ACCEPTED", output, List.of(), null,
                finalError, skillSummary(result), false, next, result.getRunId()));
    }

    private String skillReceipt(SkillInvocationVO result)
    {
        JSONObject value = new JSONObject();
        value.put("schemaVersion", "assistant-skill-observation/v1");
        value.put("skillRunId", String.valueOf(result.getRunId()));
        value.put("skillCode", result.getSkillCode());
        value.put("skillVersionId", result.getSkillVersionId() == null
                ? null : String.valueOf(result.getSkillVersionId()));
        value.put("status", result.getStatus());
        value.put("stage", result.getStage());
        value.put("operation", result.getOperation());
        value.put("formatStatus", result.getFormatStatus());
        if ("SUCCEEDED".equals(result.getStatus()) && StrUtil.isNotBlank(result.getAssistantMessage()))
        {
            require(result.getAssistantMessage().length() <= 50_000, "SKILL_OBSERVATION_TOO_LARGE");
            value.put("observation", result.getAssistantMessage());
        }
        if ("WAITING_INPUT".equals(result.getStatus()))
            value.put("requiredInput", result.getRequiredInput());
        String json = AssistantPlanExecutionJson.canonical(value);
        require(json.length() <= 100_000, "SKILL_OBSERVATION_TOO_LARGE");
        return json;
    }

    private String skillSummary(SkillInvocationVO result)
    {
        if ("WAITING_INPUT".equals(result.getStatus()))
            return "The Skill requested an unsupported input handoff and requires reconciliation.";
        if ("SUCCEEDED".equals(result.getStatus())) return "The fixed Skill method completed.";
        if (List.of("CANCELED", "CANCELLED").contains(result.getStatus()))
            return "The fixed Skill method was cancelled after STOP.";
        if ("FAILED".equals(result.getStatus()))
            return "The fixed Skill method failed.";
        return "The fixed Skill method is still running.";
    }

    private AssistantPlanExecutionResult finishFailedTask(Dispatch dispatch, AidMediaTask task)
    {
        JSONObject receipt = new JSONObject();
        receipt.put("schemaVersion", "assistant-tool-failure/v1");
        receipt.put("mediaTaskId", String.valueOf(task.getId()));
        receipt.put("status", task.getStatus());
        receipt.put("billingStatus", task.getBillingStatus());
        receipt.put("errorCode", "MEDIA_TASK_FAILED");
        return tx().execute(status -> updateChild(dispatch, "FAILED", "ACCEPTED",
                AssistantPlanExecutionJson.canonical(receipt), List.of(), task.getId(),
                "MEDIA_TASK_FAILED", "Media generation did not complete.", false));
    }

    /** Converges a trusted STOP cancellation without pretending the Provider accepted the task. */
    private AssistantPlanExecutionResult finishCancelledTask(Dispatch dispatch, AidMediaTask task)
    {
        JSONObject receipt = new JSONObject();
        receipt.put("schemaVersion", "assistant-tool-cancellation/v1");
        receipt.put("mediaTaskId", String.valueOf(task.getId()));
        receipt.put("status", task.getStatus());
        receipt.put("billingStatus", task.getBillingStatus());
        receipt.put("errorCode", "OPERATION_STOPPED_BEFORE_SUBMISSION");
        return tx().execute(status -> updateChild(dispatch, "CANCELLED_PRETASK", "NOT_SUBMITTED",
                AssistantPlanExecutionJson.canonical(receipt), List.of(), task.getId(),
                "OPERATION_STOPPED_BEFORE_SUBMISSION", "Operation was cancelled before Provider submission.", false));
    }

    /**
     * Persists an ambiguous cancellation outcome without erasing stronger admission evidence that
     * may have won concurrently.  A later recovery pass reconciles the exact durable task.
     */
    private AssistantPlanExecutionResult markCancellationUnknown(Dispatch dispatch, String code)
    {
        Dispatch claimed = claimForReconciliation(dispatch);
        if (claimed == null) return reconciling(dispatch, "STEP_LEASE_UNAVAILABLE");
        return tx().execute(status -> {
            Locked locked = lockDispatch(claimed);
            AidRuntimeRunStep child = locked.child();
            if (TERMINAL_STEP.contains(child.getStatus()))
            {
                convergeRunGraph(locked, child, claimed.runLease().getOwner(), new Date(), false);
                return view(child);
            }
            require(matches(child, claimed.stepLease(), new Date()), "STEP_LEASE_CHANGED");
            child.setStatus("RECONCILING");
            if ("NOT_SUBMITTED".equals(child.getProviderAcceptanceState()))
                child.setProviderAcceptanceState("UNKNOWN");
            child.setErrorMessage(code);
            child.setLeaseOwner(null);
            child.setLeaseUntil(null);
            Date now = new Date();
            touchUpdate(child, claimed.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            convergeRunGraph(locked, child, claimed.runLease().getOwner(), now, true);
            return view(child);
        });
    }

    private AssistantPlanExecutionResult markUnknown(Dispatch dispatch, String code)
    {
        return tx().execute(status -> updateChild(dispatch, "RECONCILING", "UNKNOWN", null,
                List.of(), null, code, "Operation outcome requires reconciliation.", false));
    }

    /**
     * New submission rejection and recovery rejection have different evidence. A READY child can
     * still prove that Provider entry never happened. An already UNKNOWN child cannot make that
     * claim; only the fixed nonbillable READ + REPLAY_SAFE contract can end after its authorization
     * expires or another deterministic pre-entry gate rejects the same-key recovery.
     */
    private AssistantPlanExecutionResult settleToolRejectedBeforeEntry(Dispatch dispatch, String errorCode)
    {
        AidRuntimeRunStep step = dispatch.step();
        if (provenInitialToolNotEntered(step))
            return settleProvenNotEntered(dispatch, "FAILED_PRETASK", errorCode, false);
        if (StrUtil.isBlank(errorCode) || !errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))
            return reconciling(dispatch, "TOOL_RESULT_UNKNOWN");
        RuntimeToolRegistry.Descriptor descriptor;
        try { descriptor = toolRegistry.require(step == null ? null : step.getToolCode()); }
        catch (RuntimeException uncertain) { return reconciling(dispatch, "TOOL_RESULT_UNKNOWN"); }
        if (!definiteReplaySafeReadReconciliation(descriptor,
                dispatch.persisted().billable(), step, dispatch.bound().bindingCode()))
            return reconciling(dispatch, "TOOL_RESULT_UNKNOWN");
        return failReplaySafeReadReconciliation(dispatch, errorCode);
    }

    private AssistantPlanExecutionResult settleInvalidToolSnapshot(Dispatch dispatch, String errorCode)
    {
        return provenInitialToolNotEntered(dispatch.step())
                ? failInvalidReadySnapshot(dispatch, errorCode)
                : reconciling(dispatch, "TOOL_RESULT_UNKNOWN");
    }

    static boolean provenInitialToolNotEntered(AidRuntimeRunStep step)
    {
        return step != null && "READY".equals(step.getStatus())
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                && step.getMediaTaskId() == null;
    }

    private AssistantPlanExecutionResult failReplaySafeReadReconciliation(Dispatch dispatch, String errorCode)
    {
        return tx().execute(status -> {
            Locked locked = lockDispatch(dispatch);
            AidRuntimeRunStep child = locked.child();
            RuntimeToolRegistry.Descriptor descriptor = toolRegistry.require(child.getToolCode());
            require(definiteReplaySafeReadReconciliation(descriptor,
                    dispatch.persisted().billable(), child, dispatch.bound().bindingCode()),
                    "TOOL_RESULT_UNKNOWN");
            require("ACCEPTED".equals(locked.approval().getStatus())
                            && dispatch.persisted().isTool()
                            && dispatch.persisted().operationScope() != null
                            && Objects.equals(dispatch.persisted().operationScope().getToolCode(), child.getToolCode())
                            && Objects.equals(descriptor.contractVersion(), dispatch.bound().bindingVersion())
                            && Objects.equals(descriptor.schemaDigest(), dispatch.bound().schemaDigest())
                            && authoritativeTask(locked.run(), child) == null
                            && matches(child, dispatch.stepLease(), new Date()),
                    "TOOL_RESULT_UNKNOWN");
            child.setStatus("FAILED");
            child.setErrorMessage(errorCode);
            child.setOutputArtifactRef("[]");
            child.setLeaseOwner(null);
            child.setLeaseUntil(null);
            Date now = new Date();
            touchUpdate(child, dispatch.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            convergeRunGraph(locked, child, dispatch.runLease().getOwner(), now, true);
            return view(child);
        });
    }

    static boolean definiteReplaySafeReadReconciliation(RuntimeToolRegistry.Descriptor descriptor,
                                                         boolean operationBillable,
                                                         AidRuntimeRunStep step,
                                                         String expectedToolCode)
    {
        return descriptor != null && step != null && !operationBillable
                && "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable()
                && "REPLAY_SAFE".equals(descriptor.recoveryPolicy())
                && "RECONCILING".equals(step.getStatus())
                && "UNKNOWN".equals(step.getProviderAcceptanceState())
                && step.getReservationId() == null && step.getMediaTaskId() == null
                && step.getChildRunId() == null && StrUtil.isBlank(step.getOutputJson())
                && emptyArtifactRefs(step.getOutputArtifactRef())
                && Objects.equals(descriptor.code(), step.getToolCode())
                && Objects.equals(expectedToolCode, step.getToolCode());
    }

    private static boolean emptyArtifactRefs(String value)
    {
        if (StrUtil.isBlank(value)) return true;
        try
        {
            JSONArray refs = JSON.parseArray(value);
            return refs != null && refs.isEmpty();
        }
        catch (RuntimeException invalid)
        {
            return false;
        }
    }

    /**
     * A control command that wins before Provider admission cancels the accepted child without
     * submitting it.  Runtime rows are kept non-terminal until Billing has durably released the
     * reservation; an uncertain release remains on the operation reconciliation path.
     */
    private AssistantPlanExecutionResult cancelBeforeProvider(Dispatch dispatch)
    {
        return settleProvenNotEntered(dispatch, "CANCELLED_PRETASK",
                "OPERATION_STOPPED_BEFORE_SUBMISSION", true);
    }

    private AssistantPlanExecutionResult settleProvenNotEntered(Dispatch dispatch, String terminalState,
                                                                  String errorCode, boolean stopRequired)
    {
        try
        {
            return tx().execute(status -> {
                Locked locked = lockDispatch(dispatch);
                AidRuntimeRun run = locked.run();
                AidRuntimeRunStep child = locked.child();
                if (stopRequired) require(Boolean.TRUE.equals(run.getStopRequested()),
                        "OPERATION_CANCELLATION_NOT_REQUESTED");
                require(provenUnsubmitted(child),
                        "OPERATION_ALREADY_SUBMITTED");
                require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
                require(authoritativeTask(run, child) == null, "OPERATION_TASK_IDENTITY_EXISTS");

                return settleUnsubmittedLocked(locked, dispatch, terminalState, errorCode);
            });
        }
        catch (InvalidPersistedSubmissionException invalidBudgetSnapshot)
        {
            log.error("assistant tool settlement found an invalid persisted budget identity, "
                            + "runId={}, stepId={}, reason={}", dispatch.runId(), dispatch.stepId(),
                    invalidBudgetSnapshot.reason());
            return failInvalidReadySnapshot(dispatch, "BUDGET_AUTHORIZATION_CHANGED");
        }
    }

    /**
     * A task-less UNKNOWN is recoverable only after adapter-owned durable negative entry proof.
     * Runtime session/run/approval/main/child locks and a new step fence block old workers;
     * any task, conflicting namespace, financial evidence or unsupported adapter stays UNKNOWN.
     * Quote/authorization expiry never prevents closing the original unsubmitted reservation.
     */
    private AssistantPlanExecutionResult reconcileBeforeMediaEntry(Dispatch dispatch)
    {
        try
        {
            return tx().execute(status -> {
                Locked locked = lockDispatch(dispatch);
                AidRuntimeRunStep child = locked.child();
                require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
                if (!dispatch.persisted().isTool() || !dispatch.persisted().billable()
                        || !"RECONCILING".equals(child.getStatus())
                        || !"UNKNOWN".equals(child.getProviderAcceptanceState())
                        || child.getMediaTaskId() != null || child.getChildRunId() != null
                        || child.getChildRunKind() != null || StrUtil.isNotBlank(child.getOutputJson())
                        || !emptyArtifactRefs(child.getOutputArtifactRef()) || child.getReservationId() == null)
                    return reconciling(dispatch, "TASK_IDENTITY_UNKNOWN");
                BillingBudgetViews.ReservationView original = reservation(dispatch);
                if (!List.of("RESERVING", "RESERVED").contains(original.getState())
                        || original.getMediaTaskId() != null || StrUtil.isBlank(original.getBillingTraceId())
                        || StrUtil.isNotBlank(original.getSettlementKey())
                        || !knownZero(original.getWalletFrozenAmount()) || !knownZero(original.getSettledAmount())
                        || !knownZero(original.getReleasedAmount()) || !knownZero(original.getWalletRefundedAmount()))
                    return reconciling(dispatch, "TASK_IDENTITY_UNKNOWN");
                if (toolRegistry.inspectSubmissionEntry(prepared(dispatch), budgetContext(dispatch))
                        != com.aid.runtime.tool.RuntimeToolProvider.SubmissionEntryEvidence.NO_ENTRY_PROVEN)
                    return reconciling(dispatch, "TASK_IDENTITY_UNKNOWN");
                // Only adapter-proven non-entry reaches the task namespace checks below.
                if (!knownEmpty(mediaTasks.selectAssistantEntryStepForUpdate(
                        com.aid.runtime.assistant.AssistantRuntimeSubmissionAdmissionHandler.BIZ_TASK_TYPE, child.getId()))
                        || !knownEmpty(mediaTasks.selectAssistantEntryCallForUpdate(child.getIdempotencyKey()))
                        || !knownEmpty(mediaTasks.selectAssistantEntryTrace(original.getBillingTraceId())))
                    return reconciling(dispatch, "TASK_IDENTITY_UNKNOWN");
                // Only this fully fenced proof may convert UNKNOWN into a known pre-task failure.
                child.setProviderAcceptanceState("NOT_SUBMITTED");
                return settleUnsubmittedLocked(locked, dispatch, "FAILED_PRETASK",
                        "OPERATION_CONFIRMED_BEFORE_MEDIA_ENTRY");
            });
        }
        catch (RuntimeException uncertain)
        {
            // The transaction rolls back all state/budget/event changes. Never replay this operation.
            log.info("assistant negative entry evidence unavailable, runId={}, stepId={}, cause={}",
                    dispatch.runId(), dispatch.stepId(), uncertain.getClass().getSimpleName());
            return reconciling(dispatch, "TASK_IDENTITY_UNKNOWN");
        }
    }

    private boolean knownZero(BigDecimal value) { return value != null && value.signum() == 0; }
    private boolean knownEmpty(List<?> value) { return value != null && value.isEmpty(); }

    /** Caller must hold Runtime locks and prove NOT_SUBMITTED before entering this helper. */
    private AssistantPlanExecutionResult settleUnsubmittedLocked(Locked locked, Dispatch dispatch,
                                                                  String terminalState, String errorCode)
    {
        AidRuntimeRun run = locked.run();
        AidRuntimeRunStep child = locked.child();
        require("NOT_SUBMITTED".equals(child.getProviderAcceptanceState()), "OPERATION_ALREADY_SUBMITTED");
        if (dispatch.persisted().isTool()) toolRegistry.releaseUnsubmitted(prepared(dispatch));
        Date now = new Date();
        child.setStatus(terminalState);
        child.setErrorMessage(errorCode);
        child.setLeaseOwner(null);
        child.setLeaseUntil(null);
        touchUpdate(child, dispatch.runLease().getOwner(), now);
        require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");

        if (child.getReservationId() != null)
        {
            BillingBudgetViews.ReservationView current = reservation(dispatch);
            BillingBudgetViews.ReservationView released = billingBudgets.releaseUnsubmittedReservation(
                    releaseRequest(dispatch, current));
            require(released != null && List.of("RELEASED", "RECONCILING").contains(released.getState()),
                    "RESERVATION_RELEASE_RESULT_INVALID");
            AidRuntimeRunStep persistedChild = steps.selectById(child.getId());
            require(persistedChild != null, "OPERATION_STEP_MISSING");
            if ("RECONCILING".equals(released.getState()))
            {
                AidRuntimeRun persistedRun = requireRun(run.getId(), true);
                boolean changed = !"RECONCILING".equals(persistedRun.getStatus())
                        || !"RECONCILE_OPERATION".equals(persistedRun.getNextAction());
                persistedRun.setStatus("RECONCILING");
                persistedRun.setNextAction("RECONCILE_OPERATION");
                persistedRun.setNextRetryAt(new Date(now.getTime() + 5_000L));
                if (changed) persistedRun.setStateRevision(longValue(persistedRun.getStateRevision()) + 1);
                touchUpdate(persistedRun, dispatch.runLease().getOwner(), now);
                require(runs.updateById(persistedRun) == 1, "RUNTIME_RUN_CHANGED");
                return view(persistedChild);
            }
            require("RELEASED".equals(persistedChild.getProviderAcceptanceState()),
                    "RESERVATION_RELEASE_STATE_CHANGED");
            child = persistedChild;
        }
        convergeRunGraph(new Locked(locked.session(), run, locked.approval(), locked.main(), child),
                child, dispatch.runLease().getOwner(), now, true);
        return view(child);
    }

    /**
     * A successful budget lookup proved that the persisted reservation is absent or belongs to a
     * different identity. The provider was never called, and no reservation can be safely released
     * under this operation identity, so fail the corrupted run without touching billing state.
     */
    private AssistantPlanExecutionResult failInvalidReadySnapshot(Dispatch dispatch, String errorCode)
    {
        return tx().execute(status -> {
            Locked locked = lockDispatch(dispatch);
            AidRuntimeRunStep child = locked.child();
            require("READY".equals(child.getStatus())
                            && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                            && child.getMediaTaskId() == null && child.getChildRunId() == null,
                    "OPERATION_ALREADY_SUBMITTED");
            require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
            require(authoritativeTask(locked.run(), child) == null, "OPERATION_TASK_IDENTITY_EXISTS");
            Date now = new Date();
            child.setStatus("FAILED_PRETASK");
            child.setErrorMessage(errorCode);
            child.setLeaseOwner(null);
            child.setLeaseUntil(null);
            touchUpdate(child, dispatch.runLease().getOwner(), now);
            require(steps.updateById(child) == 1, "OPERATION_STEP_CHANGED");
            convergeRunGraph(locked, child, dispatch.runLease().getOwner(), now, true);
            return view(child);
        });
    }

    private boolean requiresPreSubmissionCancellation(Dispatch dispatch)
    {
        return provenUnsubmitted(dispatch.step())
                && Boolean.TRUE.equals(dispatch.run().getStopRequested());
    }

    private static boolean provenUnsubmitted(AidRuntimeRunStep step) {
        return step != null && ("READY".equals(step.getStatus()) || waitingResource(step))
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                && step.getMediaTaskId() == null && step.getChildRunId() == null
                && (StrUtil.isBlank(step.getOutputJson())||waitingResource(step)
                    &&com.aid.runtime.tool.RuntimeResourceWait.isMetadata(step.getOutputJson()))
                && emptyArtifactRefs(step.getOutputArtifactRef());
    }

    private AssistantPlanExecutionResult updateChild(Dispatch dispatch, String state, String acceptance,
                                                       String outputJson, List<String> artifactRefs,
                                                       Long mediaTaskId, String errorCode, String summary,
                                                       boolean retryable)
    {
        return updateChild(dispatch, state, acceptance, outputJson, artifactRefs, mediaTaskId,
                errorCode, summary, retryable, nextAction(state));
    }

    private AssistantPlanExecutionResult updateChild(Dispatch dispatch, String state, String acceptance,
                                                       String outputJson, List<String> artifactRefs,
                                                       Long mediaTaskId, String errorCode, String summary,
                                                       boolean retryable, String nextAction)
    {
        return updateChild(dispatch, state, acceptance, outputJson, artifactRefs, mediaTaskId,
                errorCode, summary, retryable, nextAction, null);
    }

    private AssistantPlanExecutionResult updateChild(Dispatch dispatch, String state, String acceptance,
                                                       String outputJson, List<String> artifactRefs,
                                                       Long mediaTaskId, String errorCode, String summary,
                                                       boolean retryable, String nextAction, Long childRunId)
    {
        Locked locked = lockDispatch(dispatch);
        AidRuntimeRunStep child = locked.child();
        if (TERMINAL_STEP.contains(child.getStatus()))
        {
            convergeRunGraph(locked, child, dispatch.runLease().getOwner(), new Date(), false);
            return view(child);
        }
        require(matches(child, dispatch.stepLease(), new Date()), "STEP_LEASE_CHANGED");
        if (mediaTaskId != null && child.getMediaTaskId() != null)
            require(Objects.equals(mediaTaskId, child.getMediaTaskId()), "TASK_IDENTITY_CHANGED");
        child.setStatus(state);
        child.setProviderAcceptanceState(acceptance);
        if (outputJson != null) child.setOutputJson(outputJson);
        child.setOutputArtifactRef(JSON.toJSONString(artifactRefs == null ? List.of() : artifactRefs));
        if (mediaTaskId != null) child.setMediaTaskId(mediaTaskId);
        if (childRunId != null)
        {
            if (child.getChildRunId() != null) require(Objects.equals(child.getChildRunId(), childRunId),
                    "SKILL_RUN_IDENTITY_CHANGED");
            child.setChildRunId(childRunId);
            child.setChildRunKind("SKILL_ORCHESTRATOR");
        }
        child.setErrorMessage(errorCode);
        child.setLeaseOwner(null);
        child.setLeaseUntil(null);
        Date now = new Date();
        touchUpdate(child, dispatch.runLease().getOwner(), now);
        if ("SUCCEEDED".equals(state)) {
            // Explicitly clear the current error after receipt reconciliation; updateById skips nulls.
            // Historical uncertainty remains in the step events, rather than masquerading as failure.
            steps.update(child, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getId, child.getId()).set(AidRuntimeRunStep::getErrorMessage, null));
        } else steps.updateById(child);
        convergeRunGraph(locked, child, dispatch.runLease().getOwner(), now, true);
        return new AssistantPlanExecutionResult(child.getId(), resultStatus(child), nextAction,
                artifactRefs, child.getMediaTaskId(), summary, errorCode, retryable);
    }

    private void convergeRunGraph(Locked locked, AidRuntimeRunStep child, String operator, Date now,
                                  boolean childChanged)
    {
        AidRuntimeRun run = locked.run();
        AidRuntimeApproval approval = locked.approval();
        AidRuntimeRunStep main = locked.main();
        boolean graphChanged = childChanged;
        if (List.of("CANCEL_FOR_REPLAN","WAIT_REPLAN_RECONCILIATION").contains(Objects.toString(run.getNextAction(),"")))
        {
            // REPLAN recovery owns the outer state. Preserve original task outputs without resuming the Agent.
            if (TERMINAL_STEP.contains(child.getStatus()) && "ACCEPTED".equals(approval.getStatus()))
            {
                approval.setStatus("CONSUMED");approval.setStateRevision(longValue(approval.getStateRevision())+1);
                touchUpdate(approval,operator,now);approvals.updateById(approval);
            }
            appendEvent(locked.session(),run,"operation.step_changed",String.valueOf(child.getId()),
                    longValue(child.getFencingToken()),String.valueOf(approval.getId()),
                    Map.of("status",child.getStatus(),"providerAcceptanceState",Objects.toString(child.getProviderAcceptanceState(),"")),now,operator);
            return;
        }
        boolean terminal = TERMINAL_STEP.contains(child.getStatus());
        if (terminal)
        {
            if ("SUCCEEDED".equals(child.getStatus())
                    && advanceNativeReadBatch(locked, operator, now)) return;
            boolean newlySettledFailure = List.of("FAILED", "FAILED_PRETASK").contains(child.getStatus())
                    && (childChanged || !"SUCCEEDED".equals(main.getStatus())
                        || !"CONSUMED".equals(approval.getStatus()));
            if (!"SUCCEEDED".equals(main.getStatus()))
            {
                require("DECISION_READY".equals(main.getStatus()), "MAIN_STEP_CHANGED");
                main.setStatus("SUCCEEDED");
                main.setLeaseOwner(null);
                main.setLeaseUntil(null);
                touchUpdate(main, operator, now);
                steps.updateById(main);
                graphChanged = true;
            }
            consumeMainApproval(main, run, operator, now);
            if (!"CONSUMED".equals(approval.getStatus()))
            {
                require("ACCEPTED".equals(approval.getStatus()), "OPERATION_APPROVAL_STATE_CHANGED");
                approval.setStatus("CONSUMED");
                approval.setStateRevision(longValue(approval.getStateRevision()) + 1);
                touchUpdate(approval, operator, now);
                approvals.updateById(approval);
                graphChanged = true;
            }
            if (newlySettledFailure && !Boolean.TRUE.equals(run.getStopRequested()))
            {
                if (StrUtil.isBlank(run.getPauseReason())
                        && !"AUTO".equals(budgetPolicies.executionMode(run))) run.setPauseReason("OPERATION_FAILED");
                run.setErrorMessage(child.getErrorMessage());
            }
            boolean budgetRejected = "FAILED_PRETASK".equals(child.getStatus())
                    && List.of("NOT_SUBMITTED", "RELEASED").contains(child.getProviderAcceptanceState())
                    && child.getMediaTaskId() == null && child.getChildRunId() == null
                    && AssistantBudgetAdmissionRejectedException.requiresNewRun(child.getErrorMessage());
            boolean resourcesUnavailable = List.of("FAILED", "FAILED_PRETASK").contains(child.getStatus())
                    && com.aid.compute.ComputeResourcesUnavailableException.CODE.equals(child.getErrorMessage());
            boolean cannotContinue = budgetRejected || resourcesUnavailable;
            String terminalRunStatus = Boolean.TRUE.equals(run.getStopRequested())
                    ? "STOPPED" : cannotContinue ? "FAILED"
                    : StrUtil.isNotBlank(run.getPauseReason()) ? "PAUSED" : "WAITING_APPROVAL";
            String terminalRunAction = Boolean.TRUE.equals(run.getStopRequested()) || cannotContinue
                    ? null : "CONTINUE_AGENT_REQUIRED";
            if (!Objects.equals(terminalRunStatus, run.getStatus())
                    || !Objects.equals(terminalRunAction, run.getNextAction()) || newlySettledFailure)
            {
                run.setStatus(terminalRunStatus);
                run.setNextAction(terminalRunAction);
                if ("PAUSED".equals(terminalRunStatus)) run.setResumeStatus("WAITING_APPROVAL");
                else if (cannotContinue)
                {
                    run.setResumeStatus(null);
                    run.setPauseReason(null);
                }
                run.setNextRetryAt(null);
                run.setStateRevision(longValue(run.getStateRevision()) + 1);
                touchUpdate(run, operator, now);
                runs.updateById(run);
                graphChanged = true;
            }
            if ("STOPPED".equals(terminalRunStatus)
                    && Objects.equals(locked.session().getActiveRuntimeRunId(), run.getId()))
            {
                locked.session().setActiveRuntimeRunId(null);
                locked.session().setStateRevision(longValue(locked.session().getStateRevision()) + 1);
                locked.session().setLastActiveTime(now);
                touchUpdate(locked.session(), operator, now);
                require(sessions.updateById(locked.session()) == 1, "ASSISTANT_SESSION_CHANGED");
                graphChanged = true;
            }
        }
        else
        {
            boolean stopping = Boolean.TRUE.equals(run.getStopRequested());
            boolean pausing = !stopping && ("PAUSING".equals(run.getStatus())
                    || StrUtil.isNotBlank(run.getPauseReason()));
            String runStatus = stopping ? "RECONCILING" : pausing ? "PAUSING"
                    : "RECONCILING".equals(child.getStatus()) ? "RECONCILING" : "RUNNING";
            String runAction = stopping || pausing ? "RECONCILE_OPERATION"
                    : "RECONCILING".equals(child.getStatus())
                    ? "RECONCILE_OPERATION" : "WAIT_OPERATION_TASK";
            if (!Objects.equals(runStatus, run.getStatus()) || !Objects.equals(runAction, run.getNextAction()))
            {
                run.setStatus(runStatus);
                run.setNextAction(runAction);
                run.setNextRetryAt(new Date(now.getTime() + 5_000L));
                run.setStateRevision(longValue(run.getStateRevision()) + 1);
                touchUpdate(run, operator, now);
                runs.updateById(run);
                graphChanged = true;
            }
        }
        if (!graphChanged) return;
        appendEvent(locked.session(), run, "operation.step_changed", String.valueOf(child.getId()),
                longValue(child.getFencingToken()), String.valueOf(approval.getId()),
                Map.of("status", child.getStatus(), "providerAcceptanceState",
                        Objects.toString(child.getProviderAcceptanceState(), "")), now, operator);
        appendEvent(locked.session(), run, "run.state_changed", String.valueOf(run.getId()),
                run.getStateRevision(), String.valueOf(approval.getId()),
                Map.of("status", run.getStatus(), "nextAction", Objects.toString(run.getNextAction(), "")),
                now, operator);
    }

    /** Advances only a server-normalized all-READ batch; the main approval remains pinned. */
    private boolean advanceNativeReadBatch(Locked locked, String operator, Date now)
    {
        AidRuntimeRunStep main = locked.main();
        if (!NativeAssistantToolBatch.isBatch(main.getOutputJson())) return false;
        NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(main.getOutputJson());
        Persisted current = scopeCodec.decode(locked.approval().getScopeJson());
        String currentDecisionId = current.decision().getString("decisionId");
        int index = -1;
        for (NativeAssistantToolBatch.Call call : batch.calls())
        {
            JSONObject operation = JSON.parseObject(call.decisionJson());
            if (operation != null && Objects.equals(
                    operation.getString("decisionId"), currentDecisionId)) index = call.index();
        }
        require(index >= 0, "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
        if (index + 1 >= batch.calls().size()) return false;
        if (Boolean.TRUE.equals(locked.run().getStopRequested())
                || "PAUSING".equals(locked.run().getStatus())
                || StrUtil.isNotBlank(locked.run().getPauseReason())) return false;
        require("ACCEPTED".equals(locked.approval().getStatus())
                        && "DECISION_READY".equals(main.getStatus()),
                "NATIVE_TOOL_BATCH_CURSOR_CHANGED");
        locked.approval().setStatus("CONSUMED");
        locked.approval().setStateRevision(longValue(locked.approval().getStateRevision()) + 1);
        touchUpdate(locked.approval(), operator, now);
        require(approvals.updateById(locked.approval()) == 1, "OPERATION_APPROVAL_STATE_CHANGED");
        locked.run().setStatus("READY");
        locked.run().setNextAction("EXECUTE_PLAN_OPERATION");
        locked.run().setNextRetryAt(now);
        locked.run().setStateRevision(longValue(locked.run().getStateRevision()) + 1);
        touchUpdate(locked.run(), operator, now);
        require(runs.updateById(locked.run()) == 1, "RUNTIME_RUN_CHANGED");
        appendEvent(locked.session(), locked.run(), "operation.step_changed",
                String.valueOf(locked.child().getId()), longValue(locked.child().getFencingToken()),
                String.valueOf(locked.approval().getId()),
                Map.of("status", locked.child().getStatus(), "providerAcceptanceState",
                        Objects.toString(locked.child().getProviderAcceptanceState(), "")), now, operator);
        appendEvent(locked.session(), locked.run(), "run.state_changed",
                String.valueOf(locked.run().getId()), locked.run().getStateRevision(),
                batch.decisionId(), Map.of("status", locked.run().getStatus(),
                        "nextAction", locked.run().getNextAction(),
                        "nativeToolBatchIndex", index + 1), now, operator);
        dueRunWakeup.wakeAfterCommit(locked.run().getId());
        return true;
    }

    private Locked lockDispatch(Dispatch dispatch)
    {
        AidAssistantSession session = requireSession(dispatch.session().getId(), dispatch.run().getUserId(), true);
        AidRuntimeRun run = requireRun(dispatch.runId(), true);
        requireAcceptedReconciliation(session, run, dispatch.runLease(), new Date());
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, dispatch.approval().getId())
                .eq(AidRuntimeApproval::getRunId, run.getId()).eq(AidRuntimeApproval::getDelFlag, "0")
                .last("FOR UPDATE"));
        AidRuntimeRunStep main = requireMainStep(dispatch.mainStepId(), run,
                dispatch.commandDecisionJson(), true);
        AidRuntimeRunStep child = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, dispatch.stepId()).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        require(approval != null && child != null && Objects.equals(child.getApprovalId(), approval.getId())
                && Objects.equals(main.getId(), dispatch.mainStepId()), "OPERATION_IDENTITY_CHANGED");
        return new Locked(session, run, approval, main, child);
    }

    private RuntimeToolPreparedInvocation prepared(Dispatch dispatch)
    {
        Persisted scope = dispatch.persisted();
        RuntimeToolPreparedInvocation result = new RuntimeToolPreparedInvocation();
        try
        {
            result.setDescriptor(toolRegistry.require(dispatch.bound().bindingCode()));
        }
        catch (ServiceException changed)
        {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED, changed);
        }
        result.setRunId(dispatch.runId());
        result.setUserId(dispatch.run().getUserId());
        result.setRuntimeStepId(dispatch.stepId());
        result.setPlanRevision(dispatch.step().getPlanRevision());
        result.setStableStepId(dispatch.step().getStableStepId());
        result.setAttemptNo(dispatch.step().getAttemptNo());
        result.setOperationKind(dispatch.bound().operationKind());
        result.setToolCode(dispatch.step().getToolCode());
        result.setContractVersion(dispatch.bound().bindingVersion());
        result.setSchemaDigest(dispatch.bound().schemaDigest());
        result.setArgumentsJson(scope.argumentsJson());
        result.setArgumentsDigest(scope.argumentsDigest());
        result.setContextManifestJson(scope.executionManifestJson());
        result.setApprovalId(dispatch.approval().getId());
        result.setApprovedScopeDigest(dispatch.approval().getScopeDigest());
        result.setIdempotencyKey(dispatch.step().getIdempotencyKey());
        result.setRunLease(dispatch.runLease());
        result.setStepLease(dispatch.stepLease());
        result.setOperationScope(scope.operationScope());
        result.setPreparedPayloadJson(scope.preparedPayloadJson());
        result.setPreparedPayloadDigest(scope.preparedPayloadDigest());
        result.setBillingIntent(scope.billingIntent());
        return result;
    }

    private AssistantFixedSkillInvocation fixedSkillInvocation(Dispatch dispatch)
    {
        Persisted scope = dispatch.persisted();
        Long projectId = zeroToNull(dispatch.session().getProjectId());
        SkillInvocationRequests.InvokeRequest request = com.aid.assistant.skill.AssistantFixedSkillInputAdapter.adapt(
                scope.skill().getString("inputJson"), projectId, dispatch.session().getEpisodeId(),
                dispatch.run().getRequestJson(), scope.skill().getString("conversationMemoryJson") == null
                        ? null : JSON.parseObject(scope.skill().getString("conversationMemoryJson")),
                scope.skill().getInteger("methodInputContractVersion"));
        AssistantRuntimeContextManifestService.ResolvedManifest manifest;
        JSONArray projected;
        try
        {
            manifest = manifests.requirePinned(dispatch.run().getUserId(), dispatch.session(), dispatch.run());
            requireSkillReferenceContext(scope.executionManifestJson(), manifest.executionManifestJson());
            projected = referenceContents.project(dispatch.run().getUserId(),
                    manifest.executionContextRefs(), manifest.executionResolvedContextRefs());
            AssistantRuntimeContextManifestService.ResolvedManifest rechecked = manifests.requirePinned(
                    dispatch.run().getUserId(), dispatch.session(), dispatch.run());
            requireSkillReferenceContext(manifest.executionManifestJson(), rechecked.executionManifestJson());
        }
        catch (com.aid.assistant.skill.AssistantSkillPreparationRejectedException rejected)
        {
            throw rejected;
        }
        catch (RuntimeException rejected)
        {
            String errorCode = rejected instanceof AssistantServiceException assistant
                    ? assistant.getErrorCode() : null;
            String code = "ASSISTANT_CONTEXT_TOO_LARGE".equals(errorCode)
                    ? "SKILL_REFERENCE_CONTEXT_TOO_LARGE"
                    : "ASSISTANT_CONTEXT_REFERENCE_LIMIT".equals(errorCode)
                    ? "SKILL_REFERENCE_COUNT_EXCEEDED" : "SKILL_REFERENCE_CONTEXT_INVALID";
            throw new com.aid.assistant.skill.AssistantSkillPreparationRejectedException(code, rejected);
        }
        request.setReferences(AssistantSkillReferenceAdapter.adapt(projected));
        request.setSkillCode(dispatch.bound().bindingCode());
        request.setIdempotencyKey(dispatch.step().getIdempotencyKey());
        AssistantSkillBudgetEnvelope envelope = new AssistantSkillBudgetEnvelope();
        envelope.setAssistantRuntimeRunId(dispatch.runId());
        envelope.setAssistantParentStepId(dispatch.stepId());
        envelope.setAssistantOuterStableStepKey(dispatch.step().getStableStepId());
        envelope.setAssistantAttemptNo(dispatch.step().getAttemptNo());
        envelope.setBillingAuthorizationId(dispatch.approval().getBillingAuthorizationId());
        envelope.setBillingAuthorizationRevision(dispatch.approval().getBillingAuthorizationRevision());
        envelope.setPayerUserId(dispatch.run().getUserId());
        envelope.setApprovalId(dispatch.approval().getId());
        envelope.setSkillVersionId(dispatch.bound().skillVersionId());
        envelope.setPackageDigest(dispatch.bound().packageDigest());
        envelope.setApprovedScopeDigest(dispatch.approval().getScopeDigest());
        envelope.setApprovedOperationScopeJson(AssistantPlanExecutionJson.canonical(
                JSON.parseObject(JSON.toJSONString(scope.operationScope()))));
        envelope.setApprovedOperationScopeDigest(SecureUtil.sha256(envelope.getApprovedOperationScopeJson()));
        request.setAssistantBudgetEnvelope(envelope);
        AssistantFixedSkillInvocation invocation = new AssistantFixedSkillInvocation();
        invocation.setInvocation(request);
        invocation.setBudgetEnvelope(envelope);
        invocation.setMethodOnly(Boolean.TRUE);
        invocation.setRunLease(dispatch.runLease());
        invocation.setStepLease(dispatch.stepLease());
        return invocation;
    }

    /** Final authority fence after approval/budget admission and immediately before tool Provider entry. */
    void requireToolSubmissionReferenceContext(Long userId, AidAssistantSession session,
                                               AidRuntimeRun run, String approvedManifestJson)
    {
        try
        {
            AssistantRuntimeContextManifestService.ResolvedManifest current = manifests.requirePinned(
                    userId, session, run);
            if (!referenceContents.matchesPinnedReferenceContext(
                    approvedManifestJson, current.executionManifestJson()))
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID);
        }
        catch (RuntimeToolPreparationRejectedException rejected)
        {
            throw rejected;
        }
        catch (RuntimeException rejected)
        {
            RuntimeToolPreparationRejectedException.Reason reason =
                    rejected instanceof AssistantServiceException assistant
                            && "ASSISTANT_CONTEXT_TOO_LARGE".equals(assistant.getErrorCode())
                            ? RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_TOO_LARGE
                            : RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID;
            throw new RuntimeToolPreparationRejectedException(reason);
        }
    }

    /** Fixed Skills receive the same last-moment reference authorization fence as prepared tools. */
    void requireSkillSubmissionReferenceContext(Long userId, AidAssistantSession session,
                                                AidRuntimeRun run, String approvedManifestJson)
    {
        try
        {
            AssistantRuntimeContextManifestService.ResolvedManifest current = manifests.requirePinned(
                    userId, session, run);
            requireSkillReferenceContext(approvedManifestJson, current.executionManifestJson());
        }
        catch (com.aid.assistant.skill.AssistantSkillPreparationRejectedException rejected)
        {
            throw rejected;
        }
        catch (RuntimeException rejected)
        {
            String errorCode = rejected instanceof AssistantServiceException assistant
                    ? assistant.getErrorCode() : null;
            String code = "ASSISTANT_CONTEXT_TOO_LARGE".equals(errorCode)
                    ? "SKILL_REFERENCE_CONTEXT_TOO_LARGE"
                    : "ASSISTANT_CONTEXT_REFERENCE_LIMIT".equals(errorCode)
                    ? "SKILL_REFERENCE_COUNT_EXCEEDED" : "SKILL_REFERENCE_CONTEXT_INVALID";
            throw new com.aid.assistant.skill.AssistantSkillPreparationRejectedException(code, rejected);
        }
    }

    private void requireSkillReferenceContext(String approvedManifestJson, String currentManifestJson)
    {
        requireSkillReference(referenceContents.matchesPinnedReferenceContext(
                approvedManifestJson, currentManifestJson), "SKILL_REFERENCE_CONTEXT_CHANGED");
    }

    private void requireSkillReference(boolean valid, String code)
    {
        if (!valid) throw new com.aid.assistant.skill.AssistantSkillPreparationRejectedException(
                code, new IllegalStateException(code));
    }

    private void requirePreparedIdentity(RuntimeToolPreparedInvocation prepared, Preflight preflight, Long stepId)
    {
        boolean valid = prepared != null && prepared.getDescriptor() != null
                && Objects.equals(prepared.getRunId(), preflight.run().getId())
                && Objects.equals(prepared.getUserId(), preflight.run().getUserId())
                && Objects.equals(prepared.getRuntimeStepId(), stepId)
                && Objects.equals(prepared.getPlanRevision(), preflight.decision().observedPlanRevision())
                && Objects.equals(prepared.getStableStepId(), preflight.decision().stableStepId())
                && Objects.equals(prepared.getAttemptNo(), preflight.decision().attemptNo())
                && Objects.equals(prepared.getOperationKind(), preflight.bound().operationKind())
                && Objects.equals(prepared.getToolCode(), preflight.bound().bindingCode())
                && Objects.equals(prepared.getContractVersion(), preflight.bound().bindingVersion())
                && Objects.equals(prepared.getSchemaDigest(), preflight.bound().schemaDigest())
                && Objects.equals(prepared.getIdempotencyKey(), logicalKey(preflight.run().getId(), preflight.decision()))
                && Objects.equals(prepared.getArgumentsJson(),
                AssistantPlanExecutionJson.canonicalExplicitNulls(preflight.decision().arguments()))
                && Objects.equals(prepared.getArgumentsDigest(), SecureUtil.sha256(prepared.getArgumentsJson()))
                && Objects.equals(prepared.getContextManifestJson(), preflight.manifest().executionManifestJson())
                && prepared.getApprovalId() == null && StrUtil.isBlank(prepared.getApprovedScopeDigest())
                && prepared.getRunLease() != null && prepared.getStepLease() == null
                && Objects.equals(prepared.getRunLease().getOwner(), preflight.command().lease().getOwner())
                && Objects.equals(prepared.getRunLease().getFencingToken(),
                preflight.command().lease().getFencingToken());
        if (!valid) throw new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IDENTITY_CHANGED);
    }

    private void requireOperationScope(AssistantBudgetOperationScope scope, Preflight preflight,
                                       RuntimeToolPreparedInvocation prepared)
    {
        boolean baseValid = scope != null
                && Objects.equals(scope.getOperationKind(), preflight.bound().operationKind())
                && Objects.equals(scope.getToolCode(), preflight.decision().isTool()
                ? preflight.bound().bindingCode() : null)
                && !scope.isCountsAsIteration()
                && scope.getCapabilityCodes() != null && scope.getMediaTypes() != null
                && scope.getTargetRefs() != null && scope.getQuotedUpperBound() != null
                && scope.getQuotedUpperBound().signum() >= 0;
        if (!baseValid)
        {
            if (preflight.decision().isTool()) throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
            require(false, "OPERATION_SCOPE_INVALID");
        }
        if (preflight.decision().isSkill())
        {
            require(prepared == null && scope.getCapabilityCodes().contains("skill.invoke")
                    && scope.getQuotedUpperBound().compareTo(BigDecimal.ZERO) == 0,
                    "OPERATION_SCOPE_INVALID");
            return;
        }
        if (prepared == null || !Objects.equals(scope, prepared.getOperationScope()))
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
        RuntimeToolBillingIntent intent = prepared.getBillingIntent();
        if (preflight.bound().billable())
        {
            boolean billingValid = intent != null && StrUtil.isNotBlank(scope.getModelCode())
                    && !scope.getCapabilityCodes().isEmpty() && !scope.getMediaTypes().isEmpty()
                    && Objects.equals(scope.getQuotedUpperBound(), intent.getQuotedUpperBound())
                    && intent.getQuoteExpiresAt() != null;
            if (!billingValid) throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
            if (!intent.getQuoteExpiresAt().after(new Date()))
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_EXPIRED);
        }
        else
        {
            if (intent != null || scope.getQuotedUpperBound().compareTo(BigDecimal.ZERO) != 0)
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
        }
    }

    private RuntimeToolInvocation toolInvocation(AidRuntimeRun run, AidAssistantSession session,
                                                  AssistantPlanExecutionDecision decision,
                                                  BoundOperation bound,
                                                  AssistantRuntimeContextManifestService.ResolvedManifest manifest,
                                                  AssistantBudgetOperationScope scope, RuntimeLease lease,
                                                  Long stepId, Long approvalId, String approvalDigest)
    {
        RuntimeToolInvocation result = new RuntimeToolInvocation();
        result.setRunId(run.getId());
        result.setUserId(run.getUserId());
        result.setRuntimeStepId(stepId);
        result.setPlanRevision(run.getPlanRevision());
        result.setStableStepId(decision.stableStepId());
        result.setAttemptNo(decision.attemptNo());
        result.setOperationKind(bound.operationKind());
        result.setToolCode(bound.bindingCode());
        result.setContractVersion(bound.bindingVersion());
        result.setSchemaDigest(bound.schemaDigest());
        String arguments = AssistantPlanExecutionJson.canonicalExplicitNulls(decision.arguments());
        result.setArgumentsJson(arguments);
        result.setArgumentsDigest(SecureUtil.sha256(arguments));
        result.setContextManifestJson(manifest.executionManifestJson());
        // This invocation is built only after preflight proved exact identity or presentation-only progression.
        if (decision.isTool() && !com.aid.assistant.service.AssistantReferenceContextFence
                .matchesExpectedRevisions(decision.expectedContextRevisions(), manifest.executionManifestJson()))
            result.setVerifiedPresentationRevisionsJson(decision.expectedContextRevisions().toJSONString());
        if (CreativeRuntimeToolProvider.TEXT_SUBMIT.equals(bound.bindingCode()))
        {
            try
            {
                JSONArray projected = referenceContents.project(run.getUserId(),
                        manifest.executionContextRefs(), manifest.executionResolvedContextRefs());
                AssistantRuntimeContextManifestService.ResolvedManifest rechecked =
                        manifests.requirePinned(run.getUserId(), session, run);
                if (!referenceContents.matchesPinnedReferenceContext(
                        manifest.executionManifestJson(), rechecked.executionManifestJson())) {
                    throw new RuntimeToolPreparationRejectedException(
                            RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID);
                }
                result.setAssistantReferencePrompt(CreativeAssistantReferencePromptAdapter.append(
                        decision.arguments().getString("prompt"), projected));
            }
            catch (RuntimeToolPreparationRejectedException rejected)
            {
                throw rejected;
            }
            catch (RuntimeException rejected)
            {
                RuntimeToolPreparationRejectedException.Reason reason =
                        rejected instanceof AssistantServiceException assistant
                                && "ASSISTANT_CONTEXT_TOO_LARGE".equals(assistant.getErrorCode())
                                ? RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_TOO_LARGE
                                : RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID;
                throw new RuntimeToolPreparationRejectedException(reason);
            }
        }
        result.setApprovalId(approvalId);
        result.setApprovedScopeDigest(approvalDigest);
        result.setIdempotencyKey(logicalKey(run.getId(), decision));
        result.setLease(lease);
        result.setOperationScope(scope);
        return result;
    }

    private AssistantBudgetOperationScope operationScope(BoundOperation bound,
                                                           AssistantPlanExecutionDecision decision,
                                                           AssistantRuntimeContextManifestService.ResolvedManifest manifest)
    {
        AssistantBudgetOperationScope result = new AssistantBudgetOperationScope();
        result.setOperationKind(bound.operationKind());
        result.setToolCode(decision.isTool() ? bound.bindingCode() : null);
        if (decision.isSkill())
        {
            result.setModelCode(decision.arguments().getString("modelCode"));
            result.setCapabilityCodes(List.of("skill.invoke"));
            result.setMediaTypes(List.of());
            result.setTargetRefs(manifest.executionContextRefs());
            result.setQuotedUpperBound(BigDecimal.ZERO);
        }
        result.setCountsAsIteration(false);
        return result;
    }

    private AidRuntimeApproval newApproval(AidRuntimeRun run, AidRuntimePlan plan, Preflight preflight,
                                             AssistantBudgetOperationScope operationScope,
                                             AssistantBudgetOperationAdmission admission,
                                             Persisted persisted, AidRuntimeBudgetPolicy policy, Date now,
                                             boolean automatic)
    {
        Date policyExpiresAt = admission.scopeOnly() && !persisted.billable()
                ? Date.from(now.toInstant().plus(java.time.Duration.ofMinutes(10))) : policy.getExpiresAt();
        Date expires = persisted.quoteExpiresAt() == null ? policyExpiresAt
                : earlier(persisted.quoteExpiresAt(), policyExpiresAt);
        AidRuntimeApproval value = new AidRuntimeApproval();
        value.setRunId(run.getId());
        value.setUserId(run.getUserId());
        value.setClientRequestId(preflight.clientKey());
        value.setBillingAuthorizationId(admission.authorizationId());
        value.setBillingAuthorizationRevision(admission.authorizationRevision());
        value.setScopeKind("PLAN_EXECUTION");
        value.setRequestDigest(persisted.requestDigest());
        value.setAnswerDigest(decisionDigest(preflight.decision()));
        value.setContextDigest(run.getContextDigest());
        value.setPlanningAttemptNo(preflight.decision().attemptNo());
        value.setPlanRevision(plan.getRevision());
        value.setPlanDigest(plan.getDefinitionDigest());
        value.setScopeJson(persisted.json());
        value.setScopeDigest(persisted.digest());
        value.setNodeRevisionSetJson(preflight.manifest().executionTargetRefsJson());
        value.setAllowedToolCodesJson(JSON.toJSONString(preflight.decision().isTool()
                ? List.of(preflight.bound().bindingCode()) : List.of()));
        value.setAllowedModelCodesJson(JSON.toJSONString(operationScope.getModelCode() == null
                ? List.of() : List.of(operationScope.getModelCode())));
        value.setAllowedCapabilityCodesJson(JSON.toJSONString(operationScope.getCapabilityCodes()));
        value.setMaxAttempts(preflight.bound().maxAttempts());
        value.setMode(automatic ? admission.policyMode() : "MANUAL");
        value.setQuoteId(persisted.quoteId());
        value.setQuoteDigest(persisted.quoteDigest());
        value.setQuoteRef(persisted.quoteId() == null ? null : "tool:" + persisted.quoteId());
        value.setMaxCharge(persisted.quotedUpperBound());
        value.setExpiresAt(expires);
        value.setStatus(automatic ? "ACCEPTED" : "QUOTED");
        value.setStateRevision(0L);
        value.setAcceptedAt(automatic ? now : null);
        value.setDelFlag("0");
        touchCreate(value, preflight.command().lease().getOwner(), now);
        return value;
    }

    private AidRuntimeRunStep newPreparingChild(AidRuntimeRun run, AidRuntimePlan plan, Preflight preflight,
                                                 BoundOperation bound, Date now)
    {
        AidRuntimeRunStep value = new AidRuntimeRunStep();
        value.setRunId(run.getId());
        value.setStepIndex(nextStepIndex(run.getId()));
        value.setStepKey(preflight.decision().stableStepId());
        value.setStepKind(preflight.decision().action());
        value.setSkillVersionId(preflight.decision().skillVersionId());
        value.setToolCode(preflight.decision().isTool() ? bound.bindingCode() : null);
        value.setInputDigest(decisionDigest(preflight.decision()));
        value.setStableStepId(preflight.decision().stableStepId());
        value.setPlanRevision(plan.getRevision());
        value.setPlanPosition(bound.planPosition());
        value.setAttemptNo(preflight.decision().attemptNo());
        value.setOperationKind(preflight.decision().isTool() ? "ASSISTANT_TOOL" : "ASSISTANT_SKILL");
        value.setIdempotencyKey(logicalKey(run.getId(), preflight.decision()));
        value.setInputArtifactRef(JSON.toJSONString(preflight.decision().inputArtifactRefs()));
        value.setRetryClass(bound.retryClass());
        value.setProviderAcceptanceState("NOT_SUBMITTED");
        value.setFencingToken(0L);
        value.setChildRunKind(null);
        value.setStatus("PREPARING");
        value.setDelFlag("0");
        touchCreate(value, preflight.command().lease().getOwner(), now);
        return value;
    }

    private BillingBudgetRequests.ReserveCallRequest reserveRequest(AidRuntimeRun run, AidRuntimeRunStep child,
                                                                     AidRuntimeApproval approval,
                                                                     Persisted persisted, String operator)
    {
        BillingBudgetRequests.ReserveCallRequest value = new BillingBudgetRequests.ReserveCallRequest();
        value.setAuthorizationId(approval.getBillingAuthorizationId());
        value.setAuthorizationRevision(approval.getBillingAuthorizationRevision());
        value.setPayerUserId(run.getUserId());
        value.setRuntimeRunId(run.getId());
        value.setRuntimeStepId(child.getId());
        value.setStableStepKey(child.getStableStepId());
        value.setApprovalId(approval.getId());
        value.setAttemptNo(child.getAttemptNo());
        value.setOperationKind(child.getOperationKind());
        value.setLogicalCallKey(child.getIdempotencyKey());
        value.setRequestDigest(persisted.requestDigest());
        value.setQuoteId(persisted.quoteId());
        value.setQuoteDigest(persisted.quoteDigest());
        value.setQuoteExpiresAt(persisted.quoteExpiresAt());
        value.setQuotedUpperBound(persisted.quotedUpperBound());
        value.setOperator(operator);
        return value;
    }

    private BillingBudgetContext budgetContext(Dispatch dispatch)
    {
        BillingBudgetViews.ReservationView reservation = reservation(dispatch);
        BillingBudgetContext value = new BillingBudgetContext();
        value.setMode(BillingBudgetContext.MODE_RUN_AUTHORIZATION);
        value.setAuthorizationId(reservation.getAuthorizationId());
        value.setAuthorizationRevision(reservation.getAuthorizationRevision());
        value.setReservationId(reservation.getId());
        value.setReservationRevision(reservation.getRevision());
        value.setRuntimeRunId(reservation.getRuntimeRunId());
        value.setRuntimeStepId(reservation.getRuntimeStepId());
        value.setRuntimeRunFencingToken(dispatch.runLease().getFencingToken());
        value.setRuntimeStepFencingToken(dispatch.stepLease().getFencingToken());
        value.setStableStepKey(reservation.getStableStepKey());
        value.setAttemptNo(reservation.getAttemptNo());
        value.setLogicalCallKey(reservation.getLogicalCallKey());
        value.setRequestDigest(reservation.getRequestDigest());
        value.setQuoteId(reservation.getQuoteId());
        value.setQuoteDigest(reservation.getQuoteDigest());
        value.setQuoteExpiresAt(reservation.getQuoteExpiresAt());
        return value;
    }

    private BillingBudgetViews.ReservationView reservation(Dispatch dispatch)
    {
        if (dispatch.step().getReservationId() == null)
            throw new InvalidPersistedSubmissionException(InvalidPersistedSubmissionReason.RESERVATION_ID_MISSING);
        List<BillingBudgetViews.ReservationView> reservations = billingBudgets.listReservations(
                dispatch.approval().getBillingAuthorizationId());
        if (reservations == null)
            throw new InvalidPersistedSubmissionException(InvalidPersistedSubmissionReason.RESERVATION_LIST_INVALID);
        BillingBudgetViews.ReservationView reservation = reservations.stream()
                .filter(value -> Objects.equals(value.getId(), dispatch.step().getReservationId()))
                .findFirst().orElseThrow(() -> new InvalidPersistedSubmissionException(
                        InvalidPersistedSubmissionReason.RESERVATION_MISSING));
        if (!Objects.equals(reservation.getRuntimeRunId(), dispatch.runId())
                || !Objects.equals(reservation.getRuntimeStepId(), dispatch.stepId())
                || !Objects.equals(reservation.getApprovalId(), dispatch.approval().getId())
                || !Objects.equals(reservation.getLogicalCallKey(), dispatch.step().getIdempotencyKey()))
            throw new InvalidPersistedSubmissionException(
                    InvalidPersistedSubmissionReason.RESERVATION_IDENTITY_CHANGED);
        return reservation;
    }

    private BillingBudgetRequests.ReleaseUnsubmittedReservationRequest releaseRequest(
            Dispatch dispatch, BillingBudgetViews.ReservationView reservation)
    {
        BillingBudgetRequests.ReleaseUnsubmittedReservationRequest value =
                new BillingBudgetRequests.ReleaseUnsubmittedReservationRequest();
        value.setAuthorizationId(reservation.getAuthorizationId());
        value.setAuthorizationRevision(reservation.getAuthorizationRevision());
        value.setReservationId(reservation.getId());
        value.setExpectedReservationRevision(reservation.getRevision());
        value.setPayerUserId(dispatch.run().getUserId());
        value.setRuntimeRunId(dispatch.runId());
        value.setRuntimeStepId(dispatch.stepId());
        value.setStableStepKey(dispatch.step().getStableStepId());
        value.setApprovalId(dispatch.approval().getId());
        value.setAttemptNo(dispatch.step().getAttemptNo());
        value.setOperationKind(dispatch.step().getOperationKind());
        value.setLogicalCallKey(dispatch.step().getIdempotencyKey());
        value.setOperator(dispatch.runLease().getOwner());
        return value;
    }

    private Dispatch dispatch(AidRuntimeRun run, AidAssistantSession session, AidRuntimeRunStep main,
                              AidRuntimeRunStep child, AidRuntimeApproval approval, Persisted persisted,
                              BoundOperation bound, RuntimeLease runLease)
    {
        return new Dispatch(run.getId(), main.getId(), child.getId(), run, session, main, child,
                approval, persisted, bound, runLease, null, main.getOutputJson());
    }

    private AidRuntimeRunStep requireMainStep(Long stepId, AidRuntimeRun run, String decisionJson, boolean lock)
    {
        AidRuntimeRunStep value = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null && "MAIN_AGENT".equals(value.getStepKind())
                && "ASSISTANT_DECISION".equals(value.getOperationKind())
                && "DECISION_READY".equals(value.getStatus())
                && Objects.equals(value.getOutputJson(), decisionJson)
                && Objects.equals(value.getPlanRevision(), run.getPlanRevision()), "MAIN_STEP_CHANGED");
        return value;
    }

    private AidRuntimeRunStep requirePreparingMainStep(Long stepId, AidRuntimeRun run,
                                                        String decisionJson,
                                                        AssistantPlanExecutionDecision decision,
                                                        boolean terminalChild, boolean lock)
    {
        AidRuntimeRunStep value = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null && "MAIN_AGENT".equals(value.getStepKind())
                && "ASSISTANT_DECISION".equals(value.getOperationKind())
                && ("DECISION_READY".equals(value.getStatus())
                || terminalChild && "SUCCEEDED".equals(value.getStatus()))
                && Objects.equals(value.getOutputJson(), decisionJson)
                && Objects.equals(value.getPlanRevision(), decision.observedPlanRevision()),
                "MAIN_STEP_CHANGED");
        return value;
    }

    private AidRuntimeRunStep requireExistingMainStep(Long stepId, AidRuntimeRun run,
                                                       String decisionJson, boolean terminalChild,
                                                       boolean lock)
    {
        AidRuntimeRunStep value = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId).eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null && "MAIN_AGENT".equals(value.getStepKind())
                && "ASSISTANT_DECISION".equals(value.getOperationKind())
                && ("DECISION_READY".equals(value.getStatus())
                || terminalChild && "SUCCEEDED".equals(value.getStatus()))
                && Objects.equals(value.getOutputJson(), decisionJson)
                && Objects.equals(value.getPlanRevision(), run.getPlanRevision()), "MAIN_STEP_CHANGED");
        return value;
    }

    private AidRuntimeRunStep requireChild(Long runId, Long planRevision, String stableStepId,
                                             int attemptNo, boolean lock)
    {
        AidRuntimeRunStep value = findChild(runId, planRevision, stableStepId, attemptNo, lock);
        require(value != null, "OPERATION_STEP_MISSING");
        return value;
    }

    private AidRuntimeRunStep findChild(Long runId, Long planRevision, String stableStepId,
                                         int attemptNo, boolean lock)
    {
        return steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getPlanRevision, planRevision)
                .eq(AidRuntimeRunStep::getStableStepId, stableStepId)
                .eq(AidRuntimeRunStep::getAttemptNo, attemptNo).eq(AidRuntimeRunStep::getDelFlag, "0")
                .last(lock ? "FOR UPDATE" : "LIMIT 1"));
    }

    private AidRuntimeApproval findApproval(Long userId, Long runId, String clientRequestId, boolean lock)
    {
        return approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getUserId, userId)
                .eq(AidRuntimeApproval::getRunId, runId)
                .eq(AidRuntimeApproval::getClientRequestId, clientRequestId)
                .eq(AidRuntimeApproval::getDelFlag, "0")
                .last(lock ? "FOR UPDATE" : "LIMIT 1"));
    }

    private void requirePreparingIdentity(AidRuntimeRunStep child, Preflight preflight,
                                           BoundOperation bound)
    {
        require(Objects.equals(child.getRunId(), preflight.run().getId())
                && Objects.equals(child.getPlanRevision(), preflight.decision().observedPlanRevision())
                && Objects.equals(child.getStableStepId(), preflight.decision().stableStepId())
                && Objects.equals(child.getAttemptNo(), preflight.decision().attemptNo())
                && Objects.equals(child.getIdempotencyKey(), logicalKey(preflight.run().getId(), preflight.decision()))
                && Objects.equals(child.getOperationKind(), preflight.decision().isTool()
                ? "ASSISTANT_TOOL" : "ASSISTANT_SKILL")
                && Objects.equals(child.getToolCode(), preflight.decision().isTool() ? bound.bindingCode() : null)
                && Objects.equals(child.getSkillVersionId(), preflight.decision().skillVersionId()),
                "OPERATION_STEP_CHANGED");
    }

    private void requirePreparingIdentity(AidRuntimeRunStep child, AidRuntimeRun run,
                                           AssistantPlanExecutionDecision decision)
    {
        require(Objects.equals(child.getRunId(), run.getId())
                && Objects.equals(child.getPlanRevision(), decision.observedPlanRevision())
                && Objects.equals(child.getStableStepId(), decision.stableStepId())
                && Objects.equals(child.getAttemptNo(), decision.attemptNo())
                && Objects.equals(child.getIdempotencyKey(), logicalKey(run.getId(), decision))
                && Objects.equals(child.getStepKind(), decision.action())
                && Objects.equals(child.getOperationKind(), decision.isTool()
                ? "ASSISTANT_TOOL" : "ASSISTANT_SKILL")
                && Objects.equals(child.getToolCode(), decision.isTool() ? decision.bindingCode() : null)
                && Objects.equals(child.getSkillVersionId(), decision.skillVersionId()),
                "OPERATION_STEP_CHANGED");
    }

    static boolean noPreparingSideEffectEvidence(AidRuntimeRunStep step)
    {
        return step != null && "PREPARING".equals(step.getStatus())
                && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                && step.getApprovalId() == null && step.getReservationId() == null
                && step.getMediaTaskId() == null && step.getChildRunId() == null
                && StrUtil.isBlank(step.getOutputJson()) && emptyArtifactRefs(step.getOutputArtifactRef())
                && StrUtil.isBlank(step.getLeaseOwner()) && step.getLeaseUntil() == null
                && (step.getFencingToken() == null || step.getFencingToken() == 0L);
    }

    private void requireChildIdentity(AidRuntimeRunStep child, AidRuntimeApproval approval,
                                       Preflight preflight, BoundOperation bound)
    {
        requirePreparingIdentity(child, preflight, bound);
        require(Objects.equals(child.getApprovalId(), approval.getId()), "OPERATION_STEP_CHANGED");
    }

    private AidMediaTask authoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        return authoritativeTask(run, step, false);
    }

    private AidMediaTask authoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step, boolean lock)
    {
        AidMediaTask task = step.getMediaTaskId() == null ? null : mediaTasks.selectById(step.getMediaTaskId());
        if (task == null)
        {
            task = mediaTasks.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getUserId, run.getUserId())
                    .eq(AidMediaTask::getBizTaskType, BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE)
                    .eq(AidMediaTask::getBizTaskId, step.getId())
                    .eq(AidMediaTask::getIdempotencyKey, step.getIdempotencyKey())
                    .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        }
        if (task != null) require(Objects.equals(task.getUserId(), run.getUserId())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())
                && BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(task.getBizTaskType()),
                "TASK_IDENTITY_CHANGED");
        return task;
    }

    private AidRuntimeRun requireRun(Long runId, boolean lock)
    {
        AidRuntimeRun value = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId).eq(AidRuntimeRun::getDelFlag, "0")
                .last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null, "RUNTIME_RUN_NOT_FOUND");
        return value;
    }

    private AidAssistantSession requireSession(Long sessionId, Long userId, boolean lock)
    {
        AidAssistantSession value = sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, sessionId).eq(AidAssistantSession::getDelFlag, "0")
                .last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null && "ASSISTANT".equals(value.getSessionType())
                && Objects.equals(value.getUserId(), userId), "ASSISTANT_SESSION_NOT_FOUND");
        return value;
    }

    private AidRuntimePlan requirePlan(Long runId, Long revision, boolean lock)
    {
        AidRuntimePlan value = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, runId).eq(AidRuntimePlan::getRevision, revision)
                .eq(AidRuntimePlan::getDelFlag, "0").last(lock ? "FOR UPDATE" : "LIMIT 1"));
        require(value != null, "PLAN_NOT_FOUND");
        return value;
    }

    private void requireActiveMutation(AidAssistantSession session, AidRuntimeRun run,
                                       RuntimeLease lease, Date now)
    {
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                && ACTIVE_RUN.contains(run.getStatus()) && !Boolean.TRUE.equals(run.getStopRequested())
                && lease != null && Objects.equals(run.getLeaseOwner(), lease.getOwner())
                && Objects.equals(run.getFencingToken(), lease.getFencingToken())
                && run.getLeaseUntil() != null && run.getLeaseUntil().after(now), "RUN_LEASE_CHANGED");
    }

    /** Existing accepted work may reconcile while pause/stop prevents every new submission. */
    private void requireAcceptedReconciliation(AidAssistantSession session, AidRuntimeRun run,
                                               RuntimeLease lease, Date now)
    {
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                && ACTIVE_RUN.contains(run.getStatus())
                && lease != null && Objects.equals(run.getLeaseOwner(), lease.getOwner())
                && Objects.equals(run.getFencingToken(), lease.getFencingToken())
                && run.getLeaseUntil() != null && run.getLeaseUntil().after(now), "RUN_LEASE_CHANGED");
    }

    private boolean matches(AidRuntimeRunStep step, RuntimeLease lease, Date now)
    {
        return lease != null && Objects.equals(step.getLeaseOwner(), lease.getOwner())
                && Objects.equals(step.getFencingToken(), lease.getFencingToken())
                && step.getLeaseUntil() != null && step.getLeaseUntil().after(now);
    }

    private AssistantPlanExecutionResult view(AidRuntimeRunStep step)
    {
        return new AssistantPlanExecutionResult(step.getId(), resultStatus(step), nextAction(step),
                outputs(step), step.getMediaTaskId(), safeSummary(step), step.getErrorMessage(),
                "RETRY_WAIT".equals(step.getStatus()));
    }

    private String resultStatus(AidRuntimeRunStep step)
    {
        if ("INFLIGHT".equals(step.getStatus()) || "SUBMITTING".equals(step.getStatus()))
            return "ASSISTANT_SKILL".equals(step.getOperationKind()) ? "WAITING_SKILL" : "WAITING_TASK";
        return step.getStatus();
    }

    private String nextAction(AidRuntimeRunStep step)
    {
        if (("INFLIGHT".equals(step.getStatus()) || "SUBMITTING".equals(step.getStatus()))
                && "ASSISTANT_SKILL".equals(step.getOperationKind())) return "WAIT_SKILL";
        return nextAction(step.getStatus());
    }

    private static boolean waitingResource(AidRuntimeRunStep step){
        return "RECONCILING".equals(step.getStatus())&&"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
            &&"TOOL_WAITING_RESOURCE".equals(step.getErrorMessage());
    }

    private AssistantPlanExecutionResult reconciling(Dispatch dispatch, String code)
    {
        return new AssistantPlanExecutionResult(dispatch.stepId(), "RECONCILING", "RECONCILE_OPERATION",
                outputs(dispatch.step()), dispatch.step().getMediaTaskId(),
                "Operation outcome requires reconciliation.", code, true);
    }

    private List<String> outputs(AidRuntimeRunStep step)
    {
        if (step == null || StrUtil.isBlank(step.getOutputArtifactRef())) return List.of();
        try { return JSON.parseArray(step.getOutputArtifactRef(), String.class); }
        catch (RuntimeException invalid) { throw new ServiceException("OPERATION_OUTPUT_INVALID"); }
    }

    private String nextAction(String status)
    {
        return switch (status)
        {
            case "SUCCEEDED" -> "CONTINUE_AGENT";
            case "AWAITING_APPROVAL" -> "CONFIRM_OPERATION_APPROVAL";
            case "INFLIGHT", "SUBMITTING" -> "WAIT_TASK";
            case "RECONCILING" -> "RECONCILE_OPERATION";
            default -> "OPERATION_FAILED";
        };
    }

    private String safeSummary(AidRuntimeRunStep step)
    {
        if(waitingResource(step))return com.aid.runtime.tool.RuntimeResourceWait.message(
                com.aid.runtime.tool.RuntimeResourceWait.position(step.getOutputJson()));
        if(com.aid.compute.ComputeResourcesUnavailableException.CODE.equals(step.getErrorMessage()))
            return "构建资源维护中，请稍后重试";
        if ("SUCCEEDED".equals(step.getStatus())) return "Operation completed.";
        if ("AWAITING_APPROVAL".equals(step.getStatus())) return "Operation approval is required.";
        if ("INFLIGHT".equals(step.getStatus())) return "Operation is still running.";
        return StrUtil.isBlank(step.getErrorMessage()) ? "Operation state changed." : "Operation did not complete.";
    }

    private int nextStepIndex(Long runId)
    {
        AidRuntimeRunStep latest = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId).eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByDesc(AidRuntimeRunStep::getStepIndex).orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 1"));
        return latest == null || latest.getStepIndex() == null ? 0 : latest.getStepIndex() + 1;
    }

    private String clientKey(String invocationId)
    {
        return "op:" + SecureUtil.sha256(invocationId).substring(0, 61);
    }

    private String logicalKey(Long runId, AssistantPlanExecutionDecision decision)
    {
        return "asst-op:" + SecureUtil.sha256(runId + "\n" + decision.observedPlanRevision()
                + "\n" + decision.stableStepId() + "\n" + decision.attemptNo()
                + "\n" + decision.invocationId()).substring(0, 56);
    }

    private String decisionDigest(AssistantPlanExecutionDecision decision)
    {
        return SecureUtil.sha256(decision.canonicalPayloadJson());
    }

    private Date earlier(Date left, Date right)
    {
        require(left != null && right != null, "APPROVAL_EXPIRY_INVALID");
        return left.before(right) ? left : right;
    }

    private Long zeroToNull(Long value) { return value == null || value == 0L ? null : value; }

    private void touchCreate(com.aid.common.core.domain.BaseEntity value, String operator, Date now)
    {
        value.setCreateBy(operator); value.setCreateTime(now); value.setUpdateBy(operator); value.setUpdateTime(now);
    }

    private void touchUpdate(com.aid.common.core.domain.BaseEntity value, String operator, Date now)
    {
        value.setUpdateBy(operator); value.setUpdateTime(now);
    }

    private void requireCommand(AssistantPlanExecutionCommand command)
    {
        require(command != null && command.runId() != null && command.runId() > 0
                && command.mainAgentStepId() != null && command.mainAgentStepId() > 0
                && StrUtil.isNotBlank(command.mainDecisionJson())
                && StrUtil.isNotBlank(command.operationDecisionJson())
                && command.batchSize() >= 1 && command.batchSize() <= NativeAssistantToolBatch.MAX_CALLS
                && command.batchIndex() >= 0 && command.batchIndex() < command.batchSize()
                && (command.batchMember() || Objects.equals(
                command.mainDecisionJson(), command.operationDecisionJson()))
                && command.lease() != null
                && StrUtil.isNotBlank(command.lease().getOwner())
                && command.lease().getFencingToken() != null, "EXECUTION_COMMAND_INVALID");
        if (command.batchMember())
        {
            NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(command.mainDecisionJson());
            require(command.batchSize() == batch.calls().size()
                            && Objects.equals(batch.calls().get(command.batchIndex()).decisionJson(),
                            command.operationDecisionJson()),
                    "EXECUTION_COMMAND_INVALID");
        }
    }

    private void requireConfirmCommand(AssistantPlanExecutionConfirmCommand command)
    {
        confirmReject(command != null && command.userId() != null && command.userId() > 0
                && command.runId() != null && command.runId() > 0
                && command.approvalId() != null && command.approvalId() > 0
                && command.expectedRunRevision() >= 0 && command.expectedApprovalRevision() >= 0
                && StrUtil.isNotBlank(command.clientRequestId())
                && command.clientRequestId().length() <= 64,
                422, "OPERATION_CONFIRM_COMMAND_INVALID", "操作确认请求无效",
                confirmDetails(command, null, null, null, null));
    }

    private String confirmPayloadDigest(AssistantPlanExecutionConfirmCommand command)
    {
        JSONObject value = new JSONObject();
        value.put("runId", String.valueOf(command.runId()));
        value.put("approvalId", String.valueOf(command.approvalId()));
        value.put("expectedRunRevision", command.expectedRunRevision());
        value.put("expectedApprovalRevision", command.expectedApprovalRevision());
        if(command.confirmRenewedExecution()) value.put("confirmRenewedExecution",true);
        return SecureUtil.sha256(AssistantPlanExecutionJson.canonical(value));
    }

    private AidRuntimeCommand findCommand(Long actorId, Long sessionId, String key)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = commands.selectOne(new LambdaQueryWrapper<AidRuntimeCommand>()
                .eq(AidRuntimeCommand::getActorId, actorId)
                .eq(AidRuntimeCommand::getSessionId, sessionId)
                .eq(AidRuntimeCommand::getClientRequestId, key).last("LIMIT 1"));
        if (command == null) return null;
        require("COMPLETED".equals(command.getStatus()) && StrUtil.isNotBlank(command.getResultJson()),
                "SNAPSHOT_REQUIRED");
        return command;
    }

    private AidRuntimeCommand startCommand(Long actorId, Long sessionId, String key,
                                           String digest, Date now)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand value = new AidRuntimeCommand();
        value.setActorId(actorId);
        value.setSessionId(sessionId);
        value.setClientRequestId(key);
        value.setPayloadDigest(digest);
        value.setCommandType("OPERATION_APPROVAL_CONFIRM");
        value.setStatus("PROCESSING");
        touchCreate(value, String.valueOf(actorId), now);
        try { commands.insert(value); }
        catch (DuplicateKeyException raced) { throw new ServiceException("SNAPSHOT_REQUIRED"); }
        return value;
    }

    private void completeCommand(AidRuntimeCommand command, AssistantPlanExecutionConfirmation result,
                                 String operator, Date now)
    {
        command.setStatus("COMPLETED");
        command.setResultJson(JSON.toJSONString(result));
        touchUpdate(command, operator, now);
        commands.updateById(command);
    }

    private AssistantPlanExecutionConfirmation confirmation(AidRuntimeCommand command)
    {
        try
        {
            AssistantPlanExecutionConfirmation value = JSON.parseObject(
                    command.getResultJson(), AssistantPlanExecutionConfirmation.class);
            require(value != null, "COMMAND_RESULT_INVALID");
            return value;
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException("COMMAND_RESULT_INVALID");
        }
    }

    private AssistantPlanExecutionConfirmation confirmation(AidRuntimeRun run,
                                                             AidAssistantSession session,
                                                             AidRuntimeApproval approval,
                                                             AidRuntimeRunStep child)
    {
        return new AssistantPlanExecutionConfirmation(run.getId(), longValue(run.getStateRevision()),
                approval.getId(), longValue(approval.getStateRevision()), child.getId(),
                child.getReservationId(), approval.getStatus(),
                Objects.toString(run.getNextAction(), ""), longValue(session.getLastEventSeq()));
    }

    private void appendEvent(AidAssistantSession session, AidRuntimeRun run, String type,
                             String entityId, Long entityRevision, String causationId,
                             Object payload, Date now, String operator)
    {
        session.setLastEventSeq(longValue(session.getLastEventSeq()) + 1);
        touchUpdate(session, operator, now);
        sessions.updateById(session);
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(run.getId());
        event.setSeq(session.getLastEventSeq());
        event.setEventType(type);
        event.setEntityId(entityId);
        event.setEntityRevision(entityRevision);
        event.setCausationId(causationId);
        event.setPayloadJson(payload == null ? null : JSON.toJSONString(payload));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        events.insert(event);
        realtimeEventPublisher.publishAfterCommit(event);
    }

    private AssistantPlanExecutionDecisionParser decisionParser()
    {
        return new AssistantPlanExecutionDecisionParser(contextLimits.maxReferences() + 1);
    }

    private long longValue(Long value) { return value == null ? 0L : value; }

    private void confirmReject(boolean condition, int status, String code, String message, Object details)
    {
        if (!condition) throw new AssistantServiceException(status, code, message, details);
    }

    private Map<String, Object> confirmDetails(AssistantPlanExecutionConfirmCommand request,
                                               AidRuntimeRun run,
                                               AidAssistantSession session,
                                               AidRuntimeApproval approval,
                                               AidRuntimeRunStep child)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", request == null || request.runId() == null ? null : String.valueOf(request.runId()));
        details.put("runRevision", run == null ? null : longValue(run.getStateRevision()));
        details.put("runStatus", run == null ? null : run.getStatus());
        details.put("nextAction", run == null ? null : run.getNextAction());
        details.put("approvalId", request == null || request.approvalId() == null
                ? null : String.valueOf(request.approvalId()));
        details.put("approvalRevision", approval == null ? null : longValue(approval.getStateRevision()));
        details.put("approvalStatus", approval == null ? null : approval.getStatus());
        details.put("childStepId", child == null || child.getId() == null ? null : String.valueOf(child.getId()));
        details.put("childStepStatus", child == null ? null : child.getStatus());
        details.put("lastEventSeq", session == null ? null : longValue(session.getLastEventSeq()));
        return details;
    }

    private Map<String, Object> confirmationDetails(AssistantPlanExecutionConfirmation result)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", result.runId() == null ? null : String.valueOf(result.runId()));
        details.put("runRevision", result.runRevision());
        details.put("approvalId", result.approvalId() == null ? null : String.valueOf(result.approvalId()));
        details.put("approvalRevision", result.approvalRevision());
        details.put("approvalStatus", result.status());
        details.put("childStepId", result.childStepId() == null ? null : String.valueOf(result.childStepId()));
        details.put("nextAction", result.nextAction());
        details.put("lastEventSeq", result.lastEventSeq());
        return details;
    }

    private Persisted decodeConfirmationScope(AssistantPlanExecutionConfirmCommand request,
                                               AidRuntimeRun run,
                                               AidAssistantSession session,
                                               AidRuntimeApproval approval)
    {
        try
        {
            return scopeCodec.decode(approval.getScopeJson());
        }
        catch (ServiceException invalid)
        {
            if (!"OPERATION_SCOPE_INVALID".equals(invalid.getMessage())) throw invalid;
            throw new AssistantServiceException(409, "OPERATION_SCOPE_INVALID",
                    "操作审批范围已变化", confirmDetails(request, run, session, approval, null));
        }
    }

    private void requireConfirmationScopeIdentity(AssistantPlanExecutionConfirmCommand request,
                                                  AidRuntimeRun run,
                                                  AidAssistantSession session,
                                                  AidRuntimeApproval approval,
                                                  Persisted persisted,
                                                  Long mainStepId,
                                                  AssistantPlanExecutionDecision decision,
                                                  BoundOperation bound)
    {
        try
        {
            scopeCodec.requireIdentity(persisted, mainStepId, decision, bound, run.getContextDigest());
        }
        catch (ServiceException changed)
        {
            if (!"OPERATION_SCOPE_CHANGED".equals(changed.getMessage())) throw changed;
            throw new AssistantServiceException(409, "OPERATION_SCOPE_INVALID",
                    "操作审批范围已变化", confirmDetails(request, run, session, approval, null));
        }
    }

    private AssistantPlanExecutionDecision decisionForApproval(String mainDecisionJson, Persisted persisted)
    {
        if (!NativeAssistantToolBatch.isBatch(mainDecisionJson))
            return decisionParser().parse(mainDecisionJson);
        NativeAssistantToolBatch.Batch batch = NativeAssistantToolBatch.parse(mainDecisionJson);
        String decisionId = persisted == null ? null : persisted.decision().getString("decisionId");
        AssistantPlanExecutionDecision selected = null;
        for (NativeAssistantToolBatch.Call call : batch.calls())
        {
            AssistantPlanExecutionDecision candidate = decisionParser().parse(call.decisionJson());
            if (!Objects.equals(candidate.decisionId(), decisionId)) continue;
            require(selected == null, "OPERATION_SCOPE_CHANGED");
            selected = candidate;
        }
        require(selected != null, "OPERATION_SCOPE_CHANGED");
        return selected;
    }

    private void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Preflight(AssistantPlanExecutionCommand command,
                             AssistantPlanExecutionDecision decision,
                             AidRuntimeRun run,
                             AidAssistantSession session,
                             AidRuntimePlan plan,
                             AssistantRuntimeContextManifestService.ResolvedManifest manifest,
                             BoundOperation bound,
                             AssistantBudgetOperationScope operationScope,
                             String clientKey)
    { }

    private record PreparationSlot(Long stepId, boolean finalized) { }

    private enum InvalidPersistedSubmissionReason
    {
        RESERVATION_ID_MISSING,
        RESERVATION_LIST_INVALID,
        RESERVATION_MISSING,
        RESERVATION_IDENTITY_CHANGED
    }

    private static final class InvalidPersistedSubmissionException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final InvalidPersistedSubmissionReason reason;

        private InvalidPersistedSubmissionException(InvalidPersistedSubmissionReason reason)
        {
            super(java.util.Objects.requireNonNull(reason).name());
            this.reason = reason;
        }

        private InvalidPersistedSubmissionReason reason()
        {
            return reason;
        }
    }

    private record Dispatch(Long runId,
                            Long mainStepId,
                            Long stepId,
                            AidRuntimeRun run,
                            AidAssistantSession session,
                            AidRuntimeRunStep main,
                            AidRuntimeRunStep step,
                            AidRuntimeApproval approval,
                            Persisted persisted,
                            BoundOperation bound,
                            RuntimeLease runLease,
                            RuntimeLease stepLease,
                            String commandDecisionJson)
    {
        Dispatch withStepLease(RuntimeLease lease)
        {
            return new Dispatch(runId, mainStepId, stepId, run, session, main, step, approval,
                    persisted, bound, runLease, lease, commandDecisionJson);
        }
    }

    private record Locked(AidAssistantSession session, AidRuntimeRun run, AidRuntimeApproval approval,
                          AidRuntimeRunStep main, AidRuntimeRunStep child) { }
    @Resource private AssistantOperationQuoteLineage operationQuoteLineage;
    @Resource private com.aid.billing.mapper.AidBillingAuthorizationReservationMapper quoteReservations;
    @Resource private com.aid.assistant.service.AssistantQuiescentRunGuard quoteGraphGuard;

    @Override
    public AssistantPlanExecutionQuoted quote(AssistantPlanExecutionQuoteCommand request) {
        try {return quoteOnly(request);}
        catch(ServiceException rejected) {
            var known=java.util.Set.of("OPERATION_QUOTE_REQUEST_INVALID","RUNTIME_RUN_NOT_FOUND","ASSISTANT_SESSION_NOT_FOUND",
                    "IDEMPOTENCY_KEY_REUSED","SNAPSHOT_REQUIRED","RUNTIME_REVISION_CONFLICT","OPERATION_APPROVAL_STATE_CHANGED",
                    "OPERATION_SCOPE_INVALID","OPERATION_SCOPE_NOT_CANONICAL","OPERATION_CONTEXT_CHANGED","OPERATION_EXECUTION_CONTEXT_CHANGED",
                    "OPERATION_SCOPE_CHANGED","OPERATION_STEP_CHANGED","OPERATION_QUOTE_SUBMISSION_UNPROVEN","OPERATION_QUOTE_GRAPH_UNPROVEN",
                    "OPERATION_QUOTE_INPUT_CHANGED","OPERATION_QUOTE_SOURCE_CHANGED","OPERATION_QUOTE_SOURCE_UNKNOWN","OPERATION_QUOTE_NOT_SUPPORTED",
                    "OPERATION_QUOTE_REFERENCE_UNAVAILABLE","OPERATION_QUOTE_REFERENCE_TOKEN_INVALID","OPERATION_QUOTE_LINEAGE_INVALID",
                    "OPERATION_QUOTE_LINEAGE_TOO_LARGE","BUDGET_POLICY_CHANGED","BUDGET_POLICY_INACTIVE","OPERATION_CONTEXT_REVISIONS_CHANGED",
                    "PLAN_NOT_APPROVED","PLAN_IDENTITY_CHANGED","PLAN_DIGEST_CHANGED","PLAN_INPUTS_CHANGED","TOOL_BINDING_CHANGED");
            String code=rejected.getDetailMessage()!=null && known.contains(rejected.getDetailMessage()) ? rejected.getDetailMessage() : rejected.getMessage();
            if(code==null || !known.contains(code))throw rejected;
            int status="OPERATION_QUOTE_REQUEST_INVALID".equals(code)?422
                    : java.util.Set.of("RUNTIME_RUN_NOT_FOUND","ASSISTANT_SESSION_NOT_FOUND").contains(code)?404:409;
            JSONObject details=new JSONObject();details.put("executionSubmitted",false);details.put("nextAction","REFRESH_OPERATION_APPROVAL");
            if(request!=null) {details.put("runId",String.valueOf(request.runId()));details.put("approvalId",String.valueOf(request.approvalId()));}
            throw new AssistantServiceException(status,code,"操作报价暂不可更新，请刷新状态核对",details);
        }
    }

    private AssistantPlanExecutionQuoted quoteOnly(AssistantPlanExecutionQuoteCommand request) {
        require(request!=null && request.userId()!=null && request.userId()>0 && request.runId()!=null && request.runId()>0
                && request.approvalId()!=null && request.approvalId()>0 && request.expectedRunRevision()>=0
                && request.expectedApprovalRevision()>=0 && request.clientRequestId()!=null
                && request.clientRequestId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
                && (request.referenceRevalidationToken()==null || request.referenceRevalidationToken().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")),"OPERATION_QUOTE_REQUEST_INVALID");
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(request.clientRequestId());
        QuoteSource source=tx().execute(status->quoteSourceLocked(request));
        require(source!=null,"OPERATION_QUOTE_SOURCE_UNKNOWN");
        rejectReferenceChallenge(source); // The FAILED challenge transaction has committed before this 409.
        if(source.replay()!=null)return source.replay();
        if(quoteCurrent(source,new Date())) {
            return tx().execute(status->{
                QuoteSource current=quoteSourceLocked(request);
                if(current.replay()!=null)return current.replay();
                require(Objects.equals(source.fingerprint(),current.fingerprint()),"OPERATION_QUOTE_INPUT_CHANGED");
                if(!quoteCurrent(current,new Date()))throw new ServiceException("OPERATION_QUOTE_SOURCE_CHANGED");
                return completeQuoteOnly(request,current,null);
            });
        }
        // Price/metadata validation may be expensive. It owns no run/session transaction or lease.
        var preview=toolRegistry.repricePreparedQuote(source.priceRequest());
        return tx().execute(status->{
            QuoteSource current=quoteSourceLocked(request);
            if(current.replay()!=null)return current.replay();
            require(Objects.equals(source.fingerprint(),current.fingerprint()),"OPERATION_QUOTE_INPUT_CHANGED");
            require(!quoteCurrent(current,new Date()),"OPERATION_QUOTE_SOURCE_CHANGED");
            return completeQuoteOnly(request,current,preview);
        });
    }

    private QuoteSource quoteSourceLocked(AssistantPlanExecutionQuoteCommand request) {
        AidRuntimeRun snapshot=requireRun(request.runId(),false);
        AidAssistantSession session=requireSession(snapshot.getSessionId(),request.userId(),true);
        AidRuntimeRun run=requireRun(request.runId(),true);
        require(Objects.equals(run.getUserId(),request.userId()) && Objects.equals(run.getSessionId(),session.getId()),"RUNTIME_RUN_NOT_FOUND");
        AidRuntimeCommand receipt=quoteCommand(request.userId(),session.getId(),request.clientRequestId());
        if(receipt!=null) {
            require("OPERATION_QUOTE".equals(receipt.getCommandType())
                    && Objects.equals(receipt.getPayloadDigest(),AssistantOperationQuoteLineage.payloadDigest(request)),"IDEMPOTENCY_KEY_REUSED");
            if("FAILED".equals(receipt.getStatus()) && REFERENCE_REQUIRED.equals(receipt.getErrorCode()))
                return new QuoteSource(session,run,null,null,null,null,null,null,null,null,null,
                        validatedReferenceChallengeReceipt(receipt).getJSONObject("challenge"));
            require("COMPLETED".equals(receipt.getStatus()) && "0".equals(receipt.getDelFlag()),"SNAPSHOT_REQUIRED");
            return new QuoteSource(session,run,null,null,null,null,null,null,quoteReplay(receipt,request),null,null);
        }
        require(Objects.equals(session.getActiveRuntimeRunId(),run.getId()),"RUNTIME_RUN_NOT_FOUND");
        require(Objects.equals(run.getStateRevision(),request.expectedRunRevision()),"RUNTIME_REVISION_CONFLICT");
        require("WAITING_APPROVAL".equals(run.getStatus()) && "CONFIRM_OPERATION_APPROVAL".equals(run.getNextAction())
                && !Boolean.TRUE.equals(run.getStopRequested()),"OPERATION_APPROVAL_STATE_CHANGED");
        AidRuntimeBudgetPolicy policy=policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId,run.getId()).eq(AidRuntimeBudgetPolicy::getDelFlag,"0").last("FOR UPDATE"));
        require(policy!=null,"BUDGET_POLICY_INACTIVE");
        AidRuntimeApproval approval=approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId,request.approvalId()).eq(AidRuntimeApproval::getRunId,run.getId())
                .eq(AidRuntimeApproval::getUserId,request.userId()).eq(AidRuntimeApproval::getDelFlag,"0").last("FOR UPDATE"));
        require(approval!=null && "PLAN_EXECUTION".equals(approval.getScopeKind())
                && Objects.equals(approval.getStateRevision(),request.expectedApprovalRevision())
                && List.of("QUOTED","INVALIDATED").contains(approval.getStatus()) && "MANUAL".equals(approval.getMode())
                && approval.getAcceptedAt()==null,"OPERATION_APPROVAL_STATE_CHANGED");
        Persisted persisted=scopeCodec.decode(approval.getScopeJson());
        require(Objects.equals(persisted.digest(),approval.getScopeDigest()) && persisted.isTool() && persisted.billable()
                && persisted.billingIntent()!=null,"OPERATION_SCOPE_INVALID");
        AidRuntimeRunStep main=steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId,persisted.decision().getLong("mainAgentStepId"))
                .eq(AidRuntimeRunStep::getRunId,run.getId()).eq(AidRuntimeRunStep::getDelFlag,"0").last("FOR UPDATE"));
        var candidates=steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>().eq(AidRuntimeRunStep::getApprovalId,approval.getId())
                .last("LIMIT 2 FOR UPDATE"));
        require(candidates.size()==1,"OPERATION_STEP_CHANGED");AidRuntimeRunStep child=candidates.get(0);
        AssistantOperationQuoteSourceGuard.requireBudgetRenewalOperationIdentity(run,main,child,approval);
        require("DECISION_READY".equals(main.getStatus()) && "TOOL".equals(child.getStepKind())
                && "AWAITING_APPROVAL".equals(child.getStatus()) && "NOT_SUBMITTED".equals(child.getProviderAcceptanceState())
                && child.getMediaTaskId()==null && child.getReservationId()==null && child.getChildRunId()==null
                && child.getChildRunKind()==null && StrUtil.isBlank(child.getOutputJson()) && emptyArtifactRefs(child.getOutputArtifactRef())
                && StrUtil.isBlank(child.getLeaseOwner()) && child.getLeaseUntil()==null
                && (child.getFencingToken()==null || child.getFencingToken()==0),"OPERATION_QUOTE_SUBMISSION_UNPROVEN");
        // TARGET_DECISION_RESTORE
        AssistantPlanExecutionDecision decision=decisionForApproval(main.getOutputJson(),persisted);
        AidRuntimePlan plan=requirePlan(run.getId(),run.getPlanRevision(),true);
        BoundOperation bound=planResolver.resolve(run,plan,decision);
        scopeCodec.requireIdentity(persisted,main.getId(),decision,bound,run.getContextDigest());
        var manifest=manifests.requirePinned(run.getUserId(),session,run);
        requireExpectedContextRevisions(decision,manifest,run,session);
        operationQuoteLineage.requireApprovalKey(run,child,approval,decision.invocationId(),true);
        if("INVALIDATED".equals(approval.getStatus()))
            AssistantOperationQuoteGrantHistory.requireBudgetRepriceSource(run,approval,policy.getAuthorizationId(),policy.getAuthorizationRevision(),
                    policy.getScopeDigest(),operationQuoteLineage.loadBudgetGrantHistory(run,policy.getAuthorizationId(),
                            approval.getBillingAuthorizationRevision(),policy.getAuthorizationRevision(),true));
        else require(approval.getInvalidatedAt()==null && Objects.equals(approval.getBillingAuthorizationId(),policy.getAuthorizationId())
                && Objects.equals(approval.getBillingAuthorizationRevision(),policy.getAuthorizationRevision()),"BUDGET_POLICY_CHANGED");
        requireQuoteNoSubmission(run,child,approval);
        var graphProof=quoteGraphGuard.inspectBudgetRenewal(run.getUserId(),run,policy,true);
        require(graphProof!=null && graphProof.blockReason()==null,"OPERATION_QUOTE_GRAPH_UNPROVEN");
        var admission=budgetPolicies.assertOperationQuotable(run.getId(),persisted.operationScope());
        var priceRequest=new com.aid.runtime.tool.RuntimeToolQuoteRequest(run.getId(),run.getUserId(),child.getId(),child.getPlanRevision(),
                child.getStableStepId(),child.getAttemptNo(),persisted.operationScope().getOperationKind(),child.getToolCode(),
                bound.bindingVersion(),bound.schemaDigest(),persisted.argumentsJson(),persisted.argumentsDigest(),persisted.executionManifestJson(),
                persisted.digest(),child.getIdempotencyKey(),JSON.toJSONString(persisted.operationScope()),persisted.preparedPayloadJson(),
                persisted.preparedPayloadDigest(),JSON.toJSONString(persisted.billingIntent()),null);
        try {toolRegistry.validateQuoteReadOnly(priceRequest);}
        catch(com.aid.runtime.tool.RuntimeToolQuoteReferenceRequiredException required) {
            if(request.referenceRevalidationToken()==null)
                return new QuoteSource(session,run,null,null,null,null,null,null,null,null,null,
                        recordReferenceChallenge(request,session,approval,required.evidence()));
            JSONObject challenge=requireReferenceChallenge(request,session,approval,required.evidence());
            priceRequest=priceRequest.withReferenceRevalidation(AssistantPlanExecutionJson.canonicalExplicitNulls(challenge.getJSONObject("evidence")));
            toolRegistry.validateQuoteReadOnly(priceRequest);
        }
        if(request.referenceRevalidationToken()!=null && priceRequest.referenceRevalidationJson()==null)
            throw new ServiceException("OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        JSONObject fingerprint=new JSONObject();fingerprint.put("scope",approval.getScopeJson());fingerprint.put("scopeDigest",approval.getScopeDigest());
        fingerprint.put("status",approval.getStatus());fingerprint.put("approvalRevision",approval.getStateRevision());fingerprint.put("runRevision",run.getStateRevision());
        fingerprint.put("child",JSON.parseObject(JSON.toJSONString(child)));fingerprint.put("policy",JSON.parseObject(JSON.toJSONString(policy)));
        fingerprint.put("referenceRevalidation",priceRequest.referenceRevalidationJson());
        return new QuoteSource(session,run,policy,approval,main,child,persisted,admission,null,priceRequest,
                SecureUtil.sha256(AssistantPlanExecutionJson.canonicalExplicitNulls(fingerprint)));
    }

    private void requireQuoteNoSubmission(AidRuntimeRun run,AidRuntimeRunStep child,AidRuntimeApproval approval) {
        var tasks=mediaTasks.selectList(new LambdaQueryWrapper<AidMediaTask>().select(AidMediaTask::getId)
                .and(query->query.eq(AidMediaTask::getBizTaskType,BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE)
                        .eq(AidMediaTask::getBizTaskId,child.getId()).or().eq(AidMediaTask::getIdempotencyKey,child.getIdempotencyKey()))
                .last("LIMIT 1 FOR UPDATE"));
        require(tasks.isEmpty(),"OPERATION_QUOTE_SUBMISSION_UNPROVEN");
        var reservations=quoteReservations.selectList(new LambdaQueryWrapper<com.aid.billing.domain.AidBillingAuthorizationReservation>()
                .select(com.aid.billing.domain.AidBillingAuthorizationReservation::getId)
                .and(query->query.nested(step->step.eq(com.aid.billing.domain.AidBillingAuthorizationReservation::getRuntimeStepId,child.getId())
                        .in(com.aid.billing.domain.AidBillingAuthorizationReservation::getOperationKind,List.of("ASSISTANT_DECISION","ASSISTANT_TOOL","ASSISTANT_SKILL")))
                        .or().eq(com.aid.billing.domain.AidBillingAuthorizationReservation::getApprovalId,approval.getId())
                        .or().eq(com.aid.billing.domain.AidBillingAuthorizationReservation::getLogicalCallKey,child.getIdempotencyKey()))
                .last("LIMIT 1 FOR UPDATE"));
        require(reservations.isEmpty(),"OPERATION_QUOTE_SUBMISSION_UNPROVEN");
        // The provider's read-only capability independently rejects any existing private target task intent.
    }

    private boolean quoteCurrent(QuoteSource source,Date now) {
        return source.priceRequest().referenceRevalidationJson()==null
                && "QUOTED".equals(source.approval().getStatus()) && source.approval().getExpiresAt()!=null
                && source.approval().getExpiresAt().after(now) && source.persisted().quoteExpiresAt()!=null
                && source.persisted().quoteExpiresAt().after(now);
    }

    private AssistantPlanExecutionQuoted completeQuoteOnly(AssistantPlanExecutionQuoteCommand request,
            QuoteSource source,com.aid.runtime.tool.RuntimeToolPreparation preview) {
        Date now=new Date();String operator=String.valueOf(request.userId());
        AidRuntimeCommand receipt=startQuoteCommand(request.userId(),source.session().getId(),request.clientRequestId(),
                AssistantOperationQuoteLineage.payloadDigest(request),now);
        AidRuntimeApproval previous=source.approval();AidRuntimeApproval current=previous;
        if(preview!=null) {
            require(preview.getBillingIntent().getQuoteExpiresAt().after(now),"OPERATION_QUOTE_SOURCE_CHANGED");
            var admission=budgetPolicies.assertOperationQuotable(source.run().getId(),preview.getOperationScope());
            JSONObject root=JSON.parseObject(previous.getScopeJson());
            JSONObject priorLink=root.getJSONObject(AssistantOperationQuoteLineage.FIELD);
            JSONObject link=new JSONObject();link.put("schemaVersion",AssistantOperationQuoteLineage.SCHEMA);
            link.put("expectedSourceRunRevision",request.expectedRunRevision());link.put("previousApprovalId",previous.getId());
            link.put("previousApprovalRevision",request.expectedApprovalRevision());link.put("previousApprovalStatus",previous.getStatus());
            link.put("previousScopeDigest",previous.getScopeDigest());link.put("rootApprovalId",priorLink==null?previous.getId():priorLink.getLong("rootApprovalId"));
            link.put("rootClientRequestId",priorLink==null?previous.getClientRequestId():priorLink.getString("rootClientRequestId"));
            link.put("quoteCommandKey",request.clientRequestId());link.put("quoteCommandPayloadDigest",receipt.getPayloadDigest());
            link.put("childStepId",source.child().getId());link.put("requestDigest",previous.getRequestDigest());
            var history=priorLink==null?new java.util.ArrayList<Long>():new java.util.ArrayList<>(priorLink.getJSONArray("historyApprovalIds").toJavaList(Long.class));
            require(history.size()<AssistantOperationQuoteLineage.MAX,"OPERATION_QUOTE_LINEAGE_TOO_LARGE");history.add(previous.getId());link.put("historyApprovalIds",history);
            if(request.referenceRevalidationToken()!=null)link.put("referenceRevalidationToken",request.referenceRevalidationToken());
            root.put(AssistantOperationQuoteLineage.FIELD,link);root.put("operationScope",JSON.parseObject(JSON.toJSONString(preview.getOperationScope())));
            JSONObject policy=root.getJSONObject("policy");policy.put("authorizationId",admission.authorizationId());policy.put("authorizationRevision",admission.authorizationRevision());
            policy.put("revision",admission.policyRevision());policy.put("mode",admission.policyMode());policy.put("scopeDigest",admission.scopeDigest());
            JSONObject tool=root.getJSONObject("tool");tool.put("preparedPayloadJson",preview.getPreparedPayloadJson());
            tool.put("preparedPayloadDigest",SecureUtil.sha256(preview.getPreparedPayloadJson()));tool.put("billingIntent",JSON.parseObject(JSON.toJSONString(preview.getBillingIntent())));
            String scopeJson=AssistantPlanExecutionJson.canonical(root);Persisted persisted=scopeCodec.decode(scopeJson);
            current=new AidRuntimeApproval();org.springframework.beans.BeanUtils.copyProperties(previous,current);
            current.setId(null);current.setCreateTime(null);current.setUpdateTime(null);current.setInvalidatedAt(null);current.setAcceptedAt(null);
            current.setClientRequestId(AssistantOperationQuoteLineage.replacementKey(request,source.session().getId()));
            current.setBillingAuthorizationId(admission.authorizationId());current.setBillingAuthorizationRevision(admission.authorizationRevision());
            current.setScopeJson(scopeJson);current.setScopeDigest(persisted.digest());current.setQuoteId(persisted.quoteId());current.setQuoteDigest(persisted.quoteDigest());
            current.setQuoteRef("tool:"+persisted.quoteId());current.setMaxCharge(persisted.quotedUpperBound());
            current.setExpiresAt(earlier(persisted.quoteExpiresAt(),source.policy().getExpiresAt()));
            current.setStatus("QUOTED");current.setMode("MANUAL");current.setStateRevision(0L);touchCreate(current,operator,now);
            require(approvals.insert(current)==1 && current.getId()!=null,"OPERATION_QUOTE_WRITE_CONFLICT");
            if("QUOTED".equals(previous.getStatus())) {
                previous.setStatus("INVALIDATED");previous.setInvalidatedAt(now);previous.setStateRevision(request.expectedApprovalRevision()+1);
                touchUpdate(previous,operator,now);require(approvals.updateById(previous)==1,"OPERATION_QUOTE_WRITE_CONFLICT");
            }
            source.child().setApprovalId(current.getId());touchUpdate(source.child(),operator,now);
            require(steps.updateById(source.child())==1,"OPERATION_QUOTE_WRITE_CONFLICT");
            source.run().setStateRevision(request.expectedRunRevision()+1);touchUpdate(source.run(),operator,now);
            require(runs.update(source.run(),new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidRuntimeRun>()
                    .eq(AidRuntimeRun::getId,source.run().getId()).eq(AidRuntimeRun::getStateRevision,request.expectedRunRevision()))==1,"RUNTIME_REVISION_CONFLICT");
            appendEvent(source.session(),source.run(),"operation.approval_required",String.valueOf(current.getId()),0L,String.valueOf(receipt.getId()),
                    Map.of("approvalId",String.valueOf(current.getId()),"previousApprovalId",String.valueOf(previous.getId()),
                            "childStepId",String.valueOf(source.child().getId()),"quoteOnly",true),now,operator);
            appendEvent(source.session(),source.run(),"run.state_changed",String.valueOf(source.run().getId()),source.run().getStateRevision(),
                    String.valueOf(receipt.getId()),Map.of("status",source.run().getStatus(),"nextAction",source.run().getNextAction()),now,operator);
        }
        var quote=new AssistantOperationQuoteView();quote.setQuoteId(current.getQuoteId());
        quote.setModelCode((preview==null?source.persisted().operationScope():preview.getOperationScope()).getModelCode());
        quote.setEstimatedAmount(null);quote.setQuotedUpperBound(current.getMaxCharge().setScale(4,java.math.RoundingMode.UNNECESSARY).toPlainString());
        quote.setCurrencyUnit("BALANCE");quote.setPrecisionScale(4);quote.setExpiresAt(current.getExpiresAt().toInstant().toString());quote.setHardUpperBoundAvailable(true);
        var result=new AssistantPlanExecutionQuoted(String.valueOf(source.run().getId()),longValue(source.run().getStateRevision()),String.valueOf(current.getId()),
                longValue(current.getStateRevision()),preview==null?null:String.valueOf(previous.getId()),String.valueOf(source.child().getId()),
                longValue(source.run().getPlanRevision()),"PLAN_EXECUTION","QUOTED","CONFIRM_OPERATION_APPROVAL",preview==null?"UNCHANGED":"REPLACED",
                quote,longValue(source.session().getLastEventSeq()),false,source.priceRequest().referenceRevalidationJson()!=null,
                source.priceRequest().referenceRevalidationJson()==null?List.of():JSON.parseObject(source.priceRequest().referenceRevalidationJson()).getJSONArray("resources").toJavaList(JSONObject.class));
        receipt.setStatus("COMPLETED");receipt.setResultJson(JSON.toJSONString(result,com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));touchUpdate(receipt,operator,now);
        require(commands.updateById(receipt)==1,"COMMAND_STATE_UNAVAILABLE");return result;
    }

    private AssistantPlanExecutionQuoted quoteReplay(AidRuntimeCommand receipt,AssistantPlanExecutionQuoteCommand request) {
        AssistantPlanExecutionQuoted result=JSON.parseObject(receipt.getResultJson(),AssistantPlanExecutionQuoted.class);
        require(result!=null && !result.executionSubmitted() && "PLAN_EXECUTION".equals(result.scopeKind())
                && "QUOTED".equals(result.status()) && List.of("UNCHANGED","REPLACED").contains(result.quoteDisposition())
                && result.runId()!=null && result.approvalId()!=null && result.stepId()!=null && result.quote()!=null
                && result.quote().isHardUpperBoundAvailable() && result.quote().getQuoteId()!=null
                && result.quote().getQuotedUpperBound()!=null && result.quote().getExpiresAt()!=null
                && Objects.equals(result.runId(),String.valueOf(request.runId())) && result.approvalId().matches("[1-9][0-9]{0,18}")
                && result.stepId().matches("[1-9][0-9]{0,18}") && result.planRevision()>=0 && result.lastEventSeq()>=0
                && "CONFIRM_OPERATION_APPROVAL".equals(result.nextAction()) && "BALANCE".equals(result.quote().getCurrencyUnit())
                && Objects.equals(result.quote().getPrecisionScale(),4) && result.referenceResources()!=null
                && result.quote().getExpiresAt().endsWith("Z")
                && result.quote().getQuotedUpperBound().matches("(?:0|[1-9][0-9]*)\\.[0-9]{4}"),"COMMAND_RESULT_INVALID");
        java.time.Instant.parse(result.quote().getExpiresAt());
        require("UNCHANGED".equals(result.quoteDisposition()) ? result.previousApprovalId()==null
                        && Objects.equals(result.approvalId(),String.valueOf(request.approvalId()))
                        && result.approvalRevision()==request.expectedApprovalRevision() && result.runRevision()==request.expectedRunRevision()
                        && !result.referenceRevalidated() && result.referenceResources().isEmpty()
                    : Objects.equals(result.previousApprovalId(),String.valueOf(request.approvalId()))
                        && result.runRevision()==request.expectedRunRevision()+1 && result.approvalRevision()==0
                        && result.referenceRevalidated()==(request.referenceRevalidationToken()!=null)
                        && (result.referenceRevalidated()?!result.referenceResources().isEmpty():result.referenceResources().isEmpty()),"COMMAND_RESULT_INVALID");
        return result;
    }

    private AidRuntimeCommand startQuoteCommand(Long actorId,Long sessionId,String key,String digest,Date now) {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand value=new AidRuntimeCommand();value.setActorId(actorId);value.setSessionId(sessionId);
        value.setClientRequestId(key);value.setPayloadDigest(digest);value.setCommandType("OPERATION_QUOTE");
        value.setStatus("PROCESSING");touchCreate(value,String.valueOf(actorId),now);
        try {require(commands.insert(value)==1,"COMMAND_STATE_UNAVAILABLE");}
        catch(DuplicateKeyException raced){throw new ServiceException("SNAPSHOT_REQUIRED");}
        return value;
    }

    private record QuoteSource(AidAssistantSession session,AidRuntimeRun run,AidRuntimeBudgetPolicy policy,AidRuntimeApproval approval,
            AidRuntimeRunStep main,AidRuntimeRunStep child,Persisted persisted,AssistantBudgetOperationAdmission admission,
            AssistantPlanExecutionQuoted replay,com.aid.runtime.tool.RuntimeToolQuoteRequest priceRequest,String fingerprint,
            JSONObject challenge) {
        QuoteSource(AidAssistantSession session,AidRuntimeRun run,AidRuntimeBudgetPolicy policy,AidRuntimeApproval approval,
            AidRuntimeRunStep main,AidRuntimeRunStep child,Persisted persisted,AssistantBudgetOperationAdmission admission,
            AssistantPlanExecutionQuoted replay,com.aid.runtime.tool.RuntimeToolQuoteRequest priceRequest,String fingerprint) {
            this(session,run,policy,approval,main,child,persisted,admission,replay,priceRequest,fingerprint,null);
        }
    }


    private void requireCurrentOperationReferences(AssistantPlanExecutionConfirmCommand request,AidRuntimeRun run,
            AidAssistantSession session,AidRuntimeApproval approval,AidRuntimeRunStep child,Persisted persisted,BoundOperation bound) {
        if(!persisted.isTool() || !persisted.billable() || persisted.preparedPayloadJson()==null)return;
        var payload=JSON.parseObject(persisted.preparedPayloadJson());
        if(payload==null || !payload.containsKey("serverResourceEvidence"))return;
        var current=new com.aid.runtime.tool.RuntimeToolQuoteRequest(run.getId(),run.getUserId(),child.getId(),child.getPlanRevision(),
                child.getStableStepId(),child.getAttemptNo(),persisted.operationScope().getOperationKind(),child.getToolCode(),bound.bindingVersion(),bound.schemaDigest(),
                persisted.argumentsJson(),persisted.argumentsDigest(),persisted.executionManifestJson(),persisted.digest(),child.getIdempotencyKey(),
                JSON.toJSONString(persisted.operationScope()),persisted.preparedPayloadJson(),persisted.preparedPayloadDigest(),JSON.toJSONString(persisted.billingIntent()),null);
        try {toolRegistry.validatePreparedReferencesReadOnly(current);}
        catch(ServiceException denied) {
            var known=java.util.Set.of("OPERATION_QUOTE_REFERENCE_UNAVAILABLE","OPERATION_QUOTE_INPUT_CHANGED","OPERATION_REFERENCE_ACCESS_DENIED",
                    "CANVAS_TARGET_INVALID","CANVAS_TARGET_NOT_AUTHORIZED","CANVAS_REVISION_INVALID","CANVAS_MEDIA_NODE_TYPE_OR_VERSION_INVALID",
                    "CANVAS_MANIFEST_MISSING","CANVAS_MANIFEST_INVALID","CANVAS_MANIFEST_REFS_INVALID","CANVAS_MANIFEST_REF_INVALID","CANVAS_CANONICAL_REF_INVALID",
                    "CANVAS_TARGET_REF_INVALID","CANVAS_CONTENT_REF_INVALID","CANVAS_PROJECT_INVALID","CANVAS_WORKSPACE_INVALID","CANVAS_ACCESS_INVALID");
            String code=denied.getDetailMessage()!=null && known.contains(denied.getDetailMessage())?denied.getDetailMessage():denied.getMessage();
            if(code==null || !known.contains(code))throw denied;
            var details=new java.util.LinkedHashMap<String,Object>(confirmDetails(request,run,session,approval,child));details.put("executionSubmitted",false);
            throw new AssistantServiceException("OPERATION_REFERENCE_ACCESS_DENIED".equals(code)?403:409,
                    "OPERATION_APPROVAL_CHANGED","操作素材或访问范围已变化，请核对后重新报价",details);
        }
    }
    private static final String REFERENCE_REQUIRED="OPERATION_QUOTE_REFERENCE_REVALIDATION_REQUIRED";

    private void rejectReferenceChallenge(QuoteSource source) {
        if(source.challenge()!=null)throw new AssistantServiceException(409,REFERENCE_REQUIRED,
                "请核对当前素材后明确重新报价",source.challenge());
    }

    private JSONObject recordReferenceChallenge(AssistantPlanExecutionQuoteCommand request,AidAssistantSession session,
            AidRuntimeApproval approval,JSONObject evidence) {
        Date now=new Date();var receipt=startQuoteCommand(request.userId(),session.getId(),request.clientRequestId(),
                AssistantOperationQuoteLineage.payloadDigest(request),now);
        JSONObject challenge=new JSONObject();challenge.put("runId",String.valueOf(request.runId()));
        challenge.put("runRevision",request.expectedRunRevision());challenge.put("approvalId",String.valueOf(request.approvalId()));
        challenge.put("approvalRevision",request.expectedApprovalRevision());challenge.put("referenceRevalidationToken",request.clientRequestId());
        challenge.put("expiresAt",now.toInstant().plusSeconds(300).toString());challenge.put("resources",evidence.getJSONArray("resources"));
        challenge.put("executionSubmitted",false);
        JSONObject stored=new JSONObject();stored.put("schemaVersion","assistant-operation-reference-challenge/v1");
        stored.put("sourceScopeDigest",approval.getScopeDigest());stored.put("sourceRequestDigest",approval.getRequestDigest());
        stored.put("requestPayloadDigest",receipt.getPayloadDigest());stored.put("challenge",challenge);stored.put("evidence",evidence);
        receipt.setStatus("FAILED");receipt.setErrorCode(REFERENCE_REQUIRED);receipt.setResultJson(AssistantPlanExecutionJson.canonicalExplicitNulls(stored));
        touchUpdate(receipt,String.valueOf(request.userId()),now);require(commands.updateById(receipt)==1,"COMMAND_STATE_UNAVAILABLE");
        return challenge;
    }

    private JSONObject requireReferenceChallenge(AssistantPlanExecutionQuoteCommand request,AidAssistantSession session,
            AidRuntimeApproval approval,JSONObject evidence) {
        String token=request.referenceRevalidationToken();
        require(token!=null && token.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
                && !Objects.equals(token,request.clientRequestId()),"OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(token);
        var receipt=quoteCommand(request.userId(),session.getId(),token);
        JSONObject stored=validatedReferenceChallengeReceipt(receipt);
        JSONObject challenge=stored.getJSONObject("challenge");
        var original=new AssistantPlanExecutionQuoteCommand(request.userId(),request.runId(),request.approvalId(),
                request.expectedRunRevision(),request.expectedApprovalRevision(),token);
        require(Objects.equals(receipt.getActorId(),request.userId()) && Objects.equals(receipt.getSessionId(),session.getId())
                && Objects.equals(receipt.getPayloadDigest(),AssistantOperationQuoteLineage.payloadDigest(original))
                && Objects.equals(challenge.getString("runId"),String.valueOf(request.runId()))
                && Objects.equals(challenge.getLong("runRevision"),request.expectedRunRevision())
                && Objects.equals(challenge.getString("approvalId"),String.valueOf(request.approvalId()))
                && Objects.equals(challenge.getLong("approvalRevision"),request.expectedApprovalRevision())
                && Objects.equals(challenge.getString("referenceRevalidationToken"),token)
                && Objects.equals(stored.getString("sourceScopeDigest"),approval.getScopeDigest())
                && Objects.equals(stored.getString("sourceRequestDigest"),approval.getRequestDigest())
                && Objects.equals(AssistantPlanExecutionJson.canonicalExplicitNulls(stored.getJSONObject("evidence")),
                        AssistantPlanExecutionJson.canonicalExplicitNulls(evidence))
                && java.time.Instant.parse(challenge.getString("expiresAt")).isAfter(java.time.Instant.now()),
                "OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        return stored;
    }

    private JSONObject validatedReferenceChallengeReceipt(AidRuntimeCommand receipt) {
        require(receipt!=null && "0".equals(receipt.getDelFlag()) && "OPERATION_QUOTE".equals(receipt.getCommandType())
                && "FAILED".equals(receipt.getStatus()) && REFERENCE_REQUIRED.equals(receipt.getErrorCode()),
                "OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        JSONObject stored=JSON.parseObject(receipt.getResultJson());
        require(stored!=null && java.util.Set.of("schemaVersion","sourceScopeDigest","sourceRequestDigest","requestPayloadDigest","challenge","evidence").equals(stored.keySet())
                && "assistant-operation-reference-challenge/v1".equals(stored.getString("schemaVersion"))
                && Objects.equals(stored.getString("requestPayloadDigest"),receipt.getPayloadDigest())
                && stored.getJSONObject("challenge")!=null && stored.getJSONObject("evidence")!=null,
                "OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        JSONObject challenge=stored.getJSONObject("challenge"),evidence=stored.getJSONObject("evidence");
        require(java.util.Set.of("runId","runRevision","approvalId","approvalRevision","referenceRevalidationToken","expiresAt","resources","executionSubmitted").equals(challenge.keySet())
                && (java.util.Set.of("schemaVersion","resources").equals(evidence.keySet())
                    || java.util.Set.of("schemaVersion","resources","referenceVideos").equals(evidence.keySet()))
                && "assistant-operation-resource-evidence/v1".equals(evidence.getString("schemaVersion"))
                && challenge.get("runId") instanceof String runId && runId.matches("[1-9][0-9]{0,18}")
                && challenge.get("approvalId") instanceof String approvalId && approvalId.matches("[1-9][0-9]{0,18}")
                && (challenge.get("runRevision") instanceof Integer || challenge.get("runRevision") instanceof Long)
                && challenge.getLong("runRevision")>=0
                && (challenge.get("approvalRevision") instanceof Integer || challenge.get("approvalRevision") instanceof Long)
                && challenge.getLong("approvalRevision")>=0
                && Boolean.FALSE.equals(challenge.get("executionSubmitted"))
                && Objects.equals(challenge.getString("referenceRevalidationToken"),receipt.getClientRequestId())
                && challenge.getString("expiresAt")!=null && challenge.getString("expiresAt").endsWith("Z")
                && challenge.getJSONArray("resources")!=null && !challenge.getJSONArray("resources").isEmpty()
                && challenge.getJSONArray("resources").size()<=200
                && Objects.equals(challenge.getJSONArray("resources"),evidence.getJSONArray("resources")),"OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        java.time.Instant.parse(challenge.getString("expiresAt"));
        var seen=new java.util.HashSet<String>();
        for(Object item:challenge.getJSONArray("resources")) {
            require(item instanceof JSONObject,"OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");JSONObject row=(JSONObject)item;
            require(java.util.Set.of("resourceId","identityHash","mediaType").equals(row.keySet())
                    && row.get("resourceId") instanceof String id && id.matches("[1-9][0-9]{0,18}") && seen.add(id)
                    && row.get("identityHash") instanceof String hash && hash.matches("[0-9a-f]{64}")
                    && row.get("mediaType") instanceof String media && java.util.Set.of("IMAGE","VIDEO","AUDIO").contains(media),
                    "OPERATION_QUOTE_REFERENCE_TOKEN_INVALID");
        }
        return stored;
    }

    private AidRuntimeCommand quoteCommand(Long actor,Long session,String key) {
        return commands.selectOne(new LambdaQueryWrapper<AidRuntimeCommand>().eq(AidRuntimeCommand::getActorId,actor)
                .eq(AidRuntimeCommand::getSessionId,session).eq(AidRuntimeCommand::getClientRequestId,key).last("LIMIT 1 FOR UPDATE"));
    }


    /** New submissions keep output admission; accepted work and terminal receipts restore their frozen scope. */
    static boolean requiresOutputAdmission(boolean recovering, AidRuntimeRunStep child) {
        return !recovering || !(TERMINAL_STEP.contains(child.getStatus())
                || "ACCEPTED".equals(child.getProviderAcceptanceState())
                || "UNKNOWN".equals(child.getProviderAcceptanceState()));
    }

}
