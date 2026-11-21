package com.aid.runtime.assistant;

import com.alibaba.fastjson2.*;
import java.util.*;

/** Bind a preview only when its approved dependency graph identifies exactly one verified build. */
final class NativeHostPreviewInput {
    private NativeHostPreviewInput() { }
    static JSONObject schema(JSONObject request, JSONObject step, JSONObject canonical) {
        String code=step.getString("bindingCode");
        if ("aid.previz.build-scoped".equals(code)) return buildScopeSchema(canonical);
        if (Set.of("aid.previz.source-write", "aid.previz.source-edit").contains(code))
            return sourceExportSchema(canonical);
        if (!Set.of("aid.previz.render-preview", "aid.previz.preview").contains(step.getString("bindingCode"))) return canonical;
        JSONObject plan=request.getJSONObject("currentPlan");
        JSONArray steps=plan==null?null:plan.getJSONArray("steps"),prior=request.getJSONArray("priorResults");
        if(steps==null||prior==null||steps.size()>100)return canonical;
        JSONObject execution=request.getJSONObject("executionContext"),manifest=execution==null?null:execution.getJSONObject("manifest");
        JSONArray refs=manifest==null?null:manifest.getJSONArray("refs");String project=null;
        if(refs!=null)for(Object raw:refs)if(raw instanceof JSONObject ref&&"PREVIZ_PROJECT".equals(ref.getString("kind"))){
            JSONObject target=ref.getJSONObject("canonicalRef");String id=target==null?null:target.getString("previzProjectId");
            if(id==null||project!=null&&!project.equals(id))return canonical;project=id;}
        if(project==null)return canonical;
        Map<String,JSONObject> byId=new HashMap<>();for(Object raw:steps)if(raw instanceof JSONObject item)byId.put(item.getString("stableStepId"),item);
        Set<String> ancestors=new HashSet<>();Deque<String> pending=new ArrayDeque<>();
        if(step.getJSONArray("dependsOn")!=null)for(Object id:step.getJSONArray("dependsOn"))if(id instanceof String text)pending.add(text);
        while(!pending.isEmpty()){String id=pending.remove();if(id.equals(step.getString("stableStepId")))return canonical;if(!ancestors.add(id))continue;JSONObject parent=byId.get(id);if(parent==null)return canonical;
            if(parent.getJSONArray("dependsOn")!=null)for(Object next:parent.getJSONArray("dependsOn"))if(next instanceof String text)pending.add(text);}
        JSONObject selected=null;String buildId=null;
        for(Object raw:prior){if(!(raw instanceof JSONObject row)||!"SUCCEEDED".equals(row.getString("status"))
                ||!("aid.previz.build".equals(row.getString("toolCode"))||"aid.previz.build-scoped".equals(row.getString("toolCode")))||!ancestors.contains(row.getString("stableStepId"))
                ||!Objects.equals(plan.getLong("revision"),row.getLong("planRevision")))continue;
            JSONObject receipt=row.getJSONObject("observation");String id=row.getString("stepId");
            if(receipt==null||!project.equals(receipt.getString("projectId"))||!"BUILT".equals(receipt.getString("state"))||id==null||!id.matches("[1-9][0-9]{0,18}"))continue;
            if(selected!=null&&!Objects.equals(id,buildId))return canonical;selected=receipt;buildId=id;}
        if(selected==null)return canonical;
        JSONObject artifact=selected.getJSONObject("artifact");JSONArray cameras=artifact==null?null:artifact.getJSONArray("cameraOptions");
        // Old or partial inventories cannot restrict the available camera choices.
        if(artifact==null||!Boolean.TRUE.equals(artifact.get("cameraOptionsComplete"))||cameras==null||cameras.size()>32)return canonical;
        JSONArray ids=new JSONArray();for(Object raw:cameras){if(!(raw instanceof JSONObject camera)||!(camera.get("id") instanceof String id)
                ||id.isBlank()||id.length()>200||ids.contains(id))return canonical;ids.add(id);}
        JSONObject result=JSON.parseObject(canonical.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONObject properties=result.getJSONObject("properties"),build=properties==null?null:properties.getJSONObject("buildStepId");
        JSONObject options=properties==null?null:properties.getJSONObject("options"),optionFields=options==null?null:options.getJSONObject("properties");
        if(build==null||optionFields==null||optionFields.getJSONObject("cameraId")==null)return canonical;
        build.put("enum",JSONArray.of(buildId));build.put("x-host-owned-default",true);
        if(ids.isEmpty())optionFields.remove("cameraId");else{
            optionFields.getJSONObject("cameraId").put("enum",ids);
            optionFields.getJSONObject("cameraId").put("description","Camera hf_id in this verified build's cameraOptions; editor node IDs are a different namespace. Omit for a directional view.");
        }
        return result;
    }

    /** Narrow the published object shape to the four operation rules enforced before a build. */
    private static JSONObject buildScopeSchema(JSONObject canonical) {
        JSONObject result=copy(canonical),operation=result.getJSONObject("properties").getJSONObject("operation");
        JSONObject fields=operation.getJSONObject("properties");
        JSONArray alternatives=new JSONArray();
        for (String kind:List.of("ADD", "MODIFY", "REPLACE_SCOPE", "REBUILD_SCENE")) {
            JSONObject properties=copy(fields);
            properties.getJSONObject("kind").put("enum",JSONArray.of(kind));
            JSONObject targets=properties.getJSONObject("targetNodeIds"),outputs=properties.getJSONObject("outputObjectIds");
            targets.put("uniqueItems",true);outputs.put("uniqueItems",true);
            JSONArray required=JSONArray.of("kind");
            if ("ADD".equals(kind) || "REBUILD_SCENE".equals(kind)) {
                targets.put("maxItems",0);
                targets.put("description","Omit or send []; the host determines the full rebuild baseline from the pinned editor snapshot. Never copy observed node IDs into this field for ADD or REBUILD_SCENE.");
            } else {
                required.add("targetNodeIds");targets.put("minItems",1);
                if ("MODIFY".equals(kind)) targets.put("maxItems",1);
            }
            if ("ADD".equals(kind) || "REPLACE_SCOPE".equals(kind)) {
                required.add("outputObjectIds");outputs.put("minItems",1);
            }
            alternatives.add(JSONObject.of("type","object","properties",properties,"required",required,"additionalProperties",false));
        }
        operation.remove("properties");operation.remove("required");operation.remove("additionalProperties");
        operation.put("oneOf",alternatives);
        operation.put("description","Choose exactly one operation. ADD adds new output roots and has no targets. MODIFY names one observed GLB node. REPLACE_SCOPE names observed targets and new output roots. REBUILD_SCENE has no caller-supplied targets: the host derives all replaceable geometry and rejects locked objects. This never grants permission to alter unobserved or locked objects.");
        return result;
    }

    private static JSONObject sourceExportSchema(JSONObject canonical) {
        JSONObject result=copy(canonical);
        result.put("description",(result.getString("description")==null?"":result.getString("description")+" ")
                +"For Three.js sources exported to GLB, createScene(THREE) returns Object3D with unique userData.hf_id values. "
                +"Put AnimationClips in root.animations. Animate position, quaternion, scale or morphTargetInfluences only. "
                +"Use QuaternionKeyframeTrack with normalized XYZW quaternions for rotation; Euler rotation[x/y/z] keyframe tracks cannot be exported. "
                +"Static object.rotation assignments are allowed. Preserve existing motion by authoring equivalent quaternion keys; do not drop requested animation to avoid an export failure. "
                +"Do not add animation when the user requested only a static scene.");
        return result;
    }

    private static JSONObject copy(JSONObject value) {
        return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
