package com.aid.previz.workspace;

import com.alibaba.fastjson2.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Versioned auxiliary files under /workspace/files, separate from the executable source snapshot. */
public final class PrevizWorkspaceFiles {
    private final PrevizSourceStore manifests;
    private final PrevizFileContentStore content;
    private final int maximumFiles;
    private final long maximumBytes;
    private final PrevizWorkspaceAccess access;
    private final PrevizStoragePolicy policy;
    public PrevizWorkspaceFiles(Path root,PrevizWorkspaceAccess access,int maximumFiles,long maximumBytes)throws IOException {
        this(root,access,maximumFiles,maximumBytes,1024L*1024*1024,8192);
    }
    public PrevizWorkspaceFiles(Path root,PrevizWorkspaceAccess access,int maximumFiles,long maximumBytes,long storedBytes,int storedObjects)throws IOException {
        this(root,access,maximumFiles,maximumBytes,storedBytes,storedObjects,PrevizStoragePolicy.DEFAULT);
    }
    public PrevizWorkspaceFiles(Path root,PrevizWorkspaceAccess access,int maximumFiles,long maximumBytes,long storedBytes,int storedObjects,PrevizStoragePolicy policy)throws IOException {
        if(maximumFiles<1||maximumFiles>5000||maximumBytes<PrevizFileContentStore.MAX_FILE_BYTES)throw new IllegalArgumentException("工作区容量配置无效");
        manifests=new PrevizSourceStore(root.resolve("manifests"),access);content=new PrevizFileContentStore(root.resolve("objects"),access,storedBytes,storedObjects,PrevizFileContentStore.MAX_FILE_BYTES,policy);this.access=access;this.maximumFiles=maximumFiles;this.maximumBytes=maximumBytes;this.policy=Objects.requireNonNull(policy);
    }
    public String current(Long actor,String project)throws IOException{return manifests.currentVersion(actor,project);}
    private JSONObject files(Long actor,String project,String version)throws IOException {
        if(version==null){manifests.currentVersion(actor,project);return new JSONObject();}var entries=manifests.read(actor,project,version);
        if(entries.size()!=1||!entries.containsKey("files.json"))throw new IOException("WORKSPACE_MANIFEST_INVALID");
        JSONObject result=JSON.parseObject(entries.get("files.json"));if(result.size()>5000)throw new IOException("WORKSPACE_FILE_COUNT_EXCEEDED");
        for(String name:result.keySet()){validateName(name);JSONObject ref=result.getJSONObject(name);if(ref==null||ref.getString("sha256")==null||!ref.getString("sha256").matches("[a-f0-9]{64}")||ref.getLongValue("size")<0||ref.getLongValue("size")>PrevizFileContentStore.MAX_FILE_BYTES)throw new IOException("WORKSPACE_MANIFEST_INVALID");}
        return result;
    }
    public JSONObject read(Long actor,String project,String version,String path,int offset,int limit)throws IOException {
        String name=filePath(path);JSONObject ref=files(actor,project,version).getJSONObject(name);if(ref==null)throw new IOException("WORKSPACE_FILE_NOT_FOUND");
        JSONObject result=PrevizTextPage.read(content.read(actor,project,ref.getString("sha256"),ref.getIntValue("size")),offset,limit);result.put("path","/workspace/files/"+name);result.put("etag",ref.get("sha256"));result.put("workspaceVersion",version);return result;
    }
    public byte[] readBytes(Long actor,String project,String version,String path)throws IOException {
        JSONObject ref=files(actor,project,version).getJSONObject(filePath(path));if(ref==null)throw new IOException("WORKSPACE_FILE_NOT_FOUND");return content.read(actor,project,ref.getString("sha256"),ref.getIntValue("size"));
    }
    public JSONObject write(Long actor,String project,String expected,String path,byte[] bytes)throws IOException {
        access.requireWrite(actor,project);
        if(bytes==null||bytes.length>PrevizFileContentStore.MAX_FILE_BYTES)throw new IllegalArgumentException("单文件超过10MiB限制");
        String name=filePath(path),head=current(actor,project),hash=PrevizFileContentStore.digest(bytes);JSONObject entries=files(actor,project,head),previous=entries.getJSONObject(name);
        if(previous!=null&&hash.equals(previous.getString("sha256"))&&previous.getIntValue("size")==bytes.length){content.read(actor,project,hash,bytes.length);return receipt("/workspace/files/"+name,head,hash,bytes.length,false);}
        if(!Objects.equals(expected,head))throw new IOException("WORKSPACE_VERSION_CONFLICT");
        long total=bytes.length;String lower=name.toLowerCase(Locale.ROOT);
        for(String existing:entries.keySet()){
            if(existing.equals(name))continue;String folded=existing.toLowerCase(Locale.ROOT);
            if(folded.equals(lower)||folded.startsWith(lower+"/")||lower.startsWith(folded+"/"))throw new IllegalArgumentException("工作区文件路径冲突");
            total=Math.addExact(total,entries.getJSONObject(existing).getLongValue("size"));
        }
        if(total>policy.limit(PrevizStoragePolicy.Key.FILE_BYTES,maximumBytes)||previous==null&&entries.size()>=policy.limit(PrevizStoragePolicy.Key.FILE_COUNT,maximumFiles))throw new IOException("WORKSPACE_QUOTA_EXCEEDED");
        content.put(actor,project,bytes);
        entries.put(name,JSONObject.of("sha256",hash,"size",bytes.length,"modified",System.currentTimeMillis()));
        String next=manifests.save(actor,project,head,Map.of("files.json",JSON.toJSONBytes(entries,JSONWriter.Feature.MapSortField)));
        return receipt("/workspace/files/"+name,next,hash,bytes.length,previous==null);
    }
    public JSONObject writeText(Long actor,String project,String expected,String path,String text)throws IOException {
        if(text==null)throw new IllegalArgumentException("文件正文不能为空值");if(text.length()>PrevizFileContentStore.MAX_FILE_BYTES)throw new IllegalArgumentException("单文件超过10MiB限制");
        var buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        if(buffer.remaining()>PrevizFileContentStore.MAX_FILE_BYTES)throw new IllegalArgumentException("单文件超过10MiB限制");byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return write(actor,project,expected,path,bytes);
    }
    private List<PrevizSourceStore.FileInfo> inspect(JSONObject entries){return entries.entrySet().stream().map(item->{JSONObject ref=(JSONObject)item.getValue();return new PrevizSourceStore.FileInfo(item.getKey(),ref.getLongValue("size"),ref.getLongValue("modified"));}).toList();}
    public JSONObject list(Long actor,String project,String version,String path)throws IOException {JSONObject result=PrevizSourceSearch.list(inspect(files(actor,project,version)),directory(path));result.put("workspaceVersion",version);return result;}
    public JSONObject glob(Long actor,String project,String version,String pattern)throws IOException {JSONObject result=PrevizSourceSearch.glob(inspect(files(actor,project,version)),directory(pattern));result.put("workspaceVersion",version);return result;}
    public JSONObject edit(Long actor,String project,String expected,String path,JSONArray edits)throws IOException {
        access.requireWrite(actor,project);var changed=PrevizSourceEdits.apply(readBytes(actor,project,expected,path),edits,PrevizFileContentStore.MAX_FILE_BYTES);
        JSONObject result=write(actor,project,expected,path,changed.bytes());result.put("replacements",changed.replacements());return result;
    }
    public JSONObject grep(Long actor,String project,String version,String path,String pattern,String include,int before,int after)throws IOException {
        PrevizSourceSearch.validateGrep(pattern,include,before,after);
        String folder=directory(path),prefix=folder.isEmpty()?"":folder+"/";JSONObject entries=files(actor,project,version);
        if(entries.containsKey(folder)||!folder.isEmpty()&&entries.keySet().stream().noneMatch(name->name.startsWith(prefix)))throw new IllegalArgumentException("需要已有目录");
        JSONArray matches=new JSONArray();int scanned=0,skipped=0,size=0;boolean truncated=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        outer:for(String name:new TreeSet<>(entries.keySet())){
            if(!name.startsWith(prefix))continue;if(System.nanoTime()>deadline){truncated=true;break;}
            JSONObject result=PrevizSourceSearch.grep(Map.of(name,readBytes(actor,project,version,"/workspace/files/"+name)),folder,pattern,include,before,after);
            scanned+=result.getIntValue("filesScanned");skipped+=result.getIntValue("skippedFiles");truncated|=result.getBooleanValue("truncated");
            for(Object match:result.getJSONArray("matches")){int length=JSON.toJSONBytes(match).length;if(matches.size()>=1000||size+length>100*1024-512){truncated=true;break outer;}matches.add(match);size+=length;}
        }
        return JSONObject.of("workspaceVersion",version,"matches",matches,"filesScanned",scanned,"skippedFiles",skipped,"truncated",truncated);
    }
    public Map<String,String> copyTargets(Long actor,String project,String expected,String sourcePath,String targetPath)throws IOException {
        String source=directory(sourcePath);JSONObject sourceEntries=files(actor,project,expected);
        boolean pattern=source.chars().anyMatch(c->"*?[{".indexOf(c)>=0);
        List<String> names;
        if(pattern){JSONObject matches=PrevizSourceSearch.glob(inspect(sourceEntries),source);if(matches.getBooleanValue("truncated")||matches.getJSONArray("paths").size()>50)throw new IllegalArgumentException("复制最多50个文件，未写入任何目标");names=matches.getJSONArray("paths").toJavaList(String.class);}
        else{validateName(source);if(!sourceEntries.containsKey(source))throw new IOException("WORKSPACE_FILE_NOT_FOUND");names=List.of(source);}
        if(names.isEmpty())throw new IOException("WORKSPACE_FILE_NOT_FOUND");
        String target=directory(targetPath),base="";
        if(pattern){int wildcard=source.length();for(char c:new char[]{'*','?','[','{'}){int at=source.indexOf(c);if(at>=0)wildcard=Math.min(wildcard,at);}int slash=source.lastIndexOf('/',wildcard);base=slash<0?"":source.substring(0,slash+1);}
        Map<String,String> destinations=new LinkedHashMap<>();Set<String> unique=new HashSet<>();
        for(String name:names){String destination=pattern?(target.isEmpty()?"":target+"/")+name.substring(base.length()):target;validateName(destination);if(!unique.add(destination.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("复制目标重叠，未写入任何目标");destinations.put(name,destination);}
        return Collections.unmodifiableMap(destinations);
    }
    public JSONObject copy(Long actor,String project,String expected,String sourcePath,String targetPath)throws IOException {
        access.requireWrite(actor,project);JSONObject sourceEntries=files(actor,project,expected);
        var destinations=copyTargets(actor,project,expected,sourcePath,targetPath);
        if(!Objects.equals(current(actor,project),expected))throw new IOException("WORKSPACE_VERSION_CONFLICT");
        JSONArray successes=new JSONArray(),failures=new JSONArray();String version=expected;boolean stopped=false;
        for(var entry:destinations.entrySet()){
            if(stopped){failures.add(JSONObject.of("source",entry.getKey(),"target",entry.getValue(),"code","NOT_ATTEMPTED_AFTER_CONFLICT"));continue;}
            try{JSONObject ref=sourceEntries.getJSONObject(entry.getKey());byte[] bytes=content.read(actor,project,ref.getString("sha256"),ref.getIntValue("size"));JSONObject result=write(actor,project,version,"/workspace/files/"+entry.getValue(),bytes);version=result.getString("workspaceVersion");successes.add(result);}
            catch(IOException|IllegalArgumentException failure){failures.add(JSONObject.of("source",entry.getKey(),"target",entry.getValue(),"code",Objects.equals(failure.getMessage(),"WORKSPACE_VERSION_CONFLICT")?"WORKSPACE_VERSION_CONFLICT":"WORKSPACE_COPY_FAILED"));stopped=true;}
        }
        return JSONObject.of("workspaceVersion",version,"succeeded",successes,"failed",failures,"atomicBatch",false);
    }
    private static JSONObject receipt(String path,String version,String hash,int size,boolean created){return JSONObject.of("path",path,"workspaceVersion",version,"etag",hash,"size",size,"created",created);}
    /** The virtual namespace has no OS-dependent // semantics. Never decode or resolve parent segments. */
    public static String normalizeVirtualPath(String path){
        if(path==null||path.length()>1024||path.indexOf('\\')>=0||path.indexOf(':')>=0||path.indexOf('\0')>=0)
            throw new IllegalArgumentException("工程文件路径无效");
        String normalized=path.replaceAll("/{2,}","/");
        if(!normalized.equals("/workspace/files")&&!normalized.startsWith("/workspace/files/"))
            throw new IllegalArgumentException("仅允许/workspace/files项目文件区");
        return normalized;
    }
    public static String canonicalFilePath(String path){return "/workspace/files/"+filePath(path);}
    private static String directory(String path){String normalized=normalizeVirtualPath(path);return PrevizSourceSearch.path(normalized.equals("/workspace/files")?"/workspace":normalized.substring(0,11)+normalized.substring(17));}
    private static String filePath(String path){String name=directory(path);validateName(name);return name;}
    private static void validateName(String name){
        if(name==null||name.isEmpty()||name.length()>240||name.contains(":")||name.contains("\\")||name.startsWith("/"))throw new IllegalArgumentException("工作区文件路径无效");String[] parts=name.split("/",-1);if(parts.length>16)throw new IllegalArgumentException("工作区路径过深");
        for(String part:parts)if(part.isBlank()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" ")||part.chars().anyMatch(c->c<32||c==127)||part.matches(".*[<>\"|?*].*")||part.toLowerCase(Locale.ROOT).matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))throw new IllegalArgumentException("工作区文件路径无效");
    }
}
