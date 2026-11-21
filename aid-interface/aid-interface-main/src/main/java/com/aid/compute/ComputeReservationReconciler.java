package com.aid.compute;

import com.aid.compute.domain.AidComputeReservation;
import com.aid.compute.mapper.*;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Only an authenticated read of the original worker may release submitted occupancy. */
@Service
public class ComputeReservationReconciler {
    private final AidComputeNodeMapper nodes;private final AidComputeReservationMapper reservations;
    private final ComputeWorkerClient worker;private final ComputeCredentialCodec credentials;private final TransactionTemplate transactions;
    public ComputeReservationReconciler(AidComputeNodeMapper n,AidComputeReservationMapper r,ComputeWorkerClient w,ComputeCredentialCodec c,PlatformTransactionManager tm){nodes=n;reservations=r;worker=w;credentials=c;transactions=new TransactionTemplate(tm);transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public AidComputeReservation reconcile(String key){
        ComputeResourcePolicy.key(key);var row=reservations.selectById(key);
        if(row==null||row.getWorkerJobId()==null||row.getNodeKey()==null)throw new ServiceException("尚无可核对的远程执行",409);
        if(Set.of("BUILT","FAILED","CANCELLED").contains(row.getState()))return row;
        var version=nodes.readRevision(row.getNodeKey(),row.getConfigRevision());
        if(version==null)throw new ServiceException("执行绑定的配置版本不可用",409);
        var config=JSON.parseObject(version.getDraftConfigJson(),ComputeNodeConfiguration.class);
        String observedState="UNKNOWN",receiptDigest=null;
        try{
            var receipt=worker.call(config.endpoint(),version.getDraftWorkerId(),credentials.decrypt(version.getDraftWorkerId(),version.getDraftCredentialCiphertext()),
                "/v1/jobs/status",Map.of("jobId",row.getWorkerJobId(),"projectId",row.getProjectKey()),config.tlsCaPem());
            String state=receipt.getString("state");
            if(!row.getWorkerJobId().equals(receipt.getString("jobId"))||!row.getProjectKey().equals(receipt.getString("projectId")))throw new ServiceException("执行回执身份不一致",409);
            if(Set.of("BUILT","FAILED","CANCELLED").contains(state)){observedState=state;receiptDigest=ComputeWorkerClient.digest(receipt);}
            else if(Set.of("QUEUED","PREPARING","RUNNING").contains(state))observedState="SUBMITTED";
        }catch(RuntimeException unavailable){ /* No trusted terminal: retain occupancy. */ }
        String finalState=observedState,finalDigest=receiptDigest;
        // Network observation is outside the database transaction. Recheck the pinned identity
        // under the same lock order as admission; a delayed reply never regresses a terminal.
        return transactions.execute(status->{
            var current=reservations.lock(key);
            if(current==null||!Objects.equals(row.getNodeKey(),current.getNodeKey())||!Objects.equals(row.getConfigRevision(),current.getConfigRevision())
                ||!Objects.equals(row.getWorkerJobId(),current.getWorkerJobId()))throw new ServiceException("执行绑定已变化",409);
            if(Set.of("BUILT","FAILED","CANCELLED").contains(current.getState()))return current;
            nodes.lock(current.getNodeKey());current.setState(finalState);current.setTerminalReceiptDigest(finalDigest);
            current.setUpdateTime(new Date());reservations.updateById(current);return current;
        });
    }
}
