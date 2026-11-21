package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.model.dto.AiModelListRequest;
import com.aid.model.service.IAiModelBusinessService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit composer choices are instructions, never a replacement for execution authorization. */
@Service
public class AssistantModelSelectionService
{
    @Resource private IAiModelBusinessService models;

    public JSONArray freeze(List<AssistantRequests.ModelSelection> requested)
    {
        JSONArray result = new JSONArray();
        if (requested == null || requested.isEmpty()) return result;
        var types = new HashSet<String>();
        if (requested.size() > 2) throw unavailable();
        for (var choice : requested)
            if (choice == null || !List.of("image", "video").contains(Objects.toString(choice.getModelType(), ""))
                    || !types.add(choice.getModelType()) || choice.getModelCode() == null
                    || choice.getModelCode().isBlank() || choice.getModelCode().length() > 100) throw unavailable();
        // Reuse the bounded model catalog query. Default execution never invokes this query.
        var candidates = models.listAvailableModelsByCodes(new AiModelListRequest(),
                requested.stream().map(AssistantRequests.ModelSelection::getModelCode).toList());
        for (var choice : requested)
        {
            var matches = candidates.stream().filter(model -> Objects.equals(model.getModelCode(), choice.getModelCode())
                    && Objects.equals(model.getModelType(), choice.getModelType())).toList();
            if (matches.size() != 1) throw unavailable();
            var model = matches.get(0);
            JSONObject frozen = new JSONObject();
            frozen.put("modelType", model.getModelType()); frozen.put("modelCode", model.getModelCode());
            frozen.put("modelName", model.getModelName()); frozen.put("providerName", model.getProviderName());
            frozen.put("providerCode", model.getProviderCode());
            result.add(frozen);
        }
        return result;
    }

    private AssistantServiceException unavailable()
    {
        return new AssistantServiceException(422, "ASSISTANT_SELECTED_MODEL_UNAVAILABLE",
                "所选模型当前不可用或类型不匹配，请重新选择；不会自动替换模型", Map.of("field", "selectedModels"));
    }
}
