package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.runtime.assistant.RuntimeLease;

public interface AssistantPlanningService
{
    AssistantExecutionViews.PlanningApproval quotePlanning(
            Long userId, AssistantExecutionRequests.PlanningQuote request);

    AssistantExecutionViews.ApprovalConfirmed confirmApproval(
            Long userId, AssistantExecutionRequests.ApprovalConfirm request);

    AssistantExecutionViews.PlanningApproval approvalDetail(
            Long userId, AssistantExecutionRequests.ApprovalDetail request);
    boolean advanceAutomaticText(Long runId, RuntimeLease lease);
}
