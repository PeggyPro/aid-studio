package com.aid.media.provider.audio;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderSubmitResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只解析官方错误字段，优先业务码，不从请求文本和元数据猜测错误。 */
@Component
public final class OfficialAudioErrorMapper {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JsonNode mappings;
    public OfficialAudioErrorMapper() {
        try(var in=new ClassPathResource("model/audio/error-mappings.json").getInputStream()){mappings=JSON.readTree(in);}
        catch(Exception ex){throw new IllegalStateException("音频错误映射无法加载",ex);}
    }

    public TaskErrorResult classify(String vendor,int http,JsonNode root) {
        JsonNode error=root==null?JSON.createObjectNode():root;
        String code=code(vendor,error),message=message(vendor,error);
        String selected=mappings.path(vendor).path(code==null?"":code).asText("");
        if("minimax".equals(vendor)&&("2153".equals(code)||http==410))selected="UPSTREAM_SERVICE_NOT_OPEN";
        if("seed".equals(vendor)&&"40000020".equals(code)) {
            selected=message.contains("LLMSafetyFilterTriggered")?"UPSTREAM_CONTENT_FILTERED"
                    :message.contains("DurationOutOfRange")?"USER_AUDIO_INVALID"
                    :"USER_INPUT_INVALID";
        }
        if("mureka".equals(vendor)) {
            if(http==429)selected=message.contains("exceeded your current quota")?"PROVIDER_BALANCE_INSUFFICIENT":"UPSTREAM_RATE_LIMITED";
            else if(http==451)selected="UPSTREAM_CONTENT_FILTERED";
            else if(http==403)selected="UPSTREAM_SERVICE_NOT_OPEN";
        }
        TaskErrorCode type;
        try{type=TaskErrorCode.valueOf(selected);}catch(Exception ignored){type=switch(http){
            case 400,422->TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE;
            case 401->TaskErrorCode.UPSTREAM_AUTH_INVALID;
            case 402->TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED;
            case 403,410->TaskErrorCode.UPSTREAM_SERVICE_NOT_OPEN;
            case 413->TaskErrorCode.USER_FILE_TOO_LARGE;
            case 429->TaskErrorCode.UPSTREAM_RATE_LIMITED;
            case 408,504->TaskErrorCode.UPSTREAM_TIMEOUT;
            case 500,502,503->TaskErrorCode.UPSTREAM_SERVER_ERROR;
            default->TaskErrorCode.AI_GENERATION_FAILED;
        };}
        String safe=ProviderErrorSanitizer.safeMessage(message,"上游音频生成失败");
        return TaskErrorResult.of(type,safe);
    }

    public ProviderSubmitResult rejected(String vendor,int http,JsonNode body,String requestId) {
        TaskErrorResult error=classify(vendor,http,body);
        Map<String,Object> audit=new LinkedHashMap<>();
        audit.put("provider",vendor);audit.put("http_status",http);audit.put("upstream_code",code(vendor,body));
        audit.put("request_id",requestId);audit.put("error_code",error.getErrorCode());
        try{return ProviderSubmitResult.builder().confirmedFailureCode(error.getErrorCode())
                .errorDetailJson(TaskErrorSnapshot.write(error)).submissionHttpStatus(http)
                .submissionErrorMessage(error.getUserMessage()).rawResponse(JSON.writeValueAsString(audit)).build();}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    public static String code(String vendor,JsonNode root) {
        if(root==null)return null;
        if("elevenlabs".equals(vendor)&&root.path("error").isTextual())return root.path("error").asText();
        JsonNode value=switch(vendor){
            case "minimax"->root.path("base_resp").path("status_code");
            case "elevenlabs"->root.path("detail").path("code");
            default->root.path("code");
        };
        if(value.isMissingNode()||value.isNull()) {
            value="elevenlabs".equals(vendor)?root.path("detail").path("status"):root.path("error").path("code");
        }
        return value.isValueNode()&&!value.isNull()?value.asText():null;
    }

    private static String message(String vendor,JsonNode root) {
        JsonNode value=switch(vendor){
            case "minimax"->root.path("base_resp").path("status_msg");
            case "elevenlabs"->root.path("detail").path("message");
            default->root.path("message");
        };
        if(!value.isTextual())value=root.path("error").path("message");
        if(!value.isTextual())value=root.path("failed_reason");
        return value.isTextual()?value.asText():"上游音频生成失败";
    }
}
