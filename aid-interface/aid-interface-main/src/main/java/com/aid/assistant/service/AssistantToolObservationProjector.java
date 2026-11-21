package com.aid.assistant.service;

import com.alibaba.fastjson2.JSONObject;

/** Projects selected server Tool outputs without changing immutable user-authored text. */
public interface AssistantToolObservationProjector {
    /** Non-Tool steps have no code; a missing code is never supported. */
    boolean supports(String toolCode);

    JSONObject project(Input input);

    record Input(Long userId, Long runId, Long stepId, String stableStepId,
                 String toolCode, String contractVersion, String schemaDigest,
                 String argumentsJson, String outputJson, int maxUtf8Bytes) {
    }
}
