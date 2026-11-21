package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.Set;

/** New-plan metadata comes from frozen host catalogs; business dependency edges stay explicit. */
final class NativeHostPlanInputContract {
    static final String FIELD = "hostPlanBindings";
    private static final String BINDING_CHOICES = "hostBindingChoices";
    private static final Set<String> HOST_FIELDS = Set.of("position", "executorKind", "bindingVersion",
            "operationKind", "retryClass", "maxAttempts", "approvalScope");
    private NativeHostPlanInputContract() { }

    static JSONObject schema() {
        JSONObject root = NativeMethodResultInputContract.schema("PLAN");
        root.getJSONObject("$defs").keySet().retainAll(Set.of("planStep", "planInputBinding", "planCriterion"));
        JSONObject step = root.getJSONObject("$defs").getJSONObject("planStep");
        step.getJSONObject("properties").keySet().removeAll(HOST_FIELDS);
        step.getJSONObject("properties").put("bindingKind", JSONObject.of("type", "string",
                "enum", JSONArray.of("TOOL", "SKILL"), "description", "Only needed when a tool and Skill share the same bindingCode; selects the intended catalog namespace."));
        step.getJSONArray("required").removeIf(HOST_FIELDS::contains);
        step.put("description", "Choose an exact bindingCode from the current tool/Skill catalog; keep stableStepId and business dependency edges. Host freezes execution kind, version, position, retry policy and approval scope. Do not supply those technical fields. Array order supplies display positions, not implicit dependencies.");
        return root;
    }

    static String digest() {
        return SecureUtil.sha256(JSON.toJSONString(schema(), JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject schema(String version) {
        JSONObject root = schema();
        if (!NativeAssistantBindingPolicy.V11.equals(version)) return root;
        JSONObject step = root.getJSONObject("$defs").getJSONObject("planStep");
        step.getJSONArray("required").remove("stableStepId");
        step.getJSONObject("properties").put("stepRef", JSONObject.of("type", "string", "minLength", 1,
                "maxLength", 64, "description", "Optional local name used by dependsOn and sourceStableStepId. Host translates it into the persistent step ID. Omit for a step that nobody references; explicit stableStepId remains supported."));
        step.getJSONObject("properties").put("authoringSourceMode", JSONObject.of("type", "string",
                "enum", JSONArray.of("SCRATCH", "CONVERSATION", "CANVAS"), "description",
                "For Skill draft proposal steps, declare the actual business source here. Host adds fixed-version authoring master and matching branch READ prerequisites and their dependencies. Do not invent their technical paths or versions. Omit on unrelated steps; existing fully explicit plans remain supported."));
        JSONObject input = root.getJSONObject("$defs").getJSONObject("planInputBinding");
        input.getJSONArray("required").remove("artifactType");
        input.getJSONObject("properties").getJSONObject("artifactType").put("description",
                "Omit when the producer tool declares one exact archived artifact type; the host derives it. Dynamic/custom producer types require an explicit value. An explicit value must match any fixed producer type.");
        return root;
    }

    static String digest(String version) {
        return SecureUtil.sha256(JSON.toJSONString(schema(version), JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject schema(String version,JSONObject bindings) {
        JSONObject root=schema(version);
        if (!NativeAssistantBindingPolicy.V11.equals(version) || bindings == null) return root;
        if (bindings.get(BINDING_CHOICES) instanceof JSONArray choices && !choices.isEmpty()) {
            JSONObject code = root.getJSONObject("$defs").getJSONObject("planStep")
                    .getJSONObject("properties").getJSONObject("bindingCode");
            code.put("enum", new JSONArray(choices));
        }
        if (!bindings.containsKey("hostCompletionKinds")) return root;
        JSONArray allowed=bindings.getJSONArray("hostCompletionKinds");
        Set<String> known=Set.of("TECHNICAL_ARCHIVE","TARGET_WRITE","METHOD_RESULT","CREATIVE_QUALITY","CONTENT_REQUIREMENT");
        if(allowed==null||allowed.isEmpty()||allowed.size()>known.size()||!known.containsAll(allowed.toJavaList(String.class))
                ||new java.util.HashSet<>(allowed).size()!=allowed.size()||!(bindings.get("hostReviewBindings") instanceof JSONArray))throw invalid();
        JSONObject kind=root.getJSONObject("$defs").getJSONObject("planCriterion").getJSONObject("properties").getJSONObject("criterionKind");
        kind.put("enum",new JSONArray(allowed));
        kind.put("description","Use an executable completion category from this frozen catalog. Formal CREATIVE_QUALITY or CONTENT_REQUIREMENT needs a planned producer of a real REVIEW_REPORT. Viewing and improving rendered previews remains available; a generated preview alone is technical archive evidence, not a formal review pass. Load a named authorized review capability before planning formal review.");
        return root;
    }

    static String digest(String version,JSONObject bindings) {
        return SecureUtil.sha256(JSON.toJSONString(schema(version,bindings),JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject canonicalArguments(JSONObject arguments, JSONObject bindings, String version) {
        if (!NativeAssistantBindingPolicy.V11.equals(version)) return canonicalArguments(arguments, bindings);
        if (arguments == null || !JSONSchema.of(schema(version,bindings)).validate(arguments).isSuccess()) throw invalid();
        JSONObject result = JSON.parseObject(arguments.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        NativeHostSkillPrerequisites.expand(result, bindings);
        JSONArray steps = result.getJSONArray("steps");
        String generatedPrefix = generatedStepPrefix(bindings);
        Set<String> used = new java.util.HashSet<>();
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        // Reserve explicit persistent IDs before generating any, preserving old caller identities.
        for (Object item : steps) {
            JSONObject step = (JSONObject) item;
            String id = step.getString("stableStepId");
            if (id != null && !used.add(id)) throw invalid();
        }
        for (Object item : steps) {
            String local = ((JSONObject) item).getString("stepRef");
            if (local != null) used.add(local);
        }
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String id = step.getString("stableStepId");
            if (id == null) {
                String base = generatedPrefix + (i + 1); id = base;
                for (int suffix = 1; !used.add(id); suffix++) id = base + "_" + suffix;
                step.put("stableStepId", id);
            }
            bindName(names, id, id);
            String local = step.getString("stepRef");
            if (local != null) bindName(names, local, id);
            step.remove("stepRef");
        }
        for (Object item : steps) {
            JSONObject step = (JSONObject) item;
            JSONArray dependencies = step.getJSONArray("dependsOn");
            for (int i = 0; i < dependencies.size(); i++) {
                String source = names.get(dependencies.getString(i));
                if (source == null) throw new UnresolvedDependencyException();
                if (source.equals(step.getString("stableStepId"))) throw invalid();
                dependencies.set(i, source);
            }
            for (Object raw : step.getJSONArray("inputArtifactBindings")) {
                JSONObject input = (JSONObject) raw;
                String source = names.get(input.getString("sourceStableStepId"));
                if (source == null) throw new UnresolvedDependencyException();
                input.put("sourceStableStepId", source);
                JSONObject producer = null;
                for (Object candidate : steps) {
                    JSONObject possible = (JSONObject) candidate;
                    if (source.equals(possible.getString("stableStepId"))) producer = possible;
                }
                if (producer == null || !producer.getJSONArray("outputLogicalArtifactIds").contains(input.getString("logicalArtifactId")))
                    throw invalid();
                String code = producer.getString("bindingCode"), kind = producer.getString("bindingKind");
                if (kind == null) {
                    boolean tool = bindings.containsKey("TOOL:" + code), skill = bindings.containsKey("SKILL:" + code);
                    if (tool == skill) throw invalid();
                    kind = tool ? "TOOL" : "SKILL";
                }
                JSONObject types = bindings.getJSONObject("hostArtifactTypes");
                String type = types == null ? null : types.getString(kind + ":" + code);
                if (type != null) {
                    if (!type.matches("[A-Z][A-Z0-9_]{0,63}")
                            || input.containsKey("artifactType") && !type.equals(input.getString("artifactType"))) throw invalid();
                    input.put("artifactType", type);
                } else if (!input.containsKey("artifactType")) throw invalid();
            }
        }
        // Existing canonical parsing still checks graph cycles, missing dependencies and artifact declarations.
        JSONObject canonical=canonicalArguments(result, bindings);
        requireReviewProducer(canonical,bindings);
        return canonical;
    }

    private static void bindName(java.util.Map<String, String> names, String name, String id) {
        String prior = names.putIfAbsent(name, id);
        if (prior != null && !prior.equals(id)) throw invalid();
    }

    // Frozen in new request bindings only. Old archived bindings retain their original IDs.
    static JSONObject withStepIdentityScope(JSONObject bindings, Long parentRevision) {
        if (parentRevision == null || parentRevision < 0 || parentRevision == Long.MAX_VALUE) throw invalid();
        JSONObject result = new JSONObject(bindings);
        result.put("hostStepIdentity", JSONObject.of("version", "plan-revision/v1", "parentRevision", parentRevision));
        return result;
    }

    static void requireStepIdentityScope(JSONObject bindings, Long parentRevision) {
        if (!bindings.containsKey("hostStepIdentity")) return;
        JSONObject scope = stepIdentityScope(bindings);
        if (!scope.getLong("parentRevision").equals(parentRevision)) throw invalid();
    }

    private static JSONObject stepIdentityScope(JSONObject bindings) {
        Object raw = bindings.get("hostStepIdentity");
        if (!(raw instanceof JSONObject scope)
                || !scope.keySet().equals(Set.of("version", "parentRevision"))
                || !"plan-revision/v1".equals(scope.getString("version"))) throw invalid();
        Object revision = scope.get("parentRevision");
        if (!(revision instanceof Long || revision instanceof Integer)
                || scope.getLongValue("parentRevision") < 0
                || scope.getLongValue("parentRevision") == Long.MAX_VALUE) throw invalid();
        return scope;
    }

    private static String generatedStepPrefix(JSONObject bindings) {
        if (!bindings.containsKey("hostStepIdentity")) return "step_";
        return "plan_" + (stepIdentityScope(bindings).getLongValue("parentRevision") + 1) + "_step_";
    }

    static JSONObject bindings(JSONObject request, RuntimeToolRegistry registry, int readAttempts, int operationAttempts) {
        if (readAttempts < 1 || readAttempts > 10 || operationAttempts < 1 || operationAttempts > 10) throw invalid();
        JSONObject result = new JSONObject();
        JSONArray tools = request.getJSONArray("toolCatalog");
        if (tools != null) for (Object item : tools) {
            JSONObject tool = (JSONObject) item;
            String code = tool.getString("toolCode");
            var descriptor = registry.require(code);
            if (!descriptor.contractVersion().equals(tool.getString("contractVersion"))) throw invalid();
            boolean read = !descriptor.billable() && Set.of("PURE", "READ").contains(descriptor.sideEffectLevel());
            boolean replay = read && "REPLAY_SAFE".equals(descriptor.recoveryPolicy());
            String operation = operationKind(descriptor, registry, read);
            put(result, code, JSONObject.of("executorKind", "TOOL", "bindingVersion", descriptor.contractVersion(),
                    "operationKind", operation,
                    "retryClass", replay ? "SAFE_READ" : descriptor.recoveryPolicy(), "maxAttempts", replay ? readAttempts : operationAttempts,
                    "approvalScope", operation));
        }
        JSONArray skills = request.getJSONArray("skillCatalog");
        if (skills != null) for (Object item : skills) {
            JSONObject skill = (JSONObject) item;
            put(result, skill.getString("skillCode"), JSONObject.of("executorKind", "SKILL",
                    "bindingVersion", skill.getString("skillVersionId"), "operationKind", "SKILL_INVOKE",
                    "retryClass", "RECONCILE_TASK", "maxAttempts", operationAttempts, "approvalScope", "SKILL_INVOKE"));
        }
        return result;
    }

    static JSONObject bindings(JSONObject request, RuntimeToolRegistry registry, int readAttempts, int operationAttempts, String version) {
        JSONObject result = bindings(request, registry, readAttempts, operationAttempts);
        if (!NativeAssistantBindingPolicy.V11.equals(version)) return result;
        NativeHostSkillPrerequisites.bind(request, result);
        boolean authoringReady = NativeHostSkillPrerequisites.bound(result);
        result.put(BINDING_CHOICES, new JSONArray(result.keySet().stream()
                .filter(key -> key.startsWith("TOOL:") || key.startsWith("SKILL:"))
                .filter(key -> authoringReady || !key.startsWith("TOOL:")
                        || !NativeSkillAuthoringContract.isProposalTool(key.substring(5)))
                .map(key -> key.substring(key.indexOf(':') + 1)).distinct().sorted().toList()));
        JSONObject types = new JSONObject();
        for (String key : result.keySet()) {
            if (!key.startsWith("TOOL:")) continue;
            String type = registry.planningArtifactType(key.substring(5));
            if (type != null) types.put(key, type);
        }
        result.put("hostArtifactTypes", types);
        bindCompletionCapabilities(request,registry,result);
        return result;
    }

    static boolean canProduceReview(String kind,String type,String sideEffect) {
        return "REVIEW_REPORT".equals(type)||"RUNTIME_ARTIFACT".equals(kind)&&type==null
                ||"UNKNOWN".equals(kind)&&!Set.of("PURE","READ").contains(java.util.Objects.toString(sideEffect,""));
    }

    private static void bindCompletionCapabilities(JSONObject request,RuntimeToolRegistry registry,JSONObject bindings) {
        var reviews=new JSONArray();
        for(String key:bindings.keySet())if(key.startsWith("TOOL:")){
            String code=key.substring(5);var descriptor=registry.require(code);
            if(canProduceReview(registry.planningOutputReferenceKind(code).name(),registry.planningArtifactType(code),descriptor.sideEffectLevel()))reviews.add(key);
        }
        JSONArray skills=request.getJSONArray("skillCatalog");
        if(skills!=null)for(Object raw:skills)if(raw instanceof JSONObject skill){
            // An immutable method observation is not a finalized Runtime review artifact.
            // Unknown/custom executable Skill contracts retain their existing capabilities.
            if(!Boolean.TRUE.equals(skill.getBoolean("methodOnly"))&&!Set.of("OBSERVATION_ONLY","OBSERVATION").contains(java.util.Objects.toString(skill.getString("outputReferenceKind"),"")))reviews.add("SKILL:"+skill.getString("skillCode"));
        }
        bindings.put("hostReviewBindings",reviews);
        bindings.put("hostCompletionKinds",reviews.isEmpty()?JSONArray.of("TECHNICAL_ARCHIVE","TARGET_WRITE","METHOD_RESULT")
                :JSONArray.of("TECHNICAL_ARCHIVE","TARGET_WRITE","METHOD_RESULT","CREATIVE_QUALITY","CONTENT_REQUIREMENT"));
    }

    static void requireReviewProducer(JSONObject plan,JSONObject bindings) {
        if(bindings==null||!bindings.containsKey("hostCompletionKinds"))return; // Frozen historical bindings retain their contract.
        boolean formal=plan.getJSONArray("completionCriteria").stream().anyMatch(raw->raw instanceof JSONObject row&&Set.of("CONTENT_REQUIREMENT","CREATIVE_QUALITY").contains(row.getString("criterionKind")));
        if(!formal)return;
        JSONArray available=bindings.getJSONArray("hostReviewBindings");
        if(available==null||plan.getJSONArray("steps").stream().noneMatch(raw->raw instanceof JSONObject row&&available.contains(row.getString("executorKind")+":"+row.getString("bindingCode"))))throw new ReviewProducerMissingException();
    }

    private static String operationKind(RuntimeToolRegistry.Descriptor descriptor, RuntimeToolRegistry registry, boolean read) {
        // Preserve existing explicit-confirmation categories; a generic write label must not
        // hide adoption or another protected operation from the existing budget policy.
        if (com.aid.canvas.runtime.CanvasAssistantToolProvider.ADOPT.equals(descriptor.code())) return "ARTIFACT_ADOPT";
        Set<String> protectedKinds = Set.of("ARTIFACT_ADOPT", "CREDENTIAL_WRITE", "EXTERNAL_PUBLISH", "TEAM_MEMBERSHIP_WRITE");
        var kinds = descriptor.requiredCapabilities().stream().filter(protectedKinds::contains).distinct().toList();
        if (kinds.size() > 1) throw invalid();
        if (!kinds.isEmpty()) return kinds.get(0);
        if (read) return "READ_ASSISTANT_CONTEXT";
        var media = descriptor.billable() ? registry.billingMediaType(descriptor.code()) : null;
        return media == null ? "TOOL_EXECUTE" : media.name() + "_GENERATE";
    }

    private static void put(JSONObject result, String code, JSONObject value) {
        if (code == null || value.getString("bindingVersion") == null
                || result.putIfAbsent(value.getString("executorKind") + ":" + code, value) != null)
            throw invalid();
    }

    static JSONObject canonicalArguments(JSONObject arguments, JSONObject bindings) {
        if (arguments == null || bindings == null || !JSONSchema.of(schema()).validate(arguments).isSuccess()) throw invalid();
        JSONObject result = JSON.parseObject(arguments.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONArray steps = result.getJSONArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String code = step.getString("bindingCode");
            String kind = step.getString("bindingKind");
            JSONObject tool = bindings.getJSONObject("TOOL:" + code), skill = bindings.getJSONObject("SKILL:" + code);
            JSONObject bound = kind == null ? tool == null ? skill : skill == null ? tool : null
                    : "TOOL".equals(kind) ? tool : skill;
            if (bound == null || !bound.keySet().equals(Set.of("executorKind", "bindingVersion", "operationKind",
                    "retryClass", "maxAttempts", "approvalScope"))) throw invalid();
            step.putAll(bound);
            step.remove("bindingKind");
            step.put("position", i + 1);
        }
        return result;
    }

    private static ServiceException invalid() { return new ServiceException("计划业务输入或冻结工具绑定无效"); }
    static final class UnresolvedDependencyException extends RuntimeException {
        UnresolvedDependencyException(){super("MODEL_PLAN_DEPENDENCY_INVALID");}
    }
    static final class ReviewProducerMissingException extends RuntimeException {
        ReviewProducerMissingException(){super("MODEL_PLAN_REVIEW_UNAVAILABLE");}
    }
}
