package com.aid.assistant.skill;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.skill.executor.SkillExecutionContext;

/** Quotes, reserves, and attaches one trusted budget context to an internal Skill media call. */
public interface AssistantSkillBillingBridge {
    MediaTextGenerateRequest quoteReserveAndAttach(SkillExecutionContext context,
                                                   MediaTextGenerateRequest request);

    /** Returns true only when the inner reservation was durably released. */
    boolean releaseNotEntered(SkillExecutionContext context,
                              MediaSubmissionNotEnteredException notEntered);
}
