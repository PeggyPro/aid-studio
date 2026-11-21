package com.aid.media.dto;

/** 登录用户或后台操作主体按字符串或整数任务编号查询。 */
public record OfficialAudioTaskQuery(
        @jakarta.validation.constraints.NotNull(message="任务编号必填")
        @jakarta.validation.constraints.Min(value=1,message="任务编号无效") Long taskId) { }
