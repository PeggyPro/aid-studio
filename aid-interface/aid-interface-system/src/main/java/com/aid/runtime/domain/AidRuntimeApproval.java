package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/** User authorization snapshot for planning or execution scope. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_approval")
public class AidRuntimeApproval extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long userId;
    private String clientRequestId;
    @TableField("authorization_id")
    private Long billingAuthorizationId;
    @TableField("authorization_revision")
    private Long billingAuthorizationRevision;
    private String scopeKind;
    private String requestDigest;
    private String answerDigest;
    private String contextDigest;
    private Integer planningAttemptNo;
    private Long planRevision;
    private String planDigest;
    private String scopeJson;
    private String scopeDigest;
    private String nodeRevisionSetJson;
    @TableField("allowed_tool_set_json")
    private String allowedToolCodesJson;
    @TableField("allowed_model_set_json")
    private String allowedModelCodesJson;
    @TableField("allowed_capability_set_json")
    private String allowedCapabilityCodesJson;
    private Integer maxAttempts;
    private String mode;
    private String quoteId;
    private String quoteDigest;
    @TableField("quote_snapshot_ref")
    private String quoteRef;
    private BigDecimal maxCharge;
    private Date expiresAt;
    private String status;
    private Long stateRevision;
    private Date acceptedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date invalidatedAt;
    private String delFlag;

    /** Both authorization timestamps use DATETIME(0); never let persistence round them forward. */
    public void setAcceptedAt(Date value)
    {
        this.acceptedAt = persistedSecond(value);
    }

    public void setExpiresAt(Date value)
    {
        this.expiresAt = persistedSecond(value);
    }

    private static Date persistedSecond(Date value)
    {
        return value == null ? null : new Date(Math.floorDiv(value.getTime(), 1000L) * 1000L);
    }
}
