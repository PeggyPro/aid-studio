package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AidRuntimeSourceRevisionMapper extends BaseMapper<AidRuntimeSourceRevision> {
    @Select("""
            SELECT * FROM aid_runtime_source_revision
            WHERE user_id=#{userId} AND source_kind=#{sourceKind}
              AND source_object_id=#{sourceObjectId} AND source_revision=#{sourceRevision}
              AND content_hash=#{contentHash} AND del_flag='0' LIMIT 1
            """)
    AidRuntimeSourceRevision selectImmutable(@Param("userId") Long userId,
                                             @Param("sourceKind") String sourceKind,
                                             @Param("sourceObjectId") String sourceObjectId,
                                             @Param("sourceRevision") String sourceRevision,
                                             @Param("contentHash") String contentHash);

    @Select("""
            SELECT * FROM aid_runtime_source_revision
            WHERE id=#{sourceRevisionId} AND user_id=#{userId} AND del_flag='0' LIMIT 1
            """)
    AidRuntimeSourceRevision selectAuthorized(@Param("userId") Long userId,
                                              @Param("sourceRevisionId") Long sourceRevisionId);
}
