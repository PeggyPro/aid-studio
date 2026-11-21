package com.aid.compute;

import com.aid.core.service.ISysConfigService;
import com.aid.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Settings use the existing administrator parameter store and its cache invalidation. */
@Component
public class ComputeHealthPolicy {
    @Autowired(required=false) private ISysConfigService configs;
    private String read(String key){return configs==null?"":configs.selectConfigByKey(key);}
    public boolean enabled(){String v=read("aid.compute.health.enabled");return v==null||v.isBlank()||"true".equals(v);}
    private long seconds(String key,long fallback,long min,long max){
        String value=read(key);if(value==null||value.isBlank())return fallback;
        try{long parsed=Long.parseLong(value);if(parsed>=min&&parsed<=max)return parsed;}catch(NumberFormatException ignored){}
        throw new ServiceException("计算健康参数无效："+key,400);
    }
    public long intervalMillis(){return seconds("aid.compute.health.intervalSeconds",20,10,300)*1000;}
    public long freshnessMillis(){return Math.max(intervalMillis()*2,seconds("aid.compute.health.freshnessSeconds",60,30,900)*1000);}
    public boolean reconciliationEnabled(){String v=read("aid.compute.reconciliation.enabled");return v==null||v.isBlank()||"true".equals(v);}
    public long reconciliationIntervalMillis(){return seconds("aid.compute.reconciliation.intervalSeconds",30,10,3600)*1000;}
}
