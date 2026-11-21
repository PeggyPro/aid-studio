package com.aid.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Selects catalog data for an already bounded execution phase, without changing source evidence. */
public final class ExecutionCatalogDemand {
    private ExecutionCatalogDemand() { }

    public record Binding(String kind, String code, String version) {
        public Binding {
            if (kind == null || !Set.of("TOOL", "SKILL").contains(kind) || code == null || code.isBlank()
                    || version == null || version.isBlank()) throw invalid();
        }
    }

    /**
     * The caller supplies server-derived executable bindings after building its authoritative
     * function catalog. Planning phases retain all candidates. No source, permission, receipt,
     * user requirement or saved contract is deleted by this provider-only projection.
     */
    public static JSONObject project(JSONObject source, Collection<Binding> executable,
                                     boolean planningAllowed) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(executable);
        JSONObject result = JSON.parseObject(ContextContractProjection.canonical(source));
        if (planningAllowed) return result;
        Set<Binding> required = new HashSet<>(executable);
        if (required.contains(null)) throw invalid();
        JSONObject plan = source.getJSONObject("currentPlan");
        if (plan != null && plan.get("steps") instanceof JSONArray steps) {
            for (Object raw : steps) {
                if (!(raw instanceof JSONObject step)) throw invalid();
                required.add(new Binding(step.getString("executorKind"),
                        step.getString("bindingCode"), step.getString("bindingVersion")));
            }
        }
        JSONObject turn = source.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        if (selected != null) required.add(new Binding("SKILL", selected.getString("code"),
                selected.getString("versionId")));

        Set<Binding> available = new HashSet<>();
        int omittedTools = select(result, "toolCatalog", "TOOL", required, available);
        int omittedSkills = select(result, "skillCatalog", "SKILL", required, available);
        if (!available.containsAll(executable)) throw invalid();
        if (ContextPreparationExecutionGate.pending(result)) return ContextPreparationExecutionGate.providerView(result);
        if (omittedTools + omittedSkills == 0) return result;
        JSONObject limits = result.getJSONObject("contextLimits");
        if (limits == null) {
            limits = new JSONObject();
            result.put("contextLimits", limits);
        }
        if (limits.containsKey("executionCatalogSelection")) throw invalid();
        limits.put("executionCatalogSelection", JSONObject.of(
                "mode", "CURRENT_EXECUTION", "omittedToolCount", omittedTools,
                "omittedSkillCount", omittedSkills,
                "instruction", "This phase carries the current executable bindings, approved plan dependencies "
                        + "and selected immutable Skill only. Full candidates are rebuilt when planning is available. "
                        + "The current function bindings remain authoritative; catalog presence grants no permission."));
        return result;
    }

    private static int select(JSONObject result, String field, String kind, Set<Binding> required,
                              Set<Binding> available) {
        Object rawCatalog = result.get(field);
        if (rawCatalog == null) return 0;
        if (!(rawCatalog instanceof JSONArray catalog)) throw invalid();
        JSONArray retained = new JSONArray();
        Set<Binding> seen = new HashSet<>();
        for (Object raw : catalog) {
            if (!(raw instanceof JSONObject entry)) throw invalid();
            Binding identity = "TOOL".equals(kind)
                    ? new Binding(kind, entry.getString("toolCode"), entry.getString("contractVersion"))
                    : new Binding(kind, entry.getString("skillCode"), entry.getString("skillVersionId"));
            if (!seen.add(identity)) throw invalid();
            available.add(identity);
            if (required.contains(identity)) retained.add(entry);
        }
        result.put(field, retained);
        return catalog.size() - retained.size();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_EXECUTION_CATALOG_INVALID");
    }
}
