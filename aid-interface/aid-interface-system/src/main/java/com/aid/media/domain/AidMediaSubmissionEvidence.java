package com.aid.media.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** Sanitized immutable evidence observed while reconciling a media submission. */
@Data
@TableName("aid_media_submission_evidence")
public class AidMediaSubmissionEvidence implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long reconciliationId;
    private Long taskId;
    private String adapterCode;
    private String evidenceType;
    private String verdict;
    private String providerTaskId;
    private String requestDigest;
    private String submissionKey;
    private String evidenceDigest;
    private String providerStatus;
    private String terminalStatus;
    private Boolean querySuccessful;
    private Boolean terminalConfirmed;
    private String sanitizedPayloadJson;
    private Date observedAt;
    private String delFlag;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
