package com.aid.assistant.controller;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.dto.AssistantRealtimeRequests;
import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.service.AssistantPlanningService;
import com.aid.assistant.service.AssistantBudgetPolicyService;
import com.aid.assistant.realtime.AssistantRealtimeService;
import com.aid.assistant.vo.AssistantViews;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionConfirmCommand;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionConfirmation;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User assistant persistence and control API. */
@Slf4j
@RestController
@RequestMapping("/api/user/assistant")
public class AssistantController
{
    @Resource
    private AssistantCoreService assistantCoreService;
    @Resource
    private AssistantPlanningService assistantPlanningService;
    @Resource
    private AssistantBudgetPolicyService assistantBudgetPolicyService;
    @Resource
    private AssistantPlanExecutionService assistantPlanExecutionService;
    @Resource
    private AssistantRealtimeService assistantRealtimeService;

    @Resource
    private com.aid.assistant.realtime.AssistantReasoningHistoryService reasoningHistory;

    @PostMapping("/reasoning/read")
    public AjaxResult reasoningHistory(@Valid @RequestBody com.aid.assistant.realtime.AssistantReasoningHistoryService.Read request) {
        return AjaxResult.success(reasoningHistory.read(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/capabilities")
    public AjaxResult capabilities(@Valid @RequestBody AssistantRequests.Capabilities request)
    {
        return AjaxResult.success(assistantCoreService.capabilities(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/session/open")
    public AjaxResult openSession(@Valid @RequestBody AssistantRequests.SessionOpen request)
    {
        return AjaxResult.success(assistantCoreService.openSession(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/session/page")
    public AjaxResult pageSessions(@Valid @RequestBody AssistantRequests.SessionPage request)
    {
        return AjaxResult.success(assistantCoreService.pageSessions(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/session/detail")
    public AjaxResult sessionDetail(@Valid @RequestBody AssistantRequests.SessionDetail request)
    {
        return AjaxResult.success(assistantCoreService.sessionDetail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/realtime/ticket")
    @Operation(summary = "签发助手实时连接票据",
            description = "一次性票据仅授权当前用户连接指定助手会话，并从 afterSeq 恢复持久事件")
    public AjaxResult realtimeTicket(@Valid @RequestBody AssistantRealtimeRequests.Ticket request)
    {
        return AjaxResult.success(assistantRealtimeService.createTicket(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/message/send")
    public AjaxResult sendMessage(@Valid @RequestBody AssistantRequests.MessageSend request)
    {
        return AjaxResult.success(assistantCoreService.sendMessage(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/context/refresh")
    public AjaxResult refreshContext(@Valid @RequestBody AssistantRequests.ContextRefresh request)
    {
        return AjaxResult.success(assistantCoreService.refreshContext(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/planning/quote")
    public AjaxResult quotePlanning(@Valid @RequestBody AssistantExecutionRequests.PlanningQuote request)
    {
        return AjaxResult.success(assistantPlanningService.quotePlanning(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/approval/confirm")
    public AjaxResult confirmApproval(@Valid @RequestBody AssistantExecutionRequests.ApprovalConfirm request)
    {
        return AjaxResult.success(assistantPlanningService.confirmApproval(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/approval/detail")
    public AjaxResult approvalDetail(@Valid @RequestBody AssistantExecutionRequests.ApprovalDetail request)
    {
        return AjaxResult.success(assistantPlanningService.approvalDetail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/plan/confirm")
    public AjaxResult confirmPlan(@Valid @RequestBody AssistantExecutionRequests.PlanConfirm request)
    {
        return AjaxResult.success(assistantCoreService.confirmPlan(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/operation/approval/confirm")
    public AjaxResult confirmOperationApproval(
            @Valid @RequestBody AssistantExecutionRequests.OperationApprovalConfirm request)
    {
        AssistantPlanExecutionConfirmation confirmed = assistantPlanExecutionService.confirm(
                new AssistantPlanExecutionConfirmCommand(SecurityUtils.getUserId(), request.getRunId(),
                        request.getApprovalId(), request.getExpectedRunRevision(),
                        request.getExpectedApprovalRevision(), request.getClientRequestId(),
                        Boolean.TRUE.equals(request.getConfirmRenewedExecution())));
        AssistantExecutionViews.OperationApprovalConfirmed result = new AssistantExecutionViews.OperationApprovalConfirmed();
        result.setRunId(String.valueOf(confirmed.runId()));
        result.setRunRevision(confirmed.runRevision());
        result.setApprovalId(String.valueOf(confirmed.approvalId()));
        result.setApprovalRevision(confirmed.approvalRevision());
        result.setStepId(String.valueOf(confirmed.childStepId()));
        result.setReservationId(confirmed.reservationId() == null ? null : String.valueOf(confirmed.reservationId()));
        result.setStatus(confirmed.status());
        result.setNextAction(confirmed.nextAction());
        result.setLastEventSeq(confirmed.lastEventSeq());
        return AjaxResult.success(result);
    }

    @PostMapping("/budget/detail")
    public AjaxResult budgetDetail(@Valid @RequestBody AssistantExecutionRequests.BudgetDetail request)
    {
        return AjaxResult.success(assistantBudgetPolicyService.detail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/budget/revise")
    public AjaxResult reviseBudget(@Valid @RequestBody AssistantExecutionRequests.BudgetRevise request)
    {
        return AjaxResult.success(assistantBudgetPolicyService.revise(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/input/draft/save")
    public AjaxResult saveDraft(@Valid @RequestBody AssistantRequests.DraftSave request)
    {
        return AjaxResult.success(assistantCoreService.saveDraft(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/input/view/save")
    public AjaxResult saveView(@Valid @RequestBody AssistantRequests.ViewSave request)
    {
        return AjaxResult.success(assistantCoreService.saveView(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/input/submit")
    public AjaxResult submitInput(@Valid @RequestBody AssistantRequests.InputSubmit request)
    {
        return AjaxResult.success(assistantCoreService.submitInput(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/input/suspend")
    public AjaxResult suspendInput(@Valid @RequestBody AssistantRequests.InputSuspend request)
    {
        return AjaxResult.success(assistantCoreService.suspendInput(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/run/pause")
    public AjaxResult pauseRun(@Valid @RequestBody AssistantRequests.RunControl request)
    {
        return AjaxResult.success(assistantCoreService.pauseRun(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/run/resume")
    public AjaxResult resumeRun(@Valid @RequestBody AssistantRequests.RunControl request)
    {
        return AjaxResult.success(assistantCoreService.resumeRun(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/run/stop")
    public AjaxResult stopRun(@Valid @RequestBody AssistantRequests.RunControl request)
    {
        return AjaxResult.success(assistantCoreService.stopRun(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/run/detail")
    public AjaxResult runDetail(@Valid @RequestBody AssistantRequests.RunDetail request)
    {
        return AjaxResult.success(assistantCoreService.runDetail(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/run/step/page")
    public AjaxResult pageRunSteps(@Valid @RequestBody AssistantRequests.RunStepPage request)
    {
        return AjaxResult.success(assistantCoreService.pageRunSteps(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/effect/resolve")
    public AjaxResult resolveEffect(@Valid @RequestBody AssistantRequests.EffectResolve request)
    {
        return AjaxResult.success(assistantCoreService.resolveEffect(SecurityUtils.getUserId(), request));
    }

    @PostMapping("/session/events/page")
    public AjaxResult pageEvents(@Valid @RequestBody AssistantRequests.EventPage request)
    {
        return AjaxResult.success(assistantCoreService.pageEvents(SecurityUtils.getUserId(), request));
    }

    @ExceptionHandler(AssistantServiceException.class)
    public ResponseEntity<AjaxResult> handleAssistantFailure(AssistantServiceException exception)
    {
        log.info("assistant request rejected, code={}", exception.getErrorCode());
        AssistantViews.Error error = new AssistantViews.Error(exception.getErrorCode(), exception.getDetails());
        return ResponseEntity.status(exception.getHttpStatus())
                .body(AjaxResult.error(exception.getHttpStatus(), exception.getMessage()).put("data", error));
    }
    @PostMapping("/operation/quote")
    public AjaxResult quoteOperation(@Valid @RequestBody AssistantExecutionRequests.OperationQuote request) {
        return AjaxResult.success(assistantPlanExecutionService.quote(
                new com.aid.runtime.assistant.execution.AssistantPlanExecutionQuoteCommand(SecurityUtils.getUserId(),
                        request.getRunId(),request.getApprovalId(),request.getExpectedRunRevision(),
                        request.getExpectedApprovalRevision(),request.getClientRequestId(),request.getReferenceRevalidationToken())));
    }
}
