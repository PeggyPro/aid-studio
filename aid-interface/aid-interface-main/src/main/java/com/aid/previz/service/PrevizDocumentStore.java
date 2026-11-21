package com.aid.previz.service;

import com.aid.previz.workspace.PrevizSourceStore;
import com.aid.previz.workspace.PrevizStoragePolicy;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Complete scene documents reference immutable project assets; private chat/draft data stays separate. */
public final class PrevizDocumentStore {
    private static final ReentrantLock[] LOCKS=new ReentrantLock[64];
    static{for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new ReentrantLock();}
    private final Path root,headsRoot;
    private final PrevizProjectService projects;
    private final PrevizDocumentValidator validator;
    private final PrevizSourceStore heads;
    private final PrevizSourceStore sources;
    private final PrevizWorkspaceState workspace;
    private final int maximumBytes,maximumVersions;
    private final long quotaBytes;
    private final PrevizStoragePolicy policy;
    public PrevizDocumentStore(Path root,PrevizProjectService projects,PrevizDocumentValidator validator,PrevizSourceStore sources,PrevizWorkspaceState workspace,int maximumBytes,long quotaBytes,int maximumVersions)throws IOException {
        this(root,projects,validator,sources,workspace,maximumBytes,quotaBytes,maximumVersions,PrevizStoragePolicy.DEFAULT);
    }
    public PrevizDocumentStore(Path root,PrevizProjectService projects,PrevizDocumentValidator validator,PrevizSourceStore sources,PrevizWorkspaceState workspace,int maximumBytes,long quotaBytes,int maximumVersions,PrevizStoragePolicy policy)throws IOException {
        if(maximumBytes<1024||maximumBytes>64*1024*1024||quotaBytes<maximumBytes||maximumVersions<1)throw new IllegalArgumentException("工程文档存储配置无效");
        directory(root);this.root=root.toRealPath();this.projects=projects;this.validator=validator;this.maximumBytes=maximumBytes;this.quotaBytes=quotaBytes;this.maximumVersions=maximumVersions;
        headsRoot=this.root.resolve("heads");heads=new PrevizSourceStore(headsRoot,projects);
        this.sources=sources;this.workspace=workspace;
        this.policy=Objects.requireNonNull(policy);
    }
    public JSONObject current(Long actor,String project)throws IOException {
        var state=workspace.read(actor,project);String version=state.getDocumentVersion();
        JSONObject result=version==null?JSONObject.of("documentVersion",null):summary(actor,project,version);
        result.put("currentSourceVersion",state.getSourceVersion());result.put("workspaceRevision",String.valueOf(state.getRevision()));return result;
    }
    public JSONObject read(Long actor,String project,String version)throws IOException {
        String current=workspace.read(actor,project).getDocumentVersion();String selected=version==null?current:version;
        if(selected==null)throw new IOException("DOCUMENT_NOT_FOUND");workspace.requirePublished(actor,project,selected);JSONObject manifest=summary(actor,project,selected);
        Path file=bodyFolder(project,false).resolve(manifest.getString("documentSha256")+".json");
        regular(file);byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(maximumBytes+1);}
        if(bytes.length!=manifest.getLongValue("sizeBytes")||!hash(bytes).equals(manifest.get("documentSha256")))throw new IOException("DOCUMENT_CONTENT_CHANGED");
        JSONObject document=JSON.parseObject(bytes);projects.requireRead(actor,project);
        return JSONObject.of("documentVersion",selected,"currentVersion",current,"sourceVersion",manifest.get("sourceVersion"),"documentSha256",manifest.get("documentSha256"),"document",document);
    }
    public JSONObject save(Long actor,String project,String expected,String expectedSource,JSONObject document)throws IOException {
        return save(actor,project,expected,expectedSource,document,version->{});
    }
    /** Internal operation journal is durable before publishing the authoritative workspace revision. */
    public JSONObject save(Long actor,String project,String expected,String expectedSource,JSONObject document,java.util.function.Consumer<String> beforePublish)throws IOException {
        projects.requireWrite(actor,project);if(expected!=null&&!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("工程版本无效");
        if(document==null)throw new IllegalArgumentException("工程文档不能为空");
        byte[] bytes=JSON.toJSONBytes(document,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue);if(bytes.length>maximumBytes)throw new IOException("DOCUMENT_SIZE_EXCEEDED");
        document=JSON.parseObject(bytes);
        validator.validate(actor,project,document);String digest=hash(bytes);
        Path folder=bodyFolder(project,true);ReentrantLock local=LOCKS[Math.floorMod(folder.hashCode(),LOCKS.length)];local.lock();
        try {
            Path lockFile=folder.resolve(".document-lock");if(Files.isSymbolicLink(lockFile))throw new IOException("DOCUMENT_LINK_INVALID");
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()) {
                projects.requireWrite(actor,project);var state=workspace.read(actor,project);String current=state.getDocumentVersion();
                if(!Objects.equals(expectedSource,state.getSourceVersion()))throw new IOException("WORKSPACE_VERSION_CONFLICT");
                if(current!=null){JSONObject previous=summary(actor,project,current);if(digest.equals(previous.get("documentSha256"))&&Objects.equals(expectedSource,previous.get("sourceVersion"))){verify(folder.resolve(digest+".json"),digest,bytes.length);beforePublish.accept(current);workspace.publishDocument(actor,project,expectedSource,current,current,document.getString("title"));return previous;}}
                if(!Objects.equals(expected,current))throw new IOException("DOCUMENT_VERSION_CONFLICT");
                long quotaBytes=policy.limit(PrevizStoragePolicy.Key.DOCUMENT_BYTES,this.quotaBytes);
                long maximumVersions=policy.limit(PrevizStoragePolicy.Key.DOCUMENT_VERSIONS,this.maximumVersions);
                long occupied=0;int files=0;
                try(var paths=Files.list(folder)){var iterator=paths.iterator();while(iterator.hasNext()){Path path=iterator.next();regular(path);if(++files>maximumVersions+1)throw new IOException("DOCUMENT_HISTORY_LIMIT");if(!path.getFileName().toString().equals(".document-lock"))occupied=Math.addExact(occupied,Files.size(path));}}
                Path body=folder.resolve(digest+".json");
                if(!Files.exists(body,LinkOption.NOFOLLOW_LINKS)&&occupied+bytes.length>quotaBytes)throw new IOException("DOCUMENT_QUOTA_EXCEEDED");
                Path versions=headsRoot.resolve(project).resolve("versions");
                if(Files.exists(versions,LinkOption.NOFOLLOW_LINKS)){if(Files.isSymbolicLink(versions))throw new IOException("DOCUMENT_LINK_INVALID");try(var paths=Files.list(versions)){if(paths.limit((long)maximumVersions+1).count()>=maximumVersions)throw new IOException("DOCUMENT_HISTORY_LIMIT");}}
                writeOnce(body,bytes,digest);projects.requireWrite(actor,project);
                JSONObject manifest=JSONObject.of("schemaVersion",1,"documentSha256",digest,"sizeBytes",bytes.length,"sourceVersion",expectedSource,"title",document.getString("title"),"savedBy",String.valueOf(actor),"savedAt",Instant.now().toString());
                String version=heads.save(actor,project,heads.currentVersion(actor,project),Map.of("manifest.json",JSON.toJSONBytes(manifest,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue)));
                beforePublish.accept(version);workspace.publishDocument(actor,project,expectedSource,expected,version,document.getString("title"));
                manifest.put("documentVersion",version);return manifest;
            }
        }finally{local.unlock();}
    }
    public JSONObject history(Long actor,String project,String before,int limit)throws IOException {
        long cursor=Long.MAX_VALUE;try{if(before!=null)cursor=Long.parseLong(before);}catch(NumberFormatException failure){throw new IllegalArgumentException("历史分页无效");}
        var entries=workspace.history(actor,project,cursor,limit);List<JSONObject> values=new ArrayList<>();
        for(var entry:entries){JSONObject item=summary(actor,project,entry.getDocumentVersion());item.put("workspaceRevision",String.valueOf(entry.getRevision()));item.put("operation",entry.getOperation());values.add(item);}
        projects.requireRead(actor,project);return JSONObject.of("items",values,"nextCursor",entries.size()==limit?String.valueOf(entries.get(entries.size()-1).getRevision()):null);
    }
    public JSONObject restore(Long actor,String project,String expected,String expectedSource,String version)throws IOException {
        projects.requireWrite(actor,project);JSONObject document=read(actor,project,version);String source=document.getString("sourceVersion");
        if(source!=null)sources.read(actor,project,source);
        workspace.restore(actor,project,expectedSource,expected,source,version,document.getJSONObject("document").getString("title"));return current(actor,project);
    }
    private JSONObject summary(Long actor,String project,String version)throws IOException {
        Map<String,byte[]> files=heads.read(actor,project,version);byte[] bytes=files.get("manifest.json");if(files.size()!=1||bytes==null||bytes.length>4096)throw new IOException("DOCUMENT_MANIFEST_INVALID");
        JSONObject manifest=JSON.parseObject(bytes);String digest=manifest.getString("documentSha256");
        if(digest==null||!digest.matches("[a-f0-9]{64}")||manifest.getLongValue("sizeBytes")<1||manifest.getLongValue("sizeBytes")>maximumBytes||manifest.getString("title")==null)throw new IOException("DOCUMENT_MANIFEST_INVALID");
        manifest.put("documentVersion",version);return manifest;
    }
    private Path bodyFolder(String project,boolean create)throws IOException {
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("工程标识无效");
        Path bodies=root.resolve("bodies"),folder=bodies.resolve(project);
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(bodies)||Files.isSymbolicLink(folder))throw new IOException("DOCUMENT_LINK_INVALID");
        if(create){directory(bodies);directory(folder);}return folder;
    }
    private static void directory(Path path)throws IOException{Files.createDirectories(path);if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("DOCUMENT_DIRECTORY_INVALID");}
    private static void regular(Path path)throws IOException{if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("DOCUMENT_NOT_FOUND");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}
    private static void verify(Path path,String hash,long size)throws IOException {
        regular(path);if(Files.size(path)!=size)throw new IOException("DOCUMENT_CONTENT_CHANGED");MessageDigest digest=digest();
        try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[262144];for(int read;(read=input.read(buffer))>=0;)if(read>0)digest.update(buffer,0,read);}
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("DOCUMENT_CONTENT_CHANGED");
    }
    private static void writeOnce(Path path,byte[] bytes,String digest)throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){verify(path,digest,bytes.length);return;}
        Path pending=path.resolveSibling(".pending-"+UUID.randomUUID());
        try{try(var output=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}try{Files.createLink(path,pending);}catch(FileAlreadyExistsException race){verify(path,digest,bytes.length);}}
        finally{Files.deleteIfExists(pending);}
    }
}
