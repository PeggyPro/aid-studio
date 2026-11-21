package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolResult;

public interface CreativeTextGenerationService {
    String BUSINESS_FUNC_CODE = "ASSISTANT_CREATIVE_TEXT";

    CreativeRequests.PreparedTextSubmit prepareRequest(RuntimeToolInvocation invocation,
                                                       CreativeRequests.TextSubmit request);

    RuntimeToolResult submitPrepared(RuntimeToolPreparedInvocation invocation,
                                     CreativeRequests.PreparedTextSubmit prepared,
                                     BillingBudgetContext billingBudgetContext);
}
