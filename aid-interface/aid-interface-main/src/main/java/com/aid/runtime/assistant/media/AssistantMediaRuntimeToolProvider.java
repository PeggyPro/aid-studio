package com.aid.runtime.assistant.media;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetViews.AudioQuoteView;
import com.aid.billing.dto.BillingBudgetViews.ImageQuoteView;
import com.aid.billing.dto.BillingBudgetViews.QuoteView;
import com.aid.billing.dto.BillingBudgetViews.VideoQuoteView;
import com.aid.billing.service.AssistantMediaBillingQuoteService;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.MinimaxMusicGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.impl.MinimaxMusicGenerationService;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.model.dto.AiModelListRequest;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.ModelCapabilityOptionVO;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolBillingIntent;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolBudgetOffer;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.aid.runtime.tool.RuntimeToolPreparationValidation;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Supplier;

/**
 * Two-phase Assistant media tools. Preparation only normalizes and quotes; execution enters the
 * existing durable media task, billing and Provider admission path with an already-reserved budget.
 */
@Component
@RequiredArgsConstructor
public class AssistantMediaRuntimeToolProvider implements RuntimeToolProvider {

    public static final String IMAGE_GENERATE = "media.image.generate";
    public static final String VIDEO_GENERATE = "media.video.generate";
    public static final String AUDIO_GENERATE = "media.audio.generate";
    public static final String MUSIC_GENERATE = "media.music.generate";

    private static final String VERSION = "1.0.0";
    private static final String MUSIC_VERSION = "1.1.0";
    private static final String PREPARED_SCHEMA = "assistant-media-prepared/v1";
    private static final Set<String> TOOL_CODES = Set.of(
            IMAGE_GENERATE, VIDEO_GENERATE, AUDIO_GENERATE, MUSIC_GENERATE);
    private static final Set<String> WAITING = Set.of("QUEUED", "PENDING", "PROCESSING", "SUBMITTING");
    private static final Set<String> FAILED = Set.of("FAILED", "CANCELLED", "CANCELED");

    private static final String IMAGE_INPUT = """
            {"type":"object","required":["modelCode","prompt"],"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100},
              "capabilityCode":{"type":["string","null"],"maxLength":100},
              "prompt":{"type":"string","minLength":1,"maxLength":100000},
              "negativePrompt":{"type":["string","null"],"maxLength":10000},
              "size":{"type":["string","null"],"maxLength":40},
              "expectedImageCount":{"type":["integer","null"],"minimum":1,"maximum":16}
            },"additionalProperties":false}
            """;
    private static final String VIDEO_INPUT = """
            {"type":"object","required":["modelCode","prompt"],"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100},
              "capabilityCode":{"type":["string","null"],"maxLength":100},
              "prompt":{"type":"string","minLength":1,"maxLength":100000},
              "durationSeconds":{"type":["integer","null"],"minimum":1,"maximum":3600},
              "aspectRatio":{"type":["string","null"],"maxLength":20},
              "audio":{"type":["boolean","null"]},
              "bgm":{"type":["boolean","null"]},
              "audioType":{"type":["string","null"],"enum":["all","speech_only","sound_effect_only",null]},
              "voiceId":{"type":["string","null"],"maxLength":128}
            },"additionalProperties":false}
            """;
    private static final String AUDIO_INPUT = """
            {"type":"object","required":["modelCode","text"],"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100},
              "capabilityCode":{"type":["string","null"],"maxLength":100},
              "text":{"type":"string","minLength":1,"maxLength":100000},
              "voiceCode":{"type":["string","null"],"maxLength":128},
              "language":{"type":["string","null"],"maxLength":20},
              "emotion":{"type":["string","null"],"maxLength":40},
              "emotionScale":{"type":["integer","null"],"minimum":1,"maximum":5},
              "speechRate":{"type":["integer","null"],"minimum":-50,"maximum":100},
              "loudnessRate":{"type":["integer","null"],"minimum":-50,"maximum":100},
              "pitch":{"type":["integer","null"],"minimum":-12,"maximum":12},
              "audioFormat":{"type":["string","null"],"maxLength":20},
              "sampleRate":{"type":["integer","null"],"minimum":8000,"maximum":48000},
              "enableTimestamp":{"type":["boolean","null"]}
            },"additionalProperties":false}
            """;
    private static final String MUSIC_INPUT = """
            {"type":"object","required":["modelCode"],"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100},
              "capabilityCode":{"type":["string","null"],"maxLength":100},
              "prompt":{"type":["string","null"],"maxLength":10000},
              "lyrics":{"type":["string","null"],"maxLength":100000},
              "instrumental":{"type":["boolean","null"]},
              "lyricsOptimizer":{"type":["boolean","null"]},
              "audioFormat":{"type":["string","null"],"maxLength":20},
              "sampleRate":{"type":["integer","null"],"minimum":8000,"maximum":48000},
              "bitrate":{"type":["integer","null"],"minimum":32000,"maximum":320000},
              "aigcWatermark":{"type":["boolean","null"]},
              "referenceAudioId":{"type":["string","null"],"pattern":"^[1-9][0-9]{0,18}$"},
              "coverFeatureId":{"type":["string","null"],"maxLength":256}
            },"additionalProperties":false}
            """;
    private static final String OUTPUT = """
            {"type":"object","required":["mediaTaskId","status","mediaType","modelCode"],"properties":{
              "mediaTaskId":{"type":"string"},"status":{"type":"string"},
              "mediaType":{"enum":["IMAGE","VIDEO","AUDIO","MUSIC"]},
              "modelCode":{"type":"string"}
            },"additionalProperties":false}
            """;

    private final AssistantMediaBillingQuoteService quoteService;
    private final IMediaGenerationService mediaGenerationService;
    private final MinimaxMusicGenerationService musicGenerationService;
    private final IAiModelBusinessService modelBusinessService;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(IMAGE_GENERATE, "Generate image", "Quote and submit one image media task.",
                        IMAGE_INPUT, "IMAGE"),
                descriptor(VIDEO_GENERATE, "Generate video", "Quote and submit one video media task.",
                        VIDEO_INPUT, "VIDEO"),
                descriptor(AUDIO_GENERATE, "Generate audio", "Quote and submit one speech or audio media task.",
                        AUDIO_INPUT, "AUDIO"),
                descriptor(MUSIC_GENERATE, "Generate music", "Quote and submit one music task through audio billing.",
                        MUSIC_INPUT, "MUSIC"));
    }

    @Override
    public BillingMediaType billingMediaType(RuntimeToolRegistry.Descriptor descriptor) {
        if (descriptor == null) return null;
        return switch (descriptor.code()) {
            case IMAGE_GENERATE -> BillingMediaType.IMAGE;
            case VIDEO_GENERATE -> BillingMediaType.VIDEO;
            case AUDIO_GENERATE -> BillingMediaType.AUDIO;
            case MUSIC_GENERATE -> BillingMediaType.MUSIC;
            default -> null;
        };
    }

    @Override
    public BillingMediaType modelCatalogMediaType(RuntimeToolRegistry.Descriptor descriptor) {
        return billingMediaType(descriptor);
    }

    @Override
    public String planningArtifactType(RuntimeToolRegistry.Descriptor descriptor) {
        BillingMediaType type = billingMediaType(descriptor);
        return type == null ? null : type.name();
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return descriptor != null && TOOL_CODES.contains(descriptor.code())
                ? OutputReferenceKind.RUNTIME_ARTIFACT : OutputReferenceKind.UNKNOWN;
    }

    @Override
    public AiModelVO projectModelCatalog(RuntimeToolRegistry.Descriptor descriptor, AiModelVO model) {
        return descriptor != null && MUSIC_GENERATE.equals(descriptor.code()) ? projectMusicModel(model) : model;
    }

    @Override
    public Map<String,Object> modelCatalogInputSupport(RuntimeToolRegistry.Descriptor descriptor,AiModelVO model) {
        if(descriptor==null || model==null)return Map.of();
        if(!MUSIC_GENERATE.equals(descriptor.code())) {
            String schema = switch(descriptor.code()) {
                case IMAGE_GENERATE -> IMAGE_INPUT; case VIDEO_GENERATE -> VIDEO_INPUT;
                case AUDIO_GENERATE -> AUDIO_INPUT; default -> null;
            };
            if(schema==null)return Map.of();
            Map<String,Object> contracts=new LinkedHashMap<>();
            var aliases=com.aid.runtime.tool.RuntimeMediaInputContract.aliases(
                    billingMediaType(descriptor).name());
            if(model.getCapabilityCode()!=null) {
                var contract=com.aid.runtime.tool.RuntimeModelParameterContract.project(schema,model.getParameterSchema(),aliases);
                if(!contract.isEmpty())contracts.put(model.getCapabilityCode(),contract);
            }
            for(var option:model.getAvailableCapabilities()==null?List.<ModelCapabilityOptionVO>of():model.getAvailableCapabilities()) {
                if(option==null || option.getCode()==null)continue;
                var contract=com.aid.runtime.tool.RuntimeModelParameterContract.project(schema,option.getParameterSchema(),aliases);
                if(!contract.isEmpty())contracts.put(option.getCode(),contract);
            }
            return contracts.isEmpty()?Map.of():Map.of("capabilities",contracts);
        }
        Map<String,Object> capabilities=new LinkedHashMap<>();
        if(model.getCapabilityCode()!=null && model.getRuntimeMusicInputSupport()!=null)
            capabilities.put(model.getCapabilityCode(),musicParameterSupport(model.getRuntimeMusicInputSupport(),model.getParameterSchema()));
        for(var option:model.getAvailableCapabilities()==null?List.<ModelCapabilityOptionVO>of():model.getAvailableCapabilities())
            if(option!=null && Boolean.TRUE.equals(option.getRuntimeMusicSupported()) && option.getRuntimeMusicInputSupport()!=null)
                capabilities.put(option.getCode(),musicParameterSupport(option.getRuntimeMusicInputSupport(),option.getParameterSchema()));
        return capabilities.isEmpty()?Map.of():Map.of("capabilities",capabilities);
    }

    private Map<String,Object> musicParameterSupport(Map<String,Object> source,
            List<com.aid.aid.domain.model.ModelParameter> parameters) {
        Map<String,Object> result = new LinkedHashMap<>(source);
        Map<String,String> aliases = new LinkedHashMap<>();
        Object raw = result.remove("_parameterAliases");
        if (raw instanceof Map<?,?> values) values.forEach((key,value) -> {
            // Official audio normalization writes these fields inside request.options.
            if (key instanceof String name && value instanceof String path) aliases.put(name,"options." + path);
        });
        var projected = com.aid.runtime.tool.RuntimeModelParameterContract.project(MUSIC_INPUT,parameters,aliases);
        result.put("requiredFields",projected.get("requiredFields"));
        result.put("fieldConstraints",projected.get("fieldConstraints"));
        return result;
    }

    private AiModelVO projectMusicModel(AiModelVO model) {
        if(model==null)return null;
        List<ModelCapabilityOptionVO> supported=(model.getAvailableCapabilities()==null
                ? List.<ModelCapabilityOptionVO>of() : model.getAvailableCapabilities()).stream()
                .filter(Objects::nonNull).filter(option->Boolean.TRUE.equals(option.getRuntimeMusicSupported())
                        && option.getBilling()!=null).toList();
        if(!Boolean.TRUE.equals(model.getRuntimeMusicSupported()) && supported.isEmpty())return null;
        AiModelVO result=new AiModelVO();BeanUtil.copyProperties(model,result);
        result.setAvailableCapabilities(supported);
        if(!Boolean.TRUE.equals(model.getRuntimeMusicSupported())) {
            // A display projection only. Execution still requires the explicitly selected capability.
            ModelCapabilityOptionVO capability=supported.get(0);
            result.setCapabilityCode(capability.getCode());result.setGenerateMode(capability.getGenerateMode());
            result.setParameterSchema(capability.getParameterSchema());result.setParameterRules(capability.getParameterRules());
            result.setBilling(capability.getBilling());result.setCostCredits(null);
            result.setIsFree(capability.getBilling().getIsFree());result.setRuntimeMusicSupported(true);
            result.setRuntimeMusicInputSupport(capability.getRuntimeMusicInputSupport());
        }
        return result;
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null || context.getUserId() == null || context.getUserId() <= 0
                || descriptor == null || !TOOL_CODES.contains(descriptor.code())) {
            return RuntimeToolAvailability.unavailable("MEDIA_TOOL_SCOPE_UNAVAILABLE");
        }
        String modelType = modelType(descriptor.code());
        if (modelType == null) return RuntimeToolAvailability.unavailable("MEDIA_TOOL_UNSUPPORTED");
        if (context.isMetadataDiscovery()) return RuntimeToolAvailability.enabled();
        try {
            AiModelListRequest request = new AiModelListRequest();
            request.setModelType(modelType);
            boolean available = !catalog(context, descriptor.code(), request).isEmpty();
            return available ? RuntimeToolAvailability.enabled()
                    : RuntimeToolAvailability.unavailable("MEDIA_MODEL_NOT_CONFIGURED");
        } catch (RuntimeException unavailable) {
            return RuntimeToolAvailability.unavailable("MEDIA_MODEL_CATALOG_UNAVAILABLE");
        }
    }

    @Override
    public RuntimeToolBudgetOffer budgetOffer(RuntimeToolAvailabilityContext context,
                                              RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null) return new RuntimeToolBudgetOffer();
        return context.withDiscoverySnapshot(() -> budgetOfferInDiscovery(context, descriptor));
    }

    private RuntimeToolBudgetOffer budgetOfferInDiscovery(RuntimeToolAvailabilityContext context,
                                                        RuntimeToolRegistry.Descriptor descriptor) {
        if (!availability(context, descriptor).available()) return new RuntimeToolBudgetOffer();
        AiModelListRequest request = new AiModelListRequest();
        request.setModelType(modelType(descriptor.code()));
        List<AiModelVO> models = catalog(context, descriptor.code(), request);
        List<String> modelCodes = models.stream().map(AiModelVO::getModelCode)
                .filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
        List<String> capabilities = models.stream().flatMap(this::capabilities)
                .filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
        String policyMediaType = policyMediaType(descriptor.code());
        if (modelCodes.isEmpty() || capabilities.isEmpty() || policyMediaType == null) {
            return new RuntimeToolBudgetOffer();
        }
        RuntimeToolBudgetOffer offer = new RuntimeToolBudgetOffer(modelCodes, capabilities, List.of(policyMediaType));
        offer.setModelChoices(models.stream().filter(model -> StrUtil.isNotBlank(model.getModelCode())).flatMap(model -> {
            Map<String, RuntimeToolBudgetOffer.ModelChoice> choices = new LinkedHashMap<>();
            if (StrUtil.isNotBlank(model.getCapabilityCode())) {
                choices.put(model.getCapabilityCode(), new RuntimeToolBudgetOffer.ModelChoice(
                        model.getModelCode(), model.getCapabilityCode(), model.getGenerateMode(), model.getIsFree(),
                        planningParameters(model.getParameterSchema()), model.getParameterRules()));
            }
            for (ModelCapabilityOptionVO capability : model.getAvailableCapabilities() == null
                    ? List.<ModelCapabilityOptionVO>of() : model.getAvailableCapabilities()) {
                if (capability == null || StrUtil.isBlank(capability.getCode())) continue;
                choices.put(capability.getCode(), new RuntimeToolBudgetOffer.ModelChoice(
                        model.getModelCode(), capability.getCode(), capability.getGenerateMode(),
                        capability.getBilling() != null && Boolean.TRUE.equals(capability.getBilling().getIsFree()),
                        planningParameters(capability.getParameterSchema()), capability.getParameterRules()));
            }
            return choices.values().stream();
        }).toList());
        return offer;
    }

    private List<com.aid.aid.domain.model.ModelParameter> planningParameters(
            List<com.aid.aid.domain.model.ModelParameter> parameters) {
        if (parameters == null) return List.of();
        Set<String> fields = Set.of("size", "options", "aspectRatio", "resolution", "durationSeconds",
                "expectedImageCount", "audio", "bgm", "audioType");
        return parameters.stream().filter(Objects::nonNull).filter(item -> fields.contains(item.getName())).toList();
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        requirePreparationIdentity(invocation);
        JSONObject arguments;
        try {
            arguments = parseObject(invocation.getArgumentsJson(), "Media Tool parameters are invalid");
        } catch (ServiceException invalid) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        return switch (invocation.getToolCode()) {
            case IMAGE_GENERATE -> prepareImage(invocation, arguments);
            case VIDEO_GENERATE -> prepareVideo(invocation, arguments);
            case AUDIO_GENERATE -> prepareAudio(invocation, arguments);
            case MUSIC_GENERATE -> prepareMusic(invocation, arguments);
            default -> throw rejected(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
        };
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        throw new ServiceException("Billable media Tool requires prepared execution");
    }

    @Override
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext budgetContext) {
        requireExecutionIdentity(prepared, budgetContext);
        RuntimeToolBillingIntent intent = prepared.getBillingIntent();
        JSONObject payload = parseObject(prepared.getPreparedPayloadJson(), "Media Tool snapshot is invalid");
        if (!PREPARED_SCHEMA.equals(payload.getString("schemaVersion"))
                || !Objects.equals(intent.getMediaType(), payload.getString("mediaType"))
                || !Objects.equals(intent.getNormalizedRequestJson(), payload.getString("normalizedRequestJson"))
                || !Objects.equals(intent.getRequestDigest(), payload.getString("requestDigest"))
                || !Objects.equals(intent.getQuoteId(), payload.getString("quoteId"))
                || !Objects.equals(intent.getQuoteDigest(), payload.getString("quoteDigest"))) {
            throw new ServiceException("Media Tool snapshot has changed");
        }
        MediaTaskResponse response = switch (prepared.getToolCode()) {
            case IMAGE_GENERATE -> submitImage(prepared, budgetContext, intent);
            case VIDEO_GENERATE -> submitVideo(prepared, budgetContext, intent);
            case AUDIO_GENERATE, MUSIC_GENERATE -> submitAudio(prepared, budgetContext, intent);
            default -> throw new ServiceException("Media Tool is unsupported");
        };
        return result(prepared, response, prepared.getOperationScope().getMediaTypes().get(0));
    }

    /** Server-side Canvas adapter: quoted request and project come from its verified manifest. */
    public RuntimeToolPreparation prepareScopedImage(RuntimeToolInvocation invocation,
                                                       MediaImageGenerateRequest request, Long projectId,
                                                       List<String> targetRefs) {
        var canonicalTargets = requireScopedTarget(projectId, targetRefs);
        requirePreparationIdentity(invocation);
        applyTrustedIdentity(invocation, request);
        request.setProjectId(projectId);
        ImageQuoteView quote = prepareQuote(() -> quoteService.quoteImage(request));
        RuntimeToolPreparation preparation = preparation(invocation, "IMAGE", quote, quote.getNormalizedRequest());
        preparation.getOperationScope().setTargetRefs(canonicalTargets);
        return preparation;
    }

    /** Uses the same normalized quote, billing reservation and durable task admission as other media tools. */
    public RuntimeToolPreparation prepareScopedVideo(RuntimeToolInvocation invocation,
                                                       MediaVideoGenerateRequest request, Long projectId,
                                                       List<String> targetRefs) {
        var canonicalTargets = requireScopedTarget(projectId, targetRefs);
        requirePreparationIdentity(invocation);
        applyTrustedIdentity(invocation, request);
        request.setProjectId(projectId);
        VideoQuoteView quote = prepareQuote(() -> quoteService.quoteVideo(request));
        RuntimeToolPreparation preparation = preparation(invocation, "VIDEO", quote, quote.getNormalizedRequest());
        preparation.getOperationScope().setTargetRefs(canonicalTargets);
        return preparation;
    }

    public MediaTaskResponse submitPreparedScopedImage(RuntimeToolPreparedInvocation prepared,
                                                         BillingBudgetContext budgetContext) {
        return submitPreparedScopedImage(prepared, budgetContext, Map.of());
    }

    public MediaTaskResponse submitPreparedScopedImage(RuntimeToolPreparedInvocation prepared,
            BillingBudgetContext budgetContext, Map<String, String> managedReferences) {
        return submitPreparedScopedImage(prepared, budgetContext, managedReferences, Map.of());
    }

    public MediaTaskResponse submitPreparedScopedImage(RuntimeToolPreparedInvocation prepared,
            BillingBudgetContext budgetContext, Map<String, String> managedReferences,
            Map<String, com.aid.media.dto.AuthorizedImageAssetSnapshot> deliveryImages) {
        requirePreparedMedia(prepared, budgetContext, "IMAGE");
        return submitImage(prepared, budgetContext, prepared.getBillingIntent(), managedReferences, deliveryImages);
    }

    public MediaTaskResponse submitPreparedScopedVideo(RuntimeToolPreparedInvocation prepared,
                                                         BillingBudgetContext budgetContext) {
        requirePreparedMedia(prepared, budgetContext, "VIDEO");
        return submitVideo(prepared, budgetContext, prepared.getBillingIntent());
    }

    public RuntimeToolPreparation prepareScopedAudio(RuntimeToolInvocation invocation, JSONObject input,
            Long projectId, List<String> targetRefs, boolean music) {
        var targets = requireScopedTarget(projectId, targetRefs);
        requirePreparationIdentity(invocation);
        return music ? prepareMusic(invocation, input, projectId, targets)
                : prepareAudio(invocation, input, projectId, targets);
    }

    public MediaTaskResponse submitPreparedScopedAudio(RuntimeToolPreparedInvocation prepared,
            BillingBudgetContext budgetContext, boolean music) {
        requirePreparedMedia(prepared, budgetContext, music ? "MUSIC" : "AUDIO");
        return submitAudio(prepared, budgetContext, prepared.getBillingIntent());
    }

    public static JSONObject businessInputSchema(String toolCode) {
        return JSON.parseObject(switch (toolCode) {
            case AUDIO_GENERATE -> AUDIO_INPUT;
            case MUSIC_GENERATE -> MUSIC_INPUT;
            default -> throw new IllegalArgumentException("Unsupported audio business contract");
        });
    }

    public void requirePreparedMedia(RuntimeToolPreparedInvocation prepared,
                                      BillingBudgetContext budgetContext, String mediaType) {
        requireExecutionIdentity(prepared, budgetContext);
        RuntimeToolBillingIntent intent = prepared.getBillingIntent();
        JSONObject payload = parseObject(prepared.getPreparedPayloadJson(), "Media Tool snapshot is invalid");
        if (prepared.getOperationScope() == null
                || !List.of(mediaType).equals(prepared.getOperationScope().getMediaTypes())
                || !("MUSIC".equals(mediaType) ? "AUDIO" : mediaType).equals(intent.getMediaType())
                || !PREPARED_SCHEMA.equals(payload.getString("schemaVersion"))
                || !Objects.equals(intent.getMediaType(), payload.getString("mediaType"))
                || !Objects.equals(intent.getNormalizedRequestJson(), payload.getString("normalizedRequestJson"))
                || !Objects.equals(intent.getRequestDigest(), payload.getString("requestDigest"))
                || !Objects.equals(intent.getQuoteId(), payload.getString("quoteId"))
                || !Objects.equals(intent.getQuoteDigest(), payload.getString("quoteDigest"))) {
            throw new ServiceException("Media Tool snapshot has changed");
        }
    }

    private static List<com.aid.assistant.dto.AssistantRequests.ContextRef> requireScopedTarget(Long projectId, List<String> targetRefs) {
        if (projectId == null || projectId < 1 || targetRefs == null || targetRefs.size() != 1
                || targetRefs.get(0) == null
                || !targetRefs.get(0).matches("canvas:node:[1-9][0-9]{0,18}:[A-Za-z0-9_-]{1,64}:[1-9][0-9]{0,18}")) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
        }
        String[] identity = targetRefs.get(0).split(":");
        var ref = new com.aid.assistant.dto.AssistantRequests.ContextRef();
        ref.setKind("FREE_CANVAS");
        ref.setProjectId(projectId);
        try {
            ref.setWorkspaceId(Long.valueOf(identity[2]));
            Long.parseLong(identity[4]);
        } catch (NumberFormatException invalid) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
        }
        ref.setObjectId(identity[3]);
        ref.setVersion(identity[4]);
        return List.of(ref);
    }

    private RuntimeToolPreparation prepareImage(RuntimeToolInvocation invocation, JSONObject input) {
        MediaImageGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.image(input);
        applyTrustedIdentity(invocation, request);
        ImageQuoteView quote = prepareQuote(() -> quoteService.quoteImage(request));
        return preparation(invocation, "IMAGE", quote, quote.getNormalizedRequest());
    }

    private RuntimeToolPreparation prepareVideo(RuntimeToolInvocation invocation, JSONObject input) {
        MediaVideoGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.video(input);
        applyTrustedIdentity(invocation, request);
        VideoQuoteView quote = prepareQuote(() -> quoteService.quoteVideo(request));
        return preparation(invocation, "VIDEO", quote, quote.getNormalizedRequest());
    }

    private RuntimeToolPreparation prepareAudio(RuntimeToolInvocation invocation, JSONObject input) {
        return prepareAudio(invocation, input, null, List.of());
    }

    private RuntimeToolPreparation prepareAudio(RuntimeToolInvocation invocation, JSONObject input, Long projectId,
            List<com.aid.assistant.dto.AssistantRequests.ContextRef> targetRefs) {
        MediaAudioGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.audio(input);
        applyTrustedIdentity(invocation, request);
        if (projectId != null) request.setProjectId(projectId);
        AudioQuoteView quote = prepareQuote(() -> quoteService.quoteAudio(request));
        RuntimeToolPreparation prepared = preparation(invocation, "AUDIO", quote, quote.getNormalizedRequest());
        prepared.getOperationScope().setTargetRefs(targetRefs);
        return prepared;
    }

    private RuntimeToolPreparation prepareMusic(RuntimeToolInvocation invocation, JSONObject input) {
        return prepareMusic(invocation, input, null, List.of());
    }

    private RuntimeToolPreparation prepareMusic(RuntimeToolInvocation invocation, JSONObject input, Long projectId,
            List<com.aid.assistant.dto.AssistantRequests.ContextRef> targetRefs) {
        MinimaxMusicGenerateRequest music;
        try {
            music = JSON.parseObject(input.toJSONString(), MinimaxMusicGenerateRequest.class);
        } catch (RuntimeException invalid) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        try {
            var config=quoteModelConfigs.selectByModelCodeForUser(music.getModelCode(),invocation.getUserId());
            if(config==null)throw com.aid.media.service.MediaPreparationErrorCode.MODEL_UNAVAILABLE.reject("音乐模型不可用");
            config=quoteModelResolver.select(config,music.getCapabilityCode());
            String operation=com.aid.media.service.MusicInputContract.operation(config.getProtocol(),config.getCapabilityJson());
            if(operation==null)throw com.aid.media.service.MediaPreparationErrorCode.MODEL_CAPABILITY_INVALID
                    .reject("当前音乐工具不支持所选模型能力，请从该工具的可用目录重新选择");
            MediaAudioGenerateRequest request;
            if("minimax.post.music_generation".equals(operation)) {
                MinimaxMusicGenerateRequest legacy=new MinimaxMusicGenerateRequest();BeanUtil.copyProperties(music,legacy);
                legacy.setModelCode(StrUtil.blankToDefault(config.getRealModelCode(),music.getModelCode()));legacy.setCapabilityCode(config.getCapabilityCode());
                request=musicGenerationService.prepareRequest(legacy,invocation.getUserId());
                request.setModelName(music.getModelCode());
            } else {
                request=new MediaAudioGenerateRequest();request.setUserId(invocation.getUserId());
                request.setModelName(music.getModelCode());request.setCapabilityCode(config.getCapabilityCode());
                request.setTtsText(StrUtil.isNotBlank(music.getPrompt())?music.getPrompt():music.getLyrics());
                request.setOptions(com.aid.media.service.MusicInputContract.normalize(music,operation));
            }
            request.setExpectedModelConfigurationHash(com.aid.model.ModelConfigurationFingerprint.of(config));
            applyTrustedIdentity(invocation, request);
            if (projectId != null) request.setProjectId(projectId);
            AudioQuoteView quote = prepareQuote(() -> quoteService.quoteAudio(request));
            RuntimeToolPreparation prepared = preparation(invocation, "MUSIC", quote, quote.getNormalizedRequest());
            prepared.getOperationScope().setTargetRefs(targetRefs);
            return prepared;
        } catch(ServiceException invalid) {
            // Request normalization is read-only too; definitive rejection must not leave PREPARING forever.
            throw RuntimeToolPreparationValidation.mediaQuote(invalid);
        }
    }

    private <T extends QuoteView> T prepareQuote(Supplier<T> quote) {
        try { return quote.get(); }
        catch (ServiceException invalid) { throw RuntimeToolPreparationValidation.mediaQuote(invalid); }
    }

    private RuntimeToolPreparation preparation(RuntimeToolInvocation invocation, String mediaType,
                                               QuoteView quote, Object normalizedRequest) {
        requireHardQuote(quote);
        String normalizedRequestJson;
        try {
            normalizedRequestJson = MediaTaskPayloadSanitizer.serializeRequest(normalizedRequest);
        } catch (RuntimeException invalid) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_INVALID, invalid);
        }
        if (!Objects.equals(quote.getRequestDigest(),
                AssistantMediaQuoteDigest.requestDigestFromJson(normalizedRequestJson))) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_INVALID);
        }
        RuntimeToolBillingIntent intent = new RuntimeToolBillingIntent();
        intent.setMediaType("MUSIC".equals(mediaType) ? "AUDIO" : mediaType);
        intent.setNormalizedRequestJson(normalizedRequestJson);
        intent.setRequestDigest(quote.getRequestDigest());
        intent.setQuoteId(quote.getQuoteId());
        intent.setQuoteDigest(quote.getQuoteDigest());
        intent.setQuoteExpiresAt(quote.getExpiresAt());
        intent.setQuotedUpperBound(quote.getQuotedUpperBound());

        AssistantBudgetOperationScope scope = new AssistantBudgetOperationScope();
        scope.setOperationKind(invocation.getOperationKind());
        scope.setToolCode(invocation.getToolCode());
        scope.setModelCode(quote.getModelCode());
        scope.setCapabilityCodes(List.of(requireCapabilityCode(normalizedRequest)));
        scope.setMediaTypes(List.of(mediaType));
        scope.setTargetRefs(List.of());
        scope.setQuotedUpperBound(quote.getQuotedUpperBound());
        scope.setCountsAsIteration(false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", PREPARED_SCHEMA);
        payload.put("mediaType", intent.getMediaType());
        payload.put("normalizedRequestJson", normalizedRequestJson);
        payload.put("requestDigest", quote.getRequestDigest());
        payload.put("quoteId", quote.getQuoteId());
        payload.put("quoteDigest", quote.getQuoteDigest());
        if(normalizedRequest instanceof MediaVideoGenerateRequest video && video.getResolvedReferenceVideos()!=null
                && !video.getResolvedReferenceVideos().isEmpty()) {
            var references=new com.alibaba.fastjson2.JSONArray();
            for(var reference:video.getResolvedReferenceVideos()) {
                var item=new JSONObject();item.put("recordId",reference.getRecordId());item.put("videoUrl",reference.getVideoUrl());
                item.put("durationMs",reference.getDurationMs());item.put("fileSizeBytes",reference.getFileSizeBytes());
                item.put("width",reference.getWidth());item.put("height",reference.getHeight());item.put("fps",reference.getFps());item.put("format",reference.getFormat());references.add(item);
            }
            payload.put("serverReferenceVideos",references);
        }
        return new RuntimeToolPreparation(JSON.toJSONString(payload), intent, scope);
    }

    private MediaTaskResponse submitImage(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context,
                                          RuntimeToolBillingIntent intent) {
        return submitImage(prepared, context, intent, Map.of());
    }

    private MediaTaskResponse submitImage(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context,
            RuntimeToolBillingIntent intent, Map<String, String> managedReferences) {
        return submitImage(prepared, context, intent, managedReferences, Map.of());
    }

    private MediaTaskResponse submitImage(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context,
            RuntimeToolBillingIntent intent, Map<String, String> managedReferences,
            Map<String, com.aid.media.dto.AuthorizedImageAssetSnapshot> deliveryImages) {
        MediaImageGenerateRequest request = restorePreparedRequest(prepared, context, intent,
                MediaImageGenerateRequest.class, "MEDIA_IMAGE_PRETASK_REJECTED");
        request.setBillingBudgetContext(context);
        request.setManagedReferenceImages(managedReferences);

        var allowed = com.aid.media.provider.ImageInputExecutionScope.slots(request).values();
        deliveryImages.forEach((url, snapshot) -> {
            if (!allowed.contains(url) || snapshot == null) throw new IllegalArgumentException("IMAGE_DELIVERY_AUTHORIZATION_INVALID");
            snapshot.requireValid(url, "参考图");
        });
        request.setDeliveryAuthorizedReferenceImages(Map.copyOf(deliveryImages));
        return mediaGenerationService.submitImage(request);
    }

    private MediaTaskResponse submitVideo(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context,
                                          RuntimeToolBillingIntent intent) {
        MediaVideoGenerateRequest request = restorePreparedRequest(prepared, context, intent,
                MediaVideoGenerateRequest.class, "MEDIA_VIDEO_PRETASK_REJECTED");
        request.setProviderIdempotencyKey(prepared.getIdempotencyKey());
        request.setBillingBudgetContext(context);
        return mediaGenerationService.submitVideo(request);
    }

    private MediaTaskResponse submitAudio(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context,
                                          RuntimeToolBillingIntent intent) {
        MediaAudioGenerateRequest request = restorePreparedRequest(prepared, context, intent,
                MediaAudioGenerateRequest.class, "MEDIA_AUDIO_PRETASK_REJECTED");
        request.setBillingBudgetContext(context);
        return mediaGenerationService.generateAudio(request);
    }

    private void applyTrustedIdentity(RuntimeToolInvocation invocation, MediaImageGenerateRequest request) {
        SessionScope session = sessionScope(invocation.getContextManifestJson());
        request.setUserId(invocation.getUserId());
        request.setProjectId(session.projectId());
        request.setEpisodeId(session.episodeId());
        request.setBizTaskType(BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE);
        request.setBizTaskId(invocation.getRuntimeStepId());
        request.setInvocationIdentity(invocation.getIdempotencyKey());
        request.setCallId(invocation.getIdempotencyKey());
        request.setCallIdentity(StrUtil.blankToDefault(request.getCallIdentity(), "assistant-media"));
    }

    private void applyTrustedIdentity(RuntimeToolInvocation invocation, MediaVideoGenerateRequest request) {
        SessionScope session = sessionScope(invocation.getContextManifestJson());
        request.setUserId(invocation.getUserId());
        request.setProjectId(session.projectId());
        request.setEpisodeId(session.episodeId());
        request.setBizTaskType(BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE);
        request.setBizTaskId(invocation.getRuntimeStepId());
        request.setInvocationIdentity(invocation.getIdempotencyKey());
        request.setCallId(invocation.getIdempotencyKey());
        request.setCallIdentity(StrUtil.blankToDefault(request.getCallIdentity(), "assistant-media"));
        request.setProviderIdempotencyKey(invocation.getIdempotencyKey());
    }

    private void applyTrustedIdentity(RuntimeToolInvocation invocation, MediaAudioGenerateRequest request) {
        SessionScope session = sessionScope(invocation.getContextManifestJson());
        request.setUserId(invocation.getUserId());
        request.setProjectId(session.projectId());
        request.setEpisodeId(session.episodeId());
        request.setBizTaskType(BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE);
        request.setBizTaskId(invocation.getRuntimeStepId());
        request.setInvocationIdentity(invocation.getIdempotencyKey());
        request.setCallId(invocation.getIdempotencyKey());
        request.setCallIdentity(StrUtil.blankToDefault(request.getCallIdentity(), "assistant-media"));
        request.setPreviewMode(false);
    }

    /**
     * Restore the complete server-owned quote, including fields that public JSON intentionally
     * cannot write. This path only consumes the digest-bound prepared snapshot after Runtime has
     * verified approval, leases and budget identity; never use it for user or model input.
     */
    private <T> T restorePreparedRequest(RuntimeToolPreparedInvocation prepared,
                                        BillingBudgetContext context,
                                        RuntimeToolBillingIntent intent,
                                        Class<T> requestType, String evidenceCode) {
        try {
            JSONObject snapshot = parseObject(intent.getNormalizedRequestJson(),
                    "Media Tool normalized request is invalid");
            requireNormalizedIdentity(prepared, intent, snapshot);
            // Bean mapping is intentional here: public Jackson/Fastjson access annotations discard
            // trusted internal fields. The complete quoted digest must still match after restore.
            T request = BeanUtil.toBean(snapshot, requestType);
            if (request == null || !Objects.equals(intent.getRequestDigest(),
                    AssistantMediaQuoteDigest.requestDigest(request))) {
                throw new ServiceException("Media Tool restored request has changed");
            }
            return request;
        } catch (RuntimeException rejected) {
            // No media service or Provider has been entered. Runtime must independently prove
            // exact-task absence and unchanged fences before releasing any reservation.
            throw new MediaSubmissionNotEnteredException(evidenceCode, rejected, context);
        }
    }

    private void requireNormalizedIdentity(RuntimeToolPreparedInvocation prepared,
                                           RuntimeToolBillingIntent intent, JSONObject snapshot) {
        if (!Objects.equals(prepared.getUserId(), snapshot.getLong("userId"))
                || !BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(snapshot.getString("bizTaskType"))
                || !Objects.equals(prepared.getRuntimeStepId(), snapshot.getLong("bizTaskId"))
                || !sha256(snapshot.getString("invocationIdentity"))
                || !Objects.equals(prepared.getIdempotencyKey(), snapshot.getString("callId"))
                || StrUtil.isBlank(snapshot.getString("callIdentity"))
                || !Objects.equals(intent.getRequestDigest(),
                        AssistantMediaQuoteDigest.requestDigestFromJson(intent.getNormalizedRequestJson()))) {
            throw new ServiceException("Media Tool normalized request has changed");
        }
    }

    private void requirePreparationIdentity(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getRunId() == null || invocation.getRunId() <= 0
                || invocation.getUserId() == null || invocation.getUserId() <= 0
                || invocation.getRuntimeStepId() == null || invocation.getRuntimeStepId() <= 0
                || StrUtil.isBlank(invocation.getOperationKind()) || StrUtil.isBlank(invocation.getIdempotencyKey())) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IDENTITY_CHANGED);
        }
    }

    private void requireExecutionIdentity(RuntimeToolPreparedInvocation prepared, BillingBudgetContext context) {
        if (prepared == null || prepared.getBillingIntent() == null || context == null
                || !context.isRunAuthorizationMode()
                || !Objects.equals(prepared.getRunId(), context.getRuntimeRunId())
                || !Objects.equals(prepared.getRuntimeStepId(), context.getRuntimeStepId())
                || !Objects.equals(prepared.getStableStepId(), context.getStableStepKey())
                || !Objects.equals(prepared.getAttemptNo(), context.getAttemptNo())
                || !Objects.equals(prepared.getIdempotencyKey(), context.getLogicalCallKey())
                || !Objects.equals(prepared.getBillingIntent().getRequestDigest(), context.getRequestDigest())
                || !Objects.equals(prepared.getBillingIntent().getQuoteId(), context.getQuoteId())
                || !Objects.equals(prepared.getBillingIntent().getQuoteDigest(), context.getQuoteDigest())
                || !sameDate(prepared.getBillingIntent().getQuoteExpiresAt(), context.getQuoteExpiresAt())) {
            throw new ServiceException("Media Tool budget context has changed");
        }
    }

    private void requireHardQuote(QuoteView quote) {
        if (quote != null && quote.getExpiresAt() != null && !quote.getExpiresAt().after(new Date())) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_EXPIRED);
        }
        if (quote == null || !quote.isHardUpperBoundAvailable()
                || quote.getQuotedUpperBound() == null || quote.getQuotedUpperBound().signum() < 0
                || StrUtil.isBlank(quote.getModelCode()) || StrUtil.isBlank(quote.getQuoteId())
                || !sha256(quote.getRequestDigest()) || !sha256(quote.getQuoteDigest())
                || quote.getExpiresAt() == null) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_UNAVAILABLE);
        }
    }

    private String requireCapabilityCode(Object normalizedRequest) {
        String capability = null;
        if (normalizedRequest instanceof MediaImageGenerateRequest image) capability = image.getCapabilityCode();
        else if (normalizedRequest instanceof MediaVideoGenerateRequest video) capability = video.getCapabilityCode();
        else if (normalizedRequest instanceof MediaAudioGenerateRequest audio) capability = audio.getCapabilityCode();
        if (StrUtil.isBlank(capability)) throw rejected(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_CAPABILITY_INVALID);
        return capability.trim();
    }

    private static RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason) {
        return new RuntimeToolPreparationRejectedException(reason);
    }

    private static RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason, Throwable cause) {
        return new RuntimeToolPreparationRejectedException(reason, cause);
    }

    public RuntimeToolResult scopedTaskResult(RuntimeToolPreparedInvocation prepared, MediaTaskResponse response) {
        if (prepared == null || prepared.getOperationScope() == null
                || prepared.getOperationScope().getMediaTypes() == null
                || prepared.getOperationScope().getMediaTypes().size() != 1
                || !Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC").contains(prepared.getOperationScope().getMediaTypes().get(0))) {
            throw new ServiceException("Media Tool scope is invalid");
        }
        return result(prepared, response, prepared.getOperationScope().getMediaTypes().get(0));
    }

    private RuntimeToolResult result(RuntimeToolPreparedInvocation prepared, MediaTaskResponse response,
                                     String mediaType) {
        if (response == null || response.getTaskId() == null) {
            throw new ServiceException("Media task was not persisted");
        }
        String taskStatus = StrUtil.blankToDefault(response.getStatus(), "UNKNOWN").toUpperCase();
        JSONObject output = new JSONObject();
        output.put("mediaTaskId", String.valueOf(response.getTaskId()));
        output.put("status", taskStatus);
        output.put("mediaType", mediaType);
        output.put("modelCode", prepared.getOperationScope().getModelCode());
        RuntimeToolResult result = new RuntimeToolResult();
        result.setOutputJson(output.toJSONString());
        result.setMediaTaskId(response.getTaskId());
        if ("SUCCEEDED".equals(taskStatus)) {
            result.setStatus("SUCCEEDED");
            result.setSafeSummary("Media task completed");
        } else if (WAITING.contains(taskStatus)) {
            result.setStatus("WAITING_TASK");
            result.setSafeSummary("Media task is running");
        } else if (FAILED.contains(taskStatus)) {
            result.setStatus("FAILED");
            result.setErrorCode(StrUtil.blankToDefault(response.getErrorCode(), "MEDIA_TASK_FAILED"));
            result.setRetryable(response.isRetryable());
            result.setSafeSummary("Media task failed");
        } else {
            result.setStatus("UNKNOWN");
            result.setErrorCode("MEDIA_TASK_STATUS_UNKNOWN");
            result.setSafeSummary("Media task requires reconciliation");
        }
        return result;
    }

    private record CatalogSnapshotKey(Long userId, String modelType, String generateMode, List<String> modelCodes) { }

    private List<AiModelVO> catalog(RuntimeToolAvailabilityContext context, String toolCode, AiModelListRequest request) {
        List<String> codes = context.getCandidateModelCodes() == null
                ? null : List.copyOf(context.getCandidateModelCodes());
        List<AiModelVO> models = context.discoveryValue(
                new CatalogSnapshotKey(context.getUserId(), request.getModelType(), request.getGenerateMode(), codes),
                () -> codes == null ? modelBusinessService.listAvailableModels(request)
                        : modelBusinessService.listAvailableModelsByCodes(request, codes));
        if (models == null) return List.of();
        return models.stream().filter(Objects::nonNull)
                .filter(model -> model.getBilling() != null)
                .map(model -> MUSIC_GENERATE.equals(toolCode) ? projectMusicModel(model) : model)
                .filter(Objects::nonNull)
                .filter(model -> capabilities(model).findAny().isPresent())
                .toList();
    }

    private Stream<String> capabilities(AiModelVO model) {
        Stream<String> selected = Stream.of(model.getCapabilityCode());
        Stream<String> available = model.getAvailableCapabilities() == null ? Stream.empty()
                : model.getAvailableCapabilities().stream().filter(Objects::nonNull)
                .map(ModelCapabilityOptionVO::getCode);
        return Stream.concat(selected, available).filter(StrUtil::isNotBlank).distinct();
    }

    private String modelType(String toolCode) {
        return switch (toolCode) {
            case IMAGE_GENERATE -> "image";
            case VIDEO_GENERATE -> "video";
            case AUDIO_GENERATE, MUSIC_GENERATE -> "audio";
            default -> null;
        };
    }

    private String policyMediaType(String toolCode) {
        return switch (toolCode) {
            case IMAGE_GENERATE -> "IMAGE";
            case VIDEO_GENERATE -> "VIDEO";
            case AUDIO_GENERATE -> "AUDIO";
            case MUSIC_GENERATE -> "MUSIC";
            default -> null;
        };
    }

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                      String input, String mediaType) {
        String contractVersion = MUSIC_GENERATE.equals(code) ? MUSIC_VERSION : VERSION;
        return new RuntimeToolRegistry.Descriptor(code, contractVersion, SecureUtil.sha256(input + "\n" + OUTPUT),
                name, description, input, OUTPUT, "BILLABLE", true, "IDEMPOTENCY_KEY",
                false, "RECONCILE_TASK", List.of(mediaType));
    }

    private SessionScope sessionScope(String manifestJson) {
        JSONObject manifest = parseObject(manifestJson, "Assistant context manifest is invalid");
        JSONObject scope = manifest.getJSONObject("sessionScope");
        if (!"assistant-context-manifest/v1".equals(manifest.getString("schemaVersion")) || scope == null) {
            throw new ServiceException("Assistant context manifest is invalid");
        }
        return new SessionScope(parsePositive(scope.getString("projectId")),
                parseNonNegative(scope.getString("episodeId")));
    }

    private Long parsePositive(String value) {
        if (StrUtil.isBlank(value)) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ServiceException("Assistant project scope is invalid");
        }
    }

    private Long parseNonNegative(String value) {
        if (StrUtil.isBlank(value)) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ServiceException("Assistant episode scope is invalid");
        }
    }

    private JSONObject parseObject(String value, String message) {
        try {
            JSONObject parsed = JSON.parseObject(value);
            if (parsed == null) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new ServiceException(message);
        }
    }

    private boolean sameDate(Date left, Date right) {
        return left != null && right != null && left.getTime() / 1000L == right.getTime() / 1000L;
    }

    private boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private record SessionScope(Long projectId, Long episodeId) { }
    @jakarta.annotation.Resource private com.aid.service.IAiModelConfigService quoteModelConfigs;
    @jakarta.annotation.Resource private com.aid.model.definition.ModelInvocationResolver quoteModelResolver;

    @Override
    public void validateQuoteReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        requireFrozenQuoteIdentity(source);
        Object restored = frozenQuoteRequest(source);
        String modelCode = BeanUtil.getProperty(restored, "modelName");
        String capability = BeanUtil.getProperty(restored, "capabilityCode");
        var config = quoteModelConfigs.selectByModelCode(modelCode);
        if (config == null || !Objects.equals(modelCode, config.getModelCode()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        config = quoteModelResolver.select(config, capability);
        quoteModelResolver.normalize(config, restored);
        var intent = JSON.parseObject(source.billingIntentJson(), RuntimeToolBillingIntent.class);
        if (!Objects.equals(intent.getRequestDigest(), AssistantMediaQuoteDigest.requestDigest(restored)))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
    }

    @Override
    public RuntimeToolPreparation repricePreparedQuote(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        validateQuoteReadOnly(source);
        Object restored = frozenQuoteRequest(source);
        QuoteView quote;
        if (restored instanceof MediaImageGenerateRequest image) quote = quoteService.quoteImage(image);
        else if (restored instanceof MediaVideoGenerateRequest video) {
            String digest = JSON.parseObject(source.billingIntentJson(),RuntimeToolBillingIntent.class).getRequestDigest();
            quote = source.authorizedQuoteImages().isEmpty() ? quoteService.quoteFrozenReadOnlyVideo(video,digest)
                    : quoteService.quoteFrozenReadOnlyVideo(video,digest,source.authorizedQuoteImages());
        }
        else if (restored instanceof MediaAudioGenerateRequest audio) quote = quoteService.quoteAudio(audio);
        else throw new ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
        requireHardQuote(quote);
        RuntimeToolBillingIntent before = JSON.parseObject(source.billingIntentJson(), RuntimeToolBillingIntent.class);
        if (!Objects.equals(before.getRequestDigest(), quote.getRequestDigest())
                || !Objects.equals(before.getRequestDigest(), AssistantMediaQuoteDigest.requestDigest(restored)))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        RuntimeToolBillingIntent next = JSON.parseObject(source.billingIntentJson(), RuntimeToolBillingIntent.class);
        next.setQuoteId(quote.getQuoteId());next.setQuoteDigest(quote.getQuoteDigest());
        next.setQuoteExpiresAt(quote.getExpiresAt());next.setQuotedUpperBound(quote.getQuotedUpperBound());
        JSONObject payload = JSON.parseObject(source.preparedPayloadJson());
        payload.put("quoteId", quote.getQuoteId());payload.put("quoteDigest", quote.getQuoteDigest());
        if(source.referenceRevalidationJson()!=null)
            payload.put("serverResourceEvidence",JSON.parseObject(source.referenceRevalidationJson()));
        AssistantBudgetOperationScope scope = JSON.parseObject(source.operationScopeJson(), AssistantBudgetOperationScope.class);
        if (!Objects.equals(scope.getModelCode(), quote.getModelCode()))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        scope.setQuotedUpperBound(quote.getQuotedUpperBound());
        return new RuntimeToolPreparation(payload.toJSONString(), next, scope);
    }

    /** Also used by a Canvas adapter after its separate current node/resource permission proof. */
    private void requireFrozenQuoteIdentity(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        if (source == null || source.runId() == null || source.userId() == null || source.runtimeStepId() == null)
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        RuntimeToolBillingIntent intent = JSON.parseObject(source.billingIntentJson(), RuntimeToolBillingIntent.class);
        JSONObject payload = JSON.parseObject(source.preparedPayloadJson());
        JSONObject request = intent == null ? null : JSON.parseObject(intent.getNormalizedRequestJson());
        if (intent == null || request == null || payload == null || intent.getQuoteId()==null
                || !sha256(intent.getQuoteDigest()) || intent.getQuoteExpiresAt()==null
                || intent.getQuotedUpperBound()==null || intent.getQuotedUpperBound().signum()<0
                || !PREPARED_SCHEMA.equals(payload.getString("schemaVersion"))
                || !Objects.equals(source.userId(), request.getLong("userId"))
                || !Objects.equals(source.runtimeStepId(), request.getLong("bizTaskId"))
                || !BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(request.getString("bizTaskType"))
                || !Objects.equals(source.idempotencyKey(), request.getString("callId"))
                || !sha256(request.getString("invocationIdentity")) || StrUtil.isBlank(request.getString("callIdentity"))
                || !Objects.equals(intent.getNormalizedRequestJson(), payload.getString("normalizedRequestJson"))
                || !Objects.equals(intent.getRequestDigest(), payload.getString("requestDigest"))
                || !Objects.equals(intent.getQuoteId(), payload.getString("quoteId"))
                || !Objects.equals(intent.getQuoteDigest(), payload.getString("quoteDigest"))
                || !Objects.equals(intent.getMediaType(), payload.getString("mediaType"))
                || !Objects.equals(intent.getRequestDigest(), AssistantMediaQuoteDigest.requestDigestFromJson(intent.getNormalizedRequestJson())))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
    }

    private Object frozenQuoteRequest(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        RuntimeToolBillingIntent intent = JSON.parseObject(source.billingIntentJson(), RuntimeToolBillingIntent.class);
        if (intent == null) throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        JSONObject snapshot = JSON.parseObject(intent.getNormalizedRequestJson());
        Object restored = switch (Objects.toString(intent.getMediaType(), "")) {
            case "IMAGE" -> BeanUtil.toBean(snapshot, MediaImageGenerateRequest.class);
            case "VIDEO" -> {
                JSONObject copy=JSON.parseObject(snapshot.toJSONString());var refs=copy.getJSONArray("resolvedReferenceVideos");
                JSONObject payload=JSON.parseObject(source.preparedPayloadJson());
                if(refs==null)refs=payload.getJSONArray("serverReferenceVideos");
                JSONObject evidence=source.referenceRevalidationJson()==null ? payload.getJSONObject("serverResourceEvidence")
                        : JSON.parseObject(source.referenceRevalidationJson());
                if(evidence!=null && evidence.getJSONArray("referenceVideos")!=null)refs=evidence.getJSONArray("referenceVideos");
                copy.remove("resolvedReferenceVideos");var video=BeanUtil.toBean(copy,MediaVideoGenerateRequest.class);
                if(refs!=null) {
                    var restoredRefs=new java.util.ArrayList<com.aid.media.dto.ReferenceVideoInput>();
                    for(Object item:refs) {
                        if(!(item instanceof JSONObject value))throw new ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
                        restoredRefs.add(new com.aid.media.dto.ReferenceVideoInput(value.getLong("recordId"),value.getString("videoUrl"),
                                value.getLong("durationMs"),value.getLong("fileSizeBytes"),value.getInteger("width"),
                                value.getInteger("height"),value.getBigDecimal("fps"),value.getString("format")));
                    }
                    video.setResolvedReferenceVideos(restoredRefs);
                }
                yield video;
            }
            case "AUDIO" -> BeanUtil.toBean(snapshot, MediaAudioGenerateRequest.class);
            default -> throw new ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
        };
        if (!Objects.equals(intent.getRequestDigest(), AssistantMediaQuoteDigest.requestDigest(restored)))
            throw new ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        return restored;
    }

}
