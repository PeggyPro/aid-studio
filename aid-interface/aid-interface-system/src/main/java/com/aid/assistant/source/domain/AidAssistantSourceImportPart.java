package com.aid.assistant.source.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** One ordered, independently recoverable part of a source import. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_assistant_source_import_part")
public class AidAssistantSourceImportPart extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long importId;
    private Integer partIndex;
    private String partKind;
    private String locatorJson;
    private Integer charStart;
    private Integer charEnd;
    private Integer charCount;
    private Integer wordCount;
    private String contentText;
    private String contentHash;
    private String status;
    private Integer attemptCount;
    private Long stateRevision;
    private String errorCode;
    private String errorDetail;
    private String leaseOwner;
    private Date leaseUntil;
    private Long fencingToken;
    private String delFlag;
}
