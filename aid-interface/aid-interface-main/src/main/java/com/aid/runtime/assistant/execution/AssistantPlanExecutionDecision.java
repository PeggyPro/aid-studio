package com.aid.runtime.assistant.execution;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/** Strictly parsed, untrusted model decision. Authorization is deliberately absent. */
record AssistantPlanExecutionDecision(String protocolVersion,
                                      String decisionId,
                                      long observedRunRevision,
                                      long observedPlanRevision,
                                      String action,
                                      String invocationId,
                                      String stableStepId,
                                      int attemptNo,
                                      String operationKind,
                                      String bindingCode,
                                      String bindingVersion,
                                      Long skillVersionId,
                                      String packageDigest,
                                      JSONObject arguments,
                                      List<String> inputArtifactRefs,
                                      JSONArray expectedContextRevisions,
                                      String canonicalPayloadJson)
{
    boolean isTool()
    {
        return "TOOL".equals(action);
    }

    boolean isSkill()
    {
        return "SKILL".equals(action);
    }
}
