package com.aid.discovery.vo;

import java.util.Date;
import java.util.List;

import com.aid.common.aid.oss.annotation.MediaUrl;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** User/manager discovery media response with review metadata. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "发现投稿管理信息")
public class DiscoveryManageVO
{
    private Long id;
    private Long userId;
    private String submitterName;
    private String sourceType;
    private String mediaType;
    private String name;
    private String description;
    @MediaUrl
    private String coverUrl;
    @MediaUrl
    private String mediaUrl;
    @MediaUrl
    private String previewVideoUrl;
    private Integer mediaWidth;
    private Integer mediaHeight;
    private String aspectRatio;
    private String ratioSource;
    private String reviewStatus;
    private String reviewReason;
    private String reviewBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date reviewTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
    private Integer sortOrder;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
    @Schema(description = "仅后台管理接口返回")
    private String promptText;

    @Schema(description = "仅后台详情返回的审核操作时间线")
    private List<DiscoveryReviewLogVO> reviewLogs;
}
