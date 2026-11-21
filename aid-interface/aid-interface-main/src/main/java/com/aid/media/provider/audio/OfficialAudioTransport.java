package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.tokendance.security.TokenDancePublicMediaDownloader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 官方音频协议共用传输：可配置路径、禁止自动重发/跳转、受限二进制响应。 */
@Component
@lombok.RequiredArgsConstructor
public class OfficialAudioTransport {
    private final com.aid.media.service.VerifiedMediaMetadataService metadata;
    public static final int MAX_RESPONSE_BYTES = 320 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Construct and validate configuration before any task or wallet reservation is created. */
    public void validateConfiguration(AiModelConfigVo config, OfficialAudioContractRegistry.Plan plan) {
        authorized(config, ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(), resolvePath(config.getApiSuffix(), plan.path())));
    }

    public Response submit(AiModelConfigVo config, OfficialAudioContractRegistry.Plan plan, JsonNode contract) {
        String path = resolvePath(config.getApiSuffix(), plan.path());
        Map<String,Object> queryValues=new java.util.LinkedHashMap<>();
        if(config.getExtraQueryJson()!=null&&!config.getExtraQueryJson().isBlank())try{queryValues.putAll(JSON.readValue(config.getExtraQueryJson(),java.util.LinkedHashMap.class));}catch(Exception invalid){throw new ServiceException("音频扩展查询配置无效");}
        queryValues.putAll(plan.query());
        String url = query(ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(), path), queryValues);
        if(plan.method().equals("WS"))return websocket(config,url,plan);
        String type = plan.contentType();
        HttpRequest.BodyPublisher payload;
        try {
            if (type.startsWith("multipart/form-data")) {
                String boundary="aid-audio-"+UUID.randomUUID();
                payload=multipart(plan.body(), contract.path("requestSchema"), boundary);
                type="multipart/form-data; boundary="+boundary;
            } else payload=HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(plan.body()));
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("音频请求无法编码"); }
        HttpRequest.Builder request=authorized(config, url).header("Content-Type",type)
                .method(plan.method(), plan.method().equals("GET") || plan.method().equals("DELETE")
                        ? HttpRequest.BodyPublishers.noBody() : payload);
        return execute(request.build(), false);
    }

    private Response websocket(AiModelConfigVo config,String address,OfficialAudioContractRegistry.Plan plan) {
        String url=address.replaceFirst("^https:","wss:").replaceFirst("^http:","ws:");
        java.util.concurrent.CompletableFuture<byte[]> completed=new java.util.concurrent.CompletableFuture<>();
        java.net.http.WebSocket socket=null;
        try {
            String header=config.getAuthHeader()==null?"xi-api-key":config.getAuthHeader();
            String prefix=config.getAuthPrefix()==null?"":config.getAuthPrefix();
            if(config.getApiKey()==null||config.getApiKey().isBlank())throw com.aid.media.provider.ProviderSubmissionFailures.notSent("音频供应商密钥未配置");
            socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header(header,prefix+config.getApiKey())
                .buildAsync(URI.create(url),new java.net.http.WebSocket.Listener(){
                    final StringBuilder frame=new StringBuilder();final ByteArrayOutputStream frames=new ByteArrayOutputStream();
                    public void onOpen(java.net.http.WebSocket ws){ws.request(1);}
                    public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket ws,CharSequence text,boolean last){
                        try {
                            if(frame.length()+text.length()>16*1024*1024)throw new ServiceException("音频消息过大");
                            frame.append(text);
                            if(last){byte[] bytes=(frame+"\n").getBytes(StandardCharsets.UTF_8);if((long)frames.size()+bytes.length>MAX_RESPONSE_BYTES)throw new ServiceException("音频响应过大");frames.writeBytes(bytes);frame.setLength(0);}
                            ws.request(1);
                        } catch(Exception invalid){completed.completeExceptionally(invalid);ws.abort();}
                        return null;
                    }
                    public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket ws,int status,String reason){
                        // Error frames retain the vendor's structured error even when followed by a non-normal close.
                        if(frames.size()>0)completed.complete(frames.toByteArray());else completed.completeExceptionally(new java.io.IOException("empty audio websocket"));return null;
                    }
                    public void onError(java.net.http.WebSocket ws,Throwable error){completed.completeExceptionally(error);}
                }).get(15,java.util.concurrent.TimeUnit.SECONDS);
            java.net.http.WebSocket ownedSocket=socket;
            com.aid.media.provider.TextExecutionTransportScope.attach(ownedSocket::abort);
            for(Object message:(List<?>)plan.body().get("messages"))socket.sendText(JSON.writeValueAsString(message),true).get(30,java.util.concurrent.TimeUnit.SECONDS);
            return new Response(200,"application/x-ndjson",completed.get(10,java.util.concurrent.TimeUnit.MINUTES),null,Map.of());
        } catch(Exception unknown){
            if(unknown instanceof InterruptedException)Thread.currentThread().interrupt();
            Throwable cause=unknown;
            while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
            if(cause instanceof java.net.http.WebSocketHandshakeException rejected)
                return new Response(rejected.getResponse().statusCode(),"application/json",new byte[0],null,Map.of());
            throw new ProviderSubmissionOutcomeUnknownException();
        }
        finally{if(socket!=null)socket.abort();}
    }

    public Response queryTask(AiModelConfigVo config,String taskId) {
        return execute(authorized(config, ProviderEndpointUtils.buildTaskQueryUrl(
                config.getBaseUrl(),config.getTaskQuerySuffix(),taskId)).GET().build(),true);
    }

    public Response accountGet(AiModelConfigVo config,String path) {
        return execute(authorized(config,ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(),ProviderEndpointUtils.normalizeSubmitPath(path)))
                .timeout(Duration.ofSeconds(30)).GET().build(),true);
    }

    private HttpRequest.Builder authorized(AiModelConfigVo config,String url) {
        if(config.getApiKey()==null||config.getApiKey().isBlank())throw com.aid.media.provider.ProviderSubmissionFailures.notSent("音频供应商密钥未配置");
        String header=config.getAuthHeader();
        if(header==null||header.isBlank())header="Authorization";
        String prefix=config.getAuthPrefix();
        if(prefix==null)prefix="Authorization".equalsIgnoreCase(header)?"Bearer ":"";
        if(!header.matches("[A-Za-z][A-Za-z0-9-]{0,63}")||prefix.contains("\r")||prefix.contains("\n"))throw new ServiceException("音频鉴权配置无效");
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header(header,prefix+config.getApiKey().trim()).header("Accept-Encoding","identity");
        request.header("User-Agent","AID-official-audio");
        if("X-Api-Key".equalsIgnoreCase(header))request.header("X-Api-Request-Id",UUID.randomUUID().toString());
        if(config.getExtraHeadersJson()!=null&&!config.getExtraHeadersJson().isBlank())try {
            JsonNode headers=JSON.readTree(config.getExtraHeadersJson());
            if(!headers.isObject())throw new IllegalArgumentException();
            var values=headers.fields();while(values.hasNext()) {
                var value=values.next();String name=value.getKey();
                if(!name.matches("[A-Za-z][A-Za-z0-9-]{0,63}")||!value.getValue().isTextual()||java.util.Set.of("host","content-length","connection","transfer-encoding").contains(name.toLowerCase()))throw new IllegalArgumentException();
                request.setHeader(name,value.getValue().asText());
            }
        }catch(Exception invalid){throw new ServiceException("音频扩展请求头配置无效");}
        return request;
    }

    private Response execute(HttpRequest request,boolean query) {
        try {
            HttpResponse<java.io.InputStream> response=com.aid.media.provider.TextExecutionTransportScope.send(http,request,HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()) {
                int limit=response.statusCode()>=200&&response.statusCode()<300?MAX_RESPONSE_BYTES:256*1024;
                byte[] bytes=in.readNBytes(limit+1);
                if(bytes.length>limit)throw new ServiceException("音频响应超过安全上限");
                return new Response(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),bytes,
                        response.headers().firstValue("request-id").orElse(response.headers().firstValue("trace_id")
                                .orElse(response.headers().firstValue("X-Tt-Logid").orElse(null))),
                        response.headers().map());
            }
        } catch(Exception ex) {
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            if(query)throw new ServiceException("上游音频查询暂不可用");
            // 进入HTTP之后，包括读流/解析上限异常，都不能证明请求未受理。
            throw new ProviderSubmissionOutcomeUnknownException();
        }
    }

    public static String resolvePath(String template,Map<String,Object> values) {
        String result=ProviderEndpointUtils.normalizeSubmitPath(template);
        for(var entry:values.entrySet()) {
            String value=String.valueOf(entry.getValue());
            if(!value.matches("[A-Za-z0-9_-]{1,200}"))throw new ServiceException("音频路径参数无效");
            String marker="{"+entry.getKey()+"}";
            if(!result.contains(marker))throw new ServiceException("音频路径参数与配置不匹配");
            result=result.replace(marker,value);
        }
        if(result.contains("{")||result.contains("}"))throw new ServiceException("音频接口缺少路径参数");
        return ProviderEndpointUtils.normalizeSubmitPath(result);
    }

    private static String query(String url,Map<String,Object> values) {
        StringBuilder result=new StringBuilder(url);
        for(var entry:values.entrySet()) {
            if(entry.getValue()==null)continue;
            result.append(result.indexOf("?")<0?'?':'&').append(encode(entry.getKey())).append('=')
                    .append(encode(entry.getValue().toString()));
        }
        return result.toString();
    }

    private HttpRequest.BodyPublisher multipart(Map<String,Object> values,JsonNode schema,String boundary)throws Exception {
        List<HttpRequest.BodyPublisher> parts=new java.util.ArrayList<>();long total=0;
        for(var entry:values.entrySet()) {
            JsonNode field=dereference(schema.path("properties").path(entry.getKey()),schema);
            Object value=entry.getValue();if(value==null)continue;
            boolean array=field.path("type").asText().equals("array");
            JsonNode item=array?dereference(field.path("items"),schema):field;
            boolean binary=item.path("format").asText().equals("binary");
            List<?> items=array&&value instanceof List<?> list?list:List.of(value);
            for(Object part:items) {
                String header="--"+boundary+"\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"";
                if(binary) {
                    if(!(part instanceof String source))throw new ServiceException("音频文件必须引用已上传素材URL");
                    byte[] data=metadata.readTrustedBytes(source,300 * 1024 * 1024);
                    String name=URI.create(source).getPath();name=name.substring(name.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._-]","_");
                    if(name.isBlank())name="audio.bin";
                    parts.add(HttpRequest.BodyPublishers.ofString(header+"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n"));
                    parts.add(HttpRequest.BodyPublishers.ofByteArray(data));total+=data.length;
                }else {
                    String text=part instanceof Map<?,?>||part instanceof List<?>?JSON.writeValueAsString(part):part.toString();
                    parts.add(HttpRequest.BodyPublishers.ofString(header+"\r\n\r\n"+text));total+=text.getBytes(StandardCharsets.UTF_8).length;
                }
                parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
                if(total>MAX_RESPONSE_BYTES)throw new ServiceException("音频上传总量超过安全上限");
            }
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--"+boundary+"--\r\n"));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    static JsonNode dereference(JsonNode node,JsonNode schema) {
        for(int i=0;i<16&&node.has("$ref");i++) {
            String ref=node.path("$ref").asText();
            if(!ref.startsWith("#/$defs/"))throw new ServiceException("音频Schema引用无效");
            node=schema.path("$defs").path(ref.substring(8));
        }
        return node;
    }
    private static void write(ByteArrayOutputStream out,String value)throws Exception{out.write(value.getBytes(StandardCharsets.UTF_8));}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public record Response(int status,String contentType,byte[] bytes,String requestId,Map<String,List<String>> headers) {
        public String text(){return new String(bytes,StandardCharsets.UTF_8);}
        public boolean successful(){return status>=200&&status<300;}
    }
}
