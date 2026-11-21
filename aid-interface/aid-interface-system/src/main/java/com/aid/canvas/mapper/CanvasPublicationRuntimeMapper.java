package com.aid.canvas.mapper;

import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** 发布冻结与无任务身份的导入结果。 */
@Mapper
public interface CanvasPublicationRuntimeMapper {
    @Select("SELECT root_resource_id FROM aid_canvas_publication_asset_bundle WHERE workspace_id=#{workspace} AND user_id=#{user} AND operation_id=#{op} AND request_hash=#{hash}") Long bundle(@Param("workspace") Long workspace,@Param("user") Long user,@Param("op") String op,@Param("hash") String hash);
    @Select("SELECT request_hash FROM aid_canvas_publication_asset_bundle WHERE workspace_id=#{workspace} AND user_id=#{user} AND operation_id=#{op} FOR UPDATE") String bundleHash(@Param("workspace") Long workspace,@Param("user") Long user,@Param("op") String op);
    @Insert("INSERT INTO aid_canvas_publication_asset_bundle(workspace_id,user_id,operation_id,request_hash,root_resource_id) VALUES(#{workspace},#{user},#{op},#{hash},#{root})") int bundleReceipt(@Param("workspace") Long workspace,@Param("user") Long user,@Param("op") String op,@Param("hash") String hash,@Param("root") Long root);
    @Update("UPDATE aid_canvas_resource SET metadata_json=#{metadata} WHERE id=#{id} AND workspace_id=#{workspace}") int bundleMetadata(@Param("id") Long id,@Param("workspace") Long workspace,@Param("metadata") String metadata);
    /** 当前读并持有项目/成员行锁，防止场景写入越过并发移除或降权。 */
    @Select("SELECT p.id FROM aid_comic_project p LEFT JOIN aid_project_member m ON m.project_id=p.id AND m.user_id=#{userId} WHERE p.id=#{projectId} AND p.project_type='canvas' AND p.del_flag='0' AND (p.user_id=#{userId} OR (m.status='ACTIVE' AND m.role_code='EDITOR')) FOR UPDATE")
    Long lockSceneEditor(@Param("projectId") Long projectId,@Param("userId") Long userId);

    @Select("<script>SELECT * FROM aid_canvas_resource WHERE workspace_id=#{workspaceId} AND status='ACTIVE' AND id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach> ORDER BY id FOR UPDATE</script>")
    List<CanvasResource> lockResources(@Param("workspaceId") Long workspaceId, @Param("ids") List<Long> ids);

    @Select("SELECT id FROM aid_canvas_node_result_selection WHERE workspace_id=#{workspaceId} ORDER BY id FOR UPDATE")
    List<Long> lockSelections(@Param("workspaceId") Long workspaceId);

    @Select("<script>SELECT COUNT(*) FROM aid_runtime_run WHERE workspace_id=#{workspaceId} AND del_flag='0' AND status NOT IN <foreach collection='terminalStatuses' item='status' open='(' close=')' separator=','>#{status}</foreach></script>")
    int countUnfinishedRuns(@Param("workspaceId") Long workspaceId,
                            @Param("terminalStatuses") List<String> terminalStatuses);

    @Insert("INSERT INTO aid_canvas_publication_import_result(workspace_id,object_id,resource_id,media_type) VALUES(#{workspaceId},#{objectId},#{resourceId},#{mediaType})")
    int insertResult(@Param("workspaceId") Long workspaceId, @Param("objectId") String objectId,
                     @Param("resourceId") Long resourceId, @Param("mediaType") String mediaType);

    @Insert("INSERT INTO aid_canvas_publication_import_identity(workspace_id,identity_type,source_id,target_id) VALUES(#{workspaceId},#{type},#{sourceId},#{targetId})")
    int insertIdentity(@Param("workspaceId") Long workspaceId,@Param("type") String type,
                       @Param("sourceId") String sourceId,@Param("targetId") String targetId);

    @Select("SELECT i.identity_type AS identityType,i.source_id AS sourceId,i.target_id AS targetId FROM aid_canvas_publication_import_identity i INNER JOIN aid_canvas_workspace w ON w.id=i.workspace_id WHERE w.project_id=#{projectId} ORDER BY i.identity_type,i.source_id")
    List<Map<String,Object>> identities(@Param("projectId") Long projectId);

    @Select("<script>SELECT w.project_id AS projectId,w.revision AS draftRevision,COALESCE(s.version,0) AS stateVersion,COALESCE(s.scene_version,0) AS sceneVersion,COALESCE(s.visible,0) AS visible,COALESCE(s.blocked,0) AS blocked,COALESCE(s.copy_enabled,0) AS copyEnabled,s.published_id AS publishedId,s.pending_id AS pendingId,s.source_publication_id AS sourcePublicationId,p.workspace_revision AS publishedRevision,p.scene_version AS publishedSceneVersion,p.review_status AS publishedReviewStatus,n.review_status AS pendingReviewStatus,l.review_status AS latestReviewStatus FROM aid_canvas_workspace w LEFT JOIN aid_canvas_publication_state s ON s.project_id=w.project_id LEFT JOIN aid_canvas_publication p ON p.id=s.published_id LEFT JOIN aid_canvas_publication n ON n.id=s.pending_id LEFT JOIN aid_canvas_publication l ON l.id=(SELECT MAX(latest.id) FROM aid_canvas_publication latest WHERE latest.project_id=w.project_id) WHERE w.del_flag='0' AND w.project_id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String,Object>> projectStates(@Param("ids") List<Long> projectIds);
}
