package com.aid.discovery.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** User discovery image or video submission request. */
@Data
@Schema(description = "C端发现作品投稿请求")
public class UserDiscoverySubmitRequest
{
    @NotBlank(message = "媒体类型不能为空")
    @Pattern(regexp = "^(video|image)$", message = "媒体类型仅支持视频或图片")
    private String mediaType = "video";

    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称最多100个字符")
    private String name;

    @NotBlank(message = "描述不能为空")
    @Size(max = 1000, message = "描述最多1000个字符")
    private String description;

    @Size(max = 500, message = "封面地址过长")
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
}
