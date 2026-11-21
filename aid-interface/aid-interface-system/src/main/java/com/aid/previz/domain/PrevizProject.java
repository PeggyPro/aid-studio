package com.aid.previz.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** 独立三维工程的归属和配置。 */
@Data
@TableName("aid_previz_project")
public class PrevizProject
{
    @TableId
    private String id;
    private Long ownerUserId;
    private String title;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    private String accessRole;
    @JsonIgnore
    private String createRequestId;
    @JsonIgnore
    private String createRequestDigest;
    private String preferredEngine;
    private String modelCode;
    private Long revision;
    private String delFlag;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
}
