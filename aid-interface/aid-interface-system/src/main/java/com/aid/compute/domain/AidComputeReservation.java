package com.aid.compute.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import java.util.Date;

/** Resource reservation for an existing business task; unknown execution remains occupied. */
@Data
@TableName("aid_compute_reservation")
public class AidComputeReservation {
    @TableId private String requestKey;
    private String requestDigest;
    private String nodeKey;
    private String taskKey;
    private String projectKey;
    private Long configRevision;
    private String engine;
    private String taskKind;
    private Long cpuMillis;
    private Long memoryBytes;
    private Long scratchBytes;
    private String state;
    private String workerJobId;
    private String terminalReceiptDigest;
    @TableField(exist = false)
    private Long queuePosition;
    private Date createTime;
    private Date updateTime;
}
