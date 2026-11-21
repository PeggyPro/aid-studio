package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/** Durable non-monetary execution boundary bound to one Billing authorization. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_budget_policy")
public class AidRuntimeBudgetPolicy extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long authorizationId;
    private Long authorizationRevision;
    /** Authorization revision awaiting explicit confirmation of its first new execution. */
    private Long renewalConfirmationRevision;
    private String mode;
    private BigDecimal absoluteMaxCharge;
    private BigDecimal perCallMaxCharge;
    private String allowedToolCodesJson;
    private String allowedModelCodesJson;
    private String allowedCapabilityCodesJson;
    private String allowedMediaTypesJson;
    private String targetRefsJson;
    private String automaticForbiddenOperationKindsJson;
    private Integer maxIterations;
    private Integer maxOperations;
    private Integer usedIterations;
    private Integer usedOperations;
    private Date expiresAt;
    private String scopeDigest;
    private String status;
    private Long stateRevision;
    private String delFlag;
}
