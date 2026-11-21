package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Per-question saved answer draft. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_input_draft")
public class AidRuntimeInputDraft extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long inputRequestId;
    private Long userId;
    private String questionId;
    private Long draftRevision;
    private String choiceIdsJson;
    private String freeText;
    private String answerState;
    private String lastClientOperationId;
    private String lastPayloadDigest;
}
