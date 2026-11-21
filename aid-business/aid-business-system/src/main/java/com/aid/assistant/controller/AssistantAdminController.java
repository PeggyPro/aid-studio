package com.aid.assistant.controller;

import com.aid.assistant.admin.AssistantAdminQueryService;
import com.aid.assistant.admin.AssistantAdminRequests.BudgetDetailRequest;
import com.aid.assistant.admin.AssistantAdminRequests.BudgetQuery;
import com.aid.assistant.admin.AssistantAdminRequests.InputDetailRequest;
import com.aid.assistant.admin.AssistantAdminRequests.InputQuery;
import com.aid.assistant.admin.AssistantAdminRequests.RunDetailRequest;
import com.aid.assistant.admin.AssistantAdminRequests.RunQuery;
import com.aid.assistant.admin.AssistantAdminRequests.ToolQuery;
import com.aid.common.core.domain.AjaxResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only public Assistant governance endpoints for the management console. */
@RestController
@RequestMapping("/aid/assistant/admin")
public class AssistantAdminController
{
    private final AssistantAdminQueryService queryService;

    public AssistantAdminController(AssistantAdminQueryService queryService)
    {
        this.queryService = queryService;
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:run:list')")
    @PostMapping("/run/list")
    public AjaxResult runList(@RequestBody RunQuery request)
    {
        return AjaxResult.success(queryService.listRuns(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:run:query')")
    @PostMapping("/run/detail")
    public AjaxResult runDetail(@Valid @RequestBody RunDetailRequest request)
    {
        return AjaxResult.success(queryService.getRunDetail(request.getRunId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:tool:query')")
    @PostMapping("/tool/catalog")
    public AjaxResult toolCatalog(@RequestBody ToolQuery request)
    {
        return AjaxResult.success(queryService.listTools(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:budget:list')")
    @PostMapping("/budget/list")
    public AjaxResult budgetList(@RequestBody BudgetQuery request)
    {
        return AjaxResult.success(queryService.listBudgets(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:budget:query')")
    @PostMapping("/budget/detail")
    public AjaxResult budgetDetail(@Valid @RequestBody BudgetDetailRequest request)
    {
        return AjaxResult.success(queryService.getBudgetDetail(request.getAuthorizationId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:input:list')")
    @PostMapping("/input/list")
    public AjaxResult inputList(@RequestBody InputQuery request)
    {
        return AjaxResult.success(queryService.listInputs(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:assistant:input:query')")
    @PostMapping("/input/detail")
    public AjaxResult inputDetail(@Valid @RequestBody InputDetailRequest request)
    {
        return AjaxResult.success(queryService.getInputDetail(request.getRequestId()));
    }
}
