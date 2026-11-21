package com.aid.director.mapper;

import com.aid.director.domain.DirectorEntities.Take;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import lombok.Data;
import java.util.List;

/** 录制状态元数据访问。 */
@Mapper
public interface DirectorTakeMapper extends BaseMapper<Take> {
    @Select("SELECT id,project_id,workspace_id,scene_id,camera_row_id,host_user_id,origin_room_id,room_id,room_epoch,control_fence,context_version,start_request_id,start_request_hash,stop_request_id,host_stopped,state,durable_frame_seq,chunk_count,frame_count,duration_ms,manifest_key,content_hash,scene_revision_at_start,applied_revision,applied_clip_id,apply_request_hash,recovered,last_activity_at,expire_at,create_by,update_by,create_time,update_time FROM aid_director_take WHERE id=#{id} FOR UPDATE")
    Take lock(@Param("id") Long id);

    @Select({"<script>",
            "SELECT t.id AS takeId,t.applied_clip_id AS appliedClipId,s.scene_key AS sceneKey,c.camera_key AS cameraKey",
            "FROM aid_director_take t",
            "JOIN aid_director_scene s ON s.id=t.scene_id AND s.deleted=0",
            "JOIN aid_director_camera c ON c.id=t.camera_row_id AND c.scene_id=s.id AND c.deleted=0",
            "WHERE t.state='APPLIED' AND t.project_id=#{projectId} AND s.project_id=#{projectId}",
            "AND t.workspace_id &lt;=&gt; #{workspaceId} AND s.workspace_id &lt;=&gt; #{workspaceId}",
            "AND t.id IN <foreach collection='takeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<AppliedReference> selectAppliedReferences(@Param("projectId") Long projectId,
            @Param("workspaceId") Long workspaceId, @Param("takeIds") List<Long> takeIds);

    /** 已应用录制的场景和机位归属。 */
    @Data
    class AppliedReference {
        private Long takeId;
        private String appliedClipId;
        private String sceneKey;
        private String cameraKey;
    }
}
