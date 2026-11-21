package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Manager discovery media page request. */
@Data
@Schema(description = "后台发现内容分页请求")
public class AdminDiscoveryPageRequest
{
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数必须大于0")
    @Max(value = 100, message = "每页最多查询100条")
    private Integer pageSize = 20;

    @Size(max = 100, message = "搜索名称最多100个字符")
    private String name;

    @Pattern(regexp = "^(video|image)?$", message = "媒体类型不正确")
    private String mediaType;

    @Pattern(regexp = "^(USER|ADMIN)?$", message = "来源类型不正确")
    private String sourceType;

    @Pattern(regexp = "^(PENDING|APPROVED|REJECTED|REVOKED)?$", message = "审核状态不正确")
    private String reviewStatus;
}
