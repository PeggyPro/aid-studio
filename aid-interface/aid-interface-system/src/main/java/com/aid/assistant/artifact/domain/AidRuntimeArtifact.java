package com.aid.assistant.artifact.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable assistant artifact revision. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_artifact")
public class AidRuntimeArtifact extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String logicalArtifactId;
    private Long revision;
    private String artifactType;
    private String schemaVersion;
    private Long resourceId;
    private Long fileId;
    private String resourceRefJson;
    private String sourceContextDigest;
    private String sourceContextRefsJson;
    private Long parentRevision;
    private Long producerRunId;
    private Long producerStepId;
    private Long agentVersionId;
    private Long planRevision;
    private String contentHash;
    private String reviewStatus;
    private String summary;
    private String metadataJson;
    private String status;
    private String delFlag;
}
