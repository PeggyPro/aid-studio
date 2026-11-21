package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Active published main-Agent identity for one assistant module. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_module_agent_binding")
public class AidRuntimeModuleAgentBinding extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String moduleCode;
    private Long runtimeAgentId;
    private Long activeVersionId;
    private Long bindingRevision;
    private String status;
    private String delFlag;
}
