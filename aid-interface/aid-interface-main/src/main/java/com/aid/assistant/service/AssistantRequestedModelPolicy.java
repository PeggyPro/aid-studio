package com.aid.assistant.service;

import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.alibaba.fastjson2.JSON;
import java.util.Locale;
import java.util.Objects;

/** Compare the prepared authoritative scope before reservation or submission, for every media tool. */
public final class AssistantRequestedModelPolicy
{
    private AssistantRequestedModelPolicy() { }

    public static void requireMatch(String requestJson, AssistantBudgetOperationScope scope)
    {
        if (requestJson == null || scope == null || scope.getMediaTypes() == null || scope.getMediaTypes().isEmpty()) return;
        var request = JSON.parseObject(requestJson);
        var choices = request == null ? null : request.getJSONArray("_selectedModels");
        if (choices == null || choices.isEmpty()) return;
        for (int index = 0; index < choices.size(); index++)
        {
            var choice = choices.getJSONObject(index);
            String type = choice.getString("modelType");
            if (type != null && scope.getMediaTypes().contains(type.toUpperCase(Locale.ROOT))
                    && !Objects.equals(choice.getString("modelCode"), scope.getModelCode()))
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SELECTED_MODEL_MISMATCH);
        }
    }
}
