package com.aid.assistant.source.mapper;

import com.aid.assistant.source.domain.AidAssistantSourceImportPart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

public interface AidAssistantSourceImportPartMapper extends BaseMapper<AidAssistantSourceImportPart>
{
    List<AidAssistantSourceImportPart> selectByImportIdForUpdate(@Param("importId") Long importId);

    List<AidAssistantSourceImportPart> selectRequestedForUpdate(@Param("importId") Long importId,
                                                                @Param("partIndexes") Collection<Integer> partIndexes);

    List<Long> selectRecoverableIds(@Param("now") Date now, @Param("limit") int limit);

    int claim(@Param("id") Long id,
              @Param("expectedStateRevision") Long expectedStateRevision,
              @Param("expectedStatus") String expectedStatus,
              @Param("targetStatus") String targetStatus,
              @Param("owner") String owner,
              @Param("leaseUntil") Date leaseUntil,
              @Param("now") Date now,
              @Param("operator") String operator);
}
