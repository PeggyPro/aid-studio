package com.aid.aid.mapper;

import com.aid.aid.domain.media.AidMediaTaskAnomaly;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.util.List;
import java.util.Date;

@Mapper
public interface AidMediaTaskAnomalyMapper extends BaseMapper<AidMediaTaskAnomaly> {
    List<Long> selectUnobservedSubmissions(@Param("thresholdSeconds") int thresholdSeconds, @Param("limit") int limit);
    int insertSubmissionObservation(@Param("taskId") Long taskId, @Param("thresholdSeconds") int thresholdSeconds);
    List<Long> selectRecoveredSubmissions(@Param("limit") int limit);
    int insertObservation(AidMediaTaskAnomaly anomaly);

    List<Map<String,Object>> selectAdminRows(@Param("taskId") Long taskId,
                                             @Param("userId") Long userId,
                                             @Param("mediaType") String mediaType,
                                             @Param("anomalyType") String anomalyType,
                                             @Param("anomalyStatus") String anomalyStatus,
                                             @Param("detectedStartTime") Date detectedStartTime,
                                             @Param("detectedEndTime") Date detectedEndTime);

    Map<String,Object> selectAdminSummary();

    Map<String,Object> selectLatestActive();

    Map<String,Object> selectAdminRowById(@Param("id") Long id);
}
