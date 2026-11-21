package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.error.AssistantPlanningPreparationRejectedException;
import com.aid.media.provider.TextNativeToolProtocol;
import com.aid.media.provider.NativeFunctionSchemaAdapter;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps canonical Assistant functions to one exact provider protocol. */
public final class NativeTextToolRequestAdapter
{
    static final int MAX_DESCRIPTION_CHARACTERS = 1000;
    private NativeTextToolRequestAdapter() { }

    /** Align current explicit-decision turns with the non-thinking Chat wire contract. */
    public static Adapted adapt(String protocol, String canonicalToolsJson,
                                String nativeBindingsJson, Boolean reasoningEnabled)
    {
        Adapted legacy = adapt(protocol, canonicalToolsJson);
        // Null is not proof that thinking is disabled. Preserve existing provider behavior then.
        if (!Boolean.FALSE.equals(reasoningEnabled) || StrUtil.isBlank(nativeBindingsJson)) return legacy;
        JSONObject bindings;
        try { bindings = JSON.parseObject(nativeBindingsJson); }
        catch (RuntimeException invalid)
        {
            throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
        }
        if (bindings == null || !(NativeAssistantBindingPolicy.V8.equals(bindings.getString("schemaVersion")) || NativeAssistantBindingPolicy.methodResults(bindings.getString("schemaVersion")))
                || !TextNativeToolProtocol.Shape.OPENAI_CHAT.equals(TextNativeToolProtocol.resolve(protocol)))
            return legacy;
        // The model still selects a real exposed function and supplies its original arguments.
        // No particular business tool is forced, and result/schema/permission checks remain intact.
        return new Adapted(legacy.tools(), "required", legacy.parallelControl());
    }

    public static Adapted adapt(String protocol, String canonicalToolsJson)
    {
        TextNativeToolProtocol.ProtocolContract contract = TextNativeToolProtocol.contract(protocol);
        if (contract == null)
            throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_PROTOCOL_UNAVAILABLE);
        TextNativeToolProtocol.Shape shape = contract.wireShape();
        if (StrUtil.isBlank(canonicalToolsJson))
            throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
        JSONObject envelope;
        try { envelope = JSON.parseObject(canonicalToolsJson); }
        catch (RuntimeException invalid)
        {
            throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
        }
        Object schemaVersion = envelope == null ? null : envelope.get("schemaVersion");
        Object toolsValue = envelope == null ? null : envelope.get("tools");
        JSONArray values = toolsValue instanceof JSONArray array ? array : null;
        if (!"assistant-native-tools/v1".equals(schemaVersion)
                || values == null || values.isEmpty() || values.size() > 64)
            throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
        List<Map<String, Object>> tools = new ArrayList<>(values.size());
        Set<String> names = new HashSet<>();
        for (int index = 0; index < values.size(); index++)
        {
            Object toolValue = values.get(index);
            JSONObject value = toolValue instanceof JSONObject object ? object : null;
            Object nameValue = value == null ? null : value.get("name");
            Object descriptionValue = value == null ? null : value.get("description");
            String name = nameValue instanceof String text ? text : null;
            String description = descriptionValue instanceof String text ? text : null;
            Object inputSchemaValue = value == null ? null : value.get("inputSchema");
            JSONObject inputSchema = inputSchemaValue instanceof JSONObject schema ? schema : null;
            Object schemaType = inputSchema == null ? null : inputSchema.get("type");
            if (StrUtil.isBlank(name) || !name.matches("[A-Za-z0-9_-]{1,64}") || !names.add(name)
                    || description == null || description.length() > MAX_DESCRIPTION_CHARACTERS || inputSchema == null
                    || !"object".equals(schemaType))
                throw rejected(AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
            NativeFunctionSchemaAdapter.AdaptedSchema providerSchema =
                    NativeFunctionSchemaAdapter.adapt(shape, inputSchema);
            Map<String, Object> definition = new LinkedHashMap<>();
            if (TextNativeToolProtocol.Shape.OPENAI_CHAT.equals(shape))
            {
                definition.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", name);
                function.put("description", description);
                function.put("parameters", providerSchema.schema());
                if (TextNativeToolProtocol.requiresStrictToolArguments(contract.providerProtocol()))
                    function.put("strict", true);
                definition.put("function", function);
            }
            else if (TextNativeToolProtocol.Shape.OPENAI_RESPONSES.equals(shape))
            {
                definition.put("type", "function");
                definition.put("name", name);
                definition.put("description", description);
                definition.put("parameters", providerSchema.schema());
                definition.put("strict", providerSchema.strict());
            }
            else
            {
                definition.put("name", name);
                definition.put("description", description);
                definition.put("input_schema", providerSchema.schema());
            }
            tools.add(definition);
        }
        Object toolChoice = TextNativeToolProtocol.ParallelControl.TOOL_CHOICE_DISABLE.equals(
                contract.parallelControl())
                ? Map.of("type", "auto", "disable_parallel_tool_use", true) : "auto";
        return new Adapted(List.copyOf(tools), toolChoice, contract.parallelControl());
    }

    private static AssistantPlanningPreparationRejectedException rejected(
            AssistantPlanningPreparationRejectedException.Reason reason)
    {
        return new AssistantPlanningPreparationRejectedException(reason);
    }

    public record Adapted(List<Map<String, Object>> tools,
                          Object toolChoice,
                          TextNativeToolProtocol.ParallelControl parallelControl)
    {
        /** The exact provider request options for one-call-per-turn Assistant semantics. */
        public Map<String, Object> requestOptions()
        {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("tools", tools);
            options.put("tool_choice", toolChoice);
            if (TextNativeToolProtocol.ParallelControl.TOP_LEVEL_FALSE.equals(parallelControl))
                options.put("parallel_tool_calls", false);
            return options;
        }
    }
}
