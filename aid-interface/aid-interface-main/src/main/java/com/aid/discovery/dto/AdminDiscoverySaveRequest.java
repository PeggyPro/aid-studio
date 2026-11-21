package com.aid.discovery.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Manager discovery media create request. */
@Data
@Schema(description = "后台发现内容保存请求")
public class AdminDiscoverySaveRequest
{
    @NotBlank(message = "媒体类型不能为空")
    @Pattern(regexp = "^(video|image)$", message = "媒体类型不正确")
    private String mediaType;

    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称最多100个字符")
    private String name;

    @NotBlank(message = "描述不能为空")
    @Size(max = 1000, message = "描述最多1000个字符")
    private String description;

    @NotBlank(message = "封面图片不能为空")
    @Size(max = 500, message = "封面图片地址过长")
    @MediaUrl
    private String coverUrl;

    @Size(max = 500, message = "视频地址过长")
    @MediaUrl
    private String mediaUrl;

    @Size(max = 500, message = "预览视频地址过长")
    @MediaUrl
    private String previewVideoUrl;

    @NotNull(message = "媒体宽度不能为空")
    @Positive(message = "媒体宽度必须大于0")
    private Integer mediaWidth;

    @NotNull(message = "媒体高度不能为空")
    @Positive(message = "媒体高度必须大于0")
    private Integer mediaHeight;

    @Size(max = 4000, message = "提示词最多4000个字符")
    private String promptText;

    @Min(value = 0, message = "排序不能小于0")
    @Max(value = 999999, message = "排序不能大于999999")
    private Integer sortOrder = 0;

    @Pattern(regexp = "^[01]$", message = "展示状态不正确")
    private String status = "0";
}
