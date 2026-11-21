package com.aid.runtime.assistant.execution;

/** Definitive validation rejection before an operation is created or dispatched. */
public final class AssistantPlanDecisionRejectedException extends RuntimeException
{
    private final Reason reason;

    public AssistantPlanDecisionRejectedException(Reason reason, RuntimeException cause)
    {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason()
    {
        return reason;
    }

    public enum Reason
    {
        OPERATION_DECISION_INVALID,
        NATIVE_PLAN_SAFE_READ_REQUIRED,
        NATIVE_PLAN_SAFE_READ_DEPENDENCY_REQUIRED,
        NATIVE_PLAN_SAFE_READ_DEPENDENCY_INVALID,
        PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE,
        ASSISTANT_IMAGE_SKILL_SELECTION_INVALID,
        ASSISTANT_IMAGE_SKILL_TOOL_DOWNGRADE,
        ASSISTANT_IMAGE_SKILL_TOOL_REQUIRED,
        PLAN_INPUT_BINDINGS_UNRESOLVED,
        PLAN_DEPENDENCY_INCOMPLETE,
        PLAN_BILLABLE_OUTPUT_INVALID,
        PLAN_ARTIFACT_OUTPUT_INVALID,
        PLAN_BILLABLE_OUTPUT_CHANGED;

        public static Reason fromCode(String code)
        {
            if (code == null) return null;
            try { return valueOf(code); }
            catch (IllegalArgumentException unknown) { return null; }
        }
    }
}
