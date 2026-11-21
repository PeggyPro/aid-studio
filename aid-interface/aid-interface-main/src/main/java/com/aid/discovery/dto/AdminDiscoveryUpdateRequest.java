package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Manager discovery media update request. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台发现内容修改请求")
public class AdminDiscoveryUpdateRequest extends AdminDiscoverySaveRequest
{
    @NotNull(message = "内容ID不能为空")
    @Positive(message = "内容ID必须大于0")
    private Long id;
}
