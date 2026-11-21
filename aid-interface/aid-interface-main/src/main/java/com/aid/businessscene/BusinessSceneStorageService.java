package com.aid.businessscene;

import com.aid.common.constant.CacheConstants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.core.domain.SysConfig;
import com.aid.system.mapper.SysConfigMapper;
import com.aid.businessscene.mapper.AidBusinessEntrypointMapper;
import com.aid.compute.ComputeWorkerClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.util.*;

/** Reuses the scene lock, system parameters, administrator permissions and cache invalidation. */
@Service
public class BusinessSceneStorageService {
    private final List<BusinessSceneStorageProvider> providers;
    private final SysConfigMapper configs;private final AidBusinessEntrypointMapper scenes;private final RedisCache cache;
    public BusinessSceneStorageService(List<BusinessSceneStorageProvider> p,SysConfigMapper c,AidBusinessEntrypointMapper s,RedisCache r){providers=List.copyOf(p);configs=c;scenes=s;cache=r;}
    public record Value(BusinessSceneStorageProvider.Field definition,Long value,Long effectiveValue,boolean invalid) { }
    public record View(String sceneCode,String revision,boolean supported,List<Value> fields) { }
    public record Save(String sceneCode,String expectedRevision,Map<String,Object> values) { }
    private List<BusinessSceneStorageProvider.Field> fields(String scene){
        com.aid.compute.ComputeResourcePolicy.key(scene);
        var selected=providers.stream().filter(p->p.supports(scene)).toList();
        if(selected.size()>1)throw new ServiceException("业务存储配置来源重复",409);
        var fields=selected.isEmpty()?List.<BusinessSceneStorageProvider.Field>of():List.copyOf(selected.get(0).fields(scene));
        if(fields.size()>50||fields.stream().map(BusinessSceneStorageProvider.Field::key).distinct().count()!=fields.size())throw new ServiceException("业务存储定义无效",409);
        return fields;
    }
    private SysConfig stored(String key){var query=new SysConfig();query.setConfigKey(key);return configs.selectConfig(query);}
    private static String text(SysConfig row){return row==null||row.getConfigValue()==null?"":row.getConfigValue();}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View read(String scene){
        var definitions=fields(scene);var values=new ArrayList<Value>();var identity=new TreeMap<String,Object>();
        for(var field:definitions){String raw=text(stored(field.key()));Long value=null,effective=null;boolean invalid=false;
            try{if(!raw.isBlank())value=field.validate(Long.parseLong(raw));effective=value==null?field.defaultValue():value;}
            catch(IllegalArgumentException rejected){invalid=true;}
            values.add(new Value(field,value,effective,invalid));identity.put(field.key(),List.of(field,raw));
        }
        return new View(scene,ComputeWorkerClient.digest(Map.of("scene",scene,"fields",identity)),!definitions.isEmpty(),List.copyOf(values));
    }
    @Transactional(rollbackFor=Exception.class)
    public View save(Save input,String actor){
        if(input==null||input.values()==null||scenes.lockScene(input.sceneCode())==null)throw new ServiceException("业务存储配置不存在",404);
        var definitions=fields(input.sceneCode());var keys=new HashSet<String>();definitions.forEach(f->keys.add(f.key()));
        if(keys.isEmpty()||!keys.equals(input.values().keySet()))throw new ServiceException("存储字段与业务定义不一致",400);
        var selected=new HashMap<String,Long>();
        definitions.forEach(field->{Object raw=input.values().get(field.key());Long value=null;
            if(raw!=null){
                if(!(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long||raw instanceof java.math.BigInteger))throw new ServiceException("存储配置必须为整数或留空",400);
                try{value=raw instanceof java.math.BigInteger number?number.longValueExact():((Number)raw).longValue();field.validate(value);}
                catch(IllegalArgumentException|ArithmeticException invalid){throw new ServiceException(field.name()+"超出允许范围",400);}
            }
            selected.put(field.key(),value);
        });
        var current=read(input.sceneCode());
        boolean same=current.fields().stream().allMatch(value->!value.invalid()&&Objects.equals(value.value(),selected.get(value.definition().key())));
        if(!Objects.equals(input.expectedRevision(),current.revision())&&!same)throw new ServiceException("存储配置已变化，请重新读取",409);
        for(var field:definitions){String value=selected.get(field.key())==null?"":String.valueOf(selected.get(field.key()));SysConfig prior=stored(field.key());
            if(!value.equals(text(prior))){
                if(prior==null){prior=new SysConfig();prior.setConfigKey(field.key());prior.setConfigName(field.name());prior.setConfigValue(value);prior.setConfigType("N");prior.setCreateBy(actor);configs.insertConfig(prior);}
                else{if(scenes.writeParameterValue(prior.getConfigId(),field.key(),value,actor)!=1)throw new ServiceException("存储参数已变化，请重新读取",409);}
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){for(String key:keys)cache.deleteObject(CacheConstants.SYS_CONFIG_KEY+key);}});
        return read(input.sceneCode());
    }
}
