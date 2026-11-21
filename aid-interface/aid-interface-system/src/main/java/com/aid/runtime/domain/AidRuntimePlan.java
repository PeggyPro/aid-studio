package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable main-Agent plan revision. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_plan")
public class AidRuntimePlan extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long revision;
    private Long parentRevision;
    private Long agentVersionId;
    private String protocolVersion;
    private Integer plannerAttemptNo;
    private String definitionJson;
    private String inputContextDigest;
    private String definitionDigest;
    private String quoteDigest;
    private Long mediaTaskId;
    private String status;
    private String delFlag;
}
