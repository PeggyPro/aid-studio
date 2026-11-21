package com.aid.runtime.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Fixed server-registered Tool contract captured at Agent publication. */
@Data
@TableName("aid_runtime_agent_tool_ref")
public class AidRuntimeAgentToolRef
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentVersionId;
    private String toolCode;
    private String contractVersion;
    private String schemaDigest;
}
