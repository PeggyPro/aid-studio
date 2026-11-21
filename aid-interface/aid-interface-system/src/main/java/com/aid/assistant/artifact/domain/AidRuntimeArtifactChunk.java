package com.aid.assistant.artifact.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Immutable text chunk for one artifact revision. */
@Data
@TableName("aid_runtime_artifact_chunk")
public class AidRuntimeArtifactChunk {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long artifactId;
    private Integer chunkIndex;
    private Integer charStart;
    private Integer charEnd;
    private Integer charCount;
    private Integer wordCount;
    private String contentText;
    private String contentHash;
    private String status;
    private String createBy;
    private Date createTime;
}
