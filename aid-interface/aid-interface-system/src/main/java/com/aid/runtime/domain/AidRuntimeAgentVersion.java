package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable published Runtime Agent package. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_agent_version")
public class AidRuntimeAgentVersion extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String versionCode;
    private String publishStatus;
    private String instruction;
    private String modelConfigJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String definitionJson;
    private String packageDigest;
    private String status;
    private String delFlag;
}
