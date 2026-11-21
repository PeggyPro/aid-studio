package com.aid.businessgroup.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_business_group")
public class AidBusinessGroup extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String groupCode;
    private Long parentId;
    private String name;
    private String iconUrl;
    private String description;
    private Integer sortOrder;
    private String visibility;
    private Long ownerUserId;
    private String status;
    private String delFlag;
}
