package com.aid.assistant.creative.series.mapper;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.assistant.domain.AidAssistantSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** Narrow read/lock mapper for project-scope series adoption. */
public interface AssistantSeriesAdoptionMapper {
    @Select("""
            SELECT id,user_id,project_id,episode_id,session_type,module_code,workspace_id,status,del_flag
            FROM aid_chat_session WHERE id=#{sessionId} AND del_flag='0' LIMIT 1
            """)
    AidAssistantSession selectSession(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT id,user_id,project_id,episode_id,session_type,module_code,workspace_id,status,del_flag
            FROM aid_chat_session WHERE id=#{sessionId} AND del_flag='0' LIMIT 1 FOR UPDATE
            """)
    AidAssistantSession lockSession(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT id,user_id,project_type,is_public,status,default_gen_mode,default_creation_mode,del_flag
            FROM aid_comic_project WHERE id=#{projectId} AND del_flag='0' LIMIT 1
            """)
    AidComicProject selectProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT id,user_id,project_type,is_public,status,default_gen_mode,default_creation_mode,del_flag
            FROM aid_comic_project WHERE id=#{projectId} AND del_flag='0' LIMIT 1 FOR UPDATE
            """)
    AidComicProject lockProject(@Param("projectId") Long projectId);

    @Select("""
            SELECT id,project_id,episode_no,comic_title,comic_desc,user_id,gen_mode,creation_mode,status,del_flag
            FROM aid_comic_episode
            WHERE project_id=#{projectId} AND del_flag='0'
            ORDER BY episode_no ASC, id ASC
            """)
    List<AidComicEpisode> selectEpisodes(@Param("projectId") Long projectId);

    @Select("""
            SELECT id,project_id,episode_no,comic_title,comic_desc,user_id,gen_mode,creation_mode,status,del_flag
            FROM aid_comic_episode
            WHERE project_id=#{projectId} AND del_flag='0'
            ORDER BY episode_no ASC, id ASC FOR UPDATE
            """)
    List<AidComicEpisode> lockEpisodes(@Param("projectId") Long projectId);

    @Select("""
            SELECT id,project_id,episode_id,user_id,is_extracted,comic_version,status,del_flag,
                   LOWER(SHA2(COALESCE(original_text,''),256)) AS content_hash
            FROM aid_comic_script
            WHERE project_id=#{projectId} AND status=1 AND del_flag='0'
            ORDER BY episode_id ASC, id DESC
            """)
    List<SeriesCurrentScriptProjection> selectCurrentScripts(@Param("projectId") Long projectId);

    @Select("""
            SELECT id,project_id,episode_id,user_id,is_extracted,comic_version,status,del_flag,
                   LOWER(SHA2(COALESCE(original_text,''),256)) AS content_hash
            FROM aid_comic_script
            WHERE project_id=#{projectId} AND status=1 AND del_flag='0'
            ORDER BY episode_id ASC, id DESC FOR UPDATE
            """)
    List<SeriesCurrentScriptProjection> lockCurrentScripts(@Param("projectId") Long projectId);

    /** Reads bodies only for the bounded catalog page after its global metadata digest is known. */
    @Select("""
            <script>
            SELECT id,project_id,episode_id,user_id,original_text,is_extracted,comic_version,status,del_flag
            FROM aid_comic_script
            WHERE project_id=#{projectId} AND status=1 AND del_flag='0'
              AND episode_id IN
              <foreach collection="episodeIds" item="episodeId" open="(" separator="," close=")">
                #{episodeId}
              </foreach>
            ORDER BY episode_id ASC, id DESC
            </script>
            """)
    List<AidComicScript> selectCurrentScriptBodies(@Param("projectId") Long projectId,
                                                    @Param("episodeIds") Collection<Long> episodeIds);
}
