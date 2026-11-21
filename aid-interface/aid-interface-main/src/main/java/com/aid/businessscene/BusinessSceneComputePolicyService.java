package com.aid.businessscene;

import com.aid.common.constant.CacheConstants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.core.domain.SysConfig;
import com.aid.system.mapper.SysConfigMapper;
import com.aid.businessscene.mapper.AidBusinessEntrypointMapper;
import com.aid.compute.*;
import com.aid.compute.domain.AidComputeNode;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Scene-to-pool selection reuses the administrator parameter store; no new scheduler or task table. */
@Service
public class BusinessSceneComputePolicyService {
    private final SysConfigMapper settings;private final AidComputeNodeMapper nodes;
    private final AidBusinessEntrypointMapper scenes;private final RedisCache cache;
    public BusinessSceneComputePolicyService(SysConfigMapper settings,AidComputeNodeMapper nodes,AidBusinessEntrypointMapper scenes,RedisCache cache){this.settings=settings;this.nodes=nodes;this.scenes=scenes;this.cache=cache;}
    public static String settingKey(String scene){ComputeResourcePolicy.key(scene);return "aid.compute.scene."+scene+".pool";}
    public record Pool(String code,int enabledNodes,Set<String> engines) { }
    public record View(String sceneCode,String poolCode,String revision,List<Pool> pools) { }
    public record Save(String sceneCode,String poolCode,String expectedRevision) { }
    private SysConfig stored(String scene){var query=new SysConfig();query.setConfigKey(settingKey(scene));return settings.selectConfig(query);}
    private static String value(SysConfig row){return row==null||row.getConfigValue()==null?"":row.getConfigValue();}
    public String configuredPool(String scene){return value(stored(scene));}
    public boolean managed(String scene){return stored(scene)!=null;}
    private void invalidateAfterCommit(String key){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){cache.deleteObject(CacheConstants.SYS_CONFIG_KEY+key);}});}
    private static String revision(String scene,String pool){return ComputeWorkerClient.digest(Map.of("scene",scene,"pool",pool));}
    public View read(String scene){
        String selected=configuredPool(scene);var pools=new TreeMap<String,List<AidComputeNode>>();
        var eligible=nodes.selectList(Wrappers.<AidComputeNode>lambdaQuery().select(AidComputeNode::getPoolCode,AidComputeNode::getActiveConfigJson)
            .isNotNull(AidComputeNode::getActiveRevision).in(AidComputeNode::getState,List.of("ACTIVE","MAINTENANCE")).last("LIMIT 1000"));
        eligible.forEach(node->pools.computeIfAbsent(node.getPoolCode(),key->new ArrayList<>()).add(node));
        return new View(scene,selected,revision(scene,selected),pools.entrySet().stream().map(entry->{var engines=new TreeSet<String>();
            for(var node:entry.getValue())engines.addAll(com.alibaba.fastjson2.JSON.parseObject(node.getActiveConfigJson(),ComputeNodeConfiguration.class).engines());
            return new Pool(entry.getKey(),entry.getValue().size(),engines);}).toList());
    }
    @Transactional(rollbackFor=Exception.class)
    public View save(Save input,String actor){
        if(input==null||input.poolCode()==null)throw new ServiceException("请明确选择计算资源池",400);
        String key=settingKey(input.sceneCode());if(scenes.lockScene(input.sceneCode())==null)throw new ServiceException("业务场景不存在",404);
        if(!input.poolCode().isEmpty())ComputeResourcePolicy.key(input.poolCode());
        SysConfig prior=stored(input.sceneCode());String current=value(prior);
        if(!Objects.equals(input.expectedRevision(),revision(input.sceneCode(),current))){
            if(current.equals(input.poolCode())){invalidateAfterCommit(key);return read(input.sceneCode());}throw new ServiceException("资源池绑定已变化，请重新读取",409);
        }
        if(!input.poolCode().isEmpty()&&read(input.sceneCode()).pools().stream().noneMatch(pool->pool.code().equals(input.poolCode())))throw new ServiceException("资源池没有已验证启用的机器",409);
        if(!current.equals(input.poolCode())){
            if(prior==null){prior=new SysConfig();prior.setConfigKey(key);prior.setConfigName("业务计算资源池："+input.sceneCode());prior.setConfigType("N");prior.setCreateBy(actor);prior.setConfigValue(input.poolCode());settings.insertConfig(prior);}
            else{if(scenes.writeParameterValue(prior.getConfigId(),key,input.poolCode(),actor)!=1)throw new ServiceException("资源池参数已变化，请重新读取",409);}
        }
        invalidateAfterCommit(key);
        return read(input.sceneCode());
    }
}
