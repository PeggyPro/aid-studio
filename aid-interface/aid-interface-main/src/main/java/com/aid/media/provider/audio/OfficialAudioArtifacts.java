package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.common.oss.factory.OssFactory;
import com.aid.media.provider.AudioOutputItem;
import com.aid.media.service.VerifiedMediaMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 二进制只在内存中解析并写对象存储，绝不回填任务JSON。 */
@Component
@RequiredArgsConstructor
public final class OfficialAudioArtifacts {
    private final VerifiedMediaMetadataService metadata;
    private final com.aid.common.aid.oss.util.MediaUrlResolver urls;

    public AudioOutputItem store(byte[] bytes,String format,String role,int variant,Map<String,Object> details) {
        if(bytes==null||bytes.length==0||bytes.length>OfficialAudioTransport.MAX_RESPONSE_BYTES)throw new ServiceException("音频产物为空或过大");
        format=format==null?"mp3":format.toLowerCase(Locale.ROOT);
        if(format.equals("ogg_opus"))format="ogg";
        if(!format.matches("mp3|wav|pcm|m4a|flac|ogg|opus|aac|ulaw|alaw|mp4|webm|jpg|jpeg|png|webp|gif|zip|json|xml|pls|srt|vtt|txt"))throw new ServiceException("音频产物格式未支持");
        String mime=switch(format){case "mp3"->"audio/mpeg";case "m4a"->"audio/mp4";case "mp4","webm"->"video/"+format;case "jpg","jpeg"->"image/jpeg";case "png","webp","gif"->"image/"+format;case "zip"->"application/zip";case "json"->"application/json";case "xml","pls"->"application/xml";case "srt","vtt","txt"->"text/plain";default->"audio/"+format;};
        AudioOutputItem result=new AudioOutputItem();
        result.setRole(role);result.setVariantIndex(variant);result.setFormat(format);result.setContentType(mime);
        if(Set.of("mp4","webm").contains(format)) result.setMediaType("VIDEO");
        if(Set.of("jpg","jpeg","png","webp","gif").contains(format))result.setMediaType("IMAGE");
        if(Set.of("zip","json","xml","pls","srt","vtt","txt").contains(format))result.setMediaType("FILE");
        result.setSizeBytes((long)bytes.length);result.setMetadata(details);result.setPersisted(true);
        if(!java.util.Set.of("pcm","ulaw","alaw","zip","json","xml","pls","srt","vtt","txt").contains(format)) {
            String kind="IMAGE".equals(result.getMediaType())?"image":"VIDEO".equals(result.getMediaType())?"video":"audio";
            var actual=metadata.inspectUpload(new InMemoryFile(bytes,"output."+format,mime),kind);
            if(!kind.equals("image")&&actual.durationSeconds().signum()<=0)throw new ServiceException("音频产物没有有效时长");
            if(!kind.equals("image"))result.setDurationMs(actual.durationSeconds().movePointRight(3).longValue());
        }
        try{result.setUrl(OssFactory.instance().uploadSuffix(bytes,"."+format,mime).getUrl());}
        catch(IOException ex){throw new ServiceException("音频产物保存失败");}
        if(result.getUrl()==null||result.getUrl().isBlank())throw new ServiceException("音频产物保存失败");
        return result;
    }

    public AudioOutputItem remote(String url,String role,int variant,Long durationMs,Map<String,Object> details) {
        java.net.URI uri;
        try{uri=java.net.URI.create(url);}catch(Exception ex){throw new ServiceException("音频产物地址无效");}
        if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new ServiceException("音频产物地址无效");
        AudioOutputItem result=new AudioOutputItem();result.setUrl(url);result.setRole(role);result.setVariantIndex(variant);
        result.setDurationMs(durationMs);result.setPersisted(false);result.setMetadata(details);
        String p=uri.getPath();String ext=p!=null&&p.lastIndexOf('.')>=0?p.substring(p.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(ext.matches("mp3|wav|pcm|m4a|flac|ogg|opus|aac|mp4|webm|zip"))result.setFormat(ext);
        return result;
    }

    /** 每一个变体和附属产物都转存，不让短期签名URL成为唯一副本。 */
    public void persist(AudioOutputItem item) {
        if(Boolean.TRUE.equals(item.getPersisted()))return;
        String format=item.getFormat();
        byte[] bytes=com.aid.tokendance.security.TokenDancePublicMediaDownloader.download(item.getUrl(),10000,180000,MAX_ARTIFACT_BYTES);
        if(format==null||format.isBlank()) {
            var actual=metadata.inspectUpload(new InMemoryFile(bytes,"output.bin","application/octet-stream"),"VIDEO".equals(item.getMediaType())?"video":"audio");
            format=actual.format();
        }
        AudioOutputItem stored=store(bytes,format,item.getRole(),item.getVariantIndex(),item.getMetadata());
        item.setFormat(format);
        item.setUrl(stored.getUrl());item.setPersisted(true);item.setSizeBytes(stored.getSizeBytes());
        item.setMediaType(stored.getMediaType());
        if(item.getDurationMs()==null)item.setDurationMs(stored.getDurationMs());
        if(item.getContentType()==null)item.setContentType(stored.getContentType());
    }
    private static final int MAX_ARTIFACT_BYTES=320*1024*1024;

    public String stageReference(String encoded,boolean image) {
        if(encoded==null||encoded.length()>14*1024*1024)throw new ServiceException("参考素材体积超过10MB");
        byte[] bytes;
        try { bytes=java.util.Base64.getDecoder().decode(encoded); }
        catch(IllegalArgumentException invalid){throw new ServiceException("参考素材Base64无效");}
        if(bytes.length==0||bytes.length>10*1024*1024||image&&bytes.length<1024)throw new ServiceException("参考素材体积不符合要求");
        var actual=metadata.inspectUpload(new InMemoryFile(bytes,"reference.bin","application/octet-stream"),image?"image":"audio");
        String format=actual.format();
        if(image?!Set.of("jpg","jpeg","png","webp").contains(format):!Set.of("wav","mp3","ogg","opus","ogg_opus").contains(format)||actual.durationSeconds().compareTo(java.math.BigDecimal.valueOf(30))>0)
            throw new ServiceException("参考素材格式或时长不符合要求");
        try { return urls.toFullUrl(OssFactory.instance().uploadSuffix(bytes,"."+format,image?"image/"+format:"audio/"+format).getUrl()); }
        catch(IOException ex){throw new ServiceException("参考素材保存失败");}
    }

    public String stageMurekaDataUrl(String value) {
        if(value==null||value.length()>14*1024*1024||!value.matches("^data:audio/(mp3|mpeg|mp4|m4a|x-m4a);base64,[A-Za-z0-9+/=]+$"))throw new ServiceException("分轨音频Data URL无效或过大");
        byte[] bytes;
        try{bytes=java.util.Base64.getDecoder().decode(value.substring(value.indexOf(',')+1));}catch(Exception invalid){throw new ServiceException("分轨音频Base64无效");}
        if(bytes.length==0||bytes.length>10*1024*1024)throw new ServiceException("分轨内嵌音频不得超过10MB");
        var actual=metadata.inspectUpload(new InMemoryFile(bytes,"input.bin","application/octet-stream"),"audio");
        if(!Set.of("mp3","m4a").contains(actual.format()))throw new ServiceException("分轨音频格式仅支持mp3或m4a");
        var output=store(bytes,actual.format(),"reference",0,Map.of());return urls.toFullUrl(output.getUrl());
    }

    public void ensureMetadata(AudioOutputItem item) {
        if(!Set.of("AUDIO","VIDEO").contains(item.getMediaType()))return;
        if(item.getDurationMs()!=null&&item.getDurationMs()>0)return;
        if(Set.of("zip","json","srt","vtt","pcm","ulaw","alaw").contains(item.getFormat()==null?"":item.getFormat()))return;
        var actual=metadata.inspect(item.getUrl(),"VIDEO".equals(item.getMediaType())?"video":"audio");
        item.setDurationMs(actual.durationSeconds().movePointRight(3).longValue());
        item.setSizeBytes(actual.sizeBytes());
        if(item.getFormat()==null)item.setFormat(actual.format());
    }

    private record InMemoryFile(byte[] data,String name,String contentType) implements MultipartFile {
        public String getName(){return "file";}
        public String getOriginalFilename(){return name;}
        public String getContentType(){return contentType;}
        public boolean isEmpty(){return data.length==0;}
        public long getSize(){return data.length;}
        public byte[] getBytes(){return data;}
        public InputStream getInputStream(){return new ByteArrayInputStream(data);}
        public void transferTo(File file)throws IOException{Files.write(file.toPath(),data);}
    }
}
