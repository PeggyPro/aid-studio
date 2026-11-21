package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeSourceChunkMapper extends BaseMapper<AidRuntimeSourceChunk> {
    @Select("""
            SELECT c.id,c.source_revision_id,c.chunk_index,c.char_start,c.char_end,c.char_count,
                   c.word_count,c.locator_json,c.content_text,c.content_hash,c.status,
                   c.create_by,c.create_time
            FROM aid_runtime_source_chunk c
            JOIN aid_runtime_source_revision s ON s.id=c.source_revision_id
            WHERE s.id=#{sourceRevisionId} AND s.user_id=#{userId} AND s.status='ACTIVE'
              AND s.del_flag='0' AND c.status='ACTIVE' AND c.chunk_index > #{afterChunkIndex}
            ORDER BY c.chunk_index ASC LIMIT #{limit}
            """)
    List<AidRuntimeSourceChunk> selectAuthorizedPage(@Param("userId") Long userId,
                                                      @Param("sourceRevisionId") Long sourceRevisionId,
                                                      @Param("afterChunkIndex") int afterChunkIndex,
                                                      @Param("limit") int limit);

    @Select("""
            SELECT c.id,c.source_revision_id,c.chunk_index,c.char_start,c.char_end,c.char_count,
                   c.word_count,c.locator_json,c.content_text,c.content_hash,c.status,
                   c.create_by,c.create_time
            FROM aid_runtime_source_chunk c
            JOIN aid_runtime_source_revision s ON s.id=c.source_revision_id
            WHERE s.id=#{sourceRevisionId} AND s.user_id=#{userId} AND s.status='ACTIVE'
              AND s.del_flag='0' AND c.status='ACTIVE' AND c.chunk_index=#{chunkIndex}
            LIMIT 1
            """)
    AidRuntimeSourceChunk selectAuthorizedChunk(@Param("userId") Long userId,
                                                 @Param("sourceRevisionId") Long sourceRevisionId,
                                                 @Param("chunkIndex") int chunkIndex);
}
