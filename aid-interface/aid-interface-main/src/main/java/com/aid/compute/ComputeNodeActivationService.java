package com.aid.compute;

import com.aid.common.exception.ServiceException;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ComputeNodeActivationService {
    private final AidComputeNodeMapper nodes;private final ComputeCredentialCodec credentials;
    private final ComputeWorkerClient worker;private final ComputeNodeConfigurationService configurations;
    private final TransactionTemplate transactions;
    public ComputeNodeActivationService(AidComputeNodeMapper nodes,ComputeCredentialCodec credentials,ComputeWorkerClient worker,ComputeNodeConfigurationService configurations,PlatformTransactionManager tm){
        this.nodes=nodes;this.credentials=credentials;this.worker=worker;this.configurations=configurations;this.transactions=new TransactionTemplate(tm);
    }
    public record Activate(String nodeKey,String expectedRevision) { }
    /** Worker CAS is reconciled first; only the final database write holds a node lock. */
    public ComputeNodeConfigurationService.View activate(Activate input,String actor){
        ComputeResourcePolicy.key(input.nodeKey());var row=nodes.selectById(input.nodeKey());
        if(row==null)throw new ServiceException("计算节点不存在",404);
        if(!Objects.equals(input.expectedRevision(),String.valueOf(row.getRevision())))throw new ServiceException("配置已变化，请重新读取后启用",409);
        if(row.getDraftWorkerId()==null||row.getDraftCredentialCiphertext()==null||row.getVerificationJson()==null)throw new ServiceException("请先检测连接并保存配置",409);
        if(row.getActiveRevision()!=null){
            var active=nodes.readRevision(row.getNodeKey(),row.getActiveRevision());
            if(active==null||!Objects.equals(active.getDraftWorkerId(),row.getDraftWorkerId()))
                throw new ServiceException("已启用节点不能替换执行器身份；请恢复原节点或新增计算节点",409);
        }
        var config=JSON.parseObject(row.getDraftConfigJson(),ComputeNodeConfiguration.class);
        if(config.slots().isEmpty())throw new ServiceException("请先配置任务槽位",400);
        String secret=credentials.decrypt(row.getDraftWorkerId(),row.getDraftCredentialCiphertext());
        var current=worker.call(config.endpoint(),row.getDraftWorkerId(),secret,"/v1/configuration/status",Map.of(),config.tlsCaPem());
        long version=current.getLongValue("configVersion");
        if(version<0||version>row.getRevision())throw new ServiceException("节点存在未登记的配置版本，不能覆盖",409);
        if(row.getActiveRevision()!=null&&version<row.getActiveRevision())
            throw new ServiceException("节点已登记状态缺失或回退；请完整恢复原状态及产物目录后再检测，不能覆盖启用",409);
        if(version>0){
            var prior=nodes.readRevision(row.getNodeKey(),version);
            if(!row.getNodeKey().equals(current.getString("nodeKey"))||prior==null
                ||!ComputeWorkerClient.digest(JSON.parseObject(prior.getDraftConfigJson())).equals(current.getString("configurationDigest")))
                throw new ServiceException("节点配置与登记历史不一致，不能覆盖",409);
        }
        var payload=Map.of("nodeKey",row.getNodeKey(),"configVersion",row.getRevision(),"expectedVersion",version,"configuration",config);
        var receipt=worker.call(config.endpoint(),row.getDraftWorkerId(),secret,"/v1/configuration/apply",payload,config.tlsCaPem());
        if(!row.getNodeKey().equals(receipt.getString("nodeKey"))||receipt.getLongValue("configVersion")!=row.getRevision()
            ||!ComputeWorkerClient.digest(config).equals(receipt.getString("configurationDigest")))throw new ServiceException("配置下发回执不一致，未确认启用",502);
        return transactions.execute(status->{
            var currentRow=nodes.lock(row.getNodeKey());
            if(currentRow==null||!Objects.equals(row.getRevision(),currentRow.getRevision())||!Objects.equals(row.getState(),currentRow.getState())
                ||!Objects.equals(row.getHealthGeneration(),currentRow.getHealthGeneration()))throw new ServiceException("启用期间配置或节点状态变化，请重新核对当前版本",409);
            currentRow.setActiveConfigJson(row.getDraftConfigJson());currentRow.setActiveRevision(row.getRevision());
            currentRow.setState("MAINTENANCE".equals(row.getState())?"MAINTENANCE":"ACTIVE");
            currentRow.setHealthJson(null);currentRow.setHealthCheckedAt(null);currentRow.setHealthStatus("UNCHECKED");
            currentRow.setHealthGeneration(Objects.requireNonNullElse(currentRow.getHealthGeneration(),0L)+1);
            currentRow.setUpdateBy(actor);currentRow.setUpdateTime(new Date());nodes.updateById(currentRow);return configurations.view(currentRow);
        });
    }
}
