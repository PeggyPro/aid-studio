package com.aid.generation.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/** 通用生成与 Skill 共用的对话会话。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_chat_session")
public class AidChatSession extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long skillId;
    private Long projectId;
    private Long episodeId;
    private Long workspaceId;
    private String nodeObjectId;
    private String sessionType;
    private String clientSessionId;
    private String title;
    private String baseInputJson;
    private Boolean reasoningEnabled;
    private String reasoningLevel;
    private Boolean showReasoning;
    private String contextSummary;
    private Long summaryUntilSeq;
    private Long contextAnchorMessageId;
    private Long lastMessageSeq;
    private Boolean favorite;
    private String status;
    private Date lastActiveTime;
    private String delFlag;
}
