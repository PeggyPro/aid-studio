package com.aid.assistant.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.budget.AssistantBudgetOperationAdmission;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.budget.AssistantBudgetAdmissionRejectedException;
import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.realtime.AssistantRealtimeEventPublisher;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.RuntimeScopeAccessRegistry;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.service.AssistantRunBudgetScopeService;
import com.aid.assistant.service.AssistantQuiescentRunGuard;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.billing.dto.BillingBudgetRequests;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.enums.BillingAuthorizationStatus;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.RuntimeRunLifecycle;
import com.aid.runtime.mapper.AidRuntimeBudgetPolicyMapper;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AssistantBudgetPolicyServiceImpl implements AssistantBudgetPolicyService
{
    private static final long MAX_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final Set<String> MODES = Set.of("MANUAL", "AUTO");
    private static final Set<String> MEDIA_TYPES = Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "MUSIC");
    private static final Set<String> MANDATORY_AUTO_FORBIDDEN = Set.of(
            "ARTIFACT_ADOPT", "EXTERNAL_PUBLISH",
            "CREDENTIAL_WRITE", "TEAM_MEMBERSHIP_WRITE");

    @Resource private AidRuntimeBudgetPolicyMapper policies;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private com.aid.runtime.mapper.AidRuntimeInputRequestMapper inputRequests;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private com.aid.runtime.mapper.AidRuntimePlanMapper plans;
    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidRuntimeEventMapper events;
    @Resource private AssistantRealtimeEventPublisher realtimeEventPublisher;
    @Resource private AssistantBillingBudgetService budgets;
    @Resource private AssistantQuiescentRunGuard quiescentRuns;
    @Resource private RuntimeScopeAccessRegistry scopeAccess;
    @Resource private AssistantRuntimeContextManifestService contextManifests;
    @Resource private AssistantContextLimits contextLimits;
    /** Break Registry -> Provider -> admission guard -> policy -> live Tool catalogue startup cycle. */
    @Lazy
    @Resource private AssistantRunBudgetScopeService runBudgetScopes;
    @Resource @Lazy private com.aid.assistant.service.AssistantCoreService sessionAccess;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleRegistry;
    @Resource private com.aid.runtime.assistant.recovery.AssistantRuntimeDueRunWakeup dueRunWakeup;

    @Override
    @Transactional(readOnly = true)
    public String executionMode(AidRuntimeRun run) {
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, run.getId())
                .eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("LIMIT 1"));
        return com.aid.assistant.service.AssistantExecutionMode.resolve(run, policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillingBudgetViews.AuthorizationView ensureOperationPolicy(Long userId, AidRuntimeRun run,
            AssistantExecutionRequests.RunBudgetPolicy defaults, String operator)
    {
        AidRuntimeBudgetPolicy policy = lockPolicy(run.getId());
        if (policy == null) return createPolicy(userId, run, canonical(defaults, run, null), operator).authorization();
        BillingBudgetViews.AuthorizationView authorization = budgets.getAuthorization(policy.getAuthorizationId());
        requireAuthorization(policy, authorization, run, userId);
        return authorization;
    }

    private Confirmation createPolicy(Long userId, AidRuntimeRun run, CanonicalPolicy canonical, String operator)
    {
        BillingBudgetRequests.CreateAuthorizationRequest create = new BillingBudgetRequests.CreateAuthorizationRequest();
        create.setPayerUserId(userId);
        create.setRuntimeRunId(run.getId());
        create.setApprovedTotal(canonical.absoluteMaxCharge());
        create.setPolicyDigest(canonical.scopeDigest());
        create.setExpiresAt(canonical.expiresAt());
        create.setOperator(operator);
        BillingBudgetViews.AuthorizationView authorization = budgets.createAuthorization(create);
        AidRuntimeBudgetPolicy policy = new AidRuntimeBudgetPolicy();
        policy.setRunId(run.getId());
        policy.setAuthorizationId(authorization.getId());
        policy.setAuthorizationRevision(authorization.getRevision());
        apply(policy, canonical);
        policy.setUsedIterations(0);
        policy.setUsedOperations(0);
        policy.setStatus("ACTIVE");
        policy.setStateRevision(0L);
        policy.setDelFlag("0");
        touchCreate(policy, operator);
        policies.insert(policy);
        return new Confirmation(policy, authorization);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Confirmation confirmCall(Long userId, AidRuntimeRun run, AidRuntimeApproval approval,
                                    AssistantExecutionRequests.RunBudgetPolicy requested, String operator)
    {
        return confirmCall(userId, run, approval, requested, false, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Confirmation confirmCall(Long userId, AidRuntimeRun run, AidRuntimeApproval approval,
                                    AssistantExecutionRequests.RunBudgetPolicy requested,
                                    boolean confirmRenewedExecution, String operator)
    {
        AidRuntimeBudgetPolicy existing = lockPolicy(run.getId());
        if (existing == null)
        {
            if (requested == null)
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "RUN_BUDGET_REQUIRED",
                        "\u8bf7\u786e\u8ba4\u672c\u6b21\u8fd0\u884c\u9884\u7b97", Map.of("runId", id(run.getId())));
            return createPolicy(userId, run, canonical(requested, run, approval), operator);
        }

        requireActive(existing, new Date());
        BillingBudgetViews.AuthorizationView authorization = budgets.getAuthorization(existing.getAuthorizationId());
        requireAuthorization(existing, authorization, run, userId);
        if (requested != null)
        {
            CanonicalPolicy supplied = canonical(requested, run, approval);
            if (!Objects.equals(supplied.scopeDigest(), existing.getScopeDigest()))
                throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_REVISION_REQUIRED",
                        "\u8bf7\u5355\u72ec\u786e\u8ba4\u9884\u7b97\u4fee\u6539", Map.of("runId", id(run.getId())));
        }
        assertCall(existing, authorization, approval.getMaxCharge(), first(approval.getAllowedModelCodesJson()),
                "ASSISTANT_DECISION");
        if (existing.getRenewalConfirmationRevision() != null)
        {
            if (!Objects.equals(existing.getRenewalConfirmationRevision(), existing.getAuthorizationRevision()))
                throw policyDenied("RENEWAL_STATE_UNAVAILABLE");
            if (!confirmRenewedExecution)
                throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_RENEWAL_CONFIRMATION_REQUIRED", "请确认本次新调用", null);
            long before = value(existing.getStateRevision());
            existing.setRenewalConfirmationRevision(null);
            existing.setStateRevision(before + 1);
            touchUpdate(existing, operator);
            if (policies.update(existing, new LambdaUpdateWrapper<AidRuntimeBudgetPolicy>()
                    .eq(AidRuntimeBudgetPolicy::getId, existing.getId())
                    .eq(AidRuntimeBudgetPolicy::getStateRevision, before)
                    .set(AidRuntimeBudgetPolicy::getRenewalConfirmationRevision, null)) != 1)
                throw policyDenied("RENEWAL_STATE_UNAVAILABLE");
        }
        return new Confirmation(existing, authorization);
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantExecutionViews.BudgetPolicy detail(Long userId, AssistantExecutionRequests.BudgetDetail request)
    {
        AidRuntimeRun run = requireOwnedRun(userId, request.getRunId(), false);
        boolean historical = RuntimeRunLifecycle.isTerminal(run.getStatus());
        AidAssistantSession session = historical
                ? sessionAccess.authorizeSession(userId, run.getSessionId(), false)
                : requireSession(userId, run.getSessionId(), false);
        if (!historical) verifyScope(userId, run);
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, run.getId())
                .eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("LIMIT 1"));
        // New runs have no optional budget before their first authorized planning step.
        if (policy == null) return null;
        BillingBudgetViews.AuthorizationView authorization = budgets.getAuthorization(policy.getAuthorizationId());
        requireAuthorization(policy, authorization, run, userId);
        return view(run, session, policy, authorization);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantExecutionViews.BudgetRevision revise(Long userId, AssistantExecutionRequests.BudgetRevise request)
    {
        AidRuntimeRun discovered = requireOwnedRun(userId, request.getRunId(), false);
        String payloadDigest = SecureUtil.sha256(JSON.toJSONString(request));
        AidRuntimeCommand replay = replay(userId, discovered.getSessionId(), request.getClientRequestId(), payloadDigest);
        if (replay != null) return JSON.parseObject(replay.getResultJson(), AssistantExecutionViews.BudgetRevision.class);

        AidAssistantSession session = requireSession(userId, discovered.getSessionId(), true);
        AidRuntimeRun run = requireOwnedRun(userId, discovered.getId(), true);
        replay = replay(userId, session.getId(), request.getClientRequestId(), payloadDigest);
        if (replay != null) return JSON.parseObject(replay.getResultJson(), AssistantExecutionViews.BudgetRevision.class);
        if (!Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !Objects.equals(request.getExpectedRunRevision(), value(run.getStateRevision())))
            throw conflict(run, session);
        verifyScope(userId, run);
        AidRuntimeBudgetPolicy policy = lockPolicy(run.getId());
        if (policy == null)
            throw failure(HttpStatus.NOT_FOUND, "RUN_BUDGET_NOT_FOUND", "\u8fd0\u884c\u9884\u7b97\u4e0d\u5b58\u5728", id(run.getId()));
        if (!Objects.equals(request.getExpectedPolicyRevision(), value(policy.getStateRevision()))
                || !Objects.equals(request.getExpectedAuthorizationRevision(), policy.getAuthorizationRevision()))
            throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_REVISION_CONFLICT", "\u8fd0\u884c\u9884\u7b97\u5df2\u66f4\u65b0",
                    Map.of("policyRevision", value(policy.getStateRevision()),
                            "authorizationRevision", value(policy.getAuthorizationRevision())));
        boolean renewal = Boolean.TRUE.equals(request.getRenewExpired());
        BillingBudgetViews.AuthorizationView previous = budgets.getAuthorization(policy.getAuthorizationId());
        requireAuthorization(policy, previous, run, userId);
        if (policy.getRenewalConfirmationRevision() != null
                && !Objects.equals(policy.getRenewalConfirmationRevision(), policy.getAuthorizationRevision()))
            throw policyDenied("RENEWAL_STATE_UNAVAILABLE");
        AssistantQuiescentRunGuard.BudgetGraph renewalGraph = null;
        if (renewal)
        {
            String reason = renewalBlockReason(run, session, policy, previous, new Date());
            if (reason != null) throw renewalDenied(reason);
            if (budgets.hasUnresolvedReservations(previous.getId())) throw renewalDenied("RESERVATION_PENDING");
            renewalGraph = quiescentRuns.inspectBudgetRenewal(userId, run, policy, true);
            if (renewalGraph.blockReason() != null) throw renewalDenied(renewalGraph.blockReason());
        }
        else
        {
            requireRevisable(policy, new Date());
            if (budgets.hasUnresolvedReservations(previous.getId()))
                throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_CALL_PENDING", "当前调用结束后可调整生成预算", null);
        }
        CanonicalPolicy canonical = canonical(request.getRunBudget(), run, null, renewal);
        if (canonical.maxIterations() < value(policy.getUsedIterations())
                || canonical.maxOperations() < value(policy.getUsedOperations()))
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "RUN_BUDGET_INVALID", "\u9884\u7b97\u6b21\u6570\u4f4e\u4e8e\u5df2\u7528\u6b21\u6570", null);
        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(), payloadDigest);
        BillingBudgetRequests.ReviseAuthorizationRequest revise = renewal
                ? new BillingBudgetRequests.RenewAuthorizationRequest() : new BillingBudgetRequests.ReviseAuthorizationRequest();
        revise.setAuthorizationId(policy.getAuthorizationId());
        revise.setPayerUserId(userId);
        revise.setExpectedRevision(policy.getAuthorizationRevision());
        revise.setApprovedTotal(canonical.absoluteMaxCharge());
        revise.setPolicyDigest(SecureUtil.sha256(policy.getScopeDigest() + "\n" + canonical.scopeDigest()));
        revise.setExpiresAt(canonical.expiresAt());
        revise.setOperator(String.valueOf(userId));
        if (renewal)
        {
            BillingBudgetRequests.RenewAuthorizationRequest renew = (BillingBudgetRequests.RenewAuthorizationRequest) revise;
            renew.setRuntimeRunId(run.getId());
            renew.setExpectedStateRevision(previous.getStateRevision());
            renew.setPriorPolicyExpiresAt(policy.getExpiresAt());
        }
        BillingBudgetViews.AuthorizationView authorization = renewal
                ? budgets.renewExpiredAuthorization((BillingBudgetRequests.RenewAuthorizationRequest) revise)
                : budgets.reviseAuthorization(revise);
        boolean needsConfirmation = renewal || policy.getRenewalConfirmationRevision() != null;
        long previousPolicyRevision = value(policy.getStateRevision());
        apply(policy, canonical);
        policy.setAuthorizationRevision(authorization.getRevision());
        policy.setRenewalConfirmationRevision(needsConfirmation ? authorization.getRevision() : null);
        policy.setStatus(canonical.maxIterations() > value(policy.getUsedIterations())
                && canonical.maxOperations() > value(policy.getUsedOperations()) ? "ACTIVE" : "EXHAUSTED");
        policy.setStateRevision(value(policy.getStateRevision()) + 1);
        touchUpdate(policy, String.valueOf(userId));
        if (policies.update(policy, new LambdaUpdateWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getId, policy.getId())
                .eq(AidRuntimeBudgetPolicy::getStateRevision, previousPolicyRevision)) != 1)
            throw policyDenied("POLICY_REVISION_CHANGED");
        long previousRunRevision = value(run.getStateRevision());
        JSONObject executionRequest = JSON.parseObject(run.getRequestJson());
        executionRequest.put("executionPreference", policy.getMode());
        run.setRequestJson(executionRequest.toJSONString());
        boolean automaticResume = "AUTO".equals(policy.getMode()) && !Boolean.TRUE.equals(run.getStopRequested())
                && ("WAITING_INPUT".equals(run.getStatus())
                    || "PAUSED".equals(run.getStatus()) && ("OPERATION_FAILED".equals(run.getPauseReason())
                        || !renewal && "BUDGET_EXCEEDED".equals(run.getPauseReason())));
        if (automaticResume) {
            for (var input : inputRequests.selectList(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeInputRequest>()
                    .eq(com.aid.runtime.domain.AidRuntimeInputRequest::getRunId, run.getId())
                    .in(com.aid.runtime.domain.AidRuntimeInputRequest::getStatus, List.of("OPEN", "DRAFTING", "SUSPENDED"))
                    .last("FOR UPDATE"))) {
                input.setStatus("WITHDRAWN");
                touchUpdate(input, String.valueOf(userId));
                inputRequests.updateById(input);
            }
            run.setStatus("WAITING_APPROVAL");
            run.setNextAction("CONTINUE_AGENT_REQUIRED");
            run.setNextRetryAt(new Date());
            run.setPauseReason(null);
            run.setResumeStatus(null);
        }
        run.setStateRevision(value(run.getStateRevision()) + 1);
        touchUpdate(run, String.valueOf(userId));
        if (runs.update(run, new LambdaUpdateWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, run.getId()).eq(AidRuntimeRun::getStateRevision, previousRunRevision)
                .set(automaticResume, AidRuntimeRun::getPauseReason, null)
                .set(automaticResume, AidRuntimeRun::getResumeStatus, null)) != 1)
            throw conflict(run, session);
        List<String> invalidatedApprovalIds = renewal
                ? quiescentRuns.invalidateBudgetQuotes(userId, run.getId(), renewalGraph)
                : invalidateUnsubmittedApprovals(run, userId);
        appendEvent(session, run, policy, request.getClientRequestId());
        if ("AUTO".equals(policy.getMode()) && !Boolean.TRUE.equals(run.getStopRequested())
                && "WAITING_APPROVAL".equals(run.getStatus())
                && "PLAN_EXECUTION_APPROVAL_REQUIRED".equals(run.getNextAction())) {
            var plan = plans.selectOne(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimePlan>()
                    .eq(com.aid.runtime.domain.AidRuntimePlan::getRunId, run.getId())
                    .eq(com.aid.runtime.domain.AidRuntimePlan::getRevision, run.getPlanRevision())
                    .eq(com.aid.runtime.domain.AidRuntimePlan::getDelFlag, "0").last("LIMIT 1"));
            var confirmation = new AssistantExecutionRequests.PlanConfirm();
            confirmation.setRunId(run.getId());
            confirmation.setPlanRevision(plan.getRevision());
            confirmation.setDefinitionDigest(plan.getDefinitionDigest());
            confirmation.setExpectedRunRevision(run.getStateRevision());
            confirmation.setClientRequestId(SecureUtil.sha256("automatic-plan:" + run.getId() + ":" + plan.getRevision()));
            sessionAccess.confirmPlan(userId, confirmation);
            run = runs.selectById(run.getId());
            session = sessions.selectById(session.getId());
        }
        AssistantExecutionViews.BudgetRevision result = new AssistantExecutionViews.BudgetRevision();
        result.setRunRevision(value(run.getStateRevision()));
        result.setBudgetPolicy(view(run, session, policy, authorization));
        result.setInvalidatedApprovalIds(invalidatedApprovalIds);
        result.setRenewedExpired(renewal);
        command.setStatus("COMPLETED");
        command.setResultJson(JSON.toJSONString(result));
        touchUpdate(command, String.valueOf(userId));
        if (commands.updateById(command) != 1) throw policyDenied("COMMAND_STATE_UNAVAILABLE");
        if ("AUTO".equals(policy.getMode()) && !Boolean.TRUE.equals(run.getStopRequested()))
            dueRunWakeup.wakeAfterCommit(run.getId());
        return result;
    }

    private List<String> invalidateUnsubmittedApprovals(AidRuntimeRun run, Long userId)
    {
        List<AidRuntimeApproval> active = approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .in(AidRuntimeApproval::getStatus, List.of("QUOTED", "ACCEPTED"))
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByAsc(AidRuntimeApproval::getId)
                .last("FOR UPDATE"));
        List<String> invalidated = new ArrayList<>();
        Date now = new Date();
        for (AidRuntimeApproval approval : active)
        {
            boolean quoted = "QUOTED".equals(approval.getStatus()) && approval.getAcceptedAt() == null;
            com.aid.runtime.domain.AidRuntimeRunStep step = steps.selectOne(
                    new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeRunStep>()
                            .eq(com.aid.runtime.domain.AidRuntimeRunStep::getApprovalId, approval.getId())
                            .eq(com.aid.runtime.domain.AidRuntimeRunStep::getDelFlag, "0")
                            .orderByDesc(com.aid.runtime.domain.AidRuntimeRunStep::getId).last("LIMIT 1 FOR UPDATE"));
            if (step != null && !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
            {
                continue;
            }
            approval.setStatus("INVALIDATED");
            approval.setInvalidatedAt(now);
            approval.setStateRevision(value(approval.getStateRevision()) + 1);
            touchUpdate(approval, String.valueOf(userId));
            if (approvals.updateById(approval) != 1) throw policyDenied("APPROVAL_STATE_CHANGED");
            // A replaced, never-accepted MAIN quote has no remaining executable step.
            // Keep operation steps intact: they are reused by the existing operation quote lineage.
            if (step != null && "MAIN_AGENT".equals(step.getStepKind())
                    && "AWAITING_APPROVAL".equals(step.getStatus()) && approval.getAcceptedAt() == null
                    && step.getMediaTaskId() == null && step.getReservationId() == null && step.getChildRunId() == null)
            {
                step.setStatus("CANCELLED_PRETASK");
                step.setErrorMessage("BUDGET_AUTHORIZATION_UPDATED");
                com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(step);
                step.setUpdateBy(String.valueOf(userId));
                step.setUpdateTime(now);
                if (steps.updateById(step) != 1) throw policyDenied("APPROVAL_STATE_CHANGED");
            }
            if (quoted && step != null && "AUTO".equals(executionMode(run))
                    && "CONFIRM_OPERATION_APPROVAL".equals(run.getNextAction())
                    && "AWAITING_APPROVAL".equals(step.getStatus())
                    && step.getReservationId() == null && step.getMediaTaskId() == null && step.getChildRunId() == null)
                retireAutomaticQuote(run, step, approval, String.valueOf(userId));
            invalidated.add(id(approval.getId()));
        }
        return invalidated;
    }

    /** Mode switching retires only a proven unsubmitted quote; the next model reuses all completed work. */
    private void retireAutomaticQuote(AidRuntimeRun run, com.aid.runtime.domain.AidRuntimeRunStep child,
                                     AidRuntimeApproval approval, String operator) {
        if (!Objects.equals(approval.getScopeDigest(), SecureUtil.sha256(approval.getScopeJson())))
            throw policyDenied("OPERATION_SCOPE_CHANGED");
        Long mainId = JSON.parseObject(approval.getScopeJson()).getJSONObject("decision").getLong("mainAgentStepId");
        var main = steps.selectOne(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeRunStep>()
                .eq(com.aid.runtime.domain.AidRuntimeRunStep::getId, mainId)
                .eq(com.aid.runtime.domain.AidRuntimeRunStep::getRunId, run.getId()).last("FOR UPDATE"));
        if (main == null || !"DECISION_READY".equals(main.getStatus())
                || !Objects.equals(main.getPlanRevision(), child.getPlanRevision())) throw policyDenied("MAIN_STEP_CHANGED");
        var mainApproval = approvals.selectById(main.getApprovalId());
        if (mainApproval == null || !Objects.equals(mainApproval.getRunId(), run.getId())
                || !List.of("ACCEPTED", "CONSUMED").contains(mainApproval.getStatus())) throw policyDenied("APPROVAL_STATE_CHANGED");
        main.setStatus("SUCCEEDED");
        com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(main);
        touchUpdate(main, operator);
        steps.updateById(main);
        if (!"CONSUMED".equals(mainApproval.getStatus())) {
            mainApproval.setStatus("CONSUMED");
            mainApproval.setStateRevision(value(mainApproval.getStateRevision()) + 1);
            touchUpdate(mainApproval, operator);
            approvals.updateById(mainApproval);
        }
        child.setStatus("CANCELLED_PRETASK");
        child.setErrorMessage("EXECUTION_MODE_AUTO_REPLAN");
        com.aid.runtime.assistant.AssistantRuntimeStepLifecycle.clearTerminalLease(child);
        touchUpdate(child, operator);
        steps.updateById(child);
        run.setStatus("WAITING_APPROVAL");
        run.setNextAction("CONTINUE_AGENT_REQUIRED");
        run.setStateRevision(value(run.getStateRevision()) + 1);
        touchUpdate(run, operator);
        runs.updateById(run);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordScheduledIteration(Long runId, Long authorizationId, Long authorizationRevision,
                                         String modelCode, String operationKind, BigDecimal quotedUpperBound,
                                         String operator)
    {
        AssistantBudgetOperationScope scope = new AssistantBudgetOperationScope();
        scope.setModelCode(modelCode);
        scope.setOperationKind(operationKind);
        scope.setQuotedUpperBound(quotedUpperBound);
        scope.setCountsAsIteration(true);
        // Main-Agent submission already owns its durable reservation. Revalidate the
        // authorization and count this iteration without comparing unreserved balance again.
        AssistantBudgetOperationAdmission admitted = admitScheduledOperation(
                runId, scope, operator, false, authorizationId, authorizationRevision);
        if (!Objects.equals(admitted.authorizationId(), authorizationId)
                || !Objects.equals(admitted.authorizationRevision(), authorizationRevision))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CHANGED);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertProviderSubmission(Long runId, Long authorizationId, Long authorizationRevision,
                                         String modelCode, String operationKind, BigDecimal authorizedChargeCap)
    {
        AssistantBudgetOperationScope scope = new AssistantBudgetOperationScope();
        scope.setModelCode(modelCode);
        scope.setOperationKind(operationKind);
        scope.setQuotedUpperBound(authorizedChargeCap);
        assertProviderSubmission(runId, authorizationId, authorizationRevision, scope);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantBudgetOperationAdmission admitScheduledOperation(Long runId,
                                                                     AssistantBudgetOperationScope operationScope,
                                                                     String operator)
    {
        return admitScheduledOperation(runId, operationScope, operator, true, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantBudgetOperationAdmission admitScheduledOperation(Long runId,
            AssistantBudgetOperationScope operationScope, String operator, boolean confirmRenewedExecution)
    {
        return admitScheduledOperation(runId, operationScope, operator, true, null, null,
                confirmRenewedExecution);
    }

    private AssistantBudgetOperationAdmission admitScheduledOperation(
            Long runId, AssistantBudgetOperationScope operationScope, String operator,
            boolean requireUnreservedFunds, Long expectedAuthorizationId, Long expectedAuthorizationRevision)
    {
        return admitScheduledOperation(runId, operationScope, operator, requireUnreservedFunds,
                expectedAuthorizationId, expectedAuthorizationRevision, false);
    }

    private AssistantBudgetOperationAdmission admitScheduledOperation(
            Long runId, AssistantBudgetOperationScope operationScope, String operator,
            boolean requireUnreservedFunds, Long expectedAuthorizationId, Long expectedAuthorizationRevision,
            boolean confirmRenewedExecution)
    {
        AidRuntimeBudgetPolicy policy = lockPolicy(runId);
        if (policy == null) throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.POLICY_MISSING);
        if (!requireUnreservedFunds && (!Objects.equals(policy.getAuthorizationId(), expectedAuthorizationId)
                || !Objects.equals(policy.getAuthorizationRevision(), expectedAuthorizationRevision)))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CHANGED);
        Date now = new Date();
        boolean scopeOnly = sceneToolWithoutBudget(runId, operationScope);
        BillingBudgetViews.AuthorizationView authorization = requireOperationalPolicy(
                policy, runId, policy.getAuthorizationId(), policy.getAuthorizationRevision(), now, false,
                confirmRenewedExecution, scopeOnly);
        assertPolicyScope(policy, operationScope, true, true);
        if (scopeOnly) return admission(policy, true);
        if (requireUnreservedFunds) assertNewOperationFunding(authorization, operationScope);
        int iterations = value(policy.getUsedIterations()) + (operationScope.isCountsAsIteration() ? 1 : 0);
        int operations = value(policy.getUsedOperations()) + 1;
        if (iterations > policy.getMaxIterations() || operations > policy.getMaxOperations())
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.COUNT_EXHAUSTED);
        Long renewalRevision = policy.getRenewalConfirmationRevision();
        long beforeRevision = value(policy.getStateRevision());
        if (renewalRevision != null) policy.setRenewalConfirmationRevision(null);
        policy.setUsedIterations(iterations);
        policy.setUsedOperations(operations);
        policy.setStateRevision(value(policy.getStateRevision()) + 1);
        if (iterations >= policy.getMaxIterations() || operations >= policy.getMaxOperations())
            policy.setStatus("EXHAUSTED");
        touchUpdate(policy, operator);
        if (renewalRevision == null) policies.updateById(policy);
        else if (policies.update(policy, new LambdaUpdateWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getId, policy.getId())
                .eq(AidRuntimeBudgetPolicy::getStateRevision, beforeRevision)
                .eq(AidRuntimeBudgetPolicy::getAuthorizationRevision, renewalRevision)
                .eq(AidRuntimeBudgetPolicy::getRenewalConfirmationRevision, renewalRevision)
                .set(AidRuntimeBudgetPolicy::getRenewalConfirmationRevision, null)) != 1)
            throw policyDenied("RENEWAL_STATE_UNAVAILABLE");
        return admission(policy, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantBudgetOperationAdmission assertOperationSchedulable(
            Long runId, AssistantBudgetOperationScope operationScope)
    {
        return assertOperationScope(runId, operationScope, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantBudgetOperationAdmission assertOperationQuotable(
            Long runId, AssistantBudgetOperationScope operationScope)
    {
        return assertOperationScope(runId, operationScope, true);
    }

    private AssistantBudgetOperationAdmission assertOperationScope(
            Long runId, AssistantBudgetOperationScope operationScope, boolean quoteOnly)
    {
        AidRuntimeBudgetPolicy policy = lockPolicy(runId);
        if (policy == null) throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.POLICY_MISSING);
        Date now = new Date();
        boolean scopeOnly = sceneToolWithoutBudget(runId, operationScope);
        BillingBudgetViews.AuthorizationView authorization = requireOperationalPolicy(
                policy, runId, policy.getAuthorizationId(), policy.getAuthorizationRevision(), now, false,
                quoteOnly, scopeOnly);
        assertPolicyScope(policy, operationScope, true, true);
        if (scopeOnly) return admission(policy, true);
        assertNewOperationFunding(authorization, operationScope);
        int iterations = value(policy.getUsedIterations()) + (operationScope.isCountsAsIteration() ? 1 : 0);
        int operations = value(policy.getUsedOperations()) + 1;
        if (iterations > policy.getMaxIterations() || operations > policy.getMaxOperations())
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.COUNT_EXHAUSTED);
        return admission(policy, false);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertProviderSubmission(Long runId, Long authorizationId, Long authorizationRevision,
                                         AssistantBudgetOperationScope operationScope)
    {
        AidRuntimeBudgetPolicy policy = policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, runId).eq(AidRuntimeBudgetPolicy::getDelFlag, "0").last("LIMIT 1"));
        if (policy == null || !Objects.equals(policy.getAuthorizationId(), authorizationId)
                || !Objects.equals(policy.getAuthorizationRevision(), authorizationRevision))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CHANGED);
        Date now = new Date();
        requireOperationalPolicy(policy, runId, authorizationId, authorizationRevision, now, true,
                false, sceneToolWithoutBudget(runId, operationScope));
        assertPolicyScope(policy, operationScope, false, true);
    }

    private boolean sceneToolWithoutBudget(Long runId, AssistantBudgetOperationScope scope)
    {
        if (moduleRegistry == null || scope == null || StrUtil.isBlank(scope.getToolCode())
                || scope.getQuotedUpperBound() == null || scope.getQuotedUpperBound().signum() != 0) return false;
        AidRuntimeRun run = runs.selectById(runId);
        if (run == null) return false;
        return !moduleRegistry.usesExecutionBudget(requireSession(run.getUserId(), run.getSessionId(), false).getModuleCode());
    }

    private AssistantBudgetOperationAdmission admission(AidRuntimeBudgetPolicy policy, boolean scopeOnly)
    {
        return new AssistantBudgetOperationAdmission(policy.getAuthorizationId(), policy.getAuthorizationRevision(),
                policy.getStateRevision(), policy.getMode(), policy.getScopeDigest(), scopeOnly);
    }

    private BillingBudgetViews.AuthorizationView requireOperationalPolicy(
            AidRuntimeBudgetPolicy policy, Long runId, Long authorizationId,
            Long authorizationRevision, Date now, boolean admitted, boolean permitRenewalHold, boolean scopeOnly)
    {
        if (!("ACTIVE".equals(policy.getStatus()) || "EXHAUSTED".equals(policy.getStatus())))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.POLICY_INACTIVE);
        // Nonrenewable identity/revocation must win over recoverable time/count limits.
        // Keep lookup failures unknown rather than reporting an expiry that hides lost authority.
        BillingBudgetViews.AuthorizationView authorization = requireOperationalAuthorization(
                policy, runId, authorizationId, authorizationRevision);
        if (scopeOnly) return authorization;
        if (policy.getExpiresAt() == null || !policy.getExpiresAt().after(now))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.POLICY_EXPIRED);
        if (authorization.getExpiresAt() == null || !authorization.getExpiresAt().after(now)
                || BillingAuthorizationStatus.EXPIRED.name().equals(authorization.getStatus()))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_EXPIRED);
        if (policy.getRenewalConfirmationRevision() != null
                && (!permitRenewalHold || !Objects.equals(policy.getRenewalConfirmationRevision(),
                        policy.getAuthorizationRevision())))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.RENEWAL_CONFIRMATION_REQUIRED);
        if ("EXHAUSTED".equals(policy.getStatus()) && !admitted)
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.COUNT_EXHAUSTED);
        return authorization;
    }

    private BillingBudgetViews.AuthorizationView requireOperationalAuthorization(
            AidRuntimeBudgetPolicy policy, Long runId, Long authorizationId,
            Long authorizationRevision)
    {
        BillingBudgetViews.AuthorizationView authorization = budgets.getAuthorization(authorizationId);
        if (authorization == null)
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_MISSING);
        AidRuntimeRun run = runs.selectById(runId);
        if (run == null || !"0".equals(run.getDelFlag()))
            throw failure(HttpStatus.CONFLICT, "RUNTIME_STATE_UNAVAILABLE", "运行状态待核实", null);
        if (!Objects.equals(authorization.getId(), authorizationId)
                || !Objects.equals(authorization.getRevision(), authorizationRevision)
                || !Objects.equals(authorization.getRuntimeRunId(), runId)
                || !Objects.equals(authorization.getPayerUserId(), run.getUserId())
                || !Objects.equals(policy.getAuthorizationId(), authorizationId)
                || !Objects.equals(policy.getAuthorizationRevision(), authorizationRevision))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CHANGED);
        if (BillingAuthorizationStatus.REVOKED.name().equals(authorization.getStatus()))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_REVOKED);
        if (BillingAuthorizationStatus.CLOSED.name().equals(authorization.getStatus()))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_CLOSED);
        if (!BillingAuthorizationStatus.ACTIVE.name().equals(authorization.getStatus())
                && !BillingAuthorizationStatus.EXPIRED.name().equals(authorization.getStatus()))
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.POLICY_INACTIVE);
        return authorization;
    }

    private void assertNewOperationFunding(BillingBudgetViews.AuthorizationView authorization,
                                           AssistantBudgetOperationScope operationScope)
    {
        BigDecimal upperBound = operationScope == null ? null : operationScope.getQuotedUpperBound();
        if (authorization.getAvailableAmount() == null || upperBound == null)
            throw failure(HttpStatus.CONFLICT, "RUNTIME_STATE_UNAVAILABLE", "运行预算状态待核实", null);
        if (authorization.getAvailableAmount().compareTo(upperBound) < 0)
            throw admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.AUTHORIZATION_FUNDS_EXHAUSTED);
    }

    private CanonicalPolicy canonical(AssistantExecutionRequests.RunBudgetPolicy request,
                                      AidRuntimeRun run, AidRuntimeApproval approval)
    {
        return canonical(request, run, approval, false);
    }

    private CanonicalPolicy canonical(AssistantExecutionRequests.RunBudgetPolicy request,
                                      AidRuntimeRun run, AidRuntimeApproval approval, boolean renewedExpiry)
    {
        if (request == null || !MODES.contains(request.getMode())) invalid("mode");
        if (moduleRegistry != null && moduleRegistry.automaticExecutionRequired(
                requireSession(run.getUserId(), run.getSessionId(), false).getModuleCode())
                && !"AUTO".equals(request.getMode())) invalid("mode");
        BigDecimal absolute = amount(request.getAbsoluteMaxCharge());
        BigDecimal perCall = amount(request.getPerCallMaxCharge());
        if (absolute.signum() < 0 || perCall.signum() < 0 || perCall.compareTo(absolute) > 0) invalid("charge");
        if (approval != null && (approval.getMaxCharge() == null || approval.getMaxCharge().compareTo(perCall) > 0))
            throw policyDenied("CALL_CAP_EXCEEDED");
        Date expiresAt = instant(request.getExpiresAt());
        if (renewedExpiry) expiresAt = new Date(expiresAt.getTime() / 1000L * 1000L);
        Date now = new Date();
        if (!expiresAt.after(now) || expiresAt.getTime() - now.getTime() > MAX_TTL_MILLIS) invalid("expiresAt");
        int maxIterations = bounded(request.getMaxIterations(), 1, 1000, "maxIterations");
        int maxOperations = bounded(request.getMaxOperations(), 1, 10000, "maxOperations");
        List<String> tools = strings(request.getAllowedToolCodes(), 200, "allowedToolCodes");
        List<String> models = strings(request.getAllowedModelCodes(), 100, "allowedModelCodes");
        List<String> capabilities = strings(request.getAllowedCapabilityCodes(), 100, "allowedCapabilityCodes");
        List<String> media = strings(request.getAllowedMediaTypes(), 20, "allowedMediaTypes");
        if (!MEDIA_TYPES.containsAll(media)) invalid("allowedMediaTypes");
        List<String> forbidden = strings(request.getAutomaticForbiddenOperationKinds(), 100,
                "automaticForbiddenOperationKinds");
        if ("AUTO".equals(request.getMode()) && !forbidden.containsAll(MANDATORY_AUTO_FORBIDDEN))
            invalid("automaticForbiddenOperationKinds");
        if (approval != null && (!models.contains(first(approval.getAllowedModelCodesJson()))
                || !capabilities.contains("text.chat") || !media.contains("TEXT")))
            throw policyDenied("INITIAL_CALL_OUTSIDE_SCOPE");
        String targetRefsJson = canonicalTargets(request.getTargetRefs(), run);
        AidAssistantSession session = requireSession(run.getUserId(), run.getSessionId(), false);
        AssistantExecutionViews.RunBudgetScope available = runBudgetScopes.discover(run.getUserId(), session, run);
        if (!Objects.equals(request.getScopeDigest(), available.getScopeDigest())
                || !tools.equals(available.getAllowedToolCodes())
                || !models.equals(available.getAllowedModelCodes())
                || !capabilities.equals(available.getAllowedCapabilityCodes())
                || !media.equals(available.getAllowedMediaTypes())
                || !forbidden.equals(available.getAutomaticForbiddenOperationKinds())
                || !Objects.equals(JSON.toJSONString(JSON.parseArray(targetRefsJson)),
                JSON.toJSONString(available.getTargetRefs())))
            throw policyDenied("AVAILABLE_SCOPE_CHANGED");
        JSONObject scope = new JSONObject();
        scope.put("mode", request.getMode());
        scope.put("absoluteMaxCharge", absolute);
        scope.put("perCallMaxCharge", perCall);
        scope.put("expiresAt", expiresAt.getTime());
        scope.put("maxIterations", maxIterations);
        scope.put("maxOperations", maxOperations);
        scope.put("allowedToolCodes", tools);
        scope.put("allowedModelCodes", models);
        scope.put("allowedCapabilityCodes", capabilities);
        scope.put("allowedMediaTypes", media);
        scope.put("targetRefs", JSON.parseArray(targetRefsJson));
        scope.put("automaticForbiddenOperationKinds", forbidden);
        return new CanonicalPolicy(request.getMode(), absolute, perCall, tools, models, capabilities,
                media, targetRefsJson, forbidden, maxIterations, maxOperations, expiresAt,
                SecureUtil.sha256(scope.toJSONString()));
    }

    private String canonicalTargets(List<AssistantRequests.ContextRef> requested, AidRuntimeRun run)
    {
        List<AssistantRequests.ContextRef> targetValues = requested == null ? List.of() : requested;
        contextLimits.checkResolvedIdentityBudget(targetValues);
        AidAssistantSession session = requireSession(run.getUserId(), run.getSessionId(), false);
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                contextManifests.requirePinned(run.getUserId(), session, run);
        if (targetValues.stream().anyMatch(Objects::isNull)) throw policyDenied("TARGET_SCOPE_CHANGED");
        List<String> targets = targetValues.stream().map(AssistantRuntimeContextManifestService::canonicalTarget)
                .map(value -> value.toJSONString()).sorted().toList();
        List<String> sources = JSON.parseArray(manifest.targetRefsJson()).stream()
                .map(JSON::toJSONString).sorted().toList();
        if (!targets.equals(sources)) throw policyDenied("TARGET_SCOPE_CHANGED");
        return manifest.targetRefsJson();
    }

    private void assertCall(AidRuntimeBudgetPolicy policy, BillingBudgetViews.AuthorizationView authorization,
                            BigDecimal upperBound, String modelCode, String operationKind)
    {
        assertPolicyScope(policy, modelCode, operationKind, upperBound);
        if (authorization.getAvailableAmount() == null || upperBound == null
                || authorization.getAvailableAmount().compareTo(upperBound) < 0)
            throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_EXCEEDED", "\u8fd0\u884c\u9884\u7b97\u4e0d\u8db3",
                    Map.of("remaining", Objects.toString(authorization.getAvailableAmount(), "0"),
                            "required", Objects.toString(upperBound, "0")));
    }

    private void assertPolicyScope(AidRuntimeBudgetPolicy policy, String modelCode,
                                   String operationKind, BigDecimal upperBound)
    {
        AssistantBudgetOperationScope scope = new AssistantBudgetOperationScope();
        scope.setModelCode(modelCode);
        scope.setOperationKind(operationKind);
        scope.setQuotedUpperBound(upperBound);
        assertPolicyScope(policy, scope, false);
    }

    private void assertPolicyScope(AidRuntimeBudgetPolicy policy, AssistantBudgetOperationScope scope,
                                   boolean scheduling)
    {
        assertPolicyScope(policy, scope, scheduling, false);
    }

    private void assertPolicyScope(AidRuntimeBudgetPolicy policy, AssistantBudgetOperationScope scope,
                                   boolean scheduling, boolean operational)
    {
        if (scope == null || scope.getQuotedUpperBound() == null
                || scope.getQuotedUpperBound().compareTo(policy.getPerCallMaxCharge()) > 0)
            throw denied("CALL_CAP_EXCEEDED", operational);
        if (StrUtil.isNotBlank(scope.getModelCode())
                && !array(policy.getAllowedModelCodesJson()).contains(scope.getModelCode()))
            throw denied("MODEL_DENIED", operational);
        if (StrUtil.isNotBlank(scope.getToolCode())
                && !array(policy.getAllowedToolCodesJson()).contains(scope.getToolCode()))
            throw denied("TOOL_DENIED", operational);
        if (!array(policy.getAllowedCapabilityCodesJson()).containsAll(nullSafe(scope.getCapabilityCodes())))
            throw denied("CAPABILITY_DENIED", operational);
        if (!array(policy.getAllowedMediaTypesJson()).containsAll(nullSafe(scope.getMediaTypes())))
            throw denied("MEDIA_TYPE_DENIED", operational);
        assertTargets(policy, scope.getTargetRefs(), operational);
        if ("AUTO".equals(policy.getMode())
                && array(policy.getAutomaticForbiddenOperationKindsJson()).contains(scope.getOperationKind()))
            throw denied("AUTO_OPERATION_FORBIDDEN", operational);
        if (scheduling && StrUtil.isBlank(scope.getOperationKind()))
            throw denied("OPERATION_KIND_REQUIRED", operational);
    }

    private void assertTargets(AidRuntimeBudgetPolicy policy, List<AssistantRequests.ContextRef> requested,
                               boolean operational)
    {
        if (requested == null || requested.isEmpty()) return;
        // Persisted policy/context JSON is durable evidence. Corruption or decoding failures are
        // infrastructure-unknown, never a definitive runtime admission rejection.
        Set<JSONObject> allowed = targetObjects(policy.getTargetRefsJson(), false);
        Set<JSONObject> targets = targetObjects(JSON.toJSONString(requested), operational);
        if (allowed.containsAll(targets)) return;
        AidRuntimeRun run = runs.selectById(policy.getRunId());
        if (run == null || !Objects.equals(run.getId(), policy.getRunId()) || run.getUserId() == null)
        {
            if (operational)
                throw failure(HttpStatus.CONFLICT, "RUNTIME_STATE_UNAVAILABLE", "运行状态待核实", null);
            throw policyDenied("TARGET_DENIED");
        }
        AidAssistantSession session = requireSession(run.getUserId(), run.getSessionId(), false);
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                contextManifests.requirePinned(run.getUserId(), session, run);
        // This proof never changes the stored policy, amounts, lifetime, models or Tool permissions.
        if (!allowed.equals(targetObjects(manifest.targetRefsJson(), false))
                || Objects.equals(manifest.executionDigest(), manifest.digest())
                || !targetObjects(manifest.executionTargetRefsJson(), false).containsAll(targets))
            throw denied("TARGET_DENIED", operational);
    }

    private Set<JSONObject> targetObjects(String value, boolean operational)
    {
        JSONArray rows = JSON.parseArray(value);
        if (rows == null || rows.size() > contextLimits.maxReferences() + 1)
            throw denied("TARGET_DENIED", operational);
        Set<JSONObject> result = new LinkedHashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.getJSONObject(index);
            if (row == null) throw denied("TARGET_DENIED", operational);
            // Manifest IDs are strings; typed ContextRef IDs are Longs. Compare the same exact integral identity.
            for (String field : List.of("workspaceId", "resourceId", "projectId", "episodeId", "scriptId", "documentId", "documentSessionId")) {
                if (!row.containsKey(field)) continue;
                Object raw = row.get(field);
                if (!(raw instanceof String) && !(raw instanceof Number)) throw denied("TARGET_DENIED", operational);
                String identity = String.valueOf(raw);
                if (!identity.matches("episodeId".equals(field) ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"))
                    throw denied("TARGET_DENIED", operational);
                try { Long.parseLong(identity); } catch (NumberFormatException invalid) { throw denied("TARGET_DENIED", operational); }
                row.put(field, identity);
            }
            if (!result.add(row)) throw denied("TARGET_DENIED", operational);
        }
        return result;
    }

    private AssistantServiceException denied(String reason, boolean operational)
    {
        return operational
                ? admissionDenied(AssistantBudgetAdmissionRejectedException.Reason.valueOf(reason))
                : policyDenied(reason);
    }

    private List<String> nullSafe(List<String> values)
    {
        return values == null ? List.of() : values;
    }

    private void requireAuthorization(AidRuntimeBudgetPolicy policy, BillingBudgetViews.AuthorizationView authorization,
                                      AidRuntimeRun run, Long userId)
    {
        if (authorization == null || !Objects.equals(authorization.getId(), policy.getAuthorizationId())
                || !Objects.equals(authorization.getRevision(), policy.getAuthorizationRevision())
                || !Objects.equals(authorization.getRuntimeRunId(), run.getId())
                || !Objects.equals(authorization.getPayerUserId(), userId))
            throw policyDenied("AUTHORIZATION_CHANGED");
    }

    private void requireActive(AidRuntimeBudgetPolicy policy, Date now)
    {
        if (!"ACTIVE".equals(policy.getStatus()) || policy.getExpiresAt() == null || !policy.getExpiresAt().after(now))
            throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_EXPIRED", "\u8fd0\u884c\u9884\u7b97\u5df2\u5931\u6548",
                    Map.of("expiresAt", policy.getExpiresAt() == null ? "" : policy.getExpiresAt().toInstant().toString()));
    }

    private void requireRevisable(AidRuntimeBudgetPolicy policy, Date now)
    {
        if (!("ACTIVE".equals(policy.getStatus()) || "EXHAUSTED".equals(policy.getStatus()))
                || policy.getExpiresAt() == null || !policy.getExpiresAt().after(now))
            throw failure(HttpStatus.CONFLICT, "RUN_BUDGET_EXPIRED", "\u8fd0\u884c\u9884\u7b97\u5df2\u5931\u6548",
                    Map.of("expiresAt", policy.getExpiresAt() == null ? "" : policy.getExpiresAt().toInstant().toString()));
    }

    private void apply(AidRuntimeBudgetPolicy target, CanonicalPolicy value)
    {
        target.setMode(value.mode());
        target.setAbsoluteMaxCharge(value.absoluteMaxCharge());
        target.setPerCallMaxCharge(value.perCallMaxCharge());
        target.setAllowedToolCodesJson(JSON.toJSONString(value.allowedToolCodes()));
        target.setAllowedModelCodesJson(JSON.toJSONString(value.allowedModelCodes()));
        target.setAllowedCapabilityCodesJson(JSON.toJSONString(value.allowedCapabilityCodes()));
        target.setAllowedMediaTypesJson(JSON.toJSONString(value.allowedMediaTypes()));
        target.setTargetRefsJson(value.targetRefsJson());
        target.setAutomaticForbiddenOperationKindsJson(JSON.toJSONString(value.automaticForbiddenOperationKinds()));
        target.setMaxIterations(value.maxIterations());
        target.setMaxOperations(value.maxOperations());
        target.setExpiresAt(value.expiresAt());
        target.setScopeDigest(value.scopeDigest());
    }

    private AssistantExecutionViews.BudgetPolicy view(AidRuntimeRun run, AidAssistantSession session,
                                                       AidRuntimeBudgetPolicy policy,
                                                       BillingBudgetViews.AuthorizationView authorization)
    {
        AssistantExecutionViews.BudgetPolicy result = new AssistantExecutionViews.BudgetPolicy();
        result.setRunId(id(run.getId()));
        result.setRunRevision(value(run.getStateRevision()));
        result.setPolicyRevision(value(policy.getStateRevision()));
        result.setAuthorizationId(id(policy.getAuthorizationId()));
        result.setAuthorizationRevision(value(policy.getAuthorizationRevision()));
        result.setMode(policy.getMode());
        Date now = new Date();
        boolean timedOut = policy.getExpiresAt() != null && !policy.getExpiresAt().after(now)
                || authorization.getExpiresAt() != null && !authorization.getExpiresAt().after(now);
        result.setStatus(timedOut && ("ACTIVE".equals(policy.getStatus()) || "EXHAUSTED".equals(policy.getStatus()))
                ? "EXPIRED" : policy.getStatus());
        result.setAbsoluteMaxCharge(decimal(policy.getAbsoluteMaxCharge()));
        result.setPerCallMaxCharge(decimal(policy.getPerCallMaxCharge()));
        result.setReserved(decimal(authorization.getReservedAmount()));
        result.setSettled(decimal(authorization.getSettledAmount()));
        result.setRemaining(decimal(authorization.getAvailableAmount()));
        result.setCurrencyUnit(authorization.getCurrencyUnit());
        result.setPrecisionScale(authorization.getPrecisionScale() == null ? 4 : authorization.getPrecisionScale());
        result.setMaxIterations(policy.getMaxIterations());
        result.setUsedIterations(value(policy.getUsedIterations()));
        result.setMaxOperations(policy.getMaxOperations());
        result.setUsedOperations(value(policy.getUsedOperations()));
        Date effectiveExpiry = policy.getExpiresAt() == null ? authorization.getExpiresAt()
                : authorization.getExpiresAt() == null || policy.getExpiresAt().before(authorization.getExpiresAt())
                ? policy.getExpiresAt() : authorization.getExpiresAt();
        result.setExpiresAt(effectiveExpiry == null ? null : effectiveExpiry.toInstant().toString());
        result.setAllowedToolCodes(array(policy.getAllowedToolCodesJson()));
        result.setAllowedModelCodes(array(policy.getAllowedModelCodesJson()));
        result.setAllowedCapabilityCodes(array(policy.getAllowedCapabilityCodesJson()));
        result.setAllowedMediaTypes(array(policy.getAllowedMediaTypesJson()));
        result.setTargetRefs(JSON.parseArray(policy.getTargetRefsJson()));
        result.setAutomaticForbiddenOperationKinds(array(policy.getAutomaticForbiddenOperationKindsJson()));
        result.setScopeDigest(policy.getScopeDigest());
        if (RuntimeRunLifecycle.isTerminal(run.getStatus()))
        {
            // Historical policy is a persisted receipt, not a request to authorize current tools.
            result.setAvailableRunBudgetScope(null);
            result.setScopeState("HISTORICAL");
        }
        else
        {
            AssistantExecutionViews.RunBudgetScope available = runBudgetScopes.discover(run.getUserId(), session, run);
            result.setAvailableRunBudgetScope(available);
            result.setScopeState(policyScopeMatches(policy, available) ? "CURRENT" : "CONTEXT_CHANGED");
        }
        result.setRenewalNeedsConfirmation(policy.getRenewalConfirmationRevision() != null);
        String blocked = renewalBlockReason(run, session, policy, authorization, now);
        if (blocked == null)
        {
            if (budgets.hasUnresolvedReservations(authorization.getId())) blocked = "RESERVATION_PENDING";
            else blocked = quiescentRuns.inspectBudgetRenewal(run.getUserId(), run, policy, false).blockReason();
        }
        result.setCanRenew(blocked == null);
        result.setRenewBlockReason(blocked);
        result.setLastEventSeq(value(session.getLastEventSeq()));
        return result;
    }

    private boolean policyScopeMatches(AidRuntimeBudgetPolicy policy,
                                       AssistantExecutionViews.RunBudgetScope available)
    {
        return array(policy.getAllowedToolCodesJson()).equals(available.getAllowedToolCodes())
                && array(policy.getAllowedModelCodesJson()).equals(available.getAllowedModelCodes())
                && array(policy.getAllowedCapabilityCodesJson()).equals(available.getAllowedCapabilityCodes())
                && array(policy.getAllowedMediaTypesJson()).equals(available.getAllowedMediaTypes())
                && array(policy.getAutomaticForbiddenOperationKindsJson())
                .equals(available.getAutomaticForbiddenOperationKinds())
                && Objects.equals(JSON.toJSONString(JSON.parseArray(policy.getTargetRefsJson())),
                JSON.toJSONString(available.getTargetRefs()));
    }

    private AidRuntimeBudgetPolicy lockPolicy(Long runId)
    {
        return policies.selectOne(new LambdaQueryWrapper<AidRuntimeBudgetPolicy>()
                .eq(AidRuntimeBudgetPolicy::getRunId, runId).eq(AidRuntimeBudgetPolicy::getDelFlag, "0")
                .last("LIMIT 1 FOR UPDATE"));
    }

    static String renewalBlockReason(AidRuntimeRun run, AidAssistantSession session,
                                     AidRuntimeBudgetPolicy policy, BillingBudgetViews.AuthorizationView authorization,
                                     Date now)
    {
        if (RuntimeRunLifecycle.isTerminal(run.getStatus())) return "RUN_TERMINAL";
        if (!Objects.equals(session.getActiveRuntimeRunId(), run.getId())) return "RUN_NOT_ACTIVE";
        if (Boolean.TRUE.equals(run.getStopRequested())) return "STOP_REQUESTED";
        if (!Set.of("WAITING_APPROVAL", "WAITING_INPUT", "PAUSED").contains(Objects.toString(run.getStatus(), ""))) return "RUN_BUSY";
        if (!("ACTIVE".equals(policy.getStatus()) || "EXHAUSTED".equals(policy.getStatus()))) return "POLICY_INACTIVE";
        if (!("ACTIVE".equals(authorization.getStatus()) || "EXHAUSTED".equals(authorization.getStatus()))) return "AUTHORIZATION_INACTIVE";
        if (policy.getExpiresAt() == null || authorization.getExpiresAt() == null
                || authorization.getSettledAmount() == null || authorization.getSettledAmount().signum() < 0
                || authorization.getReservedAmount() == null || authorization.getReservedAmount().signum() < 0
                || authorization.getRevision() == null || authorization.getStateRevision() == null
                || policy.getUsedIterations() == null || policy.getUsedIterations() < 0
                || policy.getUsedOperations() == null || policy.getUsedOperations() < 0
                || policy.getRenewalConfirmationRevision() != null
                && !Objects.equals(policy.getRenewalConfirmationRevision(), policy.getAuthorizationRevision()))
            return "RENEWAL_STATE_UNAVAILABLE";
        if (authorization.getExpiresAt().after(now) && policy.getExpiresAt().after(now)) return "NOT_EXPIRED";
        if (authorization.getReservedAmount().signum() != 0) return "RESERVATION_PENDING";
        return null;
    }

    private AssistantServiceException renewalDenied(String reason)
    {
        return failure(HttpStatus.CONFLICT, "RUN_BUDGET_RENEWAL_BLOCKED", "当前授权需先核实", Map.of("reason", reason));
    }

    private AidRuntimeRun requireOwnedRun(Long userId, Long runId, boolean lock)
    {
        AidRuntimeRun run = lock ? runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId).eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE")) : runs.selectById(runId);
        if (run == null || !"0".equals(run.getDelFlag()) || !Objects.equals(run.getUserId(), userId))
            throw failure(HttpStatus.NOT_FOUND, "RUNTIME_RUN_NOT_FOUND", "\u8fd0\u884c\u4e0d\u5b58\u5728", id(runId));
        return run;
    }

    private AidAssistantSession requireSession(Long userId, Long sessionId, boolean lock)
    {
        AidAssistantSession session = lock ? sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, sessionId).eq(AidAssistantSession::getDelFlag, "0").last("FOR UPDATE"))
                : sessions.selectById(sessionId);
        if (session == null || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(session.getUserId(), userId) || !"0".equals(session.getDelFlag()))
            throw failure(HttpStatus.NOT_FOUND, "ASSISTANT_SESSION_NOT_FOUND", "\u4f1a\u8bdd\u4e0d\u5b58\u5728", id(sessionId));
        return session;
    }

    private void verifyScope(Long userId, AidRuntimeRun run)
    {
        try
        {
            AidAssistantSession session = requireSession(userId, run.getSessionId(), false);
            contextManifests.requirePinned(userId, session, run);
        }
        catch (AssistantServiceException known) { throw known; }
        catch (RuntimeException invalid) { throw policyDenied("CONTEXT_UNAVAILABLE"); }
    }

    private AidRuntimeCommand replay(Long userId, Long sessionId, String key, String digest)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = commands.selectOne(new LambdaQueryWrapper<AidRuntimeCommand>()
                .eq(AidRuntimeCommand::getActorId, userId).eq(AidRuntimeCommand::getSessionId, sessionId)
                .eq(AidRuntimeCommand::getClientRequestId, key).last("LIMIT 1"));
        if (command == null) return null;
        if (!Objects.equals(command.getPayloadDigest(), digest))
            throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_PAYLOAD_CONFLICT", "\u8bf7\u6c42\u5185\u5bb9\u51b2\u7a81", Map.of("key", key));
        if (!"COMPLETED".equals(command.getStatus()) || StrUtil.isBlank(command.getResultJson()))
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001", Map.of("key", key));
        return command;
    }

    private AidRuntimeCommand startCommand(Long userId, Long sessionId, String key, String digest)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = new AidRuntimeCommand();
        command.setActorId(userId);
        command.setSessionId(sessionId);
        command.setClientRequestId(key);
        command.setPayloadDigest(digest);
        command.setCommandType("BUDGET_REVISE");
        command.setStatus("PROCESSING");
        command.setCreateBy(String.valueOf(userId));
        command.setCreateTime(new Date());
        command.setUpdateBy(String.valueOf(userId));
        command.setUpdateTime(new Date());
        try { commands.insert(command); }
        catch (DuplicateKeyException duplicate)
        {
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001", Map.of("key", key));
        }
        return command;
    }

    private void appendEvent(AidAssistantSession session, AidRuntimeRun run,
                             AidRuntimeBudgetPolicy policy, String causationId)
    {
        Date now = new Date();
        session.setLastEventSeq(value(session.getLastEventSeq()) + 1);
        touchUpdate(session, String.valueOf(run.getUserId()));
        sessions.updateById(session);
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(run.getId());
        event.setSeq(session.getLastEventSeq());
        event.setEventType("budget.revised");
        event.setEntityId(id(policy.getId()));
        event.setEntityRevision(policy.getStateRevision());
        event.setCausationId(causationId);
        event.setPayloadJson(JSON.toJSONString(Map.of("policyRevision", policy.getStateRevision(),
                "authorizationRevision", policy.getAuthorizationRevision(), "mode", policy.getMode())));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        events.insert(event);
        realtimeEventPublisher.publishAfterCommit(event);
    }

    private List<String> strings(List<String> values, int limit, String field)
    {
        if (values == null || values.size() > limit) invalid(field);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values)
        {
            if (StrUtil.isBlank(value) || value.length() > 100 || !unique.add(value)) invalid(field);
        }
        List<String> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }

    private List<String> array(String json)
    {
        JSONArray values = JSON.parseArray(json);
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) result.add(values.getString(index));
        return result;
    }

    private String first(String json)
    {
        List<String> values = array(json);
        return values.isEmpty() ? null : values.get(0);
    }

    private BigDecimal amount(BigDecimal value)
    {
        if (value == null || value.signum() < 0 || value.precision() - value.scale() > 10) invalid("charge");
        try { return value.setScale(4, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw policyDenied("CHARGE_SCALE_INVALID"); }
    }

    private Date instant(String value)
    {
        try { return Date.from(Instant.parse(value)); }
        catch (DateTimeParseException invalid) { throw policyDenied("EXPIRY_INVALID"); }
    }

    private int bounded(Integer value, int min, int max, String field)
    {
        if (value == null || value < min || value > max) invalid(field);
        return value;
    }

    private void invalid(String field)
    {
        throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "RUN_BUDGET_INVALID", "\u8fd0\u884c\u9884\u7b97\u65e0\u6548", Map.of("field", field));
    }

    private AssistantServiceException policyDenied(String reason)
    {
        return failure(HttpStatus.CONFLICT, "RUN_BUDGET_SCOPE_DENIED",
                "\u64cd\u4f5c\u8d85\u51fa\u5df2\u6279\u51c6\u8303\u56f4", Map.of("reason", reason));
    }

    private AssistantBudgetAdmissionRejectedException admissionDenied(
            AssistantBudgetAdmissionRejectedException.Reason reason)
    {
        return new AssistantBudgetAdmissionRejectedException(reason);
    }

    private AssistantServiceException conflict(AidRuntimeRun run, AidAssistantSession session)
    {
        return failure(HttpStatus.CONFLICT, "RUNTIME_REVISION_CONFLICT", "\u72b6\u6001\u5df2\u66f4\u65b0",
                Map.of("runRevision", value(run.getStateRevision()), "lastEventSeq", value(session.getLastEventSeq())));
    }

    private AssistantServiceException failure(HttpStatus status, String code, String message, Object details)
    {
        return new AssistantServiceException(status.value(), code, message, details);
    }

    private void touchCreate(com.aid.common.core.domain.BaseEntity value, String actor)
    {
        value.setCreateBy(actor); value.setCreateTime(new Date()); touchUpdate(value, actor);
    }

    private void touchUpdate(com.aid.common.core.domain.BaseEntity value, String actor)
    {
        value.setUpdateBy(actor); value.setUpdateTime(new Date());
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private int value(Integer value) { return value == null ? 0 : value; }
    private String id(Long value) { return value == null ? null : String.valueOf(value); }
    private String decimal(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.UNNECESSARY).toPlainString(); }

    private record CanonicalPolicy(String mode, BigDecimal absoluteMaxCharge, BigDecimal perCallMaxCharge,
                                   List<String> allowedToolCodes, List<String> allowedModelCodes,
                                   List<String> allowedCapabilityCodes, List<String> allowedMediaTypes,
                                   String targetRefsJson, List<String> automaticForbiddenOperationKinds,
                                   int maxIterations, int maxOperations, Date expiresAt, String scopeDigest) { }
}
