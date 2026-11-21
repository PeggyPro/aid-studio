package com.aid.assistant.skill;

import com.aid.runtime.assistant.RuntimeLease;
import com.aid.skill.dto.SkillInvocationRequests;
import lombok.Data;

/** Trusted request used by the Assistant runtime to invoke one immutable Skill version. */
@Data
public class AssistantFixedSkillInvocation {
    private SkillInvocationRequests.InvokeRequest invocation;
    private AssistantSkillBudgetEnvelope budgetEnvelope;
    /** Explicit proof that this invocation may execute only one PROMPT method, never a legacy questionnaire. */
    private Boolean methodOnly;
    /** Runtime-run and child-step fences are independent and must never be collapsed into one token. */
    private RuntimeLease runLease;
    private RuntimeLease stepLease;
}
