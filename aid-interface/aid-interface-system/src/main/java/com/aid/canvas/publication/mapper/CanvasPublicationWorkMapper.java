package com.aid.canvas.publication.mapper;
import com.aid.canvas.publication.domain.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface CanvasPublicationWorkMapper {
    @Select("SELECT user_id FROM sys_user WHERE user_id=#{userId} AND status='0' AND del_flag='0' FOR UPDATE") Long lockUser(Long userId);
    @Select("SELECT * FROM aid_canvas_publication_copy_job WHERE user_id=#{uid} AND operation_id=#{op}") CanvasCopyJob operation(@Param("uid") Long uid,@Param("op") String op);
    @Select("SELECT * FROM aid_canvas_publication_copy_job WHERE id=#{id}") CanvasCopyJob job(Long id);
    @Select("SELECT * FROM aid_canvas_publication_copy_job WHERE id=#{id} FOR UPDATE") CanvasCopyJob lockJob(Long id);
    @Insert("INSERT INTO aid_canvas_publication_copy_job(user_id,operation_id,publication_id,request_hash,project_name,total_resources,total_bytes) VALUES(#{userId},#{operationId},#{publicationId},#{requestHash},#{projectName},#{totalResources},#{totalBytes})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertJob(CanvasCopyJob job);
    @Select("SELECT COUNT(*) FROM aid_canvas_publication_copy_job WHERE user_id=#{uid} AND status IN ('QUEUED','COPYING')") int active(Long uid);
    @Select("SELECT id FROM aid_canvas_publication_copy_job WHERE status='QUEUED' OR (status='COPYING' AND lease_until<NOW()) ORDER BY id LIMIT 32") List<Long> candidates();
    @Update("UPDATE aid_canvas_publication_copy_job SET status='CANCELLED',error_code='CANCELLED',lease_until=NULL WHERE status='COPYING' AND cancel_requested=1 AND lease_until<NOW()") int cancelExpired();
    @Update("UPDATE aid_canvas_publication_copy_job SET status='COPYING',worker_token=#{token},lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND),attempts=attempts+1,completed_resources=0,completed_bytes=0,error_code=NULL WHERE id=#{id} AND cancel_requested=0 AND (status='QUEUED' OR (status='COPYING' AND lease_until<NOW()))") int claim(@Param("id") Long id,@Param("token") String token);
    @Update("UPDATE aid_canvas_publication_copy_job SET lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE id=#{id} AND worker_token=#{token} AND status='COPYING' AND cancel_requested=0") int heartbeat(@Param("id") Long id,@Param("token") String token);
    @Update("UPDATE aid_canvas_publication_copy_job SET completed_resources=#{count},completed_bytes=#{bytes} WHERE id=#{id} AND worker_token=#{token} AND status='COPYING' AND cancel_requested=0 AND lease_until>NOW()") int progress(@Param("id") Long id,@Param("token") String token,@Param("count") int count,@Param("bytes") long bytes);
    @Update("UPDATE aid_canvas_publication_copy_job SET status='SUCCEEDED',target_project_id=#{target},completed_resources=total_resources,completed_bytes=total_bytes,lease_until=NULL WHERE id=#{id} AND worker_token=#{token} AND status='COPYING' AND cancel_requested=0 AND lease_until>NOW()") int succeeded(@Param("id") Long id,@Param("token") String token,@Param("target") Long target);
    @Update("UPDATE aid_canvas_publication_copy_job SET status=IF(cancel_requested=1,'CANCELLED','FAILED'),error_code=#{error},lease_until=NULL WHERE id=#{id} AND worker_token=#{token} AND status='COPYING'") int failed(@Param("id") Long id,@Param("token") String token,@Param("error") String error);
    @Update("UPDATE aid_canvas_publication_copy_job SET cancel_requested=1,status=IF(status='QUEUED','CANCELLED',status) WHERE id=#{id} AND status IN ('QUEUED','COPYING')") int cancel(Long id);
    @Update("UPDATE aid_canvas_publication_copy_job SET status='QUEUED',cancel_requested=0,worker_token=NULL,error_code=NULL,completed_resources=0,completed_bytes=0 WHERE id=#{id} AND status IN ('FAILED','CANCELLED')") int retry(Long id);
    @Insert("INSERT INTO aid_canvas_publication_file_intent(id,scope_id,kind,location,storage_identity,extension,expires_at) VALUES(#{id},#{scopeId},#{kind},#{location},#{storageIdentity},#{extension},DATE_ADD(NOW(),INTERVAL 1 HOUR))") int intent(CanvasPublicationFileIntent file);
    @Select("SELECT * FROM aid_canvas_publication_file_intent WHERE id=#{id} FOR UPDATE") CanvasPublicationFileIntent lockIntent(String id);
    @Select("SELECT * FROM aid_canvas_publication_file_intent WHERE scope_id=#{scope} ORDER BY id") List<CanvasPublicationFileIntent> scope(String scope);
    @Update("UPDATE aid_canvas_publication_file_intent SET expires_at=DATE_ADD(NOW(),INTERVAL 1 HOUR) WHERE scope_id=#{scope} AND status='STAGED'") int renew(String scope);
    @Update("UPDATE aid_canvas_publication_file_intent SET status='ATTACHED',publication_id=#{pub},project_id=#{project} WHERE id=#{id} AND status='STAGED' AND expires_at>NOW()") int attach(@Param("id") String id,@Param("pub") Long pub,@Param("project") Long project);
    @Update("UPDATE aid_canvas_publication_file_intent SET expires_at=NOW(),next_attempt_at=NOW() WHERE scope_id=#{scope} AND status IN ('STAGED','DELETING','DELETED')") int abandon(String scope);
    @Select("SELECT id FROM aid_canvas_publication_file_intent WHERE status IN ('STAGED','DELETING') AND expires_at<NOW() AND next_attempt_at<=NOW() ORDER BY next_attempt_at LIMIT 100") List<String> garbage();
    @Update("UPDATE aid_canvas_publication_file_intent SET status='DELETING',attempts=attempts+1,next_attempt_at=DATE_ADD(NOW(),INTERVAL 5 MINUTE) WHERE id=#{id} AND status IN ('STAGED','DELETING') AND expires_at<=NOW()") int deleting(String id);
    @Update("UPDATE aid_canvas_publication_file_intent SET status='DELETED' WHERE id=#{id} AND status='DELETING'") int deleted(String id);
    @Select("SELECT COUNT(*) FROM aid_canvas_publication_file_intent WHERE status='DELETING'") int pendingCleanup();
}
