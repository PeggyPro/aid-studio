package com.aid.aid.domain.media;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_media_task_anomaly_audit")
public class AidMediaTaskAnomalyAudit extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;
    @TableId
    private Long id;
    private Long anomalyId;
    private Long taskId;
    private String action;
    private Long operatorId;
    private String operatorName;
    private String reason;
    private String beforeStatus;
    private String afterStatus;
    private String detailJson;
}
