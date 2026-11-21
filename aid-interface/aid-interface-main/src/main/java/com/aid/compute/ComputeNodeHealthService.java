package com.aid.compute;

import com.aid.compute.mapper.AidComputeNodeMapper;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Health refresh never releases a reservation or changes an existing task's terminal state. */
@Service
public class ComputeNodeHealthService {
    private final AidComputeNodeMapper nodes;
    private final ComputeNodeProbeService probes;
    private final ComputeWorkerClient worker;
    private final ComputeCredentialCodec credentials;
    private final TransactionTemplate transactions;
    public ComputeNodeHealthService(AidComputeNodeMapper nodes,ComputeNodeProbeService probes,ComputeWorkerClient worker,ComputeCredentialCodec credentials,PlatformTransactionManager tm){
        this.nodes=nodes;this.probes=probes;this.worker=worker;this.credentials=credentials;this.transactions=new TransactionTemplate(tm);
    }
    public ComputeNodeProbeService.Result refresh(String key,Long actor){
        ComputeResourcePolicy.key(key);var node=nodes.selectById(key);
        if(node==null)throw new ServiceException("计算节点不存在",404);
        if(node.getActiveRevision()==null)throw new ServiceException("节点尚未启用配置",409);
        var version=nodes.readRevision(key,node.getActiveRevision());
        if(version==null||version.getDraftCredentialCiphertext()==null)throw new ServiceException("生效版本缺少连接凭据",409);
        var config=JSON.parseObject(version.getDraftConfigJson(),ComputeNodeConfiguration.class);
        ComputeNodeProbeService.Result observation=null;RuntimeException failure=null;
        try{
            String secret=credentials.decrypt(version.getDraftWorkerId(),version.getDraftCredentialCiphertext());
            var result=probes.inspect(new ComputeNodeProbeService.Connection(config.endpoint(),version.getDraftWorkerId(),secret,key,Objects.requireNonNullElse(config.tlsCaPem(),"")));
            var status=worker.call(config.endpoint(),version.getDraftWorkerId(),secret,"/v1/configuration/status",Map.of(),config.tlsCaPem());
            if(!key.equals(status.getString("nodeKey"))||status.getLongValue("configVersion")!=node.getActiveRevision()
                ||!ComputeWorkerClient.digest(config).equals(status.getString("configurationDigest")))throw new ServiceException("节点生效配置与登记版本不一致",409);
            observation=result;
        }catch(RuntimeException unavailable){failure=unavailable;}
        var observed=observation;
        transactions.executeWithoutResult(status->{
            var current=nodes.lock(key);
            if(current==null||!Objects.equals(node.getActiveRevision(),current.getActiveRevision())
                ||!Objects.equals(node.getState(),current.getState())||!Objects.equals(node.getHealthGeneration(),current.getHealthGeneration()))
                throw new ServiceException("健康检查期间配置或维护状态变化，请重新检查",409);
            current.setHealthJson(observed==null?null:JSON.toJSONString(observed));current.setHealthCheckedAt(new Date());
            current.setHealthStatus(observed==null?"UNAVAILABLE":"ONLINE");
            current.setHealthGeneration(Objects.requireNonNullElse(current.getHealthGeneration(),0L)+1);nodes.updateById(current);
        });
        if(failure!=null){
            if(failure instanceof ServiceException expected)throw expected;
            throw new ServiceException("计算节点凭据或健康状态无法核验",503);
        }
        return observation;
    }
    @Transactional(rollbackFor=Exception.class)
    public void maintenance(String key,boolean enabled){
        ComputeResourcePolicy.key(key);var node=nodes.lock(key);if(node==null)throw new ServiceException("计算节点不存在",404);
        if(node.getActiveRevision()==null)throw new ServiceException("节点尚未启用",409);
        node.setState(enabled?"MAINTENANCE":"ACTIVE");
        node.setHealthGeneration(Objects.requireNonNullElse(node.getHealthGeneration(),0L)+1);
        // Leaving maintenance requires a fresh observation before the scheduler may reserve resources.
        node.setHealthCheckedAt(null);node.setHealthJson(null);node.setHealthStatus("UNCHECKED");nodes.updateById(node);
    }
}
