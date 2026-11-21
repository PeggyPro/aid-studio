package com.aid.director.mapper;

import com.aid.director.domain.DirectorEntities.Scene;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 场景版本元数据访问。 */
@Mapper
public interface DirectorSceneMapper extends BaseMapper<Scene> {
    // 固定按主键检查100行，不能对全部活动场景反复做无界孤儿扫描。
    @Select("SELECT s.id,CASE WHEN p.id IS NULL OR p.del_flag='1' THEN 1 ELSE 0 END AS deleted "
            + "FROM aid_director_scene s FORCE INDEX(PRIMARY) LEFT JOIN aid_comic_project p ON p.id=s.project_id "
            + "WHERE s.id>#{cursor} ORDER BY s.id LIMIT 100")
    java.util.List<Scene> scanProjectLifecycle(@Param("cursor") Long cursor);

    @Select("SELECT id,project_id,workspace_id,scope_key,scene_key,name,revision,object_key,content_hash,byte_size,last_request_id,last_request_hash,deleted,create_by,update_by,create_time,update_time FROM aid_director_scene WHERE id=#{id} FOR UPDATE")
    Scene lock(@Param("id") Long id);
}
