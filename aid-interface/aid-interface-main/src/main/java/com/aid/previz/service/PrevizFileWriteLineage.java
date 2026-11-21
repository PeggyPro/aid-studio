package com.aid.previz.service;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.assistant.execution.AssistantCompletedToolScopeVerifier;
import com.aid.previz.workspace.PrevizWorkspaceFiles;
import com.alibaba.fastjson2.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Only verified later writes in the same run may supersede an earlier target-write criterion. */
@Component
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizFileWriteLineage {
 @Autowired private AidRuntimeRunMapper runs;
 @Autowired private AidRuntimeRunStepMapper steps;
 @Autowired private AssistantCompletedToolScopeVerifier completed;
 @Autowired private PrevizWorkspaceFiles files;
 @Autowired private org.springframework.beans.factory.ObjectProvider<PrevizFileWriteCompletionVerifier> writeProvider;
 public String verifiedSuccessorVersion(VerifiedRuntimeToolEffect original,String project,String version)throws IOException {
  var writes=writeProvider.getObject();var run=runs.selectById(original.runId());if(run==null||!Objects.equals(run.getUserId(),original.userId()))return null;
  var candidates=steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery().eq(AidRuntimeRunStep::getRunId,original.runId()).eq(AidRuntimeRunStep::getDelFlag,"0").eq(AidRuntimeRunStep::getStatus,"SUCCEEDED").in(AidRuntimeRunStep::getToolCode,writes.supportedToolCodes()).gt(AidRuntimeRunStep::getId,original.stepId()).orderByAsc(AidRuntimeRunStep::getId).last("LIMIT 257"));
  if(candidates.size()>256)return null;
  String reached=version;
  for(var step:candidates){
   var proof=completed.require(original.userId(),run,step);JSONObject input=JSON.parseObject(proof.preparedPayloadJson()==null?proof.argumentsJson():proof.preparedPayloadJson());
   if(!Objects.equals(reached,input.getString("expectedVersion")))continue;
   writes.requirePersistedWrite(proof);JSONObject output=JSON.parseObject(proof.outputJson());
   if(!project.equals(output.getString("projectId")))return null;reached=output.getString("workspaceVersion");
  }
  return Objects.equals(reached,version)?null:reached;
 }
}
