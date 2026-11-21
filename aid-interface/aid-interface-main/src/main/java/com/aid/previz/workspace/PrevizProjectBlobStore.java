package com.aid.previz.workspace;

import com.aid.common.aid.image.ManagedImageIO;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Project-owned immutable assets. Each HTTP chunk is bounded; completing never buffers a whole model. */
public final class PrevizProjectBlobStore {
    public static final int CHUNK_BYTES = 262144;
    public static final int MAX_BLOB_BYTES = 32 * 1024 * 1024;
    private static final Set<String> MIMES = Set.of("model/gltf-binary", "image/png", "image/jpeg", "image/webp");
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    static { for (int i=0;i<LOCKS.length;i++) LOCKS[i]=new ReentrantLock(); }
    private final Path root;
    private final PrevizWorkspaceAccess access;
    private final long quotaBytes, minimumFreeBytes;
    private final int maximumPending, maximumAssets, maximumUploadRecords;
    private final PrevizStoragePolicy policy;
    public PrevizProjectBlobStore(Path root, PrevizWorkspaceAccess access, long quotaBytes, long minimumFreeBytes, int maximumPending) throws IOException {
        this(root,access,quotaBytes,minimumFreeBytes,maximumPending,4096,32768);
    }
    public PrevizProjectBlobStore(Path root, PrevizWorkspaceAccess access, long quotaBytes, long minimumFreeBytes, int maximumPending,int maximumAssets,int maximumUploadRecords) throws IOException {
        this(root,access,quotaBytes,minimumFreeBytes,maximumPending,maximumAssets,maximumUploadRecords,PrevizStoragePolicy.DEFAULT);
    }
    public PrevizProjectBlobStore(Path root, PrevizWorkspaceAccess access, long quotaBytes, long minimumFreeBytes, int maximumPending,int maximumAssets,int maximumUploadRecords,PrevizStoragePolicy policy) throws IOException {
        if(access==null||quotaBytes<MAX_BLOB_BYTES||minimumFreeBytes<0||maximumPending<1||maximumAssets<1||maximumUploadRecords<1)throw new IllegalArgumentException("资产存储配置无效");
        safeDirectory(root);this.root=root.toRealPath();this.access=access;this.quotaBytes=quotaBytes;this.minimumFreeBytes=minimumFreeBytes;this.maximumPending=maximumPending;this.maximumAssets=maximumAssets;this.maximumUploadRecords=maximumUploadRecords;
        this.policy=Objects.requireNonNull(policy);
    }
    public JSONObject begin(Long actor,String project,String operation,String sha256,long size,String mime) throws IOException {
        access.requireWrite(actor,project);hash(sha256);
        if(operation==null||!operation.matches("[A-Za-z0-9_-]{1,100}")||size<1||size>MAX_BLOB_BYTES||mime==null||!MIMES.contains(mime))throw new IllegalArgumentException("资产上传参数无效");
        return locked(actor,project,folder->{
            String upload=sha(operation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path uploads=folder.resolve("uploads"),owner=uploads.resolve(String.valueOf(actor)),pending=owner.resolve(upload);
            safeDirectory(uploads);safeDirectory(owner);
            JSONObject binding=JSONObject.of("actor",actor,"projectId",project,"sha256",sha256,"sizeBytes",size,"mimeType",mime);
            Path blobs=blobFolder(folder,true),existing=blobs.resolve(sha256);
            boolean complete=Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS);
            if(complete){verifyBlob(existing,sha256,size);validateMedia(existing,size,mime);}
            Path metadata=pending.resolve("upload.json");
            active(pending);
            if(Files.exists(metadata,LinkOption.NOFOLLOW_LINKS)) {
                JSONObject original=binding(metadata,actor,project);
                if(!sha256.equals(original.getString("sha256"))||size!=original.getLongValue("sizeBytes")||!mime.equals(original.getString("mimeType")))throw new IOException("ASSET_UPLOAD_CONFLICT");
            } else {
                long quotaBytes=policy.limit(PrevizStoragePolicy.Key.ASSET_BYTES,this.quotaBytes),minimumFreeBytes=policy.limit(PrevizStoragePolicy.Key.FREE_BYTES,this.minimumFreeBytes);
                long maximumPending=policy.limit(PrevizStoragePolicy.Key.PENDING_UPLOADS,this.maximumPending),maximumAssets=policy.limit(PrevizStoragePolicy.Key.ASSET_COUNT,this.maximumAssets),maximumUploadRecords=policy.limit(PrevizStoragePolicy.Key.UPLOAD_RECORDS,this.maximumUploadRecords);
                long occupied=0,reserved=0;int count=0,assetCount=0,recordCount=0;
                try(var paths=Files.list(blobs)){var iterator=paths.iterator();while(iterator.hasNext()){Path file=iterator.next();regular(file);if(++assetCount>maximumAssets)throw new IOException("ASSET_PROJECT_COUNT_EXCEEDED");occupied=Math.addExact(occupied,Files.size(file));}}
                try(var paths=Files.walk(uploads,3)) {
                    var iterator=paths.iterator();int visited=0;
                    while(iterator.hasNext()) {
                        Path file=iterator.next();if(++visited>maximumUploadRecords*4L+maximumPending*130L)throw new IOException("ASSET_UPLOAD_RECORD_LIMIT");
                        if(!file.getFileName().toString().equals("upload.json"))continue;
                        if(++recordCount>=maximumUploadRecords)throw new IOException("ASSET_UPLOAD_RECORD_LIMIT");
                        JSONObject info=binding(file,null,project);
                        if(!Files.exists(file.resolveSibling("cancelled"),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(blobs.resolve(info.getString("sha256")),LinkOption.NOFOLLOW_LINKS)){count++;reserved=Math.addExact(reserved,info.getLongValue("sizeBytes"));}
                    }
                }
                if(!complete&&(assetCount>=maximumAssets||count>=maximumPending||occupied+reserved+size>quotaBytes))throw new IOException("ASSET_PROJECT_QUOTA_EXCEEDED");
                if(Files.getFileStore(root).getUsableSpace()<minimumFreeBytes+(complete?0:size))throw new IOException("ASSET_STORAGE_SPACE_LOW");
                safeDirectory(pending);writeOnce(metadata,binding.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var present=new ArrayList<Integer>();int chunks=(int)((size+CHUNK_BYTES-1)/CHUNK_BYTES);
            for(int i=0;i<chunks;i++)if(Files.isRegularFile(pending.resolve(i+".chunk"),LinkOption.NOFOLLOW_LINKS))present.add(i);
            return JSONObject.of("uploadId",upload,"chunkBytes",CHUNK_BYTES,"chunkCount",chunks,"uploadedChunks",present,"complete",complete);
        });
    }
    public JSONObject putChunk(Long actor,String project,String upload,int index,String content,String chunkSha) throws IOException {
        access.requireWrite(actor,project);hash(upload);hash(chunkSha);
        if(content==null||content.length()>((CHUNK_BYTES+2)/3)*4)throw new IllegalArgumentException("资产分块超限");
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(content);}catch(IllegalArgumentException invalid){throw new IOException("ASSET_CHUNK_ENCODING_INVALID");}
        if(!sha(bytes).equals(chunkSha))throw new IOException("ASSET_CHUNK_DIGEST_MISMATCH");
        return locked(actor,project,folder->{
            Path pending=upload(folder,actor,upload);var info=binding(pending.resolve("upload.json"),actor,project);
            active(pending);
            long size=info.getLongValue("sizeBytes"),offset=(long)index*CHUNK_BYTES;
            if(index<0||offset>=size||bytes.length!=Math.min(CHUNK_BYTES,size-offset))throw new IllegalArgumentException("资产分块范围无效");
            Path completed=blobFolder(folder,false).resolve(info.getString("sha256"));
            if(Files.exists(completed,LinkOption.NOFOLLOW_LINKS)) {
                regular(completed);if(Files.size(completed)!=size)throw new IOException("ASSET_SIZE_CHANGED");
                ByteBuffer existing=ByteBuffer.allocate(bytes.length);
                try(var file=FileChannel.open(completed,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){file.position(offset);while(existing.hasRemaining())if(file.read(existing)<0)throw new IOException("ASSET_CHANGED_DURING_READ");}
                if(!Arrays.equals(existing.array(),bytes))throw new IOException("ASSET_WRITE_CONFLICT");
            } else writeOnce(pending.resolve(index+".chunk"),bytes);
            return JSONObject.of("uploadId",upload,"index",index,"sha256",chunkSha,"sizeBytes",bytes.length);
        });
    }
    public JSONObject complete(Long actor,String project,String upload) throws IOException {
        access.requireWrite(actor,project);hash(upload);
        return locked(actor,project,folder->{
            Path pending=upload(folder,actor,upload);JSONObject info=binding(pending.resolve("upload.json"),actor,project);
            active(pending);
            String expected=info.getString("sha256");long size=info.getLongValue("sizeBytes");
            Path blobs=blobFolder(folder,true);Path target=blobs.resolve(expected);
            if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                Path assembly=blobs.resolve(".pending-"+UUID.randomUUID());
                try {
                    MessageDigest digest=digest();
                    try(FileChannel output=FileChannel.open(assembly,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        for(int index=0; (long)index*CHUNK_BYTES<size; index++) {
                            Path chunk=pending.resolve(index+".chunk");regular(chunk);
                            int length=(int)Math.min(CHUNK_BYTES,size-(long)index*CHUNK_BYTES);
                            if(Files.size(chunk)!=length)throw new IOException("ASSET_CHUNK_MISSING_OR_CHANGED");
                            byte[] bytes=Files.readAllBytes(chunk);digest.update(bytes);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);
                        }
                        output.force(true);
                    }
                    if(!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IOException("ASSET_DIGEST_MISMATCH");
                    validateMedia(assembly,size,info.getString("mimeType"));
                    access.requireWrite(actor,project);
                    try{Files.createLink(target,assembly);}catch(FileAlreadyExistsException duplicate){verifyBlob(target,expected,size);}
                }finally{Files.deleteIfExists(assembly);}
            } else verifyBlob(target,expected,size);
            validateMedia(target,size,info.getString("mimeType"));
            for(int index=0;(long)index*CHUNK_BYTES<size;index++)Files.deleteIfExists(pending.resolve(index+".chunk"));
            return JSONObject.of("sha256",expected,"sizeBytes",size,"mimeType",info.getString("mimeType"));
        });
    }
    public JSONObject readChunk(Long actor,String project,String sha256,long offset,int limit) throws IOException {
        access.requireRead(actor,project);hash(sha256);
        Path file=blobFolder(project(project),false).resolve(sha256);regular(file);long size=Files.size(file);
        if(size<1||size>MAX_BLOB_BYTES||offset<0||offset>size||limit<1||limit>CHUNK_BYTES)throw new IllegalArgumentException("资产读取范围无效");
        byte[] bytes=new byte[(int)Math.min(limit,size-offset)];
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(channel.read(buffer)<0)throw new IOException("ASSET_CHANGED_DURING_READ");
        }
        access.requireRead(actor,project);
        return JSONObject.of("sha256",sha256,"offset",offset,"sizeBytes",size,"chunkSha256",sha(bytes),"contentBase64",Base64.getEncoder().encodeToString(bytes),"nextOffset",offset+bytes.length<size?offset+bytes.length:null);
    }
    public JSONObject cancel(Long actor,String project,String upload) throws IOException {
        access.requireWrite(actor,project);hash(upload);
        return locked(actor,project,folder->{
            Path pending=upload(folder,actor,upload);JSONObject info=binding(pending.resolve("upload.json"),actor,project);
            if(Files.isRegularFile(blobFolder(folder,false).resolve(info.getString("sha256")),LinkOption.NOFOLLOW_LINKS))
                return JSONObject.of("uploadId",upload,"cancelled",false,"complete",true);
            writeOnce(pending.resolve("cancelled"),new byte[]{1});
            for(int index=0;(long)index*CHUNK_BYTES<info.getLongValue("sizeBytes");index++)Files.deleteIfExists(pending.resolve(index+".chunk"));
            return JSONObject.of("uploadId",upload,"cancelled",true,"complete",false);
        });
    }
    public void requireBlob(Long actor,String project,String sha256,long size,String mime) throws IOException {
        access.requireRead(actor,project);hash(sha256);
        if(mime==null||!MIMES.contains(mime)||size<1||size>MAX_BLOB_BYTES)throw new IllegalArgumentException("资产元信息无效");
        Path file=blobFolder(project(project),false).resolve(sha256);verifyBlob(file,sha256,size);validateMedia(file,size,mime);
    }
    /** Only native cameras reachable from the GLB's selected scene can be referenced by editor shots. */
    public Set<String> nativeCameraIds(Long actor,String project,String sha256)throws IOException {
        return nativeObjectIds(actor,project,sha256,true);
    }
    public Set<String> nativeObjectIds(Long actor,String project,String sha256)throws IOException {
        return nativeObjectIds(actor,project,sha256,false);
    }
    private Set<String> nativeObjectIds(Long actor,String project,String sha256,boolean camerasOnly)throws IOException {
        JSONObject document=nativeDocument(actor,project,sha256);
        return nativeObjectIdentities(document,camerasOnly);
    }
    private JSONObject nativeDocument(Long actor,String project,String sha256)throws IOException {
        access.requireRead(actor,project);hash(sha256);Path file=blobFolder(project(project),false).resolve(sha256);regular(file);
        long size=Files.size(file);if(size<20||size>MAX_BLOB_BYTES)throw new IOException("ASSET_GLB_INVALID");
        verifyBlob(file,sha256,size);validateMedia(file,size,"model/gltf-binary");
        JSONObject document;
        try(var input=new DataInputStream(Files.newInputStream(file))){byte[] header=input.readNBytes(20);int length=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12);byte[] json=input.readNBytes(length);if(json.length!=length)throw new IOException("ASSET_GLB_INVALID");
            try{document=JSON.parseObject(json);}catch(RuntimeException invalid){throw new IOException("ASSET_GLB_INVALID",invalid);}
        }
        if(document==null)throw new IOException("ASSET_GLB_INVALID");
        return document;
    }
    public Set<String> nativeRootObjectIds(Long actor,String project,String sha256)throws IOException {
        JSONObject document=nativeDocument(actor,project,sha256);Set<String> valid=nativeObjectIdentities(document,false),result=new HashSet<>();
        var nodes=document.getJSONArray("nodes");var scenes=document.getJSONArray("scenes");
        int selected=document.getIntValue("scene");if(valid.isEmpty()||nodes==null||scenes==null||selected<0||selected>=scenes.size()||!(scenes.get(selected) instanceof JSONObject chosen)||chosen.getJSONArray("nodes")==null)return Set.of();
        var roots=chosen.getJSONArray("nodes");
        for(Object raw:roots)if(raw instanceof Number index&&index.doubleValue()==index.intValue()&&index.intValue()>=0&&index.intValue()<nodes.size()&&nodes.get(index.intValue()) instanceof JSONObject node&&node.get("extras") instanceof JSONObject extras){String id=extras.getString("hf_id");if(valid.contains(id))result.add(id);}
        return result;
    }
    public Set<String> editableObjectIds(Long actor,String project,String sha256,List<String> selected)throws IOException {
        JSONObject document=nativeDocument(actor,project,sha256);Set<String> valid=nativeObjectIdentities(document,false);
        if(selected==null)return valid;if(valid.isEmpty())throw new IOException("ASSET_OBJECT_NOT_FOUND");var nodes=document.getJSONArray("nodes");Map<String,Integer> indexes=new HashMap<>();
        for(int index=0;index<nodes.size();index++){if(!(nodes.get(index) instanceof JSONObject node))continue;var extras=node.getJSONObject("extras");if(extras!=null)indexes.put(extras.getString("hf_id"),index);}
        Set<Integer> visited=new HashSet<>();Set<String> result=new HashSet<>();ArrayDeque<Integer> pending=new ArrayDeque<>();
        for(String id:selected){if(!valid.contains(id))throw new IOException("ASSET_OBJECT_NOT_FOUND");pending.add(indexes.get(id));}
        while(!pending.isEmpty()){int index=pending.removeFirst();if(!visited.add(index)||!(nodes.get(index) instanceof JSONObject node))continue;var extras=node.getJSONObject("extras");if(extras!=null&&valid.contains(extras.getString("hf_id")))result.add(extras.getString("hf_id"));var children=node.getJSONArray("children");if(children!=null)for(Object raw:children){if(!(raw instanceof Number child)||child.doubleValue()!=child.intValue()||child.intValue()<0||child.intValue()>=nodes.size())throw new IOException("ASSET_GLB_INVALID");pending.add(child.intValue());}}
        return result;
    }
    public com.alibaba.fastjson2.JSONArray editableObjectCatalog(Long actor,String project,String sha256,List<String> selected)throws IOException {
        JSONObject document=nativeDocument(actor,project,sha256);Set<String> valid=editableObjectIds(actor,project,sha256,selected);
        var result=new com.alibaba.fastjson2.JSONArray();if(valid.isEmpty())return result;var nodes=document.getJSONArray("nodes");Map<Integer,String> parents=new HashMap<>();
        for(int i=0;i<nodes.size();i++)if(nodes.get(i) instanceof JSONObject node&&node.get("children") instanceof com.alibaba.fastjson2.JSONArray children){String parent=node.get("extras") instanceof JSONObject extras?extras.getString("hf_id"):null;for(Object raw:children)if(raw instanceof Number child&&child.intValue()>=0&&child.intValue()<nodes.size())parents.put(child.intValue(),parent);}
        for(int i=0;i<nodes.size();i++)if(nodes.get(i) instanceof JSONObject node&&node.get("extras") instanceof JSONObject extras&&valid.contains(extras.getString("hf_id"))){
            String name=node.getString("name");result.add(JSONObject.of("id",extras.getString("hf_id"),"name",name==null?extras.getString("hf_id"):name.substring(0,Math.min(200,name.length())),"parentId",parents.get(i),"role",extras.getString("aid_role"),"mesh",node.containsKey("mesh")));
        }return result;
    }
    private Set<String> nativeObjectIdentities(JSONObject document,boolean camerasOnly)throws IOException {
        if(!(document.get("nodes") instanceof com.alibaba.fastjson2.JSONArray nodes)||nodes.size()>5000
                ||!(document.get("scenes") instanceof com.alibaba.fastjson2.JSONArray scenes))return Set.of();
        com.alibaba.fastjson2.JSONArray cameras=document.getJSONArray("cameras");
        if(cameras==null)cameras=new com.alibaba.fastjson2.JSONArray();
        Object selected=document.getOrDefault("scene",0);
        if(!(selected instanceof Number selection)||selection.doubleValue()!=selection.intValue())return Set.of();
        int scene=selection.intValue();if(scene<0||scene>=scenes.size()||!(scenes.get(scene) instanceof JSONObject selectedScene)
                ||!(selectedScene.get("nodes") instanceof com.alibaba.fastjson2.JSONArray roots))return Set.of();
        boolean[] reached=new boolean[nodes.size()];ArrayDeque<Integer> pending=new ArrayDeque<>();
        for(Object raw:roots)if(raw instanceof Number index&&index.doubleValue()==index.intValue()&&index.intValue()>=0&&index.intValue()<nodes.size()&&!reached[index.intValue()]){reached[index.intValue()]=true;pending.add(index.intValue());}
        Map<String,Integer> counts=new HashMap<>();Set<String> candidates=new HashSet<>();
        while(!pending.isEmpty()) {
            if(!(nodes.get(pending.removeFirst()) instanceof JSONObject node))continue;
            if(node.get("children") instanceof com.alibaba.fastjson2.JSONArray children)for(Object raw:children)if(raw instanceof Number index&&index.doubleValue()==index.intValue()&&index.intValue()>=0&&index.intValue()<nodes.size()&&!reached[index.intValue()]){reached[index.intValue()]=true;pending.add(index.intValue());}
            Object identity=node.get("extras") instanceof JSONObject extras?extras.get("hf_id"):null;
            if(identity instanceof String id){counts.merge(id,1,Integer::sum);Object camera=node.get("camera");
                if(!camerasOnly&&!id.isBlank()&&id.length()<=200)candidates.add(id);
                if(!id.isBlank()&&id.length()<=200&&camera instanceof Number index&&index.doubleValue()==index.intValue()&&index.intValue()>=0&&index.intValue()<cameras.size()) {
                    if(cameras.get(index.intValue()) instanceof JSONObject value&&("perspective".equals(value.getString("type"))||"orthographic".equals(value.getString("type"))))candidates.add(id);
                }
            }
        }
        candidates.removeIf(id->counts.getOrDefault(id,0)!=1);return Set.copyOf(candidates);
    }
    private Path project(String project) throws IOException {
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("工程标识无效");
        if(Files.isSymbolicLink(root))throw new IOException("ASSET_ROOT_LINK");Path folder=root.resolve(project);if(Files.isSymbolicLink(folder))throw new IOException("ASSET_PROJECT_LINK");return folder;
    }
    private Path upload(Path folder,Long actor,String upload) throws IOException {
        Path uploads=folder.resolve("uploads"),owner=uploads.resolve(String.valueOf(actor)),pending=owner.resolve(upload);
        for(Path path:List.of(uploads,owner,pending))if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("ASSET_UPLOAD_NOT_FOUND");return pending;
    }
    private interface Operation<T>{T apply(Path folder)throws IOException;}
    private <T>T locked(Long actor,String project,Operation<T> operation)throws IOException {
        Path folder=project(project);ReentrantLock local=LOCKS[Math.floorMod(folder.hashCode(),LOCKS.length)];local.lock();
        try{safeDirectory(folder);Path file=folder.resolve(".asset-lock");if(Files.isSymbolicLink(file))throw new IOException("ASSET_LOCK_LINK");
            try(var channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()) {
                access.requireWrite(actor,project);return operation.apply(folder);
            }
        }finally{local.unlock();}
    }
    private static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("资产摘要无效");}
    private static void active(Path pending)throws IOException{if(Files.exists(pending.resolve("cancelled"),LinkOption.NOFOLLOW_LINKS))throw new IOException("ASSET_UPLOAD_CANCELLED");}
    private static void safeDirectory(Path path)throws IOException{Files.createDirectories(path);if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("ASSET_DIRECTORY_INVALID");}
    private static void regular(Path path)throws IOException{if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("ASSET_FILE_UNAVAILABLE");}
    private static Path blobFolder(Path project,boolean create)throws IOException{Path folder=project.resolve("blobs");if(create)safeDirectory(folder);else if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("ASSET_DIRECTORY_INVALID");return folder;}
    private static JSONObject binding(Path path,Long actor,String project)throws IOException {
        JSONObject info=readJson(path);
        if(info==null||!info.keySet().equals(Set.of("actor","projectId","sha256","sizeBytes","mimeType"))
                ||!(info.get("actor") instanceof Number owner)||owner.longValue()<1||owner.doubleValue()!=owner.longValue()||actor!=null&&owner.longValue()!=actor
                ||!project.equals(info.getString("projectId"))||!(info.get("sizeBytes") instanceof Number size)
                ||size.longValue()<1||size.longValue()>MAX_BLOB_BYTES||size.doubleValue()!=size.longValue()||info.getString("mimeType")==null||!MIMES.contains(info.getString("mimeType")))throw new IOException("ASSET_RECEIPT_INVALID");
        hash(info.getString("sha256"));return info;
    }
    private static JSONObject readJson(Path path)throws IOException{regular(path);if(Files.size(path)>2048)throw new IOException("ASSET_RECEIPT_OVERSIZE");try{return JSON.parseObject(Files.readString(path));}catch(RuntimeException invalid){throw new IOException("ASSET_RECEIPT_INVALID",invalid);}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String sha(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}
    private static void verifyBlob(Path file,String hash,long size)throws IOException {
        regular(file);if(Files.size(file)!=size)throw new IOException("ASSET_SIZE_CHANGED");MessageDigest digest=digest();
        try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[CHUNK_BYTES];for(int n;(n=input.read(buffer))>=0;)if(n>0)digest.update(buffer,0,n);}
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("ASSET_CONTENT_CHANGED");
    }
    private static void writeOnce(Path target,byte[] bytes)throws IOException {
        Path pending=target.resolveSibling(".pending-"+UUID.randomUUID());
        try {
            try(var output=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())output.write(data);output.force(true);}
            try{Files.createLink(target,pending);}catch(FileAlreadyExistsException existing){regular(target);if(Files.size(target)!=bytes.length||!Arrays.equals(Files.readAllBytes(target),bytes))throw new IOException("ASSET_WRITE_CONFLICT");}
        }finally{Files.deleteIfExists(pending);}
    }
    private static void validateMedia(Path file,long size,String mime)throws IOException {
        regular(file);if(Files.size(file)!=size)throw new IOException("ASSET_SIZE_CHANGED");
        byte[] head;try(InputStream input=Files.newInputStream(file)){head=input.readNBytes(32);}
        if("model/gltf-binary".equals(mime)) {
            if(head.length<20||!Arrays.equals(Arrays.copyOf(head,4),new byte[]{103,108,84,70}))throw new IOException("ASSET_GLB_INVALID");
            ByteBuffer header=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if(header.getInt(4)!=2||Integer.toUnsignedLong(header.getInt(8))!=size||header.getInt(16)!=0x4e4f534a||header.getInt(12)<4||header.getInt(12)%4!=0||Integer.toUnsignedLong(header.getInt(12))>size-20)throw new IOException("ASSET_GLB_INVALID");
        } else if("image/webp".equals(mime)) {
            if(head.length<30||!new String(head,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")||!new String(head,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")||Integer.toUnsignedLong(ByteBuffer.wrap(head,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt())+8!=size)throw new IOException("ASSET_IMAGE_INVALID");
            try(var input=new DataInputStream(Files.newInputStream(file))) {
                input.skipNBytes(12);long left=size-12;boolean found=false;
                while(left>=8){byte[] chunk=input.readNBytes(8);if(chunk.length!=8)break;String kind=new String(chunk,0,4,java.nio.charset.StandardCharsets.US_ASCII);long length=Integer.toUnsignedLong(ByteBuffer.wrap(chunk,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());left-=8;if(length>left)throw new IOException("ASSET_IMAGE_INVALID");
                    if(List.of("VP8X","VP8 ","VP8L").contains(kind)){byte[] value=input.readNBytes((int)Math.min(length,10));int width,height;
                        if(kind.equals("VP8X")&&value.length>=10){width=1+le24(value,4);height=1+le24(value,7);}
                        else if(kind.equals("VP8 ")&&value.length>=10&&value[3]==(byte)0x9d&&value[4]==1&&value[5]==0x2a){width=((value[6]&255)|((value[7]&255)<<8))&0x3fff;height=((value[8]&255)|((value[9]&255)<<8))&0x3fff;}
                        else if(kind.equals("VP8L")&&value.length>=5&&value[0]==0x2f){int bits=ByteBuffer.wrap(value,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt();width=1+(bits&0x3fff);height=1+((bits>>>14)&0x3fff);}
                        else throw new IOException("ASSET_IMAGE_INVALID");dimensions(width,height);found=true;break;
                    }
                    long padded=length+(length&1);if(padded>left)throw new IOException("ASSET_IMAGE_INVALID");input.skipNBytes(padded);left-=padded;
                }if(!found)throw new IOException("ASSET_IMAGE_INVALID");
            }
        } else {
            try(var input=ManagedImageIO.createImageInputStream(file.toFile())){var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("ASSET_IMAGE_INVALID");var reader=readers.next();
                try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!mime.equals("image/"+format))throw new IOException("ASSET_IMAGE_FORMAT_MISMATCH");dimensions(reader.getWidth(0),reader.getHeight(0));}finally{reader.dispose();}
            }
        }
    }
    private static int le24(byte[] bytes,int offset){return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16);}
    private static void dimensions(int width,int height)throws IOException{if(width<1||height<1||width>8192||height>8192)throw new IOException("ASSET_IMAGE_DIMENSIONS_INVALID");}
}
