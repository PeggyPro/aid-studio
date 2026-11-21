package com.aid.canvas.runtime;

import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.service.MediaPreparationErrorCode;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.model.definition.ModelInvocationResolver;
import com.aid.model.definition.ModelParameterValidator;
import com.aid.model.definition.ModelRequestParameters;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.service.IAiModelConfigService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Resolves Canvas media settings once into an immutable prepared Tool payload. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantGenerationSettingsResolver {
    private static final Set<String> IMAGE_KEYS = Set.of(
            "aspectRatio", "quality", "count", "capabilityCode");
    private static final Set<String> VIDEO_KEYS = Set.of(
            "aspectRatio", "quality", "duration", "audioEnabled", "videoMode", "capabilityCode");
    private static final Set<String> VIDEO_MODES = Set.of("imageToVideo", "multiParam", "gridVideo");

    private final IAiModelConfigService modelConfigs;
    private final ModelInvocationResolver modelInvocations;
    private final RuntimeScopeAccess scopeAccess;
    private final CanvasMapper canvasMapper;

    public JSONObject prepare(RuntimeToolInvocation invocation, JSONObject arguments) {
        if (invocation == null || arguments == null) throw rejected();
        return switch (invocation.getToolCode()) {
            case CanvasAssistantToolProvider.NODE_CREATE_RELATED -> switch (arguments.getString("nodeKind")) {
                case "IMAGE" -> prepareCreate(invocation, arguments, MediaKind.IMAGE);
                case "VIDEO" -> prepareCreate(invocation, arguments, MediaKind.VIDEO);
                default -> copy(arguments);
            };
            case CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_SETTINGS,
                 CanvasAssistantToolProvider.NODE_CREATE_IMAGE_WITH_VALIDATED_SETTINGS ->
                    prepareCreate(invocation, arguments, MediaKind.IMAGE);
            case CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_SETTINGS,
                 CanvasAssistantToolProvider.NODE_CREATE_VIDEO_WITH_VALIDATED_SETTINGS ->
                    prepareCreate(invocation, arguments, MediaKind.VIDEO);
            case CanvasAssistantToolProvider.NODE_PATCH -> preparePatch(invocation, arguments);
            default -> copy(arguments);
        };
    }

    private JSONObject prepareCreate(RuntimeToolInvocation invocation, JSONObject arguments, MediaKind mediaKind) {
        JSONObject prepared = copy(arguments);
        String requestedModel;
        Map<String, Object> requestedSettings;
        try {
            requestedModel = CanvasAssistantGenerationSettings.model(arguments);
            requestedSettings = CanvasAssistantGenerationSettings.settings(arguments);
        } catch (ServiceException invalid) {
            throw rejected(invalid);
        }
        ResolvedSettings resolved = resolve(invocation.getUserId(), mediaKind, requestedModel, requestedSettings);
        if (resolved.modelCode() == null) prepared.remove("modelCode");
        else prepared.put("modelCode", resolved.modelCode());
        if (resolved.settings().isEmpty()) prepared.remove("generationSettings");
        else prepared.put("generationSettings", new JSONObject(resolved.settings()));
        return prepared;
    }

    private JSONObject preparePatch(RuntimeToolInvocation invocation, JSONObject arguments) {
        JSONObject originalPatch = arguments.getJSONObject("patch");
        if (originalPatch == null || originalPatch.isEmpty()) return copy(arguments);
        PatchTouch touch = patchTouch(originalPatch);
        if (!touch.requiresValidation()) return copy(arguments);

        CanvasAssistantContextManifest.TargetRef target = requireTarget(invocation, arguments);
        RuntimeScopeAccess.Scope scope = scopeAccess.authorizeExactTarget(
                invocation.getRunId(), invocation.getUserId(), lease(invocation.getLease()),
                target.projectId(), target.workspaceId(), target.objectId(), target.objectVersion());
        CanvasObject node = canvasMapper.selectCanvasObject(scope.workspaceId(), scope.objectId());
        if (node == null || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), scope.objectVersion())
                || !"NODE".equals(node.getObjectType())) {
            throw scopeRejected();
        }
        JSONObject current;
        try {
            current = JSON.parseObject(node.getContentJson());
        } catch (RuntimeException invalid) {
            throw scopeRejected(invalid);
        }
        if (current == null) throw scopeRejected();
        JSONObject merged = copy(current);
        mergePatch(merged, originalPatch);
        MediaKind mediaKind;
        JSONObject currentParameters;
        JSONObject currentStudioData;
        JSONObject currentOptions;
        try {
            mediaKind = mediaKind(current.getString("nodeType"));
            currentParameters = optionalObject(current, "parameters");
            currentStudioData = studioData(current, false);
            currentOptions = currentStudioData == null
                    ? null : optionalObject(currentStudioData, "generationOptions");
        } catch (RuntimeToolPreparationRejectedException invalidCurrent) {
            throw scopeRejected(invalidCurrent);
        }
        JSONObject mergedParameters = optionalObject(merged, "parameters");
        JSONObject mergedStudioData = studioData(merged, false);
        JSONObject mergedOptions = mergedStudioData == null
                ? null : optionalObject(mergedStudioData, "generationOptions");

        Map<String, Object> selectedSettings = selectSettingsSource(touch,
                currentParameters, currentOptions, mergedParameters, mergedOptions);
        String mergedModel = mergedStudioData == null ? null : model(mergedStudioData.get("model"));
        ResolvedSettings resolved = resolve(invocation.getUserId(), mediaKind, mergedModel, selectedSettings);

        JSONObject preparedPatch = copy(originalPatch);
        JSONObject metadataPatch = requiredPatchObject(preparedPatch, "metadata");
        JSONObject studioPatch = requiredPatchObject(metadataPatch, "studioData");
        if (resolved.modelCode() == null) studioPatch.put("model", null);
        else studioPatch.put("model", resolved.modelCode());
        projectMirrors(preparedPatch, studioPatch, currentParameters, currentOptions, resolved.settings());

        JSONObject prepared = copy(arguments);
        prepared.put("patch", preparedPatch);
        return prepared;
    }

    private ResolvedSettings resolve(Long userId, MediaKind mediaKind, String requestedModel,
                                     Map<String, Object> rawSettings) {
        if (userId == null || mediaKind == null || rawSettings == null) throw rejected();
        Map<String, Object> settings = structuralSettings(mediaKind, rawSettings);
        if (requestedModel == null) return new ResolvedSettings(null, settings);

        AiModelConfigVo base = modelConfigs.selectByModelCodeForUser(requestedModel.trim(), userId);
        if (base == null) throw new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_UNAVAILABLE);
        if (base.getModelCode() == null || base.getModelCode().isBlank()
                || !mediaKind.modelType().equalsIgnoreCase(base.getModelType())) throw capabilityRejected();
        // An empty settings draft keeps only the canonical model identity. Resolving a default capability here
        // would resurrect a capability after an explicit RFC 7396 settings deletion.
        if (settings.isEmpty()) return new ResolvedSettings(base.getModelCode(), settings);
        try {
            String requestedCapability = text(settings.get("capabilityCode"));
            AiModelConfigVo selected = modelInvocations.select(base, requestedCapability);
            if (selected == null || selected.getResolvedDefinition() == null
                    || selected.getModelCode() == null || selected.getModelCode().isBlank()
                    || selected.getCapabilityCode() == null || selected.getCapabilityCode().isBlank()
                    || !mediaKind.modelType().equalsIgnoreCase(selected.getModelType())) {
                throw capabilityRejected();
            }
            Map<String, Object> canonical = validateSelected(mediaKind, selected, settings);
            canonical.put("capabilityCode", selected.getCapabilityCode());
            return new ResolvedSettings(selected.getModelCode(), canonical);
        } catch (RuntimeToolPreparationRejectedException rejected) {
            throw rejected;
        } catch (ServiceException invalid) {
            RuntimeToolPreparationRejectedException classified = classifyModelRejection(invalid);
            if (classified != null) throw classified;
            throw invalid;
        }
    }

    private static RuntimeToolPreparationRejectedException classifyModelRejection(ServiceException error) {
        MediaPreparationErrorCode preparation = MediaPreparationErrorCode.read(error.getDetailMessage());
        if (preparation == MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID) return rejected(error);
        if (preparation == MediaPreparationErrorCode.MODEL_CAPABILITY_INVALID) {
            return capabilityRejected(error);
        }
        String code = error.getClientErrorData() == null ? null : error.getClientErrorData().getErrorCode();
        if ("MODEL_PARAMETER_INVALID".equals(code)) return rejected(error);
        if (code != null && (code.startsWith("MODEL_CAPABILITY_")
                || code.equals("MODEL_PROTOCOL_UNAVAILABLE"))) return capabilityRejected(error);
        if (ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED.equals(error.getMessage())
                || ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED.equals(error.getMessage())) {
            return rejected(error);
        }
        return null;
    }

    private Map<String, Object> validateSelected(MediaKind mediaKind, AiModelConfigVo selected,
                                                 Map<String, Object> settings) {
        ModelCapabilityDefinition definition = selected.getResolvedDefinition();
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, Object> canonical = new LinkedHashMap<>();
        if (mediaKind == MediaKind.IMAGE) {
            String quality = settings.containsKey("quality")
                    ? choice(definition, "size", text(settings.get("quality"))) : null;
            String ratio = text(settings.get("aspectRatio"));
            ModelCapabilityResolver.ImageSizeSpec imageSpec = quality == null && ratio == null ? null
                    : ModelCapabilityResolver.resolveImageSpec(selected, quality, ratio);
            if (quality != null) {
                if (imageSpec == null || imageSpec.size() == null
                        || !Objects.equals(normalizedChoice("size", quality),
                        normalizedChoice("size", imageSpec.size()))) throw rejected();
                parameters.put("size", quality);
                canonical.put("quality", quality);
            }
            if (ratio != null) {
                ModelParameter ratioField = fieldAt(definition, "options.aspectRatio");
                if (ratioField == null) {
                    if (quality == null || !pixelSize(quality) || imageSpec == null
                            || imageSpec.aspectRatio() != null) throw rejected();
                    canonical.put("aspectRatio", displayRatio(ratio));
                } else {
                    String canonicalRatio = choice(ratioField, "options.aspectRatio", ratio);
                    if (imageSpec == null || imageSpec.aspectRatio() == null
                            || !Objects.equals(normalizedChoice("aspectRatio", canonicalRatio),
                            normalizedChoice("aspectRatio", imageSpec.aspectRatio()))) {
                        throw rejected();
                    }
                    putPath(parameters, "options", "aspectRatio", canonicalRatio);
                    canonical.put("aspectRatio", canonicalRatio);
                }
            }
            if (settings.containsKey("count")) {
                requireField(definition, "expectedImageCount");
                Integer count = integer(settings.get("count"), 1, 100);
                parameters.put("expectedImageCount", count);
                canonical.put("count", count);
            }
        } else {
            if (settings.containsKey("quality")) {
                String quality = choice(definition, "options.resolution", text(settings.get("quality")));
                putPath(parameters, "options", "resolution", quality);
                canonical.put("quality", quality);
            }
            if (settings.containsKey("aspectRatio")) {
                String ratio = choice(definition, "aspectRatio", text(settings.get("aspectRatio")));
                parameters.put("aspectRatio", ratio);
                canonical.put("aspectRatio", ratio);
            }
            if (settings.containsKey("duration")) {
                requireField(definition, "durationSeconds");
                Integer duration = integer(settings.get("duration"), 1, 600);
                parameters.put("durationSeconds", duration);
                canonical.put("duration", duration);
            }
            if (settings.containsKey("audioEnabled")) {
                if (!ModelRequestParameters.writeDeclaredVideoAudio(definition, parameters,
                        (Boolean) settings.get("audioEnabled"))) throw rejected();
                canonical.put("audioEnabled", settings.get("audioEnabled"));
            }
            if (settings.containsKey("videoMode")) {
                canonical.put("videoMode", videoMode(text(settings.get("videoMode"))));
            }
        }
        ModelParameterValidator.validateKnownSubset(definition, parameters);
        return canonical;
    }

    private static Map<String, Object> structuralSettings(MediaKind mediaKind, Map<String, Object> source) {
        Set<String> allowed = mediaKind == MediaKind.IMAGE ? IMAGE_KEYS : VIDEO_KEYS;
        if (!allowed.containsAll(source.keySet()) || !CanvasAssistantGenerationSettings.valid(source)) {
            throw rejected();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "count" -> result.put("count", integer(value, 1, 100));
                case "duration" -> result.put("duration", integer(value, 1, 600));
                case "audioEnabled" -> result.put("audioEnabled", value);
                case "videoMode" -> result.put("videoMode", videoMode(text(value)));
                default -> result.put(entry.getKey(), text(value));
            }
        }
        return result;
    }

    private static Map<String, Object> selectSettingsSource(PatchTouch touch,
                                                            JSONObject currentParameters,
                                                            JSONObject currentOptions,
                                                            JSONObject mergedParameters,
                                                            JSONObject mergedOptions) {
        if (touch.parameters() && touch.generationOptions()) {
            Map<String, Object> parameters = map(mergedParameters);
            Map<String, Object> options = map(mergedOptions);
            if (!same(parameters, options)) throw rejected();
            return parameters;
        }
        if (touch.parameters()) return map(mergedParameters);
        if (touch.generationOptions()) return map(mergedOptions);
        if (currentParameters != null && currentOptions != null
                && !same(map(currentParameters), map(currentOptions))) throw rejected();
        return currentParameters != null ? map(currentParameters) : map(currentOptions);
    }

    private static void projectMirrors(JSONObject preparedPatch, JSONObject studioPatch,
                                       JSONObject currentParameters, JSONObject currentOptions,
                                       Map<String, Object> settings) {
        preparedPatch.put("parameters", mirrorPatch(currentParameters, settings));
        if (settings.isEmpty()) studioPatch.put("generationOptions", null);
        else studioPatch.put("generationOptions", mirrorPatch(currentOptions, settings));
    }

    private static JSONObject mirrorPatch(JSONObject current, Map<String, Object> canonical) {
        JSONObject patch = new JSONObject();
        if (current != null) {
            for (String key : current.keySet()) if (!canonical.containsKey(key)) patch.put(key, null);
        }
        canonical.forEach(patch::put);
        return patch;
    }

    private static PatchTouch patchTouch(JSONObject patch) {
        boolean parameters = patch.containsKey("parameters");
        boolean model = false;
        boolean options = false;
        if (patch.containsKey("metadata")) {
            Object rawMetadata = patch.get("metadata");
            if (rawMetadata == null || !(rawMetadata instanceof Map<?, ?>)) {
                model = true;
                options = true;
            } else {
                JSONObject metadata = object(rawMetadata);
                if (metadata.containsKey("studioData")) {
                    Object rawStudio = metadata.get("studioData");
                    if (rawStudio == null || !(rawStudio instanceof Map<?, ?>)) {
                        model = true;
                        options = true;
                    } else {
                        JSONObject studio = object(rawStudio);
                        model = studio.containsKey("model");
                        options = studio.containsKey("generationOptions");
                    }
                }
            }
        }
        return new PatchTouch(parameters, model, options);
    }

    private static CanvasAssistantContextManifest.TargetRef requireTarget(RuntimeToolInvocation invocation,
                                                                          JSONObject arguments) {
        JSONObject target = arguments.getJSONObject("target");
        if (target == null || !Set.of("objectId", "objectVersion").containsAll(target.keySet())) {
            throw scopeRejected();
        }
        return CanvasAssistantContextManifest.requireTarget(invocation, target.getString("objectId"),
                target.getString("objectVersion"), true);
    }

    private static RuntimeScopeAccess.Lease lease(RuntimeLease lease) {
        if (lease == null) throw scopeRejected();
        return new RuntimeScopeAccess.Lease(lease.getOwner(), lease.getFencingToken(), lease.getLeaseUntil());
    }

    private static JSONObject studioData(JSONObject content, boolean required) {
        JSONObject metadata = optionalObject(content, "metadata");
        JSONObject studio = metadata == null ? null : optionalObject(metadata, "studioData");
        if (required && studio == null) throw rejected();
        return studio;
    }

    private static JSONObject optionalObject(JSONObject parent, String key) {
        if (parent == null || !parent.containsKey(key) || parent.get(key) == null) return null;
        if (!(parent.get(key) instanceof Map<?, ?>)) throw rejected();
        return object(parent.get(key));
    }

    private static JSONObject requiredPatchObject(JSONObject parent, String key) {
        if (parent.containsKey(key) && !(parent.get(key) instanceof Map<?, ?>)) throw rejected();
        JSONObject value = parent.containsKey(key) ? object(parent.get(key)) : new JSONObject();
        parent.put(key, value);
        return value;
    }

    private static void mergePatch(JSONObject target, JSONObject patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                target.remove(entry.getKey());
            } else if (target.get(entry.getKey()) instanceof Map<?, ?>
                    && entry.getValue() instanceof Map<?, ?>) {
                JSONObject nested = object(target.get(entry.getKey()));
                mergePatch(nested, object(entry.getValue()));
                target.put(entry.getKey(), nested);
            } else {
                target.put(entry.getKey(), copied(entry.getValue()));
            }
        }
    }

    private static ModelParameter requireField(ModelCapabilityDefinition definition, String path) {
        ModelParameter field = fieldAt(definition, path);
        if (field == null) throw rejected();
        return field;
    }

    private static ModelParameter fieldAt(ModelCapabilityDefinition definition, String path) {
        List<ModelParameter> fields = definition == null ? null : definition.getParameters();
        ModelParameter current = null;
        for (String part : path.split("\\.")) {
            current = fields == null ? null : fields.stream()
                    .filter(field -> Objects.equals(part, field.getName())).findFirst().orElse(null);
            if (current == null) return null;
            fields = current.getProperties();
        }
        return current;
    }

    private static String choice(ModelCapabilityDefinition definition, String path, String requested) {
        return choice(requireField(definition, path), path, requested);
    }

    private static String choice(ModelParameter field, String path, String requested) {
        if (requested == null) throw rejected();
        if (field.getChoices() == null || field.getChoices().isEmpty()) return requested;
        String normalized = normalizedChoice(path, requested);
        return field.getChoices().stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(value -> Objects.equals(normalizedChoice(path, value), normalized))
                .findFirst().orElseThrow(CanvasAssistantGenerationSettingsResolver::rejected);
    }

    private static String normalizedChoice(String path, String value) {
        return ModelCapabilityResolver.normalize(value);
    }

    private static boolean pixelSize(String value) {
        return value != null && normalizedChoice("size", value).matches("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}");
    }

    private static String displayRatio(String value) {
        String result = normalizedChoice("aspectRatio", value);
        if (!ModelCapabilityResolver.isConcreteAspectRatio(result)) throw rejected();
        return result;
    }

    private static String videoMode(String value) {
        if (value == null) throw rejected();
        return VIDEO_MODES.stream().filter(item -> item.equalsIgnoreCase(value.trim()))
                .findFirst().orElseThrow(CanvasAssistantGenerationSettingsResolver::rejected);
    }

    private static Integer integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()
                || number.intValue() < minimum || number.intValue() > maximum) throw rejected();
        return number.intValue();
    }

    private static String model(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 100
                || !text.matches("[A-Za-z0-9._:/-]+")) throw rejected();
        return text.trim();
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 100
                || text.chars().anyMatch(Character::isISOControl)) throw rejected();
        return text.trim();
    }

    private static MediaKind mediaKind(String nodeType) {
        if ("IMAGE".equals(nodeType)) return MediaKind.IMAGE;
        if ("VIDEO".equals(nodeType)) return MediaKind.VIDEO;
        throw rejected();
    }

    private static void putPath(Map<String, Object> target, String object, String field, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) target.computeIfAbsent(object,
                ignored -> new LinkedHashMap<>());
        nested.put(field, value);
    }

    private static Map<String, Object> map(JSONObject value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private static boolean same(Map<String, Object> first, Map<String, Object> second) {
        return Objects.equals(JSON.toJSONString(ordered(first)), JSON.toJSONString(ordered(second)));
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> result = new TreeMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw rejected();
                result.put(key, ordered(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> values) return values.stream().map(
                CanvasAssistantGenerationSettingsResolver::ordered).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    private static JSONObject object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw rejected();
        return (JSONObject) copied(value);
    }

    private static JSONObject copy(JSONObject value) {
        return object(value);
    }

    private static Object copied(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> values) {
            JSONObject result = new JSONObject();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw rejected();
                result.put(key, copied(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> values) {
            List<Object> result = new ArrayList<>(values.size());
            for (Object item : values) result.add(copied(item));
            return result;
        }
        throw rejected();
    }

    private static RuntimeToolPreparationRejectedException rejected() {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_PARAMETERS_INVALID);
    }

    private static RuntimeToolPreparationRejectedException rejected(Throwable cause) {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_PARAMETERS_INVALID, cause);
    }

    private static RuntimeToolPreparationRejectedException capabilityRejected() {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_CAPABILITY_INVALID);
    }

    private static RuntimeToolPreparationRejectedException capabilityRejected(Throwable cause) {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_CAPABILITY_INVALID, cause);
    }

    private static RuntimeToolPreparationRejectedException scopeRejected() {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
    }

    private static RuntimeToolPreparationRejectedException scopeRejected(Throwable cause) {
        return new RuntimeToolPreparationRejectedException(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID, cause);
    }

    private enum MediaKind {
        IMAGE("image"), VIDEO("video");
        private final String modelType;
        MediaKind(String modelType) { this.modelType = modelType; }
        String modelType() { return modelType; }
    }

    private record ResolvedSettings(String modelCode, Map<String, Object> settings) { }
    private record PatchTouch(boolean parameters, boolean model, boolean generationOptions) {
        boolean requiresValidation() { return parameters || model || generationOptions; }
    }
}
