package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Idempotent runtime command receipt. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_command")
public class AidRuntimeCommand extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private Long sessionId;
    private String clientRequestId;
    private String payloadDigest;
    private String commandType;
    private String status;
    private String resultJson;
    private String errorCode;
    private String delFlag;
}
