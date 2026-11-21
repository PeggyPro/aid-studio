package com.aid.previz.workspace;

import com.aid.assistant.source.parser.*;
import com.aid.assistant.source.service.SourceDocumentParserRegistry;
import com.aid.assistant.error.AssistantServiceException;
import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;

/** Fixed document decoder entrypoint for a memory-limited child JVM. Never starts the application. */
public final class PrevizResourceDocumentMain {
    public static void main(String[] args){
        try{if(args.length<2||args.length>3)throw new IOException("RESOURCE_JOB_INVALID");JSONObject result=inspect(Path.of(args[0]),args[1],args.length==3?parsePages(args[2]):null);System.out.println(JSONObject.of("ok",true,"sourceSha256",result.getString("sourceSha256")).toJSONString());}
        catch(Exception failure){String code=failure instanceof AssistantServiceException known?known.getErrorCode():Objects.toString(failure.getMessage(),"");System.out.println(JSONObject.of("error",code.matches("[A-Z][A-Z_]{1,80}")?code:"RESOURCE_DOCUMENT_DECODE_FAILED").toJSONString());System.exit(1);}
    }
    public static JSONObject inspect(Path job,String type)throws Exception{
        return inspect(job,type,null);
    }
    public static JSONObject inspect(Path job,String type,int[] requestedPages)throws Exception{
        if(requestedPages!=null){requestedPages=requestedPages.clone();validatePages(requestedPages,500);}
        if(!job.isAbsolute()||Files.isSymbolicLink(job)||!Files.isDirectory(job))throw new IOException("RESOURCE_JOB_INVALID");Path root=job.toRealPath(),input=root.resolve("input.bin"),output=root.resolve("inspection.json");
        System.setProperty("java.io.tmpdir",root.toString());javax.imageio.ImageIO.setCacheDirectory(root.toFile());
        if(Files.isSymbolicLink(input)||!Files.isRegularFile(input)||!input.toRealPath().getParent().equals(root)||Files.size(input)>10*1024*1024||Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("RESOURCE_INPUT_INVALID");
        if(!Set.of("pdf","docx","doc","pptx","ppt","html","md").contains(type))throw new IOException("RESOURCE_DOCUMENT_TYPE_INVALID");
        byte[] bytes=Files.readAllBytes(input);String actual=PrevizResourceType.detect(bytes,null,type);if(!type.equals(actual))throw new IOException("RESOURCE_TYPE_MISMATCH");
        var extractor=new PrevizResourceTextExtractor(new SourceDocumentParserRegistry(java.util.List.of(new PdfSourceDocumentParser(500),new DocxSourceDocumentParser(),new Utf8TextSourceDocumentParser())));JSONObject result;
        try{result=extractor.extract(bytes,type);}catch(AssistantServiceException empty){if(!"pdf".equals(type)||!"SOURCE_PDF_OCR_REQUIRED".equals(empty.getErrorCode()))throw empty;result=JSONObject.of("contentType",type,"parts",new JSONArray(),"truncated",false,"ocrRequired",true,"sourceBytes",bytes.length,"sourceSha256",PrevizFileContentStore.digest(bytes),"analysisRequired",true);}
        JSONArray previews=new JSONArray();
        if(type.equals("pdf"))try(var document=Loader.loadPDF(bytes)){
            if(document.isEncrypted()||!document.getCurrentAccessPermission().canExtractContent())throw new IOException("RESOURCE_DOCUMENT_ENCRYPTED");
            int pages=document.getNumberOfPages();if(pages<1||pages>500)throw new IOException("RESOURCE_DOCUMENT_PAGE_LIMIT");result.put("pageCount",pages);
            int[] selected=selectedPages(requestedPages,pages);
            var renderer=new PDFRenderer(document);renderer.setSubsamplingAllowed(true);
            for(int page:selected){var box=document.getPage(page).getCropBox();float side=Math.max(box.getWidth(),box.getHeight());if(!Float.isFinite(side)||side<=0)throw new IOException("RESOURCE_DOCUMENT_PAGE_INVALID");previews.add(save(root,renderer.renderImage(page,Math.min(2f,2048f/side),ImageType.RGB),page));}
        }
        if(type.equals("pptx"))try(var show=new XMLSlideShow(new ByteArrayInputStream(bytes))){for(int page:selectedPages(requestedPages,show.getSlides().size()))previews.add(slide(root,show.getPageSize(),page,graphics->show.getSlides().get(page).draw(graphics)));result.put("pageCount",show.getSlides().size());}
        if(type.equals("ppt"))try(var show=new HSLFSlideShow(new ByteArrayInputStream(bytes))){for(int page:selectedPages(requestedPages,show.getSlides().size()))previews.add(slide(root,show.getPageSize(),page,graphics->show.getSlides().get(page).draw(graphics)));result.put("pageCount",show.getSlides().size());}
        if(requestedPages!=null&&!Set.of("pdf","pptx","ppt").contains(type))throw new IOException("RESOURCE_DOCUMENT_NOT_PAGINATED");
        JSONArray renderedPages=new JSONArray();for(Object preview:previews)renderedPages.add(((JSONObject)preview).getIntValue("page"));
        result.put("previews",previews);result.put("previewPages",renderedPages);result.put("additionalPagesNotRendered",result.getIntValue("pageCount")>previews.size());
        Files.writeString(output,result.toJSONString(),StandardOpenOption.CREATE_NEW);return result;
    }
    private interface Draw{void draw(Graphics2D graphics);}
    private static int[] parsePages(String value)throws IOException{
        if(value.length()>40||!value.matches("[0-9]{1,3}(,[0-9]{1,3}){0,7}"))throw new IOException("RESOURCE_DOCUMENT_PAGE_SELECTION_INVALID");
        int[] pages=Arrays.stream(value.split(",")).mapToInt(Integer::parseInt).toArray();validatePages(pages,500);return pages;
    }
    private static void validatePages(int[] pages,int count)throws IOException{
        if(pages.length<1||pages.length>8)throw new IOException("RESOURCE_DOCUMENT_PAGE_SELECTION_INVALID");
        Set<Integer> seen=new HashSet<>();for(int page:pages)if(page<0||page>=count||!seen.add(page))throw new IOException("RESOURCE_DOCUMENT_PAGE_SELECTION_INVALID");
    }
    private static int[] selectedPages(int[] requested,int count)throws IOException{
        if(count<0||count>500)throw new IOException("RESOURCE_DOCUMENT_PAGE_LIMIT");
        if(requested!=null){validatePages(requested,count);return requested;}
        return count==0?new int[0]:new int[]{0};
    }
    private static JSONObject slide(Path root,Dimension size,int page,Draw draw)throws IOException{
        if(size.width<1||size.height<1)throw new IOException("RESOURCE_DOCUMENT_PAGE_INVALID");double scale=Math.min(2,2048.0/Math.max(size.width,size.height));int width=Math.max(1,(int)Math.ceil(size.width*scale)),height=Math.max(1,(int)Math.ceil(size.height*scale));
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);graphics.scale(scale,scale);draw.draw(graphics);}finally{graphics.dispose();}return save(root,image,page);
    }
    private static JSONObject save(Path root,BufferedImage image,int page)throws IOException{
        if(image.getWidth()<1||image.getHeight()<1||image.getWidth()>2048||image.getHeight()>2048)throw new IOException("RESOURCE_PREVIEW_DIMENSIONS");
        Path target=root.resolve("page-"+page+".png");try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){if(!javax.imageio.ImageIO.write(image,"png",output))throw new IOException("RESOURCE_PNG_WRITER_UNAVAILABLE");}finally{image.flush();}
        if(Files.size(target)>16*1024*1024)throw new IOException("RESOURCE_PREVIEW_LIMIT");byte[] bytes=Files.readAllBytes(target);return JSONObject.of("file",target.getFileName().toString(),"page",page,"sha256",PrevizFileContentStore.digest(bytes),"sizeBytes",bytes.length);
    }
}
