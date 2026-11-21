package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Mutable, optimistic-lock protected Runtime Agent draft. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_agent_draft")
public class AidRuntimeAgentDraft extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long ownerUserId;
    private String activeKey;
    private Long baseVersionId;
    private String draftJson;
    private String draftDigest;
    private String status;
}
