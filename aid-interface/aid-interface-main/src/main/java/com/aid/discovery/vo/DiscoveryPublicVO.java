package com.aid.discovery.vo;

import java.util.Date;

import com.aid.common.aid.oss.annotation.MediaUrl;
import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Public discovery media response. */
@Data
@Schema(description = "公开发现内容")
public class DiscoveryPublicVO
{
    private Long id;
    private String name;
    private String description;
    private String mediaType;
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
    private String sourceType;
    private String publisherName;
    @MediaUrl
    private String publisherAvatar;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
}
