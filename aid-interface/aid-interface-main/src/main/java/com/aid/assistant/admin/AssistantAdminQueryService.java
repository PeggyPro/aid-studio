package com.aid.assistant.admin;

import com.aid.assistant.admin.AssistantAdminRequests.BudgetQuery;
import com.aid.assistant.admin.AssistantAdminRequests.InputQuery;
import com.aid.assistant.admin.AssistantAdminRequests.RunQuery;
import com.aid.assistant.admin.AssistantAdminRequests.ToolQuery;
import com.aid.assistant.admin.AssistantAdminViews.BudgetAuthorizationDetail;
import com.aid.assistant.admin.AssistantAdminViews.BudgetAuthorizationSummary;
import com.aid.assistant.admin.AssistantAdminViews.InputRequestDetail;
import com.aid.assistant.admin.AssistantAdminViews.InputRequestSummary;
import com.aid.assistant.admin.AssistantAdminViews.PageView;
import com.aid.assistant.admin.AssistantAdminViews.RunDetail;
import com.aid.assistant.admin.AssistantAdminViews.RunSummary;
import com.aid.assistant.admin.AssistantAdminViews.ToolCatalogItem;

/** Read-only public Assistant governance queries. */
public interface AssistantAdminQueryService
{
    PageView<RunSummary> listRuns(RunQuery request);

    RunDetail getRunDetail(Long runId);

    PageView<ToolCatalogItem> listTools(ToolQuery request);

    PageView<BudgetAuthorizationSummary> listBudgets(BudgetQuery request);

    BudgetAuthorizationDetail getBudgetDetail(Long authorizationId);

    PageView<InputRequestSummary> listInputs(InputQuery request);

    InputRequestDetail getInputDetail(Long requestId);
}
