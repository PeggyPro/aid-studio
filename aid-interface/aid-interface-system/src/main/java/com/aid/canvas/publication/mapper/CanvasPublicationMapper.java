package com.aid.canvas.publication.mapper;

import com.aid.canvas.publication.domain.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface CanvasPublicationMapper {
    @Insert("INSERT IGNORE INTO aid_canvas_publication_state(project_id) VALUES(#{id})")
    int ensure(@Param("id") Long id);

    @Insert("INSERT INTO aid_canvas_publication_state(project_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE project_id=project_id")
    int ensureForWrite(@Param("id") Long id);
    @Select("SELECT * FROM aid_canvas_publication_state WHERE project_id=#{id} FOR UPDATE")
    CanvasPublicationState lock(@Param("id") Long id);
    @Select("SELECT * FROM aid_canvas_publication_state WHERE project_id=#{id}")
    CanvasPublicationState state(@Param("id") Long id);
    @Update("UPDATE aid_canvas_publication_state SET version=version+1,published_id=#{publishedId},pending_id=#{pendingId},visible=#{visible},copy_enabled=#{copyEnabled},blocked=#{blocked},scene_version=#{sceneVersion},scene_document_json=#{sceneDocumentJson},atomic_scene_write=COALESCE(#{atomicSceneWrite},0),source_publication_id=#{sourcePublicationId},root_publication_id=#{rootPublicationId} WHERE project_id=#{projectId} AND version=#{version}")
    int save(CanvasPublicationState state);
    @Insert("INSERT INTO aid_canvas_scene_write_history(project_id, scene_version, before_document_json) VALUES(#{projectId}, #{version}, #{document})")
    int insertSceneHistory(@Param("projectId") Long projectId,@Param("version") long version,@Param("document") String document);
    @Select("SELECT before_document_json FROM aid_canvas_scene_write_history WHERE project_id=#{projectId} AND scene_version=#{version}")
    String sceneBefore(@Param("projectId") Long projectId,@Param("version") long version);
    @Delete("DELETE FROM aid_canvas_scene_write_history WHERE project_id=#{projectId} AND scene_version<=#{version}")
    int pruneSceneHistory(@Param("projectId") Long projectId,@Param("version") long version);
    @Insert("INSERT INTO aid_canvas_publication(project_id,owner_user_id,workspace_revision,scene_version,operation_id,request_hash,project_name,project_desc,video_url,cover_url,snapshot_json,content_hash,allow_copy,review_status,source_publication_id,root_publication_id) VALUES(#{projectId},#{ownerUserId},#{workspaceRevision},#{sceneVersion},#{operationId},#{requestHash},#{projectName},#{projectDesc},#{videoUrl},#{coverUrl},#{snapshotJson},#{contentHash},#{allowCopy},'PENDING',#{sourcePublicationId},#{rootPublicationId})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(CanvasPublication publication);
    @Update("UPDATE aid_canvas_publication SET video_url=#{videoUrl},cover_url=#{coverUrl} WHERE id=#{id}")
    int mediaUrls(CanvasPublication publication);
    @Select("SELECT * FROM aid_canvas_publication WHERE id=#{id}")
    CanvasPublication get(@Param("id") Long id);
    @Select("SELECT * FROM aid_canvas_publication WHERE project_id=#{id} ORDER BY id DESC LIMIT 1")
    CanvasPublication latest(@Param("id") Long id);
    @Select("SELECT * FROM aid_canvas_publication WHERE project_id=#{projectId} AND operation_id=#{operationId}")
    CanvasPublication byOperation(@Param("projectId") Long projectId,@Param("operationId") String operationId);
    @Update("UPDATE aid_canvas_publication SET review_status=#{status},review_reason=#{reason} WHERE id=#{id} AND review_status='PENDING'")
    int review(@Param("id") Long id,@Param("status") String status,@Param("reason") String reason);
    @Insert("INSERT INTO aid_canvas_publication_audit(publication_id,project_id,actor,action,reason) VALUES(#{publicationId},#{projectId},#{actor},#{action},#{reason})")
    int audit(@Param("publicationId") Long publicationId,@Param("projectId") Long projectId,@Param("actor") String actor,@Param("action") String action,@Param("reason") String reason);
    @Select("SELECT id,publication_id,actor,action,reason,create_time FROM aid_canvas_publication_audit WHERE project_id=#{projectId} ORDER BY id DESC LIMIT 200")
    List<Map<String,Object>> history(@Param("projectId") Long projectId);
    @Select("SELECT target_project_id FROM aid_canvas_publication_copy WHERE user_id=#{userId} AND operation_id=#{operationId} AND publication_id=#{publicationId} AND request_hash=#{requestHash}")
    Long copied(@Param("userId") Long userId,@Param("operationId") String operationId,@Param("publicationId") Long publicationId,@Param("requestHash") String requestHash);
    @Select("SELECT target_project_id,publication_id,request_hash FROM aid_canvas_publication_copy WHERE user_id=#{userId} AND operation_id=#{operationId}")
    Map<String,Object> copyOperation(@Param("userId") Long userId,@Param("operationId") String operationId);
    @Select("SELECT id,project_id,owner_user_id,workspace_revision,scene_version,project_name,project_desc,video_url,cover_url,allow_copy,review_status,review_reason,source_publication_id,root_publication_id,create_time FROM aid_canvas_publication WHERE project_id=#{id} ORDER BY id DESC LIMIT #{offset},#{limit}")
    List<CanvasPublication> versions(@Param("id") Long id,@Param("offset") int offset,@Param("limit") int limit);
    @Insert("INSERT INTO aid_canvas_publication_copy(user_id,operation_id,publication_id,request_hash,target_project_id) VALUES(#{userId},#{operationId},#{publicationId},#{requestHash},#{projectId})")
    int recordCopy(@Param("userId") Long userId,@Param("operationId") String operationId,@Param("publicationId") Long publicationId,@Param("requestHash") String requestHash,@Param("projectId") Long projectId);
    List<CanvasPublication> list(@Param("publicOnly") boolean publicOnly,@Param("status") String status,@Param("keyword") String keyword,@Param("offset") int offset,@Param("limit") int limit);
    long count(@Param("publicOnly") boolean publicOnly,@Param("status") String status,@Param("keyword") String keyword);
}
