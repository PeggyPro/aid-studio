package com.aid.runtime.assistant;

import com.aid.runtime.tool.SharedContextRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Set;

/** Resolves only server-owned catalog references; user content is never traversed or rewritten. */
public final class NativeContextDemandProjection {
    private NativeContextDemandProjection() { }

    public static boolean retainInline(JSONObject request, JSONObject entry, Set<String> completedReferences) {
        if (retainInline(request, entry)) return true;
        String kind = entry.containsKey("toolCode") ? "TOOL" : "SKILL";
        return completedReferences.contains(com.aid.context.ContextContractProjection.referenceFor(kind, entry));
    }

    public static boolean retainInline(JSONObject request, JSONObject entry) {
        String toolCode = entry.getString("toolCode");
        // Planning candidates may need a node that an earlier step has not created yet.
        // Their contract must stay discoverable; unavailable-now does not mean unsupported.
        if (Boolean.FALSE.equals(entry.getBoolean("availableNow"))) return true;
        if (SharedContextRuntimeToolProvider.isContextReaderCode(toolCode)) return true;
        if (toolCode != null) {
            JSONObject plan = request.getJSONObject("currentPlan");
            JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
            if (steps != null && steps.stream().anyMatch(raw -> raw instanceof JSONObject step
                    && "TOOL".equals(step.getString("executorKind"))
                    && toolCode.equals(step.getString("bindingCode")))) return true;
            if (NativeCapabilityInputProjection.hasVerifiedContract(request, toolCode)) return true;
        }
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        String version = selected == null ? null : selected.getString("versionId");
        return version != null && version.equals(entry.getString("skillVersionId"));
    }

    public static boolean readerExecutable(JSONObject request, JSONObject bindings) {
        return executableReaderCode(request, bindings) != null;
    }

    /** Use the exact pinned and executable reader, including the current v2 contract. */
    public static String executableReaderCode(JSONObject request, JSONObject bindings) {
        if (request == null || bindings == null || !(bindings.get("tools") instanceof JSONArray tools)
                || !(request.get("toolCatalog") instanceof JSONArray catalog)) return null;
        for (Object raw : catalog) {
            if (!(raw instanceof JSONObject entry)) continue;
            String code = entry.getString("toolCode");
            var descriptor = SharedContextRuntimeToolProvider.descriptorForCode(code);
            if (descriptor == null || !descriptor.contractVersion().equals(entry.getString("contractVersion"))
                    || !descriptor.schemaDigest().equals(entry.getString("schemaDigest"))) continue;
            boolean executable = tools.stream().anyMatch(value -> value instanceof JSONObject binding
                    && "TOOL".equals(binding.getString("action"))
                    && binding.get("payloadTemplate") instanceof JSONObject payload
                    && descriptor.code().equals(payload.getString("toolCode"))
                    && descriptor.contractVersion().equals(payload.getString("contractVersion")));
            if (executable) return code;
        }
        return null;
    }

    public static JSONObject materialize(JSONObject source) {
        NativeAssistantContextProjection.validate(source);
        JSONObject full = JSON.parseObject(com.aid.context.ContextContractProjection.canonical(source));
        for (String field : List.of("toolCatalog", "skillCatalog")) {
            JSONArray entries = full.getJSONArray(field);
            if (entries == null) continue;
            for (Object raw : entries) {
                JSONObject entry = (JSONObject) raw;
                for (String key : List.of("inputSchema", "outputSchema", "outputReferencePolicy"))
                    if (entry.containsKey(key)) entry.put(key,
                            NativeAssistantContextProjection.resolveValue(source, entry.get(key)));
                if (entry.get("executionOptions") instanceof JSONObject options && options.containsKey("modelChoices"))
                    options.put("modelChoices", NativeAssistantContextProjection.resolveValue(source, options.get("modelChoices")));
            }
        }
        JSONObject limits = full.getJSONObject("contextLimits");
        if (limits != null) {
            limits.remove("catalogDefinitions");
            limits.remove("catalogReferenceContract");
        }
        return full;
    }
}
