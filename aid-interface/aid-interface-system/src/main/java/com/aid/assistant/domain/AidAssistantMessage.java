package com.aid.assistant.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** Persisted assistant message. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_chat_message")
public class AidAssistantMessage extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long runtimeRunId;
    private Long turnId;
    private Long mediaTaskId;
    private Long seqNo;
    private String clientMessageId;
    private String requestHash;
    private String role;
    private String status;
    private String messageKind;
    private String mediaType;
    private String modelCode;
    private String toolCallId;
    private String contentText;
    private String errorMessage;
    private Boolean contextAnchor;
    private String delFlag;
}
