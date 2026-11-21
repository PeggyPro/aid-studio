package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 发现内容媒体对象。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("aid_discovery_media")
public class AidDiscoveryMedia extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 投稿用户ID */
    private Long userId;

    /** 来源类型：USER / ADMIN */
    private String sourceType;

    /** 媒体类型：video / image */
    private String mediaType;

    /** 内容名称 */
    private String name;

    /** 内容描述 */
    private String description;

    /** 封面地址；图片内容也使用此字段 */
    @MediaUrl
    private String coverUrl;

    /** 视频地址 */
    @MediaUrl
    private String mediaUrl;

    /** 视频预览地址 */
    @MediaUrl
    private String previewVideoUrl;

    /** 媒体宽度 */
    private Integer mediaWidth;

    /** 媒体高度 */
    private Integer mediaHeight;

    /** 规范化媒体比例 */
    private String aspectRatio;

    /** 比例来源：measured / declared */
    private String ratioSource;

    /** 后台预留提示词 */
    @ToString.Exclude
    private String promptText;

    /** 审核状态：PENDING / APPROVED / REJECTED / REVOKED */
    private String reviewStatus;

    /** 最近一次审核说明 */
    private String reviewReason;

    /** 最近审核人 */
    private String reviewBy;

    /** 最近审核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date reviewTime;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 排序值 */
    private Integer sortOrder;

    /** 展示状态：0启用 1停用 */
    private String status;

    /** 删除标志：0正常 1删除 */
    private String delFlag;
}
