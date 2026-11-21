package com.aid.assistant.artifact.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** CAS-updated coverage projection for one source chunk and purpose. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_source_coverage")
public class AidRuntimeSourceCoverage extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    private Long sourceRevisionId;
    private Integer chunkIndex;
    private String purpose;
    private String coverageStatus;
    private Long evidenceArtifactId;
    private Long revision;
    private String notes;
    private String status;
    private String delFlag;
}
