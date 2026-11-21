package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Preview reads an already verified build, never reruns model-authored source. */
@Service
@ConditionalOnProperty(prefix="aid.previz.local-worker",name="enabled",havingValue="true")
public final class PrevizPreviewService {
    private final PrevizArtifactService artifacts;
    private final PrevizReferenceImageStore images;
    private final PrevizProjectService projects;
    private final PrevizEditorStore editors;
    private final Path node,entry,profile,root;
    private final PrevizComputeDispatchService dispatch;
    private static final Object[] LOCKS=new Object[32];
    static {for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object();}
    public PrevizPreviewService(PrevizArtifactService artifacts,PrevizReferenceImageStore images,PrevizProjectService projects,PrevizEditorStore editors,
       @Value("${aid.previz.local-worker.node}") String node,@Value("${aid.previz.local-worker.entry}") String entry,
       @Value("${aid.previz.local-worker.profile}") String profile,@Value("${aid.previz.local-worker.requests-root}") String root,PrevizComputeDispatchService dispatch) throws java.io.IOException {
        this.artifacts=artifacts;this.images=images;this.projects=projects;this.editors=editors;
        this.dispatch=dispatch;
        this.node=Path.of(node);this.entry=Path.of(entry).resolveSibling("preview-cli.mjs");this.profile=Path.of(profile);
        this.root=Path.of(root).resolve("previews");Files.createDirectories(this.root);
        if(!this.root.isAbsolute()||Files.isSymbolicLink(this.root)||!Files.isRegularFile(this.entry))throw new IllegalArgumentException("预览节点配置无效");
    }
    public JSONObject preview(Long actor,String project,Long buildStep,JSONObject options) throws java.io.IOException,InterruptedException {
        return preview(actor,project,buildStep,options,"http-preview-v2");
    }
    private String key(String project,Long step,JSONObject options,String operation) {
        return cn.hutool.crypto.SecureUtil.sha256(project+":"+step+":"+JSON.toJSONString(options,com.alibaba.fastjson2.JSONWriter.Feature.MapSortField)+":"+operation);
    }
    public JSONObject lookup(Long actor,String project,Long step,JSONObject options,String operation) throws java.io.IOException {
        synchronized(LOCKS[Math.floorMod(key(project,step,options,operation).hashCode(),LOCKS.length)]) {
            return lookupLocked(actor,project,step,options,operation);
        }
    }
    private JSONObject lookupLocked(Long actor,String project,Long step,JSONObject options,String operation) throws java.io.IOException {
        projects.requireWrite(actor,project);artifacts.manifest(actor,project,step);
        Path folder=root.resolve(key(project,step,options,operation));
        if(!Files.exists(folder))folder=root.resolve(key(project,step,options,"preview-v1")); // legacy local acceptance attempt
        Path receipt=folder.resolve("receipt.json"),log=folder.resolve("process.log");
        if(Files.exists(receipt)) {
            if(Files.isSymbolicLink(receipt)||Files.size(receipt)>65536)throw new ServiceException("预览回执无效");
            JSONObject result=JSON.parseObject(Files.readString(receipt));
            if(!"FAILED".equals(result.getString("state")))images.read(actor,project,result.getString("imageId"));
            dispatch.reconcileExisting("preview-"+key(project,step,options,operation));
            return result;
        }
        if(Files.isRegularFile(log)&&!Files.isSymbolicLink(log)&&Files.size(log)<65536) {
            try {
                JSONObject event=JSON.parseObject(Files.readString(log,StandardCharsets.UTF_8).strip());
                if(event!=null&&"error".equals(event.getString("type"))&&!"UNKNOWN".equals(event.getString("state")))return JSONObject.of("state","FAILED","errorCode",event.getString("code"),"projectId",project);
                if(event!=null&&"result".equals(event.getString("type")))return archive(actor,project,step,folder,event.getJSONObject("result"),artifacts.manifest(actor,project,step));
            }catch(com.alibaba.fastjson2.JSONException incomplete){ /* running output is not a terminal receipt */ }
        }
        Path request=folder.resolve("request.json");
        if(!Files.exists(request))try(var route=dispatch.route("preview-"+key(project,step,options,operation),project,"blender","preview",null,false)){
            if(route.waiting()||route.managed()&&dispatch.binding(route.requestKey()).getWorkerJobId()==null)return queued(project,route);
        }
        if(Files.isRegularFile(request)&&!Files.isSymbolicLink(request)&&Files.exists(folder.resolve("remote-preview-claim.json"))) {
            try(var route=dispatch.route("preview-"+key(project,step,options,operation),project,"blender","preview",null,false)) {
            if(route.waiting())return queued(project,route);
            JSONObject config=JSON.parseObject(Files.readString(route.profile()));
            if("REMOTE_COMPUTE".equals(config.getString("mode"))&&config.getBooleanValue("enabled")) {
                Path recoveryLog=folder.resolve("lookup.log");
                if(Files.isSymbolicLink(recoveryLog))throw new ServiceException("预览核对日志位置无效");
                ProcessBuilder builder=new ProcessBuilder(node.toString(),entry.resolveSibling("remote-preview-cli.mjs").toString(),route.profile().toString(),request.toString(),"--lookup-only");
                builder.directory(folder.toFile());builder.environment().keySet().removeIf(k->!Set.of("PATH","SYSTEMROOT","WINDIR","COMSPEC","PATHEXT").contains(k.toUpperCase(java.util.Locale.ROOT)));
                builder.environment().put("TEMP",folder.toString());builder.environment().put("TMP",folder.toString());
                builder.redirectErrorStream(true).redirectOutput(recoveryLog.toFile());Process process=builder.start();
                try {
                    if(!process.waitFor(40,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();return null;}
                }catch(InterruptedException interrupted){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();Thread.currentThread().interrupt();throw new java.io.IOException("预览核对中断",interrupted);}
                if(Files.size(recoveryLog)>65536)throw new ServiceException("预览核对回执超限");
                JSONObject event=JSON.parseObject(Files.readString(recoveryLog,StandardCharsets.UTF_8).strip());
                dispatch.reconcile(route);
                if("result".equals(event.getString("type")))return archive(actor,project,step,folder,event.getJSONObject("result"),artifacts.manifest(actor,project,step));
                if(!"UNKNOWN".equals(event.getString("state")))return JSONObject.of("state","FAILED","errorCode",event.getString("code"),"projectId",project);
            }
            }
        }
        return null;
    }
    public JSONObject preview(Long actor,String project,Long buildStep,JSONObject options,String operation) throws java.io.IOException,InterruptedException {
        projects.requireWrite(actor,project);
        if(options==null||!Set.of("width","height","time","animationIndex","view","cameraId","inspectIds","exposureEV").containsAll(options.keySet()))throw new ServiceException("预览参数字段无效");
        JSONObject manifest=artifacts.manifest(actor,project,buildStep);
        if(operation==null||operation.length()>500)throw new ServiceException("缺少预览操作标识");
        String key=key(project,buildStep,options,operation);
        synchronized(LOCKS[Math.floorMod(key.hashCode(),LOCKS.length)]) {
            Path folder=root.resolve(key);Files.createDirectories(folder);
            if(Files.isSymbolicLink(folder))throw new ServiceException("预览目录无效");
            Path receipt=folder.resolve("receipt.json");
            if(Files.exists(receipt)) {
                if(Files.isSymbolicLink(receipt)||Files.size(receipt)>65536)throw new ServiceException("预览回执无效");
                JSONObject result=JSON.parseObject(Files.readString(receipt));
                if(!"FAILED".equals(result.getString("state")))images.read(actor,project,result.getString("imageId"));return result;
            }
            if(Files.exists(folder.resolve("request.json"))) {
                JSONObject known=lookup(actor,project,buildStep,options,operation);
                if(known!=null)return known;
                return pending(project);
            }
            JSONObject artifact=artifacts.read(actor,project,buildStep);
            try(var route=dispatch.route("preview-"+key,project,"blender","preview",artifact.getString("jobId"),true)) {
            if(route.waiting())return queued(project,route);
            JSONObject config=JSON.parseObject(Files.readString(route.profile()));
            boolean remote="REMOTE_COMPUTE".equals(config.getString("mode"));
            if((!remote&&!"LOCAL_DEVELOPMENT_PROCESS".equals(config.getString("mode")))||!config.getBooleanValue("enabled"))throw new ServiceException("预览计算节点未启用");
            Path glb=folder.resolve("scene.glb");
            if(!remote)writeOnce(glb,java.util.Base64.getDecoder().decode(artifact.getString("contentBase64")));
            Path request=folder.resolve("request.json"),log=folder.resolve("process.log");
            JSONObject input=JSONObject.of("glbPath",glb.toString(),"sha256",manifest.getJSONObject("artifact").getString("sha256"),"options",options);
            if(manifest.getString("editorVersion")!=null) {
                JSONObject state=editors.read(actor,project,manifest.getString("editorVersion"));
                for(Object raw:state.getJSONArray("nodes"))if(raw instanceof JSONObject item && item.getString("id").equals(state.getString("targetNodeId"))) {
                    if(item.getJSONObject("assetOverrides")!=null)input.put("editorOverlay",item.getJSONObject("assetOverrides"));
                    if(item.getJSONObject("localTransform")!=null)input.put("editorTransform",item.getJSONObject("localTransform"));
                    if(item.getJSONObject("assetPlayback")!=null)input.put("editorPlayback",item.getJSONObject("assetPlayback"));
                }
            }
            JSONObject host=JSONObject.of("executionRoot",folder.toString(),"blenderExecutable",config.getString("blenderExecutable"),"localDevelopmentEnabled",true);
            JSONObject requestBody=JSONObject.of("input",input,"host",host);
            if(remote) {
                JSONObject job=JSONObject.of("jobId","preview-"+key,"projectId",project,"buildJobId",artifact.getString("jobId"),
                    "sourceSha256",artifact.getString("sourceSha256"),"artifactSha256",manifest.getJSONObject("artifact").getString("sha256"),"options",options);
                for(String field:java.util.List.of("editorOverlay","editorTransform","editorPlayback"))if(input.containsKey(field))job.put(field,input.get(field));
                requestBody=JSONObject.of("job",job);
            }
            writeOnce(request,requestBody.toJSONString().getBytes(StandardCharsets.UTF_8));
            if(Files.isSymbolicLink(log))throw new ServiceException("预览日志位置无效");
            ProcessBuilder builder=remote?new ProcessBuilder(node.toString(),entry.resolveSibling("remote-preview-cli.mjs").toString(),route.profile().toString(),request.toString())
                :new ProcessBuilder(node.toString(),entry.toString(),request.toString());
            builder.directory(folder.toFile());builder.environment().keySet().removeIf(k->!Set.of("PATH","SYSTEMROOT","WINDIR","COMSPEC","PATHEXT").contains(k.toUpperCase(java.util.Locale.ROOT)));
            builder.environment().put("TEMP",folder.toString());builder.environment().put("TMP",folder.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            dispatch.beforeSubmission(route);Process process=builder.start();
            try {if(!process.waitFor(330,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();return pending(project);}}
            catch(InterruptedException interrupted){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw interrupted;}
            if(Files.size(log)>65536)throw new ServiceException("预览回执超限");
            JSONObject event=JSON.parseObject(Files.readString(log,StandardCharsets.UTF_8).strip());
            dispatch.reconcile(route);
            if(process.exitValue()!=0||!"result".equals(event.getString("type"))) {
                if("UNKNOWN".equals(event.getString("state")))return pending(project);
                JSONObject failure=JSONObject.of("state","FAILED","errorCode",event.getString("code"),"projectId",project);
                Files.writeString(receipt,failure.toJSONString(),StandardOpenOption.CREATE_NEW);return failure;
            }
            return archive(actor,project,buildStep,folder,event.getJSONObject("result"),manifest);
            }
        }
    }
    private static JSONObject pending(String project) {
        return JSONObject.of("state","UNKNOWN","errorCode","PREVIEW_RECEIPT_PENDING","projectId",project);
    }
    private static JSONObject queued(String project,PrevizComputeDispatchService.Route route){return JSONObject.of(
            "state","QUEUED","errorCode","COMPUTE_CAPACITY_WAIT","projectId",project,
            "queuePosition",route.queuePosition(),"message",route.waitingMessage());}

    public void cancelQueued(Long actor,String project,Long buildStep,JSONObject options,String operation){
        if(actor==null||actor<=0)throw new ServiceException("缺少排队释放身份",409);
        dispatch.cancelUnsubmitted("preview-"+key(project,buildStep,options,operation),project);
    }
    private JSONObject archive(Long actor,String project,Long buildStep,Path folder,JSONObject result,JSONObject manifest) throws java.io.IOException {
            Path receipt=folder.resolve("receipt.json"),image=Path.of(result.getString("imagePath"));
            if(Files.isSymbolicLink(image)||!image.toRealPath().startsWith(folder.toRealPath())||Files.size(image)>PrevizReferenceImageStore.MAX_PREVIEW_BYTES)throw new ServiceException("预览图位置或大小无效");
            byte[] bytes=Files.readAllBytes(image);
            if(!cn.hutool.crypto.SecureUtil.sha256(new java.io.ByteArrayInputStream(bytes)).equals(result.getString("sha256")))throw new ServiceException("预览图指纹不一致");
            var stored=images.saveGeneratedPreview(actor,project,bytes);
            result.remove("outputDirectory");result.remove("imagePath");result.put("projectId",project);result.put("buildStepId",String.valueOf(buildStep));result.put("imageId",stored.id());
            result.put("visualReviewed",false);result.put("sourceVersionId",manifest.getString("sourceVersionId"));
            projects.requireWrite(actor,project);
            writeOnce(receipt,result.toJSONString().getBytes(StandardCharsets.UTF_8));
            return result;
    }
    private static void writeOnce(Path file,byte[] data) throws java.io.IOException {
        try {Files.write(file,data,StandardOpenOption.CREATE_NEW);}
        catch(FileAlreadyExistsException existing) {
            if(Files.isSymbolicLink(file)||Files.size(file)!=data.length||!java.util.Arrays.equals(Files.readAllBytes(file),data))
                throw new ServiceException("预览输入已变化");
        }
    }
}
