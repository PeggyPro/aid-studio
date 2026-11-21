package com.aid.media.provider.audio;

import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** 官方内嵌音频先写受控存储，再用服务端句柄重放；任务表永不保存音频字节。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioInlineInputs {
    private static final String PREFIX="@aid-audio-blob:";
    private final OfficialAudioArtifacts artifacts;
    private final VerifiedMediaMetadataService metadata;

    public void stage(MediaAudioGenerateRequest request) {
        Map<String,String> references=request.getStagedAudioInputs()==null?new LinkedHashMap<>():new LinkedHashMap<>(request.getStagedAudioInputs());
        walk(request.getOptions(),(map,key,value)->{
            if(value.startsWith(PREFIX)) {if(!references.containsKey(value))throw new ServiceException("内嵌音频句柄不属于当前请求");return;}
            if(value.length()>34952536)throw new ServiceException("内嵌音频不得超过25MB");
            byte[] bytes;try{bytes=Base64.getDecoder().decode(value);}catch(Exception invalid){throw new ServiceException("内嵌音频Base64无效");}
            if(bytes.length==0||bytes.length>25*1024*1024)throw new ServiceException("内嵌音频不得超过25MB");
            String mime=String.valueOf(map.get("mime_type"));String format=switch(mime){case "audio/mpeg"->"mp3";case "audio/wav"->"wav";default->throw new ServiceException("内嵌音频格式不支持");};
            String marker=PREFIX+cn.hutool.crypto.SecureUtil.sha256().digestHex(bytes);
            if(!references.containsKey(marker))references.put(marker,artifacts.store(bytes,format,"reference",0,Map.of()).getUrl());
            map.put(key,marker);
        });
        request.setStagedAudioInputs(references);
    }
    public void materialize(Map<String,Object> body,MediaAudioGenerateRequest request) {
        walk(body,(map,key,value)->{
            if(!value.startsWith(PREFIX)||request.getStagedAudioInputs()==null)throw new ServiceException("内嵌音频未经过本站暂存");
            String source=request.getStagedAudioInputs().get(value);if(source==null)throw new ServiceException("内嵌音频句柄无效");
            map.put(key,Base64.getEncoder().encodeToString(metadata.readTrustedBytes(source,25*1024*1024)));
        });
    }
    @SuppressWarnings("unchecked")
    private static void walk(Object value,Handler handler) {
        if(value instanceof Map<?,?> raw) {
            Map<String,Object> map=(Map<String,Object>)raw;
            for(String key:new ArrayList<>(map.keySet())) {
                Object child=map.get(key);
                if(key.equals("content_base64")&&child instanceof String text)handler.accept(map,key,text);
                else walk(child,handler);
            }
        }else if(value instanceof List<?> list)for(Object child:list)walk(child,handler);
    }
    private interface Handler {void accept(Map<String,Object> map,String key,String value);}
}
