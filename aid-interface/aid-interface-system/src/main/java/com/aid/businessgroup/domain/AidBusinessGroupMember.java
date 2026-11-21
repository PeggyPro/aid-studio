package com.aid.businessgroup.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_business_group_member")
public class AidBusinessGroupMember extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String memberType;
    private Long memberId;
    private Integer sortOrder;
    private String delFlag;
}
