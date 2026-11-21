package com.aid.media.service;
import com.aid.media.dto.MediaTaskAnomalyRequests;
import com.aid.media.dto.MediaTaskAnomalyConfigRequest;
import java.util.Map;
public interface MediaTaskAnomalyAdminService {
 Map<String,Object> summary();
 Map<String,Object> config();
 void updateConfig(MediaTaskAnomalyConfigRequest request,Long operatorId,String operator);
 Map<String,Object> list(Long taskId,Long userId,String mediaType,String anomalyType,String anomalyStatus,
                         java.util.Date detectedStartTime,java.util.Date detectedEndTime,int pageNum,int pageSize);
 Map<String,Object> detail(Long id);
 Map<String,Object> recheck(Long id,Long operatorId,String operator);
 Map<String,Object> recheckTask(Long taskId,MediaTaskAnomalyRequests.TaskRecheck request,Long operatorId,String operator);
 void continueWait(Long id,MediaTaskAnomalyRequests.ContinueWait request,Long operatorId,String operator);
 Map<String,Object> confirmFailure(Long id,MediaTaskAnomalyRequests.ConfirmFailure request,Long operatorId,String operator);
}
