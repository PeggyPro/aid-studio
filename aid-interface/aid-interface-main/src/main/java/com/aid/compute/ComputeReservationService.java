package com.aid.compute;

import com.aid.compute.domain.*;
import com.aid.compute.mapper.*;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Internal admission for an already-authorized task. Never submits an engine or charges a wallet. */
@Service
public class ComputeReservationService {
    private final AidComputeNodeMapper nodes;
    private final AidComputeReservationMapper reservations;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ComputeHealthPolicy healthPolicy;
    public ComputeReservationService(AidComputeNodeMapper nodes,AidComputeReservationMapper reservations){this.nodes=nodes;this.reservations=reservations;}
    public record Request(String requestKey,String taskKey,String projectKey,String poolCode,String engine,String taskKind,String preferredNodeKey) {
        public Request(String requestKey,String taskKey,String projectKey,String poolCode,String engine,String taskKind){this(requestKey,taskKey,projectKey,poolCode,engine,taskKind,null);}
    }
    public AidComputeReservation find(String key){ComputeResourcePolicy.key(key);return reservations.selectById(key);}
    /** Persist the identity before sending a remote request. Replays keep exactly the same job id. */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AidComputeReservation bindSubmission(String key,String jobId){
        ComputeResourcePolicy.key(key);ComputeResourcePolicy.key(jobId);var row=reservations.lock(key);
        if(row==null||row.getNodeKey()==null)throw new ServiceException("任务尚未取得计算资源",409);
        nodes.lock(row.getNodeKey());
        if(row.getWorkerJobId()!=null){if(!jobId.equals(row.getWorkerJobId()))throw new ServiceException("任务已绑定其他执行标识",409);return row;}
        if(!"RESERVED".equals(row.getState()))throw new ServiceException("当前预留状态不允许提交",409);
        row.setWorkerJobId(jobId);row.setState("SUBMITTED");row.setUpdateTime(new Date());reservations.updateById(row);return row;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AidComputeReservation reserve(Request request){
        return admit(request, true);
    }

    /** Lookup may close a never-submitted unavailable intent, but cannot allocate or submit work. */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AidComputeReservation inspectPending(Request request){
        return admit(request, false);
    }

    private AidComputeReservation admit(Request request, boolean assign){
        for(String value:List.of(request.requestKey(),request.taskKey(),request.projectKey(),request.poolCode(),request.engine(),request.taskKind()))ComputeResourcePolicy.key(value);
        if(request.preferredNodeKey()!=null)ComputeResourcePolicy.key(request.preferredNodeKey());
        String digest=ComputeWorkerClient.digest(request);
        var pending=new AidComputeReservation();pending.setRequestKey(request.requestKey());pending.setRequestDigest(digest);
        pending.setTaskKey(request.taskKey());pending.setProjectKey(request.projectKey());pending.setEngine(request.engine());pending.setTaskKind(request.taskKind());
        if (assign) reservations.insertPending(pending);
        var row=reservations.lock(request.requestKey());
        if (row == null) return null;
        if(!digest.equals(row.getRequestDigest()))throw new ServiceException("预留请求标识已被不同任务参数使用",409);
        if(!"PENDING".equals(row.getState()))return row;
        String queue = ComputeWorkerClient.digest(List.of(request.poolCode(),request.engine(),request.taskKind(),
                Objects.toString(request.preferredNodeKey(),"")));
        // Stable lock order prevents different request keys from acquiring candidate nodes in opposing order.
        var candidates=nodes.selectList(Wrappers.<AidComputeNode>lambdaQuery().eq(AidComputeNode::getPoolCode,request.poolCode())
            .eq(request.preferredNodeKey()!=null,AidComputeNode::getNodeKey,request.preferredNodeKey())
            .orderByAsc(AidComputeNode::getNodeKey));
        var lockedCandidates=new ArrayList<AidComputeNode>();
        for(var candidate:candidates){
            var node=nodes.lock(candidate.getNodeKey());
            if(node!=null)lockedCandidates.add(node);
        }
        // Serialize enrollment with admission. Otherwise an uncommitted earlier queue row is
        // invisible to a later transaction and both requests can temporarily regard themselves as first.
        reservations.insertQueue(request.requestKey(),queue);
        if (!queue.equals(reservations.queueKey(request.requestKey())))
            throw new ServiceException("计算排队身份不匹配",409);
        int available = 0;
        for(var node:lockedCandidates){
            if(!"ACTIVE".equals(node.getState())||!request.poolCode().equals(node.getPoolCode())||node.getActiveConfigJson()==null)continue;
            // Stale observations cannot authorize new work. Health maintenance refreshes this evidence.
            if(!"ONLINE".equals(node.getHealthStatus())||node.getHealthCheckedAt()==null||System.currentTimeMillis()-node.getHealthCheckedAt().getTime()>(healthPolicy==null?60000:healthPolicy.freshnessMillis()))continue;
            ComputeNodeConfiguration config;
            ComputeNodeProbeService.Result observation;
            try {
                config=JSON.parseObject(node.getActiveConfigJson(),ComputeNodeConfiguration.class);
                observation=JSON.parseObject(node.getHealthJson(),ComputeNodeProbeService.Result.class);
            } catch(RuntimeException unavailable){continue;}
            if(config==null||observation==null||observation.resources()==null)continue;
            var slot=config.slots().stream().filter(s->s.engine().equals(request.engine())&&s.taskKind().equals(request.taskKind())).findFirst().orElse(null);
            if(slot==null)continue;
            var occupied=reservations.occupied(node.getNodeKey());var engines=new HashMap<String,Integer>();var kinds=new HashMap<String,Integer>();
            var total=new ComputeResourcePolicy.Resources(0,0,0);
            for(var item:occupied){engines.merge(item.getEngine(),1,Integer::sum);kinds.merge(item.getTaskKind(),1,Integer::sum);
                total=total.plus(new ComputeResourcePolicy.Resources(item.getCpuMillis(),item.getMemoryBytes(),item.getScratchBytes()));}
            if (!config.engines().contains(request.engine())
                    || config.limits().engineConcurrent().getOrDefault(request.engine(),config.limits().maxConcurrent()) == 0
                    || config.limits().taskConcurrent().getOrDefault(request.taskKind(),config.limits().maxConcurrent()) == 0) continue;
            available++;
            long position=reservations.queuePosition(request.requestKey(),queue);
            if(position<1)throw new ServiceException("计算排队记录不可核验",409);
            row.setQueuePosition(position);
            var decision=ComputeResourcePolicy.assess(config.limits(),new ComputeResourcePolicy.Occupancy(occupied.size(),engines,kinds,total),
                new ComputeResourcePolicy.Request(request.engine(),request.taskKind(),slot.resources()),true,config.engines(),observation.resources().scratchFreeBytes());
            if(!assign || position!=1 || decision!=ComputeResourcePolicy.Decision.ACCEPT)continue;
            row.setNodeKey(node.getNodeKey());row.setConfigRevision(node.getActiveRevision());row.setCpuMillis(slot.resources().cpuMillis());
            row.setMemoryBytes(slot.resources().memoryBytes());row.setScratchBytes(slot.resources().scratchBytes());row.setState("RESERVED");row.setUpdateTime(new Date());
            reservations.updateById(row);return row;
        }
        if (available == 0) {
            // Only PENDING reaches here. Accepted/unknown executions returned above and retain capacity.
            row.setState("FAILED");row.setUpdateTime(new Date());reservations.updateById(row);
        } else row.setQueuePosition(reservations.queuePosition(request.requestKey(),queue));
        return row; // Pending is not success and reserves no machine capacity.
    }

    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancelUnsubmitted(String key,String project){
        ComputeResourcePolicy.key(key);var row=reservations.lock(key);
        if(row==null)return;
        if(!Objects.equals(project,row.getProjectKey()))throw new ServiceException("计算任务与项目不匹配",409);
        if(Set.of("CANCELED","FAILED").contains(row.getState()) && row.getWorkerJobId()==null)return;
        if(row.getWorkerJobId()!=null || !Set.of("PENDING","RESERVED").contains(row.getState()))
            throw new ServiceException("计算任务已提交，须核对原任务",409);
        if(row.getNodeKey()!=null)nodes.lock(row.getNodeKey());
        row.setState("CANCELED");row.setUpdateTime(new Date());reservations.updateById(row);
    }
}
