package com.aid.assistant.artifact.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Idempotent receipt for adopting one candidate into a versioned target. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_artifact_adoption")
public class AidRuntimeArtifactAdoption extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private Long artifactId;
    private String targetKind;
    private String targetObjectId;
    private String expectedTargetRevision;
    private String resultTargetRevision;
    private String clientRequestId;
    private String requestDigest;
    private String status;
    private String receiptJson;
    private String errorCode;
    private String delFlag;
}
