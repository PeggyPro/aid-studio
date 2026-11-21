package com.aid.generation.vo;

import lombok.Builder;
import lombok.Data;

/** 通用生成任务响应。 */
@Data
@Builder
public class GenerationResponse {
    private Object sessionId;
    private Object messageId;
    private GenerationTaskView task;
}
