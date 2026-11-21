package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** Current user's discovery submissions page request. */
@Data
@Schema(description = "我的发现投稿分页请求")
public class UserDiscoveryPageRequest
{
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数必须大于0")
    @Max(value = 100, message = "每页最多查询100条")
    private Integer pageSize = 20;
}
