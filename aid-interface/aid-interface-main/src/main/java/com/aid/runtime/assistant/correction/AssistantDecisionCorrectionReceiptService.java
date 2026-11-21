package com.aid.runtime.assistant.correction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.enums.BillingReservationState;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextToolResultSupport;
import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantLoopExecutor;
import com.aid.runtime.assistant.AssistantLoopExecutorRegistry;
import com.aid.runtime.assistant.DefaultAssistantLoopExecutor;
import com.aid.runtime.assistant.AssistantLoopModelResult;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.assistant.AssistantTrustedTextRequestSnapshot;
import com.aid.runtime.assistant.NativeAssistantLoopExecutor;
import com.aid.runtime.assistant.NativeAssistantBindingPolicy;
import com.aid.runtime.assistant.NativeDecisionValidationRejectedException;
import com.aid.runtime.assistant.NativeRejectedSourceIdentity;
import com.aid.runtime.assistant.execution.AssistantPlanDecisionRejectedException;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionCommand;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionService;
import com.aid.runtime.assistant.execution.AssistantRejectedDecisionEvidenceCommand;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the immutable run-level SLOT/BOUND receipts for one fresh decision correction. It never
 * calls a model or Provider. Every public method is invoked only from a server-owned runtime path.
 */
@Service
public class AssistantDecisionCorrectionReceiptService
{
    public static final String SOURCE_CLAIMED = "NATIVE_DECISION_CORRECTION_SLOT_CLAIMED";
    private static final String SLOT_SCHEMA = "assistant-native-decision-correction-slot/v1";
    private static final String BOUND_SCHEMA = "assistant-native-decision-correction-bound/v1";
    private static final String APPROVAL_SCHEMA = "assistant-native-decision-correction-approval/v1";
    private static final String MAIN_BIZ_TYPE = "ASSISTANT_RUNTIME_STEP";
    private static final String SHA256 = "^[0-9a-f]{64}$";
    private static final String NATIVE_MESSAGE_KEY = "textToolMessage";
    private static final Set<String> NATIVE_MESSAGE_FIELDS = Set.of(
            "role", "prefix", "toolCalls", "toolCallId", "responseId", "toolError");
    private static final Set<String> NATIVE_CALL_FIELDS = Set.of("id", "name", "arguments");
    private static final Set<String> TERMINAL_RESERVATION = Set.of(
            BillingReservationState.SETTLED.name(), BillingReservationState.REFUNDED.name(),
            BillingReservationState.RELEASED.name());
    private static final Set<String> TERMINAL_STEP = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "FAILED_PRETASK", "CANCELLED_PRETASK");
    private static final Set<String> TERMINAL_TASK = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED");
    private static final Set<String> TERMINAL_APPROVAL = Set.of("CONSUMED", "REJECTED", "EXPIRED", "CANCELLED");

    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidRuntimeEventMapper events;
    @Resource private com.aid.runtime.mapper.AidRuntimeReplanIntentMapper replanIntents;
    @Resource private com.aid.runtime.mapper.AidRuntimePlanMapper retirementPlans;
    @Resource private AidMediaTaskMapper mediaTasks;
    @Resource private AidBillingAuthorizationReservationMapper reservations;
    @Resource private IAccountUpdateService accounts;
    @Resource private com.aid.runtime.assistant.execution.AssistantTerminalChildEvidenceVerifier terminalChildEvidence;
    @Resource private AssistantPlanExecutionService planExecution;
    @Resource private AssistantModuleAgentResolver moduleAgents;
    @Resource private AssistantLoopExecutorRegistry executorRegistry;

    /** Claims the one run-level correction slot after the caller verified the persisted Native source. */
    @Transactional
    public ClaimResult claim(ClaimCommand command)
    {
        requireClaimCommand(command);
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        AidRuntimeRunStep main = requireStep(command.mainAgentStepId(), run.getId(), true);
        AidMediaTask task = requireTask(command.mediaTaskId(), true);
        AidRuntimeCommand existingSlot = findInternal(session.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);
        if (existingSlot != null)
        {
            SlotReceipt slot = requireSlot(existingSlot, session, run);
            AidRuntimeApproval approval = requireSourceApproval(run, main, Set.of("CONSUMED"));
            require(Objects.equals(main.getErrorMessage(), SOURCE_CLAIMED)
                            && "FAILED".equals(main.getStatus())
                            && "ACCEPTED".equals(main.getProviderAcceptanceState())
                            && Objects.equals(main.getMediaTaskId(), task.getId())
                            && "SUCCEEDED".equals(task.getStatus())
                            && Objects.equals(task.getUserId(), run.getUserId())
                            && Objects.equals(task.getBizTaskId(), main.getId())
                            && MAIN_BIZ_TYPE.equals(task.getBizTaskType())
                            && Objects.equals(task.getIdempotencyKey(), main.getIdempotencyKey())
                            && Objects.equals(command.sourceResultDigest(),
                            AssistantLoopModelResult.persistedTextDigest(task.getResultText()))
                            && command.observedRunRevision() == slot.observedRunRevision()
                            && command.observedPlanRevision() == slot.planRevision(),
                    "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
            String sourceDigest = sourceDigest(command, run, main, approval);
            require(Objects.equals(slot.sourceIdentityDigest(), sourceDigest)
                            && Objects.equals(slot.feedbackDigest(), command.feedback().digest()),
                    "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
            requireClaimedSource(session, run, slot);
            return new ClaimResult(slot.slotDigest(), slot.feedbackDigest(), slot.candidateIteration(), true);
        }
        requireActive(session, run, command.lease());
        AidRuntimeApproval approval = requireSourceApproval(run, main, Set.of("ACCEPTED"));
        JSONObject approvalScope = approvalScope(approval);
        requireSourceIdentity(command, session, run, main, task, approval, approvalScope);
        String sourceDigest = sourceDigest(command, run, main, approval);
        require(findInternal(session.getUserId(), session.getId(),
                        AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true) == null,
                "ASSISTANT_DECISION_CORRECTION_MARKER_CONFLICT");
        require(!sourceClaimExists(run.getId()), "ASSISTANT_DECISION_CORRECTION_SLOT_MISSING");
        require(planExecution.hasNoOperationEvidence(new AssistantRejectedDecisionEvidenceCommand(
                        run.getId(), main.getId(), command.lease())),
                "ASSISTANT_DECISION_CORRECTION_OPERATION_EXISTS");
        requireSourceSettlement(run, main, task);
        requireFullRunSafe(run, main.getId(), null);

        int candidateIteration = nextMainIteration(run.getId());
        JSONObject slotJson = slotJson(command, session, run, main, approval,
                sourceDigest, candidateIteration);
        String slotResultJson = slotJson.toJSONString();
        String slotDigest = SecureUtil.sha256(slotResultJson);
        slotJson.put("slotDigest", slotDigest);
        slotResultJson = slotJson.toJSONString();
        // slotDigest excludes itself to avoid a digest cycle; payloadDigest binds the complete receipt.
        AidRuntimeCommand slotCommand = insertInternal(session.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()),
                AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE,
                SecureUtil.sha256(slotResultJson), slotResultJson);

        Date now = new Date();
        main.setStatus("FAILED");
        main.setProviderAcceptanceState("ACCEPTED");
        main.setMediaTaskId(task.getId());
        main.setErrorMessage(SOURCE_CLAIMED);
        com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(main);
        touch(main, command.lease().getOwner(), now);
        require(steps.updateById(main) == 1, "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        consumeApproval(approval, command.lease().getOwner(), now);

        run.setStatus("WAITING_APPROVAL");
        run.setNextAction("CONTINUE_AGENT_REQUIRED");
        run.setNextRetryAt(null);
        run.setErrorMessage(null);
        run.setStateRevision(value(run.getStateRevision()) + 1);
        touch(run, command.lease().getOwner(), now);
        require(runs.updateById(run) == 1, "ASSISTANT_DECISION_CORRECTION_RUN_CHANGED");
        appendEvent(session, run, main, slotCommand, now, command.lease().getOwner());
        return new ClaimResult(slotDigest, command.feedback().digest(), candidateIteration, false);
    }

    /**
     * Rebuilds one Native rejection exclusively from locked durable source rows before claiming.
     * The first thrown identity is only an equality witness and never supplies claim authority.
     */
    @Transactional
    public ClaimResult claimNative(NativeClaimCommand command)
    {
        boolean mutationStarted = false;
        NativeClaimRejectionStage rejectionStage = NativeClaimRejectionStage.INPUT;
        try
        {
            requireNativeClaimCommand(command);
            rejectionStage = NativeClaimRejectionStage.LOCKED_AUTHORITY;
            AidRuntimeRun snapshot = requireRun(command.runId(), false);
            AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
            AidRuntimeRun run = requireRun(command.runId(), true);
            AidRuntimeRunStep main = requireStep(command.mainAgentStepId(), run.getId(), true);
            AidMediaTask task = requireTask(command.mediaTaskId(), true);
            AidRuntimeCommand existingSlot = findInternal(session.getUserId(), session.getId(),
                    AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);

            MediaTextGenerateRequest request;
            AidRuntimeApproval approval;
            SlotReceipt existingReceipt = null;
            long observedRunRevision;
            long observedPlanRevision;
            if (existingSlot == null)
            {
                requireActive(session, run, command.lease());
                approval = requireSourceApproval(run, main, Set.of("ACCEPTED"));
                NativeRejectedSourceIdentity observed = command.rejected().sourceIdentity();
                ClaimCommand preflight = claimCommand(run, main, task, observed,
                        command.rejected().feedback(), command.lease());
                requireClaimCommand(preflight);
                rejectionStage = NativeClaimRejectionStage.SOURCE_IDENTITY;
                request = requireSourceIdentity(preflight, session, run, main, task, approval,
                        approvalScope(approval));
                rejectionStage = NativeClaimRejectionStage.SIDE_EFFECT_PREFLIGHT;
                require(planExecution.hasNoOperationEvidence(new AssistantRejectedDecisionEvidenceCommand(
                                run.getId(), main.getId(), command.lease())),
                        "ASSISTANT_DECISION_CORRECTION_OPERATION_EXISTS");
                rejectionStage = NativeClaimRejectionStage.SETTLEMENT;
                requireSourceSettlement(run, main, task);
                rejectionStage = NativeClaimRejectionStage.FULL_GRAPH;
                requireFullRunSafe(run, main.getId(), null);
                observedRunRevision = observed.observedRunRevision();
                observedPlanRevision = observed.observedPlanRevision();
            }
            else
            {
                existingReceipt = requireSlot(existingSlot, session, run);
                require(Objects.equals(existingReceipt.sourceMainStepId(), command.mainAgentStepId())
                                && Objects.equals(existingReceipt.sourceMediaTaskId(), command.mediaTaskId()),
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                // Replay authority comes from the immutable SLOT. Current/latest run pointers may only
                // discover the same ids; they never replace the historical source recorded by SLOT.
                main = requireStep(existingReceipt.sourceMainStepId(), run.getId(), true);
                task = requireTask(existingReceipt.sourceMediaTaskId(), true);
                require("FAILED".equals(main.getStatus())
                                && SOURCE_CLAIMED.equals(main.getErrorMessage())
                                && "ACCEPTED".equals(main.getProviderAcceptanceState())
                                && "SUCCEEDED".equals(task.getStatus())
                                && Objects.equals(existingReceipt.sourceResultDigest(),
                                AssistantLoopModelResult.persistedTextDigest(task.getResultText())),
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                requireClaimedSource(session, run, existingReceipt);
                rejectionStage = NativeClaimRejectionStage.FULL_GRAPH;
                requireFullRunSafe(run, existingReceipt.sourceMainStepId(), null);
                rejectionStage = NativeClaimRejectionStage.SOURCE_IDENTITY;
                approval = requireSourceApproval(run, main, Set.of("CONSUMED"));
                request = requireApprovalIdentity(session, run, main, approval,
                        existingReceipt.observedRunRevision(), existingReceipt.acceptedMessageSeq(),
                        existingReceipt.agentVersionId(), existingReceipt.packageDigest(),
                        existingReceipt.contextDigest(), existingReceipt.planRevision());
                observedRunRevision = existingReceipt.observedRunRevision();
                observedPlanRevision = existingReceipt.planRevision();
            }

            rejectionStage = NativeClaimRejectionStage.LOCKED_REPARSE;
            NativeDecisionValidationRejectedException locked = lockedNativeRejection(
                    session, run, task, request, observedRunRevision, observedPlanRevision);
            rejectionStage = NativeClaimRejectionStage.EQUALITY;
            require(Objects.equals(command.rejected().feedback(), locked.feedback())
                            && Objects.equals(command.rejected().sourceIdentity(), locked.sourceIdentity()),
                    "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
            ClaimCommand authoritative = claimCommand(run, main, task, locked.sourceIdentity(),
                    locked.feedback(), command.lease());
            requireClaimCommand(authoritative);
            String sourceIdentityDigest = sourceDigest(authoritative, run, main, approval);
            if (existingReceipt != null)
            {
                require(Objects.equals(existingReceipt.sourceIdentityDigest(), sourceIdentityDigest)
                                && Objects.equals(existingReceipt.feedbackDigest(), locked.feedback().digest()),
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                return new ClaimResult(existingReceipt.slotDigest(), existingReceipt.feedbackDigest(),
                        existingReceipt.candidateIteration(), true);
            }

            rejectionStage = NativeClaimRejectionStage.MARKER_IDENTITY;
            require(findInternal(session.getUserId(), session.getId(),
                            AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true) == null,
                    "ASSISTANT_DECISION_CORRECTION_MARKER_CONFLICT");
            require(!sourceClaimExists(run.getId()), "ASSISTANT_DECISION_CORRECTION_SLOT_MISSING");
            int candidateIteration = nextMainIteration(run.getId());
            JSONObject slotJson = slotJson(authoritative, session, run, main, approval,
                    sourceIdentityDigest, candidateIteration);
            String slotResultJson = slotJson.toJSONString();
            String slotDigest = SecureUtil.sha256(slotResultJson);
            slotJson.put("slotDigest", slotDigest);
            slotResultJson = slotJson.toJSONString();
            // From this point onward, a failure is a transaction/write failure and must remain
            // recoverable. It must never be converted into an invalid-model terminal outcome.
            mutationStarted = true;
            AidRuntimeCommand slotCommand = insertInternal(session.getUserId(), session.getId(),
                    AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()),
                    AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE,
                    SecureUtil.sha256(slotResultJson), slotResultJson);

            Date now = new Date();
            main.setStatus("FAILED");
            main.setProviderAcceptanceState("ACCEPTED");
            main.setMediaTaskId(task.getId());
            main.setErrorMessage(SOURCE_CLAIMED);
            com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(main);
            touch(main, command.lease().getOwner(), now);
            require(steps.updateById(main) == 1, "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
            consumeApproval(approval, command.lease().getOwner(), now);
            run.setStatus("WAITING_APPROVAL");
            run.setNextAction("CONTINUE_AGENT_REQUIRED");
            run.setNextRetryAt(null);
            run.setErrorMessage(null);
            run.setStateRevision(value(run.getStateRevision()) + 1);
            touch(run, command.lease().getOwner(), now);
            require(runs.updateById(run) == 1, "ASSISTANT_DECISION_CORRECTION_RUN_CHANGED");
            appendEvent(session, run, main, slotCommand, now, command.lease().getOwner());
            return new ClaimResult(slotDigest, locked.feedback().digest(), candidateIteration, false);
        }
        catch (ServiceException failure)
        {
            if (!mutationStarted && isExplicitPreMutationRejection(failure))
                throw new NativeClaimSourceRejectedException(failure, rejectionStage);
            throw failure;
        }
    }

    public static boolean supportsParsedRejection(AssistantPlanDecisionRejectedException.Reason reason)
    {
        return reason == AssistantPlanDecisionRejectedException.Reason.PLAN_INPUT_BINDINGS_UNRESOLVED;
    }

    /**
     * Claims the same run-level correction slot for a decision that was parsed and persisted, but
     * whose first read-only operation preflight proved one code-owned plan defect. The rejected
     * decision, Native archive and quote snapshot are rebuilt from locked rows; the caught reason
     * is only an equality witness.
     */
    @Transactional
    public ParsedClaimResult claimParsed(ParsedClaimCommand command)
    {
        requireParsedClaimCommand(command);
        AidRuntimeRun snapshot = requireRun(command.runId(), false);
        AidAssistantSession session = requireSession(snapshot.getSessionId(), snapshot.getUserId(), true);
        AidRuntimeRun run = requireRun(command.runId(), true);
        AidRuntimeRunStep main = requireStep(command.mainAgentStepId(), run.getId(), true);
        AidMediaTask task = requireTask(command.mediaTaskId(), true);
        AidRuntimeCommand existingSlot = findInternal(session.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);
        AidRuntimeCommand existingBound = findInternal(session.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true);
        AssistantDecisionValidationFeedback expectedFeedback = parsedFeedback(command.reason());

        if (existingSlot != null)
        {
            SlotReceipt slot = requireSlot(existingSlot, session, run);
            if (Objects.equals(slot.sourceMainStepId(), main.getId()))
            {
                AidRuntimeApproval approval = requireSourceApproval(run, main, Set.of("CONSUMED"));
                require("FAILED".equals(main.getStatus()) && SOURCE_CLAIMED.equals(main.getErrorMessage())
                                && "ACCEPTED".equals(main.getProviderAcceptanceState())
                                && Objects.equals(main.getMediaTaskId(), task.getId())
                                && "SUCCEEDED".equals(task.getStatus()),
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                MediaTextGenerateRequest request = requireApprovalIdentity(session, run, main, approval,
                        slot.observedRunRevision(), slot.acceptedMessageSeq(), slot.agentVersionId(),
                        slot.packageDigest(), slot.contextDigest(), slot.planRevision());
                ClaimCommand replay = parsedClaimCommand(session, run, main, task, request,
                        slot.observedRunRevision(), slot.planRevision(), expectedFeedback, command.lease());
                requireClaimCommand(replay);
                require(Objects.equals(slot.sourceIdentityDigest(), sourceDigest(replay, run, main, approval))
                                && Objects.equals(slot.feedbackDigest(), expectedFeedback.digest()),
                        "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
                requireClaimedSource(session, run, slot);
                if (existingBound != null)
                {
                    BoundReceipt replayBound = requireBound(existingBound, session, run);
                    require(Objects.equals(replayBound.slotDigest(), slot.slotDigest())
                                    && Objects.equals(replayBound.feedbackDigest(), slot.feedbackDigest())
                                    && Objects.equals(replayBound.sourceMainStepId(), slot.sourceMainStepId()),
                            "ASSISTANT_DECISION_CORRECTION_BOUND_CHANGED");
                }
                return new ParsedClaimResult(ParsedClaimDisposition.SOURCE_REPLAY,
                        slot.slotDigest(), slot.feedbackDigest(), slot.candidateIteration());
            }
        }

        requireActive(session, run, command.lease());
        AidRuntimeApproval approval = requireSourceApproval(run, main, Set.of("ACCEPTED"));
        MediaTextGenerateRequest request;
        SlotReceipt slot = existingSlot == null ? null : requireSlot(existingSlot, session, run);
        BoundReceipt bound = existingBound == null ? null : requireBound(existingBound, session, run);
        if (slot == null)
        {
            require(bound == null && !sourceClaimExists(run.getId())
                            && !correctionApprovalExists(run.getId()),
                    "ASSISTANT_DECISION_CORRECTION_SLOT_MISSING");
            request = requireParsedSourceIdentity(session, run, main, task, approval,
                    value(run.getPlanRevision()), value(session.getLastMessageSeq()));
        }
        else
        {
            require(bound != null, "ASSISTANT_DECISION_CORRECTION_BOUND_MISSING");
            requireClaimedSource(session, run, slot);
            if (Objects.equals(bound.successorMainStepId(), main.getId()))
            {
                JSONObject correction = requireCorrectionApprovalIdentity(session, run, main, approval,
                        bound.acceptedRunRevision(), bound.acceptedMessageSeq(), slot.agentVersionId(),
                        slot.packageDigest(), bound.contextDigest(), bound.planRevision(), "ACCEPTED");
                require(Objects.equals(bound.slotDigest(), slot.slotDigest())
                                && Objects.equals(bound.feedbackDigest(), slot.feedbackDigest())
                                && Objects.equals(bound.successorApprovalId(), approval.getId())
                                && Objects.equals(bound.requestDigest(), approval.getRequestDigest())
                                && Objects.equals(string(correction, "slotDigest"), slot.slotDigest())
                                && Objects.equals(string(correction, "feedbackDigest"), slot.feedbackDigest())
                                && integer(correction, "candidateIteration") == slot.candidateIteration(),
                        "ASSISTANT_DECISION_CORRECTION_BOUND_CHANGED");
                request = restoreApprovedRequest(approval);
            }
            else
            {
                requireCompletedBound(session, run, slot, bound, main.getId());
                request = requireParsedSourceIdentity(session, run, main, task, approval,
                        value(run.getPlanRevision()), value(session.getLastMessageSeq()));
            }
        }

        AssistantDecisionValidationFeedback authoritativeFeedback =
                planExecution.requireRejectedDecisionFeedback(new AssistantPlanExecutionCommand(
                        run.getId(), main.getId(), main.getOutputJson(), command.lease()), command.reason());
        require(Objects.equals(authoritativeFeedback, expectedFeedback),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        ClaimCommand authoritative = parsedClaimCommand(session, run, main, task, request,
                longValue(approvalScope(approval), "acceptedRunRevision"),
                value(run.getPlanRevision()), authoritativeFeedback, command.lease());
        requireClaimCommand(authoritative);
        requireSourceSettlement(run, main, task);
        requireFullRunSafe(run, main.getId(), null);

        if (slot != null)
        {
            return new ParsedClaimResult(ParsedClaimDisposition.LIMIT_ALREADY_USED,
                    slot.slotDigest(), slot.feedbackDigest(), slot.candidateIteration());
        }

        String sourceIdentityDigest = sourceDigest(authoritative, run, main, approval);
        int candidateIteration = nextMainIteration(run.getId());
        JSONObject slotJson = slotJson(authoritative, session, run, main, approval,
                sourceIdentityDigest, candidateIteration);
        String slotResultJson = slotJson.toJSONString();
        String slotDigest = SecureUtil.sha256(slotResultJson);
        slotJson.put("slotDigest", slotDigest);
        slotResultJson = slotJson.toJSONString();
        AidRuntimeCommand slotCommand = insertInternal(session.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()),
                AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE,
                SecureUtil.sha256(slotResultJson), slotResultJson);

        Date now = new Date();
        main.setStatus("FAILED");
        main.setProviderAcceptanceState("ACCEPTED");
        main.setMediaTaskId(task.getId());
        main.setErrorMessage(SOURCE_CLAIMED);
        com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(main);
        touch(main, command.lease().getOwner(), now);
        require(steps.updateById(main) == 1, "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        consumeApproval(approval, command.lease().getOwner(), now);
        run.setStatus("WAITING_APPROVAL");
        run.setNextAction("CONTINUE_AGENT_REQUIRED");
        run.setNextRetryAt(null);
        run.setErrorMessage(null);
        run.setStateRevision(value(run.getStateRevision()) + 1);
        touch(run, command.lease().getOwner(), now);
        require(runs.updateById(run) == 1, "ASSISTANT_DECISION_CORRECTION_RUN_CHANGED");
        appendEvent(session, run, main, slotCommand, now, command.lease().getOwner());
        return new ParsedClaimResult(ParsedClaimDisposition.CLAIMED,
                slotDigest, authoritativeFeedback.digest(), candidateIteration);
    }

    /** Returns the sole correction candidate before Assembler/quote; an existing BOUND blocks new work. */
    @Transactional
    public AssistantDecisionCorrectionCandidate candidate(Long userId, AidAssistantSession session,
                                                          AidRuntimeRun run, int iteration)
    {
        require(userId != null && session != null && run != null && iteration > 0
                        && Objects.equals(userId, session.getUserId())
                        && Objects.equals(session.getId(), run.getSessionId()),
                "ASSISTANT_DECISION_CORRECTION_CONTEXT_INVALID");
        AidRuntimeCommand slotCommand = findInternal(userId, session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);
        AidRuntimeCommand boundCommand = findInternal(userId, session.getId(),
                AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true);
        if (slotCommand == null)
        {
            require(boundCommand == null && !sourceClaimExists(run.getId())
                            && !correctionApprovalExists(run.getId()),
                    "ASSISTANT_DECISION_CORRECTION_SLOT_MISSING");
            return null;
        }
        SlotReceipt slot = requireSlot(slotCommand, session, run);
        if (boundCommand != null)
        {
            BoundReceipt bound = requireBound(boundCommand, session, run);
            requireCompletedBound(session, run, slot, bound);
            return null;
        }
        require(!correctionApprovalExists(run.getId()),
                "ASSISTANT_DECISION_CORRECTION_BOUND_MISSING");
        require("WAITING_APPROVAL".equals(run.getStatus())
                        && "CONTINUE_AGENT_REQUIRED".equals(run.getNextAction())
                        && Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(slot.planRevision(), value(run.getPlanRevision()))
                        && Objects.equals(slot.contextDigest(), run.getContextDigest())
                        && Objects.equals(slot.agentVersionId(), run.getVersionId())
                        && Objects.equals(slot.packageDigest(), run.getVersionDigest())
                        && Objects.equals(slot.acceptedMessageSeq(), value(session.getLastMessageSeq()))
                        && slot.candidateIteration() == iteration,
                "ASSISTANT_DECISION_CORRECTION_CONTEXT_CHANGED");
        ClaimedSource diagnosticSource = requireClaimedSource(session, run, slot);
        requireFullRunSafe(run, slot.sourceMainStepId(), null);
        AssistantDecisionValidationFeedback feedback =
                AssistantDecisionValidationFeedback.parse(slot.feedbackCanonicalJson());
        String diagnostics = schemaDiagnostics(slot, feedback, diagnosticSource);
        return new AssistantDecisionCorrectionCandidate(slot.slotDigest(), slot.feedbackDigest(),
                slot.candidateIteration(), slot.feedbackCanonicalJson(), diagnostics);
    }

    private String schemaDiagnostics(SlotReceipt slot, AssistantDecisionValidationFeedback feedback,
                                     ClaimedSource source)
    {
        if (!Set.of(AssistantDecisionValidationFeedback.Reason.NATIVE_CONTROL_SCHEMA_MISMATCH,
                AssistantDecisionValidationFeedback.Reason.ARGUMENT_SCHEMA_MISMATCH).contains(feedback.reason()))
            return null;
        try
        {
            return AssistantSchemaCorrectionDiagnostics.fromVerifiedSource(
                    slot.slotDigest(), feedback, source.request(), TextToolResultSupport.readNative(source.task()),
                    source.task().getResponseJson(), slot.sourceToolMessageDigest(), slot.sourceBindingsDigest());
        }
        catch (RuntimeException unsupportedArchive)
        {
            // Source identity and settlement were already checked outside this optional projection.
            // Old/unrecognized provider archives retain their original immutable feedback.
            return null;
        }
    }

    /** Inserts the immutable BOUND after normal MAIN/quote/approval persistence in the same transaction. */
    @Transactional
    public void bind(Long userId, AidAssistantSession session, AidRuntimeRun run,
                     AssistantDecisionCorrectionCandidate candidate,
                     AidRuntimeRunStep successor, AidRuntimeApproval approval)
    {
        if (candidate == null) return;
        AidRuntimeCommand slotCommand = findInternal(userId, session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);
        SlotReceipt slot = requireSlot(slotCommand, session, run);
        require(Objects.equals(slot.slotDigest(), candidate.slotDigest())
                        && Objects.equals(slot.feedbackDigest(), candidate.feedbackDigest())
                        && slot.candidateIteration() == candidate.candidateIteration(),
                "ASSISTANT_DECISION_CORRECTION_SLOT_CHANGED");
        require(findInternal(userId, session.getId(),
                        AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true) == null,
                "ASSISTANT_DECISION_CORRECTION_ALREADY_BOUND");
        require(successor != null && approval != null && successor.getId() != null && approval.getId() != null,
                "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        JSONObject correction = requireCorrectionApprovalIdentity(session, run, successor, approval,
                value(run.getStateRevision()) + 1, value(session.getLastMessageSeq()),
                run.getVersionId(), run.getVersionDigest(), run.getContextDigest(),
                value(run.getPlanRevision()), "QUOTED");
        require(Objects.equals(userId, run.getUserId())
                        && Objects.equals(userId, session.getUserId())
                        && Objects.equals(session.getId(), run.getSessionId())
                        && Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(slot.contextDigest(), run.getContextDigest())
                        && slot.planRevision() == value(run.getPlanRevision())
                        && slot.acceptedMessageSeq() == value(session.getLastMessageSeq())
                        && Objects.equals(successor.getRunId(), run.getId())
                        && Objects.equals(successor.getApprovalId(), approval.getId())
                        && successor.getPlanPosition() != null
                        && successor.getPlanPosition() == candidate.candidateIteration()
                        && Objects.equals(successor.getInputDigest(), approval.getRequestDigest())
                        && Objects.equals(string(correction, "slotDigest"), candidate.slotDigest())
                        && Objects.equals(string(correction, "feedbackDigest"), candidate.feedbackDigest())
                        && integer(correction, "candidateIteration") == candidate.candidateIteration(),
                "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        requireClaimedSource(session, run, slot);
        requireFullRunSafe(run, slot.sourceMainStepId(), successor.getId());
        JSONObject result = new JSONObject();
        result.put("schemaVersion", BOUND_SCHEMA);
        result.put("runId", id(run.getId()));
        result.put("sessionId", id(session.getId()));
        result.put("slotDigest", candidate.slotDigest());
        result.put("feedbackDigest", candidate.feedbackDigest());
        result.put("candidateIteration", candidate.candidateIteration());
        result.put("sourceMainStepId", id(slot.sourceMainStepId()));
        result.put("successorMainStepId", id(successor.getId()));
        result.put("successorApprovalId", id(approval.getId()));
        result.put("requestDigest", approval.getRequestDigest());
        result.put("contextDigest", run.getContextDigest());
        result.put("planRevision", value(run.getPlanRevision()));
        result.put("acceptedMessageSeq", value(session.getLastMessageSeq()));
        result.put("acceptedRunRevision", longValue(approvalScope(approval), "acceptedRunRevision"));
        String json = result.toJSONString();
        insertInternal(userId, session.getId(),
                AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()),
                AssistantDecisionCorrectionCommandNamespace.BOUND_TYPE,
                SecureUtil.sha256(json), json);
    }

    /** Provider-entry guard: correction approval snapshots require their exact immutable BOUND. */
    @Transactional
    public void requireBoundForSubmission(AidAssistantSession session, AidRuntimeRun run,
                                          AidRuntimeRunStep successor, AidRuntimeApproval approval,
                                          MediaTextGenerateRequest request)
    {
        JSONObject scope = approvalScope(approval);
        if (scope.get("decisionCorrection") == null) return;
        JSONObject correction = requireCorrectionApprovalIdentity(session, run, successor, approval,
                value(run.getStateRevision()), value(session.getLastMessageSeq()),
                run.getVersionId(), run.getVersionDigest(), run.getContextDigest(),
                value(run.getPlanRevision()), "ACCEPTED");
        AidRuntimeCommand slotCommand = findInternal(run.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()), true);
        AidRuntimeCommand boundCommand = findInternal(run.getUserId(), session.getId(),
                AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()), true);
        SlotReceipt slot = requireSlot(slotCommand, session, run);
        BoundReceipt bound = requireBound(boundCommand, session, run);
        require(Objects.equals(bound.slotDigest(), slot.slotDigest())
                        && Objects.equals(bound.feedbackDigest(), slot.feedbackDigest())
                        && bound.candidateIteration() == slot.candidateIteration()
                        && Objects.equals(bound.sourceMainStepId(), slot.sourceMainStepId())
                        && Objects.equals(string(correction, "slotDigest"), slot.slotDigest())
                        && Objects.equals(string(correction, "feedbackDigest"), slot.feedbackDigest())
                        && integer(correction, "candidateIteration") == slot.candidateIteration()
                        && Objects.equals(bound.successorMainStepId(), successor.getId())
                        && Objects.equals(bound.successorApprovalId(), approval.getId())
                        && Objects.equals(bound.requestDigest(), approval.getRequestDigest())
                        && Objects.equals(bound.requestDigest(), successor.getInputDigest())
                        && request != null && Objects.equals(request.getBizTaskId(), successor.getId())
                        && Objects.equals(request.getUserId(), run.getUserId())
                        && Objects.equals(bound.contextDigest(), run.getContextDigest())
                        && Objects.equals(bound.planRevision(), value(run.getPlanRevision()))
                        && bound.acceptedRunRevision() == value(run.getStateRevision())
                        && Objects.equals(bound.acceptedMessageSeq(), value(session.getLastMessageSeq())),
                "ASSISTANT_DECISION_CORRECTION_BOUND_CHANGED");
        requireClaimedSource(session, run, slot);
        requireFullRunSafe(run, slot.sourceMainStepId(), successor.getId());
    }

    public JSONObject approvalMetadata(AssistantDecisionCorrectionCandidate candidate)
    {
        if (candidate == null) return null;
        JSONObject value = new JSONObject();
        value.put("schemaVersion", APPROVAL_SCHEMA);
        value.put("slotDigest", candidate.slotDigest());
        value.put("feedbackDigest", candidate.feedbackDigest());
        value.put("candidateIteration", candidate.candidateIteration());
        return value;
    }

    private JSONObject slotJson(ClaimCommand command, AidAssistantSession session, AidRuntimeRun run,
                                AidRuntimeRunStep main, AidRuntimeApproval approval,
                                String sourceDigest, int candidateIteration)
    {
        JSONObject value = new JSONObject();
        value.put("schemaVersion", SLOT_SCHEMA);
        value.put("runId", id(run.getId()));
        value.put("sessionId", id(session.getId()));
        value.put("sourceMainStepId", id(main.getId()));
        value.put("sourceMediaTaskId", id(command.mediaTaskId()));
        value.put("sourceRequestDigest", approval.getRequestDigest());
        value.put("sourceResultDigest", command.sourceResultDigest());
        value.put("sourceToolMessageDigest", command.toolMessageDigest());
        value.put("sourceBindingsDigest", command.nativeBindingsDigest());
        value.put("sourceAliasDigest", command.trustedAliasDigest());
        value.put("sourceIdentityDigest", sourceDigest);
        value.put("feedbackDigest", command.feedback().digest());
        value.put("feedbackCanonicalJson", command.feedback().canonicalJson());
        value.put("candidateIteration", candidateIteration);
        value.put("observedRunRevision", command.observedRunRevision());
        value.put("planRevision", value(run.getPlanRevision()));
        value.put("contextDigest", run.getContextDigest());
        value.put("agentVersionId", id(run.getVersionId()));
        value.put("packageDigest", run.getVersionDigest());
        value.put("acceptedMessageSeq", longValue(approvalScope(approval), "acceptedMessageSeq"));
        return value;
    }

    private SlotReceipt requireSlot(AidRuntimeCommand command, AidAssistantSession session, AidRuntimeRun run)
    {
        require(command != null && AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE.equals(command.getCommandType())
                        && "COMPLETED".equals(command.getStatus()) && StrUtil.isNotBlank(command.getResultJson())
                        && Objects.equals(command.getActorId(), run.getUserId())
                        && Objects.equals(command.getSessionId(), session.getId())
                        && Objects.equals(command.getClientRequestId(),
                        AssistantDecisionCorrectionCommandNamespace.slotKey(run.getId()))
                        && Objects.equals(command.getPayloadDigest(), SecureUtil.sha256(command.getResultJson())),
                "ASSISTANT_DECISION_CORRECTION_SLOT_INVALID");
        JSONObject value = object(command.getResultJson(), "ASSISTANT_DECISION_CORRECTION_SLOT_INVALID");
        Set<String> fields = Set.of("schemaVersion", "runId", "sessionId", "sourceMainStepId",
                "sourceMediaTaskId", "sourceRequestDigest", "sourceResultDigest", "sourceToolMessageDigest",
                "sourceBindingsDigest", "sourceAliasDigest", "sourceIdentityDigest", "feedbackDigest",
                "feedbackCanonicalJson", "candidateIteration", "observedRunRevision", "planRevision",
                "contextDigest", "agentVersionId", "packageDigest", "acceptedMessageSeq", "slotDigest");
        require(fields.equals(value.keySet()) && SLOT_SCHEMA.equals(string(value, "schemaVersion")),
                "ASSISTANT_DECISION_CORRECTION_SLOT_INVALID");
        AssistantDecisionValidationFeedback feedback = AssistantDecisionValidationFeedback.parse(
                string(value, "feedbackCanonicalJson"));
        digest(value, "sourceRequestDigest");
        digest(value, "sourceResultDigest");
        digest(value, "sourceToolMessageDigest");
        digest(value, "sourceBindingsDigest");
        digest(value, "sourceAliasDigest");
        SlotReceipt result = new SlotReceipt(id(value, "runId"), id(value, "sessionId"),
                id(value, "sourceMainStepId"), id(value, "sourceMediaTaskId"),
                digest(value, "sourceRequestDigest"), digest(value, "sourceResultDigest"),
                digest(value, "sourceToolMessageDigest"), digest(value, "sourceBindingsDigest"),
                digest(value, "sourceAliasDigest"),
                digest(value, "sourceIdentityDigest"), digest(value, "feedbackDigest"),
                string(value, "feedbackCanonicalJson"), integer(value, "candidateIteration"),
                longValue(value, "observedRunRevision"), longValue(value, "planRevision"),
                digest(value, "contextDigest"), id(value, "agentVersionId"),
                digest(value, "packageDigest"), longValue(value, "acceptedMessageSeq"),
                digest(value, "slotDigest"));
        require(Objects.equals(result.runId(), run.getId()) && Objects.equals(result.sessionId(), session.getId())
                        && Objects.equals(result.feedbackDigest(), feedback.digest()),
                "ASSISTANT_DECISION_CORRECTION_SLOT_INVALID");
        JSONObject withoutDigest = new JSONObject(value);
        withoutDigest.remove("slotDigest");
        require(Objects.equals(result.slotDigest(), SecureUtil.sha256(withoutDigest.toJSONString())),
                "ASSISTANT_DECISION_CORRECTION_SLOT_INVALID");
        return result;
    }

    private BoundReceipt requireBound(AidRuntimeCommand command, AidAssistantSession session, AidRuntimeRun run)
    {
        require(command != null && AssistantDecisionCorrectionCommandNamespace.BOUND_TYPE.equals(command.getCommandType())
                        && "COMPLETED".equals(command.getStatus()) && StrUtil.isNotBlank(command.getResultJson())
                        && Objects.equals(command.getActorId(), run.getUserId())
                        && Objects.equals(command.getSessionId(), session.getId())
                        && Objects.equals(command.getClientRequestId(),
                        AssistantDecisionCorrectionCommandNamespace.boundKey(run.getId()))
                        && Objects.equals(command.getPayloadDigest(), SecureUtil.sha256(command.getResultJson())),
                "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        JSONObject value = object(command.getResultJson(), "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        Set<String> fields = Set.of("schemaVersion", "runId", "sessionId", "slotDigest", "feedbackDigest",
                "candidateIteration", "sourceMainStepId", "successorMainStepId", "successorApprovalId",
                "requestDigest", "contextDigest", "planRevision", "acceptedMessageSeq",
                "acceptedRunRevision");
        require(fields.equals(value.keySet()) && BOUND_SCHEMA.equals(string(value, "schemaVersion")),
                "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        BoundReceipt result = new BoundReceipt(id(value, "runId"), id(value, "sessionId"),
                digest(value, "slotDigest"), digest(value, "feedbackDigest"),
                integer(value, "candidateIteration"), id(value, "sourceMainStepId"),
                id(value, "successorMainStepId"), id(value, "successorApprovalId"),
                digest(value, "requestDigest"), digest(value, "contextDigest"),
                longValue(value, "planRevision"), longValue(value, "acceptedMessageSeq"),
                longValue(value, "acceptedRunRevision"));
        require(Objects.equals(result.runId(), run.getId()) && Objects.equals(result.sessionId(), session.getId()),
                "ASSISTANT_DECISION_CORRECTION_BOUND_INVALID");
        return result;
    }

    /**
     * Proves that the one BOUND correction successor finished under its immutable quote identity.
     * The current run plan/context may have advanced after a valid PLAN decision, so historical
     * approval identity is checked against SLOT/BOUND instead of the current mutable run snapshot.
     */
    private void requireCompletedBound(AidAssistantSession session, AidRuntimeRun run,
                                       SlotReceipt slot, BoundReceipt bound)
    {
        requireCompletedBound(session, run, slot, bound, null);
    }

    private void requireCompletedBound(AidAssistantSession session, AidRuntimeRun run,
                                       SlotReceipt slot, BoundReceipt bound,
                                       Long ignoredCurrentMainId)
    {
        require(session != null && run != null && slot != null && bound != null
                        && Objects.equals(session.getId(), run.getSessionId())
                        && Objects.equals(session.getUserId(), run.getUserId())
                        && Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && !Boolean.TRUE.equals(run.getStopRequested())
                        && Objects.equals(bound.runId(), run.getId())
                        && Objects.equals(bound.sessionId(), session.getId())
                        && Objects.equals(bound.slotDigest(), slot.slotDigest())
                        && Objects.equals(bound.feedbackDigest(), slot.feedbackDigest())
                        && bound.candidateIteration() == slot.candidateIteration()
                        && Objects.equals(bound.sourceMainStepId(), slot.sourceMainStepId()),
                "ASSISTANT_DECISION_CORRECTION_BOUND_CHANGED");

        AidRuntimeRunStep successor = requireStep(bound.successorMainStepId(), run.getId(), true);
        AidRuntimeApproval approval = approvals.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, bound.successorApprovalId())
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .eq(AidRuntimeApproval::getUserId, run.getUserId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        boolean retired = "REPLAN_SUPERSEDED_DECISION".equals(successor.getErrorMessage());
        if (retired) requireRetiredDecision(run, successor, approval);
        JSONObject correction = requireCorrectionApprovalIdentity(session, run, successor, approval,
                bound.acceptedRunRevision(), bound.acceptedMessageSeq(),
                slot.agentVersionId(), slot.packageDigest(), bound.contextDigest(),
                bound.planRevision(), retired ? approval.getStatus() : "CONSUMED");
        require(("SUCCEEDED".equals(successor.getStatus()) || retired && TERMINAL_TASK.contains(successor.getStatus()))
                        && "ACCEPTED".equals(successor.getProviderAcceptanceState())
                        && "MAIN_AGENT".equals(successor.getStepKind())
                        && "ASSISTANT_DECISION".equals(successor.getOperationKind())
                        && Objects.equals(successor.getPlanRevision(), bound.planRevision())
                        && Objects.equals(successor.getPlanPosition(), bound.candidateIteration())
                        && Objects.equals(successor.getAttemptNo(), 1)
                        && Objects.equals(successor.getStableStepId(),
                        "assistant-loop/iteration/" + bound.candidateIteration())
                        && Objects.equals(successor.getIdempotencyKey(),
                        "assistant:" + run.getId() + ":iteration:" + bound.candidateIteration())
                        && Objects.equals(successor.getApprovalId(), approval.getId())
                        && Objects.equals(successor.getInputDigest(), bound.requestDigest())
                        && Objects.equals(bound.requestDigest(), approval.getRequestDigest())
                        && Objects.equals(string(correction, "slotDigest"), slot.slotDigest())
                        && Objects.equals(string(correction, "feedbackDigest"), slot.feedbackDigest())
                        && integer(correction, "candidateIteration") == slot.candidateIteration()
                        && successor.getMediaTaskId() != null && successor.getReservationId() != null,
                "ASSISTANT_DECISION_CORRECTION_SUCCESSOR_INVALID");

        AidMediaTask task = requireTask(successor.getMediaTaskId(), true);
        require(("SUCCEEDED".equals(task.getStatus()) || retired && TERMINAL_TASK.contains(task.getStatus()))
                        && Objects.equals(task.getUserId(), run.getUserId())
                        && Objects.equals(task.getBizTaskId(), successor.getId())
                        && MAIN_BIZ_TYPE.equals(task.getBizTaskType())
                        && Objects.equals(task.getIdempotencyKey(), successor.getIdempotencyKey()),
                "ASSISTANT_DECISION_CORRECTION_SUCCESSOR_INVALID");
        requireClaimedSource(session, run, slot);
        if (!retired) requireSourceSettlement(run, successor, task);
        // Include the successor in the full graph proof; only the already-proved source is excluded.
        requireFullRunSafe(run, slot.sourceMainStepId(), ignoredCurrentMainId,
                retired ? Set.of(approval.getId()) : Set.of());
    }

    private MediaTextGenerateRequest requireParsedSourceIdentity(
            AidAssistantSession session, AidRuntimeRun run, AidRuntimeRunStep main,
            AidMediaTask task, AidRuntimeApproval approval, long planRevision,
            long acceptedMessageSeq)
    {
        JSONObject scope = approvalScope(approval);
        long acceptedRunRevision = longValue(scope, "acceptedRunRevision");
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(session.getId(), run.getSessionId())
                        && Objects.equals(session.getUserId(), run.getUserId())
                        && !Boolean.TRUE.equals(run.getStopRequested())
                        && List.of("RUNNING", "RECONCILING").contains(run.getStatus())
                        && Objects.equals(main.getMediaTaskId(), task.getId())
                        && "MAIN_AGENT".equals(main.getStepKind())
                        && "ASSISTANT_DECISION".equals(main.getOperationKind())
                        && "DECISION_READY".equals(main.getStatus())
                        && "ACCEPTED".equals(main.getProviderAcceptanceState())
                        && Objects.equals(main.getPlanRevision(), planRevision)
                        && acceptedMessageSeq == value(session.getLastMessageSeq())
                        && "SUCCEEDED".equals(task.getStatus())
                        && Objects.equals(task.getUserId(), run.getUserId())
                        && Objects.equals(task.getBizTaskId(), main.getId())
                        && MAIN_BIZ_TYPE.equals(task.getBizTaskType())
                        && Objects.equals(task.getIdempotencyKey(), main.getIdempotencyKey())
                        && StrUtil.isNotBlank(main.getOutputJson()),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        MediaTextGenerateRequest request = requireApprovalIdentity(session, run, main, approval,
                acceptedRunRevision, acceptedMessageSeq, run.getVersionId(), run.getVersionDigest(),
                run.getContextDigest(), planRevision);
        require(request != null && Objects.equals(request.getBizTaskId(), main.getId())
                        && MAIN_BIZ_TYPE.equals(request.getBizTaskType())
                        && Objects.equals(request.getUserId(), run.getUserId())
                        && Objects.equals(request.getCallId(), main.getIdempotencyKey())
                        && Objects.equals(request.getCallIdentity(),
                        "assistant-loop:" + run.getId() + ":" + main.getStableStepId())
                        && StrUtil.isNotBlank(request.getInvocationIdentity())
                        && request.isStrictModelSelection()
                        && StrUtil.isNotBlank(request.getAssistantNativeToolBindingsJson()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        return request;
    }

    private ClaimCommand parsedClaimCommand(AidAssistantSession session, AidRuntimeRun run, AidRuntimeRunStep main,
                                             AidMediaTask task, MediaTextGenerateRequest request,
                                             long observedRunRevision, long observedPlanRevision,
                                             AssistantDecisionValidationFeedback feedback,
                                             RuntimeLease lease)
    {
        JSONObject decision = object(main.getOutputJson(),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        String protocolVersion = string(decision, "protocolVersion");
        String decisionId = string(decision, "decisionId");
        String action = string(decision, "action");
        require("TOOL".equals(action)
                        && decisionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                        && longValue(decision, "observedRunRevision") == observedRunRevision
                        && longValue(decision, "observedPlanRevision") == observedPlanRevision
                        && task.getLiveTextTurn() == null && StrUtil.isNotBlank(task.getResponseJson())
                        && request != null && StrUtil.isNotBlank(request.getAssistantNativeToolBindingsJson()),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        MediaTextGenerateRequest.TextMessageItem message;
        try
        {
            message = TextToolResultSupport.readNative(task);
        }
        catch (RuntimeException invalid)
        {
            throw fail("ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        }
        require(message != null && "assistant".equals(message.getRole())
                        && message.getToolCalls() != null && message.getToolCalls().size() == 1,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        MediaTextGenerateRequest.TextToolCall call = message.getToolCalls().get(0);
        require(call != null && StrUtil.isNotBlank(call.getId()) && StrUtil.isNotBlank(call.getName())
                        && call.getArguments() != null,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");

        JSONObject bindings = object(request.getAssistantNativeToolBindingsJson(),
                "ASSISTANT_DECISION_CORRECTION_BINDINGS_INVALID");
        AssistantLoopBinding pinned = moduleAgents.requirePinned(session.getModuleCode(),
                run.getVersionId(), run.getVersionDigest());
        NativeAssistantBindingPolicy.requireArchivedCompatible(pinned, request.getAssistantNativeToolBindingsJson());
        JSONObject selectedBinding = requireParsedToolBinding(bindings, protocolVersion,
                id(run.getId()), observedRunRevision, observedPlanRevision, call.getName());
        JSONObject rawArguments;
        try { rawArguments = JSON.parseObject(call.getArguments()); }
        catch (RuntimeException invalid) { throw fail("ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID"); }
        JSONObject decisionTool = decision.getJSONObject("tool");
        JSONObject expectedTool = new JSONObject(selectedBinding.getJSONObject("payloadTemplate"));
        expectedTool.put("arguments", rawArguments);
        require(rawArguments != null && decisionTool != null
                        && Objects.equals(canonical(expectedTool), canonical(decisionTool)),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        String decisionDigest = SecureUtil.sha256(main.getOutputJson());
        String responseDigest = SecureUtil.sha256(
                "assistant-parsed-native-response/v1\n" + task.getResponseJson());
        String bindingsDigest = SecureUtil.sha256(
                "assistant-parsed-native-bindings/v1\n" + request.getAssistantNativeToolBindingsJson());
        String aliasDigest = SecureUtil.sha256("assistant-parsed-native-alias/v1\n"
                + protocolVersion + "\n" + action + "\n" + decisionId + "\n"
                + call.getId() + "\n" + call.getName() + "\n"
                + canonical(selectedBinding) + "\n" + decisionDigest);
        return new ClaimCommand(run.getId(), main.getId(), task.getId(), observedRunRevision,
                observedPlanRevision, AssistantLoopModelResult.persistedTextDigest(task.getResultText()),
                responseDigest, bindingsDigest, aliasDigest, feedback, lease);
    }

    static JSONObject requireParsedToolBinding(JSONObject bindings, String protocolVersion,
                                               String runId, long observedRunRevision,
                                               long observedPlanRevision, String callName)
    {
        String code = "ASSISTANT_DECISION_CORRECTION_BINDINGS_INVALID";
        Object rawSchemaVersion = bindings == null ? null : bindings.get("schemaVersion");
        String schemaVersion = rawSchemaVersion instanceof String ? (String) rawSchemaVersion : null;
        if (bindings == null || !Set.of("schemaVersion", "protocolVersion", "runId",
                        "observedRunRevision", "observedPlanRevision", "tools")
                        .equals(bindings.keySet())
                || !("assistant-native-bindings/v2".equals(schemaVersion)
                || "assistant-native-bindings/v3".equals(schemaVersion)
                || NativeAssistantBindingPolicy.strictInputs(schemaVersion))
                || !(bindings.get("protocolVersion") instanceof String persistedProtocol)
                || !Objects.equals(protocolVersion, persistedProtocol)
                || !(bindings.get("runId") instanceof String persistedRunId)
                || !Objects.equals(runId, persistedRunId)
                || !exactRevision(bindings.get("observedRunRevision"), observedRunRevision)
                || !exactRevision(bindings.get("observedPlanRevision"), observedPlanRevision)
                || !(bindings.get("tools") instanceof com.alibaba.fastjson2.JSONArray tools)
                || tools.isEmpty() || tools.size() > 64)
            throw new ServiceException(code);
        JSONObject selected = null;
        for (Object raw : tools)
        {
            if (!(raw instanceof JSONObject candidate)) throw new ServiceException(code);
            boolean strictAsk = NativeAssistantBindingPolicy.strictInputs(schemaVersion)
                    && "ASK".equals(candidate.get("action"));
            Set<String> fields = strictAsk ? Set.of("name", "action", "payloadTemplate", "controlInputSchemaDigest")
                    : Set.of("name", "action", "payloadTemplate");
            if (!fields.equals(candidate.keySet()) || strictAsk
                    && !NativeAssistantBindingPolicy.askInputSchemaDigest(schemaVersion).equals(candidate.get("controlInputSchemaDigest")))
                throw new ServiceException(code);
            if (!Objects.equals(callName, candidate.getString("name"))) continue;
            if (selected != null) throw new ServiceException(code);
            selected = candidate;
        }
        if (selected == null || !"TOOL".equals(selected.getString("action"))
                || !(selected.get("payloadTemplate") instanceof JSONObject))
            throw new ServiceException(code);
        return selected;
    }

    private static boolean exactRevision(Object value, long expected)
    {
        return (value instanceof Long || value instanceof Integer)
                && ((Number) value).longValue() >= 0
                && ((Number) value).longValue() == expected;
    }

    private MediaTextGenerateRequest restoreApprovedRequest(AidRuntimeApproval approval)
    {
        JSONObject scope = approvalScope(approval);
        require(scope.get("normalizedRequest") instanceof JSONObject,
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        try
        {
            return AssistantTrustedTextRequestSnapshot.restore(
                    (JSONObject) scope.get("normalizedRequest"), approval.getRequestDigest());
        }
        catch (RuntimeException invalid)
        {
            throw fail("ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        }
    }

    private AssistantDecisionValidationFeedback parsedFeedback(
            AssistantPlanDecisionRejectedException.Reason reason)
    {
        require(supportsParsedRejection(reason),
                "ASSISTANT_DECISION_CORRECTION_REASON_UNSUPPORTED");
        return AssistantDecisionValidationFeedback.planInputsNotReady(
                DefaultAssistantLoopExecutor.validationContractDigest());
    }

    private void requireParsedClaimCommand(ParsedClaimCommand command)
    {
        require(command != null && command.runId() != null && command.runId() > 0
                        && command.mainAgentStepId() != null && command.mainAgentStepId() > 0
                        && command.mediaTaskId() != null && command.mediaTaskId() > 0
                        && supportsParsedRejection(command.reason()) && command.lease() != null,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
    }

    private String canonical(JSONObject value)
    {
        return JSON.toJSONString(value, JSONWriter.Feature.MapSortField,
                JSONWriter.Feature.WriteMapNullValue);
    }

    private MediaTextGenerateRequest requireSourceIdentity(ClaimCommand command,
                                                            AidAssistantSession session,
                                                            AidRuntimeRun run,
                                                            AidRuntimeRunStep main,
                                                            AidMediaTask task,
                                                            AidRuntimeApproval approval,
                                                            JSONObject scope)
    {
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(session.getId(), run.getSessionId())
                        && Objects.equals(session.getUserId(), run.getUserId())
                        && !Boolean.TRUE.equals(run.getStopRequested())
                        && List.of("RUNNING", "RECONCILING").contains(run.getStatus())
                        && Objects.equals(main.getMediaTaskId(), task.getId())
                        && "MAIN_AGENT".equals(main.getStepKind())
                        && "ASSISTANT_DECISION".equals(main.getOperationKind())
                        && List.of("INFLIGHT", "RECONCILING").contains(main.getStatus())
                        && "ACCEPTED".equals(main.getProviderAcceptanceState())
                        && Objects.equals(main.getPlanRevision(), value(run.getPlanRevision()))
                        && Objects.equals(command.observedPlanRevision(), value(run.getPlanRevision()))
                        && command.observedRunRevision() == longValue(scope, "acceptedRunRevision")
                        && longValue(scope, "acceptedMessageSeq") == value(session.getLastMessageSeq())
                        && "SUCCEEDED".equals(task.getStatus())
                        && Objects.equals(task.getUserId(), run.getUserId())
                        && Objects.equals(task.getBizTaskId(), main.getId())
                        && MAIN_BIZ_TYPE.equals(task.getBizTaskType())
                        && Objects.equals(task.getIdempotencyKey(), main.getIdempotencyKey())
                        && Objects.equals(command.sourceResultDigest(),
                        AssistantLoopModelResult.persistedTextDigest(task.getResultText())),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        MediaTextGenerateRequest request = requireApprovalIdentity(session, run, main, approval,
                command.observedRunRevision(), value(session.getLastMessageSeq()),
                run.getVersionId(), run.getVersionDigest(), run.getContextDigest(),
                value(run.getPlanRevision()));
        require(request != null && Objects.equals(request.getBizTaskId(), main.getId())
                        && MAIN_BIZ_TYPE.equals(request.getBizTaskType())
                        && Objects.equals(request.getUserId(), run.getUserId())
                        && Objects.equals(request.getCallId(), main.getIdempotencyKey())
                        && Objects.equals(request.getCallIdentity(),
                        "assistant-loop:" + run.getId() + ":" + main.getStableStepId())
                        && StrUtil.isNotBlank(request.getInvocationIdentity()) && request.isStrictModelSelection(),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        return request;
    }

    private ClaimCommand claimCommand(AidRuntimeRun run, AidRuntimeRunStep main,
                                      AidMediaTask task, NativeRejectedSourceIdentity source,
                                      AssistantDecisionValidationFeedback feedback, RuntimeLease lease)
    {
        return new ClaimCommand(run.getId(), main.getId(), task.getId(),
                source.observedRunRevision(), source.observedPlanRevision(),
                AssistantLoopModelResult.persistedTextDigest(task.getResultText()),
                source.toolMessageDigest(), source.nativeBindingsDigest(),
                source.trustedAliasDigest(), feedback, lease);
    }

    private NativeDecisionValidationRejectedException lockedNativeRejection(
            AidAssistantSession session, AidRuntimeRun run, AidMediaTask task,
            MediaTextGenerateRequest request, long observedRunRevision, long observedPlanRevision)
    {
        require(session != null && run != null && task != null && request != null
                        && task.getLiveTextTurn() == null
                        && StrUtil.isNotBlank(request.getAssistantNativeToolBindingsJson()),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        AssistantLoopBinding binding = moduleAgents.requirePinned(session.getModuleCode(),
                run.getVersionId(), run.getVersionDigest());
        require(binding != null && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(
                        binding.getProtocolVersion()),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        AssistantLoopExecutor executor = executorRegistry.require(binding.getProtocolVersion());
        require(executor instanceof NativeAssistantLoopExecutor,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");

        AssistantLoopModelResult result = new AssistantLoopModelResult();
        result.setRunId(run.getId());
        result.setMediaTaskId(task.getId());
        result.setObservedRunRevision(observedRunRevision);
        result.setObservedPlanRevision(observedPlanRevision);
        result.setStatus(task.getStatus());
        result.setResultJson(task.getResultText());
        result.setResultDigest(AssistantLoopModelResult.persistedTextDigest(task.getResultText()));
        result.setNativeToolBindingsJson(request.getAssistantNativeToolBindingsJson());
        result.setToolMessage(requirePersistedNativeToolMessage(task));
        try
        {
            executor.parseDecision(result, binding);
        }
        catch (NativeDecisionValidationRejectedException rejected)
        {
            return rejected;
        }
        throw fail("ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
    }

    private MediaTextGenerateRequest.TextMessageItem requirePersistedNativeToolMessage(AidMediaTask task)
    {
        // A text-only archive is re-parsed under the same locked source, settlement and one-slot
        // guards. It is never converted into a native message or an executable call.
        if (StrUtil.isBlank(task.getResponseJson())) return null;
        JSONObject root;
        try
        {
            root = JSON.parseObject(task.getResponseJson());
        }
        catch (RuntimeException invalid)
        {
            throw fail("ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        }
        Object rawMessage = root == null ? null : root.get(NATIVE_MESSAGE_KEY);
        require(root != null && root.size() == 1 && rawMessage instanceof JSONObject,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        JSONObject message = (JSONObject) rawMessage;
        require(message.keySet().equals(NATIVE_MESSAGE_FIELDS)
                        && "assistant".equals(message.get("role"))
                        && (message.get("prefix") == null || message.get("prefix") instanceof Boolean)
                        && message.get("toolCalls") instanceof JSONArray
                        && (message.get("toolCallId") == null || message.get("toolCallId") instanceof String)
                        && (message.get("responseId") == null || message.get("responseId") instanceof String)
                        && (message.get("toolError") == null || message.get("toolError") instanceof Boolean),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        JSONArray calls = (JSONArray) message.get("toolCalls");
        require(!calls.isEmpty(), "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        for (Object rawCall : calls)
        {
            require(rawCall instanceof JSONObject, "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
            JSONObject call = (JSONObject) rawCall;
            require(call.keySet().equals(NATIVE_CALL_FIELDS)
                            && call.get("id") instanceof String
                            && call.get("name") instanceof String
                            && call.get("arguments") instanceof String,
                    "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        }
        return TextToolResultSupport.readNative(task);
    }

    private void requireNativeClaimCommand(NativeClaimCommand command)
    {
        require(command != null && command.runId() != null && command.runId() > 0
                        && command.mainAgentStepId() != null && command.mainAgentStepId() > 0
                        && command.mediaTaskId() != null && command.mediaTaskId() > 0
                        && command.rejected() != null && command.lease() != null,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        NativeRejectedSourceIdentity source = command.rejected().sourceIdentity();
        require(source != null && source.runId() == command.runId()
                        && source.mediaTaskId() == command.mediaTaskId(),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        AssistantDecisionValidationFeedback.parse(command.rejected().feedback().canonicalJson());
    }

    private MediaTextGenerateRequest requireApprovalIdentity(AidAssistantSession session,
                                                              AidRuntimeRun run,
                                                              AidRuntimeRunStep main,
                                                              AidRuntimeApproval approval,
                                                              long acceptedRunRevision,
                                                              long acceptedMessageSeq,
                                                              Long agentVersionId,
                                                              String packageDigest,
                                                              String contextDigest,
                                                              long planRevision)
    {
        JSONObject scope = approvalScope(approval);
        Set<String> fields = Set.of("schemaVersion", "normalizedRequest", "publicQuote",
                "agentVersionId", "packageDigest", "acceptedRunRevision", "acceptedMessageSeq");
        require(fields.equals(scope.keySet())
                        && "assistant-approval-scope/v1".equals(string(scope, "schemaVersion"))
                        && scope.get("normalizedRequest") instanceof JSONObject
                        && scope.get("publicQuote") instanceof JSONObject
                        && Objects.equals(positiveLong(scope, "agentVersionId"), agentVersionId)
                        && Objects.equals(string(scope, "packageDigest"), packageDigest)
                        && longValue(scope, "acceptedRunRevision") == acceptedRunRevision
                        && longValue(scope, "acceptedMessageSeq") == acceptedMessageSeq
                        && Objects.equals(approval.getContextDigest(), contextDigest)
                        && Objects.equals(approval.getPlanRevision(), planRevision)
                        && Objects.equals(approval.getRequestDigest(), main.getInputDigest()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        String expectedScope = SecureUtil.sha256(approval.getScopeJson() + "\n" + packageDigest
                + "\n" + planRevision + "\n" + acceptedRunRevision);
        require(Objects.equals(expectedScope, approval.getScopeDigest()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        try
        {
            return AssistantTrustedTextRequestSnapshot.restore(
                    (JSONObject) scope.get("normalizedRequest"), approval.getRequestDigest());
        }
        catch (RuntimeException invalid)
        {
            throw fail("ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        }
    }

    private void requireSourceSettlement(AidRuntimeRun run, AidRuntimeRunStep main, AidMediaTask task)
    {
        require(main.getReservationId() != null, "ASSISTANT_DECISION_CORRECTION_SETTLEMENT_UNKNOWN");
        AidBillingAuthorizationReservation reservation = reservations.selectByIdForUpdate(main.getReservationId());
        require(reservation != null && Objects.equals(reservation.getRuntimeRunId(), run.getId())
                        && Objects.equals(reservation.getRuntimeStepId(), main.getId())
                        && Objects.equals(reservation.getApprovalId(), main.getApprovalId())
                        && Objects.equals(reservation.getAuthorizationId(), run.getBudgetAuthorizationId())
                        && Objects.equals(reservation.getMediaTaskId(), task.getId())
                        && Objects.equals(reservation.getLogicalCallKey(), main.getIdempotencyKey())
                        && Objects.equals(reservation.getRequestDigest(), main.getInputDigest())
                        && "ASSISTANT_DECISION".equals(reservation.getOperationKind())
                        && BillingReservationState.SETTLED.name().equals(reservation.getState())
                        && Objects.equals(reservation.getSettlementKey(), "settle:" + task.getId())
                        && "SUCCESS".equals(task.getBillingStatus())
                        && StrUtil.isNotBlank(task.getBillingTraceId())
                        && Objects.equals(task.getBillingTraceId(), reservation.getBillingTraceId()),
                "ASSISTANT_DECISION_CORRECTION_SETTLEMENT_UNKNOWN");
        BigDecimal reserved = amount(reservation.getReservedAmount());
        BigDecimal settled = amount(reservation.getSettledAmount());
        BigDecimal released = amount(reservation.getReleasedAmount());
        BigDecimal walletFrozen = amount(reservation.getWalletFrozenAmount());
        BigDecimal refunded = amount(reservation.getWalletRefundedAmount());
        require(reserved.signum() >= 0 && settled.signum() >= 0 && released.signum() >= 0
                        && walletFrozen.signum() >= 0 && refunded.signum() >= 0
                        && reserved.compareTo(settled.add(released)) == 0
                        && walletFrozen.compareTo(settled.add(refunded)) == 0
                        && amount(task.getActualCost()).compareTo(settled) == 0,
                "ASSISTANT_DECISION_CORRECTION_SETTLEMENT_UNKNOWN");
        IAccountUpdateService.WalletLedgerEvidence ledger = accounts.inspectLedger(task.getBillingTraceId());
        if (walletFrozen.signum() == 0)
            require(ledger != null && ledger.empty(), "ASSISTANT_DECISION_CORRECTION_LEDGER_UNKNOWN");
        else
            require(ledger != null && ledger.valid() && Objects.equals(ledger.userId(), run.getUserId())
                            && amount(ledger.frozenAmount()).compareTo(walletFrozen) == 0
                            && amount(ledger.netConsumedAmount()).compareTo(settled) == 0,
                    "ASSISTANT_DECISION_CORRECTION_LEDGER_UNKNOWN");
    }

    /** Closed, never-authorized quote history is distinct from a settled execution. */
    private boolean isSafeUnsubmittedMainApproval(AidRuntimeRun run, AidRuntimeApproval approval,
                                           List<AidRuntimeRunStep> graph)
    {
        if (approval == null || !("INVALIDATED".equals(approval.getStatus()) || TERMINAL_APPROVAL.contains(approval.getStatus()))
                || "CONSUMED".equals(approval.getStatus())
                || approval.getId() == null || approval.getId() <= 0
                || !Objects.equals(approval.getRunId(), run.getId())
                || !Objects.equals(approval.getUserId(), run.getUserId()) || !"0".equals(approval.getDelFlag())
                || !"ASSISTANT_ITERATION".equals(approval.getScopeKind())
                || approval.getAcceptedAt() != null
                || "INVALIDATED".equals(approval.getStatus()) && approval.getInvalidatedAt() == null
                || approval.getBillingAuthorizationId() != null || approval.getBillingAuthorizationRevision() != null
                || approval.getPlanningAttemptNo() == null || approval.getPlanningAttemptNo() <= 0
                || approval.getPlanRevision() == null || approval.getPlanRevision() < 0
                || !digest(approval.getRequestDigest())) return false;
        List<AidRuntimeRunStep> linked = graph.stream()
                .filter(step -> Objects.equals(step.getApprovalId(), approval.getId())).toList();
        if (linked.size() != 1) return false;
        AidRuntimeRunStep step = linked.get(0);
        String stableId = "assistant-loop/iteration/" + approval.getPlanningAttemptNo();
        String callKey = "assistant:" + run.getId() + ":iteration:" + approval.getPlanningAttemptNo();
        if (step.getId() == null || step.getId() <= 0 || !Objects.equals(step.getRunId(), run.getId())
                || !"0".equals(step.getDelFlag()) || !"MAIN_AGENT".equals(step.getStepKind())
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                || !(TERMINAL_STEP.contains(step.getStatus())
                    || "INVALIDATED".equals(approval.getStatus()) && "AWAITING_APPROVAL".equals(step.getStatus()))
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || step.getMediaTaskId() != null || step.getReservationId() != null || step.getChildRunId() != null
                || StrUtil.isNotBlank(step.getOutputJson()) || StrUtil.isNotBlank(step.getOutputArtifactRef())
                || !Objects.equals(step.getPlanPosition(), approval.getPlanningAttemptNo())
                || !Objects.equals(step.getPlanRevision(), approval.getPlanRevision())
                || !Objects.equals(step.getAttemptNo(), 1) || !Objects.equals(step.getStableStepId(), stableId)
                || !Objects.equals(step.getStepKey(), stableId) || !Objects.equals(step.getIdempotencyKey(), callKey)
                || !Objects.equals(step.getInputDigest(), approval.getRequestDigest())) return false;
        try
        {
            JSONObject scope = approvalScope(approval);
            // Existing correction metadata belongs to its SLOT/BOUND flow, never to this exception.
            if (!scope.keySet().equals(Set.of("schemaVersion", "normalizedRequest", "publicQuote",
                    "agentVersionId", "packageDigest", "acceptedRunRevision", "acceptedMessageSeq"))
                    || !"assistant-approval-scope/v1".equals(scope.get("schemaVersion"))
                    || !(scope.get("normalizedRequest") instanceof JSONObject)
                    || !(scope.get("publicQuote") instanceof JSONObject)
                    || !nonNegativeIntegral(scope.get("agentVersionId")) || scope.getLong("agentVersionId") <= 0
                    || !Objects.equals(scope.getLong("agentVersionId"), run.getVersionId())
                    || !Objects.equals(scope.get("packageDigest"), run.getVersionDigest())
                    || !nonNegativeIntegral(scope.get("acceptedRunRevision"))
                    || !nonNegativeIntegral(scope.get("acceptedMessageSeq"))) return false;
            String frozenDigest = SecureUtil.sha256(approval.getScopeJson() + "\n" + run.getVersionDigest()
                    + "\n" + approval.getPlanRevision() + "\n" + scope.getLong("acceptedRunRevision"));
            if (!Objects.equals(frozenDigest, approval.getScopeDigest())) return false;
            MediaTextGenerateRequest request = AssistantTrustedTextRequestSnapshot.restore(
                    (JSONObject) scope.get("normalizedRequest"), approval.getRequestDigest());
            return Objects.equals(request.getUserId(), run.getUserId())
                    && Objects.equals(request.getBizTaskId(), step.getId()) && MAIN_BIZ_TYPE.equals(request.getBizTaskType())
                    && Objects.equals(request.getCallId(), callKey)
                    && Objects.equals(request.getCallIdentity(), "assistant-loop:" + run.getId() + ":" + stableId)
                    && StrUtil.isNotBlank(request.getInvocationIdentity()) && request.isStrictModelSelection()
                    && request.getBillingAttemptId() == null;
        }
        catch (ServiceException | IllegalArgumentException | com.alibaba.fastjson2.JSONException invalid) { return false; }
    }

    private boolean nonNegativeIntegral(Object value)
    {
        return value instanceof Integer number && number >= 0 || value instanceof Long longNumber && longNumber >= 0;
    }

    /** Never-authorized, no-reference MAIN history also needs orphan evidence absence. */
    private Set<Long> requireNoUnsubmittedMainSideEffects(AidRuntimeRun run, List<AidRuntimeRunStep> graph,
                                                          Map<Long, AidRuntimeApproval> approvalById,
                                                          Long sourceMainId, Long successorMainId)
    {
        List<AidRuntimeRunStep> unsubmitted = graph.stream()
                .filter(step -> !Objects.equals(step.getId(), sourceMainId)
                        && !Objects.equals(step.getId(), successorMainId) && "MAIN_AGENT".equals(step.getStepKind())
                        && step.getMediaTaskId() == null && step.getReservationId() == null && step.getChildRunId() == null)
                .toList();
        if (unsubmitted.isEmpty()) return Set.of();
        Set<Long> safeApprovalIds = new HashSet<>();
        for (AidRuntimeRunStep step : unsubmitted)
        {
            AidRuntimeApproval approval = step.getApprovalId() == null ? null : approvalById.get(step.getApprovalId());
            boolean safe = step.getApprovalId() == null ? isSafeFailedPreQuoteMain(run, step)
                    : isSafeUnsubmittedMainApproval(run, approval, graph);
            require(safe, "ASSISTANT_DECISION_CORRECTION_MAIN_SUBMISSION_UNPROVEN");
            if (approval != null) safeApprovalIds.add(approval.getId());
        }
        List<Long> stepIds = unsubmitted.stream().map(AidRuntimeRunStep::getId).toList();
        List<String> callKeys = unsubmitted.stream().map(AidRuntimeRunStep::getIdempotencyKey).toList();
        // Contradictory receipts cannot be hidden by actor, run, status or deletion filters.
        List<AidMediaTask> tasks = mediaTasks.selectList(new LambdaQueryWrapper<AidMediaTask>()
                .select(AidMediaTask::getId)
                .eq(AidMediaTask::getBizTaskType, MAIN_BIZ_TYPE).in(AidMediaTask::getBizTaskId, stepIds)
                .last("LIMIT 1 FOR UPDATE"));
        require(tasks.isEmpty(), "ASSISTANT_DECISION_CORRECTION_OPERATION_TASK_INFLIGHT");
        List<AidBillingAuthorizationReservation> byStep = reservations.selectList(
                new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                        .select(AidBillingAuthorizationReservation::getId)
                        .in(AidBillingAuthorizationReservation::getRuntimeStepId, stepIds)
                        .last("LIMIT 1 FOR UPDATE"));
        require(byStep.isEmpty(), "ASSISTANT_DECISION_CORRECTION_OPERATION_SETTLEMENT_UNKNOWN");
        List<AidBillingAuthorizationReservation> byCall = reservations.selectList(
                new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                        .select(AidBillingAuthorizationReservation::getId)
                        .in(AidBillingAuthorizationReservation::getLogicalCallKey, callKeys)
                        .last("LIMIT 1 FOR UPDATE"));
        require(byCall.isEmpty(), "ASSISTANT_DECISION_CORRECTION_OPERATION_SETTLEMENT_UNKNOWN");
        if (!safeApprovalIds.isEmpty())
        {
            List<AidBillingAuthorizationReservation> byApproval = reservations.selectList(
                    new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                            .select(AidBillingAuthorizationReservation::getId)
                            .in(AidBillingAuthorizationReservation::getApprovalId, safeApprovalIds)
                            .last("LIMIT 1 FOR UPDATE"));
            require(byApproval.isEmpty(), "ASSISTANT_DECISION_CORRECTION_OPERATION_SETTLEMENT_UNKNOWN");
        }
        // Older budget edits invalidated the quote without closing its never-submitted MAIN.
        // Repair only after the complete archived identity and orphan task/reservation proof above.
        for (AidRuntimeRunStep step : unsubmitted)
        {
            if (!"AWAITING_APPROVAL".equals(step.getStatus())) continue;
            step.setStatus("CANCELLED_PRETASK");
            step.setErrorMessage("INVALIDATED_UNSUBMITTED_QUOTE");
            com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(step);
            touch(step, String.valueOf(run.getUserId()), new Date());
            require(steps.updateById(step) == 1, "ASSISTANT_DECISION_CORRECTION_GRAPH_CHANGED");
        }
        return Set.copyOf(safeApprovalIds);
    }

    /** Quote preparation can fail before an approval exists; no approval may be invented for it. */
    private boolean isSafeFailedPreQuoteMain(AidRuntimeRun run, AidRuntimeRunStep step)
    {
        if (step.getApprovalId() != null || step.getId() == null || step.getId() <= 0
                || !Objects.equals(step.getRunId(), run.getId()) || !"0".equals(step.getDelFlag())
                || !"ASSISTANT_DECISION".equals(step.getOperationKind())
                || !List.of("FAILED_PRETASK", "CANCELLED_PRETASK").contains(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || step.getMediaTaskId() != null || step.getReservationId() != null || step.getChildRunId() != null
                || StrUtil.isNotBlank(step.getOutputJson()) || StrUtil.isNotBlank(step.getOutputArtifactRef())
                || step.getPlanPosition() == null || step.getPlanPosition() < 1 || !Objects.equals(step.getAttemptNo(), 1)
                || step.getPlanRevision() == null || step.getPlanRevision() < 0 || !digest(step.getInputDigest())) return false;
        String stable = "assistant-loop/iteration/" + step.getPlanPosition();
        return Objects.equals(step.getStableStepId(), stable) && Objects.equals(step.getStepKey(), stable)
                && Objects.equals(step.getIdempotencyKey(), "assistant:" + run.getId() + ":iteration:" + step.getPlanPosition());
    }

    private void requireFullRunSafe(AidRuntimeRun run, Long sourceMainId, Long successorMainId)
    {
        requireFullRunSafe(run, sourceMainId, successorMainId, Set.of());
    }

    private void requireFullRunSafe(AidRuntimeRun run, Long sourceMainId, Long successorMainId,
                                   Set<Long> previouslyVerifiedRetiredApprovals)
    {
        List<AidRuntimeRunStep> all = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .last("LIMIT 201 FOR UPDATE"));
        require(all.size() <= 200, "ASSISTANT_DECISION_CORRECTION_GRAPH_TOO_LARGE");
        AidRuntimeRunStep sourceMain = all.stream()
                .filter(step -> Objects.equals(step.getId(), sourceMainId)).findFirst().orElse(null);
        AidRuntimeRunStep successorMain = successorMainId == null ? null : all.stream()
                .filter(step -> Objects.equals(step.getId(), successorMainId)).findFirst().orElse(null);
        require(sourceMain != null && (successorMainId == null || successorMain != null),
                "ASSISTANT_DECISION_CORRECTION_GRAPH_CHANGED");
        List<AidRuntimeApproval> runApprovals = approvals.selectList(
                new LambdaQueryWrapper<AidRuntimeApproval>()
                        .eq(AidRuntimeApproval::getRunId, run.getId())
                        .eq(AidRuntimeApproval::getDelFlag, "0").last("LIMIT 201 FOR UPDATE"));
        require(runApprovals.size() <= 200, "ASSISTANT_DECISION_CORRECTION_GRAPH_TOO_LARGE");
        Map<Long, AidRuntimeApproval> approvalById = new java.util.HashMap<>();
        for (AidRuntimeApproval approval : runApprovals)
            require(approval != null && approval.getId() != null
                            && approvalById.putIfAbsent(approval.getId(), approval) == null,
                    "ASSISTANT_DECISION_CORRECTION_GRAPH_CHANGED");
        List<AidRuntimeRunStep> skillChildren = all.stream()
                .filter(step -> "SKILL_ORCHESTRATOR".equals(step.getChildRunKind())).toList();
        Set<Long> verifiedSkillReservationIds = terminalChildEvidence.requireTerminalChildReservationIds(
                run, skillChildren, approvalById, true);
        Set<Long> supersededOperationApprovals=operationQuoteLineage.requireSupersededApprovalHistory(run,all,approvalById);
        Set<Long> safeUnsubmittedApprovalIds = requireNoUnsubmittedMainSideEffects(
                run, all, approvalById, sourceMainId, successorMainId);
        safeUnsubmittedApprovalIds=new HashSet<>(safeUnsubmittedApprovalIds);
        safeUnsubmittedApprovalIds.addAll(supersededOperationApprovals);
        Set<Long> retiredApprovalIds = new HashSet<>(previouslyVerifiedRetiredApprovals);
        for (AidRuntimeRunStep historical : all)
        {
            AidRuntimeApproval historicalApproval = approvalById.get(historical.getApprovalId());
            if (historicalApproval != null && "INVALIDATED".equals(historicalApproval.getStatus())
                    && "REPLAN_SUPERSEDED_DECISION".equals(historical.getErrorMessage())
                    && !retiredApprovalIds.contains(historicalApproval.getId()))
            {
                requireRetiredDecision(run, historical, historicalApproval);
                retiredApprovalIds.add(historicalApproval.getId());
            }
        }
        Set<Long> excludedApprovalIds = new HashSet<>();
        for (AidRuntimeRunStep step : all)
        {
            if (Objects.equals(step.getId(), sourceMainId) || Objects.equals(step.getId(), successorMainId))
            {
                if (step.getApprovalId() != null) excludedApprovalIds.add(step.getApprovalId());
                continue;
            }
            boolean main = "MAIN_AGENT".equals(step.getStepKind());
            if (!main && !List.of("ASSISTANT_TOOL", "ASSISTANT_SKILL").contains(step.getOperationKind())) continue;
            require(TERMINAL_STEP.contains(step.getStatus()),
                    main ? "ASSISTANT_DECISION_CORRECTION_MAIN_INFLIGHT"
                            : "ASSISTANT_DECISION_CORRECTION_OPERATION_INFLIGHT");
            if (step.getApprovalId() != null)
            {
                AidRuntimeApproval approval = approvalById.get(step.getApprovalId());
                require(approval != null && Objects.equals(approval.getRunId(), run.getId())
                                && Objects.equals(approval.getUserId(), run.getUserId())
                                && "0".equals(approval.getDelFlag())
                                && (TERMINAL_APPROVAL.contains(approval.getStatus())
                                || safeUnsubmittedApprovalIds.contains(approval.getId())
                                || retiredApprovalIds.contains(approval.getId())),
                        "ASSISTANT_DECISION_CORRECTION_OPERATION_APPROVAL_INFLIGHT");
            }
            // Typed Skill child IDs are not Runtime run/step IDs. Their entire graph was proved above.
            if ("SKILL_ORCHESTRATOR".equals(step.getChildRunKind())) continue;
            if (step.getMediaTaskId() != null)
            {
                AidMediaTask task = requireTask(step.getMediaTaskId(), true);
                require(TERMINAL_TASK.contains(task.getStatus())
                                && Objects.equals(task.getUserId(), run.getUserId())
                                && Objects.equals(task.getBizTaskId(), step.getId())
                                && MAIN_BIZ_TYPE.equals(task.getBizTaskType())
                                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey()),
                        "ASSISTANT_DECISION_CORRECTION_OPERATION_TASK_INFLIGHT");
            }
            if (step.getChildRunId() != null)
            {
                AidRuntimeRun child = requireRun(step.getChildRunId(), true);
                require(List.of("SUCCEEDED", "FAILED", "STOPPED").contains(child.getStatus()),
                        "ASSISTANT_DECISION_CORRECTION_CHILD_RUN_INFLIGHT");
            }
            if (main && step.getMediaTaskId() == null && step.getReservationId() == null
                    && step.getChildRunId() == null)
                require(step.getApprovalId() == null || safeUnsubmittedApprovalIds.contains(step.getApprovalId()),
                        "ASSISTANT_DECISION_CORRECTION_MAIN_SUBMISSION_UNPROVEN");
            if (step.getReservationId() == null)
            {
                require(step.getMediaTaskId() == null && step.getChildRunId() == null,
                        "ASSISTANT_DECISION_CORRECTION_OPERATION_SETTLEMENT_UNKNOWN");
            }
            else
            {
                AidBillingAuthorizationReservation receipt = reservations.selectByIdForUpdate(step.getReservationId());
                require(receipt != null && "0".equals(receipt.getDelFlag())
                                && Objects.equals(receipt.getRuntimeRunId(), run.getId())
                                && Objects.equals(receipt.getRuntimeStepId(), step.getId())
                                && Objects.equals(receipt.getAuthorizationId(), run.getBudgetAuthorizationId())
                                && Objects.equals(receipt.getApprovalId(), step.getApprovalId())
                                && Objects.equals(receipt.getLogicalCallKey(), step.getIdempotencyKey())
                                && Objects.equals(receipt.getRequestDigest(), step.getInputDigest())
                                && Objects.equals(receipt.getOperationKind(), step.getOperationKind())
                                && Objects.equals(receipt.getMediaTaskId(), step.getMediaTaskId())
                                && receipt.getState() != null && TERMINAL_RESERVATION.contains(receipt.getState()),
                        "ASSISTANT_DECISION_CORRECTION_OPERATION_SETTLEMENT_UNKNOWN");
            }
        }
        for (AidRuntimeApproval approval : runApprovals)
        {
            if (excludedApprovalIds.contains(approval.getId())) continue;
            require(Objects.equals(approval.getRunId(), run.getId())
                            && Objects.equals(approval.getUserId(), run.getUserId())
                            && "0".equals(approval.getDelFlag())
                            && (TERMINAL_APPROVAL.contains(approval.getStatus())
                                || safeUnsubmittedApprovalIds.contains(approval.getId())
                                || retiredApprovalIds.contains(approval.getId())),
                    "ASSISTANT_DECISION_CORRECTION_OPERATION_APPROVAL_INFLIGHT");
        }
        List<AidBillingAuthorizationReservation> receipts = reservations.selectList(
                new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                        .eq(AidBillingAuthorizationReservation::getRuntimeRunId, run.getId())
                        .eq(AidBillingAuthorizationReservation::getDelFlag, "0")
                        .last("LIMIT 201 FOR UPDATE"));
        require(receipts.size() <= 200, "ASSISTANT_DECISION_CORRECTION_GRAPH_TOO_LARGE");
        for (AidBillingAuthorizationReservation receipt : receipts)
        {
            // The locked child verifier already proved these exact reservations and their ledgers.
            // Every other receipt must still have an owning Runtime step below.
            if (verifiedSkillReservationIds.contains(receipt.getId())) continue;
            boolean exactSourceReceipt = Objects.equals(receipt.getRuntimeStepId(), sourceMainId)
                    && Objects.equals(receipt.getId(), sourceMain.getReservationId());
            boolean exactSuccessorReceipt = successorMain != null
                    && Objects.equals(receipt.getRuntimeStepId(), successorMainId)
                    && Objects.equals(receipt.getId(), successorMain.getReservationId());
            if (exactSourceReceipt || exactSuccessorReceipt) continue;
            AidRuntimeRunStep owner = all.stream()
                    .filter(step -> Objects.equals(step.getId(), receipt.getRuntimeStepId()))
                    .findFirst().orElse(null);
            require(owner != null && Objects.equals(owner.getReservationId(), receipt.getId()),
                    "ASSISTANT_DECISION_CORRECTION_RESERVATION_ORPHANED");
            require(receipt.getState() != null && TERMINAL_RESERVATION.contains(receipt.getState()),
                    "ASSISTANT_DECISION_CORRECTION_RESERVATION_INFLIGHT");
            requireTerminalReservationEvidence(run, receipt);
        }
    }

    /** Proves an executed but discarded decision without reviving its old authorization or correction slot. */
    private void requireRetiredDecision(AidRuntimeRun run, AidRuntimeRunStep step, AidRuntimeApproval approval)
    {
        AidMediaTask task = step.getMediaTaskId() == null ? null : requireTask(step.getMediaTaskId(), true);
        var plan = retirementPlans.selectOne(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimePlan>()
                .eq(com.aid.runtime.domain.AidRuntimePlan::getRunId, run.getId())
                .eq(com.aid.runtime.domain.AidRuntimePlan::getRevision, step.getPlanRevision())
                .eq(com.aid.runtime.domain.AidRuntimePlan::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
        var intent = replanIntents.selectOne(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeReplanIntent>()
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getRunId, run.getId())
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getBasePlanRevision, step.getPlanRevision())
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getStatus, "APPLIED")
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getDelFlag, "0").last("LIMIT 2 FOR UPDATE"));
        AidRuntimeCommand command = intent == null ? null : commands.selectById(intent.getCommandId());
        require(AssistantRetiredDecisionPolicy.verifies(run, step, approval, task, plan, intent, command),
                "ASSISTANT_DECISION_RETIREMENT_UNPROVEN");
        AidBillingAuthorizationReservation receipt = step.getReservationId() == null ? null
                : reservations.selectByIdForUpdate(step.getReservationId());
        require(receipt != null && Objects.equals(receipt.getRuntimeRunId(), run.getId())
                        && Objects.equals(receipt.getRuntimeStepId(), step.getId())
                        && Objects.equals(receipt.getApprovalId(), approval.getId())
                        && Objects.equals(receipt.getAuthorizationId(), run.getBudgetAuthorizationId())
                        && Objects.equals(receipt.getLogicalCallKey(), step.getIdempotencyKey())
                        && Objects.equals(receipt.getRequestDigest(), step.getInputDigest())
                        && "ASSISTANT_DECISION".equals(receipt.getOperationKind()),
                "ASSISTANT_DECISION_RETIREMENT_SETTLEMENT_UNPROVEN");
        com.aid.runtime.assistant.execution.AssistantTerminalChildEvidenceVerifier.requireTerminalSettlement(
                run.getUserId(), receipt, task, accounts.inspectLedger(task.getBillingTraceId()));
    }

    private void requireTerminalReservationEvidence(AidRuntimeRun run,
                                                     AidBillingAuthorizationReservation receipt)
    {
        require(receipt != null && Objects.equals(receipt.getRuntimeRunId(), run.getId())
                        && Objects.equals(receipt.getAuthorizationId(), run.getBudgetAuthorizationId())
                        && "0".equals(receipt.getDelFlag()),
                "ASSISTANT_DECISION_CORRECTION_RESERVATION_INVALID");
        BigDecimal reserved = amount(receipt.getReservedAmount());
        BigDecimal settled = amount(receipt.getSettledAmount());
        BigDecimal released = amount(receipt.getReleasedAmount());
        BigDecimal walletFrozen = amount(receipt.getWalletFrozenAmount());
        BigDecimal refunded = amount(receipt.getWalletRefundedAmount());
        require(reserved.signum() >= 0 && settled.signum() >= 0 && released.signum() >= 0
                        && walletFrozen.signum() >= 0 && refunded.signum() >= 0
                        && reserved.compareTo(settled.add(released)) == 0
                        && walletFrozen.compareTo(settled.add(refunded)) == 0
                        && StrUtil.isNotBlank(receipt.getBillingTraceId()),
                "ASSISTANT_DECISION_CORRECTION_RESERVATION_INVALID");
        boolean validSettlementKey = switch (receipt.getState()) {
            case "SETTLED" -> receipt.getMediaTaskId() != null
                    && Objects.equals(receipt.getSettlementKey(), "settle:" + receipt.getMediaTaskId());
            case "REFUNDED" -> receipt.getMediaTaskId() != null
                    && Objects.equals(receipt.getSettlementKey(), "refund:" + receipt.getMediaTaskId());
            case "RELEASED" -> Objects.equals(receipt.getSettlementKey(), "release:" + receipt.getId())
                    || receipt.getMediaTaskId() == null
                    && Objects.equals(receipt.getSettlementKey(), "unsubmitted-release:" + receipt.getId());
            default -> false;
        };
        require(validSettlementKey,
                "ASSISTANT_DECISION_CORRECTION_RESERVATION_INVALID");
        IAccountUpdateService.WalletLedgerEvidence ledger = accounts.inspectLedger(receipt.getBillingTraceId());
        if (walletFrozen.signum() == 0)
            require(ledger != null && ledger.empty(), "ASSISTANT_DECISION_CORRECTION_LEDGER_UNKNOWN");
        else
            require(ledger != null && ledger.valid() && Objects.equals(ledger.userId(), run.getUserId())
                            && amount(ledger.frozenAmount()).compareTo(walletFrozen) == 0
                            && amount(ledger.netConsumedAmount()).compareTo(settled) == 0,
                    "ASSISTANT_DECISION_CORRECTION_LEDGER_UNKNOWN");
        if (receipt.getMediaTaskId() != null)
        {
            AidMediaTask task = requireTask(receipt.getMediaTaskId(), true);
            String expectedBilling = BillingReservationState.SETTLED.name().equals(receipt.getState())
                    ? "SUCCESS" : "FAILED";
            require(TERMINAL_TASK.contains(task.getStatus())
                            && Objects.equals(task.getUserId(), run.getUserId())
                            && Objects.equals(task.getBillingTraceId(), receipt.getBillingTraceId())
                            && expectedBilling.equals(task.getBillingStatus())
                            && amount(task.getActualCost()).compareTo(settled) == 0,
                    "ASSISTANT_DECISION_CORRECTION_OPERATION_TASK_INVALID");
        }
    }

    private ClaimedSource requireClaimedSource(AidAssistantSession session, AidRuntimeRun run, SlotReceipt slot)
    {
        AidRuntimeRunStep source = requireStep(slot.sourceMainStepId(), run.getId(), true);
        require("FAILED".equals(source.getStatus()) && SOURCE_CLAIMED.equals(source.getErrorMessage())
                        && "ACCEPTED".equals(source.getProviderAcceptanceState())
                        && Objects.equals(source.getMediaTaskId(), slot.sourceMediaTaskId()),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        AidRuntimeApproval approval = requireSourceApproval(run, source, Set.of("CONSUMED"));
        MediaTextGenerateRequest request = requireApprovalIdentity(session, run, source, approval,
                slot.observedRunRevision(), slot.acceptedMessageSeq(),
                slot.agentVersionId(), slot.packageDigest(), slot.contextDigest(), slot.planRevision());
        AidMediaTask task = requireTask(slot.sourceMediaTaskId(), true);
        require(Objects.equals(slot.sourceRequestDigest(), approval.getRequestDigest())
                        && Objects.equals(run.getVersionId(), slot.agentVersionId())
                        && Objects.equals(run.getVersionDigest(), slot.packageDigest())
                        && Objects.equals(slot.sourceResultDigest(),
                        AssistantLoopModelResult.persistedTextDigest(task.getResultText())),
                "ASSISTANT_DECISION_CORRECTION_SOURCE_CHANGED");
        requireSourceSettlement(run, source, task);
        return new ClaimedSource(request, task);
    }

    private record ClaimedSource(MediaTextGenerateRequest request, AidMediaTask task) { }

    private AidRuntimeApproval requireSourceApproval(AidRuntimeRun run, AidRuntimeRunStep main,
                                                       Set<String> acceptedStatuses)
    {
        AidRuntimeApproval approval = main.getApprovalId() == null ? null : approvals.selectOne(
                new LambdaQueryWrapper<AidRuntimeApproval>().eq(AidRuntimeApproval::getId, main.getApprovalId())
                        .eq(AidRuntimeApproval::getRunId, run.getId())
                        .eq(AidRuntimeApproval::getUserId, run.getUserId())
                        .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        require(approval != null && "ASSISTANT_ITERATION".equals(approval.getScopeKind())
                        && acceptedStatuses.contains(approval.getStatus())
                        && Objects.equals(approval.getPlanningAttemptNo(), main.getPlanPosition())
                        && approval.getAcceptedAt() != null && approval.getExpiresAt() != null
                        && approval.getAcceptedAt().before(approval.getExpiresAt()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        return approval;
    }

    private JSONObject requireCorrectionApprovalIdentity(AidAssistantSession session,
                                                         AidRuntimeRun run,
                                                         AidRuntimeRunStep successor,
                                                         AidRuntimeApproval approval,
                                                         long acceptedRunRevision,
                                                         long acceptedMessageSeq,
                                                         Long agentVersionId,
                                                         String packageDigest,
                                                         String contextDigest,
                                                         long planRevision,
                                                         String expectedStatus)
    {
        require(session != null && run != null && successor != null && approval != null,
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        JSONObject scope = approvalScope(approval);
        Set<String> scopeFields = Set.of("schemaVersion", "normalizedRequest", "publicQuote",
                "agentVersionId", "packageDigest", "acceptedRunRevision", "acceptedMessageSeq",
                "decisionCorrection");
        Object rawCorrection = scope.get("decisionCorrection");
        require(scopeFields.equals(scope.keySet())
                        && "assistant-approval-scope/v1".equals(string(scope, "schemaVersion"))
                        && scope.get("normalizedRequest") instanceof JSONObject
                        && scope.get("publicQuote") instanceof JSONObject
                        && rawCorrection instanceof JSONObject
                        && Objects.equals(positiveLong(scope, "agentVersionId"), agentVersionId)
                        && Objects.equals(string(scope, "packageDigest"), packageDigest)
                        && longValue(scope, "acceptedRunRevision") == acceptedRunRevision
                        && longValue(scope, "acceptedMessageSeq") == acceptedMessageSeq
                        && Objects.equals(session.getId(), run.getSessionId())
                        && Objects.equals(session.getUserId(), run.getUserId())
                        && Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(successor.getRunId(), run.getId())
                        && Objects.equals(successor.getApprovalId(), approval.getId())
                        && Objects.equals(approval.getRunId(), run.getId())
                        && Objects.equals(approval.getUserId(), run.getUserId())
                        && "ASSISTANT_ITERATION".equals(approval.getScopeKind())
                        && expectedStatus.equals(approval.getStatus())
                        && Objects.equals(approval.getPlanningAttemptNo(), successor.getPlanPosition())
                        && Objects.equals(approval.getPlanRevision(), planRevision)
                        && Objects.equals(approval.getContextDigest(), contextDigest)
                        && Objects.equals(approval.getRequestDigest(), successor.getInputDigest())
                        && "0".equals(approval.getDelFlag()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        String expectedScope = SecureUtil.sha256(approval.getScopeJson() + "\n" + packageDigest
                + "\n" + planRevision + "\n" + acceptedRunRevision);
        require(Objects.equals(expectedScope, approval.getScopeDigest()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        MediaTextGenerateRequest request;
        try
        {
            request = AssistantTrustedTextRequestSnapshot.restore(
                    (JSONObject) scope.get("normalizedRequest"), approval.getRequestDigest());
        }
        catch (RuntimeException invalid)
        {
            throw fail("ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        }
        require(request != null && Objects.equals(request.getBizTaskId(), successor.getId())
                        && MAIN_BIZ_TYPE.equals(request.getBizTaskType())
                        && Objects.equals(request.getUserId(), run.getUserId())
                        && Objects.equals(request.getCallId(), successor.getIdempotencyKey())
                        && Objects.equals(request.getCallIdentity(),
                        "assistant-loop:" + run.getId() + ":" + successor.getStableStepId())
                        && StrUtil.isNotBlank(request.getInvocationIdentity())
                        && request.isStrictModelSelection(),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        JSONObject correction = (JSONObject) rawCorrection;
        require(Set.of("schemaVersion", "slotDigest", "feedbackDigest", "candidateIteration")
                        .equals(correction.keySet())
                        && APPROVAL_SCHEMA.equals(string(correction, "schemaVersion")),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        return correction;
    }

    private JSONObject approvalScope(AidRuntimeApproval approval)
    {
        require(approval != null && StrUtil.isNotBlank(approval.getScopeJson()),
                "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        return object(approval.getScopeJson(), "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
    }

    private AidRuntimeCommand insertInternal(Long actorId, Long sessionId, String key,
                                             String type, String payloadDigest, String resultJson)
    {
        require(AssistantDecisionCorrectionCommandNamespace.isReservedClientRequestId(key)
                        && AssistantDecisionCorrectionCommandNamespace.isInternalCommandType(type)
                        && digest(payloadDigest) && StrUtil.isNotBlank(resultJson),
                "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        AidRuntimeCommand command = new AidRuntimeCommand();
        command.setActorId(actorId);
        command.setSessionId(sessionId);
        command.setClientRequestId(key);
        command.setPayloadDigest(payloadDigest);
        command.setCommandType(type);
        command.setStatus("COMPLETED");
        command.setResultJson(resultJson);
        command.setDelFlag("0");
        Date now = new Date();
        command.setCreateBy(String.valueOf(actorId));
        command.setCreateTime(now);
        command.setUpdateBy(String.valueOf(actorId));
        command.setUpdateTime(now);
        try { require(commands.insert(command) == 1, "ASSISTANT_DECISION_CORRECTION_MARKER_CHANGED"); }
        catch (DuplicateKeyException collision) { throw fail("ASSISTANT_DECISION_CORRECTION_MARKER_CONFLICT"); }
        return command;
    }

    private AidRuntimeCommand findInternal(Long actorId, Long sessionId, String key, boolean lock)
    {
        return commands.selectOne(new LambdaQueryWrapper<AidRuntimeCommand>()
                .eq(AidRuntimeCommand::getActorId, actorId).eq(AidRuntimeCommand::getSessionId, sessionId)
                .eq(AidRuntimeCommand::getClientRequestId, key).eq(AidRuntimeCommand::getDelFlag, "0")
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    private boolean sourceClaimExists(Long runId)
    {
        Long count = steps.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .eq(AidRuntimeRunStep::getErrorMessage, SOURCE_CLAIMED)
                .eq(AidRuntimeRunStep::getDelFlag, "0"));
        return count != null && count > 0;
    }

    private boolean correctionApprovalExists(Long runId)
    {
        List<AidRuntimeApproval> values = approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, runId)
                .eq(AidRuntimeApproval::getScopeKind, "ASSISTANT_ITERATION")
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByDesc(AidRuntimeApproval::getId)
                .last("LIMIT 201 FOR UPDATE"));
        require(values.size() <= 200, "ASSISTANT_DECISION_CORRECTION_GRAPH_TOO_LARGE");
        for (AidRuntimeApproval value : values)
        {
            if (StrUtil.isBlank(value.getScopeJson()))
                throw fail("ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
            if (object(value.getScopeJson(), "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID")
                    .containsKey("decisionCorrection")) return true;
        }
        return false;
    }

    private int nextMainIteration(Long runId)
    {
        Object value = steps.selectObjs(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .select(AidRuntimeRunStep::getPlanPosition).eq(AidRuntimeRunStep::getRunId, runId)
                        .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                        .orderByDesc(AidRuntimeRunStep::getPlanPosition).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        int next = value == null ? 1 : ((Number) value).intValue() + 1;
        require(next > 0, "ASSISTANT_DECISION_CORRECTION_ITERATION_INVALID");
        return next;
    }

    private String sourceDigest(ClaimCommand command, AidRuntimeRun run,
                                AidRuntimeRunStep main, AidRuntimeApproval approval)
    {
        JSONObject value = new JSONObject();
        value.put("runId", id(run.getId()));
        value.put("mainStepId", id(main.getId()));
        value.put("mediaTaskId", id(command.mediaTaskId()));
        value.put("requestDigest", approval.getRequestDigest());
        value.put("resultDigest", command.sourceResultDigest());
        value.put("toolMessageDigest", command.toolMessageDigest());
        value.put("nativeBindingsDigest", command.nativeBindingsDigest());
        value.put("trustedAliasDigest", command.trustedAliasDigest());
        value.put("observedRunRevision", command.observedRunRevision());
        value.put("observedPlanRevision", command.observedPlanRevision());
        return SecureUtil.sha256(value.toJSONString());
    }

    private void requireClaimCommand(ClaimCommand command)
    {
        require(command != null && command.runId() != null && command.runId() > 0
                        && command.mainAgentStepId() != null && command.mainAgentStepId() > 0
                        && command.mediaTaskId() != null && command.mediaTaskId() > 0
                        && command.observedRunRevision() >= 0 && command.observedPlanRevision() >= 0
                        && digest(command.sourceResultDigest()) && digest(command.toolMessageDigest())
                        && digest(command.nativeBindingsDigest()) && digest(command.trustedAliasDigest())
                        && command.feedback() != null && command.lease() != null,
                "ASSISTANT_DECISION_CORRECTION_SOURCE_INVALID");
        AssistantDecisionValidationFeedback.parse(command.feedback().canonicalJson());
    }

    private void requireActive(AidAssistantSession session, AidRuntimeRun run, RuntimeLease lease)
    {
        Date now = new Date();
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                        && Objects.equals(session.getUserId(), run.getUserId())
                        && lease != null && StrUtil.isNotBlank(lease.getOwner())
                        && Objects.equals(run.getLeaseOwner(), lease.getOwner())
                        && Objects.equals(run.getFencingToken(), lease.getFencingToken())
                        && run.getLeaseUntil() != null && run.getLeaseUntil().after(now)
                        && lease.getLeaseUntil() != null && lease.getLeaseUntil().after(now),
                "ASSISTANT_DECISION_CORRECTION_LEASE_CHANGED");
    }

    private AidAssistantSession requireSession(Long id, Long userId, boolean lock)
    {
        AidAssistantSession value = lock ? sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, id).eq(AidAssistantSession::getDelFlag, "0")
                .last("FOR UPDATE")) : sessions.selectById(id);
        require(value != null && "ASSISTANT".equals(value.getSessionType())
                        && "0".equals(value.getDelFlag()) && Objects.equals(value.getUserId(), userId),
                "ASSISTANT_DECISION_CORRECTION_SESSION_INVALID");
        return value;
    }

    private AidRuntimeRun requireRun(Long id, boolean lock)
    {
        AidRuntimeRun value = lock ? runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, id).eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"))
                : runs.selectById(id);
        require(value != null && "0".equals(value.getDelFlag()),
                "ASSISTANT_DECISION_CORRECTION_RUN_INVALID");
        return value;
    }

    private AidRuntimeRunStep requireStep(Long id, Long runId, boolean lock)
    {
        AidRuntimeRunStep value = lock ? steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, id).eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE")) : steps.selectById(id);
        require(value != null && Objects.equals(value.getRunId(), runId) && "0".equals(value.getDelFlag()),
                "ASSISTANT_DECISION_CORRECTION_STEP_INVALID");
        return value;
    }

    private AidMediaTask requireTask(Long id, boolean lock)
    {
        AidMediaTask value = lock ? mediaTasks.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, id).last("FOR UPDATE")) : mediaTasks.selectById(id);
        require(value != null, "ASSISTANT_DECISION_CORRECTION_TASK_INVALID");
        return value;
    }

    private void consumeApproval(AidRuntimeApproval approval, String actor, Date now)
    {
        require("ACCEPTED".equals(approval.getStatus()), "ASSISTANT_DECISION_CORRECTION_APPROVAL_INVALID");
        approval.setStatus("CONSUMED");
        approval.setStateRevision(value(approval.getStateRevision()) + 1);
        touch(approval, actor, now);
        require(approvals.updateById(approval) == 1, "ASSISTANT_DECISION_CORRECTION_APPROVAL_CHANGED");
    }

    private void appendEvent(AidAssistantSession session, AidRuntimeRun run, AidRuntimeRunStep source,
                             AidRuntimeCommand slot, Date now, String actor)
    {
        session.setLastEventSeq(value(session.getLastEventSeq()) + 1);
        session.setStateRevision(value(session.getStateRevision()) + 1);
        session.setLastActiveTime(now);
        touch(session, actor, now);
        require(sessions.updateById(session) == 1, "ASSISTANT_DECISION_CORRECTION_SESSION_CHANGED");
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(run.getId());
        event.setSeq(session.getLastEventSeq());
        event.setEventType("run.state_changed");
        event.setEntityId(id(run.getId()));
        event.setEntityRevision(run.getStateRevision());
        event.setCausationId(source.getIdempotencyKey());
        event.setPayloadJson(JSON.toJSONString(Map.of("status", run.getStatus(),
                "nextAction", run.getNextAction(), "correctionSlotCommandId", id(slot.getId()))));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        require(events.insert(event) == 1, "ASSISTANT_DECISION_CORRECTION_EVENT_CHANGED");
    }

    private JSONObject object(String json, String code)
    {
        try
        {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        }
        catch (RuntimeException invalid) { throw fail(code); }
    }

    private Long id(JSONObject value, String key)
    {
        Object raw = value.get(key);
        require(raw instanceof String && ((String) raw).matches("[1-9][0-9]{0,18}"),
                "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        try { return Long.valueOf((String) raw); }
        catch (NumberFormatException invalid) { throw fail("ASSISTANT_DECISION_CORRECTION_MARKER_INVALID"); }
    }

    private long longValue(JSONObject value, String key)
    {
        Object raw = value.get(key);
        require(raw instanceof Long || raw instanceof Integer,
                "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        long result = ((Number) raw).longValue();
        require(result >= 0, "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        return result;
    }

    private int integer(JSONObject value, String key)
    {
        Object raw = value.get(key);
        require(raw instanceof Integer && (Integer) raw > 0,
                "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        return (Integer) raw;
    }

    private String digest(JSONObject value, String key)
    {
        String result = string(value, key);
        require(digest(result), "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        return result;
    }

    private String string(JSONObject value, String key)
    {
        Object raw = value.get(key);
        require(raw instanceof String,
                "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        return (String) raw;
    }

    private long positiveLong(JSONObject value, String key)
    {
        long result = longValue(value, key);
        require(result > 0, "ASSISTANT_DECISION_CORRECTION_MARKER_INVALID");
        return result;
    }

    private boolean digest(String value) { return value != null && value.matches(SHA256); }
    private boolean isExplicitPreMutationRejection(ServiceException failure)
    {
        return failure != null && failure.getMessage() != null
                && failure.getMessage().startsWith("ASSISTANT_DECISION_CORRECTION_");
    }
    private String id(Long value) { return value == null ? null : String.valueOf(value); }
    private long value(Long value) { return value == null ? 0L : value; }
    private BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private void touch(com.aid.common.core.domain.BaseEntity value, String actor, Date now)
    {
        value.setUpdateBy(actor);
        value.setUpdateTime(now);
    }

    private void require(boolean value, String code) { if (!value) throw fail(code); }
    private ServiceException fail(String code) { return new ServiceException(code); }

    public record ClaimCommand(Long runId, Long mainAgentStepId, Long mediaTaskId,
                               long observedRunRevision, long observedPlanRevision,
                               String sourceResultDigest, String toolMessageDigest,
                               String nativeBindingsDigest, String trustedAliasDigest,
                               AssistantDecisionValidationFeedback feedback, RuntimeLease lease) { }

    public record NativeClaimCommand(Long runId, Long mainAgentStepId, Long mediaTaskId,
                                     NativeDecisionValidationRejectedException rejected,
                                     RuntimeLease lease) { }

    public enum NativeClaimRejectionStage
    {
        INPUT, LOCKED_AUTHORITY, SOURCE_IDENTITY, SIDE_EFFECT_PREFLIGHT, SETTLEMENT,
        FULL_GRAPH, LOCKED_REPARSE, EQUALITY, MARKER_IDENTITY
    }

    /** Only source-owned vocabulary can reach diagnostics; never the original cause text. */
    public enum NativeClaimRejectionCode
    {
        ALREADY_BOUND,
        APPROVAL_CHANGED,
        APPROVAL_INVALID,
        BINDINGS_INVALID,
        BOUND_CHANGED,
        BOUND_INVALID,
        BOUND_MISSING,
        CHILD_RUN_INFLIGHT,
        CONTEXT_CHANGED,
        CONTEXT_INVALID,
        EVENT_CHANGED,
        GRAPH_CHANGED,
        GRAPH_TOO_LARGE,
        ITERATION_INVALID,
        LEASE_CHANGED,
        LEDGER_UNKNOWN,
        MAIN_INFLIGHT,
        MARKER_CHANGED,
        MARKER_CONFLICT,
        MARKER_INVALID,
        OPERATION_APPROVAL_INFLIGHT,
        OPERATION_EXISTS,
        OPERATION_INFLIGHT,
        OPERATION_SETTLEMENT_UNKNOWN,
        OPERATION_TASK_INFLIGHT,
        OPERATION_TASK_INVALID,
        REASON_UNSUPPORTED,
        RESERVATION_INFLIGHT,
        RESERVATION_INVALID,
        RESERVATION_ORPHANED,
        RUN_CHANGED,
        RUN_INVALID,
        SESSION_CHANGED,
        SESSION_INVALID,
        SETTLEMENT_UNKNOWN,
        SLOT_CHANGED,
        SLOT_INVALID,
        SLOT_MISSING,
        SOURCE_CHANGED,
        SOURCE_INVALID,
        STEP_INVALID,
        SUCCESSOR_INVALID,
        TASK_INVALID,
        MAIN_SUBMISSION_UNPROVEN,
        UNKNOWN_SAFE_GUARD;

        private static NativeClaimRejectionCode from(ServiceException failure)
        {
            String message = failure == null ? null : failure.getMessage();
            String prefix = "ASSISTANT_DECISION_CORRECTION_";
            if (message == null || !message.startsWith(prefix)) return UNKNOWN_SAFE_GUARD;
            try { return valueOf(message.substring(prefix.length())); }
            catch (IllegalArgumentException unknown) { return UNKNOWN_SAFE_GUARD; }
        }
    }

    /** Explicit durable-source rejection emitted before the SLOT transaction starts mutating rows. */
    public static final class NativeClaimSourceRejectedException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final NativeClaimRejectionStage rejectionStage;
        private final NativeClaimRejectionCode rejectionCode;

        private NativeClaimSourceRejectedException(ServiceException cause, NativeClaimRejectionStage stage)
        {
            super("NATIVE_DECISION_CORRECTION_SOURCE_REJECTED", cause);
            rejectionStage = stage;
            rejectionCode = NativeClaimRejectionCode.from(cause);
        }

        public NativeClaimRejectionStage rejectionStage() { return rejectionStage; }
        public NativeClaimRejectionCode rejectionCode() { return rejectionCode; }
    }

    public record ParsedClaimCommand(Long runId, Long mainAgentStepId, Long mediaTaskId,
                                     AssistantPlanDecisionRejectedException.Reason reason,
                                     RuntimeLease lease) { }

    public record ParsedClaimResult(ParsedClaimDisposition disposition, String slotDigest,
                                    String feedbackDigest, int candidateIteration) { }

    public enum ParsedClaimDisposition
    {
        CLAIMED,
        SOURCE_REPLAY,
        LIMIT_ALREADY_USED
    }

    public record ClaimResult(String slotDigest, String feedbackDigest,
                              int candidateIteration, boolean replay) { }

    private record SlotReceipt(Long runId, Long sessionId, Long sourceMainStepId,
                               Long sourceMediaTaskId, String sourceRequestDigest,
                               String sourceResultDigest, String sourceToolMessageDigest,
                               String sourceBindingsDigest, String sourceAliasDigest,
                               String sourceIdentityDigest,
                               String feedbackDigest, String feedbackCanonicalJson,
                               int candidateIteration, long observedRunRevision,
                               long planRevision, String contextDigest, Long agentVersionId,
                               String packageDigest, long acceptedMessageSeq, String slotDigest) { }

    private record BoundReceipt(Long runId, Long sessionId, String slotDigest,
                                String feedbackDigest, int candidateIteration,
                                Long sourceMainStepId, Long successorMainStepId,
                                Long successorApprovalId, String requestDigest,
                                String contextDigest, long planRevision,
                                long acceptedMessageSeq, long acceptedRunRevision) { }

    @Resource private com.aid.runtime.assistant.execution.AssistantOperationQuoteLineage operationQuoteLineage;
}
