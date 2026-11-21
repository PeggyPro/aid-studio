package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeArtifactAdoption;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeArtifactAdoptionMapper extends BaseMapper<AidRuntimeArtifactAdoption> {
    @Select("""
            SELECT * FROM aid_runtime_artifact_adoption
            WHERE user_id=#{userId} AND target_kind=#{targetKind}
              AND target_object_id=#{targetObjectId} AND client_request_id=#{clientRequestId}
              AND del_flag='0' LIMIT 1 FOR UPDATE
            """)
    AidRuntimeArtifactAdoption selectIdempotencyForUpdate(@Param("userId") Long userId,
                                                          @Param("targetKind") String targetKind,
                                                          @Param("targetObjectId") String targetObjectId,
                                                          @Param("clientRequestId") String clientRequestId);

    @Select("""
            SELECT * FROM aid_runtime_artifact_adoption
            WHERE user_id=#{userId} AND session_id=#{sessionId} AND artifact_id=#{artifactId}
              AND status='SUCCEEDED' AND del_flag='0'
            ORDER BY id DESC LIMIT 1
            """)
    AidRuntimeArtifactAdoption selectLatestSucceeded(@Param("userId") Long userId,
                                                      @Param("sessionId") Long sessionId,
                                                      @Param("artifactId") Long artifactId);

    @Select("""
            <script>
            SELECT adoption.*
            FROM aid_runtime_artifact_adoption adoption
            INNER JOIN aid_runtime_artifact artifact
                ON artifact.id=adoption.artifact_id AND artifact.del_flag='0'
            INNER JOIN aid_runtime_run runtime_run
                ON runtime_run.id=artifact.producer_run_id AND runtime_run.del_flag='0'
               AND runtime_run.user_id=#{userId} AND runtime_run.session_id=adoption.session_id
            INNER JOIN (
                SELECT candidate.artifact_id, MAX(candidate.id) AS latest_id
                FROM aid_runtime_artifact_adoption candidate
                INNER JOIN aid_runtime_artifact candidate_artifact
                    ON candidate_artifact.id=candidate.artifact_id AND candidate_artifact.del_flag='0'
                INNER JOIN aid_runtime_run candidate_run
                    ON candidate_run.id=candidate_artifact.producer_run_id AND candidate_run.del_flag='0'
                   AND candidate_run.user_id=#{userId} AND candidate_run.session_id=candidate.session_id
                WHERE candidate.user_id=#{userId} AND candidate.status='SUCCEEDED'
                  AND candidate.del_flag='0' AND candidate.artifact_id IN
                  <foreach collection="artifactIds" item="artifactId" open="(" separator="," close=")">
                    #{artifactId}
                  </foreach>
                GROUP BY candidate.artifact_id
            ) latest ON latest.latest_id=adoption.id
            WHERE adoption.user_id=#{userId} AND adoption.status='SUCCEEDED' AND adoption.del_flag='0'
            ORDER BY adoption.id DESC
            </script>
            """)
    List<AidRuntimeArtifactAdoption> selectLatestSucceededForArtifacts(
            @Param("userId") Long userId,
            @Param("artifactIds") List<Long> artifactIds);
}
