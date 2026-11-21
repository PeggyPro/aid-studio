package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Expands explicitly requested authoring sources into the existing approved READ steps. */
final class NativeHostSkillPrerequisites {
    private static final String VERSION = "hostAuthoringVersion";
    private static final String PREFIX = "host_authoring_";
    private NativeHostSkillPrerequisites() { }

    static boolean bound(JSONObject bindings) {
        return bindings.containsKey(VERSION)
                && bindings.containsKey("TOOL:" + NativeSkillAuthoringContract.READ_RESOURCE);
    }

    static void bind(JSONObject request, JSONObject bindings) {
        Set<String> versions = new LinkedHashSet<>();
        JSONArray skills = request.getJSONArray("skillCatalog");
        if (skills != null) for (Object raw : skills) {
            if (raw instanceof JSONObject skill && NativeSkillAuthoringContract.SKILL_CODE.equals(skill.getString("skillCode")))
                addVersion(versions, skill.getString("skillVersionId"));
        }
        // Authoring methods can be discovered on demand and need not be executable agent
        // bindings. Pin the version from successful server-owned discovery/read receipts.
        collectVersions(request.getJSONArray("observations"), true, versions);
        collectVersions(request.getJSONArray("priorResults"), false, versions);
        if (versions.size() > 1) throw invalid();
        if (!versions.isEmpty()) bindings.put(VERSION, versions.iterator().next());
    }

    private static void collectVersions(JSONArray receipts, boolean observations, Set<String> versions) {
        if (receipts == null) return;
        for (Object raw : receipts) {
            if (!(raw instanceof JSONObject receipt)
                    || (observations ? !"SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(receipt.getString("authority"))
                        : !"SUCCEEDED".equals(receipt.getString("status")))
                    || receipt.containsKey("status") && !"SUCCEEDED".equals(receipt.getString("status"))) continue;
            JSONObject data = receipt.getJSONObject("observation");
            if (data == null) continue;
            if (NativeSkillAuthoringContract.READ_RESOURCE.equals(receipt.getString("toolCode"))
                    && NativeSkillAuthoringContract.SKILL_CODE.equals(data.getString("skillCode"))) {
                if (!digest(data.getString("packageDigest")) || !digest(data.getString("resourceDigest"))) throw invalid();
                addVersion(versions, data.getString("skillVersionId"));
            } else if ("aid.skill.directory".equals(receipt.getString("toolCode"))) {
                JSONArray items = data.getJSONArray("items");
                if (items != null) for (Object item : items) {
                    if (item instanceof JSONObject skill && NativeSkillAuthoringContract.SKILL_CODE.equals(skill.getString("code"))) {
                        if (!digest(skill.getString("packageDigest"))) throw invalid();
                        addVersion(versions, skill.getString("versionId"));
                    }
                }
            }
        }
    }

    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void addVersion(Set<String> versions, String version) {
        if (version == null || !version.matches("[1-9][0-9]{0,18}")) throw invalid();
        versions.add(version);
    }

    static void expand(JSONObject plan, JSONObject bindings) {
        JSONArray steps = plan.getJSONArray("steps");
        Set<String> modes = new LinkedHashSet<>(), used = new HashSet<>();
        for (Object raw : steps) {
            JSONObject step = (JSONObject) raw;
            for (String field : List.of("stableStepId", "stepRef")) if (step.getString(field) != null) used.add(step.getString(field));
            if (!step.containsKey("authoringSourceMode")) continue;
            if (!NativeSkillAuthoringContract.isProposalTool(step.getString("bindingCode"))) throw invalid();
            String mode = step.getString("authoringSourceMode");
            if (!NativeSkillAuthoringContract.SOURCE_BRANCHES.containsKey(mode)) throw invalid();
            modes.add(mode);
            step.remove("authoringSourceMode");
        }
        if (modes.isEmpty()) return;
        String version = bindings.getString(VERSION);
        if (version == null || !version.matches("[1-9][0-9]{0,18}")
                || !bindings.containsKey("TOOL:" + NativeSkillAuthoringContract.READ_RESOURCE)) throw invalid();
        JSONArray expanded = new JSONArray();
        String master = unique("MASTER", version, used);
        expanded.add(read(master, new JSONArray()));
        JSONArray branches = new JSONArray();
        for (String mode : modes) {
            String branch = unique(mode, version, used);
            branches.add(branch);
            expanded.add(read(branch, JSONArray.of(master)));
        }
        for (Object raw : steps) {
            JSONObject step = (JSONObject) raw;
            String bindingCode = step.getString("bindingCode");
            boolean independentRead = !"SKILL".equals(step.getString("bindingKind"))
                    && bindings.containsKey("TOOL:" + bindingCode)
                    && NativeAssistantDiscoveryPlanService.isSafeRead(bindingCode, plan);
            if (!independentRead) {
                JSONArray dependencies = step.getJSONArray("dependsOn");
                for (Object branch : branches) if (!dependencies.contains(branch)) dependencies.add(branch);
            }
            expanded.add(step);
        }
        if (expanded.size() > 100) throw invalid();
        plan.put("steps", expanded);
    }

    static JSONObject projectRead(JSONObject request, String code, String stepId, JSONObject schema) {
        if (!NativeSkillAuthoringContract.READ_RESOURCE.equals(code) || stepId == null || !stepId.startsWith(PREFIX)) return schema;
        var match = java.util.regex.Pattern.compile("host_authoring_(MASTER|SCRATCH|CONVERSATION|CANVAS)_v([1-9][0-9]{0,18})_[0-9]+").matcher(stepId);
        if (!match.matches()) return schema; // Existing explicit step identifiers remain ordinary reads.
        JSONObject binding = new JSONObject(); bind(request, binding);
        if (!Objects.equals(match.group(2), binding.getString(VERSION))) throw invalid();
        String path = "MASTER".equals(match.group(1)) ? NativeSkillAuthoringContract.MASTER_PATH
                : NativeSkillAuthoringContract.SOURCE_BRANCHES.get(match.group(1));
        JSONObject result = JSONObject.parseObject(schema.toJSONString());
        Map<String, Object> fixed = Map.of("skillCode", NativeSkillAuthoringContract.SKILL_CODE,
                "versionId", Long.valueOf(match.group(2)), "path", path);
        for (var entry : fixed.entrySet()) {
            JSONObject property = result.getJSONObject("properties").getJSONObject(entry.getKey());
            if (property == null || !com.alibaba.fastjson2.schema.JSONSchema.of(property).validate(entry.getValue()).isSuccess()) throw invalid();
            property.put("enum", JSONArray.of(entry.getValue()));
            property.put("x-host-owned-default", true);
        }
        return result;
    }

    static JSONObject projectProposal(JSONObject request, String code, JSONObject step, JSONObject plan, JSONObject schema) {
        if (!NativeSkillAuthoringContract.isProposalTool(code)) return schema;
        Set<String> modes = new HashSet<>();
        JSONArray dependencies = step.getJSONArray("dependsOn");
        JSONArray steps = plan.getJSONArray("steps");
        if (dependencies == null || steps == null) return schema;
        JSONObject binding = new JSONObject(); bind(request, binding);
        for (Object raw : steps) {
            JSONObject candidate = (JSONObject) raw;
            String id = candidate.getString("stableStepId");
            if (!dependencies.contains(id) || !NativeSkillAuthoringContract.READ_RESOURCE.equals(candidate.getString("bindingCode"))) continue;
            var match = java.util.regex.Pattern.compile("host_authoring_(SCRATCH|CONVERSATION|CANVAS)_v([1-9][0-9]{0,18})_[0-9]+").matcher(Objects.toString(id, ""));
            if (!match.matches()) continue;
            if (!Objects.equals(match.group(2), binding.getString(VERSION))) throw invalid();
            modes.add(match.group(1));
        }
        if (modes.size() != 1) return schema;
        JSONObject result = JSONObject.parseObject(schema.toJSONString());
        JSONObject property = result.getJSONObject("properties").getJSONObject("sourceMode");
        String mode = modes.iterator().next();
        if (property == null || !com.alibaba.fastjson2.schema.JSONSchema.of(property).validate(mode).isSuccess()) throw invalid();
        property.put("enum", JSONArray.of(mode));
        property.put("x-host-owned-default", true);
        return result;
    }

    private static String unique(String mode, String version, Set<String> used) {
        for (int i = 0; i < 102; i++) {
            String id = PREFIX + mode + "_v" + version + "_" + i;
            if (used.add(id)) return id;
        }
        throw invalid();
    }
    private static JSONObject read(String id, JSONArray depends) {
        return JSONObject.of("stableStepId", id, "bindingCode", NativeSkillAuthoringContract.READ_RESOURCE,
                "bindingKind", "TOOL", "dependsOn", depends, "inputArtifactRefs", new JSONArray(),
                "inputArtifactBindings", new JSONArray(), "outputLogicalArtifactIds", new JSONArray());
    }
    private static ServiceException invalid() { return new ServiceException("Skill编制来源或固定版本前置读取绑定无效"); }
}
