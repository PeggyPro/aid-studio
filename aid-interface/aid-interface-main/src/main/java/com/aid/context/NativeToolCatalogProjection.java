package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.Objects;

/** Provider-only projection; validation and execution retain the authoritative full catalog. */
public final class NativeToolCatalogProjection {
    private NativeToolCatalogProjection() { }

    /** Apply only at the provider wire boundary, after the archived input contract was validated. */
    public static String projectWire(String wire, String bindingsJson) {
        if (wire == null || bindingsJson == null || bindingsJson.isBlank()) return wire;
        JSONObject body = JSON.parseObject(wire), bindings = JSON.parseObject(bindingsJson);
        if (body == null || bindings == null || !(body.get("tools") instanceof JSONArray providerTools)) return wire;
        JSONArray canonical = new JSONArray();
        for (Object raw : providerTools) {
            if (!(raw instanceof JSONObject tool)) continue;
            if (tool.get("function") instanceof JSONObject function)
                addDefinition(canonical, function.getString("name"), function.get("parameters"));
            else if (tool.get("input_schema") instanceof JSONObject)
                addDefinition(canonical, tool.getString("name"), tool.get("input_schema"));
            else if (tool.get("functionDeclarations") instanceof JSONArray declarations)
                for (Object declaration : declarations)
                    if (declaration instanceof JSONObject function)
                        addDefinition(canonical, function.getString("name"), function.get("parameters"));
        }
        if (canonical.isEmpty()) return wire;
        JSONObject definitions = new JSONObject(); definitions.put("tools", canonical);
        JSONArray messages = body.getJSONArray("messages");
        if (messages == null) messages = body.getJSONArray("contents");
        if (messages == null) return wire;
        boolean changed = false;
        for (Object raw : messages) {
            if (!(raw instanceof JSONObject message) || !"user".equals(message.getString("role"))) continue;
            if (message.get("content") instanceof String text) {
                String replacement = projectEnvelope(text, definitions, bindings);
                if (!text.equals(replacement)) { message.put("content", replacement); changed = true; }
            }
            Object parts = message.containsKey("parts") ? message.get("parts") : message.get("content");
            if (parts instanceof JSONArray list)
                for (Object item : list)
                    if (item instanceof JSONObject part && part.get("text") instanceof String text) {
                        String replacement = projectEnvelope(text, definitions, bindings);
                        if (!text.equals(replacement)) { part.put("text", replacement); changed = true; }
                    }
        }
        return changed ? JSON.toJSONString(body, JSONWriter.Feature.WriteMapNullValue) : wire;
    }

    private static void addDefinition(JSONArray target, String name, Object schema) {
        if (name == null || !(schema instanceof JSONObject)) return;
        JSONObject definition = new JSONObject(); definition.put("name", name); definition.put("inputSchema", schema);
        target.add(definition);
    }

    private static String projectEnvelope(String text, JSONObject definitions, JSONObject bindings) {
        JSONObject envelope;
        try { envelope = JSON.parseObject(text); }
        catch (RuntimeException plainText) { return text; }
        if (envelope == null || !(envelope.get("input") instanceof JSONObject input)
                || !(input.get("toolCatalog") instanceof JSONArray)) return text;
        JSONObject projected = project(input, definitions, bindings);
        if (projected.equals(input)) return text;
        envelope.put("input", projected);
        return JSON.toJSONString(envelope, JSONWriter.Feature.WriteMapNullValue);
    }

    public static JSONObject project(JSONObject source, JSONObject definitions, JSONObject bindings) {
        JSONObject result = JSON.parseObject(JSON.toJSONString(source, JSONWriter.Feature.WriteMapNullValue));
        JSONArray catalog = result.getJSONArray("toolCatalog");
        JSONArray tools = definitions == null ? null : definitions.getJSONArray("tools");
        JSONArray bound = bindings == null ? null : bindings.getJSONArray("tools");
        if (catalog == null || tools == null || bound == null) return result;
        for (Object raw : catalog) {
            if (!(raw instanceof JSONObject entry) || !(entry.get("inputSchema") instanceof JSONObject schema)
                    || entry.getString("toolCode") == null || entry.getString("contractVersion") == null
                    || entry.containsKey("nativeInputSchema") || schema.containsKey("$ref")) continue;
            String canonical = ContextContractProjection.canonical(schema);
            for (Object item : bound) {
                if (!(item instanceof JSONObject binding) || !"TOOL".equals(binding.getString("action"))
                        || !(binding.get("payloadTemplate") instanceof JSONObject payload)
                        || !Objects.equals(entry.getString("toolCode"), payload.getString("toolCode"))
                        || !Objects.equals(entry.getString("contractVersion"), payload.getString("contractVersion"))) continue;
                String name = binding.getString("name");
                if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) continue;
                if (tools.stream().filter(value -> value instanceof JSONObject definition
                        && name.equals(definition.getString("name"))).count() != 1) continue;
                long matches = tools.stream().filter(value -> value instanceof JSONObject definition
                        && name.equals(definition.getString("name"))
                        && definition.get("inputSchema") instanceof JSONObject input
                        && sameInputContract(schema, input)).count();
                if (matches != 1) continue;
                JSONObject reference = new JSONObject();
                reference.put("functionName", name);
                reference.put("schemaDigest", SecureUtil.sha256(canonical));
                reference.put("source", "native function parameters (complete original contract; may add execution guidance)");
                // Small schemas are cheaper to retain than to describe by reference.
                if (reference.toJSONString().length() + 24 >= canonical.length()) continue;
                entry.remove("inputSchema");
                entry.put("nativeInputSchema", reference);
                break;
            }
        }
        return result;
    }

    private static boolean sameInputContract(JSONObject source, JSONObject nativeSchema) {
        String target = ContextContractProjection.canonical(nativeSchema);
        if (ContextContractProjection.canonical(source).equals(target)) return true;
        try {
            // Reuse the authoritative adapter's constraint-preserving parameter transform, including null guidance.
            JSONObject adapted = com.aid.media.provider.NativeFunctionSchemaAdapter.adapt(
                    com.aid.media.provider.TextNativeToolProtocol.Shape.OPENAI_CHAT, source).schema();
            return ContextContractProjection.canonical(adapted).equals(target);
        } catch (RuntimeException unsupported) {
            return false;
        }
    }
}
