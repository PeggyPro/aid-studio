package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.BeanUtils;

/** Select within the pinned Agent's model pool without mutating its formal package. */
public final class AssistantTextModelSelection
{
    private AssistantTextModelSelection() { }
    public static AssistantLoopBinding select(AssistantLoopBinding binding, String requested)
    {
        if (requested == null) return binding;
        JSONObject config = JSON.parseObject(binding.getModelConfigJson());
        if (requested.isBlank() || config == null || config.getJSONArray("selectableModelCodes") == null
                || !config.getJSONArray("selectableModelCodes").contains(requested))
            throw new ServiceException("文本模型不属于本轮Agent的授权候选");
        config.put("defaultModelCode", requested);
        AssistantLoopBinding selected = new AssistantLoopBinding();
        BeanUtils.copyProperties(binding, selected);
        selected.setModelConfigJson(config.toJSONString());
        return selected;
    }
    public static AssistantLoopBinding forRequest(AssistantLoopBinding binding, String requestJson)
    {
        JSONObject request = JSON.parseObject(requestJson);
        return select(binding, request == null ? null : request.getString("_selectedTextModelCode"));
    }
}
