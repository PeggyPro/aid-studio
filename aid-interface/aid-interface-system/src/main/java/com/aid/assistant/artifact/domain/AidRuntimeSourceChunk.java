package com.aid.assistant.artifact.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Immutable chunk of an authorized source snapshot. */
@Data
@TableName("aid_runtime_source_chunk")
public class AidRuntimeSourceChunk {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sourceRevisionId;
    private Integer chunkIndex;
    private Integer charStart;
    private Integer charEnd;
    private Integer charCount;
    private Integer wordCount;
    private String locatorJson;
    private String contentText;
    private String contentHash;
    private String status;
    private String createBy;
    private Date createTime;
}
