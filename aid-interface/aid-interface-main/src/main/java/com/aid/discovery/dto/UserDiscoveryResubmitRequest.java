package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** User discovery video resubmission request. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "C端发现视频重新投稿请求")
public class UserDiscoveryResubmitRequest extends UserDiscoverySubmitRequest
{
    @NotNull(message = "内容ID不能为空")
    @Positive(message = "内容ID必须大于0")
    private Long id;
}
