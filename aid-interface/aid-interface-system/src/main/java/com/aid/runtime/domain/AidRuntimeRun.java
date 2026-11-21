package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Durable wrapper runtime run. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_run")
public class AidRuntimeRun extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private Long turnId;
    private String memberType;
    private Long memberId;
    private Long versionId;
    private String versionDigest;
    private String modelCode;
    private String outputKind;
    private Long projectId;
    private Long workspaceId;
    private String nodeObjectId;
    private Long nodeVersion;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeNodeKey;
    private String clientRequestId;
    private String requestDigest;
    private String requestJson;
    private Long trialGrantId;
    private Integer estimatedTokensUsed;
    private String clarificationKey;
    private String clarificationDigest;
    private Long mediaTaskId;
    private String executorStrategy;
    private Long stateRevision;
    private Long planRevision;
    private Long budgetAuthorizationId;
    private String contextDigest;
    private String checkpointRef;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pauseReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resumeStatus;
    private Boolean stopRequested;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String nextAction;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date leaseUntil;
    private Long fencingToken;
    // A quiescent lifecycle transition explicitly removes the durable recovery schedule.
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date nextRetryAt;
    private String status;
    private String resultJson;
    private String errorMessage;
    private String delFlag;
}
