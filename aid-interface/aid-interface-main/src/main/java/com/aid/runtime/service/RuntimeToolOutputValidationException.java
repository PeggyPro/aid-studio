package com.aid.runtime.service;

/** A provider returned success, but its serialized output failed the fixed contract. */
public final class RuntimeToolOutputValidationException extends RuntimeException
{
    public static final String ERROR_CODE = "TOOL_OUTPUT_CONTRACT_INVALID";

    private final String toolCode;
    private final boolean definiteReadFailure;

    // Only the registry creates this proof after a successful provider return.
    RuntimeToolOutputValidationException(String toolCode, boolean definiteReadFailure)
    {
        super(ERROR_CODE);
        this.toolCode = toolCode;
        this.definiteReadFailure = definiteReadFailure;
    }

    public String getToolCode()
    {
        return toolCode;
    }

    public boolean isDefiniteReadFailure()
    {
        return definiteReadFailure;
    }
}
