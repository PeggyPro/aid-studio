package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the lossless provider-visible catalog projection for native Assistant turns. */
public final class NativeAssistantContextProjection
{
    private static final String SCHEMA_VERSION = "assistant-catalog-references/v1";
    private static final String DEFINITIONS_FIELD = "catalogDefinitions";
    private static final String CONTRACT_FIELD = "catalogReferenceContract";
    private static final String REF_FIELD = "$ref";
    private static final String REF_PREFIX = "#/contextLimits/" + DEFINITIONS_FIELD + "/";
    private static final int MIN_SAVING_BYTES = 1;

    private NativeAssistantContextProjection() { }

    public static String schemaVersion()
    {
        return SCHEMA_VERSION;
    }

    public static Projection projectForProtocol(JSONObject source, boolean nativeProtocol)
    {
        if (nativeProtocol) return project(source);
        if (source == null) throw new ServiceException("助手上下文无效");
        JSONObject unchanged = AssistantMainInputContract.copy(source);
        int originalBytes = bytes(source);
        return new Projection(unchanged, originalBytes, originalBytes, 0,
                sectionBytes(unchanged, originalBytes));
    }

    public static Projection project(JSONObject source)
    {
        if (source == null) throw new ServiceException("原生上下文无效");
        validateDecisionCorrectionObservation(source);
        JSONObject projected = AssistantMainInputContract.copy(source);
        JSONObject limits = projected.getJSONObject("contextLimits");
        if (limits == null) throw new ServiceException("原生上下文限制缺失");
        if (limits.containsKey(DEFINITIONS_FIELD) || limits.containsKey(CONTRACT_FIELD))
            throw new ServiceException("原生目录保留字段冲突");

        List<Candidate> candidates = new ArrayList<>();
        collect(candidates, projected.getJSONArray("toolCatalog"), "inputSchema", "schemas");
        collect(candidates, projected.getJSONArray("toolCatalog"), "outputSchema", "schemas");
        collectNested(candidates, projected.getJSONArray("toolCatalog"), "executionOptions",
                "modelChoices", "modelChoices");
        collect(candidates, projected.getJSONArray("toolCatalog"), "outputReferencePolicy",
                "outputReferencePolicies");
        collect(candidates, projected.getJSONArray("skillCatalog"), "inputSchema", "schemas");
        collect(candidates, projected.getJSONArray("skillCatalog"), "outputSchema", "schemas");
        collect(candidates, projected.getJSONArray("skillCatalog"), "outputReferencePolicy",
                "outputReferencePolicies");

        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates)
            groups.computeIfAbsent(candidate.kind() + ":" + candidate.digest(), ignored -> new ArrayList<>())
                    .add(candidate);

        JSONObject definitions = new JSONObject();
        int referenceCount = 0;
        for (List<Candidate> group : groups.values())
        {
            Candidate first = group.get(0);
            if (group.size() < 2 || savingBytes(first, group.size()) < MIN_SAVING_BYTES) continue;
            JSONObject values = definitions.getJSONObject(first.kind());
            if (values == null)
            {
                values = new JSONObject();
                definitions.put(first.kind(), values);
            }
            values.put(first.digest(), first.value());
            String pointer = REF_PREFIX + first.kind() + "/" + first.digest();
            for (Candidate candidate : group)
            {
                JSONObject reference = new JSONObject();
                reference.put(REF_FIELD, pointer);
                candidate.owner().put(candidate.field(), reference);
                referenceCount++;
            }
        }
        if (!definitions.isEmpty())
        {
            limits.put(DEFINITIONS_FIELD, definitions);
            JSONObject contract = new JSONObject();
            contract.put("schemaVersion", SCHEMA_VERSION);
            contract.put("syntax", "JSON_POINTER");
            contract.put("definitionsDigest", SecureUtil.sha256(canonical(definitions)));
            contract.put("instruction", "A catalog value shaped as {$ref: path} is exactly the immutable JSON value at that path in this request. Resolve it before planning or calling a tool; it is shared data, not omitted data.");
            limits.put(CONTRACT_FIELD, contract);
        }

        int originalBytes = bytes(source);
        int projectedBytes = bytes(projected);
        if (projectedBytes >= originalBytes)
        {
            JSONObject unchanged = AssistantMainInputContract.copy(source);
            return new Projection(unchanged, originalBytes, originalBytes, 0,
                    sectionBytes(unchanged, originalBytes));
        }
        return new Projection(projected, originalBytes, projectedBytes, referenceCount,
                sectionBytes(projected, projectedBytes));
    }

    public static JSONObject resolveObject(JSONObject request, JSONObject owner, String field)
    {
        Object value = owner == null ? null : owner.get(field);
        value = resolveValue(request, value);
        return value instanceof JSONObject object ? object : null;
    }

    public static Object resolveValue(JSONObject request, Object value)
    {
        if (!isCatalogReference(value)) return value;
        return resolve(request, ((JSONObject) value).getString(REF_FIELD));
    }

    /**
     * Builds the provider payload only after native function definitions were derived from the
     * authoritative context. The native tools already carry these schemas, so retaining a second
     * catalog copy wastes input tokens and creates two provider-visible sources of truth.
     */
    /** Future plan steps are not exposed as executable native functions yet; retain their contracts. */
    public static JSONObject providerViewAfterNativeTools(JSONObject authoritative, boolean planningAllowed,
            Set<com.aid.context.ExecutionCatalogDemand.Binding> executable)
    {
        if (authoritative == null) throw new ServiceException("原生上下文无效");
        validate(authoritative);
        JSONObject result = AssistantMainInputContract.copy(authoritative);
        NativeHostNodeInput.sanitizeProviderObservations(result);
        if (!planningAllowed)
        {
            stripCatalogSchemas(result.getJSONArray("toolCatalog"), "TOOL", executable);
            stripCatalogSchemas(result.getJSONArray("skillCatalog"), "SKILL", executable);
        }
        JSONObject catalogPolicy = AssistantMainInputContract.policy(result, "catalogPolicy");
        if (catalogPolicy != null)
            catalogPolicy.put("includedEntries", "Use only listed runtime tools or exact immutable method-only TEXT Skills in execution plans. "
                    + "A Skill may be used as an observation for dependent tools, but it does not produce a Runtime artifact or target write. "
                    + "availableNow=false means a future valid target is required before execution. "
                    + "Gather information by calling currently available native read tools directly, not by proposing a separate read-only execution plan.");
        JSONObject evidence = result.getJSONObject("completionEvidenceContract");
        if (evidence != null)
            evidence.put("planFeasibility", "A TARGET_WRITE criterion requires a listed target-write tool step. "
                    + "Every other criterion requires a Runtime-artifact-producing tool step; CONTENT_REQUIREMENT and CREATIVE_QUALITY also require a real PASS review. "
                    + "A method-only TEXT Skill contributes only a completed observation to dependent steps and cannot satisfy those criteria by itself. "
                    + "Resolve missing method, model or project information through currently available native read tools before planning. "
                    + "Ask the user only for necessary creative choices, missing user information or authorization, not to resolve internal evidence or tool-selection rules.");
        JSONObject plan = result.getJSONObject("currentPlan");
        if (plan != null && NativeAssistantDiscoveryPlanService.DEFINITION_KIND.equals(
                plan.getString("serverPlanKind")))
        {
            plan.remove("completionCriteria");
            plan.put("discoveryInstruction", "This server-approved read-only discovery plan has no direct tool charge and is not a user execution plan. "
                    + "Its assistant text-model calls may still cost credits; this is not a claim that the run is free. "
                    + "Call its currently available tools directly to read method files, model catalogs or project state. "
                    + "Once the necessary information is available, propose execution steps that can produce real evidence for their completion criteria; "
                    + "do not submit another plan containing only these discovery reads.");
        }
        // Future steps may still reference shared definitions. Resolve their remaining schemas
        // before dropping the definition table; the contract publisher can then expose a read ref.
        return planningAllowed ? result : NativeContextDemandProjection.materialize(result);
    }

    public static void appendDecisionCorrectionObservation(
            JSONObject request, NativeDecisionCorrectionObservation correction)
    {
        if (request == null || correction == null)
            throw new ServiceException("原生决策纠正观察无效");
        Object rawObservations = request.get("observations");
        if (!(rawObservations instanceof JSONArray))
            throw new ServiceException("原生观察目录缺失");
        JSONArray observations = (JSONArray) rawObservations;
        validateDecisionCorrectionObservation(request);
        observations.add(correction.toJson());
        validateDecisionCorrectionObservation(request);
    }

    /** Rejects dangling or altered server-generated references before a provider request is built. */
    public static void validate(JSONObject request)
    {
        validateDecisionCorrectionObservation(request);
        SelectedSkillToolRoutePolicy.validateProjectedMetadata(request);
        validate(request, request == null ? null : request.getJSONArray("toolCatalog"),
                "inputSchema", "outputSchema", "outputReferencePolicy");
        validateNested(request, request == null ? null : request.getJSONArray("toolCatalog"),
                "executionOptions", "modelChoices");
        validate(request, request == null ? null : request.getJSONArray("skillCatalog"),
                "inputSchema", "outputSchema", "outputReferencePolicy");
    }

    private static void validateDecisionCorrectionObservation(JSONObject request)
    {
        Object rawObservations = request == null ? null : request.get("observations");
        if (!(rawObservations instanceof JSONArray)) return;
        JSONArray observations = (JSONArray) rawObservations;
        int corrections = 0;
        for (int index = 0; index < observations.size(); index++)
        {
            Object rawValue = observations.get(index);
            if (!(rawValue instanceof JSONObject)) continue;
            JSONObject value = (JSONObject) rawValue;
            boolean reservedKind = NativeDecisionCorrectionObservation.OBSERVATION_KIND.equals(
                    value.getString("observationKind"));
            boolean reservedAuthority = NativeDecisionCorrectionObservation.AUTHORITY.equals(
                    value.getString("authority"));
            if (!reservedKind && !reservedAuthority) continue;
            if (!reservedKind || !reservedAuthority || ++corrections > 1)
                throw new ServiceException("原生决策纠正观察冲突");
            try { NativeDecisionCorrectionObservation.parse(value); }
            catch (RuntimeException invalid)
            {
                throw new ServiceException("原生决策纠正观察无效");
            }
        }
    }

    private static Object resolve(JSONObject request, String pointer)
    {
        if (request == null || pointer == null || !pointer.startsWith(REF_PREFIX))
            throw new ServiceException("原生目录引用无效");
        String[] path = pointer.substring(REF_PREFIX.length()).split("/", -1);
        if (path.length != 2 || path[0].isBlank() || !path[1].matches("^[0-9a-f]{64}$"))
            throw new ServiceException("原生目录引用无效");
        JSONObject limits = request.getJSONObject("contextLimits");
        JSONObject definitions = limits == null ? null : limits.getJSONObject(DEFINITIONS_FIELD);
        JSONObject contract = limits == null ? null : limits.getJSONObject(CONTRACT_FIELD);
        if (contract == null || !SCHEMA_VERSION.equals(contract.getString("schemaVersion"))
                || definitions == null
                || !Objects.equals(contract.getString("definitionsDigest"),
                SecureUtil.sha256(canonical(definitions))))
            throw new ServiceException("原生目录引用契约无效");
        JSONObject values = definitions == null ? null : definitions.getJSONObject(path[0]);
        Object value = values == null ? null : values.get(path[1]);
        if (value == null || !path[1].equals(SecureUtil.sha256(canonical(value))))
            throw new ServiceException("原生目录引用已变化");
        return value;
    }

    private static void collect(List<Candidate> candidates, JSONArray catalog, String field,
                                String definitionKind)
    {
        if (catalog == null) return;
        for (int index = 0; index < catalog.size(); index++)
        {
            JSONObject owner = catalog.getJSONObject(index);
            Object value = owner == null ? null : owner.get(field);
            if (value == null) continue;
            String canonical = canonical(value);
            candidates.add(new Candidate(owner, field, definitionKind,
                    SecureUtil.sha256(canonical), value, canonical.getBytes(StandardCharsets.UTF_8).length));
        }
    }

    private static void collectNested(List<Candidate> candidates, JSONArray catalog, String parentField,
                                      String field, String definitionKind)
    {
        if (catalog == null) return;
        for (int index = 0; index < catalog.size(); index++)
        {
            JSONObject item = catalog.getJSONObject(index);
            JSONObject owner = item == null ? null : item.getJSONObject(parentField);
            Object value = owner == null ? null : owner.get(field);
            if (value == null) continue;
            String canonical = canonical(value);
            candidates.add(new Candidate(owner, field, definitionKind, SecureUtil.sha256(canonical),
                    value, canonical.getBytes(StandardCharsets.UTF_8).length));
        }
    }

    private static void validate(JSONObject request, JSONArray catalog, String... fields)
    {
        if (catalog == null) return;
        for (int index = 0; index < catalog.size(); index++)
        {
            JSONObject owner = catalog.getJSONObject(index);
            if (owner == null) continue;
            for (String field : fields)
                if (isCatalogReference(owner.get(field))) resolve(request,
                        owner.getJSONObject(field).getString(REF_FIELD));
        }
    }

    private static void validateNested(JSONObject request, JSONArray catalog, String parentField,
                                       String... fields)
    {
        if (catalog == null) return;
        for (int index = 0; index < catalog.size(); index++)
        {
            JSONObject item = catalog.getJSONObject(index);
            JSONObject owner = item == null ? null : item.getJSONObject(parentField);
            if (owner == null) continue;
            for (String field : fields)
                if (isCatalogReference(owner.get(field))) resolve(request,
                        owner.getJSONObject(field).getString(REF_FIELD));
        }
    }

    private static void stripCatalogSchemas(JSONArray catalog, String kind,
            Set<com.aid.context.ExecutionCatalogDemand.Binding> executable)
    {
        if (catalog == null) return;
        for (int index = 0; index < catalog.size(); index++)
        {
            JSONObject item = catalog.getJSONObject(index);
            if (item == null) continue;
            String code = item.getString("TOOL".equals(kind) ? "toolCode" : "skillCode");
            String version = item.getString("TOOL".equals(kind) ? "contractVersion" : "skillVersionId");
            if (!executable.contains(new com.aid.context.ExecutionCatalogDemand.Binding(kind, code, version)))
                continue;
            item.remove("inputSchema");
            item.remove("outputSchema");
            item.remove("outputReferencePolicy");
            JSONObject executionOptions = item.getJSONObject("executionOptions");
            if (executionOptions != null) executionOptions.remove("modelChoices");
        }
    }

    private static boolean isCatalogReference(Object value)
    {
        return value instanceof JSONObject object && object.size() == 1
                && object.getString(REF_FIELD) != null
                && object.getString(REF_FIELD).startsWith(REF_PREFIX);
    }

    private static int savingBytes(Candidate candidate, int occurrences)
    {
        int referenceBytes = bytes(Map.of(REF_FIELD, REF_PREFIX + candidate.kind() + "/"
                + candidate.digest()));
        int definitionOverhead = candidate.digest().length() + candidate.kind().length() + 16;
        return (occurrences - 1) * candidate.bytes()
                - occurrences * Math.max(0, referenceBytes - 2) - definitionOverhead;
    }

    static String canonical(Object value)
    {
        return JSON.toJSONString(sorted(value));
    }

    private static Object sorted(Object value)
    {
        if (value instanceof JSONObject object)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            object.keySet().stream().sorted(Comparator.naturalOrder())
                    .forEach(key -> result.put(key, sorted(object.get(key))));
            return result;
        }
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> result.put(String.valueOf(entry.getKey()), sorted(entry.getValue())));
            return result;
        }
        if (value instanceof JSONArray array)
        {
            List<Object> result = new ArrayList<>(array.size());
            for (Object item : array) result.add(sorted(item));
            return result;
        }
        if (value instanceof List<?> list)
        {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(sorted(item));
            return result;
        }
        return value;
    }

    private static int bytes(Object value)
    {
        return value == null ? 4 : AssistantMainInputContract.serialize(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, Integer> sectionBytes(JSONObject value, int totalBytes)
    {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("toolCatalog", bytes(value.get("toolCatalog")));
        result.put("skillCatalog", bytes(value.get("skillCatalog")));
        result.put("executionContext", bytes(value.get("executionContext")));
        result.put("priorResults", bytes(value.get("priorResults")));
        result.put("artifacts", bytes(value.get("artifacts")));
        result.put("contextLimits", bytes(value.get("contextLimits")));
        result.put("other", Math.max(0, totalBytes - result.values().stream()
                .mapToInt(Integer::intValue).sum()));
        return Map.copyOf(result);
    }

    private record Candidate(JSONObject owner, String field, String kind,
                             String digest, Object value, int bytes) { }

    public record Projection(JSONObject context, int originalBytes, int projectedBytes,
                             int referenceCount, Map<String, Integer> sectionBytes) { }
}
