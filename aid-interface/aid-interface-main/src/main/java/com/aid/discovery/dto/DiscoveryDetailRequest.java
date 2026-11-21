package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Discovery media detail request. */
@Data
@Schema(description = "发现内容详情请求")
public class DiscoveryDetailRequest
{
    @NotNull(message = "内容ID不能为空")
    @Positive(message = "内容ID必须大于0")
    private Long id;
}
