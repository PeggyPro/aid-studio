package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit reuse of a successful, exact-version node read; execution still rechecks the target. */
public final class NativeHostNodeInput {
    static final String FIELD = "hostNodeSources";
    static final String REUSE = "reuseNodeParameters";
    static final String DEFAULTS = "hostNodeDefaultInput";
    static final String DEFAULTS_VERSION = "observed-node-defaults/v1";
    private static final Set<String> IMAGE = Set.of(
            com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE,
            com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE_WITH_PARAMETERS);
    private static final Set<String> VIDEO = Set.of(com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.VIDEO_GENERATE);
    private static final Set<String> AUDIO = Set.of(com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.AUDIO_GENERATE,
            com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.MUSIC_GENERATE);
    private NativeHostNodeInput() { }
    public static boolean enabled(AssistantLoopBinding binding) {
        return NativeAssistantBindingPolicy.hostInputs(NativeAssistantBindingPolicy.schemaVersion(binding));
    }
    public static boolean originalInputsEnabled(AssistantLoopBinding binding) {
        return NativeAssistantBindingPolicy.V11.equals(NativeAssistantBindingPolicy.schemaVersion(binding));
    }
    /** Called only after the existing receipt/scope validation, only for the v10 read view. */
    public static Object withSavedModel(String toolCode, String rawOutput, Object sanitized) {
        if (!"canvas.node.read".equals(toolCode) || !(sanitized instanceof JSONObject safe)) return sanitized;
        JSONObject raw = JSON.parseObject(rawOutput);
        for (String field : Set.of("artifactRef", "objectId", "objectVersion"))
            if (!Objects.equals(raw.get(field), safe.get(field))) throw invalid();
        JSONObject content = raw.getJSONObject("content");
        JSONObject metadata = content == null ? null : content.getJSONObject("metadata");
        JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
        if (studio == null || !(studio.get("model") instanceof String model) || model.isBlank()) return sanitized;
        if (model.length() > 100 || !model.equals(com.aid.assistant.service.AssistantObservationSanitizer.project(model, 0)))
            throw invalid();
        JSONObject result = JSON.parseObject(safe.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONObject safeContent = result.getJSONObject("content");
        if (safeContent == null) throw invalid();
        if (safeContent.containsKey("modelCode") && !Objects.equals(safeContent.get("modelCode"), model)) throw invalid();
        safeContent.put("modelCode", model);
        return result;
    }
    /** Host-only exact business inputs; display sanitization must never become execution data. */
    public static Object withOriginalInputs(String toolCode, String rawOutput, Object sanitized) {
        Object projected = withSavedModel(toolCode, rawOutput, sanitized);
        if (!"canvas.node.read".equals(toolCode) || !(projected instanceof JSONObject safe)) return projected;
        JSONObject raw = JSON.parseObject(rawOutput);
        JSONObject content = raw.getJSONObject("content");
        if (content == null) return projected;
        JSONObject result = JSON.parseObject(safe.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONObject target = result.getJSONObject("content");
        if (target == null) throw invalid();
        for (String field : Set.of("prompt", "parameters"))
            if (content.containsKey(field)) target.put(field, content.get(field));
        JSONObject metadata = content.getJSONObject("metadata");
        JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
        if (studio != null && studio.getJSONObject("generationOptions") != null)
            target.put("metadata", JSONObject.of("studioData", JSONObject.of(
                    "generationOptions", studio.getJSONObject("generationOptions"))));
        result.put("hostOriginalNodeInput", true);
        return JSON.parseObject(result.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }

    /** Only verified node-read observation slots are projected; arbitrary Skill data is untouched. */
    public static void sanitizeProviderObservations(JSONObject request) {
        for (String field : Set.of("priorResults", "observations")) {
            JSONArray rows = request.getJSONArray(field);
            if (rows == null) continue;
            for (Object item : rows) {
                if (!(item instanceof JSONObject row) || !"canvas.node.read".equals(row.getString("toolCode"))) continue;
                JSONObject observation = row.getJSONObject("observation");
                if (observation == null || !Boolean.TRUE.equals(observation.get("hostOriginalNodeInput"))) continue;
                JSONObject safe = (JSONObject) com.aid.assistant.service.AssistantObservationSanitizer.project(observation, 0);
                safe.remove("hostOriginalNodeInput");
                row.put("observation", safe);
            }
        }
    }

    static String digest(JSONObject sources) {
        return cn.hutool.crypto.SecureUtil.sha256(JSON.toJSONString(sources,
                JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue));
    }

    static JSONObject sources(JSONObject request, String toolCode) {
        if (!IMAGE.contains(toolCode) && !VIDEO.contains(toolCode) && !AUDIO.contains(toolCode)) return new JSONObject();
        JSONObject result = new JSONObject();
        collect(request.getJSONArray("priorResults"), toolCode, false, result);
        collect(request.getJSONArray("observations"), toolCode, true, result);
        JSONObject context = request.getJSONObject("executionContext");
        JSONArray revisions = context == null ? null : context.getJSONArray("expectedContextRevisions");
        Map<String,String> bound = new java.util.HashMap<>();
        if (revisions != null) for (Object raw : revisions) {
            if (!(raw instanceof JSONObject revision) || !"FREE_CANVAS".equals(revision.getString("kind"))) continue;
            JSONObject ref = revision.getJSONObject("canonicalRef");
            if (ref != null && ref.getString("objectId") != null && ref.getString("version") != null
                    && ref.getString("workspaceId") != null) {
                String key = ref.getString("objectId") + "@" + ref.getString("version");
                String source = "canvas:node:" + ref.getString("workspaceId") + ":" + ref.getString("objectId") + ":" + ref.getString("version");
                String prior = bound.putIfAbsent(key, source);
                if (prior != null && !prior.equals(source)) throw invalid();
            }
        }
        result.entrySet().removeIf(entry -> !Objects.equals(bound.get(entry.getKey()),
                ((JSONObject) entry.getValue()).getString("_sourceRef")) || !bound.containsKey(entry.getKey()));
        return result;
    }

    private static void collect(JSONArray receipts, String code, boolean context, JSONObject result) {
        if (receipts == null) return;
        for (Object raw : receipts) {
            if (!(raw instanceof JSONObject receipt) || !"canvas.node.read".equals(receipt.getString("toolCode"))) continue;
            if (context ? !"SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(receipt.getString("authority"))
                    : !"SUCCEEDED".equals(receipt.getString("status"))) continue;
            if (receipt.containsKey("status") && !"SUCCEEDED".equals(receipt.getString("status"))) continue;
            JSONObject observation = receipt.getJSONObject("observation");
            JSONObject content = observation == null ? null : observation.getJSONObject("content");
            if (content == null || (AUDIO.contains(code)
                    ? !Set.of("VOICE", "AUDIO", "MUSIC").contains(Objects.toString(content.getString("nodeType"), ""))
                    : !Objects.equals(content.getString("nodeType"), VIDEO.contains(code) ? "VIDEO" : "IMAGE"))) continue;
            JSONObject settings = content.getJSONObject("parameters");
            // The legacy image tool cannot represent aspectRatio. Do not advertise reuse
            // through it when that would drop a saved setting; the parameter-aware tool
            // retains the complete input and the ordinary legacy invocation stays intact.
            if (com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE.equals(code)
                    && settings != null && settings.get("aspectRatio") != null) continue;
            String id = observation.getString("objectId"), version = observation.getString("objectVersion");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || version == null || !version.matches("[1-9][0-9]{0,18}")) continue;
            JSONObject values = new JSONObject();
            values.put("_sourceRef", observation.getString("artifactRef"));
            values.put("target", JSONObject.of("objectId", id, "objectVersion", version));
            values.put(com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.AUDIO_GENERATE.equals(code) ? "text" : "prompt", content.get("prompt"));
            JSONObject metadata = content.getJSONObject("metadata");
            JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
            if (content.get("modelCode") != null) values.put("modelCode", content.get("modelCode"));
            else if (studio != null && studio.get("model") != null) values.put("modelCode", studio.get("model"));
            if (AUDIO.contains(code)) {
                boolean music = com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.MUSIC_GENERATE.equals(code);
                JSONObject audioSchema = com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider.businessInputSchema(music
                        ? com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider.MUSIC_GENERATE
                        : com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider.AUDIO_GENERATE);
                JSONObject options = settings == null ? null : settings.getJSONObject("audioParameters");
                if (options == null && studio != null && studio.getJSONObject("generationOptions") != null)
                    options = studio.getJSONObject("generationOptions").getJSONObject("audioParameters");
                // Never advertise lossless reuse when the legacy speech/music tool cannot
                // express a saved official operation parameter. Its original complete-input path remains available.
                if (options != null && !audioSchema.getJSONObject("properties").keySet().containsAll(options.keySet())) continue;
                if (options != null && options.containsKey("modelCode") && values.containsKey("modelCode")
                        && !Objects.equals(options.get("modelCode"), values.get("modelCode"))) continue;
                if (options != null) values.putAll(options);
                values.put(music ? "prompt" : "text", content.get("prompt"));
                String capability = settings == null ? null : settings.getString("audioCapability");
                if (capability == null && studio != null && studio.getJSONObject("generationOptions") != null)
                    capability = studio.getJSONObject("generationOptions").getString("audioCapability");
                if (capability != null) {
                    if (options != null && options.containsKey("capabilityCode")
                            && !Objects.equals(options.get("capabilityCode"), capability)) continue;
                    values.put("capabilityCode", capability);
                }
            } else if (settings != null) {
                Map<String,String> mapping = VIDEO.contains(code)
                        ? Map.of("quality", "resolution", "aspectRatio", "aspectRatio", "duration", "durationSeconds",
                            "audioEnabled", "audio", "capabilityCode", "capabilityCode")
                        : Map.of("quality", "size", "aspectRatio", "aspectRatio", "count", "expectedImageCount", "capabilityCode", "capabilityCode");
                mapping.forEach((source,target) -> { if (settings.containsKey(source)) values.put(target, settings.get(source)); });
            }
            // Node resourceIds can identify displayed output. They are not proof of intended
            // generation inputs; retain explicit resourceIds supplied by the business request.
            String key = id + "@" + version;
            Object prior = result.putIfAbsent(key, values);
            if (prior != null && !prior.equals(values)) throw invalid();
        }
    }

    static JSONObject schema(JSONObject businessSchema) {
        return schema(businessSchema, false);
    }

    static JSONObject schema(JSONObject businessSchema, boolean observedDefaults) {
        JSONObject result = JSON.parseObject(businessSchema.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        JSONArray alternatives = result.getJSONArray("oneOf");
        if (alternatives != null) {
            JSONArray replaced = new JSONArray();
            for (Object value : alternatives) replaced.add(schema((JSONObject) value, observedDefaults));
            result.put("oneOf", replaced);
            return result;
        }
        JSONObject reuse = JSON.parseObject(result.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        reuse.getJSONObject("properties").put(REUSE, JSONObject.of("type", "boolean", "enum", JSONArray.of(true)));
        JSONObject targetSchema = reuse.getJSONObject("properties").getJSONObject("target");
        targetSchema.put("required", JSONArray.of("objectId"));
        targetSchema.put("description", "Choose the exact node ID. Its version may be omitted: host binds the unique successful read version in the current execution context, never the latest database version. An explicitly supplied version must match.");
        reuse.put("required", JSONArray.of("target", REUSE));
        reuse.put("description", "To generate from an exact node already read successfully, set reuseNodeParameters=true and its exact target. Host copies saved prompt, model and generation settings; explicitly supplied business fields override saved values. Provide intended input resourceIds explicitly: displayed prior outputs are not automatically used as references. Missing data is not invented. Other requests keep the ordinary complete-input form.");
        if (observedDefaults) {
            reuse.put("required", JSONArray.of("target"));
            reuse.put("description", "Generate the exact target node using its successfully read, version-bound saved prompt, model and settings by default. Supply only intended overrides; explicit values including null/false/zero remain explicit. reuseNodeParameters=true is optional. No model, input resource or missing saved value is invented. A target without a matching trusted read still requires complete business inputs and all original validation.");
            return reuse;
        }
        JSONObject normal = result;
        return JSONObject.of("type", "object", "oneOf", JSONArray.of(normal, reuse));
    }

    /** Publish reuse only for the exact identities for which the host holds saved inputs. */
    static JSONObject schema(JSONObject businessSchema, boolean observedDefaults, JSONObject sources) {
        return scopedSchema(businessSchema, observedDefaults, sources, false);
    }

    private static JSONObject scopedSchema(JSONObject businessSchema, boolean observedDefaults,
                                            JSONObject sources, boolean discriminated) {
        JSONObject original = JSON.parseObject(businessSchema.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        if (sources == null || sources.isEmpty()) return original;
        JSONArray alternatives = original.getJSONArray("oneOf");
        if (alternatives != null) {
            JSONArray branches = new JSONArray();
            for (Object branch : alternatives) branches.add(scopedSchema((JSONObject) branch, observedDefaults, sources, true));
            original.put("oneOf", branches);
            return original;
        }
        JSONArray identities = new JSONArray();
        JSONArray ids = new JSONArray();
        for (Object raw : sources.values()) {
            if (!(raw instanceof JSONObject saved) || saved.getJSONObject("target") == null) throw invalid();
            boolean compatible = true;
            JSONObject properties = original.getJSONObject("properties");
            for (String field : Set.of("modelCode", "capabilityCode")) {
                JSONObject property = properties == null ? null : properties.getJSONObject(field);
                JSONArray choices = property == null ? null : property.getJSONArray("enum");
                if (choices != null && ((!saved.containsKey(field) && discriminated)
                        || saved.containsKey(field) && !choices.contains(saved.get(field)))) compatible = false;
            }
            if (!compatible) continue;
            JSONObject target = saved.getJSONObject("target");
            String id = target.getString("objectId"), version = target.getString("objectVersion");
            if (id == null || version == null) throw invalid();
            identities.add(JSONObject.of("type", "object", "required", JSONArray.of("objectId"),
                    "properties", JSONObject.of("objectId", JSONObject.of("enum", JSONArray.of(id)),
                            "objectVersion", JSONObject.of("enum", JSONArray.of(version)))));
            if (!ids.contains(id)) ids.add(id);
        }
        if (identities.isEmpty()) return original;
        JSONObject expanded = schema(original, observedDefaults);
        JSONObject reuse = observedDefaults ? expanded : expanded.getJSONArray("oneOf").getJSONObject(1);
        reuse.getJSONObject("properties").getJSONObject("target").put("oneOf", identities);
        if (observedDefaults) {
            // The complete-input path remains available for nodes not represented by a read.
            // Disjoint target sets avoid ambiguous oneOf matches when an observed node supplies overrides.
            JSONObject observedTarget = JSONObject.of("type", "object", "required", JSONArray.of("target"),
                    "properties", JSONObject.of("target", JSONObject.of("type", "object",
                            "required", JSONArray.of("objectId"), "properties", JSONObject.of(
                                    "objectId", JSONObject.of("enum", ids)))));
            JSONArray guards = original.getJSONArray("allOf");
            if (guards == null) { guards = new JSONArray(); original.put("allOf", guards); }
            guards.add(JSONObject.of("not", observedTarget));
        }
        return JSONObject.of("type", "object", "oneOf", JSONArray.of(original, reuse));
    }

    static JSONObject arguments(JSONObject supplied, JSONObject sources) {
        return arguments(supplied, sources, false);
    }

    static JSONObject arguments(JSONObject supplied, JSONObject sources, boolean observedDefaults) {
        return arguments(supplied, sources, observedDefaults, null);
    }

    static JSONObject arguments(JSONObject supplied, JSONObject sources, boolean observedDefaults,
                                JSONObject inputSchema) {
        if (!supplied.containsKey(REUSE) && !observedDefaults) return supplied;
        if (supplied.containsKey(REUSE) && !Boolean.TRUE.equals(supplied.get(REUSE))) throw invalid();
        JSONObject target = supplied.getJSONObject("target");
        if (target == null || !(target.get("objectId") instanceof String)
                || !Set.of("objectId", "objectVersion").containsAll(target.keySet())
                || target.containsKey("objectVersion") && !(target.get("objectVersion") instanceof String)) throw invalid();
        JSONObject saved = null;
        boolean observedIdentity = false;
        for (Object raw : sources.values()) {
            JSONObject source = (JSONObject) raw, identity = source.getJSONObject("target");
            if (!Objects.equals(target.get("objectId"), identity.get("objectId"))) continue;
            observedIdentity = true;
            if (target.containsKey("objectVersion") && !Objects.equals(target.get("objectVersion"), identity.get("objectVersion"))) continue;
            if (saved != null) throw invalid();
            saved = source;
        }
        if (saved == null) {
            if (observedDefaults && !observedIdentity && !supplied.containsKey(REUSE)) return supplied;
            throw invalid();
        }
        JSONObject result = JSON.parseObject(saved.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        result.remove("_sourceRef");
        // Saved settings can belong to another model capability. Only reuse declared fields.
        // Keep explicit caller values intact so the frozen contract still validates them.
        if (inputSchema != null) result.keySet().removeIf(field -> !declares(inputSchema, field));
        result.putAll(supplied);
        result.put("target", new JSONObject(saved.getJSONObject("target")));
        result.remove(REUSE);
        return result;
    }

    private static boolean declares(JSONObject schema, String field) {
        JSONObject properties = schema.getJSONObject("properties");
        if (properties != null && properties.containsKey(field)) return true;
        JSONArray alternatives = schema.getJSONArray("oneOf");
        return alternatives != null && alternatives.stream()
                .anyMatch(branch -> branch instanceof JSONObject option && declares(option, field));
    }

    private static ServiceException invalid() { return new ServiceException("节点参数复用缺少对应版本的成功读取证据"); }
}
