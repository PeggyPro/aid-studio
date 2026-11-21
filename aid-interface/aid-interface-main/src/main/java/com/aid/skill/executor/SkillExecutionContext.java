package com.aid.skill.executor;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Skill单次执行上下文；配置读取自当前 Skill。 */
@Data
@Builder
public class SkillExecutionContext {
    private AidSkillRun run;
    private AidSkillRunStep step;
    private AssistantSkillBudgetEnvelope assistantBudgetEnvelope;
    private AidSkill skill;
    private List<MediaTextGenerateRequest.TextMessageItem> messages;
    private List<SkillInputPart> inputParts;
    private String responseMode;
    /** Server-owned schema from the pinned published Skill version; never browser options. */
    private String responseSchemaJson;
    private Long projectId;
    private Long episodeId;
    private String logicalCallKey;
    private String callIdentity;
    private String bizTaskType;
}
