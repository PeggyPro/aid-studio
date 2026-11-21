package com.aid.previz.mapper;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.*;

public interface PrevizMembershipMapper {
 @Data class Project {private String id;private String title;private String ownerUserId;private String role;private String revision;private String documentVersion;}
 @Data class Member {private String userId;private String role;}
 @Select("SELECT p.id,p.title,p.owner_user_id,CASE WHEN p.owner_user_id=#{actor} THEN 'OWNER' ELSE m.role_code END AS role,p.revision,h.document_version FROM aid_previz_project p LEFT JOIN aid_previz_project_member m ON m.project_id=p.id AND m.user_id=#{actor} AND m.del_flag='0' LEFT JOIN aid_previz_workspace_head h ON h.project_id=p.id WHERE p.del_flag='0' AND (p.owner_user_id=#{actor} OR m.role_code IN ('EDITOR','VIEWER')) AND (#{before} IS NULL OR p.id < #{before}) ORDER BY p.id DESC LIMIT #{limit}")
 List<Project> page(@Param("actor")Long actor,@Param("before")String before,@Param("limit")int limit);
 @Select("SELECT id,owner_user_id,revision FROM aid_previz_project WHERE id=#{project} AND del_flag='0' FOR UPDATE")
 Project lock(String project);
 @Select("SELECT user_id,role_code AS role FROM aid_previz_project_member WHERE project_id=#{project} AND del_flag='0' ORDER BY user_id LIMIT 501")
 List<Member> members(String project);
 @Select("SELECT COUNT(*) FROM sys_user WHERE user_id=#{user} AND status='0' AND del_flag='0'")
 int userExists(Long user);
 @Update("UPDATE aid_previz_project SET revision=revision+1,update_by=#{actor},update_time=NOW() WHERE id=#{project} AND revision=#{revision}")
 int advance(@Param("project")String project,@Param("actor")Long actor,@Param("revision")long revision);
}
