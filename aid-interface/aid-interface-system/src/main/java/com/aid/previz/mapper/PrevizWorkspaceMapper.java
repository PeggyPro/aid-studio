package com.aid.previz.mapper;

import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.*;

/** Only version metadata is stored here; documents and sources remain on private storage. */
public interface PrevizWorkspaceMapper {
    @Data class Head { private String projectId; private String sourceVersion; private String documentVersion; private Long revision; }
    @Data class Revision { private String projectId; private Long revision; private String sourceVersion; private String documentVersion; private Long actorId; private String operation; }
    @Select("SELECT owner_user_id FROM aid_previz_project WHERE id=#{project} AND del_flag='0' FOR UPDATE")
    Long lockProject(String project);
    @Update("UPDATE aid_previz_project SET title=#{title},update_time=NOW() WHERE id=#{project} AND del_flag='0'")
    int title(@Param("project")String project,@Param("title")String title);
    @Insert("INSERT INTO aid_previz_workspace_head(project_id,source_version,revision) VALUES(#{project},#{legacy},0) ON DUPLICATE KEY UPDATE project_id=project_id")
    int initialize(@Param("project") String project,@Param("legacy") String legacy);
    @Select("SELECT project_id,source_version,document_version,revision FROM aid_previz_workspace_head WHERE project_id=#{project}")
    Head read(String project);
    @Select("SELECT project_id,source_version,document_version,revision FROM aid_previz_workspace_head WHERE project_id=#{project} FOR UPDATE")
    Head lock(String project);
    @Update("UPDATE aid_previz_workspace_head SET source_version=#{source},document_version=#{document},revision=revision+1 WHERE project_id=#{project} AND revision=#{revision}")
    int update(@Param("project") String project,@Param("source") String source,@Param("document") String document,@Param("revision") long revision);
    @Insert("INSERT INTO aid_previz_document_revision(project_id,revision,source_version,document_version,actor_id,operation) VALUES(#{projectId},#{revision},#{sourceVersion},#{documentVersion},#{actorId},#{operation})")
    int insertRevision(Revision revision);
    @Select("SELECT project_id,revision,source_version,document_version,actor_id,operation FROM aid_previz_document_revision WHERE project_id=#{project} AND revision < #{before} ORDER BY revision DESC LIMIT #{limit}")
    List<Revision> history(@Param("project") String project,@Param("before") long before,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM aid_previz_document_revision WHERE project_id=#{project} AND document_version=#{document}")
    int published(@Param("project") String project,@Param("document") String document);
}
