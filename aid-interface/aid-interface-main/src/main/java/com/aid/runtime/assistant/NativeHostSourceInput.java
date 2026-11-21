package com.aid.runtime.assistant;

import com.alibaba.fastjson2.*;
import java.util.*;

/** Compile source CAS from the host's verified observations; never read an unobserved live head. */
final class NativeHostSourceInput {
    private static final Set<String> WRITES=Set.of("aid.previz.source-write","aid.previz.source-edit");
    private static final Set<String> OBSERVERS=Set.of("aid.previz.source-write","aid.previz.source-edit","aid.previz.source-import","aid.previz.source-inspect","aid.previz.source-read","aid.previz.source-read-page");
    private NativeHostSourceInput() { }
    static JSONObject schema(JSONObject request,String code,JSONObject canonical){
        if(!WRITES.contains(code))return canonical;
        JSONObject context=request.getJSONObject("executionContext"),manifest=context==null?null:context.getJSONObject("manifest");
        JSONArray refs=manifest==null?null:manifest.getJSONArray("refs");String project=null,version=null;
        if(refs!=null)for(Object raw:refs){
            if(!(raw instanceof JSONObject row)||!"PREVIZ_PROJECT".equals(row.getString("kind")))continue;
            JSONObject ref=row.getJSONObject("canonicalRef");if(ref==null||project!=null)return canonical;
            project=ref.getString("previzProjectId");version=ref.getString("sourceVersion");
        }
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}")||version!=null&&!version.matches("[a-f0-9]{64}"))return canonical;
        long observed=0;
        // These slots are assembled only after Runtime receipt/approval/permission verification.
        var receipts=new ArrayList<Object>();JSONArray prior=request.getJSONArray("priorResults");if(prior!=null)receipts.addAll(prior);
        JSONArray observations=request.getJSONArray("observations");if(observations!=null)for(Object raw:observations){
            if(raw instanceof JSONObject row&&"SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(row.getString("authority"))&&"BUSINESS_RECEIPT".equals(row.getString("observationKind")))receipts.add(row);
        }
        for(Object raw:receipts){
            if(!(raw instanceof JSONObject row)||!"SUCCEEDED".equals(row.getString("status"))||!OBSERVERS.contains(row.getString("toolCode")))continue;
            JSONObject receipt=row.getJSONObject("observation");if(receipt==null||!project.equals(receipt.getString("projectId")))continue;
            if(!"PREVIZ_SOURCE_VERSION".equals(receipt.getString("kind"))&&!Boolean.TRUE.equals(receipt.get("isCurrentVersion"))
                &&!"aid.previz.source-inspect".equals(row.getString("toolCode")))continue;
            String candidate=receipt.getString("sourceVersion"),id=row.getString("stepId");
            if(candidate==null||!candidate.matches("[a-f0-9]{64}")||id==null||!id.matches("[1-9][0-9]{0,18}"))continue;
            long position;try{position=Long.parseLong(id);}catch(NumberFormatException invalid){continue;}
            if(position>observed){observed=position;version=candidate;}
        }
        // EDIT requires an existing base. A new project still uses the ordinary explicit contract.
        if(version==null&&"aid.previz.source-edit".equals(code))return canonical;
        JSONObject result=JSON.parseObject(canonical.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONObject properties=result.getJSONObject("properties"),field=properties==null?null:properties.getJSONObject("expectedVersion");
        if(field==null||!com.alibaba.fastjson2.schema.JSONSchema.of(field).validate(version).isSuccess())return canonical;
        JSONArray values=new JSONArray();values.add(version);field.put("enum",values);field.put("x-host-owned-default",true);
        return result;
    }
}
