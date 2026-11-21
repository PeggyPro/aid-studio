package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Stable Runtime Agent identity. This is distinct from the legacy operating-content aid_agent table. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_agent")
public class AidRuntimeAgent extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String agentCode;
    private String agentFamily;
    private String name;
    private String description;
    private String iconUrl;
    private String ownerType;
    private Long ownerUserId;
    private String visibility;
    private Long currentVersionId;
    private String status;
    private String delFlag;
}
