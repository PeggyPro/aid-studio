package com.aid.discovery.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Manager-only discovery review timeline item. */
@Data
@Schema(description = "发现内容审核操作记录")
public class DiscoveryReviewLogVO
{
    private String action;
    private String beforeStatus;
    private String afterStatus;
    private String reason;
    private String operatorType;
    private String operatorName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
