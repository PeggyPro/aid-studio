package com.aid.compute;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import javax.net.ssl.*;

/** Operator supplied trust anchors are scoped to a connection, never installed globally. */
public final class ComputeTlsSupport {
    public static final int MAX_PEM_BYTES=65536;
    private static final Pattern BLOCK=Pattern.compile("-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\r\\n\\t ]+)\\s*-----END CERTIFICATE-----");
    private static final Map<String,HttpClient> CLIENTS=new LinkedHashMap<>(16,.75f,true);
    private static final HttpClient DEFAULT=builder().build();
    private ComputeTlsSupport() { }
    public record CertificateInfo(String subject,String issuer,String sha256,String notBefore,String notAfter) { }
    public record CertificateBundle(String pem,List<CertificateInfo> certificates) { }
    public static CertificateBundle inspect(String pem) {return parse(pem,true);}
    private static CertificateBundle parse(String pem,boolean checkDates) {
        if(pem==null||pem.isBlank())return new CertificateBundle(null,List.of());
        if(pem.length()>MAX_PEM_BYTES||pem.getBytes(StandardCharsets.UTF_8).length>MAX_PEM_BYTES)
            throw new IllegalArgumentException("CA证书文件不能超过64 KiB");
        if(pem.contains("PRIVATE KEY"))throw new IllegalArgumentException("请上传CA公共证书，不要上传私钥");
        var matcher=BLOCK.matcher(pem);var normalized=new StringBuilder();var info=new ArrayList<CertificateInfo>();var seen=new HashSet<String>();int end=0;
        try {
            var factory=CertificateFactory.getInstance("X.509");
            while(matcher.find()) {
                if(!pem.substring(end,matcher.start()).isBlank()||info.size()>=8)throw new IllegalArgumentException("请上传PEM格式CA证书，最多8张");
                byte[] bytes=Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
                var input=new ByteArrayInputStream(bytes);var cert=(X509Certificate)factory.generateCertificate(input);
                if(input.available()!=0)throw new IllegalArgumentException("CA证书内容无效");
                if(checkDates)cert.checkValidity();
                boolean[] usage=cert.getKeyUsage();
                if(cert.getBasicConstraints()<0||usage!=null&&(usage.length<=5||!usage[5]))
                    throw new IllegalArgumentException("文件不是可签发证书的CA，请上传CA公共证书");
                byte[] encoded=cert.getEncoded();String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
                if(!seen.add(fingerprint))throw new IllegalArgumentException("证书文件包含重复CA");
                normalized.append("-----BEGIN CERTIFICATE-----\n").append(Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(encoded)).append("\n-----END CERTIFICATE-----\n");
                info.add(new CertificateInfo(cert.getSubjectX500Principal().getName(),cert.getIssuerX500Principal().getName(),fingerprint,cert.getNotBefore().toInstant().toString(),cert.getNotAfter().toInstant().toString()));
                end=matcher.end();
            }
            if(info.isEmpty()||!pem.substring(end).isBlank())throw new IllegalArgumentException("请上传PEM格式的.crt或.pem CA证书");
            return new CertificateBundle(normalized.toString(),List.copyOf(info));
        } catch(CertificateExpiredException expired){throw new IllegalArgumentException("CA证书已过期，请更换有效证书");}
        catch(CertificateNotYetValidException early){throw new IllegalArgumentException("CA证书尚未生效，请检查证书有效期和系统时间");}
        catch(GeneralSecurityException|ClassCastException invalid){throw new IllegalArgumentException("CA证书无法解析，请检查文件内容");}
    }
    public static String normalize(String pem){return parse(pem,false).pem();}
    public static HttpClient client(String pem) {
        String normalized=inspect(pem).pem();if(normalized==null)return DEFAULT;
        synchronized(CLIENTS) {
            HttpClient cached=CLIENTS.get(normalized);if(cached!=null)return cached;
            try {
                KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
                var certs=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(normalized.getBytes(StandardCharsets.US_ASCII)));
                int i=0;for(var cert:certs)store.setCertificateEntry("node-ca-"+(i++),cert);
                var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
                var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);
                var parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                var client=builder().sslContext(context).sslParameters(parameters).build();
                if(CLIENTS.size()>=64)CLIENTS.remove(CLIENTS.keySet().iterator().next());
                CLIENTS.put(normalized,client);return client;
            } catch(Exception invalid){throw new IllegalArgumentException("无法初始化节点证书信任配置");}
        }
    }
    private static HttpClient.Builder builder(){return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}});}
    public static String connectionError(Throwable error,String fallback) {
        for(int i=0;error!=null&&i<12;i++,error=error.getCause())
            if(error instanceof SSLException||error instanceof CertificateException)
                return "节点TLS证书验证失败，请上传正确的CA证书，并核对访问地址与证书域名/IP及有效期";
        return fallback;
    }
}
