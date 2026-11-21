package com.aid.aid.domain.media;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_media_task_anomaly")
public class AidMediaTaskAnomaly extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;
    @TableId
    private Long id;
    private Long taskId;
    private String status;
    private String anomalyType;
    private Integer failureCount;
    private String lastProviderStatus;
    private String lastErrorMessage;
    private Date firstDetectedTime;
    private Date lastDetectedTime;
    private Date lastRecheckTime;
    private Date nextRecheckTime;
    private String waitReason;
    private String resolutionType;
    private Date resolvedTime;
}
