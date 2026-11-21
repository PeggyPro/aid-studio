package com.aid.runtime.assistant;

import com.aid.aid.domain.model.ModelParameter;
import com.aid.model.definition.ModelRequestParameters;
import com.aid.canvas.runtime.CanvasAssistantToolProvider;
import com.aid.canvas.runtime.CanvasAssistantMediaToolProvider;
import com.aid.runtime.tool.RuntimeCapabilityInputSchema;
import com.aid.runtime.tool.RuntimeModelParameterContract;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bind node configuration to the same verified model definitions used by media execution. */
final class NativeNodeGenerationSettingsProjection {
    private static final Set<String> IMAGE = Set.of(CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS);
    private static final Set<String> VIDEO = Set.of(CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
            CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS);

    private NativeNodeGenerationSettingsProjection() { }

    static JSONObject project(JSONObject request, String toolCode, JSONObject schema) {
        if (IMAGE.contains(toolCode)) return bind(request, schema, "IMAGE");
        if (VIDEO.contains(toolCode)) return bind(request, schema, "VIDEO");
        if (!CanvasAssistantToolProvider.NODE_CREATE_RELATED.equals(toolCode)) return schema;
        JSONArray branches = schema.getJSONArray("oneOf");
        if (branches == null) throw new IllegalArgumentException("Related node branches missing");
        JSONArray projected = new JSONArray();
        for (Object raw : branches) {
            JSONObject branch = (JSONObject) raw;
            JSONObject fields = branch.getJSONObject("properties");
            JSONObject kind = fields == null ? null : fields.getJSONObject("nodeKind");
            JSONArray kinds = kind == null ? null : kind.getJSONArray("enum");
            String media = kinds != null && kinds.size() == 1 ? kinds.getString(0) : null;
            JSONObject result = Set.of("IMAGE", "VIDEO").contains(media == null ? "" : media)
                    ? bind(request, branch, media) : branch;
            JSONArray alternatives = result.getJSONArray("oneOf");
            if (alternatives == null) projected.add(result);
            else projected.addAll(alternatives);
        }
        return JSONObject.of("type", "object", "oneOf", projected);
    }

    static void bindStoredSettings(String toolCode, JSONObject arguments) {
        if (!IMAGE.contains(toolCode) && !VIDEO.contains(toolCode)
                && !CanvasAssistantToolProvider.NODE_CREATE_RELATED.equals(toolCode)) return;
        JSONObject settings = arguments.getJSONObject("generationSettings");
        if (settings == null) return;
        if (settings.containsKey("size")) settings.put("quality", settings.remove("size"));
        if (settings.containsKey("resolution")) settings.put("quality", settings.remove("resolution"));
    }

    private static JSONObject bind(JSONObject request, JSONObject source, String mediaType) {
        JSONObject properties = source.getJSONObject("properties");
        if (properties == null || !properties.containsKey("generationSettings")) return source;
        JSONArray alternatives = new JSONArray();
        // Empty/unselected-model drafts remain available. Model-dependent configuration requires DETAIL.
        JSONObject draft = copy(source);
        draft.getJSONObject("properties").put("generationSettings", JSONObject.of(
                "type", "object", "properties", new JSONObject(), "maxProperties", 0,
                "additionalProperties", false));
        JSONArray required = draft.getJSONArray("required");
        if (required != null) required.remove("generationSettings");
        alternatives.add(draft);
        String generationTool = "IMAGE".equals(mediaType)
                ? CanvasAssistantMediaToolProvider.IMAGE_GENERATE_WITH_PARAMETERS : CanvasAssistantMediaToolProvider.VIDEO_GENERATE;
        Map<String, JSONObject> models = NativeCapabilityInputProjection.nodeContracts(request, generationTool, mediaType);
        for (JSONObject model : models.values()) {
            JSONObject capabilities = model.getJSONObject("capabilities");
            for (String capability : capabilities.keySet()) {
                JSONObject support = capabilities.getJSONObject(capability);
                JSONArray rawParameters = support.getJSONArray("nodeParameterSchema");
                if (rawParameters == null) continue;
                if (alternatives.size() >= 33) throw new IllegalArgumentException("Too many node model contracts");
                List<ModelParameter> parameters = rawParameters.toJavaList(ModelParameter.class);
                JSONObject settings = copy(properties.getJSONObject("generationSettings"));
                JSONObject virtualProperties = copy(settings.getJSONObject("properties"));
                virtualProperties.keySet().retainAll("IMAGE".equals(mediaType)
                        ? Set.of("aspectRatio", "quality", "count", "capabilityCode")
                        : Set.of("aspectRatio", "quality", "duration", "audioEnabled", "videoMode", "capabilityCode"));
                String resolutionField = "IMAGE".equals(mediaType) ? "size" : "resolution";
                if (virtualProperties.containsKey("quality"))
                    virtualProperties.put(resolutionField, virtualProperties.remove("quality"));
                if (virtualProperties.containsKey("duration")) {
                    virtualProperties.getJSONObject("duration").put("type", "integer");
                    virtualProperties.getJSONObject("duration").put("minimum", 1);
                }
                virtualProperties.put("modelCode", copy(properties.getJSONObject("modelCode")));
                JSONObject virtual = JSONObject.of("type", "object", "properties", virtualProperties,
                        "additionalProperties", false);
                Map<String, String> aliases = new LinkedHashMap<>();
                aliases.put(resolutionField, "IMAGE".equals(mediaType) ? "size" : "options.resolution");
                aliases.put("aspectRatio", "IMAGE".equals(mediaType) ? "options.aspectRatio" : "aspectRatio");
                aliases.put("count", "expectedImageCount");
                aliases.put("duration", "durationSeconds");
                String audioPath = ModelRequestParameters.declaredVideoAudioPath(parameters);
                if (audioPath != null) aliases.put("audioEnabled", audioPath);
                boolean displayRatio = "IMAGE".equals(mediaType) && hasPixelSize(parameters)
                        && !hasPath(parameters, "options.aspectRatio");
                Set<String> layoutFields = "VIDEO".equals(mediaType) ? Set.of("videoMode")
                        : displayRatio ? Set.of("aspectRatio") : Set.of();
                Map<String, Object> contract = RuntimeModelParameterContract.project(
                        virtual.toJSONString(), parameters, aliases, layoutFields);
                // Node creation is a draft, not a generation request: provider-required content is
                // still validated at generation. Only the selected capability identity is required here.
                contract = new LinkedHashMap<>(contract);
                contract.put("requiredFields", List.of());
                JSONObject bound = RuntimeCapabilityInputSchema.bind(virtual,
                        model.getString("modelCode"), capability, contract, layoutFields);
                if (displayRatio) {
                    // The existing node resolver also accepts a display-only ratio paired with
                    // an explicit pixel size. Preserve that path without inventing an upstream field.
                    bound.put("dependentSchemas", JSONObject.of("aspectRatio", JSONObject.of(
                            "required", JSONArray.of(resolutionField), "properties", JSONObject.of(
                                    resolutionField, JSONObject.of("pattern", "^[1-9][0-9]{0,4}[xX][1-9][0-9]{0,4}$")))));
                }
                JSONObject node = copy(source);
                JSONObject nodeProperties = node.getJSONObject("properties");
                JSONObject boundFields = bound.getJSONObject("properties");
                nodeProperties.put("modelCode", boundFields.remove("modelCode"));
                bound.getJSONArray("required").remove("modelCode");
                nodeProperties.put("generationSettings", bound);
                JSONArray nodeRequired = node.getJSONArray("required");
                if (nodeRequired == null) { nodeRequired = new JSONArray(); node.put("required", nodeRequired); }
                for (String key : List.of("modelCode", "generationSettings"))
                    if (!nodeRequired.contains(key)) nodeRequired.add(key);
                alternatives.add(node);
            }
        }
        // No discovery receipt means no invented provider settings, while prompt-only creation works.
        return alternatives.size() == 1 ? draft : JSONObject.of("type", "object", "oneOf", alternatives);
    }

    private static boolean hasPixelSize(List<ModelParameter> parameters) {
        return parameters.stream().anyMatch(field -> "size".equals(field.getName())
                && (field.getChoices() == null || field.getChoices().isEmpty()
                || field.getChoices().stream().anyMatch(value -> value instanceof String text
                        && text.matches("[1-9][0-9]{0,4}[xX][1-9][0-9]{0,4}"))));
    }

    private static boolean hasPath(List<ModelParameter> parameters, String path) {
        for (String part : path.split("\\.")) {
            ModelParameter found = parameters == null ? null : parameters.stream()
                    .filter(field -> part.equals(field.getName())).findFirst().orElse(null);
            if (found == null) return false;
            parameters = found.getProperties();
        }
        return true;
    }

    private static JSONObject copy(JSONObject value) {
        return value == null ? new JSONObject() : JSON.parseObject(value.toJSONString());
    }
}
