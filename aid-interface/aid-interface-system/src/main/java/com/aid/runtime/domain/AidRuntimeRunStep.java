package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Durable runtime step attempt. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_run_step")
public class AidRuntimeRunStep extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Integer stepIndex;
    private String stepKey;
    private String stepKind;
    private Long skillVersionId;
    private String toolCode;
    private String inputDigest;
    private Long mediaTaskId;
    private Long childRunId;
    private String stableStepId;
    private Long planRevision;
    private Integer planPosition;
    private Integer attemptNo;
    private String operationKind;
    private String idempotencyKey;
    private String inputArtifactRef;
    private String outputArtifactRef;
    private Long approvalId;
    private Long reservationId;
    private String retryClass;
    private String providerAcceptanceState;
    // Lifecycle writes use the loaded step snapshot; clearing a lease must reach the database.
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date leaseUntil;
    private Long fencingToken;
    private String childRunKind;
    private String status;
    private String outputJson;
    private String errorMessage;
    private String delFlag;
}
