package com.aid.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Manager discovery review operation request. */
@Data
@Schema(description = "后台发现内容审核操作请求")
public class AdminDiscoveryReviewRequest
{
    @NotNull(message = "内容ID不能为空")
    @Positive(message = "内容ID必须大于0")
    private Long id;

    @NotBlank(message = "审核操作不能为空")
    @Pattern(regexp = "^(APPROVE|REJECT|REVOKE)$", message = "审核操作不正确")
    private String action;

    @Size(max = 500, message = "审核说明最多500个字符")
    private String reason;
}
