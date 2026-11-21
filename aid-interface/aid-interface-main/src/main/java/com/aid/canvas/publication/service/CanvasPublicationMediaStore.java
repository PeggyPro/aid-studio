package com.aid.canvas.publication.service;

import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 发布文件独立保存在私有目录；网关每次重新鉴权，不向外暴露源地址。 */
@Component
@RequiredArgsConstructor
public class CanvasPublicationMediaStore {
    private final MediaUrlResolver urls;
    private final com.aid.common.aid.oss.core.OssTemplate oss;
    private final CanvasPublicationFiles files;
    @Value("${canvas.publication.storage-root:}") private String storageRoot;
    @Value("${canvas.publication.max-resource-bytes:67108864}") private long maxBytes;
    @Value("${canvas.publication.max-total-bytes:1073741824}") private long maxTotalBytes;
    @Value("${canvas.publication.max-concurrent-preparations:2}") private int concurrency=2;
    private java.util.concurrent.Semaphore slots=new java.util.concurrent.Semaphore(2);
    @jakarta.annotation.PostConstruct void configure() {
        if(concurrency<1||concurrency>8||maxBytes<1||maxTotalBytes<maxBytes) throw new IllegalArgumentException("Invalid publication media capacity");
        slots=new java.util.concurrent.Semaphore(concurrency);
    }
    public boolean ready() { return storageRoot!=null&&!storageRoot.isBlank(); }
    public record Frozen(String json,String batch) {}
    public record PreparedCopy(String json,List<String> uploaded) {}
    /** 独立副本沿用现有用户素材存储，供已有生成/元数据流程使用。 */
    public PreparedCopy prepareCopy(String snapshotJson) {
        throw new ServiceException("复制须通过持久化任务执行");
    }
    public PreparedCopy prepareCopy(String snapshotJson,String scope,CanvasPublicationCopyJobs.Progress progress) {return limited(()->prepareCopyInternal(snapshotJson,scope,progress));}
    private PreparedCopy prepareCopyInternal(String snapshotJson,String scope,CanvasPublicationCopyJobs.Progress progress) {
        JSONObject snapshot=JSON.parseObject(snapshotJson);
        List<String> created=new ArrayList<>();
        long copiedBytes=0;int count=0;
        try {
            for(Object raw:snapshot.getJSONArray("resources")) {
                JSONObject row=(JSONObject)raw;
                Path source=file(row.getString("storageUrl"));
                if(Files.size(source)>maxBytes) throw new IOException("resource too large");
                String mime=Objects.toString(row.getString("mimeType"),"");
                String suffix=switch(mime) {
                    case "image/png" -> ".png";case "image/jpeg" -> ".jpg";case "image/webp" -> ".webp";
                    case "video/mp4" -> ".mp4";case "audio/mpeg" -> ".mp3";case "audio/wav","audio/x-wav" -> ".wav";
                    case "model/gltf-binary" -> ".glb";default -> ".bin";
                };
                String extension=row.getString("fileExtension");
                if(extension!=null&&extension.matches("[a-zA-Z0-9]{1,8}")) suffix="."+extension;
                if(!Objects.equals(row.getString("contentHash"),hashFile(source)))throw new IOException("frozen file hash changed");
                progress.update(count,copiedBytes);
                String location=files.upload(scope,Files.readAllBytes(source),suffix.substring(1));
                if(location==null||location.isBlank()) throw new IOException("empty uploaded location");
                created.add(location);
                String preparedLocation=urls.toFullUrl(location);
                if(preparedLocation==null||preparedLocation.isBlank()||!urls.isSiteMediaUrl(preparedLocation))
                    throw new IOException("untrusted uploaded location");
                row.put("storageUrl",preparedLocation);
                copiedBytes+=Files.size(source);progress.update(++count,copiedBytes);files.renew(scope);
            }
            return new PreparedCopy(snapshot.toJSONString(),List.copyOf(created));
        } catch(Exception ex) { files.abandon(scope);throw new ServiceException("复制素材准备失败，请重试"); }
    }
    public void discardCopies(List<String> locations) {
        for(String location:locations) try { oss.deleteByUrl(location); } catch(Exception ignored) { /* 仅限本次新上传文件，交由保留审计追踪。 */ }
    }
    public Frozen freeze(String snapshotJson) {
        return limited(()->freezeInternal(snapshotJson));
    }
    private <T> T limited(java.util.function.Supplier<T> task) {
        if(!slots.tryAcquire()) throw new ServiceException("发布素材准备繁忙，请稍后重试");
        try { return task.get(); } finally { slots.release(); }
    }
    private Frozen freezeInternal(String snapshotJson) {
        JSONObject snapshot=JSON.parseObject(snapshotJson);
        String batch=UUID.randomUUID().toString().replace("-","");
        Path dir=root().resolve(batch);
        files.beginFreeze(batch);
        long total=0;
        try {
            Files.createDirectories(dir);
            for(Object raw:snapshot.getJSONArray("resources")) {
                JSONObject resource=(JSONObject)raw;
                String source=resource.getString("storageUrl");
                if(source!=null&&!source.startsWith("publication-file:")) {
                    String path=URI.create(source).getPath();
                    if(path!=null&&path.contains(".")) {
                        String ext=path.substring(path.lastIndexOf('.')+1);
                        if(ext.matches("[a-zA-Z0-9]{1,8}")) resource.put("fileExtension",ext);
                    }
                }
                Path staged=dir.resolve(UUID.randomUUID()+".part");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                long count=0;
                try(InputStream input=openSource(source);OutputStream output=Files.newOutputStream(staged)) {
                    byte[] buffer=new byte[65536]; int read;
                    while((read=input.read(buffer))!=-1) {
                        count+=read;total+=read;
                        if(count>maxBytes||total>maxTotalBytes) throw new IOException("publication resource capacity exceeded");
                        digest.update(buffer,0,read);output.write(buffer,0,read);
                    }
                }
                if(count==0) throw new IOException("empty resource");
                String hash=HexFormat.of().formatHex(digest.digest());
                Path target=dir.resolve(hash);
                if(Files.exists(target)) Files.delete(staged); else Files.move(staged,target);
                resource.put("storageUrl","publication-file:"+batch+"/"+hash);
                resource.put("contentHash",hash);resource.put("fileSize",count);
                files.renew(batch);
            }
            return new Frozen(snapshot.toJSONString(),batch);
        } catch(Exception ex) {
            discard(batch);throw new ServiceException("发布资源冻结失败，请检查文件完整性和容量后重试");
        }
    }
    public Path file(String token) {
        if(token==null||!token.matches("publication-file:[a-f0-9]{32}/[a-f0-9]{64}")) throw new ServiceException("发布资源不可用");
        Path file=root().resolve(token.substring("publication-file:".length())).normalize();
        if(!file.startsWith(root())||!Files.isRegularFile(file)) throw new ServiceException("发布资源不可用");
        return file;
    }
    public void discard(String batch) {
        if(batch==null||!batch.matches("[a-f0-9]{32}")||!ready()) return;
        files.abandon(batch);
    }
    private static String hashFile(Path path) throws Exception {MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}return HexFormat.of().formatHex(hash.digest());}
    private Path root() {
        if(!ready()) throw new ServiceException("当前环境未配置发布私有资源目录");
        Path result=Path.of(storageRoot).toAbsolutePath().normalize();
        Path publicPath=Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize();
        if(result.startsWith(publicPath)) throw new ServiceException("发布资源目录必须位于公开上传目录之外");
        return result;
    }
    private InputStream openSource(String source) throws IOException {
        if(source!=null&&source.startsWith("publication-file:")) return Files.newInputStream(file(source));
        if(source==null||!urls.isSiteImageUrl(source)) throw new IOException("untrusted resource origin");
        if(source.startsWith("/profile/")) {
            Path base=Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize();
            Path path=base.resolve(source.substring("/profile/".length())).normalize();
            if(!path.startsWith(base)||!Files.isRegularFile(path)) throw new IOException("local resource unavailable");
            return Files.newInputStream(path);
        }
        String full=urls.toFullUrl(source);
        if(full==null||!full.matches("https?://.*")||!urls.isSiteImageUrl(full)) throw new IOException("unsupported resource origin");
        HttpURLConnection connection=(HttpURLConnection)URI.create(full).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(30000);
        if(connection.getResponseCode()!=200) { connection.disconnect();throw new IOException("resource fetch failed"); }
        return new FilterInputStream(connection.getInputStream()) { @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } } };
    }
}
