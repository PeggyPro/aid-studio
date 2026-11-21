package com.aid.compute.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import java.util.Date;

@Data
@TableName("aid_compute_node")
public class AidComputeNode {
    @TableId private String nodeKey;
    private String name;
    private String poolCode;
    private String draftConfigJson;
    private Long revision;
    private String activeConfigJson;
    private Long activeRevision;
    private String state;
    private String healthStatus;
    private Long healthGeneration;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String healthJson;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private Date healthCheckedAt;
    private String draftWorkerId;
    private String draftCredentialCiphertext;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String verificationJson;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private Date verifiedAt;
    private String createBy;
    private String updateBy;
    private Date createTime;
    private Date updateTime;
}
