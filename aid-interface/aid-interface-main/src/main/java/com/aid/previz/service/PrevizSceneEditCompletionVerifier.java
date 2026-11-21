package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSceneEdits;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Register the existing TARGET_WRITE evidence route; a text promise cannot complete an edit. */
@Component
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizSceneEditCompletionVerifier implements AssistantTargetWriteCompletionVerifier,com.aid.assistant.service.AssistantToolReceiptProjector {
    private final PrevizProjectService projects;
    private final PrevizSceneEditService edits;
    private final PrevizDocumentStore documents;
    public PrevizSceneEditCompletionVerifier(PrevizProjectService projects,PrevizSceneEditService edits,PrevizDocumentStore documents){this.projects=projects;this.edits=edits;this.documents=documents;}
    @Override public Set<String> supportedToolCodes(){return Set.of(PrevizSceneEditToolProvider.CODE);}
    @Override public void requireCurrentWrite(VerifiedRuntimeToolEffect effect){requireCompletedWrites(List.of(effect));}
    @Override public void requireCompletedWrites(List<VerifiedRuntimeToolEffect> effects){
        verify(effects,true);
    }
    @Override public JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect effect){
        verify(List.of(effect),false);
        JSONObject saved=JSON.parseObject(effect.outputJson());
        return JSONObject.of("kind","PREVIZ_SCENE_EDIT","state","APPLIED","projectId",saved.get("projectId"),"operationId",saved.get("operationId"),
                "documentVersion",saved.get("documentVersion"),"baseDocumentVersion",saved.get("baseDocumentVersion"),"editorVersion",saved.get("editorVersion"),"rebuilt",false);
    }
    private void verify(List<VerifiedRuntimeToolEffect> effects,boolean requireCurrent){
        if(effects==null||effects.isEmpty()||effects.size()>100||effects.stream().anyMatch(Objects::isNull))invalid();
        if(effects.stream().anyMatch(effect->effect.stepId()==null))invalid();
        var sorted=new ArrayList<>(effects);sorted.sort(Comparator.comparing(VerifiedRuntimeToolEffect::stepId));
        Map<String,Map<String,JSONObject>> latest=new HashMap<>();var first=sorted.get(0);
        Set<Long> stepIds=new HashSet<>();
        try{
            for(var effect:sorted){
                if(effect==null||effect.userId()==null||effect.sessionId()==null||effect.runId()==null||effect.stepId()==null||!supportedToolCodes().contains(effect.toolCode())
                        ||!"1.0.0".equals(effect.contractVersion())||effect.idempotencyKey()==null||effect.idempotencyKey().isBlank()
                        ||effect.schemaDigest()==null||!effect.schemaDigest().matches("[a-f0-9]{64}")
                        ||effect.outputArtifactRefs()==null||!effect.outputArtifactRefs().isEmpty()||!stepIds.add(effect.stepId())
                        ||!Objects.equals(first.userId(),effect.userId())||!Objects.equals(first.runId(),effect.runId())||!Objects.equals(first.sessionId(),effect.sessionId()))invalid();
                JSONObject output=JSON.parseObject(effect.outputJson()),input=JSON.parseObject(effect.preparedPayloadJson());
                String project=input.getString("projectId"),operation=SecureUtil.sha256(effect.idempotencyKey());
                var invocation=new com.aid.runtime.tool.RuntimeToolInvocation();invocation.setContextManifestJson(effect.contextManifestJson());
                if(!Objects.equals(project,PrevizRuntimeToolProvider.bound(invocation).getString("previzProjectId"))||!"APPLIED".equals(output.getString("state"))||!Objects.equals(project,output.getString("projectId"))||!operation.equals(output.getString("operationId"))||!Objects.equals(input.get("editorVersion"),output.get("editorVersion")))invalid();
                projects.requireWrite(effect.userId(),project);JSONObject receipt=edits.reconcile(effect.userId(),project,operation);
                if(receipt==null||!"APPLIED".equals(receipt.getString("state"))||!Objects.equals(output.get("documentVersion"),receipt.get("documentVersion")))invalid();
                JSONObject persisted=documents.read(effect.userId(),project,receipt.getString("documentVersion")).getJSONObject("document").getJSONObject("scene");
                if(!PrevizSceneEdits.satisfied(persisted,input.getJSONArray("edits")))invalid();
                var byField=latest.computeIfAbsent(project,key->new LinkedHashMap<>());
                for(Object raw:input.getJSONArray("edits")){
                    JSONObject edit=(JSONObject)raw;String key=JSON.toJSONString(Arrays.asList(edit.get("nodeId"),edit.get("objectId"),edit.get("field"),edit.getBooleanValue("keyframe")||edit.getBooleanValue("existingTrack")?edit.get("frame"):null));
                    if(edit.get("value") instanceof JSONArray values){
                        for(int axis=0;axis<3;axis++)if(Math.abs(values.getDoubleValue(axis)-edit.getJSONArray("baseValue").getDoubleValue(axis))>1e-6){
                            JSONObject check=JSON.parseObject(edit.toJSONString());check.put("requiredAxes",JSONArray.of(axis==0,axis==1,axis==2));byField.put(key+"/"+axis,check);
                        }
                        if(edit.getBooleanValue("keyframe"))byField.put(key+"/key",edit);
                    }else byField.put(key,edit);
                }
            }
            if(requireCurrent)for(var entry:latest.entrySet()){
                JSONObject current=documents.read(first.userId(),entry.getKey(),null).getJSONObject("document").getJSONObject("scene");var commands=new JSONArray();commands.addAll(entry.getValue().values());
                if(!PrevizSceneEdits.satisfied(current,commands))invalid();
            }
        }catch(java.io.IOException|RuntimeException unavailable){throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");}
    }
    private static void invalid(){throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");}
}
