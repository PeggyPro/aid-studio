package com.aid.previz.service;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.previz.workspace.PrevizWorkspaceFiles;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component @RequiredArgsConstructor @ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizFileWriteCompletionVerifier implements AssistantTargetWriteCompletionVerifier {
 private final PrevizWorkspaceFiles files;private final PrevizProjectService projects;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private PrevizFileWriteLineage lineage;
 @Override public Set<String> supportedToolCodes(){return Set.of(PrevizFileToolProvider.WRITE,PrevizFileToolProvider.COPY,PrevizFileToolProvider.EDIT);}
 @Override public void requireCurrentWrite(VerifiedRuntimeToolEffect effect){verify(effect,true);}
 public void requirePersistedWrite(VerifiedRuntimeToolEffect effect){verify(effect,false);}
 private void verify(VerifiedRuntimeToolEffect effect,boolean requireCurrent){
  try{
   if(effect==null||effect.userId()==null||effect.runId()==null||effect.stepId()==null||!supportedToolCodes().contains(effect.toolCode())||!"1.0.0".equals(effect.contractVersion())||effect.schemaDigest()==null||!effect.schemaDigest().matches("[a-f0-9]{64}")||effect.idempotencyKey()==null||effect.idempotencyKey().isBlank()||effect.outputArtifactRefs()==null||!effect.outputArtifactRefs().isEmpty())invalid();
   var identity=new RuntimeToolInvocation();identity.setUserId(effect.userId());identity.setRunId(effect.runId());identity.setContextManifestJson(effect.contextManifestJson());String project=PrevizRuntimeToolProvider.bound(identity).getString("previzProjectId");projects.requireWrite(effect.userId(),project);
   JSONObject output=JSON.parseObject(effect.outputJson()),input=JSON.parseObject(effect.preparedPayloadJson()==null?effect.argumentsJson():effect.preparedPayloadJson());
   if(output==null||input==null||!project.equals(output.getString("projectId")))invalid();String version=output.getString("workspaceVersion"),current=requireCurrent?files.current(effect.userId(),project):null;
   var ancestry=new java.util.concurrent.atomic.AtomicReference<String>();
   if(PrevizFileToolProvider.WRITE.equals(effect.toolCode())){if(!(input.get("content") instanceof String))invalid();verifyTarget(effect,project,version,current,input.getString("path"),input.getString("content").getBytes(StandardCharsets.UTF_8),ancestry);}
   else if(PrevizFileToolProvider.EDIT.equals(effect.toolCode())){
    byte[] original=files.readBytes(effect.userId(),project,input.getString("expectedVersion"),input.getString("path"));
    byte[] changed=com.aid.previz.workspace.PrevizSourceEdits.apply(original,input.getJSONArray("edit"),com.aid.previz.workspace.PrevizFileContentStore.MAX_FILE_BYTES).bytes();verifyTarget(effect,project,version,current,input.getString("path"),changed,ancestry);
   }
   else{
    if(output.getJSONArray("failed")==null||!output.getJSONArray("failed").isEmpty())invalid();
    var targets=files.copyTargets(effect.userId(),project,input.getString("expectedVersion"),input.getString("sourcePath"),input.getString("targetPath"));
    if(output.getJSONArray("succeeded")==null||output.getJSONArray("succeeded").size()!=targets.size())invalid();
    for(var target:targets.entrySet())verifyTarget(effect,project,version,current,"/workspace/files/"+target.getValue(),files.readBytes(effect.userId(),project,input.getString("expectedVersion"),"/workspace/files/"+target.getKey()),ancestry);
   }
  }catch(java.io.IOException|RuntimeException invalid){throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");}
 }
 private void verifyTarget(VerifiedRuntimeToolEffect effect,String project,String version,String current,String path,byte[] expected,java.util.concurrent.atomic.AtomicReference<String> ancestry)throws java.io.IOException{
  if(!Arrays.equals(expected,files.readBytes(effect.userId(),project,version,path)))invalid();
  if(current!=null&&!Arrays.equals(expected,files.readBytes(effect.userId(),project,current,path))){
   if(ancestry.get()==null)ancestry.set(lineage==null?"":Objects.toString(lineage.verifiedSuccessorVersion(effect,project,version),""));
   if(ancestry.get().isEmpty()||!Arrays.equals(files.readBytes(effect.userId(),project,ancestry.get(),path),files.readBytes(effect.userId(),project,current,path)))invalid();
  }
 }
 private static void invalid(){throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");}
}
