package com.aid.runtime.assistant.correction;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.error.AssistantServiceException;

import java.util.Locale;

/** Server-only command receipt namespace for one bounded decision-correction attempt. */
public final class AssistantDecisionCorrectionCommandNamespace
{
    public static final String PREFIX = "aid.ndc.internal.";
    public static final String SLOT_TYPE = "NATIVE_DECISION_CORRECTION_SLOT";
    public static final String BOUND_TYPE = "NATIVE_DECISION_CORRECTION_BOUND";

    private AssistantDecisionCorrectionCommandNamespace()
    {
    }

    public static String slotKey(Long runId)
    {
        return key("slot", runId);
    }

    public static String boundKey(Long runId)
    {
        return key("bound", runId);
    }

    public static boolean isReservedClientRequestId(String value)
    {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static boolean isInternalCommandType(String value)
    {
        return SLOT_TYPE.equals(value) || BOUND_TYPE.equals(value);
    }

    public static void requireExternalClientRequestId(String value)
    {
        // The table uses utf8mb4_unicode_ci. Reject non-ASCII at the service boundary so an
        // accent/width-equivalent key cannot compare equal to a reserved receipt in MySQL.
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new AssistantServiceException(422, "ASSISTANT_COMMAND_KEY_INVALID",
                    "请求标识无效", null);
        if (isReservedClientRequestId(value))
            throw new AssistantServiceException(422, "ASSISTANT_INTERNAL_COMMAND_KEY_RESERVED",
                    "请求标识属于服务端保留范围", null);
    }

    private static String key(String kind, Long runId)
    {
        if (runId == null || runId < 1) throw new IllegalArgumentException("runId");
        String key = PREFIX + kind + "." + SecureUtil.sha256(String.valueOf(runId)).substring(0, 40);
        if (key.length() > 64) throw new IllegalStateException("Internal command key is too long");
        return key;
    }
}
