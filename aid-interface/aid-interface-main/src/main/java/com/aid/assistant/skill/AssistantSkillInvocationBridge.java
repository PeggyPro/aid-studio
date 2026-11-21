package com.aid.assistant.skill;

import com.aid.skill.vo.SkillInvocationVO;

/** Fixed-version Assistant-to-Skill entrypoint. */
public interface AssistantSkillInvocationBridge {
    SkillInvocationVO invokeFixedVersion(AssistantFixedSkillInvocation request, Long userId, String operator);

    SkillInvocationVO findByAssistantIdentity(Long assistantRuntimeRunId, Long assistantParentStepId,
                                              String assistantOuterStableStepKey, Integer assistantAttemptNo,
                                              Long userId);

    /** Reconciles durable Skill tasks after the owning Assistant run has accepted STOP. */
    SkillInvocationVO reconcileStoppedInvocation(Long assistantRuntimeRunId, Long assistantParentStepId,
                                                 String assistantOuterStableStepKey, Integer assistantAttemptNo,
                                                 Long userId, String operator);
}
