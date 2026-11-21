package com.aid.media.provider.audio;

import com.aid.aid.domain.AidAiModel;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** 写入时核对原生音频操作与版本、协议、资源权限配置，避免保存后才发现不可调用。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioConfigurationValidator {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final OfficialAudioContractRegistry contracts;
    public void validate(AidAiModel model,String provider) {
        if(model.getCapabilities()==null)return;
        for(var definition:model.getCapabilities())if(definition!=null&&definition.getBindings()!=null)for(var route:definition.getBindings()) {
            if(route==null||route.getCapability()==null||!route.getCapability().containsKey("officialAudioOperation"))continue;
            String operation=String.valueOf(route.getCapability().get("officialAudioOperation"));
            var contract=contracts.contract(operation);String vendor=contract.path("vendor").asText();
            String expected=vendor.equals("seed")?"volcengine_seed_audio":vendor;
            String protocol=vendor.equals("minimax")?"minimax-music":vendor.equals("seed")?"seed-audio":vendor+"-audio";
            if(!"audio".equals(model.getModelType())||!expected.equalsIgnoreCase(provider)||!protocol.equals(route.getProtocol()))throw new ServiceException("官方音频操作与模型分类、供应商或协议不匹配");
            String real=route.getUpstreamModel()==null||route.getUpstreamModel().isBlank()?model.getRealModelCode():route.getUpstreamModel();
            boolean available=false;for(var candidate:contracts.template(real).path("capabilities"))for(var binding:candidate.path("bindings"))if(operation.equals(binding.path("capability").path("officialAudioOperation").asText()))available=true;
            if(!available)throw new ServiceException("所选模型版本不支持此官方音频操作");
            if(route.getApiSuffix()==null||route.getApiSuffix().isBlank())throw new ServiceException("请配置官方音频提交接口路径");
            if(contract.path("async").asBoolean()&&(route.getTaskQuerySuffix()==null||route.getTaskQuerySuffix().isBlank()))throw new ServiceException("请配置官方音频异步任务查询路径");
            for(String field:List.of("officialSharedVoiceIds","officialSharedDictionaryIds","officialSharedVocalIds","officialSharedSeedSpeakerIds")) {
                Object value=route.getCapability().get(field);if(value==null)continue;
                if(!(value instanceof List<?> ids)||ids.size()>200||ids.stream().anyMatch(id->!(id instanceof String text)||text.isBlank()||text.length()>192))throw new ServiceException("共享音频资源列表无效："+field);
            }
        }
    }
}
