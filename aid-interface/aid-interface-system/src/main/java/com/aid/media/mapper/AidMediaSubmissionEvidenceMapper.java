package com.aid.media.mapper;

import com.aid.media.domain.AidMediaSubmissionEvidence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** Persistence for sanitized media submission evidence. */
public interface AidMediaSubmissionEvidenceMapper extends BaseMapper<AidMediaSubmissionEvidence>
{
    AidMediaSubmissionEvidence selectLatestByTaskId(@Param("taskId") Long taskId);
}
