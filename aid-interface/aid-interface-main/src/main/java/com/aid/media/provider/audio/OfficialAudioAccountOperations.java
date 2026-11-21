package com.aid.media.provider.audio;

import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.spi.ProviderUpstreamAccountOperationsExtension;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 官方账户额度查询；现金、字符与逐服务试用次数分别展示。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioAccountOperations implements ProviderUpstreamAccountOperationsExtension {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final OfficialAudioTransport transport;
    private final java.util.concurrent.ConcurrentMap<Long,Object> locks=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long,Cached> cache=new java.util.concurrent.ConcurrentHashMap<>();
    private record Cached(String identity,long at,Map<String,Object> value) { }
    public boolean supports(String code){return code!=null&&Set.of("mureka","sonilo","elevenlabs").contains(code.toLowerCase(Locale.ROOT));}
    public Map<String,Object> capabilities(AidAiProvider provider){
        String code=provider.getProviderCode().toLowerCase(Locale.ROOT);
        return Map.of("balance",true,"upstreamTasks",false,"balanceKind",code.equals("elevenlabs")?"characters":code.equals("sonilo")?"account":"money",
                "balanceUnit",code.equals("elevenlabs")?"characters":code.equals("mureka")?"CNY":"USD",
                "balanceDelayNotice","金额、字符额度和逐服务试用次数分别统计，历史消费不代表剩余额度。");
    }
    public Map<String,Object> balance(AidAiProvider provider,Long start,Long end,String resource){
        if(provider.getId()==null)throw new ServiceException("供应商身份无效");
        String identity=cn.hutool.crypto.SecureUtil.sha256(Arrays.asList(provider.getBaseUrl(),provider.getApiKey(),provider.getAuthHeader(),provider.getAuthPrefix(),provider.getAccountOperationsJson()).toString());
        synchronized(locks.computeIfAbsent(provider.getId(),ignored->new Object())) {
            Cached previous=cache.get(provider.getId());
            if(previous!=null&&previous.identity().equals(identity)&&System.currentTimeMillis()-previous.at()<1500)return previous.value();
            Map<String,Object> value=Collections.unmodifiableMap(query(provider));
            cache.put(provider.getId(),new Cached(identity,System.currentTimeMillis(),value));return value;
        }
    }
    private Map<String,Object> query(AidAiProvider provider){
        JsonNode paths;
        try{paths=JSON.readTree(provider.getAccountOperationsJson());}catch(Exception invalid){throw new ServiceException("请配置官方账户查询路径");}
        if(paths==null||!paths.isObject())throw new ServiceException("请配置官方账户查询路径");
        AiModelConfigVo config=new AiModelConfigVo();cn.hutool.core.bean.BeanUtil.copyProperties(provider,config);
        config.setExtraHeadersJson(provider.getExtraHeaders());
        Map<String,Object> result=new LinkedHashMap<>();result.put("queriedAt",System.currentTimeMillis());
        switch(provider.getProviderCode().toLowerCase(Locale.ROOT)) {
            case "mureka" -> {
                JsonNode value=get(config,paths,"balancePath");
                if(value.path("balance").isIntegralNumber()) result.put("balance",value.path("balance").decimalValue().movePointLeft(2));
                else result.put("balanceAvailable",false);
                result.put("unit","CNY");
                result.put("balanceKind","money");
                Map<String,Object> details=selected(value,"concurrent_request_limit");
                for(String field:List.of("total_recharge","total_spending"))
                    if(value.path(field).isIntegralNumber())details.put(field,value.path(field).decimalValue().movePointLeft(2));
                result.put("accountDetails",details);
            }
            case "elevenlabs" -> {
                JsonNode value=get(config,paths,"balancePath");
                boolean hasLimit=value.path("character_limit").isIntegralNumber(),hasUsed=value.path("character_count").isIntegralNumber();
                if(hasLimit)result.put("credits",value.path("character_limit").decimalValue());
                if(hasUsed)result.put("creditsUsed",value.path("character_count").decimalValue());
                if(hasLimit&&hasUsed)result.put("balance",value.path("character_limit").decimalValue().subtract(value.path("character_count").decimalValue()).max(BigDecimal.ZERO));
                else result.put("balanceAvailable",false);
                result.put("unit","characters");
                result.put("balanceKind","characters");result.put("accountDetails",selected(value,"tier","status","next_character_count_reset_unix","can_extend_character_limit","allowed_to_extend_character_limit"));
            }
            case "sonilo" -> {
                JsonNode services=get(config,paths,"servicesPath"),usage=get(config,paths,"usagePath");
                result.put("accountDetails",Map.of("services",selected(services,"available_services","rpm_limit","concurrency_limit","discount_factor","max_upload_size_mb","trial","free_trial","free_trials","trials","trial_remaining"),
                        "usage",selected(usage,"summary","daily")));
                JsonNode cash=services.path("cash_balance"),currency=services.path("currency");
                if(cash.isNumber()&&Set.of("USD","CNY").contains(currency.asText())){result.put("balance",cash.decimalValue());result.put("unit",currency.asText());}
                else result.put("balanceAvailable",false);
                result.put("balanceKind","account");
            }
            default -> throw new ServiceException("当前供应商不支持账户查询");
        }
        return result;
    }
    private JsonNode get(AiModelConfigVo config,JsonNode paths,String field){
        String path=paths.path(field).asText("");if(path.isBlank())throw new ServiceException("官方账户查询路径未配置");
        var response=transport.accountGet(config,path);
        if(!response.successful())throw new ServiceException(response.status()==401?"上游账户鉴权无效":"官方账户查询失败");
        try{return JSON.readTree(response.bytes());}catch(Exception invalid){throw new ServiceException("官方账户响应无效");}
    }
    private static Map<String,Object> selected(JsonNode root,String...keys){
        Map<String,Object> result=new LinkedHashMap<>();for(String key:keys)if(root.hasNonNull(key))result.put(key,JSON.convertValue(root.get(key),Object.class));return result;
    }
}
