package com.aid.aid.domain;

import java.io.Serializable;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 发现内容审核流水对象。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("aid_discovery_media_review_log")
public class AidDiscoveryMediaReviewLog extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发现内容ID */
    private Long mediaId;

    /** 动作：SUBMIT / RESUBMIT / APPROVE / REJECT / REVOKE / ADMIN_CREATE / UPDATE / DELETE */
    private String action;

    /** 变更前状态 */
    private String beforeStatus;

    /** 变更后状态 */
    private String afterStatus;

    /** 动作说明 */
    private String reason;

    /** 操作者类型：USER / ADMIN */
    private String operatorType;

    /** 操作者ID */
    private Long operatorId;

    /** 操作者名称 */
    private String operatorName;

    /** 删除标志：0正常 1删除 */
    private String delFlag;
}
