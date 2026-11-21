package com.aid.director.mapper;

import com.aid.director.domain.DirectorEntities.Chunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 录制分块元数据访问。 */
@Mapper
public interface DirectorChunkMapper extends BaseMapper<Chunk> {
    @Select("SELECT id,take_id,chunk_no,first_frame_seq,last_frame_seq,start_ms,end_ms,frame_count,byte_size,content_hash,object_key,create_by,update_by,create_time,update_time FROM aid_director_take_chunk WHERE id=#{id} FOR UPDATE")
    Chunk lock(@Param("id") Long id);
}
