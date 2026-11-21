package com.aid.compute;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import com.aid.compute.mapper.AidComputeNodeMapper;

/** Read-only signed worker handshake. A successful probe is not an execution authorization. */
@Service
public class ComputeNodeProbeService {
    private static final String PROTOCOL="aid.scene-compute/1", PATH="/v1/capabilities";
    private final SecureRandom random=new SecureRandom();
    private final ComputeCredentialCodec credentials;
    private final AidComputeNodeMapper nodes;
    private final ConcurrentMap<String,Proof> proofs=new ConcurrentHashMap<>();
    public ComputeNodeProbeService(ComputeCredentialCodec credentials,AidComputeNodeMapper nodes){this.credentials=credentials;this.nodes=nodes;}
    public record Connection(String endpoint,String workerId,String secret,String nodeKey,String tlsCaPem) {
        public Connection(String endpoint,String workerId,String secret,String nodeKey){this(endpoint,workerId,secret,nodeKey,null);}
        public Connection {
            endpoint=ComputeNodeConfiguration.normalizeEndpoint(endpoint);
            if(tlsCaPem!=null)tlsCaPem=Objects.requireNonNullElse(ComputeTlsSupport.inspect(tlsCaPem).pem(),"");
            if(nodeKey!=null)ComputeResourcePolicy.key(nodeKey);
            if((workerId==null||workerId.isBlank())&&nodeKey==null||workerId!=null&&!workerId.isBlank()&&!workerId.matches("[-A-Za-z0-9_]{1,100}"))throw new IllegalArgumentException("请填写有效的计算服务标识");
            if(secret==null||secret.isBlank()){if(nodeKey==null)throw new IllegalArgumentException("首次检测需要计算服务认证密钥");secret=null;}
            else try{if(secret.length()>1024)throw new IllegalArgumentException();int size=Base64.getDecoder().decode(secret).length;
                if(size<32||size>128)throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){throw new IllegalArgumentException("计算服务认证密钥格式无效");}
        }
        @Override public String toString(){return "ComputeConnection[workerId="+workerId+", secret=REDACTED]";}
    }
    public record Resources(long cpuMillis,long memoryBytes,long freeMemoryBytes,long scratchTotalBytes,long scratchFreeBytes) { }
    public record Route(String engine,String taskKind) { }
    public record Result(String proofId,String workerId,String checkedAt,String securityMode,List<String> engines,List<String> taskKinds,
                         List<Route> routes,int currentConcurrency,int activeJobs,Resources resources,boolean resourceLimitsVerified) { }
    public record Proof(Long actor,String endpoint,String workerId,String encryptedCredential,Result result,long expiresAt,AtomicReference<String> target,String tlsCaPem) { }

    public Proof requireProof(String id,Long actor,String endpoint,String target){return requireProof(id,actor,endpoint,target,null);}
    public Proof requireProof(String id,Long actor,String endpoint,String target,String tlsCaPem){
        Proof proof=proofs.get(id);
        if(proof==null||proof.expiresAt()<System.currentTimeMillis()||!Objects.equals(actor,proof.actor())||!endpoint.equals(proof.endpoint())||!Objects.equals(ComputeTlsSupport.normalize(tlsCaPem),proof.tlsCaPem()))
            throw new ServiceException("连接检测已过期或配置已变化，请重新检测",409);
        if(!proof.target().compareAndSet(null,target)&&!target.equals(proof.target().get()))throw new ServiceException("此检测回执已用于其他节点",409);
        return proof;
    }

    public Result probe(Connection connection,Long actor) {
        return probe(connection,actor,true);
    }
    public Result inspect(Connection connection){return probe(connection,1L,false);}
    private Result probe(Connection connection,Long actor,boolean retainProof) {
        if(actor==null||actor<=0)throw new ServiceException("操作身份无效",403);
        String workerId=connection.workerId(),secret=connection.secret();
        String tlsCaPem=connection.tlsCaPem();
        if(tlsCaPem==null&&connection.nodeKey()!=null&&nodes!=null){
            var saved=nodes.selectById(connection.nodeKey());
            if(saved!=null&&saved.getDraftConfigJson()!=null)tlsCaPem=JSON.parseObject(saved.getDraftConfigJson(),ComputeNodeConfiguration.class).tlsCaPem();
        }
        tlsCaPem=ComputeTlsSupport.inspect(tlsCaPem).pem();
        if(secret==null){var row=nodes==null?null:nodes.selectById(connection.nodeKey());
            if(row==null||row.getDraftWorkerId()==null||row.getDraftCredentialCiphertext()==null)throw new ServiceException("此节点尚未保存认证信息，请填写认证密钥",400);
            if(workerId!=null&&!workerId.isBlank()&&!workerId.equals(row.getDraftWorkerId()))throw new ServiceException("服务标识已变化，请重新填写认证信息",409);
            workerId=row.getDraftWorkerId();secret=credentials.decrypt(workerId,row.getDraftCredentialCiphertext());}
        String encrypted;
        try{encrypted=credentials.encrypt(workerId,secret);}catch(IllegalStateException unavailable){throw new ServiceException(unavailable.getMessage(),503);}
        byte[] key=Base64.getDecoder().decode(secret);
        try{
            long now=System.currentTimeMillis();byte[] nonce=new byte[24];random.nextBytes(nonce);
            var auth=new TreeMap<String,Object>();auth.put("protocol",PROTOCOL);auth.put("workerId",workerId);
            auth.put("issuedAt",now);auth.put("expiresAt",now+60000);auth.put("nonce",HexFormat.of().formatHex(nonce));
            auth.put("method","POST");auth.put("path",PATH);
            auth.put("digest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("{}".getBytes(StandardCharsets.UTF_8))));
            byte[] canonical=JSON.toJSONString(auth).getBytes(StandardCharsets.UTF_8);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            var request=HttpRequest.newBuilder(URI.create(connection.endpoint()+PATH)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").header("x-aid-compute-auth",Base64.getUrlEncoder().withoutPadding().encodeToString(canonical))
                    .header("x-aid-compute-signature",HexFormat.of().formatHex(mac.doFinal(canonical)))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var future=ComputeTlsSupport.client(tlsCaPem).sendAsync(request,info->new LimitedBody());
            HttpResponse<byte[]> response;
            try{response=future.get(10,TimeUnit.SECONDS);}finally{if(!future.isDone())future.cancel(true);}
            {
                if(response.statusCode()==401||response.statusCode()==403)throw new ServiceException("计算节点认证失败，请核对服务标识和密钥",400);
                if(response.statusCode()!=200)throw new ServiceException("计算节点未返回有效探测响应",502);
                byte[] bytes=response.body();
                JSONObject data;
                try{data=JSON.parseObject(new String(bytes,StandardCharsets.UTF_8));}
                catch(RuntimeException invalid){throw new ServiceException("计算节点响应格式无效",502);}
                if(data==null||!PROTOCOL.equals(data.getString("protocol"))||!workerId.equals(data.getString("workerId")))
                    throw new ServiceException("计算节点身份或协议不匹配",409);
                var resources=data.getJSONObject("resourcesObserved");
                if(resources==null)throw new ServiceException("节点在线，但尚未提供资源探测能力，请更新计算Worker",409);
                var values=new Resources(positive(resources,"cpuMillis"),positive(resources,"memoryBytes"),nonnegative(resources,"freeMemoryBytes"),
                        positive(resources,"scratchTotalBytes"),nonnegative(resources,"scratchFreeBytes"));
                if(values.freeMemoryBytes()>values.memoryBytes()||values.scratchFreeBytes()>values.scratchTotalBytes())throw new ServiceException("节点资源数据不一致",502);
                List<String> engines=data.getList("executionEngines",String.class);
                if(engines==null||engines.isEmpty()||engines.size()>100)throw new ServiceException("节点引擎清单无效",502);
                for(String engine:engines)ComputeResourcePolicy.key(engine);
                List<String> taskKinds=data.getList("taskKinds",String.class);
                if(taskKinds==null||taskKinds.isEmpty()||taskKinds.size()>100)throw new ServiceException("节点未提供有效的任务类型清单",502);
                for(String kind:taskKinds)ComputeResourcePolicy.key(kind);
                List<Route> routes=data.getList("taskRoutes",Route.class);
                if(routes==null||routes.isEmpty()||routes.size()>500||new HashSet<>(routes).size()!=routes.size()||routes.stream().anyMatch(route->route==null||!engines.contains(route.engine())||!taskKinds.contains(route.taskKind())))throw new ServiceException("节点未提供有效的执行器映射",502);
                int concurrency=Math.toIntExact(positive(data,"concurrency")),active=Math.toIntExact(nonnegative(data,"activeJobs"));
                if(concurrency>10000||data.getString("securityMode")==null||!Set.of("LOCAL_ACCEPTANCE","VERIFIED_ISOLATION").contains(data.getString("securityMode")))throw new ServiceException("节点运行模式或并发能力无效",502);
                var result=new Result(UUID.randomUUID().toString(),workerId,java.time.Instant.now().toString(),data.getString("securityMode"),List.copyOf(engines),List.copyOf(taskKinds),
                        List.copyOf(routes),concurrency,active,values,Boolean.TRUE.equals(resources.get("resourceLimitsVerified")));
                if(retainProof)synchronized(proofs){
                    proofs.entrySet().removeIf(item->item.getValue().expiresAt()<System.currentTimeMillis());
                    if(proofs.size()>=1024)throw new ServiceException("连接检测繁忙，请稍后重试",429);
                    proofs.put(result.proofId(),new Proof(actor,connection.endpoint(),workerId,encrypted,result,System.currentTimeMillis()+300000,new AtomicReference<>(),tlsCaPem));
                }
                return result;
            }
        }catch(ServiceException expected){throw expected;}
        catch(TimeoutException timeout){throw new ServiceException("计算节点连接超时，请检查地址、端口和在线状态",503);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ServiceException("计算节点检测已中断",503);}
        catch(Exception unavailable){throw new ServiceException(ComputeTlsSupport.connectionError(unavailable,"计算节点连接或资源验证失败，请检查服务状态与证书"),503);}
        finally{Arrays.fill(key,(byte)0);}
    }
    private static long positive(JSONObject data,String key){long value=nonnegative(data,key);if(value==0)throw new ServiceException("节点资源字段无效："+key,502);return value;}
    private static long nonnegative(JSONObject data,String key){Object value=data.get(key);if(!(value instanceof Number)||!value.toString().matches("[0-9]{1,16}"))throw new ServiceException("节点资源字段无效："+key,502);
        long number=Long.parseLong(value.toString());if(number>9007199254740991L)throw new ServiceException("节点资源字段超限："+key,502);return number;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> items){
            for(ByteBuffer item:items){
                if(item.remaining()>262144-bytes.size()){subscription.cancel();body.completeExceptionally(new IOException("COMPUTE_PROBE_RESPONSE_LIMIT"));return;}
                byte[] chunk=new byte[item.remaining()];item.get(chunk);bytes.writeBytes(chunk);
            }subscription.request(1);
        }
        public void onError(Throwable error){body.completeExceptionally(error);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
