package com.aid.assistant.source.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Server-owned file identity and durable parsing/finalization state. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_assistant_source_import")
public class AidAssistantSourceImport extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private Long projectId;
    private String originalName;
    private String mediaType;
    private Long byteCount;
    private String byteHash;
    private String storageLocator;
    private String parserCode;
    private String parserVersion;
    private String status;
    private Long stateRevision;
    private Integer version;
    private Integer partCount;
    private Integer readyPartCount;
    private Long sourceRevisionId;
    private String sourceRevision;
    private String contentHash;
    private Integer charCount;
    private Integer wordCount;
    private String errorCode;
    private String errorDetail;
    private String leaseOwner;
    private Date leaseUntil;
    private Long fencingToken;
    private String delFlag;
}
