package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeArtifactChunkMapper extends BaseMapper<AidRuntimeArtifactChunk> {
    @Select("""
            SELECT c.* FROM aid_runtime_artifact_chunk c
            JOIN aid_runtime_artifact a ON a.id=c.artifact_id AND a.del_flag='0'
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE c.artifact_id=#{artifactId} AND c.status='ACTIVE' AND r.user_id=#{userId}
              AND c.chunk_index > #{afterChunkIndex}
            ORDER BY c.chunk_index ASC LIMIT #{limit}
            """)
    List<AidRuntimeArtifactChunk> selectAuthorizedPage(@Param("userId") Long userId,
                                                        @Param("artifactId") Long artifactId,
                                                        @Param("afterChunkIndex") int afterChunkIndex,
                                                        @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM aid_runtime_artifact_chunk c
            JOIN aid_runtime_artifact a ON a.id=c.artifact_id AND a.del_flag='0'
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE c.artifact_id=#{artifactId} AND c.status='ACTIVE' AND r.user_id=#{userId}
            """)
    int countAuthorized(@Param("userId") Long userId, @Param("artifactId") Long artifactId);

    @Select("""
            SELECT COALESCE(SUM(c.char_count),0) FROM aid_runtime_artifact_chunk c
            JOIN aid_runtime_artifact a ON a.id=c.artifact_id AND a.del_flag='0'
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE c.artifact_id=#{artifactId} AND c.status='ACTIVE' AND r.user_id=#{userId}
            """)
    int sumAuthorizedCharCount(@Param("userId") Long userId, @Param("artifactId") Long artifactId);

    @Select("""
            SELECT COALESCE(SUM(c.word_count),0) FROM aid_runtime_artifact_chunk c
            JOIN aid_runtime_artifact a ON a.id=c.artifact_id AND a.del_flag='0'
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE c.artifact_id=#{artifactId} AND c.status='ACTIVE' AND r.user_id=#{userId}
            """)
    int sumAuthorizedWordCount(@Param("userId") Long userId, @Param("artifactId") Long artifactId);

    @Select("""
            SELECT * FROM aid_runtime_artifact_chunk
            WHERE artifact_id=#{artifactId} AND status='ACTIVE'
            ORDER BY chunk_index ASC
            """)
    List<AidRuntimeArtifactChunk> selectAllActive(@Param("artifactId") Long artifactId);

    @Select("SELECT COUNT(*) FROM aid_runtime_artifact_chunk WHERE artifact_id=#{artifactId} AND status='ACTIVE'")
    int countActive(@Param("artifactId") Long artifactId);

    @Select("""
            SELECT * FROM aid_runtime_artifact_chunk
            WHERE artifact_id=#{artifactId} AND chunk_index=#{chunkIndex} AND status='ACTIVE'
            LIMIT 1
            """)
    AidRuntimeArtifactChunk selectActive(@Param("artifactId") Long artifactId,
                                          @Param("chunkIndex") int chunkIndex);

    @Select("""
            SELECT * FROM aid_runtime_artifact_chunk
            WHERE artifact_id=#{artifactId} AND status='ACTIVE'
            ORDER BY chunk_index DESC LIMIT 1
            """)
    AidRuntimeArtifactChunk selectLastActive(@Param("artifactId") Long artifactId);
}
