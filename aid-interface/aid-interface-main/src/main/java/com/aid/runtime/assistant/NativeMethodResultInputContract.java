package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/** Additive v9 delivery contract; archived v3-v8 provider schemas remain unchanged. */
final class NativeMethodResultInputContract {
    private NativeMethodResultInputContract() { }

    static boolean applies(String version, String action) {
        return NativeAssistantBindingPolicy.methodResults(version)
                && ("PLAN".equals(action) || "COMPLETE".equals(action));
    }

    static JSONObject schema(String action) {
        if (!"PLAN".equals(action) && !"COMPLETE".equals(action))
            throw new IllegalArgumentException("Method result action is invalid");
        JSONObject schema = "PLAN".equals(action)
                ? NativeAssistantControlContractV1.nativePayloadSchema(action)
                : NativeCompletionInputContract.schema();
        String definition = "PLAN".equals(action) ? "planCriterion" : "completionCriterion";
        JSONObject kind = schema.getJSONObject("$defs").getJSONObject(definition)
                .getJSONObject("properties").getJSONObject("criterionKind");
        kind.getJSONArray("enum").add("METHOD_RESULT");
        kind.put("description", instruction());
        schema.put("description", "PLAN".equals(action)
                ? "Propose exact immutable Skill or Tool steps. For a method-only report delivered in chat, use METHOD_RESULT. "
                    + "Do not add a node write or media operation when the user only requests a report. " + instruction()
                : "Deliver the complete business result in message as Markdown. " + instruction());
        if ("COMPLETE".equals(action)) {
            schema.getJSONObject("properties").getJSONObject("artifactRefs").put("description",
                    "Optional compatibility aggregate for artifact criteria only. Exclude METHOD_RESULT and TARGET_WRITE. Normally omit.");
            schema.getJSONObject("$defs").getJSONObject(definition).getJSONObject("properties")
                    .getJSONObject("evidenceRefs").put("description",
                            "METHOD_RESULT uses current-plan runtime-step:* Skill receipts directly in this criterion, "
                            + "not in artifactRefs or targetWriteEvidenceRefs. TARGET_WRITE uses target-write receipts; "
                            + "other kinds require real artifact:* evidence.");
        }
        return schema;
    }

    static String digest(String action) {
        return SecureUtil.sha256(JSON.toJSONString(schema(action),
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static void project(JSONObject request) {
        JSONObject policy = AssistantMainInputContract.policy(request, "catalogPolicy");
        if (policy != null) policy.put("includedEntries",
                "Use listed runtime tools or exact immutable method-only TEXT Skills. A successful Skill result "
                        + "may be delivered directly in chat with METHOD_RESULT, or consumed by dependent tools. "
                        + "It does not prove a node write or Runtime artifact. Read missing information through discovery tools before planning.");
        JSONObject evidence = request.getJSONObject("completionEvidenceContract");
        if (evidence != null) evidence.put("planFeasibility", instruction());
        JSONObject plan = request.getJSONObject("currentPlan");
        if (plan != null && NativeAssistantDiscoveryPlanService.DEFINITION_KIND.equals(plan.getString("serverPlanKind")))
            plan.put("discoveryInstruction", NativePlanningEvidenceGuidance.readOnlyInstruction() + " " + instruction());
        NativePlanningEvidenceGuidance.project(request);
    }

    static String instruction() {
        return "METHOD_RESULT proves an actual successful method-only Skill result from the current approved plan, "
                + "using its runtime-step:* receipt. Deliver the full result in chat and retain uncertainty; "
                + "it does not certify creative quality, reviewed content, media archival or any target write. "
                + "Use TARGET_WRITE only for a real supported target write. TECHNICAL_ARCHIVE requires an archived artifact; "
                + "CONTENT_REQUIREMENT and CREATIVE_QUALITY require an artifact and an actual PASS review. "
                + "Do not invent evidence or add unwanted side effects to satisfy a completion criterion.";
    }
}
