package com.aid.media.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** Durable reconciliation state for one media provider submission. */
@Data
@TableName("aid_media_submission_reconciliation")
public class AidMediaSubmissionReconciliation implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String protocol;
    private String mediaType;
    private String status;
    private Date nextRetryAt;
    private Integer attemptCount;
    private String leaseOwner;
    private Date leaseUntil;
    private Long fencingToken;
    private Long lastEvidenceId;
    private String lastEvidenceDigest;
    private String lastErrorCode;
    private Long stateRevision;
    private String delFlag;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
