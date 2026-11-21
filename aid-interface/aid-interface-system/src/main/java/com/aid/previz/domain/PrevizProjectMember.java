package com.aid.previz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 独立三维工程的成员角色。 */
@Data
@TableName("aid_previz_project_member")
public class PrevizProjectMember
{
    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectId;
    private Long userId;
    private String roleCode;
    private String delFlag;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
}
