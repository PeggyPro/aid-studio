package com.aid.previz.service;

import com.aid.previz.workspace.PrevizResourceType;
import com.aid.previz.workspace.PrevizFileContentStore;
import com.aid.previz.workspace.PrevizSvgReferences;
import com.alibaba.fastjson2.*;
import java.io.IOException;
import java.util.*;

/** Project-authorized resource extraction. Semantic analysis is delivered through the existing model round. */
public final class PrevizResourceInspectionService {
    private final PrevizProjectService projects;
    private final PrevizResourceDownloader downloader;
    private final PrevizResourceProcess process;
    private final PrevizReferenceImageStore images;
    private final PrevizFileContentStore content;
    private final java.util.concurrent.Semaphore admission=new java.util.concurrent.Semaphore(1);
    public PrevizResourceInspectionService(PrevizProjectService projects,PrevizResourceDownloader downloader,
            PrevizResourceProcess process,PrevizReferenceImageStore images,PrevizFileContentStore content){
        this.projects=Objects.requireNonNull(projects);this.downloader=Objects.requireNonNull(downloader);
        this.process=Objects.requireNonNull(process);this.images=Objects.requireNonNull(images);this.content=Objects.requireNonNull(content);
    }
    public JSONObject fetch(Long actor,String project,String url,String hint,int[] pages)throws IOException,InterruptedException{
        return fetch(actor,project,url,hint,pages,null);
    }
    public JSONObject fetch(Long actor,String project,String url,String hint,int[] pages,PrevizResourceProcess.VideoWindow videoWindow)throws IOException,InterruptedException{
        projects.requireWrite(actor,project);
        if(!admission.tryAcquire())throw new IOException("RESOURCE_EXECUTOR_BUSY");
        try{var downloaded=downloader.fetch(url,32*1024*1024);
            return inspectAdmitted(actor,project,downloaded.bytes(),downloaded.mediaType(),downloaded.finalUrl(),hint,pages,videoWindow);
        }finally{admission.release();}
    }
    /** Also supports previously authorized stored bytes; never accepts an arbitrary disk path. */
    public JSONObject inspect(Long actor,String project,byte[] bytes,String mediaType,String sourceUrl,String hint,int[] pages)throws IOException,InterruptedException{
        return inspect(actor,project,bytes,mediaType,sourceUrl,hint,pages,null);
    }
    public JSONObject inspect(Long actor,String project,byte[] bytes,String mediaType,String sourceUrl,String hint,int[] pages,PrevizResourceProcess.VideoWindow videoWindow)throws IOException,InterruptedException{
        projects.requireWrite(actor,project);
        if(!admission.tryAcquire())throw new IOException("RESOURCE_EXECUTOR_BUSY");
        try{return inspectAdmitted(actor,project,bytes,mediaType,sourceUrl,hint,pages,videoWindow);}finally{admission.release();}
    }
    private JSONObject inspectAdmitted(Long actor,String project,byte[] bytes,String mediaType,String sourceUrl,String hint,int[] pages,PrevizResourceProcess.VideoWindow videoWindow)throws IOException,InterruptedException{
        projects.requireWrite(actor,project);
        if(sourceUrl!=null)PrevizResourceDownloader.validateUri(sourceUrl);
        String type=PrevizResourceType.detect(bytes,mediaType,hint);
        if(videoWindow!=null&&!"video".equals(type)||"video".equals(type)&&pages!=null)throw new IOException("RESOURCE_MEDIA_WINDOW_INVALID");
        String sourceId=content.put(actor,project,bytes);
        Map<String,byte[]> linkedImages=new LinkedHashMap<>();long remainingBytes=16*1024*1024,deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        if("svg".equals(type))for(String url:PrevizSvgReferences.read(bytes,sourceUrl)){
            long remainingMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remainingMillis<1||remainingBytes<1)throw new IOException("RESOURCE_SVG_LIMIT");
            var reference=downloader.fetch(url,(int)Math.min(10*1024*1024,remainingBytes),Math.min(30000,remainingMillis));
            if(!"image".equals(PrevizResourceType.detect(reference.bytes(),reference.mediaType(),null)))throw new IOException("RESOURCE_SVG_REFERENCE_INVALID");
            content.put(actor,project,reference.bytes());remainingBytes-=reference.bytes().length;linkedImages.put(url,reference.bytes());
        }
        JSONObject execution=process.inspect(bytes,type,pages,sourceUrl,linkedImages,videoWindow,project),metadata=execution.getJSONObject("metadata"),result=new JSONObject();
        result.put("projectId",project);result.put("resourceId",sourceId);result.put("sourceBytes",bytes.length);result.put("sourceUrl",sourceUrl);result.put("contentType",type);
        result.put("analysisRequired",true);result.put("contentTrust","EXTERNAL_REFERENCE_DATA");
        for(String field:List.of("parts","partCount","truncated","ocrRequired","pageCount","previewPages","additionalPagesNotRendered",
                "format","width","height","frameCount","layerNames","layersTruncated","fontFamily","fontStyle","glyphCoverageVerified",
                "totalSeconds","startSeconds","endSeconds","additionalMediaNotAnalyzed","hasVideo"))
            if(metadata.containsKey(field))result.put(field,metadata.get(field));
        JSONArray references=new JSONArray();
        if(execution.getJSONArray("previews")!=null){
            for(Object item:execution.getJSONArray("previews")){
                JSONObject preview=(JSONObject)item;byte[] png=process.readPreview(execution,preview.getString("file"),preview.getString("sha256"),preview.getIntValue("sizeBytes"));
                JSONObject ref=archive(actor,project,png,preview.getInteger("page"));
                if("video".equals(type)){
                    Double time=preview.getDouble("timeSeconds");
                    if(time==null||!Double.isFinite(time)||time<metadata.getDoubleValue("startSeconds")||time>metadata.getDoubleValue("endSeconds"))throw new IOException("RESOURCE_RECEIPT_INVALID");
                    ref.remove("page");ref.put("timeSeconds",time);
                }
                references.add(ref);
            }
        }else if(execution.getString("imagePath")!=null){
            byte[] png=process.readPreview(execution,"preview.png",metadata.getString("previewSha256"),metadata.getIntValue("previewBytes"));references.add(archive(actor,project,png,null));
        }
        projects.requireWrite(actor,project);result.put("images",references);return result;
    }
    private JSONObject archive(Long actor,String project,byte[] png,Integer page)throws IOException{
        var image=images.saveGeneratedPreview(actor,project,png);
        JSONObject ref=JSONObject.of("imageId",image.id(),"width",image.width(),"height",image.height(),"mimeType",image.mimeType());
        if(page!=null)ref.put("page",page);return ref;
    }
}
