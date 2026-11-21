package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.mapper.AidComicEpisodeMapper;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.media.admission.MediaSubmissionAdmissionDecision;
import com.aid.media.admission.MediaSubmissionAdmissionHandler;
import com.aid.media.admission.MediaTaskCancellationAdmissionDecision;
import com.aid.media.admission.MediaTaskCancellationAdmissionHandler;
import com.aid.media.admission.MediaTaskCancellationPurpose;
import com.aid.media.admission.MediaTaskCancellationRequest;
import com.aid.media.admission.MediaTaskIntentAdmissionDecision;
import com.aid.media.admission.MediaTaskIntentAdmissionHandler;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.assistant.replan.RuntimeReplanCancellationAuthorizationPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Provider-boundary admission for assistant runtime steps. */
@Slf4j
@Component
public class AssistantRuntimeSubmissionAdmissionHandler
        implements MediaSubmissionAdmissionHandler, MediaTaskIntentAdmissionHandler,
        MediaTaskCancellationAdmissionHandler
{
    public static final String BIZ_TASK_TYPE = "ASSISTANT_RUNTIME_STEP";

    @Resource
    private AidRuntimeRunMapper runMapper;
    @Resource
    private AidRuntimeRunStepMapper stepMapper;
    @Resource
    private AidRuntimeApprovalMapper approvalMapper;
    @Resource
    private AidAssistantSessionMapper sessionMapper;
    @Resource
    private AidRuntimeEventMapper eventMapper;
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;
    @Resource
    private AidComicProjectMapper projectMapper;
    @Resource
    private AidComicEpisodeMapper episodeMapper;
    @Resource
    private AssistantBillingBudgetService budgetService;
    @Resource
    private AssistantBudgetPolicyService budgetPolicyService;
    @Resource
    private RuntimeReplanCancellationAuthorizationPort replanCancellationAuthorization;
    @Autowired(required = false)
    private List<AssistantRuntimeSubmissionScopeGuard> scopeGuards = Collections.emptyList();

    @Override
    public String supportedBizTaskType()
    {
        return BIZ_TASK_TYPE;
    }

    /**
     * Linearizes the first durable media-task identity with the worker fences that prepared it.
     * The media service invokes this after task insert and before billing pre-hold in the same
     * transaction.  Once bound, the queued task may survive a later lease hand-off; Provider
     * admission therefore relies on the immutable task binding rather than an expired worker.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MediaTaskIntentAdmissionDecision claimBeforeCommit(AidMediaTask task, BillingBudgetContext context)
    {
        validateIntentIdentity(task, context);
        AidRuntimeRun discoveredRun = runMapper.selectById(context.getRuntimeRunId());
        AidRuntimeRunStep discoveredStep = stepMapper.selectById(context.getRuntimeStepId());
        if (discoveredRun == null || discoveredRun.getSessionId() == null
                || discoveredStep == null || discoveredStep.getApprovalId() == null)
            throw new ServiceException("Assistant task intent identity is unavailable");
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, discoveredRun.getSessionId())
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, context.getRuntimeRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeApproval approval = approvalMapper.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, discoveredStep.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, context.getRuntimeRunId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRunStep step = stepMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, context.getRuntimeStepId())
                .eq(AidRuntimeRunStep::getRunId, context.getRuntimeRunId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (session == null || run == null || approval == null || step == null
                || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(session.getUserId(), run.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !Objects.equals(run.getBudgetAuthorizationId(), context.getAuthorizationId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(step.getStableStepId(), context.getStableStepKey())
                || !Objects.equals(step.getAttemptNo(), context.getAttemptNo())
                || !Objects.equals(step.getReservationId(), context.getReservationId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !Objects.equals(task.getIdempotencyKey(), context.getLogicalCallKey())
                || !Objects.equals(approval.getBillingAuthorizationId(), context.getAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(), context.getAuthorizationRevision())
                || !Objects.equals(approval.getRequestDigest(), context.getRequestDigest())
                || !Objects.equals(approval.getQuoteId(), context.getQuoteId())
                || !Objects.equals(approval.getQuoteDigest(), context.getQuoteDigest()))
            throw new ServiceException("Assistant task intent identity changed");

        if (step.getMediaTaskId() != null)
        {
            return Objects.equals(step.getMediaTaskId(), task.getId())
                    ? MediaTaskIntentAdmissionDecision.REPLAY_EXISTING
                    : MediaTaskIntentAdmissionDecision.RECONCILE_ONLY;
        }
        if (!Arrays.asList("READY", "RUNNING").contains(run.getStatus())
                || Boolean.TRUE.equals(run.getStopRequested())
                || !"READY".equals(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || run.getFencingToken() == null || step.getFencingToken() == null
                || !Objects.equals(run.getFencingToken(), context.getRuntimeRunFencingToken())
                || !Objects.equals(step.getFencingToken(), context.getRuntimeStepFencingToken()))
            throw new ServiceException("Assistant task intent fence or state changed");

        verifyAuthoritativeScope(session, run, step, approval, task, intentSnapshot(context), "TASK_INTENT");
        step.setMediaTaskId(task.getId());
        step.setUpdateBy(String.valueOf(run.getUserId()));
        step.setUpdateTime(DateUtils.getNowDate());
        if (stepMapper.updateById(step) != 1)
            throw new ServiceException("Assistant task intent state changed");
        return MediaTaskIntentAdmissionDecision.PERSIST_NEW;
    }

    /**
     * Proves that a stopped Assistant task has a durable identity but never crossed Provider
     * admission.  The media service performs the task CAS and refund later in this same transaction;
     * this method intentionally does not mutate Runtime state before that CAS has won.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MediaTaskCancellationAdmissionDecision claimCancellation(AidMediaTask task,
                                                                     BillingSnapshot snapshot,
                                                                     MediaTaskCancellationRequest request)
    {
        validateIdentity(task, snapshot);
        AidRuntimeRun discoveredRun = runMapper.selectById(snapshot.getRuntimeRunId());
        AidRuntimeRunStep discoveredStep = stepMapper.selectById(snapshot.getRuntimeStepId());
        if (discoveredRun == null || discoveredRun.getSessionId() == null || discoveredStep == null
                || discoveredStep.getApprovalId() == null)
            return MediaTaskCancellationAdmissionDecision.RECONCILE_ONLY;
        RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity replanIdentity =
                authorizeReplanCancellation(task, snapshot, request, discoveredRun);
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, discoveredRun.getSessionId())
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, snapshot.getRuntimeRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeApproval approval = approvalMapper.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, discoveredStep.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, snapshot.getRuntimeRunId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRunStep step = stepMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, snapshot.getRuntimeStepId())
                .eq(AidRuntimeRunStep::getRunId, snapshot.getRuntimeRunId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (session == null || run == null || approval == null || step == null
                || !purposeAllowsCancellation(run, request, replanIdentity)
                || !Objects.equals(session.getUserId(), run.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(run.getBudgetAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(step.getStableStepId(), snapshot.getStableStepKey())
                || !Objects.equals(step.getAttemptNo(), snapshot.getBillingAttemptNo())
                || !Objects.equals(step.getReservationId(), snapshot.getBillingReservationId())
                || !Objects.equals(step.getMediaTaskId(), task.getId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !Objects.equals(task.getIdempotencyKey(), snapshot.getLogicalCallKey())
                || !Arrays.asList("READY", "INFLIGHT").contains(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                || !Arrays.asList("QUEUED", "PENDING").contains(task.getStatus())
                || StrUtil.isNotBlank(task.getProviderTaskId()) || task.getUpstreamAcceptTime() != null)
            return MediaTaskCancellationAdmissionDecision.RECONCILE_ONLY;
        try
        {
            verifyHistoricalApprovalIdentity(run, step, approval, snapshot);
        }
        catch (RuntimeException changed)
        {
            return MediaTaskCancellationAdmissionDecision.RECONCILE_ONLY;
        }
        return MediaTaskCancellationAdmissionDecision.CANCEL_NOT_SUBMITTED;
    }

    private RuntimeReplanCancellationAuthorizationPort.ReplanCancellationIdentity
            authorizeReplanCancellation(AidMediaTask task, BillingSnapshot snapshot,
                                        MediaTaskCancellationRequest request,
                                        AidRuntimeRun discoveredRun) {
        if (request == null || request.purpose() == null) {
            throw new ServiceException("Assistant cancellation purpose is unavailable");
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
                && Arrays.asList("CANCEL_FOR_REPLAN", "WAIT_REPLAN_RECONCILIATION")
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
    @Transactional(rollbackFor = Exception.class)
    public MediaSubmissionAdmissionDecision claimBeforeProvider(AidMediaTask task, BillingSnapshot snapshot)
    {
        validateIdentity(task, snapshot);
        AidRuntimeRun discoveredRun = runMapper.selectById(snapshot.getRuntimeRunId());
        if (discoveredRun == null || discoveredRun.getSessionId() == null)
        {
            throw new ServiceException("Assistant runtime identity is unavailable");
        }
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, discoveredRun.getSessionId())
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, snapshot.getRuntimeRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
        if (run == null || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(run.getBudgetAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Arrays.asList("READY", "RUNNING", "PAUSING", "PAUSED", "RECONCILING")
                .contains(run.getStatus()))
        {
            log.info("助手出站运行状态拒绝, taskId={}, runId={}", task.getId(), snapshot.getRuntimeRunId());
            throw new ServiceException("运行状态已变化");
        }
        AidRuntimeRunStep discoveredStep = stepMapper.selectById(snapshot.getRuntimeStepId());
        if (discoveredStep == null || discoveredStep.getApprovalId() == null)
        {
            throw new ServiceException("Assistant approval identity is unavailable");
        }
        AidRuntimeApproval approval = approvalMapper.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getId, discoveredStep.getApprovalId())
                .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRunStep step = stepMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, snapshot.getRuntimeStepId())
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (step == null || !Objects.equals(step.getStableStepId(), snapshot.getStableStepKey())
                || !Objects.equals(step.getAttemptNo(), snapshot.getBillingAttemptNo())
                || !Objects.equals(step.getReservationId(), snapshot.getBillingReservationId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !Objects.equals(task.getIdempotencyKey(), snapshot.getLogicalCallKey()))
        {
            log.info("助手出站步骤身份拒绝, taskId={}, stepId={}", task.getId(), snapshot.getRuntimeStepId());
            throw new ServiceException("步骤身份已变化");
        }
        boolean exactTaskBinding = Objects.equals(step.getMediaTaskId(), task.getId());
        if ("SUBMITTING".equals(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
        {
            if (!exactTaskBinding) throw new ServiceException("Assistant historical task identity changed");
            verifyHistoricalApprovalIdentity(run, step, approval, snapshot);
            return MediaSubmissionAdmissionDecision.RECONCILE_ONLY;
        }
        if (Boolean.TRUE.equals(run.getStopRequested()))
        {
            if (!exactTaskBinding || !Arrays.asList("READY", "INFLIGHT").contains(step.getStatus()))
                throw new ServiceException("Assistant stopped task identity changed");
            verifyHistoricalApprovalIdentity(run, step, approval, snapshot);
            // The trusted cancellation service owns task CANCELLED + refund.  Keep the durable task
            // pending until that transaction wins rather than turning an unsubmitted task UNKNOWN.
            return MediaSubmissionAdmissionDecision.DEFER_UNTIL_RESUME;
        }
        if (Arrays.asList("PAUSING", "PAUSED").contains(run.getStatus()))
        {
            if (!exactTaskBinding || !Arrays.asList("READY", "INFLIGHT").contains(step.getStatus()))
                throw new ServiceException("Assistant deferred task identity changed");
            verifyHistoricalApprovalIdentity(run, step, approval, snapshot);
            return MediaSubmissionAdmissionDecision.DEFER_UNTIL_RESUME;
        }
        verifyAuthoritativeScope(session, run, step, approval, task, snapshot, "PROVIDER_CLAIM");
        if (!com.aid.assistant.service.AssistantAutomaticTextExecution.WALLET_APPROVAL_MODE.equals(approval.getMode()))
            budgetPolicyService.assertProviderSubmission(run.getId(), snapshot.getBillingAuthorizationId(),
                    snapshot.getBillingAuthorizationRevision(), operationScope(approval, step, task, snapshot));
        if (!Arrays.asList("READY", "INFLIGHT").contains(step.getStatus()))
        {
            log.info("助手出站步骤状态拒绝, taskId={}, stepId={}, status={}",
                    task.getId(), step.getId(), step.getStatus());
            throw new ServiceException("步骤状态已变化");
        }
        if (!exactTaskBinding)
        {
            log.info("助手出站任务绑定冲突, stepId={}, expectedTaskId={}, actualTaskId={}",
                    step.getId(), step.getMediaTaskId(), task.getId());
            throw new ServiceException("任务绑定已变化");
        }

        Date now = DateUtils.getNowDate();
        step.setMediaTaskId(task.getId());
        step.setStatus("SUBMITTING");
        step.setProviderAcceptanceState("SUBMITTING");
        step.setUpdateBy(String.valueOf(run.getUserId()));
        step.setUpdateTime(now);
        if (stepMapper.updateById(step) != 1)
        {
            throw new ServiceException("Assistant step state changed");
        }

        BillingBudgetRequests.SubmissionClaimRequest claim = new BillingBudgetRequests.SubmissionClaimRequest();
        claim.setAuthorizationId(snapshot.getBillingAuthorizationId());
        claim.setAuthorizationRevision(snapshot.getBillingAuthorizationRevision());
        claim.setReservationId(snapshot.getBillingReservationId());
        claim.setExpectedReservationRevision(snapshot.getBillingReservationRevision());
        claim.setRuntimeRunId(run.getId());
        claim.setRuntimeStepId(step.getId());
        claim.setStableStepKey(step.getStableStepId());
        claim.setAttemptNo(step.getAttemptNo());
        claim.setLogicalCallKey(snapshot.getLogicalCallKey());
        claim.setMediaTaskId(task.getId());
        claim.setOperator("runtime:" + run.getId());
        BillingBudgetViews.SubmissionClaimResult result = budgetService.claimSubmission(claim);
        if (result == null)
        {
            throw new ServiceException("Budget admission result is unavailable");
        }
        if (result.isAlreadyInflight())
        {
            return MediaSubmissionAdmissionDecision.RECONCILE_ONLY;
        }
        if (!result.isNewlyClaimed())
        {
            throw new ServiceException("Budget admission was not claimed");
        }
        return MediaSubmissionAdmissionDecision.PROCEED_NEW_CLAIM;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markReconciliationRequired(AidMediaTask task, BillingSnapshot snapshot)
    {
        validateIdentity(task, snapshot);
        AidRuntimeRun discoveredRun = runMapper.selectById(snapshot.getRuntimeRunId());
        if (discoveredRun == null || discoveredRun.getSessionId() == null)
        {
            throw new ServiceException("Assistant reconciliation session is unavailable");
        }
        // All session event writers lock session before run and step; discovery is not authority.
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, discoveredRun.getSessionId())
                .eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"));
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, snapshot.getRuntimeRunId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"));
        if (run == null || session == null || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(run.getSessionId(), session.getId())
                || !Objects.equals(session.getUserId(), run.getUserId())
                || !Objects.equals(run.getUserId(), task.getUserId())
                || !Objects.equals(run.getBudgetAuthorizationId(), snapshot.getBillingAuthorizationId()))
        {
            throw new ServiceException("\u8fd0\u884c\u8eab\u4efd\u51b2\u7a81");
        }
        AidRuntimeRunStep step = stepMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, snapshot.getRuntimeStepId())
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (step == null || !Objects.equals(step.getStableStepId(), snapshot.getStableStepKey())
                || !Objects.equals(step.getAttemptNo(), snapshot.getBillingAttemptNo())
                || !Objects.equals(step.getReservationId(), snapshot.getBillingReservationId())
                || !Objects.equals(step.getMediaTaskId(), task.getId())
                || !Objects.equals(task.getBizTaskId(), step.getId())
                || !Objects.equals(task.getIdempotencyKey(), snapshot.getLogicalCallKey()))
        {
            throw new ServiceException("\u6b65\u9aa4\u8eab\u4efd\u51b2\u7a81");
        }
        if (Arrays.asList("SUCCEEDED", "FAILED", "CANCELLED").contains(step.getStatus()))
        {
            return;
        }
        if (!Arrays.asList("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING").contains(step.getStatus()))
        {
            throw new ServiceException("\u6b65\u9aa4\u72b6\u6001\u51b2\u7a81");
        }
        Date now = DateUtils.getNowDate();
        boolean stepChanged = !"RECONCILING".equals(step.getStatus())
                || !"UNKNOWN".equals(step.getProviderAcceptanceState());
        if (stepChanged)
        {
            step.setStatus("RECONCILING");
            step.setProviderAcceptanceState("UNKNOWN");
            step.setErrorMessage("PROVIDER_ACCEPTANCE_UNKNOWN");
            step.setUpdateBy(String.valueOf(run.getUserId()));
            step.setUpdateTime(now);
            if (stepMapper.updateById(step) != 1)
            {
                throw new ServiceException("\u6b65\u9aa4\u72b6\u6001\u5df2\u53d8\u66f4");
            }
        }
        String reconcileAction = "ASSISTANT_TOOL".equals(step.getOperationKind())
                ? "RECONCILE_OPERATION" : "WAIT_TASK_RECONCILIATION";
        boolean runChanged = !Arrays.asList("SUCCEEDED", "FAILED", "STOPPED").contains(run.getStatus())
                && (!"RECONCILING".equals(run.getStatus())
                || !Objects.equals(reconcileAction, run.getNextAction()));
        if (runChanged)
        {
            run.setStatus("RECONCILING");
            run.setNextAction(reconcileAction);
            run.setStateRevision((run.getStateRevision() == null ? 0L : run.getStateRevision()) + 1L);
            run.setUpdateBy(String.valueOf(run.getUserId()));
            run.setUpdateTime(now);
            if (runMapper.updateById(run) != 1)
            {
                throw new ServiceException("\u8fd0\u884c\u72b6\u6001\u5df2\u53d8\u66f4");
            }
        }
        if (stepChanged || runChanged)
        {
            appendReconciliationEvent(session, run, step, task, now);
        }
    }

    /** The journal and state commit together; realtime listeners deliver only after commit. */
    private void appendReconciliationEvent(AidAssistantSession session, AidRuntimeRun run,
                                           AidRuntimeRunStep step, AidMediaTask task, Date now)
    {
        session.setLastEventSeq((session.getLastEventSeq() == null ? 0L : session.getLastEventSeq()) + 1L);
        session.setUpdateBy(String.valueOf(run.getUserId()));
        session.setUpdateTime(now);
        if (sessionMapper.updateById(session) != 1)
        {
            throw new ServiceException("Assistant reconciliation event cursor changed");
        }
        JSONObject payload = new JSONObject();
        payload.put("status", run.getStatus());
        payload.put("nextAction", run.getNextAction());
        payload.put("stepId", String.valueOf(step.getId()));
        payload.put("providerAcceptanceState", step.getProviderAcceptanceState());
        payload.put("reason", "PROVIDER_ACCEPTANCE_UNKNOWN");
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(run.getId());
        event.setSeq(session.getLastEventSeq());
        event.setEventType("run.state_changed");
        event.setEntityId(String.valueOf(run.getId()));
        event.setEntityRevision(run.getStateRevision());
        event.setCausationId(String.valueOf(task.getId()));
        event.setPayloadJson(JSON.toJSONString(payload));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1)
        {
            throw new ServiceException("Assistant reconciliation event was not persisted");
        }
        applicationEventPublisher.publishEvent(event);
    }

    private void verifyAuthoritativeScope(AidAssistantSession session, AidRuntimeRun run,
                                          AidRuntimeRunStep step, AidRuntimeApproval approval,
                                          AidMediaTask task, BillingSnapshot snapshot, String submissionPhase)
    {
        Date now = DateUtils.getNowDate();
        if (session == null || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(session.getUserId(), run.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !"ASSISTANT_LOOP".equals(run.getExecutorStrategy())
                || approval == null || !"ACCEPTED".equals(approval.getStatus())
                || !Objects.equals(approval.getRunId(), run.getId())
                || !Objects.equals(approval.getUserId(), run.getUserId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(approval.getBillingAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(), snapshot.getBillingAuthorizationRevision())
                || !Objects.equals(step.getPlanRevision(), run.getPlanRevision())
                || !Objects.equals(approval.getPlanRevision(), run.getPlanRevision())
                || !Objects.equals(approval.getRequestDigest(), snapshot.getRequestDigest())
                || !Objects.equals(approval.getQuoteId(), snapshot.getQuoteId())
                || !Objects.equals(approval.getQuoteDigest(), snapshot.getQuoteDigest())
                || !Objects.equals(approval.getContextDigest(), run.getContextDigest())
                || !AssistantApprovalAcceptance.acceptedWithinQuoteWindow(approval, now)
                || run.getFencingToken() == null || run.getFencingToken() <= 0
                || step.getFencingToken() == null || step.getFencingToken() <= 0)
        {
            throw new ServiceException("Assistant submission authorization changed");
        }

        Long projectId = zeroToNull(session.getProjectId());
        if (projectId != null)
        {
            AidComicProject project = projectMapper.selectOne(new LambdaQueryWrapper<AidComicProject>()
                    .eq(AidComicProject::getId, projectId)
                    .eq(AidComicProject::getUserId, run.getUserId())
                    .eq(AidComicProject::getDelFlag, "0").last("LIMIT 1"));
            if (project == null)
            {
                throw new ServiceException("Assistant project access was revoked");
            }
        }
        Long episodeId = zeroToNull(session.getEpisodeId());
        if (episodeId != null)
        {
            AidComicEpisode episode = episodeMapper.selectOne(new LambdaQueryWrapper<AidComicEpisode>()
                    .eq(AidComicEpisode::getId, episodeId)
                    .eq(AidComicEpisode::getUserId, run.getUserId())
                    .eq(projectId != null, AidComicEpisode::getProjectId, projectId)
                    .eq(AidComicEpisode::getDelFlag, "0").last("LIMIT 1"));
            if (episode == null)
            {
                throw new ServiceException("Assistant episode access was revoked");
            }
        }

        boolean anyCanvasIdentity = run.getWorkspaceId() != null || StrUtil.isNotBlank(run.getNodeObjectId())
                || run.getNodeVersion() != null;
        if (anyCanvasIdentity)
        {
            boolean hasObject = StrUtil.isNotBlank(run.getNodeObjectId());
            boolean hasObjectVersion = run.getNodeVersion() != null;
            if (run.getWorkspaceId() == null || hasObject != hasObjectVersion)
            {
                throw new ServiceException("Assistant canvas submission identity is incomplete");
            }
            List<AssistantRuntimeSubmissionScopeGuard> matching = scopeGuards.stream()
                    .filter(guard -> "FREE_CANVAS".equals(guard.supportedContextKind()))
                    .collect(Collectors.toList());
            if (matching.size() != 1)
            {
                throw new ServiceException("Assistant canvas submission guard is unavailable");
            }
            AssistantRuntimeSubmissionScopeGuard.Context context = AssistantRuntimeSubmissionScopeGuard.Context.builder()
                    .contextKind("FREE_CANVAS")
                    .runId(run.getId()).userId(run.getUserId()).projectId(projectId)
                    .workspaceId(run.getWorkspaceId()).objectId(run.getNodeObjectId())
                    .objectVersion(run.getNodeVersion()).executorStrategy(run.getExecutorStrategy())
                    .runtimeLeaseOwner(run.getLeaseOwner()).runtimeFencingToken(run.getFencingToken())
                    .stepId(step.getId()).toolCode(step.getToolCode()).operationKind(step.getOperationKind())
                    .submissionFencingToken(step.getFencingToken())
                    .mediaTaskId(task.getId()).submissionPhase(submissionPhase).build();
            matching.get(0).assertStillAuthorized(context);
        }
    }

    private void verifyHistoricalApprovalIdentity(AidRuntimeRun run, AidRuntimeRunStep step,
                                                  AidRuntimeApproval approval, BillingSnapshot snapshot)
    {
        if (approval == null || !Objects.equals(approval.getRunId(), run.getId())
                || !Objects.equals(approval.getUserId(), run.getUserId())
                || !Objects.equals(step.getApprovalId(), approval.getId())
                || !Objects.equals(step.getPlanRevision(), approval.getPlanRevision())
                || !Objects.equals(approval.getBillingAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(), snapshot.getBillingAuthorizationRevision())
                || !Objects.equals(approval.getRequestDigest(), snapshot.getRequestDigest())
                || !Objects.equals(approval.getQuoteId(), snapshot.getQuoteId())
                || !Objects.equals(approval.getQuoteDigest(), snapshot.getQuoteDigest()))
        {
            throw new ServiceException("Assistant submission history changed");
        }
    }

    private AssistantBudgetOperationScope operationScope(AidRuntimeApproval approval,
                                                         AidRuntimeRunStep step,
                                                         AidMediaTask task,
                                                         BillingSnapshot snapshot)
    {
        AssistantBudgetOperationScope result = null;
        try
        {
            JSONObject persisted = JSON.parseObject(approval.getScopeJson());
            JSONObject operation = persisted == null ? null : persisted.getJSONObject("operationScope");
            if (operation != null) result = operation.toJavaObject(AssistantBudgetOperationScope.class);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("Assistant operation scope is invalid");
        }
        if (result == null && "ASSISTANT_ITERATION".equals(approval.getScopeKind()))
        {
            result = new AssistantBudgetOperationScope();
            result.setOperationKind("ASSISTANT_DECISION");
            result.setModelCode(task.getModelName());
            result.setCapabilityCodes(List.of("text.chat"));
            result.setMediaTypes(List.of("TEXT"));
            result.setQuotedUpperBound(snapshot.getAuthorizedChargeCap());
            result.setCountsAsIteration(true);
        }
        // JSON/database round trips may remove trailing zeros. Compare the exact monetary
        // value, without rounding, while still rejecting missing or genuinely changed caps.
        if (result == null || result.getQuotedUpperBound() == null || snapshot.getAuthorizedChargeCap() == null
                || result.getQuotedUpperBound().compareTo(snapshot.getAuthorizedChargeCap()) != 0
                || !Objects.equals(result.getModelCode(), task.getModelName())
                || "ASSISTANT_TOOL".equals(step.getOperationKind()) && StrUtil.isBlank(result.getToolCode()))
            throw new ServiceException("Assistant operation scope changed");
        return result;
    }

    private Long zeroToNull(Long value)
    {
        return value == null || value == 0L ? null : value;
    }

    private BillingSnapshot intentSnapshot(BillingBudgetContext context)
    {
        BillingSnapshot value = new BillingSnapshot();
        value.setBudgetMode(context.getMode());
        value.setBillingAuthorizationId(context.getAuthorizationId());
        value.setBillingAuthorizationRevision(context.getAuthorizationRevision());
        value.setBillingReservationId(context.getReservationId());
        value.setBillingReservationRevision(context.getReservationRevision());
        value.setRuntimeRunId(context.getRuntimeRunId());
        value.setRuntimeStepId(context.getRuntimeStepId());
        value.setRuntimeRunFencingToken(context.getRuntimeRunFencingToken());
        value.setRuntimeStepFencingToken(context.getRuntimeStepFencingToken());
        value.setStableStepKey(context.getStableStepKey());
        value.setBillingAttemptNo(context.getAttemptNo());
        value.setLogicalCallKey(context.getLogicalCallKey());
        value.setRequestDigest(context.getRequestDigest());
        value.setQuoteId(context.getQuoteId());
        value.setQuoteDigest(context.getQuoteDigest());
        return value;
    }

    private void validateIntentIdentity(AidMediaTask task, BillingBudgetContext context)
    {
        if (task == null || task.getId() == null || context == null
                || !BIZ_TASK_TYPE.equals(task.getBizTaskType()) || !context.isRunAuthorizationMode()
                || context.getAuthorizationId() == null || context.getAuthorizationRevision() == null
                || context.getReservationId() == null || context.getReservationRevision() == null
                || context.getRuntimeRunId() == null || context.getRuntimeStepId() == null
                || context.getRuntimeRunFencingToken() == null || context.getRuntimeStepFencingToken() == null
                || context.getAttemptNo() == null || isBlank(context.getStableStepKey())
                || isBlank(context.getLogicalCallKey()) || isBlank(context.getRequestDigest())
                || isBlank(context.getQuoteId()) || isBlank(context.getQuoteDigest()))
            throw new ServiceException("Assistant task intent is incomplete");
    }

    private void validateIdentity(AidMediaTask task, BillingSnapshot snapshot)
    {
        if (task == null || task.getId() == null || snapshot == null
                || !BIZ_TASK_TYPE.equals(task.getBizTaskType())
                || !"RUN_AUTHORIZATION".equals(snapshot.getBudgetMode())
                || snapshot.getRuntimeRunId() == null || snapshot.getRuntimeStepId() == null
                || snapshot.getBillingAuthorizationId() == null
                || snapshot.getBillingAuthorizationRevision() == null
                || snapshot.getBillingReservationId() == null
                || snapshot.getBillingReservationRevision() == null
                || snapshot.getRuntimeRunFencingToken() == null
                || snapshot.getRuntimeStepFencingToken() == null
                || snapshot.getBillingAttemptNo() == null || isBlank(snapshot.getStableStepKey())
                || isBlank(snapshot.getLogicalCallKey()))
        {
            log.info("助手出站快照不完整, taskId={}", task == null ? null : task.getId());
            throw new ServiceException("预算快照不完整");
        }
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
