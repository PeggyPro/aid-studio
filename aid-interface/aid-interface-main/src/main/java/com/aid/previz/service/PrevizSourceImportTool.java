package com.aid.previz.service;

import com.aid.previz.workspace.*;
import com.aid.runtime.tool.*;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import java.util.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Explicit project-file to immutable build-source copy, with no model retranscription. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizSourceImportTool implements RuntimeToolProvider,AssistantTargetWriteCompletionVerifier,com.aid.assistant.service.AssistantToolReceiptProjector {
    public static final String CODE="aid.previz.source-import";
    private final PrevizWorkspaceFiles files;
    private final PrevizSourceStore sources;
    private final PrevizProjectService projects;
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors(){
        String input="""
            {"type":"object","required":["workspaceVersion","expectedSourceVersion","paths"],"properties":{
            "workspaceVersion":{"type":"string","pattern":"^[a-f0-9]{64}$"},
            "expectedSourceVersion":{"type":["string","null"],"pattern":"^[a-f0-9]{64}$"},
            "paths":{"type":"object","minProperties":1,"maxProperties":50,"additionalProperties":{"type":"string","minLength":1,"maxLength":240}}},"additionalProperties":false}
            """,output="{\"type\":\"object\"}";
        return List.of(new RuntimeToolRegistry.Descriptor(CODE,"1.0.0",cn.hutool.crypto.SecureUtil.sha256(input+"\n"+output),"复制文件到构建源码","将/workspace/files固定版本中的文件按字节复制到构建源码；paths键是完整虚拟文件路径，值是源码相对路径。保留未指定的原源码文件。一次原子CAS，单文件2MiB/快照8MiB，最多50项；不会执行源码或产生构建。先查询两侧版本，不猜测版本。",input,output,"DATABASE_WRITE",false,"IDEMPOTENCY_KEY",false,"RECONCILE_RECEIPT",List.of("previz.project")));
    }
    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext c,RuntimeToolRegistry.Descriptor d){return c!=null&&c.getUserId()!=null&&"PREVIZ_3DBOX".equals(c.getModuleCode())?RuntimeToolAvailability.enabled():RuntimeToolAvailability.unavailable("PREVIZ_PROJECT_REQUIRED");}
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor d){return OutputReferenceKind.DURABLE_RECEIPT;}
    @Override public boolean requiresExecutionCheckpoint(RuntimeToolRegistry.Descriptor d){return true;}
    @Override public String activitySubject(RuntimeToolRegistry.Descriptor d){return "复制文件到构建源码";}
    private String project(Long actor,Long run,String context){var i=new RuntimeToolInvocation();i.setUserId(actor);i.setRunId(run);i.setContextManifestJson(context);if(actor==null||run==null)throw new ServiceException("缺少运行身份");String p=PrevizRuntimeToolProvider.bound(i).getString("previzProjectId");projects.requireWrite(actor,p);return p;}
    private Map<String,String> canonicalPaths(JSONObject arguments){
        JSONObject raw=arguments==null?null:arguments.getJSONObject("paths");
        if(raw==null||raw.isEmpty()||raw.size()>50)throw new IllegalArgumentException("缺少复制映射");
        Map<String,String> paths=new TreeMap<>();
        for(var entry:raw.entrySet()){
            if(!(entry.getValue() instanceof String))throw new IllegalArgumentException("目标必须是源码相对路径");
            String canonical=PrevizWorkspaceFiles.canonicalFilePath(entry.getKey());
            if(paths.putIfAbsent(canonical,(String)entry.getValue())!=null)
                throw new IllegalArgumentException("多个来源指向同一文件");
        }
        return paths;
    }
    private JSONObject canonicalArguments(JSONObject arguments){
        JSONObject normalized=JSON.parseObject(arguments.toJSONString());
        normalized.put("paths",canonicalPaths(arguments));
        return normalized;
    }
    private RuntimeToolResult rejectedBeforeWrite(){
        RuntimeToolResult result=new RuntimeToolResult();result.setStatus("FAILED");
        result.setErrorCode("PREVIZ_SOURCE_IMPORT_INPUT_INVALID");result.setRetryable(false);
        result.setSafeSummary("源码导入参数未通过校验，本次未写入；已保存的项目文件仍可继续使用。");
        return result;
    }
    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation){
        try{
            String p=project(invocation.getUserId(),invocation.getRunId(),invocation.getContextManifestJson());
            JSONObject arguments=canonicalArguments(JSON.parseObject(invocation.getArgumentsJson()));
            expected(invocation.getUserId(),p,arguments);
            return new RuntimeToolPreparation(arguments.toJSONString(),null,invocation.getOperationScope());
        }catch(IllegalArgumentException|IOException invalid){
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,invalid);
        }
    }
    private Map<String,byte[]> expected(Long actor,String p,JSONObject a)throws IOException{
        JSONObject raw=a.getJSONObject("paths");if(raw==null)throw new IllegalArgumentException("缺少复制映射");Map<String,String> paths=new TreeMap<>();for(var e:raw.entrySet()){if(!(e.getValue() instanceof String))throw new IllegalArgumentException("目标必须是源码相对路径");paths.put(e.getKey(),(String)e.getValue());}
        return PrevizSourceImport.prepare(files,sources,actor,p,a.getString("workspaceVersion"),a.getString("expectedSourceVersion"),paths);
    }
    @Override public RuntimeToolResult execute(RuntimeToolInvocation i){String p=project(i.getUserId(),i.getRunId(),i.getContextManifestJson());JSONObject a=JSON.parseObject(i.getArgumentsJson());
        Map<String,byte[]> content;
        try{
        // A persisted pre-upgrade payload was never normalized in preparation. Never execute it
        // under the new implementation and then mistake its old UNKNOWN receipt for a new write.
        if(!canonicalPaths(a).keySet().equals(a.getJSONObject("paths").keySet()))return rejectedBeforeWrite();
        content=expected(i.getUserId(),p,a);
        }catch(IllegalArgumentException invalid){return rejectedBeforeWrite();}
        catch(IOException unavailable){
            if("WORKSPACE_FILE_NOT_FOUND".equals(unavailable.getMessage()))return rejectedBeforeWrite();
            return RuntimeToolResult.unknown("SOURCE_IMPORT_PREPARATION_UNKNOWN","源码读取结果待核对，未重复写入");
        }
        try{String v=sources.save(i.getUserId(),p,a.getString("expectedSourceVersion"),content);
        return RuntimeToolResult.succeeded(JSONObject.of("projectId",p,"sourceVersion",v,"importedPaths",a.getJSONObject("paths").values(),"fileCount",content.size()).toJSONString());
    }catch(IOException error){if(error instanceof PrevizSourceStore.SourceQuotaExceededException){var r=new RuntimeToolResult();r.setStatus("FAILED");r.setErrorCode("PREVIZ_SOURCE_QUOTA_EXCEEDED");r.setRetryable(false);r.setSafeSummary("工程源码容量已达上限，本次未复制；请联系管理员调整容量");return r;}if(Objects.toString(error.getMessage(),"").startsWith("SOURCE_VERSION_CONFLICT")){var r=new RuntimeToolResult();r.setStatus("FAILED");r.setErrorCode("SOURCE_VERSION_CONFLICT");r.setRetryable(false);r.setSafeSummary("源码版本已变化，本次未覆盖，请重新读取");return r;}return RuntimeToolResult.unknown("SOURCE_IMPORT_RECEIPT_PENDING","复制回执待核对，不重复写入源码");}}
    private boolean equal(Map<String,byte[]> a,Map<String,byte[]> b){return a.keySet().equals(b.keySet())&&a.entrySet().stream().allMatch(e->Arrays.equals(e.getValue(),b.get(e.getKey())));}
    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation i){String p=project(i.getUserId(),i.getRunId(),i.getContextManifestJson());try{JSONObject a=JSON.parseObject(i.getPreparedPayloadJson());
        // The pre-upgrade filePath rejected a source outside this exact prefix before
        // sources.save. Do not classify merely noncanonical in-prefix paths as failed:
        // for example, the old reader accepted a trailing slash on a file name.
        if(a!=null&&a.getJSONObject("paths")!=null)
            for(String path:a.getJSONObject("paths").keySet())
                if(path==null||!path.startsWith("/workspace/files/"))return rejectedBeforeWrite();
        String current=sources.currentVersion(i.getUserId(),p);var expected=expected(i.getUserId(),p,a);if(current!=null&&equal(expected,sources.read(i.getUserId(),p,current)))return RuntimeToolResult.succeeded(JSONObject.of("projectId",p,"sourceVersion",current,"importedPaths",a.getJSONObject("paths").values(),"fileCount",expected.size()).toJSONString());}catch(IOException|IllegalArgumentException ignored){}return RuntimeToolResult.unknown("SOURCE_IMPORT_RECEIPT_PENDING","源码回执不可确认，不重复复制");}
    @Override public Set<String> supportedToolCodes(){return Set.of(CODE);}
    @Override public void requireCurrentWrite(VerifiedRuntimeToolEffect e){verify(e,true);}
    private void verify(VerifiedRuntimeToolEffect e,boolean requireCurrent){try{
        if(e==null||!CODE.equals(e.toolCode())||!"1.0.0".equals(e.contractVersion())||e.stepId()==null||e.idempotencyKey()==null||e.idempotencyKey().isBlank()||!descriptors().get(0).schemaDigest().equals(e.schemaDigest())||e.outputArtifactRefs()==null||!e.outputArtifactRefs().isEmpty())throw new IllegalArgumentException();
        String p=project(e.userId(),e.runId(),e.contextManifestJson());JSONObject out=JSON.parseObject(e.outputJson()),a=JSON.parseObject(e.preparedPayloadJson()==null?e.argumentsJson():e.preparedPayloadJson());
        if(out==null||!p.equals(out.getString("projectId")))throw new IllegalArgumentException();String version=out.getString("sourceVersion");
        if(version==null||requireCurrent&&!version.equals(sources.currentVersion(e.userId(),p))||!equal(expected(e.userId(),p,a),sources.read(e.userId(),p,version)))throw new IllegalArgumentException();
    }catch(IOException|RuntimeException invalid){throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");}}
    @Override public JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect e){
        verify(e,false);JSONObject out=JSON.parseObject(e.outputJson()),input=JSON.parseObject(e.preparedPayloadJson()==null?e.argumentsJson():e.preparedPayloadJson());JSONArray paths=new JSONArray();
        for(Object path:input.getJSONObject("paths").values()){paths.add(path);if(JSON.toJSONBytes(paths).length>6000){paths.remove(paths.size()-1);break;}}
        return JSONObject.of("kind","PREVIZ_SOURCE_VERSION","projectId",out.getString("projectId"),"sourceVersion",out.getString("sourceVersion"),"paths",paths,"pathsTruncated",paths.size()<input.getJSONObject("paths").size());
    }
}
