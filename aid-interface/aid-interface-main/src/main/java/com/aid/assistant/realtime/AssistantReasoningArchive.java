package com.aid.assistant.realtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Private, bounded file archive. Only the origin publisher writes; no database text storage. */
@Component
public final class AssistantReasoningArchive implements AutoCloseable {
    private static final int MAX_ACTIVE = 128, MAX_BUFFER_CHARS = 65536;
    private final Path root;
    private final long maxFileBytes;
    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"assistant-reasoning-archive"); thread.setDaemon(true); return thread;
    });

    public AssistantReasoningArchive(
            @Value("${assistant.reasoning-archive.directory:${user.home}/.aid-private/reasoning}") String directory,
            @Value("${assistant.reasoning-archive.max-file-bytes:16777216}") long maxFileBytes) {
        root=Path.of(directory).toAbsolutePath().normalize();
        this.maxFileBytes=Math.max(65536,maxFileBytes);
        writer.scheduleWithFixedDelay(this::flush,250,250,TimeUnit.MILLISECONDS);
    }

    public record Chunk(long sessionId,long runId,long stepId,String streamId,Long innerStepId,
                        Long skillRunId,String content,String reasoningKind,boolean ended) { }

    public void accept(Chunk signal) {
        if(signal==null || signal.sessionId()<=0 || signal.runId()<=0 || signal.stepId()<=0
                || signal.streamId()==null || signal.streamId().length()>256)return;
        boolean delta=!signal.ended(),ended=signal.ended();
        if(delta && (signal.reasoningKind()==null || !Set.of("PUBLIC_SUMMARY","PROVIDER_REASONING").contains(signal.reasoningKind())))return;
        Key key=new Key(signal.sessionId(),signal.runId(),signal.stepId(),digest(signal.streamId()));
        if(!pending.containsKey(key) && pending.size()>=MAX_ACTIVE)return;
        Pending state=pending.computeIfAbsent(key,ignored->new Pending(signal.innerStepId(),signal.skillRunId()));
        synchronized(state){
            if(state.ended)return;
            String content=delta?signal.content():null;
            if(content!=null){
                int remaining=MAX_BUFFER_CHARS-state.text.length();
                if(content.length()>remaining)state.partial=true;
                if(remaining>0)state.text.append(content,0,Math.min(content.length(),remaining));
            }
            if(ended)state.ended=true;
        }
    }

    public synchronized void flush() {
        for(var entry:pending.entrySet()) {
            Key key=entry.getKey();Pending state=entry.getValue();
            String content;boolean ended,partial;
            synchronized(state) {
                content=state.text.toString();state.text.setLength(0);ended=state.ended;partial=state.partial;
            }
            try {
                if(content.isEmpty()&&!ended)continue;
                Path file=file(key,true);
                if(!content.isEmpty()) {
                    JSONObject chunk=JSONObject.of("content",content,"innerStepId",Objects.toString(state.innerStepId,null),"skillRunId",Objects.toString(state.skillRunId,null));
                    byte[] bytes=(chunk.toJSONString()+"\n").getBytes(StandardCharsets.UTF_8);
                    long size=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?Files.size(file):0;
                    if(size==0) {
                        Files.writeString(file,JSONObject.of("header",true,"createdAt",System.currentTimeMillis(),"innerStepId",Objects.toString(state.innerStepId,null),"skillRunId",Objects.toString(state.skillRunId,null)).toJSONString()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        size=Files.size(file);
                    }
                    if(size+bytes.length<=maxFileBytes)Files.write(file,bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    else partial=true;
                }
                if(ended) {
                    Files.writeString(file,JSONObject.of("end",true,"partial",partial,"innerStepId",Objects.toString(state.innerStepId,null),"skillRunId",Objects.toString(state.skillRunId,null)).toJSONString()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    pending.remove(key,state);
                } else if(partial) synchronized(state){state.partial=true;}
            } catch(IOException|RuntimeException failure) {
                synchronized(state){
                    state.partial=true;
                    if(!state.reportedFailure){
                        System.getLogger(AssistantReasoningArchive.class.getName()).log(System.Logger.Level.WARNING,"Reasoning archive write failed: session="+key.session+", run="+key.run+", step="+key.step+", type="+failure.getClass().getSimpleName());
                        state.reportedFailure=true;
                    }
                }
                // Archive errors must not block model streaming or reverse successful operations.
                if(ended)pending.remove(key,state);
            }
        }
    }

    /** Caller must authorize session/run/step first. Offset is a bounded record cursor, not a path. */
    public Map<String,Object> read(long session,long run,long step,String stream,int offset,int limit)throws IOException {
        return read(session,run,step,stream,offset,limit,null,null);
    }

    public Map<String,Object> read(long session,long run,long step,String stream,int offset,int limit,Long innerStep,Long skillRun)throws IOException {
        if(session<=0||run<=0||step<=0||offset<0||offset>1000000||limit<1||limit>64)
            throw new IllegalArgumentException("Invalid archive page");
        Path folder=folder(session,run,step,false);
        try {
            if(!Files.readAttributes(folder,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory())throw new IOException("Archive directory invalid");
        } catch(NoSuchFileException missing) { return Map.of("status","UNAVAILABLE","streams",List.of()); }
        List<String> names;
        List<String> selected=new ArrayList<>();
        try(var files=Files.list(folder)) {
            for(Path candidate:files.filter(p->!Files.isSymbolicLink(p)&&p.getFileName().toString().matches("[a-f0-9]{64}\\.jsonl")).limit(1024).toList()) {
                if(Files.size(candidate)>maxFileBytes+4096)throw new IOException("Archive size exceeds configured limit");
                try(var reader=Files.newBufferedReader(candidate,StandardCharsets.UTF_8)) {
                    String first=reader.readLine();JSONObject header=first==null?null:JSON.parseObject(first);
                    if(header!=null && Objects.equals(header.getString("innerStepId"),Objects.toString(innerStep,null))
                            && Objects.equals(header.getString("skillRunId"),Objects.toString(skillRun,null)))
                        selected.add(candidate.getFileName().toString().substring(0,64));
                } catch(NoSuchFileException removed) { /* Concurrent retention cleanup. */ }
                catch(RuntimeException corrupt) { throw new IOException("Archive header invalid",corrupt); }
                if(selected.size()>=65)break;
            }
        }
        names=selected.stream().sorted().toList();
        if(stream==null) {
            if(names.isEmpty())return Map.of("status","UNAVAILABLE","streams",List.of());
            Map<String,Object> first=new LinkedHashMap<>(read(session,run,step,names.get(0),0,limit,innerStep,skillRun));
            first.put("streams",names.subList(0,Math.min(64,names.size())));first.put("hasMoreStreams",names.size()>64);return first;
        }
        if(!stream.matches("[a-f0-9]{64}")||!names.contains(stream))return Map.of("status","UNAVAILABLE","streams",List.of());
        try {
        Path file=file(new Key(session,run,step,stream),false);
        if(Files.size(file)>maxFileBytes+4096)throw new IOException("Archive size exceeds configured limit");
        List<JSONObject> chunks=new ArrayList<>();boolean complete=false,partial=false,more=false;int index=0,chars=0;
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            String line;
            while((line=reader.readLine())!=null) {
                if(index++<offset)continue;
                JSONObject record;
                try{record=JSON.parseObject(line);}catch(RuntimeException incomplete){partial=true;break;}
                if(record.getBooleanValue("header"))continue;
                if(record.getBooleanValue("end")){complete=true;partial|=record.getBooleanValue("partial");break;}
                String content=record.getString("content");int length=content==null?0:content.length();
                if(chunks.size()>=limit||chars+length>131072){more=true;index--;break;}
                chunks.add(record);chars+=length;
            }
        }
        return Map.of("status",partial?"PARTIAL":more?"AVAILABLE":complete?"COMPLETE":"INCOMPLETE","stream",stream,"chunks",chunks,"nextOffset",index,"hasMore",more);
        } catch (NoSuchFileException removed) {
            // A retention cleanup can remove the file after its header was checked.
            return Map.of("status","UNAVAILABLE","streams",List.of());
        }
    }

    private Path file(Key key,boolean create)throws IOException {
        Path path=folder(key.session,key.run,key.step,create).resolve(key.stream+".jsonl");
        if(Files.isSymbolicLink(path))throw new IOException("Linked archive is forbidden");
        return path;
    }
    private Path folder(long session,long run,long step,boolean create)throws IOException {
        Path current=root;
        if(create)Files.createDirectories(current);
        if(Files.isSymbolicLink(current))throw new IOException("Linked archive root is forbidden");
        for(long id:new long[]{session,run,step}) {
            current=current.resolve(Long.toString(id));
            if(Files.isSymbolicLink(current))throw new IOException("Linked archive directory is forbidden");
            if(create)Files.createDirectories(current);
        }
        if(!current.normalize().startsWith(root))throw new IOException("Invalid archive path");
        return current;
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override @PreDestroy public void close(){writer.shutdown();flush();}
    private record Key(long session,long run,long step,String stream) { }
    private static final class Pending {
        final StringBuilder text=new StringBuilder();final Long innerStepId,skillRunId;boolean ended,partial,reportedFailure;
        Pending(Long innerStepId,Long skillRunId){this.innerStepId=innerStepId;this.skillRunId=skillRunId;}
    }
}
