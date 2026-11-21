package com.aid.director.mapper;

import com.aid.director.domain.DirectorEntities.Camera;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 机位占用元数据访问。 */
@Mapper
public interface DirectorCameraMapper extends BaseMapper<Camera> {
    @Select("SELECT id,scene_id,camera_key,control_fence,owner_room_id,owner_room_epoch,owner_expires_at,deleted,locked,create_by,update_by,create_time,update_time FROM aid_director_camera WHERE id=#{id} FOR UPDATE")
    Camera lock(@Param("id") Long id);
}
