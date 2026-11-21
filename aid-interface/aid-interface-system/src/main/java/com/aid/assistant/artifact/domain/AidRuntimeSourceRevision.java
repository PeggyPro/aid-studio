package com.aid.assistant.artifact.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Authorized immutable source snapshot. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_source_revision")
public class AidRuntimeSourceRevision extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sourceKind;
    private String sourceObjectId;
    private String sourceRevision;
    private String contentHash;
    private Integer charCount;
    private Integer wordCount;
    private Integer chunkCount;
    private String accessScopeDigest;
    private String status;
    private String delFlag;
}
