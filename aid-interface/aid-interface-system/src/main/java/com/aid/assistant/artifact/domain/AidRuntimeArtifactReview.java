package com.aid.assistant.artifact.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Review receipt that points to an immutable review-report artifact. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_artifact_review")
public class AidRuntimeArtifactReview extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long artifactId;
    private Long reviewArtifactId;
    private Long reviewerRunId;
    private Long reviewerStepId;
    private String verdict;
    private String severityCountsJson;
    private String contentHash;
    private String status;
    private String delFlag;
}
