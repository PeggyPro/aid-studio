package com.aid.media.provider.audio;

import com.aid.aid.domain.media.AidOfficialAudioResource;
import com.aid.aid.mapper.AidOfficialAudioResourceMapper;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.AudioOutputItem;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 上游对象ID不是授权凭证；C端只能引用本人的资源或管理员明确配置的共享音色。 */
@Service
@RequiredArgsConstructor
public class OfficialAudioResourceService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final AidOfficialAudioResourceMapper resources;
    public void validate(AiModelConfigVo config,MediaAudioGenerateRequest request,OfficialAudioContractRegistry.Plan plan) {
        if("operator_official_audio".equals(request.getBizTaskType()))return;
        if(plan.vendor().equals("elevenlabs")) {
            checkEleven(config,request,plan.body());return;
        }
        if(plan.vendor().equals("seed")) {
            if(plan.body().get("references") instanceof List<?> refs)for(Object raw:refs)if(raw instanceof Map<?,?> ref&&ref.get("speaker") instanceof String id&&!shared(config,"officialSharedSeedSpeakerIds",id))
                throw new ServiceException("参考音色未在当前模型开放");
            return;
        }
        if(!plan.vendor().equals("mureka"))return;
        for(String field:List.of("reference_id","melody_id","instrumental_id","image_id","video_id","upload_audio_id","song_id","vocal_id","background_id")) {
            Object value=plan.body().get(field);if(value==null)continue;
            String id=String.valueOf(value);String kind=field.equals("song_id")?"song":field.equals("vocal_id")?"vocal":"file";
            if(kind.equals("vocal")&&shared(config,"officialSharedVocalIds",id))continue;
            if(request.getUserId()==null)throw new ServiceException("音频资源缺少用户身份");
            var query=Wrappers.<AidOfficialAudioResource>lambdaQuery().eq(AidOfficialAudioResource::getProviderId,config.getProviderId())
                    .eq(AidOfficialAudioResource::getUserId,request.getUserId()).eq(AidOfficialAudioResource::getResourceKind,kind).eq(AidOfficialAudioResource::getResourceId,id);
            String purpose=switch(field){case "reference_id"->"reference";case "melody_id"->"melody";case "instrumental_id"->"instrumental";case "image_id","video_id"->"soundtrack";case "background_id"->"lyrics-video";case "upload_audio_id"->plan.operation().endsWith("song_remix")?"remix":"audio";default->null;};
            if(purpose!=null)query.eq(AidOfficialAudioResource::getPurpose,purpose);
            if(kind.equals("song"))query.ge(AidOfficialAudioResource::getCreateTime,Date.from(java.time.ZonedDateTime.now().minusMonths(1).toInstant()));
            if(resources.selectCount(query)==0)throw new ServiceException("音频参考资源不可用或不属于当前用户");
        }
    }
    public void record(AiModelConfigVo config,MediaAudioGenerateRequest request,OfficialAudioContractRegistry.Plan plan,ProviderSubmitResult result) {
        if(plan.vendor().equals("elevenlabs")) {
            if(plan.operation().equals("elevenlabs.post.music_upload")&&result.getDirectText()!=null)try {
                save(config.getProviderId(),request.getUserId(),"song",JSON.readTree(result.getDirectText()).path("song_id").asText(null),null);
            }catch(ServiceException invalid){throw invalid;}catch(Exception invalid){throw new ServiceException("音乐参考资源登记失败");}
            if(result.getUsage()!=null&&result.getUsage().get("requestId") instanceof String id)save(config.getProviderId(),request.getUserId(),"request",id,null);
            recordOutputs(config.getProviderId(),request.getUserId(),result.getAudioOutputs());return;
        }
        if(!plan.vendor().equals("mureka"))return;
        if(plan.resultKind().equals("json")&&result.getDirectText()!=null)try {
            var root=JSON.readTree(result.getDirectText());
            if(plan.operation().equals("mureka.post.files_upload"))save(config.getProviderId(),request.getUserId(),"file",root.path("id").asText(null),String.valueOf(plan.body().get("purpose")));
            if(plan.operation().equals("mureka.post.song_vocal_clone"))save(config.getProviderId(),request.getUserId(),"vocal",root.path("vocal_id").asText(root.path("id").asText(null)),null);
        }catch(ServiceException invalid){throw invalid;}catch(Exception invalid){throw new ServiceException("上游音频资源登记失败");}
        recordOutputs(config.getProviderId(),request.getUserId(),result.getAudioOutputs());
    }
    public void recordOutputs(Long provider,Long user,List<AudioOutputItem> outputs){
        if(outputs!=null)for(var item:outputs)if(item.getUpstreamArtifactId()!=null)save(provider,user,"song",item.getUpstreamArtifactId(),null);
    }
    private void save(Long provider,Long user,String kind,String id,String purpose){
        if(id==null||id.isBlank())return;
        if(provider==null||user==null||id.length()>192)throw new ServiceException("上游音频资源身份无效");
        var item=new AidOfficialAudioResource();item.setProviderId(provider);item.setUserId(user);item.setResourceKind(kind);item.setResourceId(id);item.setPurpose(purpose);item.setCreateTime(new Date());
        try{resources.insert(item);}catch(org.springframework.dao.DuplicateKeyException duplicate){/* Receipt replay preserves the same ownership. */}
    }
    private void checkEleven(AiModelConfigVo config,MediaAudioGenerateRequest request,Object value) {
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()) {
            String key=String.valueOf(entry.getKey());Object child=entry.getValue();
            if(child instanceof String id) {
                if(key.equals("voice_id")&&!shared(config,"officialSharedVoiceIds",id))throw new ServiceException("此音色未在当前模型开放");
                if(key.equals("song_id"))requireOwned(config,request,"song",id);
                if(key.equals("pronunciation_dictionary_id")&&!shared(config,"officialSharedDictionaryIds",id))throw new ServiceException("发音词典未在当前模型开放");
            }
            if(Set.of("voices","previous_request_ids","next_request_ids").contains(key)&&child instanceof List<?> list)for(Object id:list) {
                if(key.equals("voices")){if(!shared(config,"officialSharedVoiceIds",String.valueOf(id)))throw new ServiceException("此音色未在当前模型开放");}
                else requireOwned(config,request,"request",String.valueOf(id));
            }
            checkEleven(config,request,child);
        }else if(value instanceof List<?> list)for(Object child:list)checkEleven(config,request,child);
    }
    private void requireOwned(AiModelConfigVo config,MediaAudioGenerateRequest request,String kind,String id) {
        if(request.getUserId()==null||resources.selectCount(Wrappers.<AidOfficialAudioResource>lambdaQuery().eq(AidOfficialAudioResource::getProviderId,config.getProviderId())
                .eq(AidOfficialAudioResource::getUserId,request.getUserId()).eq(AidOfficialAudioResource::getResourceKind,kind).eq(AidOfficialAudioResource::getResourceId,id))==0)
            throw new ServiceException("音频参考资源不可用或不属于当前用户");
    }
    private boolean shared(AiModelConfigVo config,String field,String id){
        try{for(var value:JSON.readTree(config.getCapabilityJson()).path(field))if(id.equals(value.asText()))return true;}catch(Exception ignored){}return false;
    }
}
