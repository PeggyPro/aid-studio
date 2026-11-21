package com.aid.compute;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded authenticated control requests. Mutations are reconciled by version, never blindly retried. */
@Component
public class ComputeWorkerClient {
    private final SecureRandom random=new SecureRandom();
    public static String canonical(Object value){return JSON.toJSONString(sorted(JSON.parse(JSON.toJSONString(value))));}
    private static Object sorted(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(String.valueOf(k),
            "engines".equals(String.valueOf(k))&&v instanceof List<?> list?list.stream().map(String::valueOf).sorted().toList():sorted(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(ComputeWorkerClient::sorted).toList();return value;
    }
    public static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public JSONObject call(String endpoint,String workerId,String secret,String path,Object body){return call(endpoint,workerId,secret,path,body,null);}
    public JSONObject call(String endpoint,String workerId,String secret,String path,Object body,String tlsCaPem){
        endpoint=ComputeNodeConfiguration.normalizeEndpoint(endpoint);
        if(!Set.of("/v1/configuration/apply","/v1/configuration/status","/v1/capabilities","/v1/jobs/status").contains(path))throw new IllegalArgumentException("不支持的节点管理操作");
        byte[] key=Base64.getDecoder().decode(secret);
        try{
            long now=System.currentTimeMillis();byte[] nonce=new byte[24];random.nextBytes(nonce);
            String payload=canonical(body);if(payload.length()>200000)throw new ServiceException("节点配置过大",400);
            var auth=new TreeMap<String,Object>();auth.put("protocol","aid.scene-compute/1");auth.put("workerId",workerId);
            auth.put("issuedAt",now);auth.put("expiresAt",now+60000);auth.put("nonce",HexFormat.of().formatHex(nonce));
            auth.put("method","POST");auth.put("path",path);auth.put("digest",digest(body));
            byte[] canonical=canonical(auth).getBytes(StandardCharsets.UTF_8);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            var request=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(10))
                .header("content-type","application/json").header("x-aid-compute-auth",Base64.getUrlEncoder().withoutPadding().encodeToString(canonical))
                .header("x-aid-compute-signature",HexFormat.of().formatHex(mac.doFinal(canonical))).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var future=ComputeTlsSupport.client(tlsCaPem).sendAsync(request,info->new LimitedBody());HttpResponse<byte[]> response;
            try{response=future.get(10,TimeUnit.SECONDS);}finally{if(!future.isDone())future.cancel(true);}
            if(response.statusCode()==401||response.statusCode()==403)throw new ServiceException("计算节点认证失败",400);
            JSONObject data=JSON.parseObject(new String(response.body(),StandardCharsets.UTF_8));
            if(data==null||!"aid.scene-compute/1".equals(data.getString("protocol"))||!workerId.equals(data.getString("workerId")))throw new ServiceException("计算节点身份或协议不匹配",502);
            if(response.statusCode()!=200){String code=data.getString("errorCode");
                throw new ServiceException("节点未接受配置："+(code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"WORKER_REJECTED"),409);}
            return data;
        }catch(ServiceException expected){throw expected;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ServiceException("节点操作中断，需重新查询配置状态",503);}
        catch(Exception unavailable){throw new ServiceException(ComputeTlsSupport.connectionError(unavailable,"节点连接或回执未确认；再次操作将先核对已生效版本"),503);}
        finally{Arrays.fill(key,(byte)0);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> items){for(ByteBuffer item:items){if(item.remaining()>262144-bytes.size()){subscription.cancel();body.completeExceptionally(new IOException("COMPUTE_RESPONSE_LIMIT"));return;}
            byte[] chunk=new byte[item.remaining()];item.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        public void onError(Throwable error){body.completeExceptionally(error);}public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
