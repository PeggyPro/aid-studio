package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.compute.*;
import com.aid.compute.domain.AidComputeReservation;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Binds existing Runtime jobs to administrator-managed resources. No model-controlled routing. */
@Service
@ConditionalOnProperty(prefix="aid.previz.local-worker",name="enabled",havingValue="true")
public final class PrevizComputeDispatchService {
    private final ComputeReservationService reservations;
    private final ComputeReservationReconciler reconciler;
    private final AidComputeNodeMapper nodes;
    private final ComputeCredentialCodec credentials;
    private final SceneAssistantSettings settings;
    private final Path baseProfile, controls;
    @jakarta.annotation.Resource private PrevizProjectService projectLifecycle;
    @jakarta.annotation.Resource private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public PrevizComputeDispatchService(ComputeReservationService reservations,ComputeReservationReconciler reconciler,
            AidComputeNodeMapper nodes,ComputeCredentialCodec credentials,SceneAssistantSettings settings,
            @Value("${aid.previz.local-worker.profile}")String profile,
            @Value("${aid.previz.local-worker.requests-root}")String root)throws IOException {
        this.reservations=reservations;this.reconciler=reconciler;this.nodes=nodes;this.credentials=credentials;this.settings=settings;
        baseProfile=Path.of(profile);controls=Path.of(root).resolve("dispatch");
        if(!baseProfile.isAbsolute()||!controls.isAbsolute())throw new IllegalArgumentException("计算路由目录必须为绝对路径");
        Files.createDirectories(controls);if(Files.isSymbolicLink(controls))throw new IllegalArgumentException("计算路由目录不能是链接");
        privatePermissions(controls,true);
    }
    public record Route(Path profile,boolean managed,boolean waiting,String requestKey,List<Path> temporaryFiles,Long queuePosition) implements AutoCloseable {
        public String waitingMessage(){return com.aid.runtime.tool.RuntimeResourceWait.message(queuePosition);}
        @Override public void close()throws IOException{IOException failure=null;for(Path file:temporaryFiles)try{Files.deleteIfExists(file);}catch(IOException e){failure=e;}if(failure!=null)throw failure;}
    }
    public AidComputeReservation binding(String job){ComputeResourcePolicy.key(job);return reservations.find(job);}
    public void reconcileExisting(String job){var row=binding(job);if(row!=null&&row.getWorkerJobId()!=null)reconciler.reconcile(job);}

    /** A preview reads the GLB on its original worker. Never move a lookup to another machine. */
    public Route route(String job,String project,String engine,String kind,String buildJob,boolean create)throws IOException {
        for(String key:List.of(job,project,engine,kind))ComputeResourcePolicy.key(key);
        Path marker=controls.resolve(job+".json");
        if(!Files.exists(marker)&&create){
            String pool=buildJob==null?settings.poolForNewBuild():"";
            String preferred=null;
            if(buildJob!=null){
                var original=reservations.find(buildJob);
                if(original==null)pool=""; // An older local build keeps its original execution route.
                else {
                    if(!project.equals(original.getProjectKey())||original.getNodeKey()==null||!"BUILT".equals(original.getState()))
                        throw new ServiceException("原构建资源回执尚未核实",409);
                    preferred=original.getNodeKey();
                    JSONObject originalRoute=read(controls.resolve(buildJob+".json"),4096);
                    pool=originalRoute.getString("pool");
                }
            }
            JSONObject identity=JSONObject.of("mode",pool==null||pool.isBlank()?"LEGACY":"MANAGED","projectId",project,"engine",engine,"kind",kind);
            if(pool!=null&&!pool.isBlank()){ComputeResourcePolicy.key(pool);identity.put("pool",pool);if(preferred!=null)identity.put("nodeKey",preferred);}
            try{Files.writeString(marker,identity.toJSONString(),StandardOpenOption.CREATE_NEW);privatePermissions(marker,false);}
            catch(FileAlreadyExistsException sameJob){ /* The durable identity below decides the route. */ }
        }
        if(!Files.exists(marker))return new Route(baseProfile,false,false,job,List.of(),null); // Historical lookup.
        JSONObject identity=read(marker,4096);
        if(!project.equals(identity.getString("projectId"))||!engine.equals(identity.getString("engine"))||!kind.equals(identity.getString("kind")))
            throw new ServiceException("计算路由与原任务不一致",409);
        if("LEGACY".equals(identity.getString("mode")))return new Route(baseProfile,false,false,job,List.of(),null);
        if(!"MANAGED".equals(identity.getString("mode")))throw new ServiceException("计算路由记录无效",409);
        var request=new ComputeReservationService.Request(job,job,project,identity.getString("pool"),engine,kind,identity.getString("nodeKey"));
        var row=create?new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            projectLifecycle.lockActiveProject(project);return reservations.reserve(request);
        }):reservations.inspectPending(request);
        if(row!=null&&"FAILED".equals(row.getState())&&row.getNodeKey()==null&&row.getWorkerJobId()==null)
            throw new ComputeResourcesUnavailableException();
        if(row==null||"PENDING".equals(row.getState()))return new Route(null,true,true,job,List.of(),row==null?null:row.getQueuePosition());
        if("CANCELED".equals(row.getState())&&row.getWorkerJobId()==null)throw new ServiceException("构建任务已停止",409);
        if(!project.equals(row.getProjectKey())||!engine.equals(row.getEngine())||!kind.equals(row.getTaskKind()))throw new ServiceException("计算预留身份不一致",409);
        var version=nodes.readRevision(row.getNodeKey(),row.getConfigRevision());
        if(version==null)throw new ServiceException("原计算配置版本不可用",409);
        var config=JSON.parseObject(version.getDraftConfigJson(),ComputeNodeConfiguration.class);
        JSONObject profile=read(baseProfile,16384);
        // Only host-owned archive locations come from the bootstrap profile. Endpoint/credential
        // always come from the immutable administrator revision pinned by the reservation.
        Path secret=null,generated=null;
        try{
            secret=Files.createTempFile(controls,"credential-",".private.json");privatePermissions(secret,false);
            Files.writeString(secret,JSONObject.of("secret",credentials.decrypt(version.getDraftWorkerId(),version.getDraftCredentialCiphertext())).toJSONString());
            profile.put("mode","REMOTE_COMPUTE");profile.put("enabled",true);profile.put("endpoint",config.endpoint());profile.put("tlsCaPem",config.tlsCaPem());
            profile.put("workerId",version.getDraftWorkerId());profile.put("credentialFile",secret.toString());
            profile.put("localAcceptance",config.endpoint().startsWith("http://127.0.0.1:")||config.endpoint().startsWith("http://[::1]:"));
            generated=Files.createTempFile(controls,"route-",".private.json");privatePermissions(generated,false);Files.writeString(generated,profile.toJSONString());
            return new Route(generated,true,false,job,List.of(generated,secret),null);
        }catch(IOException|RuntimeException failure){if(generated!=null)Files.deleteIfExists(generated);if(secret!=null)Files.deleteIfExists(secret);throw failure;}
    }
    public void beforeSubmission(Route route){if(route.managed()&&!route.waiting())reservations.bindSubmission(route.requestKey(),route.requestKey());}
    public void cancelUnsubmitted(String job,String project){reservations.cancelUnsubmitted(job,project);}
    public void reconcile(Route route){if(route.managed()&&!route.waiting()){
        var row=reservations.find(route.requestKey());if(row!=null&&row.getWorkerJobId()!=null)reconciler.reconcile(route.requestKey());
    }}
    private static JSONObject read(Path file,long maximum)throws IOException{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>maximum)throw new ServiceException("计算路由文件无效",409);
        var value=JSON.parseObject(Files.readString(file));if(value==null)throw new ServiceException("计算路由文件无效",409);return value;
    }
    private static void privatePermissions(Path file,boolean directory)throws IOException{
        if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
}
