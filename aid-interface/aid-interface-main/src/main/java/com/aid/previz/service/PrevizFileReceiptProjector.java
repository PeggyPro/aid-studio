package com.aid.previz.service;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.service.AssistantToolReceiptProjector;
import com.alibaba.fastjson2.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Validates immutable historical writes and supplies bounded receipts to subsequent model rounds. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizFileReceiptProjector implements AssistantToolReceiptProjector {
 private final PrevizFileWriteCompletionVerifier writes;
 @Override public Set<String> supportedToolCodes(){return writes.supportedToolCodes();}
 @Override public JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect effect){
  writes.requirePersistedWrite(effect);JSONObject output=JSON.parseObject(effect.outputJson());
  JSONArray paths=new JSONArray();int total;
  if(PrevizFileToolProvider.COPY.equals(effect.toolCode())){JSONArray saved=output.getJSONArray("succeeded");total=saved.size();for(Object item:saved){String path=((JSONObject)item).getString("path");paths.add(path);if(JSON.toJSONBytes(paths).length>6000){paths.remove(paths.size()-1);break;}}}
  else{JSONObject input=JSON.parseObject(effect.preparedPayloadJson()==null?effect.argumentsJson():effect.preparedPayloadJson());paths.add(input.getString("path"));total=1;}
  return JSONObject.of("kind","PREVIZ_WORKSPACE_FILES","projectId",output.getString("projectId"),"workspaceVersion",output.getString("workspaceVersion"),"paths",paths,"fileCount",total,"pathsTruncated",paths.size()<total);
 }
}
