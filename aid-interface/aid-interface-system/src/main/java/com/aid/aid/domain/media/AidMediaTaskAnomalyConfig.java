package com.aid.aid.domain.media;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_media_task_anomaly_config")
public class AidMediaTaskAnomalyConfig extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.INPUT)
    private Long id;
    private Boolean enabled;
    private Integer anomalyThresholdSeconds;
    private Integer autoRecheckIntervalSeconds;
    private Integer adminRefreshSeconds;
}
