package com.aid.director.canvas.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 使用既有唯一索引锁定成员，无新增公共表或权限规则。 */
@Mapper
public interface DirectorCanvasLockMapper {
    @Select("SELECT id FROM aid_project_member WHERE project_id=#{project} AND user_id=#{user} FOR UPDATE")
    Long member(@Param("project") Long projectId, @Param("user") Long userId);
}
