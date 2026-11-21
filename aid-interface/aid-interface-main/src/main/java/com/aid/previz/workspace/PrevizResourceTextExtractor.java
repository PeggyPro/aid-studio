package com.aid.previz.workspace;

import com.aid.assistant.source.service.SourceDocumentParserRegistry;
import com.alibaba.fastjson2.*;
import org.jsoup.Jsoup;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Text branch of resource inspection. Visual/media analysis remains a separate explicit branch. */
public final class PrevizResourceTextExtractor {
    private static final int MAX_INPUT=10*1024*1024,MAX_OUTPUT=100*1024,MAX_PARTS=500;
    private final SourceDocumentParserRegistry parsers;
    public PrevizResourceTextExtractor(SourceDocumentParserRegistry parsers){this.parsers=Objects.requireNonNull(parsers);}
    public JSONObject extract(byte[] bytes,String type)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_INPUT)throw new IllegalArgumentException("资源大小无效");
        if(type==null)throw new IllegalArgumentException("必须指定已识别的文本格式");
        JSONArray parts=new JSONArray();boolean truncated=false;int remaining=MAX_OUTPUT-1024;
        Map<Integer,JSONObject> locations=new HashMap<>();List<String> text;
        if(Set.of("pdf","docx","md").contains(type)){
            var parsed=parsers.require("resource."+type,null,bytes).parse(bytes,Set.of());
            text=parsed.stream().map(p->p.content()).toList();
            for(int i=0;i<parsed.size();i++){var p=parsed.get(i);locations.put(i,JSONObject.of("sourcePartIndex",p.partIndex(),"locator",JSON.parseObject(p.locatorJson())));}
        }else text=switch(type){
            case "html" -> {String html=utf8(bytes);var document=Jsoup.parse(html);document.select("script,style,template").remove();yield List.of(document.title(),document.body().wholeText());}
            case "doc" -> {try(var document=new HWPFDocument(new ByteArrayInputStream(bytes));var extractor=new WordExtractor(document)){yield List.of(extractor.getText());}}
            case "pptx" -> powerpointXml(bytes);
            case "ppt" -> powerpointLegacy(bytes);
            default -> throw new IllegalArgumentException("此分支仅处理文本类资源");
        };
        int index=0;
        for(String value:text){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("资源解析已取消");
            if(index>=MAX_PARTS){truncated=true;break;}
            JSONObject part=JSONObject.of("part",index,"text",value);if(locations.containsKey(index))part.putAll(locations.get(index));index++;byte[] encoded=JSON.toJSONBytes(part);
            if(encoded.length>remaining){
                int low=0,high=value.length();while(low<high){int middle=(low+high+1)/2;part.put("text",prefix(value,middle));if(JSON.toJSONBytes(part).length<=remaining)low=middle;else high=middle-1;}
                part.put("text",prefix(value,low));if(!part.getString("text").isEmpty())parts.add(part);truncated=true;break;
            }
            parts.add(part);remaining-=encoded.length+1;
        }
        return JSONObject.of("contentType",type,"parts",parts,"partCount",text.size(),"truncated",truncated,"sourceBytes",bytes.length,"sourceSha256",PrevizFileContentStore.digest(bytes),"analysisRequired",true);
    }
    private static String prefix(String value,int end){if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
    private static String utf8(byte[] bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    private static List<String> powerpointXml(byte[] bytes)throws IOException{
        auditPresentationArchive(bytes);
        try(var show=new XMLSlideShow(new ByteArrayInputStream(bytes))){
            if(show.getSlides().size()>MAX_PARTS)throw new IllegalArgumentException("幻灯片超过解析上限");List<String> result=new ArrayList<>();
            int total=0;for(var slide:show.getSlides()){StringBuilder text=new StringBuilder();appendXml(slide.getShapes(),text,0);if(slide.getNotes()!=null)appendXml(slide.getNotes().getShapes(),text,0);total+=text.length();if(total>5_000_000)throw new IOException("文档文本超限");result.add(text.toString());}return result;
        }
    }
    private static void appendXml(List<XSLFShape> shapes,StringBuilder text,int depth)throws IOException{
        if(depth>16||shapes.size()>10000)throw new IOException("幻灯片结构超限");
        for(var shape:shapes){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            if(shape instanceof XSLFTextShape value)text.append(value.getText()).append('\n');
            else if(shape instanceof XSLFTable table)for(var row:table.getRows())for(var cell:row.getCells())text.append(cell.getText()).append('\t');
            else if(shape instanceof XSLFGroupShape group)appendXml(group.getShapes(),text,depth+1);
            if(text.length()>1_000_000)throw new IOException("幻灯片文本超限");
        }
    }
    private static List<String> powerpointLegacy(byte[] bytes)throws IOException{
        try(var show=new HSLFSlideShow(new ByteArrayInputStream(bytes))){
            if(show.getSlides().size()>MAX_PARTS)throw new IllegalArgumentException("幻灯片超过解析上限");List<String> result=new ArrayList<>();
            int total=0;for(var slide:show.getSlides()){StringBuilder text=new StringBuilder();for(var paragraphs:slide.getTextParagraphs()){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();text.append(HSLFTextParagraph.getText(paragraphs)).append('\n');if(text.length()>1_000_000)throw new IOException("幻灯片文本超限");}if(slide.getNotes()!=null)for(var paragraphs:slide.getNotes().getTextParagraphs()){text.append(HSLFTextParagraph.getText(paragraphs)).append('\n');if(text.length()>1_000_000)throw new IOException("幻灯片文本超限");}total+=text.length();if(total>5_000_000)throw new IOException("文档文本超限");result.add(text.toString());}return result;
        }
    }
    private static void auditPresentationArchive(byte[] bytes)throws IOException{
        Set<String> paths=new HashSet<>();long expanded=0;byte[] buffer=new byte[8192];
        try(var input=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){
            for(java.util.zip.ZipEntry entry;(entry=input.getNextEntry())!=null;){
                String name=entry.getName();if(paths.size()>=10000||!paths.add(name)||name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.asList(name.split("/")).contains(".."))throw new IOException("压缩文档目录无效");
                for(int count;(count=input.read(buffer))>=0;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();expanded+=count;if(expanded>100L*1024*1024)throw new IOException("压缩文档解包容量超限");}
            }
        }
        if(!paths.contains("ppt/presentation.xml"))throw new IOException("PPTX内容标识缺失");
    }
}
