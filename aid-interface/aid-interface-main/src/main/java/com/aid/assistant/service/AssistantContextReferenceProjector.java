package com.aid.assistant.service;

import com.aid.assistant.scope.ResolvedContextRef;
import com.alibaba.fastjson2.JSONObject;

/** Projects one authorized context reference into bounded model-readable source data. */
public interface AssistantContextReferenceProjector {
    boolean supports(String kind);

    JSONObject project(Long userId, ResolvedContextRef reference, int ordinal);
}
