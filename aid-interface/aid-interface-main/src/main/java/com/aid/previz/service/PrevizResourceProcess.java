package com.aid.previz.service;

import com.aid.previz.workspace.PrevizFileContentStore;
import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Operator-configured local resource process. The caller must authorize the project before use. */
public final class PrevizResourceProcess {
    private final Path node,entry,profile,intake,execution,requests;
    private final Semaphore capacity=new Semaphore(1);
    public PrevizResourceProcess(Path node,Path entry,Path profile,Path requests)throws IOException{
        this.node=existing(node);this.entry=existing(entry);this.profile=existing(profile);
        if(Files.size(profile)>65536)throw new IOException("RESOURCE_CONFIG_INVALID");
        JSONObject config=JSON.parseObject(Files.readString(profile));
        if(!Boolean.TRUE.equals(config.getBoolean("enabled"))||!Set.of("LOCAL_DEVELOPMENT_PROCESS","REMOTE_COMPUTE").contains(String.valueOf(config.getString("mode"))))throw new IOException("LOCAL_EXECUTION_DISABLED");
        this.intake=directory(Path.of(config.getString("intakeRoot")));this.execution=directory(Path.of(config.getString("executionRoot")));this.requests=directory(requests);
    }
    public JSONObject inspect(byte[] bytes,String format,int[] pages)throws IOException,InterruptedException{
        return inspect(bytes,format,pages,null,Map.of());
    }
    public JSONObject inspect(byte[] bytes,String format,int[] pages,String baseUrl,Map<String,byte[]> references)throws IOException,InterruptedException{
        return inspect(bytes,format,pages,baseUrl,references,null);
    }
    public record VideoWindow(double startSeconds,double durationSeconds,int frameCount){
        public VideoWindow {
            if(!Double.isFinite(startSeconds)||startSeconds<0||!Double.isFinite(durationSeconds)||durationSeconds<=0||durationSeconds>60||frameCount<1||frameCount>8)
                throw new IllegalArgumentException("RESOURCE_MEDIA_WINDOW_INVALID");
        }
    }
    public JSONObject inspect(byte[] bytes,String format,int[] pages,String baseUrl,Map<String,byte[]> references,VideoWindow videoWindow)throws IOException,InterruptedException{
        return inspect(bytes,format,pages,baseUrl,references,videoWindow,null);
    }
    public JSONObject inspect(byte[] bytes,String format,int[] pages,String baseUrl,Map<String,byte[]> references,VideoWindow videoWindow,String projectId)throws IOException,InterruptedException{
        if(bytes==null||bytes.length==0||bytes.length>32*1024*1024)throw new IOException("RESOURCE_INPUT_INVALID");
        if(videoWindow!=null&&!"video".equals(format)||"video".equals(format)&&pages!=null)throw new IOException("RESOURCE_MEDIA_WINDOW_INVALID");
        if(references==null||references.size()>16||!references.isEmpty()&&!"svg".equals(format))throw new IOException("RESOURCE_REFERENCE_INVALID");
        if(!capacity.tryAcquire())throw new IOException("RESOURCE_EXECUTOR_BUSY");
        try{
            String sha=storeInput(bytes);
            Path folder=Files.createTempDirectory(requests,"resource-"),request=folder.resolve("request.json"),log=folder.resolve("result.json");
            JSONObject body=JSONObject.of("inputId",sha,"sha256",sha,"format",format);if(pages!=null)body.put("pages",pages.clone());
            if(projectId!=null)body.put("projectId",projectId);
            if(videoWindow!=null)body.put("videoWindow",JSONObject.of("startSeconds",videoWindow.startSeconds(),"durationSeconds",videoWindow.durationSeconds(),"frameCount",videoWindow.frameCount()));
            if(baseUrl!=null)body.put("baseUrl",baseUrl);JSONArray refs=new JSONArray();long total=0;
            for(var ref:references.entrySet()){
                byte[] value=ref.getValue();if(ref.getKey()==null||ref.getKey().length()>8192||value==null||value.length<1||value.length>10*1024*1024||(total+=value.length)>16*1024*1024)throw new IOException("RESOURCE_REFERENCE_INVALID");
                refs.add(JSONObject.of("url",ref.getKey(),"sha256",storeInput(value)));
            }
            if(!refs.isEmpty())body.put("references",refs);
            Files.writeString(request,body.toJSONString(),StandardOpenOption.CREATE_NEW);
            ProcessBuilder builder=new ProcessBuilder(node.toString(),"--max-old-space-size=256",entry.toString(),profile.toString(),request.toString());builder.directory(folder.toFile());
            builder.environment().keySet().removeIf(key->!Set.of("SYSTEMROOT","WINDIR","PATH","PATHEXT").contains(key.toUpperCase(Locale.ROOT)));
            builder.environment().put("TEMP",folder.toString());builder.environment().put("TMP",folder.toString());builder.environment().put("TMPDIR",folder.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());Process process=builder.start();
            try{if(!process.waitFor(60,TimeUnit.SECONDS)){stop(process);throw new IOException("RESOURCE_EXECUTION_TIMEOUT");}}
            catch(InterruptedException cancelled){stop(process);Thread.currentThread().interrupt();throw cancelled;}
            if(Files.size(log)>512*1024)throw new IOException("RESOURCE_RECEIPT_INVALID");
            JSONObject result;try{result=JSON.parseObject(Files.readString(log).strip());}catch(RuntimeException invalid){throw new IOException("RESOURCE_RECEIPT_INVALID");}
            if(process.exitValue()!=0||!"result".equals(result.getString("type"))){String code=result.getString("code");throw new IOException(code!=null&&code.matches("RESOURCE_[A-Z_]+|LOCAL_EXECUTION_DISABLED|CANCELLED")?code:"RESOURCE_EXECUTION_FAILED");}
            JSONObject metadata=result.getJSONObject("metadata");if(metadata==null||!sha.equals(metadata.getString("sourceSha256"))||metadata.getIntValue("sourceBytes")!=bytes.length)throw new IOException("RESOURCE_RECEIPT_INVALID");
            Path output=Path.of(result.getString("outputDirectory"));if(Files.isSymbolicLink(output)||!output.toRealPath().startsWith(execution)||output.toRealPath().equals(execution))throw new IOException("RESOURCE_RECEIPT_INVALID");
            result.put("outputDirectory",output.toRealPath().toString());return result;
        }finally{capacity.release();}
    }
    private String storeInput(byte[] bytes)throws IOException{
        String sha=PrevizFileContentStore.digest(bytes);Path input=intake.resolve(sha+".bin");
        try{Files.write(input,bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException duplicate){
            if(Files.isSymbolicLink(input)||!Files.isRegularFile(input)||Files.size(input)!=bytes.length||!PrevizFileContentStore.digest(Files.readAllBytes(input)).equals(sha))throw new IOException("RESOURCE_INPUT_CHANGED");
        }return sha;
    }
    public byte[] readPreview(JSONObject receipt,String file,String sha,int size)throws IOException{
        Path root=Path.of(receipt.getString("outputDirectory")).toRealPath();
        if(!root.startsWith(execution)||root.equals(execution)||file==null||!file.matches("preview\\.png|page-[0-9]{1,3}\\.png")||size<1||size>16*1024*1024)throw new IOException("RESOURCE_RECEIPT_INVALID");
        Path image=root.resolve(file);if(Files.isSymbolicLink(image)||!Files.isRegularFile(image)||Files.size(image)!=size)throw new IOException("RESOURCE_RECEIPT_INVALID");
        byte[] bytes=Files.readAllBytes(image);if(!PrevizFileContentStore.digest(bytes).equals(sha))throw new IOException("RESOURCE_RECEIPT_INVALID");return bytes;
    }
    private static void stop(Process process){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    private static Path existing(Path path)throws IOException{if(path==null||!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new IOException("RESOURCE_CONFIG_INVALID");return path.toRealPath();}
    private static Path directory(Path path)throws IOException{if(path==null||!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isDirectory(path))throw new IOException("RESOURCE_DIRECTORY_INVALID");return path.toRealPath();}
}
