package com.aid.aid.domain.media;

import lombok.Data;
import java.util.Date;

/** A particular execution admission; never an outcome or billing receipt. */
@Data
public class MediaExecutionPermit {
    private String token;
    private Long taskId;
    private Long userId;
    private String userBucket;
    private String modelCode;
    private Long providerId;
    private String state;
    private String executionMode;
    private String ownerId;
    private Date leaseUntil;
    private String releaseReason;
    private Date createTime;
    private Date updateTime;
    private Date releasedAt;
}
