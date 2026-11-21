package com.aid.runtime.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Per-user question view state. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_runtime_input_view")
public class AidRuntimeInputView extends BaseEntity
{
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long inputRequestId;
    private Long userId;
    private String cursorQuestionId;
}
