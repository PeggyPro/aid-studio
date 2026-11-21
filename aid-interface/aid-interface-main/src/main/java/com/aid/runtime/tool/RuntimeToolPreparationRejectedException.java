package com.aid.runtime.tool;

/** Deterministic validation rejection before a prepared payload or external submission exists. */
public final class RuntimeToolPreparationRejectedException extends RuntimeException
{
    private final Long completedStepId;
    public enum Reason
    {
        TOOL_PREPARATION_MODEL_REQUIRED,
        TOOL_PREPARATION_SELECTED_MODEL_MISMATCH,
        TOOL_PREPARATION_MODEL_UNAVAILABLE,
        TOOL_PREPARATION_MODEL_CAPABILITY_INVALID,
        TOOL_PREPARATION_MODEL_PARAMETERS_INVALID,
        TOOL_PREPARATION_MEDIA_INPUTS_INVALID,
        TOOL_PREPARATION_MEDIA_METADATA_REQUIRED,
        TOOL_PREPARATION_IMAGE_REFERENCE_REQUIRED,
        TOOL_PREPARATION_SKILL_RESOURCE_PATH_INVALID,
        TOOL_PREPARATION_SKILL_MASTER_DIGEST_REQUIRED,
        TOOL_PREPARATION_AGENT_RESOURCE_PATH_INVALID,
        TOOL_PREPARATION_AGENT_MASTER_DIGEST_REQUIRED,
        TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID,
        TOOL_PREPARATION_REFERENCE_CONTEXT_TOO_LARGE,
        TOOL_PREPARATION_RESULT_REFERENCE_INVALID,
        TOOL_PREPARATION_RESULT_REFERENCE_FORBIDDEN,
        TOOL_PREPARATION_RESULT_REFERENCE_NOT_READY,
        TOOL_PREPARATION_RESULT_REFERENCE_CHANGED,
        TOOL_PREPARATION_RESULT_POINTER_INVALID,
        TOOL_PREPARATION_RESULT_TEXT_NOT_FOUND,
        TOOL_PREPARATION_RESULT_NOT_TEXT,
        TOOL_PREPARATION_RESULT_TEXT_TOO_LARGE,
        TOOL_PREPARATION_RESULT_TEXT_EMPTY,
        TOOL_PREPARATION_REQUEST_INVALID,
        TOOL_PREPARATION_CONTRACT_CHANGED,
        TOOL_PREPARATION_ARGUMENTS_INVALID,
        TOOL_PREPARATION_PLAN_INVALID,
        TOOL_PREPARATION_PLAN_MODE_INVALID,
        TOOL_PREPARATION_ARTIFACT_CREATE_EXPECTED,
        TOOL_PREPARATION_ARTIFACT_REVISION_INVALID,
        TOOL_PREPARATION_RESULT_INVALID,
        TOOL_PREPARATION_SCOPE_INVALID,
        TOOL_PREPARATION_IDENTITY_CHANGED,
        TOOL_PREPARATION_ALREADY_COMPLETED,
        TOOL_PREPARATION_QUOTE_UNAVAILABLE,
        TOOL_PREPARATION_QUOTE_INVALID,
        TOOL_PREPARATION_QUOTE_EXPIRED
    }

    public RuntimeToolPreparationRejectedException(Reason reason)
    {
        super(java.util.Objects.requireNonNull(reason).name());
        completedStepId = null;
    }

    public RuntimeToolPreparationRejectedException(Reason reason, Throwable cause)
    {
        super(java.util.Objects.requireNonNull(reason).name(), cause);
        completedStepId = null;
    }

    private RuntimeToolPreparationRejectedException(long completedStepId) {
        super(Reason.TOOL_PREPARATION_ALREADY_COMPLETED.name());
        if (completedStepId <= 0) throw new IllegalArgumentException("completedStepId");
        this.completedStepId = completedStepId;
    }

    public static RuntimeToolPreparationRejectedException alreadyCompleted(long stepId) {
        return new RuntimeToolPreparationRejectedException(stepId);
    }

    public Long getCompletedStepId() { return completedStepId; }
}
