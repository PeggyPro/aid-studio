package com.aid.media.provider.audio;

import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.OfficialAudioTaskView;
import com.aid.media.service.IMediaGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 任务查询只读本地权威状态，不触发用户轮询向供应商重复查询。 */
@Service
@RequiredArgsConstructor
public class OfficialAudioTaskAccess {
    private final AidMediaTaskMapper tasks;
    private final IMediaGenerationService generation;

    /** 已受理请求先按持久身份恢复，不受后续模型停用、改价或路由编辑影响。 */
    public OfficialAudioTaskView replay(com.aid.media.dto.MediaAudioGenerateRequest request) {
        if(request==null||request.getOfficialIdempotencyKey()==null)return null;
        var task=tasks.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.aid.aid.domain.media.AidMediaTask>lambdaQuery()
                .eq(com.aid.aid.domain.media.AidMediaTask::getIdempotencyKey,request.getOfficialIdempotencyKey())
                .eq(com.aid.aid.domain.media.AidMediaTask::getUserId,request.getUserId()).last("LIMIT 1"));
        if(task==null)return null;
        String digest=null;
        try {digest=com.alibaba.fastjson2.JSON.parseObject(task.getRequestJson()).getString("officialInputDigest");}
        catch(RuntimeException ignored) { }
        if(!"AUDIO".equals(task.getMediaType())||request.getOfficialInputDigest()==null
                ||!request.getOfficialInputDigest().equals(digest))
            throw com.aid.media.service.MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("requestKey已被其他音频参数使用");
        return OfficialAudioTaskView.from(generation.queryTaskLocal(task.getId()));
    }

    public OfficialAudioTaskView query(Long taskId,Long userId) {
        if(taskId==null||taskId<=0||userId==null||userId==0)throw new ServiceException("任务不存在");
        var task=tasks.selectById(taskId);
        if(task==null||!java.util.Objects.equals(userId,task.getUserId())||!"AUDIO".equals(task.getMediaType()))
            throw new ServiceException("任务不存在");
        return OfficialAudioTaskView.from(generation.queryTaskLocal(taskId));
    }
}
