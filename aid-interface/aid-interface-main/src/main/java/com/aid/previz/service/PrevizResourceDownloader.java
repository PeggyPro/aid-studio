package com.aid.previz.service;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import com.aid.media.provider.TextExecutionTransportScope;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Credential-free, public-only resource download with pinned DNS, a bounded queue and hard cancellation. */
@Component
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizResourceDownloader {
    public record Resource(byte[] bytes,String mediaType,String finalUrl) {}
    public static final class FetchException extends IOException {
        private final String code;private final Integer httpStatus;
        public FetchException(String code,Integer status){super(code);this.code=code;this.httpStatus=status;}
        public String code(){return code;}public Integer httpStatus(){return httpStatus;}
    }
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var thread=new Thread(r,"previz-resource-fetch");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public Resource fetch(String url,int maximumBytes)throws IOException {
        return fetch(url,maximumBytes,30000);
    }
    public Resource fetch(String url,int maximumBytes,long maximumMillis)throws IOException {
        if(maximumMillis<1||maximumMillis>30000)throw new FetchException("RESOURCE_FETCH_TIMEOUT",null);
        if(maximumBytes<1||maximumBytes>32*1024*1024)throw new FetchException("RESOURCE_SIZE_LIMIT_INVALID",null);
        validateUri(url);var scope=new AtomicReference<TextExecutionTransportScope>();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();Future<Resource> future;
        try{future=executor.submit(()->{try(var owned=TextExecutionTransportScope.open()){scope.set(owned);if(cancelled.get()||Thread.currentThread().isInterrupted())throw new FetchException("RESOURCE_FETCH_CANCELLED",null);return download(url,maximumBytes);}});}
        catch(RejectedExecutionException busy){throw new FetchException("RESOURCE_FETCH_BUSY",null);}
        try{return future.get(maximumMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){cancelled.set(true);if(scope.get()!=null)scope.get().cancel();future.cancel(true);throw new FetchException("RESOURCE_FETCH_TIMEOUT",null);}
        catch(InterruptedException interrupted){cancelled.set(true);if(scope.get()!=null)scope.get().cancel();future.cancel(true);Thread.currentThread().interrupt();throw new FetchException("RESOURCE_FETCH_CANCELLED",null);}
        catch(ExecutionException failure){if(failure.getCause() instanceof FetchException known)throw known;if(failure.getCause() instanceof IOException io)throw new FetchException(io instanceof SocketTimeoutException?"RESOURCE_FETCH_TIMEOUT":"RESOURCE_FETCH_CONNECTION",null);throw new FetchException("RESOURCE_FETCH_FAILED",null);}
    }
    private Resource download(String url,int maximumBytes)throws IOException {
        URI current=validateUri(url);Set<String> visited=new HashSet<>();
        for(int hop=0;hop<=3;hop++){
            if(!visited.add(current.toASCIIString()))throw new FetchException("RESOURCE_REDIRECT_LOOP",null);
            InetAddress[] pinned;try{pinned=InetAddress.getAllByName(current.getHost());}catch(UnknownHostException unresolved){throw new FetchException("RESOURCE_DNS_FAILED",null);}if(pinned.length==0)throw new FetchException("RESOURCE_DNS_FAILED",null);
            for(var address:pinned)if(!publicAddress(address))throw new FetchException("RESOURCE_ADDRESS_FORBIDDEN",null);
            String host=current.getHost().replace("[","").replace("]","");var request=new HttpGet(current);request.setConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000).setConnectionRequestTimeout(5000).setRedirectsEnabled(false).build());
            request.setHeader("Accept-Encoding","identity");request.setHeader("User-Agent","AID-Resource-Reader/1.0");TextExecutionTransportScope.attach(request::abort);
            try(var client=HttpClients.custom().setDnsResolver(name->{if(!host.equalsIgnoreCase(name))throw new UnknownHostException("RESOURCE_DNS_HOST_CHANGED");return pinned.clone();}).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableAuthCaching().disableContentCompression().build();var response=client.execute(request)){
                int status=response.getStatusLine().getStatusCode();
                if(Set.of(301,302,303,307,308).contains(status)){
                    if(hop==3)throw new FetchException("RESOURCE_REDIRECT_LIMIT",status);var location=response.getFirstHeader("Location");if(location==null)throw new FetchException("RESOURCE_REDIRECT_INVALID",status);
                    URI next=validateUri(current.resolve(location.getValue()).toString());if("https".equalsIgnoreCase(current.getScheme())&&!"https".equalsIgnoreCase(next.getScheme()))throw new FetchException("RESOURCE_REDIRECT_DOWNGRADE",status);current=next;continue;
                }
                if(status<200||status>=300)throw new FetchException("RESOURCE_HTTP_ERROR",status);
                var entity=response.getEntity();if(entity==null)throw new FetchException("RESOURCE_BODY_EMPTY",status);
                if(entity.getContentLength()>maximumBytes)throw new FetchException("RESOURCE_BODY_TOO_LARGE",status);
                var encoding=response.getFirstHeader("Content-Encoding");String coding=encoding==null?"identity":encoding.getValue().trim().toLowerCase(Locale.ROOT);
                if(!Set.of("identity","gzip","deflate").contains(coding))throw new FetchException("RESOURCE_ENCODING_UNSUPPORTED",status);
                byte[] bytes;try(var raw=entity.getContent();var bounded=new LimitedInput(raw,maximumBytes);var input=decode(bounded,coding)){bytes=input.readNBytes(maximumBytes+1);}
                catch(java.util.zip.ZipException invalid){throw new FetchException("RESOURCE_BODY_INVALID",status);}
                if(bytes.length==0)throw new FetchException("RESOURCE_BODY_EMPTY",status);if(bytes.length>maximumBytes)throw new FetchException("RESOURCE_BODY_TOO_LARGE",status);
                var contentType=response.getFirstHeader("Content-Type");return new Resource(bytes,contentType==null?"application/octet-stream":contentType.getValue().split(";",2)[0].trim().toLowerCase(Locale.ROOT),current.toASCIIString());
            }
        }
        throw new FetchException("RESOURCE_REDIRECT_LIMIT",null);
    }
    public static URI validateUri(String value)throws FetchException{
        try{if(value==null||value.length()>8192)throw new IllegalArgumentException();URI uri=URI.create(value);if(uri.getFragment()!=null)uri=URI.create(value.substring(0,value.indexOf('#')));if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();return uri;}
        catch(IllegalArgumentException invalid){throw new FetchException("RESOURCE_URL_INVALID",null);}
    }
    public static boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())return false;byte[] b=address.getAddress();
        if(b.length==4){int a=b[0]&255,c=b[1]&255,d=b[2]&255;return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==169&&c==254)&&!(a==172&&c>=16&&c<=31)&&!(a==192&&(c==168||c==0&&(d==0||d==2)||c==88&&d==99))&&!(a==198&&(c==18||c==19||c==51&&d==100))&&!(a==203&&c==0&&d==113);}
        return b.length==16&&(b[0]&0xe0)==0x20&&!((b[0]&255)==0x20&&(b[1]&255)==2)&&!((b[0]&255)==0x20&&(b[1]&255)==1&&((b[2]&255)==0x0d&&(b[3]&255)==0xb8||(b[2]&255)==0&&(b[3]&255)<=1));
    }
    static InputStream decode(InputStream input,String coding)throws IOException{return switch(coding){case "gzip"->new java.util.zip.GZIPInputStream(input);case "deflate"->new java.util.zip.InflaterInputStream(input);default->input;};}
    private static final class LimitedInput extends FilterInputStream {
        private long count;private final int maximum;
        LimitedInput(InputStream input,int maximum){super(input);this.maximum=maximum;}
        @Override public int read()throws IOException{int value=super.read();if(value>=0&&++count>maximum)throw new FetchException("RESOURCE_BODY_TOO_LARGE",null);return value;}
        @Override public int read(byte[] data,int offset,int length)throws IOException{int read=in.read(data,offset,length);if(read>0&&(count+=read)>maximum)throw new FetchException("RESOURCE_BODY_TOO_LARGE",null);return read;}
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
