package com.aid.generation.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 会话消息中的有序文本或资源内容块。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_chat_message_part")
public class AidChatMessagePart extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Integer partSeq;
    private String partType;
    private String slotName;
    private String textContent;
    private Long resourceId;
    private String resourceRole;
    private String resourceUrl;
    private String thumbnailUrl;
    private String mimeType;
    private String metadataJson;
    private String contextScope;
    private String delFlag;
}
