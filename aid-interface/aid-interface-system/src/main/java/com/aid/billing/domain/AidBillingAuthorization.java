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

/** Run级预算授权，只限制既有钱包可消费的总上限。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_billing_authorization", excludeProperty = "remark")
public class AidBillingAuthorization extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long payerUserId;
    private Long runtimeRunId;
    private BigDecimal approvedTotal;
    private BigDecimal reservedAmount;
    private BigDecimal settledAmount;
    private String currencyUnit;
    private Integer precisionScale;
    private String status;

    /** 用户批准内容的修订号，消费进度变化不会使其递增。 */
    private Long revision;

    /** 预算占用及结算投影的状态修订号。 */
    private Long stateRevision;

    private String policyDigest;
    private Date expiresAt;
    private String delFlag;
}
