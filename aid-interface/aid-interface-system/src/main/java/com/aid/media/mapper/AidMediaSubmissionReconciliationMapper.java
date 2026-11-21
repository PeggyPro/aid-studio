package com.aid.media.mapper;

import com.aid.media.domain.AidMediaSubmissionReconciliation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** Persistence for media submission reconciliation state. */
public interface AidMediaSubmissionReconciliationMapper extends BaseMapper<AidMediaSubmissionReconciliation>
{
    AidMediaSubmissionReconciliation selectByTaskIdForUpdate(@Param("taskId") Long taskId);
}
