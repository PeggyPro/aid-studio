package com.aid.media.provider.audio;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import java.util.Set;

/** 以显式协议绑定供应商，模型名称不参与协议猜测。 */
public class OfficialAudioProviderClient implements AudioProviderClient {
    private final String vendor;
    private final String protocol;
    private final Set<String> providers;
    private final Set<String> models;
    private final OfficialAudioProtocolSupport support;
    public OfficialAudioProviderClient(String vendor,String protocol,Set<String> providers,Set<String> models,OfficialAudioProtocolSupport support){
        this.vendor=vendor;this.protocol=protocol;this.providers=providers;this.models=models;this.support=support;
    }
    public String protocol(){return protocol;}
    public boolean supportsProviderCode(String code){return code!=null&&providers.stream().anyMatch(p->p.equalsIgnoreCase(code.trim()));}
    public boolean supportsModel(String code){return code!=null&&models.contains(code);}
    public boolean usesStructuredInput(){return true;}
    public boolean usesSynchronousExecution(AiModelConfigVo config,MediaAudioGenerateRequest request){return support.synchronous(config,request);}
    public void validate(AiModelConfigVo config,MediaAudioGenerateRequest request,boolean verify){support.validate(vendor,config,request,verify);}
    public ProviderSubmitResult submit(AiModelConfigVo config,MediaAudioGenerateRequest request){return support.submit(vendor,config,request);}
    public ProviderTaskResult query(AiModelConfigVo config,String id){return support.query(vendor,config,id);}
}
