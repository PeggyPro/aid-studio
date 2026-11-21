package com.aid.context.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

/** Derived conversation memory; authoritative messages and business records remain separate. */
@Data
@TableName("aid_conversation_context_memory")
public class AidConversationContextMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String scopeType;
    private String scopeKey;
    private Long revision;
    private String sourceDigest;
    private String memoryJson;
    private Date createTime;
    private Date updateTime;
    private String delFlag;
}
