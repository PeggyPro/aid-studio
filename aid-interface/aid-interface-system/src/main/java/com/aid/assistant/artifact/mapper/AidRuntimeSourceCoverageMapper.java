package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeSourceCoverage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeSourceCoverageMapper extends BaseMapper<AidRuntimeSourceCoverage> {
    @Select("""
            SELECT * FROM aid_runtime_source_coverage
            WHERE run_id=#{runId} AND source_revision_id=#{sourceRevisionId}
              AND chunk_index=#{chunkIndex} AND purpose=#{purpose} AND del_flag='0'
            LIMIT 1 FOR UPDATE
            """)
    AidRuntimeSourceCoverage selectForUpdate(@Param("runId") Long runId,
                                             @Param("sourceRevisionId") Long sourceRevisionId,
                                             @Param("chunkIndex") Integer chunkIndex,
                                             @Param("purpose") String purpose);

    @Select("""
            SELECT c.* FROM aid_runtime_source_coverage c
            JOIN aid_runtime_run r ON r.id=c.run_id AND r.del_flag='0'
            JOIN aid_runtime_source_revision s ON s.id=c.source_revision_id AND s.del_flag='0'
            WHERE c.run_id=#{runId} AND c.source_revision_id=#{sourceRevisionId}
              AND c.del_flag='0' AND r.user_id=#{userId} AND s.user_id=#{userId}
              AND (#{cursor} IS NULL OR c.id < #{cursor})
            ORDER BY c.id DESC LIMIT #{limit}
            """)
    List<AidRuntimeSourceCoverage> selectAuthorizedPage(@Param("userId") Long userId,
                                                        @Param("runId") Long runId,
                                                        @Param("sourceRevisionId") Long sourceRevisionId,
                                                        @Param("cursor") Long cursor,
                                                        @Param("limit") int limit);

    @Select("""
            SELECT COUNT(DISTINCT c.chunk_index) FROM aid_runtime_source_coverage c
            JOIN aid_runtime_run r ON r.id=c.run_id AND r.del_flag='0'
            JOIN aid_runtime_source_revision s ON s.id=c.source_revision_id AND s.del_flag='0'
            WHERE c.run_id=#{runId} AND c.source_revision_id=#{sourceRevisionId}
              AND c.del_flag='0' AND c.status='ACTIVE'
              AND c.coverage_status IN ('READ','USED')
              AND r.user_id=#{userId} AND s.user_id=#{userId}
            """)
    int countAuthorizedCovered(@Param("userId") Long userId,
                               @Param("runId") Long runId,
                               @Param("sourceRevisionId") Long sourceRevisionId);
}
