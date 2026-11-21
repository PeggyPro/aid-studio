package com.aid.media.dto;

import lombok.Data;
import java.util.Map;

/** 官方音频调用仅开放模型、能力及其参数；身份和账务归属由服务端确定。 */
@Data
public class OfficialAudioGenerateRequest {
    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private String modelCode;
    private String capabilityCode;
    private Map<String,Object> parameters;
    private String requestKey;

    public void requireSubmissionKey() {
        if(requestKey==null||!requestKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw com.aid.media.service.MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("生成请求需要有效的requestKey");
    }

    public MediaAudioGenerateRequest toInternal(Long userId) {
        if(userId==null||modelCode==null||modelCode.isBlank()||capabilityCode==null||capabilityCode.isBlank())
            throw new com.aid.common.exception.ServiceException("请选择音频模型和能力");
        MediaAudioGenerateRequest request=new MediaAudioGenerateRequest();
        request.setModelName(modelCode);request.setCapabilityCode(capabilityCode);
        request.setOptions(parameters==null?new java.util.LinkedHashMap<>():new java.util.LinkedHashMap<>(parameters));
        request.setUserId(userId);request.setBizTaskType("official_audio");
        if(requestKey!=null) {
            requireSubmissionKey();
            request.setOfficialIdempotencyKey(cn.hutool.crypto.SecureUtil.sha256("official-audio:"+userId+":"+requestKey));
        }
        try {
            request.setOfficialInputDigest(cn.hutool.crypto.SecureUtil.sha256(CANONICAL.writeValueAsString(
                    Map.of("model",modelCode,"capability",capabilityCode,"parameters",request.getOptions()))));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new com.aid.common.exception.ServiceException("音频请求无法归一化");}
        return request;
    }
}
