package com.aid.canvas.publication.service;
import com.aid.canvas.publication.domain.*;
import com.aid.canvas.publication.mapper.*;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service @EnableScheduling @RequiredArgsConstructor public class CanvasPublicationCopyJobs {
    private final CanvasPublicationWorkMapper mapper;
    private final CanvasPublicationMapper publications;
    private final ObjectProvider<CanvasPublicationService> services;
    private final ObjectProvider<CanvasPublicationPort> ports;
    private final PlatformTransactionManager transactions;
    private final CanvasPublicationFiles files;
    @Value("${canvas.publication.copy-workers:2}") private int workers=2;
    @Value("${canvas.publication.copy-max-seconds:3600}") private long maxSeconds=3600;
    @Value("${canvas.publication.copy-max-active-per-user:2}") private int maxPerUser=2;
    private ExecutorService executor;private ExecutorService cleanupExecutor;private Semaphore slots;
    private ScheduledExecutorService maintenance;
    @Value("${canvas.publication.copy-dispatch-ms:1000}") private long dispatchMs=1000;
    @Value("${canvas.publication.cleanup-ms:60000}") private long cleanupMs=60000;
    private volatile boolean closing;
    private final AtomicBoolean cleanupRunning=new AtomicBoolean();
    private final AtomicBoolean maintenanceStarted=new AtomicBoolean();
    private final ConcurrentHashMap<Long,Active> active=new ConcurrentHashMap<>();
    private record Active(String token,long start,AtomicBoolean stopped) { }
    @jakarta.annotation.PostConstruct void init(){if(workers<1||workers>8||maxSeconds<30||maxPerUser<1||dispatchMs<1||cleanupMs<1)throw new IllegalArgumentException("Invalid copy worker configuration");slots=new Semaphore(workers);executor=Executors.newFixedThreadPool(workers,r->{Thread t=new Thread(r,"canvas-publication-copy");t.setDaemon(true);return t;});cleanupExecutor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"canvas-publication-cleanup");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());maintenance=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"canvas-publication-maintenance");t.setDaemon(true);return t;});}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startMaintenance(){if(closing||!maintenanceStarted.compareAndSet(false,true))return;maintenance.scheduleWithFixedDelay(()->maintain(this::dispatch),0,dispatchMs,TimeUnit.MILLISECONDS);maintenance.scheduleWithFixedDelay(()->maintain(this::heartbeat),0,10000,TimeUnit.MILLISECONDS);maintenance.scheduleWithFixedDelay(()->maintain(this::cleanup),0,cleanupMs,TimeUnit.MILLISECONDS);}
    private void maintain(Runnable action){if(closing)return;try{action.run();}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("画布复制维护异常，保留持久化状态：{}",ex.getClass().getSimpleName());}}
    @jakarta.annotation.PreDestroy void close(){closing=true;if(maintenance!=null)maintenance.shutdownNow();if(executor!=null)executor.shutdownNow();if(cleanupExecutor!=null)cleanupExecutor.shutdownNow();}
    public Map<String,Object> enqueue(Long user,Long publication,String operation,String hash,String name,String json) {
        return new TransactionTemplate(transactions).execute(ignored->{
            if(mapper.lockUser(user)==null)throw new ServiceException("账号已不可用");
            CanvasCopyJob existing=mapper.operation(user,operation);
            if(existing!=null){if(!hash.equals(existing.getRequestHash()))throw new ServiceException("operationId 已用于其他复制内容");return view(existing,true);}
            if(mapper.active(user)>=maxPerUser)throw new ServiceException("进行中的复制数量已达上限");
            com.alibaba.fastjson2.JSONArray resources=com.alibaba.fastjson2.JSON.parseObject(json).getJSONArray("resources");
            CanvasCopyJob job=new CanvasCopyJob();job.setUserId(user);job.setPublicationId(publication);job.setOperationId(operation);job.setRequestHash(hash);job.setProjectName(name);job.setTotalResources(resources.size());
            long bytes=0;for(Object raw:resources)bytes=Math.addExact(bytes,((com.alibaba.fastjson2.JSONObject)raw).getLongValue("fileSize"));job.setTotalBytes(bytes);mapper.insertJob(job);return view(mapper.job(job.getId()),false);
        });
    }
    public Map<String,Object> status(Long user,String operation) {
        CanvasCopyJob j=mapper.operation(user,operation);if(j!=null)return view(j,false);
        Map<String,Object> old=publications.copyOperation(user,operation);return old==null?Map.of("status","NOT_FOUND"):Map.of("status","SUCCEEDED","projectId",old.get("target_project_id").toString(),"progress",100);
    }
    public Map<String,Object> control(Long user,String operation,boolean retry) {
        return new TransactionTemplate(transactions).execute(ignored->{
            if(mapper.lockUser(user)==null)throw new ServiceException("账号已不可用");CanvasCopyJob j=mapper.operation(user,operation);if(j==null)throw new ServiceException("复制任务不存在");j=mapper.lockJob(j.getId());
            if(retry){if(mapper.active(user)>=maxPerUser)throw new ServiceException("进行中的复制数量已达上限");if(mapper.retry(j.getId())!=1)throw new ServiceException("当前复制状态不可重试");}else mapper.cancel(j.getId());
            return view(mapper.job(j.getId()),false);
        });
    }
    private Map<String,Object> view(CanvasCopyJob j,boolean replayed) {
        Map<String,Object> v=new LinkedHashMap<>();v.put("jobId",j.getId().toString());v.put("operationId",j.getOperationId());v.put("status",j.getStatus());v.put("replayed",replayed);
        v.put("totalResources",j.getTotalResources());v.put("completedResources",j.getCompletedResources());v.put("totalBytes",j.getTotalBytes().toString());v.put("completedBytes",j.getCompletedBytes().toString());
        int progress="SUCCEEDED".equals(j.getStatus())?100:j.getTotalBytes()>0?(int)Math.min(99,j.getCompletedBytes()*100.0/j.getTotalBytes()):0;
        v.put("progress",progress);v.put("attempts",j.getAttempts());v.put("cancelRequested",j.getCancelRequested());v.put("errorCode",j.getErrorCode());v.put("projectId",j.getTargetProjectId()==null?null:j.getTargetProjectId().toString());return v;
    }
    public void dispatch(){
        if(closing||ports.getIfAvailable()==null)return;
        mapper.cancelExpired();
        for(Long id:mapper.candidates()) {
            if(!slots.tryAcquire())break;String token=UUID.randomUUID().toString().replace("-","");
            if(mapper.claim(id,token)!=1){slots.release();continue;}
            Active context=new Active(token,System.nanoTime(),new AtomicBoolean());active.put(id,context);
            executor.execute(()->{try{services.getObject().performCopy(mapper.job(id),token,(count,bytes)->{
                if(context.stopped().get()||Thread.currentThread().isInterrupted()||mapper.progress(id,token,count,bytes)!=1)throw new ServiceException("复制已取消或执行租约失效");
            });}catch(Exception ex){mapper.failed(id,token,"COPY_FAILED_OR_ACCESS_CHANGED");}finally{active.remove(id,context);try{files.abandon(token);}finally{slots.release();}}});
        }
    }
    public void heartbeat(){active.forEach((id,context)->{if(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-context.start())>maxSeconds||mapper.heartbeat(id,context.token())!=1)context.stopped().set(true);});}
    public void cleanup(){
        if(closing||!cleanupRunning.compareAndSet(false,true))return;
        try{cleanupExecutor.execute(()->{try{files.sweep();}finally{cleanupRunning.set(false);}});}catch(RejectedExecutionException ex){cleanupRunning.set(false);}
    }
    public interface Progress {void update(int count,long bytes);}
    /** 在最终提交事务内锁定任务，确认租约与撤销状态。 */
    public void requireLease(CanvasCopyJob job,String token){CanvasCopyJob current=mapper.lockJob(job.getId());if(current==null||!"COPYING".equals(current.getStatus())||!token.equals(current.getWorkerToken())||Boolean.TRUE.equals(current.getCancelRequested())||current.getLeaseUntil().before(new Date()))throw new ServiceException("复制执行凭据已失效");}
    public void succeeded(CanvasCopyJob job,String token,Long project){if(mapper.succeeded(job.getId(),token,project)!=1)throw new ServiceException("复制已取消或租约失效");}
}
