package com.aid.runtime.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Fixed Skill package reference captured at Agent publication. */
@Data
@TableName("aid_runtime_agent_skill_ref")
public class AidRuntimeAgentSkillRef
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentVersionId;
    private Long skillId;
    private Long skillVersionId;
    private String skillCode;
    private String packageDigest;
}
