package com.aid.runtime.tool;

import com.aid.assistant.budget.AssistantBudgetOperationScope;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Provider-owned pure preparation result; the registry adds trusted invocation identity. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeToolPreparation
{
    private String preparedPayloadJson;
    private RuntimeToolBillingIntent billingIntent;
    private AssistantBudgetOperationScope operationScope;
}
