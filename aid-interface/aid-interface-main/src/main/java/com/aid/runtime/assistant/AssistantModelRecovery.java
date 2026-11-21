package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;

/** Server-owned, durable recovery budget for one unfinished model decision. */
public final class AssistantModelRecovery {
    private static final String FIELD = "_modelDecisionRecovery";
    public static final int MAX_RETRIES = 3;
    private static final String STEP_PREFIX = "MODEL_RECOVERY:";
    private static final Set<String> OUTPUT_ERRORS = Set.of(
            "MODEL_OUTPUT_EMPTY", "AGENT_OUTPUT_CONTRACT_INVALID", "MODEL_OUTPUT_INVALID",
            "MODEL_PLAN_DEPENDENCY_INVALID", "MODEL_FUNCTION_NOT_EXPOSED", "AUTO_INPUT_NOT_REQUIRED", "MODEL_PLAN_INVALID");

    private AssistantModelRecovery() { }

    public static String stepMarker(String reason) { return STEP_PREFIX + reason; }

    public static boolean isRejectedRecoveryStep(String marker) {
        if (marker == null || !marker.startsWith(STEP_PREFIX)) return false;
        String reason = marker.substring(STEP_PREFIX.length());
        return "UPSTREAM_HTTP_503".equals(reason) || OUTPUT_ERRORS.contains(reason);
    }

    public static String failedTaskReason(String errorJson) {
        if (errorJson == null || errorJson.isBlank()) return null;
        try {
            JSONObject error = JSON.parseObject(errorJson);
            if (Integer.valueOf(503).equals(error.getInteger("providerHttpStatus"))) return "UPSTREAM_HTTP_503";
            if (error.getBooleanValue("providerResponseComplete")
                    && error.getBooleanValue("providerTextCompletionConfirmed")
                    && "MODEL_OUTPUT_INVALID".equals(error.getString("textModelFailureReason")))
                return "MODEL_OUTPUT_INVALID";
            return "RESULT_INVALID".equals(error.getString("errorCode"))
                    && "模型未返回正文".equals(error.getString("userMessage")) ? "MODEL_OUTPUT_EMPTY" : null;
        } catch (RuntimeException invalid) { return null; }
    }

    /** Called only under the run lock after verifying receipt identity and absence of side effects. */
    public static String schedule(String requestJson, Long stepId, String reason) {
        if (reason == null || !"UPSTREAM_HTTP_503".equals(reason) && !OUTPUT_ERRORS.contains(reason)) return null;
        JSONObject request = JSON.parseObject(requestJson);
        JSONObject prior = request.getJSONObject(FIELD);
        int count = prior == null ? 0 : prior.getIntValue("attempt");
        if (count < 0 || count >= MAX_RETRIES) return null;
        JSONObject recovery = new JSONObject();
        recovery.put("automaticBefore", prior == null
                ? "server-v1".equals(request.getString("_automaticTextExecution"))
                : prior.getBooleanValue("automaticBefore"));
        recovery.put("attempt", count + 1);
        recovery.put("sourceStepId", String.valueOf(stepId));
        recovery.put("reason", reason);
        request.put(FIELD, recovery);
        // An existing run budget authorizes recovery analysis, including non-canvas entries.
        request.put("_automaticTextExecution", "server-v1");
        return request.toJSONString();
    }

    public static int attempt(String requestJson) {
        JSONObject value = JSON.parseObject(requestJson).getJSONObject(FIELD);
        return value == null ? 0 : value.getIntValue("attempt");
    }

    public static boolean pending(String requestJson) { return attempt(requestJson) > 0; }

    public static String clear(String requestJson) {
        JSONObject request = JSON.parseObject(requestJson);
        JSONObject recovery = request.getJSONObject(FIELD);
        if (recovery == null) return requestJson;
        request.remove(FIELD);
        if (!recovery.getBooleanValue("automaticBefore")) request.remove("_automaticTextExecution");
        return request.toJSONString();
    }

    /** Safe diagnostic only: never echo upstream text, credentials, or rejected instructions. */
    public static String instruction(String requestJson) {
        JSONObject recovery = JSON.parseObject(requestJson).getJSONObject(FIELD);
        if (recovery == null) return "";
        if ("AUTO_INPUT_NOT_REQUIRED".equals(recovery.getString("reason")))
            return "\n\nThe user selected fully automatic execution. Resolve creative choices with workable "
                    + "defaults within the original task and configured model capabilities; record assumptions and continue "
                    + "unfinished work without asking the user. Preserve completed operations. Missing permission, "
                    + "funding or unavailable required resources must produce a factual failure, never invented authority.";
        return "\n\nServer recovery of the current unfinished decision. Previous attempt: "
                + recovery.getString("reason") + ". Use the current input, exact available function names, "
                + "contracts and examples. Regenerate only the required result in its original output mode. "
                + "Keep analysis concise and reserve enough output for a complete result or tool call. "
                + "When JSON is required, use valid JSON, escape double quotes and backslashes inside strings, "
                + "and obey required fields, nesting and additionalProperties. "
                + "If native tools are present, return their arguments as required by their schema. "
                + "If a JSON body is required, return one complete JSON object without code fences. "
                + "If plain text is required, return the requested text without a JSON wrapper. "
                + "Preserve completed operations and results; continue only unfinished work. "
                + "Do not claim the rejected decision was executed.";
    }
}
