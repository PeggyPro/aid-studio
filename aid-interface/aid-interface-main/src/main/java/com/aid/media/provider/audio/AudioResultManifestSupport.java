package com.aid.media.provider.audio;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.media.provider.AudioOutputItem;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.alibaba.fastjson2.JSON;
import java.util.List;

/** 在统一终态事务内记录全部音频变体与附属产物，不增加任务或扣费流水。 */
public final class AudioResultManifestSupport {
    private AudioResultManifestSupport() { }
    public static void persist(AidMediaTask task,List<AudioOutputItem> outputs,AidMediaResultMapper mapper) {
        if(outputs==null||outputs.isEmpty())return;
        String operator=task.getUserId()==null?"":task.getUserId().toString();
        int index=0;
        for(AudioOutputItem item:outputs) {
            if(item==null||item.getUrl()==null||item.getUrl().isBlank())throw new com.aid.common.exception.ServiceException("音频结果清单缺少地址");
            mapper.upsertTaskResult(task.getId(),index,item.getMediaType(),item.getUrl(),operator);
            LambdaUpdateWrapper<AidMediaResult> update=new LambdaUpdateWrapper<>();
            update.eq(AidMediaResult::getTaskId,task.getId()).eq(AidMediaResult::getResultIndex,index);
            update.set(AidMediaResult::getMetadataJson,MediaTaskPayloadSanitizer.sanitizeForStorage(JSON.toJSONString(item)));
            if(Boolean.TRUE.equals(item.getPersisted()))update.set(AidMediaResult::getOssUrl,item.getUrl());
            mapper.update(null,update);index++;
        }
    }
}
