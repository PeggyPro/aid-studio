package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Compile only identities already observed in this run. This never grants workspace or node access. */
final class NativeHostTargetInput {
    static final String FIELD = "hostTargetBindings";
    private NativeHostTargetInput() { }

    static JSONObject bindings(JSONObject request) {
        JSONObject context = request.getJSONObject("executionContext");
        JSONArray refs = context == null ? null : context.getJSONArray("expectedContextRevisions");
        JSONObject targets = new JSONObject();
        Set<String> workspaces = new LinkedHashSet<>(), ambiguous = new LinkedHashSet<>();
        if (refs != null) for (Object raw : refs) {
            if (!(raw instanceof JSONObject row) || !"FREE_CANVAS".equals(row.getString("kind"))) continue;
            JSONObject ref = row.getJSONObject("canonicalRef");
            if (ref == null) continue;
            String workspace = ref.getString("workspaceId"), id = ref.getString("objectId"), version = ref.getString("version");
            if (!positive(workspace)) continue;
            workspaces.add(workspace);
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !positive(version)) continue;
            JSONObject value = JSONObject.of("workspaceId", workspace, "objectVersion", version);
            Object prior = targets.putIfAbsent(id, value);
            if (prior != null && !prior.equals(value)) ambiguous.add(id);
        }
        ambiguous.forEach(targets::remove);
        JSONObject result = JSONObject.of("targets", targets);
        if (workspaces.size() == 1) result.put("workspaceId", workspaces.iterator().next());
        return result;
    }

    static JSONObject schema(JSONObject canonical, JSONObject bindings) {
        JSONObject result = JSON.parseObject(canonical.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        project(result, bindings);
        return result;
    }

    static boolean bindsWorkspace(JSONObject schema) {
        if (schema.getJSONObject("properties") != null && schema.getJSONObject("properties").containsKey("workspaceId")) return true;
        JSONArray alternatives = schema.getJSONArray("oneOf");
        if (alternatives != null) return !alternatives.isEmpty()
                && alternatives.stream().allMatch(value -> value instanceof JSONObject branch && bindsWorkspace(branch));
        return false;
    }

    private static void project(JSONObject schema, JSONObject bindings) {
        JSONArray alternatives = schema.getJSONArray("oneOf");
        if (alternatives != null) for (Object item : alternatives) project((JSONObject) item, bindings);
        JSONObject properties = schema.getJSONObject("properties");
        if (properties == null) return;
        if (bindings.getBooleanValue("bindWorkspace") && bindings.containsKey("workspaceId") && properties.containsKey("workspaceId")) {
            properties.remove("workspaceId");
            if (schema.getJSONArray("required") != null) schema.getJSONArray("required").remove("workspaceId");
        }
        JSONObject target = properties.getJSONObject("target");
        if (target == null || bindings.getJSONObject("targets").isEmpty()
                || target.getJSONObject("properties") == null
                || !target.getJSONObject("properties").keySet().containsAll(Set.of("objectId", "objectVersion"))) return;
        if (target.getJSONArray("required") != null) target.getJSONArray("required").remove("objectVersion");
        target.put("description", "Choose an observed node ID. Its version may be omitted: the host binds the unique observed version, never an unobserved current database version. Explicit versions remain checked.");
    }

    static JSONObject arguments(JSONObject supplied, JSONObject bindings, boolean workspaceField) {
        JSONObject result = JSON.parseObject(supplied.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        if (workspaceField && bindings.containsKey("workspaceId")) {
            Object workspace = bindings.get("workspaceId");
            if (result.containsKey("workspaceId") && !Objects.equals(result.get("workspaceId"), workspace)) throw invalid();
            result.put("workspaceId", workspace);
        }
        JSONObject target = result.getJSONObject("target");
        if (target != null && !target.containsKey("objectVersion")) {
            JSONObject bound = bindings.getJSONObject("targets").getJSONObject(target.getString("objectId"));
            if (bound == null) throw invalid();
            target.put("objectVersion", bound.get("objectVersion"));
        }
        return result;
    }

    static String digest(JSONObject bindings) {
        return cn.hutool.crypto.SecureUtil.sha256(JSON.toJSONString(bindings,
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static boolean valid(JSONObject bindings) {
        if (bindings == null || !Set.of("targets", "workspaceId", "bindWorkspace").containsAll(bindings.keySet())
                || !(bindings.get("targets") instanceof JSONObject targets) || targets.size() > 1001
                || !(bindings.get("bindWorkspace") instanceof Boolean)
                || bindings.containsKey("workspaceId") && !positive(bindings.getString("workspaceId"))) return false;
        for (var entry : targets.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || !(entry.getValue() instanceof JSONObject target)
                    || !target.keySet().equals(Set.of("workspaceId", "objectVersion"))
                    || !positive(target.getString("workspaceId")) || !positive(target.getString("objectVersion"))) return false;
        }
        return true;
    }
    private static boolean positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static ServiceException invalid() { return new ServiceException("节点或工作区缺少唯一的当前观察绑定"); }
}
