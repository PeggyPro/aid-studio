package com.aid.assistant.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/** Assistant conversation session. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_chat_session")
public class AidAssistantSession extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long skillId;
    private Long projectId;
    private Long episodeId;
    private Long workspaceId;
    private String nodeObjectId;
    private String sessionType;
    private String entryTransport;
    private String clientSessionId;
    private String moduleCode;
    private String mode;
    private String title;
    private String baseInputJson;
    private Boolean reasoningEnabled;
    private String reasoningLevel;
    private Boolean showReasoning;
    private String contextSummary;
    private Long summaryUntilSeq;
    private Long contextAnchorMessageId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long activeRuntimeRunId;
    private Long stateRevision;
    private Long lastMessageSeq;
    private Long lastEventSeq;
    private Boolean favorite;
    private String status;
    private Date lastActiveTime;
    private String delFlag;
}
