package com.aid.assistant.budget;

import com.aid.assistant.error.AssistantServiceException;

import java.util.Map;
import java.util.Set;

/**
 * A deterministic runtime-budget rejection observed before provider submission.
 *
 * <p>Only persisted policy/authorization facts may create this exception. Storage,
 * decoding and connectivity failures must keep their original exception type so
 * recovery never mistakes an unknown outcome for a definitive rejection.</p>
 */
public final class AssistantBudgetAdmissionRejectedException extends AssistantServiceException
{
    private static final long serialVersionUID = 1L;

    public enum Reason
    {
        POLICY_MISSING,
        POLICY_EXPIRED,
        POLICY_INACTIVE,
        RENEWAL_CONFIRMATION_REQUIRED,
        COUNT_EXHAUSTED,
        AUTHORIZATION_MISSING,
        AUTHORIZATION_EXPIRED,
        AUTHORIZATION_REVOKED,
        AUTHORIZATION_CLOSED,
        AUTHORIZATION_CHANGED,
        AUTHORIZATION_FUNDS_EXHAUSTED,
        CALL_CAP_EXCEEDED,
        MODEL_DENIED,
        TOOL_DENIED,
        CAPABILITY_DENIED,
        MEDIA_TYPE_DENIED,
        AUTO_OPERATION_FORBIDDEN,
        OPERATION_KIND_REQUIRED,
        TARGET_DENIED
    }

    private static final Set<String> RUNTIME_ERROR_CODES = Set.of(
            "BUDGET_POLICY_MISSING", "BUDGET_POLICY_EXPIRED", "BUDGET_POLICY_INACTIVE", "BUDGET_POLICY_EXHAUSTED",
            "BUDGET_AUTHORIZATION_UNAVAILABLE", "BUDGET_AUTHORIZATION_EXPIRED",
            "BUDGET_AUTHORIZATION_REVOKED", "BUDGET_AUTHORIZATION_CLOSED",
            "BUDGET_AUTHORIZATION_CHANGED", "BUDGET_SCOPE_DENIED", "BUDGET_RENEWAL_CONFIRMATION_REQUIRED");
    private static final Set<String> NEW_RUN_ERROR_CODES = Set.of(
            "BUDGET_POLICY_MISSING", "BUDGET_POLICY_INACTIVE",
            "BUDGET_AUTHORIZATION_UNAVAILABLE",
            "BUDGET_AUTHORIZATION_REVOKED", "BUDGET_AUTHORIZATION_CLOSED",
            "BUDGET_AUTHORIZATION_CHANGED");

    private final Reason reason;
    private final String runtimeErrorCode;

    public AssistantBudgetAdmissionRejectedException(Reason reason)
    {
        super(409, "RUN_BUDGET_SCOPE_DENIED", "操作超出已批准范围",
                Map.of("reason", java.util.Objects.requireNonNull(reason).name()));
        this.reason = reason;
        this.runtimeErrorCode = runtimeCode(this.reason);
    }

    public Reason getReason()
    {
        return reason;
    }

    public String getRuntimeErrorCode()
    {
        return runtimeErrorCode;
    }

    public static boolean isRuntimeErrorCode(String value)
    {
        return value != null && RUNTIME_ERROR_CODES.contains(value);
    }

    /** Rejections that cannot be repaired by revising the still-active policy on this run. */
    public static boolean requiresNewRun(String value)
    {
        return value != null && NEW_RUN_ERROR_CODES.contains(value);
    }

    public boolean requiresNewRun()
    {
        return requiresNewRun(runtimeErrorCode);
    }

    private static String runtimeCode(Reason reason)
    {
        return switch (reason)
        {
            case POLICY_MISSING -> "BUDGET_POLICY_MISSING";
            case POLICY_EXPIRED -> "BUDGET_POLICY_EXPIRED";
            case POLICY_INACTIVE -> "BUDGET_POLICY_INACTIVE";
            case RENEWAL_CONFIRMATION_REQUIRED -> "BUDGET_RENEWAL_CONFIRMATION_REQUIRED";
            case COUNT_EXHAUSTED -> "BUDGET_POLICY_EXHAUSTED";
            case AUTHORIZATION_MISSING -> "BUDGET_AUTHORIZATION_UNAVAILABLE";
            case AUTHORIZATION_EXPIRED -> "BUDGET_AUTHORIZATION_EXPIRED";
            case AUTHORIZATION_REVOKED -> "BUDGET_AUTHORIZATION_REVOKED";
            case AUTHORIZATION_CLOSED -> "BUDGET_AUTHORIZATION_CLOSED";
            case AUTHORIZATION_CHANGED -> "BUDGET_AUTHORIZATION_CHANGED";
            case AUTHORIZATION_FUNDS_EXHAUSTED -> "BUDGET_SCOPE_DENIED";
            default -> "BUDGET_SCOPE_DENIED";
        };
    }
}
