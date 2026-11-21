package com.aid.compute;

import com.aid.common.exception.ServiceException;
import com.aid.compute.domain.AidComputeNode;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ComputeNodeConfigurationService {
    private final AidComputeNodeMapper nodes;
    private final ComputeNodeProbeService probes;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ComputeHealthPolicy healthPolicy;
    public ComputeNodeConfigurationService(AidComputeNodeMapper nodes,ComputeNodeProbeService probes) { this.nodes=nodes;this.probes=probes; }
    public record View(String nodeKey,String revision,String activeRevision,String state,ComputeNodeConfiguration configuration,
                       String workerId,boolean credentialConfigured,Date verifiedAt,ComputeNodeProbeService.Result verification,Date updatedAt,
                       String healthStatus,Date healthCheckedAt,boolean healthFresh) { }
    public record Save(String nodeKey,String expectedRevision,String clientRequestId,ComputeNodeConfiguration configuration,String probeId) { }
    public List<AidComputeNode> listRows(String keyword) {
        if (keyword != null && keyword.length()>100) throw new ServiceException("搜索内容过长");
        return nodes.selectList(Wrappers.<AidComputeNode>lambdaQuery()
                .like(keyword!=null&&!keyword.isBlank(),AidComputeNode::getName,keyword)
                .orderByDesc(AidComputeNode::getUpdateTime).orderByAsc(AidComputeNode::getNodeKey));
    }
    public View detail(String key) {
        ComputeResourcePolicy.key(key);var row=nodes.selectById(key);
        if(row==null)throw new ServiceException("计算节点不存在");return view(row);
    }
    @Transactional(rollbackFor=Exception.class)
    public View save(Save input,String actor,Long actorId) {
        if(input==null||input.configuration==null)throw new ServiceException("节点配置不能为空");
        // Reconstruct to enforce the same validation even for non-HTTP callers.
        var config=JSON.parseObject(JSON.toJSONString(input.configuration),ComputeNodeConfiguration.class);
        ComputeTlsSupport.inspect(config.tlsCaPem());
        AidComputeNode row;
        if(input.nodeKey==null){
            if(input.expectedRevision!=null&&!"0".equals(input.expectedRevision))throw new ServiceException("新节点版本无效");
            String key;
            try { key=UUID.fromString(input.clientRequestId).toString();if(!key.equals(input.clientRequestId))throw new IllegalArgumentException(); }
            catch(RuntimeException invalid){throw new ServiceException("请提供有效的新建请求标识");}
            row=new AidComputeNode();row.setNodeKey(key);row.setRevision(1L);
            row.setState("DRAFT");row.setCreateBy(actor);row.setCreateTime(new Date());
        }else{
            ComputeResourcePolicy.key(input.nodeKey);row=nodes.lock(input.nodeKey);
            if(row==null)throw new ServiceException("计算节点不存在");
            if(!Objects.equals(input.expectedRevision,String.valueOf(row.getRevision()))){
                boolean priorRevision=Objects.equals(input.expectedRevision,String.valueOf(row.getRevision()-1));
                if(priorRevision&&Objects.equals(actor,row.getUpdateBy())&&config.equals(view(row).configuration())&&sameProbe(input.probeId,row))return view(row);
                throw new ServiceException("配置已被其他管理员更新，请重新读取后修改",409);
            }
            if(config.equals(view(row).configuration())&&sameProbe(input.probeId,row))return view(row);
            row.setRevision(Math.addExact(row.getRevision(),1));
        }
        if(input.probeId!=null){
            var proof=probes.requireProof(input.probeId,actorId,config.endpoint(),row.getNodeKey(),config.tlsCaPem());var observed=proof.result().resources();
            if(!proof.result().engines().containsAll(config.engines()))throw new ServiceException("所选引擎不在节点已验证能力中",400);
            if(config.slots().stream().anyMatch(slot->!proof.result().routes().contains(new ComputeNodeProbeService.Route(slot.engine(),slot.taskKind()))))throw new ServiceException("槽位的引擎与任务组合不在节点已验证能力中",400);
            var capacity=config.limits().capacity();
            if(capacity.cpuMillis()>observed.cpuMillis()||capacity.memoryBytes()>observed.memoryBytes()
                    ||config.limits().minimumFreeBytes()>observed.scratchTotalBytes()
                    ||capacity.scratchBytes()>observed.scratchTotalBytes()-config.limits().minimumFreeBytes())
                throw new ServiceException("配置的资源额度超过节点探测到的可用上限",400);
            row.setDraftWorkerId(proof.workerId());row.setDraftCredentialCiphertext(proof.encryptedCredential());
            row.setVerificationJson(JSON.toJSONString(proof.result()));row.setVerifiedAt(Date.from(java.time.Instant.parse(proof.result().checkedAt())));
        }else if(row.getDraftConfigJson()!=null&&(!config.endpoint().equals(view(row).configuration().endpoint())||!Objects.equals(config.tlsCaPem(),view(row).configuration().tlsCaPem()))){
            row.setVerificationJson(null);row.setVerifiedAt(null);
        }
        row.setName(config.name());row.setPoolCode(config.poolCode());row.setDraftConfigJson(JSON.toJSONString(config));
        row.setUpdateBy(actor);row.setUpdateTime(new Date());
        if(input.nodeKey==null){
            nodes.insertIfAbsent(row);var stored=nodes.lock(row.getNodeKey());
            if(!Objects.equals(actor,stored.getCreateBy())||!config.equals(view(stored).configuration())||!sameProbe(input.probeId,stored))
                throw new ServiceException("新建请求标识已被使用，不能改变原请求内容",409);
            nodes.archiveRevision(stored);
            return view(stored);
        }else nodes.updateById(row);
        nodes.archiveRevision(row);
        return view(row);
    }
    public View view(AidComputeNode row){return new View(row.getNodeKey(),String.valueOf(row.getRevision()),
            row.getActiveRevision()==null?null:String.valueOf(row.getActiveRevision()),row.getState(),
            JSON.parseObject(row.getDraftConfigJson(),ComputeNodeConfiguration.class),row.getDraftWorkerId(),row.getDraftCredentialCiphertext()!=null,
            row.getVerifiedAt(),row.getVerificationJson()==null?null:JSON.parseObject(row.getVerificationJson(),ComputeNodeProbeService.Result.class),row.getUpdateTime(),row.getHealthStatus(),row.getHealthCheckedAt(),
            row.getHealthCheckedAt()!=null&&System.currentTimeMillis()-row.getHealthCheckedAt().getTime()<(healthPolicy==null?60000:healthPolicy.freshnessMillis()));}
    private static boolean sameProbe(String id,AidComputeNode row){return id==null||row.getVerificationJson()!=null&&id.equals(JSON.parseObject(row.getVerificationJson()).getString("proofId"));}
}
