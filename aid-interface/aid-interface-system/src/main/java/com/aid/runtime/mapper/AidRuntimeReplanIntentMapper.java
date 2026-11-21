package com.aid.runtime.mapper;

import com.aid.runtime.domain.AidRuntimeReplanIntent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface AidRuntimeReplanIntentMapper extends BaseMapper<AidRuntimeReplanIntent>
{
    AidRuntimeReplanIntent selectActiveByRunIdForUpdate(@Param("runId") Long runId);

    AidRuntimeReplanIntent selectByCommandIdForUpdate(@Param("commandId") Long commandId);
}
