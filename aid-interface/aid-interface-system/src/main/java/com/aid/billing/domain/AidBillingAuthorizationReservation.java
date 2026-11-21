package com.aid.billing.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 一次真实调用在Run总预算中的幂等占用。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_billing_authorization_reservation", excludeProperty = "remark")
public class AidBillingAuthorizationReservation extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long authorizationId;
    private Long authorizationRevision;
    private Long runtimeRunId;
    private Long runtimeStepId;
    private String stableStepKey;
    private Long approvalId;
    private Integer attemptNo;
    private String operationKind;
    private String logicalCallKey;
    private String requestDigest;
    private String quoteId;
    private String quoteDigest;
    private Date quoteExpiresAt;
    private Long mediaTaskId;
    private String billingTraceId;
    private BigDecimal quotedUpperBound;
    private BigDecimal reservedAmount;
    private BigDecimal walletFrozenAmount;
    private BigDecimal settledAmount;
    private BigDecimal releasedAmount;
    private BigDecimal walletRefundedAmount;
    private String state;
    private String settlementKey;
    private Long revision;
    private String delFlag;
}
