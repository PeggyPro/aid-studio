package com.aid.runtime.mapper;

import com.aid.runtime.domain.AidRuntimeRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Runtime run persistence. */
@Mapper
public interface AidRuntimeRunMapper extends BaseMapper<AidRuntimeRun>
{
    @Select("""
            SELECT id,user_id,session_id,project_id,workspace_id,plan_revision,status,del_flag
            FROM aid_runtime_run WHERE id=#{runId} AND user_id=#{userId} AND del_flag='0' LIMIT 1
            """)
    AidRuntimeRun selectConversationResultScope(@Param("runId") Long runId, @Param("userId") Long userId);
}
