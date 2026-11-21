package com.aid.compute;

import com.aid.compute.mapper.AidComputeNodeMapper;
import com.aid.compute.domain.AidComputeNode;
import com.aid.compute.domain.AidComputeReservation;
import com.aid.compute.mapper.AidComputeReservationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Bounded health and original-receipt checks; only verified terminal receipts release occupancy. */
@Component
public class ComputeHealthMonitor {
    private final AidComputeNodeMapper nodes;private final ComputeNodeHealthService health;private final ComputeHealthPolicy policy;
    private final AidComputeReservationMapper reservations;private final ComputeReservationReconciler reconciler;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"compute-health-timer");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),r->{var t=new Thread(r,"compute-health");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public ComputeHealthMonitor(AidComputeNodeMapper nodes,ComputeNodeHealthService health,ComputeHealthPolicy policy,AidComputeReservationMapper reservations,ComputeReservationReconciler reconciler){this.nodes=nodes;this.health=health;this.policy=policy;this.reservations=reservations;this.reconciler=reconciler;}
    @EventListener(ApplicationReadyEvent.class)
    public void start(){timer.scheduleWithFixedDelay(()->{try{inspect();}catch(Exception unavailable){/* Admission rejects stale evidence while observation is unavailable. */}},5,5,TimeUnit.SECONDS);}
    public void inspect(){
        if(policy.enabled())inspectHealth();
        if(policy.reconciliationEnabled())inspectReservations();
    }
    private void inspectHealth(){
        long cutoff=System.currentTimeMillis()-policy.intervalMillis();
        var targets=nodes.selectList(Wrappers.<AidComputeNode>lambdaQuery().select(AidComputeNode::getNodeKey)
            .eq(AidComputeNode::getState,"ACTIVE").isNotNull(AidComputeNode::getDraftWorkerId).and(q->q.isNull(AidComputeNode::getHealthCheckedAt).or().lt(AidComputeNode::getHealthCheckedAt,new Date(cutoff)))
            .orderByAsc(AidComputeNode::getHealthCheckedAt).orderByAsc(AidComputeNode::getNodeKey).last("LIMIT 20"));
        for(var target:targets){String key=target.getNodeKey();if(!pending.add(key))continue;
            try{executor.execute(()->{try{health.refresh(key,1L);}catch(Exception expected){/* State is persisted by the health service. */}finally{pending.remove(key);}});}
            catch(RejectedExecutionException busy){pending.remove(key);}
        }
    }
    private void inspectReservations(){
        var targets=reservations.selectList(Wrappers.<AidComputeReservation>lambdaQuery().select(AidComputeReservation::getRequestKey)
            .in(AidComputeReservation::getState,List.of("SUBMITTED","UNKNOWN")).isNotNull(AidComputeReservation::getWorkerJobId)
            .lt(AidComputeReservation::getUpdateTime,new Date(System.currentTimeMillis()-policy.reconciliationIntervalMillis()))
            .orderByAsc(AidComputeReservation::getUpdateTime).orderByAsc(AidComputeReservation::getRequestKey).last("LIMIT 10"));
        for(var target:targets){String key="job:"+target.getRequestKey();if(!pending.add(key))continue;
            try{executor.execute(()->{try{reconciler.reconcile(target.getRequestKey());}catch(Exception unavailable){/* Unknown stays occupied. */}finally{pending.remove(key);}});}
            catch(RejectedExecutionException busy){pending.remove(key);}
        }
    }
    @PreDestroy public void close(){timer.shutdownNow();executor.shutdownNow();}
}
