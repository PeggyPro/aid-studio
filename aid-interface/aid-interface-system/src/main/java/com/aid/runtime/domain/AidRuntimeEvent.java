package com.aid.runtime.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Replayable session event. */
@Data
@TableName("aid_runtime_event")
public class AidRuntimeEvent
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long seq;
    private String eventType;
    private String entityId;
    private Long entityRevision;
    private String causationId;
    private String payloadJson;
    private Date publishedAt;
    private Integer publishAttempt;
    private Date nextRetryAt;
    private Date createTime;
}
