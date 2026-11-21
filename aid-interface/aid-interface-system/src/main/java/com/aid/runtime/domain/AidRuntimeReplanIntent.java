package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Durable pending payload and recovery checkpoint for one paused-run replan. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_replan_intent")
public class AidRuntimeReplanIntent extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long sessionId;
    private Long commandId;
    private Long actorId;
    private String clientRequestId;
    private String commandPayloadDigest;
    private String intentType;
    private String status;
    private Integer activeSlot;
    private Long baseRunRevision;
    private Long basePlanRevision;
    private String baseContextDigest;
    private String baseRequestDigest;
    private String pendingPayloadJson;
    private String pendingPayloadDigest;
    private Long stateRevision;
    private Date nextRetryAt;
    private String lastErrorCode;
    private Date appliedAt;
    private String delFlag;
}
