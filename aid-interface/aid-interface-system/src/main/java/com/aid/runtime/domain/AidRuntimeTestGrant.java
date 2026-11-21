package com.aid.runtime.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("aid_runtime_test_grant")
public class AidRuntimeTestGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long adminUserId;
    private String memberType;
    private Long memberId;
    private String draftDigest;
    private Date expiresAt;
    private String status;
    private String clientRequestId;
    private Long runId;
    private Date createTime;
    private Date updateTime;
}
