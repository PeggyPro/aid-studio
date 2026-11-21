package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeArtifactMapper extends BaseMapper<AidRuntimeArtifact> {
    /** Metadata-only historical result read; never loads source text, routing or media URLs. */
    @Select("""
            <script>
            SELECT a.id,a.logical_artifact_id,a.revision,a.artifact_type,a.content_hash,
                   a.review_status,a.status,a.producer_run_id,a.producer_step_id
            FROM aid_runtime_artifact a
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            JOIN aid_chat_session s ON s.id=r.session_id AND s.del_flag='0'
            WHERE r.user_id=#{userId} AND s.user_id=#{userId} AND r.session_id=#{sessionId}
              AND r.id=#{runId} AND a.del_flag='0'
              <if test='cursor != null'>AND a.id &lt; #{cursor}</if>
            ORDER BY a.id DESC LIMIT #{limit}
            </script>
            """)
    List<AidRuntimeArtifact> selectConversationReferencePage(@Param("userId") Long userId,
            @Param("sessionId") Long sessionId, @Param("runId") Long runId,
            @Param("cursor") Long cursor, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT a.* FROM aid_runtime_artifact a
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE r.user_id=#{userId} AND a.del_flag='0'
              <if test='runId != null'>AND a.producer_run_id=#{runId}</if>
              <if test='logicalArtifactId != null and logicalArtifactId != ""'>AND a.logical_artifact_id=#{logicalArtifactId}</if>
              <if test='artifactType != null and artifactType != ""'>AND a.artifact_type=#{artifactType}</if>
              <if test='reviewStatus != null and reviewStatus != ""'>AND a.review_status=#{reviewStatus}</if>
              <if test='cursor != null'>AND a.id &lt; #{cursor}</if>
            ORDER BY a.id DESC LIMIT #{limit}
            </script>
            """)
    List<AidRuntimeArtifact> selectAuthorizedPage(@Param("userId") Long userId,
                                                   @Param("runId") Long runId,
                                                   @Param("logicalArtifactId") String logicalArtifactId,
                                                   @Param("artifactType") String artifactType,
                                                   @Param("reviewStatus") String reviewStatus,
                                                   @Param("cursor") Long cursor,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT a.* FROM aid_runtime_artifact a
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE a.id=#{artifactId} AND a.del_flag='0' AND r.user_id=#{userId} LIMIT 1
            """)
    AidRuntimeArtifact selectAuthorized(@Param("userId") Long userId,
                                         @Param("artifactId") Long artifactId);

    @Select("""
            <script>
            SELECT a.* FROM aid_runtime_artifact a
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE r.user_id=#{userId} AND a.del_flag='0' AND a.id IN
            <foreach collection='artifactIds' item='artifactId' open='(' separator=',' close=')'>
              #{artifactId}
            </foreach>
            ORDER BY a.id ASC
            </script>
            """)
    List<AidRuntimeArtifact> selectAuthorizedByIds(@Param("userId") Long userId,
                                                    @Param("artifactIds") List<Long> artifactIds);

    @Select("""
            SELECT a.* FROM aid_runtime_artifact a
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            JOIN aid_chat_session s ON s.id=r.session_id AND s.del_flag='0'
            WHERE a.id=#{artifactId} AND a.del_flag='0' AND r.user_id=#{userId}
              AND r.session_id=#{sessionId} AND s.user_id=#{userId} LIMIT 1
            """)
    AidRuntimeArtifact selectAuthorizedForSession(@Param("userId") Long userId,
                                                   @Param("sessionId") Long sessionId,
                                                   @Param("artifactId") Long artifactId);

    @Select("SELECT * FROM aid_runtime_artifact WHERE id=#{artifactId} AND del_flag='0' LIMIT 1 FOR UPDATE")
    AidRuntimeArtifact selectByIdForUpdate(@Param("artifactId") Long artifactId);

    @Select("""
            SELECT * FROM aid_runtime_artifact
            WHERE logical_artifact_id=#{logicalArtifactId} AND del_flag='0'
            ORDER BY revision DESC LIMIT 1 FOR UPDATE
            """)
    AidRuntimeArtifact selectLatestForUpdate(@Param("logicalArtifactId") String logicalArtifactId);

    @Select("""
            SELECT * FROM aid_runtime_artifact
            WHERE producer_run_id=#{runId} AND producer_step_id=#{stepId}
              AND logical_artifact_id=#{logicalArtifactId} AND del_flag='0'
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    AidRuntimeArtifact selectProducedForUpdate(@Param("runId") Long runId,
                                                @Param("stepId") Long stepId,
                                                @Param("logicalArtifactId") String logicalArtifactId);
}
