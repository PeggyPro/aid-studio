package com.aid.assistant.service;

import com.aid.runtime.domain.AidRuntimeBudgetPolicy;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** The persisted budget mode takes precedence over the initial message preference. */
public final class AssistantExecutionMode {
    private AssistantExecutionMode() { }

    public static String requested(JSONObject request) {
        return request != null && "AUTO".equals(request.getString("executionPreference")) ? "AUTO" : "MANUAL";
    }

    public static String resolve(AidRuntimeRun run, AidRuntimeBudgetPolicy policy) {
        return policy == null ? requested(JSON.parseObject(run.getRequestJson())) : policy.getMode();
    }

    public static String instruction(String mode) {
        return "AUTO".equals(mode) ? "\n\nFully automatic execution is enabled for this run. "
                + "Choose workable creative defaults within the original task and configured capabilities; "
                + "record assumptions and continue to delivery without asking the user questions or requesting "
                + "plan or fee confirmation. Use real failure receipts for bounded correction, preserve completed "
                + "work, and never resubmit an operation whose acceptance is unknown. Budget, permission and "
                + "required-resource failures must be reported factually. After a user stop, start no further "
                + "model requests, corrections or tool calls; the already submitted call settles normally." : "";
    }
}
