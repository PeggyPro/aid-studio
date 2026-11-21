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

/** Structured runtime input request. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_input_request")
public class AidRuntimeInputRequest extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String requestKey;
    private Integer roundNo;
    private String schemaDigest;
    private String contextDigest;
    private Long planRevision;
    private String status;
    private String questionBundleJson;
    private String acceptedAnswersJson;
    private String acceptedDigest;
    private String acceptedKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date suspendedAt;
    private Date expiresAt;
}
