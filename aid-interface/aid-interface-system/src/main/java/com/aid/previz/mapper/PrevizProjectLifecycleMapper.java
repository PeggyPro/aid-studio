package com.aid.previz.mapper;

import com.aid.previz.domain.PrevizProject;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.*;

/** Project lifecycle receipts do not replace runtime or billing ledgers. */
public interface PrevizProjectLifecycleMapper {
    @Data class Receipt {private String projectId;private String requestId;private Long actorId;private String operation;private String requestDigest;private Long appliedRevision;private Integer deleted;}
    @Select("SELECT id,owner_user_id,title,revision,del_flag FROM aid_previz_project WHERE id=#{project} FOR UPDATE")
    PrevizProject lock(String project);
    @Select("SELECT project_id,request_id,actor_id,operation,request_digest,applied_revision,deleted FROM aid_previz_project_lifecycle_operation WHERE project_id=#{project} AND request_id=#{request}")
    Receipt receipt(@Param("project")String project,@Param("request")String request);
    @Insert("INSERT INTO aid_previz_project_lifecycle_operation(project_id,request_id,actor_id,operation,request_digest,applied_revision,deleted) VALUES(#{projectId},#{requestId},#{actorId},#{operation},#{requestDigest},#{appliedRevision},#{deleted})")
    int insert(Receipt receipt);
    @Update("UPDATE aid_previz_project SET del_flag=#{deleted},revision=revision+1,update_by=#{actor},update_time=NOW() WHERE id=#{project} AND revision=#{revision}")
    int transition(@Param("project")String project,@Param("actor")Long actor,@Param("revision")long revision,@Param("deleted")String deleted);
    @Select("SELECT EXISTS(SELECT 1 FROM aid_runtime_run r JOIN aid_chat_session s ON s.id=r.session_id WHERE s.module_code='PREVIZ_3DBOX' AND JSON_UNQUOTE(JSON_EXTRACT(s.base_input_json,'$.previzProjectId'))=#{project} AND (r.status NOT IN ('SUCCEEDED','COMPLETED','FAILED','STOPPED','CANCELLED') OR EXISTS(SELECT 1 FROM aid_runtime_run_step t WHERE t.run_id=r.id AND t.status IN ('SUBMITTING','RUNNING','RECONCILING','UNKNOWN')) OR EXISTS(SELECT 1 FROM aid_runtime_run_step t JOIN aid_media_task m ON m.id=t.media_task_id WHERE t.run_id=r.id AND m.status NOT IN ('SUCCEEDED','SUCCESS','FAILED','CANCELLED'))))")
    int activeRuntime(String project);
    @Select("SELECT EXISTS(SELECT 1 FROM aid_compute_reservation WHERE project_key=#{project} AND state NOT IN ('BUILT','FAILED','CANCELLED'))")
    int activeCompute(String project);
    @Select("SELECT id,title,owner_user_id,revision,del_flag,update_time FROM aid_previz_project WHERE owner_user_id=#{actor} AND del_flag='2' AND (#{before} IS NULL OR id<#{before}) ORDER BY id DESC LIMIT #{limit}")
    List<PrevizProject> trash(@Param("actor")Long actor,@Param("before")String before,@Param("limit")int limit);
}
