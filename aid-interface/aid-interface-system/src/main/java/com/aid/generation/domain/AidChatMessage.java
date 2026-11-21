package com.aid.generation.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 通用会话中的一条有序消息。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_chat_message")
public class AidChatMessage extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long mediaTaskId;
    private Long seqNo;
    private String clientMessageId;
    private String requestHash;
    private String role;
    private String status;
    private String mediaType;
    private String modelCode;
    private String toolCallId;
    private String contentText;
    private String errorMessage;
    private Boolean contextAnchor;
    private String delFlag;
}
