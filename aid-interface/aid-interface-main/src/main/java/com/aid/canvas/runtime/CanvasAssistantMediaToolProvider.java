package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasGenerationService;
import com.aid.canvas.service.impl.CanvasGenerationServiceImpl;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolBudgetOffer;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.aid.runtime.tool.RuntimeToolPreparationValidation;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Canvas-targeted media tools sharing the public media quote, budget, task and archival pipeline. */
@Slf4j
@Component
public class CanvasAssistantMediaToolProvider implements RuntimeToolProvider {
    public static final String IMAGE_GENERATE = "canvas.media.generate_image";
    public static final String IMAGE_GENERATE_WITH_PARAMETERS = "canvas.media.generate_image_with_parameters";
    public static final String IMAGE_GENERATE_WITH_SKILL = AssistantImageSkillInvocationResolver.TOOL_CODE;
    public static final String VIDEO_GENERATE = "canvas.media.generate_video";
    public static final String AUDIO_GENERATE = "canvas.media.generate_audio";
    public static final String MUSIC_GENERATE = "canvas.media.generate_music";
    public static final Set<String> CODES = Set.of(
            IMAGE_GENERATE, IMAGE_GENERATE_WITH_PARAMETERS, IMAGE_GENERATE_WITH_SKILL, VIDEO_GENERATE, AUDIO_GENERATE, MUSIC_GENERATE,
            CanvasAssistantComposeToolProvider.CODE, CanvasAssistantComposeToolProvider.CLIP_CODE);
    private static final Set<String> LOCAL_SCOPE_FAILURE_MESSAGES = Set.of(
            "项目参数不完整", "项目不存在或无权访问", "当前项目角色无权执行该操作",
            "工作区不存在", "当前账号未获得该画布功能权益", "画布系统配置未初始化");
    private static final Set<String> LOCAL_REFERENCE_FAILURE_MESSAGES = Set.of(
            "素材编号不能为空或重复", "素材不存在或不属于当前工作区", "图片生成只能引用图片素材",
            "视频参考图片数量过多", "首帧模式最多引用一张图片",
            "视频生成只接受已登记的图片、视频和参考音频");
    private static final Set<String> FIXED_SKILL_FAILURE_CODES = Set.of(
            "ASSISTANT_IMAGE_SKILL_RUN_INVALID", "ASSISTANT_IMAGE_SKILL_SELECTION_INVALID",
            "ASSISTANT_IMAGE_SKILL_VERSION_CHANGED", "ASSISTANT_IMAGE_SKILL_CAPABILITY_INVALID",
            "ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE", "ASSISTANT_IMAGE_SKILL_SCHEMA_NOT_READY",
            "ASSISTANT_IMAGE_SKILL_INPUT_INVALID", "ASSISTANT_IMAGE_SKILL_PROMPT_TOO_LARGE");
    private static final Set<String> FIXED_AVAILABILITY_CODES = Set.of(
            "CANVAS_TARGET_WRITE_MISSING", "CANVAS_IMAGE_SKILL_UNAVAILABLE",
            "CANVAS_MEDIA_SCOPE_UNAVAILABLE", "MEDIA_TOOL_SCOPE_UNAVAILABLE",
            "MEDIA_TOOL_UNSUPPORTED", "MEDIA_MODEL_NOT_CONFIGURED", "MEDIA_MODEL_CATALOG_UNAVAILABLE");
    private static final String COMMON = """
            "target":{"type":"object","required":["objectId","objectVersion"],"properties":{
              "objectId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
              "objectVersion":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},"additionalProperties":false},
            "modelCode":{"type":"string","minLength":1,"maxLength":100},
            "capabilityCode":{"type":["string","null"],"maxLength":100},
            "prompt":{"type":"string","minLength":1,"maxLength":65536},
            "resourceIds":{"type":"array","maxItems":16,"uniqueItems":true,
              "items":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}}
            """;
    private static final String IMAGE_INPUT = "{\"type\":\"object\",\"required\":[\"target\",\"modelCode\",\"prompt\"],\"properties\":{" + COMMON + """
            ,"negativePrompt":{"type":["string","null"],"maxLength":10000},
             "size":{"type":["string","null"],"maxLength":40},
             "expectedImageCount":{"type":["integer","null"],"minimum":1,"maximum":16}
            },"additionalProperties":false}
            """;
    private static final String IMAGE_PARAMETERS_INPUT = imageParametersInput();
    private static final String IMAGE_SKILL_INPUT = """
            {"type":"object","required":["target","skillInput"],"properties":{
              "target":{"type":"object","required":["objectId","objectVersion"],"properties":{
                "objectId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
                "objectVersion":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},"additionalProperties":false},
              "skillInput":{"type":"object","required":["prompt"],"properties":{
                "prompt":{"type":"string","minLength":1,"maxLength":65536}},"additionalProperties":false},
              "modelCode":{"type":["string","null"],"maxLength":100},
              "resourceIds":{"type":"array","maxItems":16,"uniqueItems":true,
                "items":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},
              "size":{"type":["string","null"],"maxLength":40},
              "aspectRatio":{"type":["string","null"],"maxLength":20},
              "expectedImageCount":{"type":["integer","null"],"minimum":1,"maximum":16}
            },"additionalProperties":false}
            """;
    private static final String VIDEO_INPUT = "{\"type\":\"object\",\"required\":[\"target\",\"modelCode\",\"prompt\"],\"properties\":{" + COMMON + """
            ,"durationSeconds":{"type":["integer","null"],"minimum":1,"maximum":3600},
             "aspectRatio":{"type":["string","null"],"maxLength":20},
             "resolution":{"type":["string","null"],"maxLength":40},
             "audio":{"type":["boolean","null"]},"bgm":{"type":["boolean","null"]},
             "audioType":{"type":["string","null"],"enum":["all","speech_only","sound_effect_only",null]},
             "voiceId":{"type":["string","null"],"maxLength":128}
            },"additionalProperties":false}
            """;
    private static final String OUTPUT = """
            {"oneOf":[{"type":"object","required":["mediaTaskId","status","mediaType","modelCode"],"properties":{
              "mediaTaskId":{"type":"string"},"status":{"type":"string"},
              "mediaType":{"enum":["IMAGE","VIDEO"]},"modelCode":{"type":"string"}},"additionalProperties":false},
              {"type":"object","required":["status","mediaTaskId","artifacts"],"properties":{
                "status":{"enum":["FINALIZED"]},"mediaTaskId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
                "artifacts":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object",
                  "required":["ref","status"],"properties":{"ref":{"type":"string","minLength":1},
                    "status":{"enum":["FINALIZED"]}},"additionalProperties":true}}},"additionalProperties":false}]}
            """;

    private final AssistantMediaRuntimeToolProvider mediaTools;
    private final CanvasGenerationService generation;
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService access;
    private final RuntimeScopeAccess scopeAccess;
    private final AssistantImageSkillInvocationResolver imageSkills;
    @Autowired
    private CanvasLocalTextImageResolver localImages;

    @Autowired
    public CanvasAssistantMediaToolProvider(AssistantMediaRuntimeToolProvider mediaTools,
                                            CanvasGenerationService generation,
                                            CanvasMapper canvasMapper,
                                            CanvasAccessService access,
                                            RuntimeScopeAccess scopeAccess,
                                            AssistantImageSkillInvocationResolver imageSkills) {
        this.mediaTools = mediaTools;
        this.generation = generation;
        this.canvasMapper = canvasMapper;
        this.access = access;
        this.scopeAccess = scopeAccess;
        this.imageSkills = imageSkills;
    }

    /** Compatibility constructor for existing non-Skill adapter contract tests. */
    CanvasAssistantMediaToolProvider(AssistantMediaRuntimeToolProvider mediaTools,
                                     CanvasGenerationService generation,
                                     CanvasMapper canvasMapper,
                                     CanvasAccessService access,
                                     RuntimeScopeAccess scopeAccess) {
        this(mediaTools, generation, canvasMapper, access, scopeAccess, null);
    }

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(descriptor(IMAGE_GENERATE, "生成画布图片", IMAGE_INPUT, "IMAGE"),
                descriptor(IMAGE_GENERATE_WITH_PARAMETERS, "按规格与比例生成画布图片", IMAGE_PARAMETERS_INPUT, "IMAGE"),
                descriptor(IMAGE_GENERATE_WITH_SKILL, "按固定图片Skill生成画布图片", IMAGE_SKILL_INPUT, "IMAGE"),
                descriptor(VIDEO_GENERATE, "生成画布视频", VIDEO_INPUT, "VIDEO"),
                descriptor(AUDIO_GENERATE, "按音频节点生成语音", audioInput(false), "AUDIO"),
                descriptor(MUSIC_GENERATE, "按音频节点生成音乐", audioInput(true), "MUSIC"));
    }

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String input, String mediaType) {
        String output = isAudioTool(code) ? OUTPUT.replace("[\"IMAGE\",\"VIDEO\"]", "[\"AUDIO\",\"MUSIC\"]") : OUTPUT;
        return new RuntimeToolRegistry.Descriptor(code, "1.0.0", SecureUtil.sha256(input + "\n" + output), name,
                (IMAGE_GENERATE_WITH_SKILL.equals(code)
                        ? "Generate one image through the exact IMAGE Skill frozen in originalRequest.selectedSkill. modelCode must be enabled by that exact Skill version; provider options are not accepted. The server-owned fixed capability policy requires at least one registered image resource for image_to_image and forbids image resources for text_to_image. Never infer capability from the Skill name or substitute another image Tool, model, provider route, or Skill version."
                        : IMAGE_GENERATE_WITH_PARAMETERS.equals(code)
                        ? "Generate an image for one exact Canvas node. Use text_to_image with no image resources; image_to_image requires registered image resources. Use modelChoices parameterSchema: size is a resolution or pixel specification; aspectRatio is a separate width:height value. Never put a ratio in size. Persist the real task and selected result."
                        : "Quote and generate media for one exact Canvas node; persist its real task and selected result.")
                        + " Incoming reference links reported by canvas.node.read generationReferences are resolved by the host into exact selected source resources and roles during preparation. resourceIds is only for additional explicitly selected resources; omit it to use the existing links. The host verifies source availability, permission and unchanged selection again before submission. No media is submitted before the required fee approval.",
                input, output, "BILLABLE", true, "IDEMPOTENCY_KEY", false, "RECONCILE_TASK",
                List.of("CANVAS_WRITE", mediaType));
    }

    static boolean isImageTool(String code) {
        return IMAGE_GENERATE.equals(code) || IMAGE_GENERATE_WITH_PARAMETERS.equals(code)
                || IMAGE_GENERATE_WITH_SKILL.equals(code);
    }

    static boolean isAudioTool(String code) { return AUDIO_GENERATE.equals(code) || MUSIC_GENERATE.equals(code); }
    @Override
    public Set<String> capabilityIndependentInputFields(RuntimeToolRegistry.Descriptor descriptor) {
        return descriptor != null && (isImageTool(descriptor.code()) || VIDEO_GENERATE.equals(descriptor.code())
                || isAudioTool(descriptor.code())) ? Set.of("target") : Set.of();
    }
    static String physicalMediaType(String code) { return isImageTool(code) ? "IMAGE" : isAudioTool(code) ? "AUDIO" : "VIDEO"; }
    static String logicalMediaType(String code) { return MUSIC_GENERATE.equals(code) ? "MUSIC" : physicalMediaType(code); }
    private static String audioInput(boolean music) {
        JSONObject schema = AssistantMediaRuntimeToolProvider.businessInputSchema(music
                ? AssistantMediaRuntimeToolProvider.MUSIC_GENERATE : AssistantMediaRuntimeToolProvider.AUDIO_GENERATE);
        schema.getJSONObject("properties").put("target", JSON.parseObject(IMAGE_INPUT).getJSONObject("properties").getJSONObject("target"));
        schema.getJSONArray("required").add("target");
        schema.put("description", "Reuse the complete existing speech/music input contract for a VOICE node. Text source links may supply context. Media-reference or transcription operations remain separate tools, not implicit generation inputs.");
        return schema.toJSONString();
    }

    private static String imageParametersInput() {
        JSONObject schema = JSON.parseObject(IMAGE_INPUT);
        schema.getJSONObject("properties").put("aspectRatio", JSON.parseObject(
                "{\"type\":[\"string\",\"null\"],\"maxLength\":20}"));
        return schema.toJSONString();
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.RUNTIME_ARTIFACT;
    }

    @Override
    public String planningArtifactType(RuntimeToolRegistry.Descriptor descriptor) {
        BillingMediaType type = billingMediaType(descriptor);
        return type == null ? null : type.name();
    }

    @Override
    public BillingMediaType billingMediaType(RuntimeToolRegistry.Descriptor descriptor) {
        if (descriptor == null) return null;
        if (isImageTool(descriptor.code())) return BillingMediaType.IMAGE;
        if (VIDEO_GENERATE.equals(descriptor.code())) return BillingMediaType.VIDEO;
        if (AUDIO_GENERATE.equals(descriptor.code())) return BillingMediaType.AUDIO;
        if (MUSIC_GENERATE.equals(descriptor.code())) return BillingMediaType.MUSIC;
        return null;
    }

    @Override
    public BillingMediaType modelCatalogMediaType(RuntimeToolRegistry.Descriptor descriptor) {
        // A selected Skill owns its candidate pool; it cannot expose a general media catalog.
        return descriptor == null || IMAGE_GENERATE_WITH_SKILL.equals(descriptor.code())
                ? null : billingMediaType(descriptor);
    }

    @Override
    public Map<String,Object> modelCatalogInputSupport(RuntimeToolRegistry.Descriptor descriptor,com.aid.model.vo.AiModelVO model) {
        if(descriptor==null||model==null||modelCatalogMediaType(descriptor)==null)return Map.of();
        if (isAudioTool(descriptor.code())) return mediaTools.modelCatalogInputSupport(publicDescriptor(descriptor.code()), model);
        String schema=descriptor.inputSchema();
        Map<String,String> aliases=com.aid.runtime.tool.RuntimeMediaInputContract.aliases(
                billingMediaType(descriptor).name());
        Map<String,Object> capabilities=new LinkedHashMap<>();
        if(model.getCapabilityCode()!=null) {
            var contract=com.aid.runtime.tool.RuntimeModelParameterContract.project(schema,model.getParameterSchema(),aliases,Set.of("target","resourceIds"));
            if(!contract.isEmpty())capabilities.put(model.getCapabilityCode(),contract);
        }
        if(model.getAvailableCapabilities()!=null)for(var option:model.getAvailableCapabilities()) {
            if(option==null||option.getCode()==null)continue;
            var contract=com.aid.runtime.tool.RuntimeModelParameterContract.project(schema,option.getParameterSchema(),aliases,Set.of("target","resourceIds"));
            if(!contract.isEmpty())capabilities.put(option.getCode(),contract);
        }
        return capabilities.isEmpty()?Map.of():Map.of("capabilities",capabilities);
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor) {
        return availability(context, descriptor, availabilityTrace(context, descriptor));
    }

    private RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                 RuntimeToolRegistry.Descriptor descriptor,
                                                 FixedSkillAvailabilityTrace trace) {
        if (context == null || context.getUserId() == null || context.getWorkspaceId() == null
                || descriptor == null || !CODES.contains(descriptor.code())) {
            return RuntimeToolAvailability.unavailable("CANVAS_MEDIA_SCOPE_MISSING");
        }
        try {
            if (trace != null) trace.phase = FixedSkillAvailabilityPhase.SCOPE_ACCESS;
            var refs = CanvasAssistantContextManifest.availableRefs(context.getContextManifestJson(),
                    context.getProjectId(), context.getWorkspaceId());
            if (refs.mutableTargets() == 0 && !(context.isBudgetDiscovery() && refs.mutableWorkspace())) {
                if (trace != null) {
                    trace.reason = "CANVAS_TARGET_WRITE_MISSING";
                    logFixedSkillAvailability(trace, null);
                }
                return RuntimeToolAvailability.unavailable("CANVAS_TARGET_WRITE_MISSING");
            }
            var workspace = access.requireWorkspace(context.getWorkspaceId(), context.getUserId(), "OWNER", "EDITOR");
            access.requireFeature(context.getUserId(), workspace.getProjectId(), "canvas.generate");
            if (IMAGE_GENERATE_WITH_SKILL.equals(descriptor.code())) {
                if (context.isMetadataDiscovery()) {
                    return IMAGE_GENERATE_WITH_SKILL.equals(context.getRequiredToolCode())
                            ? RuntimeToolAvailability.enabled()
                            : RuntimeToolAvailability.unavailable("CANVAS_IMAGE_SKILL_NOT_SELECTED");
                }
                RuntimeToolBudgetOffer offer = skillOffer(context, descriptor, trace);
                if (offer.getModelCodes().isEmpty() || offer.getModelChoices().isEmpty()) {
                    if (trace != null) {
                        trace.reason = "NO_MATCHING_SELECTED_PAIR";
                        logFixedSkillAvailability(trace, null);
                    }
                    return RuntimeToolAvailability.unavailable("CANVAS_IMAGE_SKILL_UNAVAILABLE");
                }
            }
            if (trace != null) trace.phase = FixedSkillAvailabilityPhase.PUBLIC_MEDIA_AVAILABILITY;
            RuntimeToolAvailability availability = mediaTools.availability(context, publicDescriptor(descriptor.code()));
            if (trace != null && (availability == null || !availability.available())) {
                trace.reason = availability == null
                        ? "PUBLIC_MEDIA_AVAILABILITY_NULL" : safeDiagnosticCode(availability.reason());
                logFixedSkillAvailability(trace, null);
            }
            return availability;
        } catch (ServiceException unavailable) {
            if (trace != null) logFixedSkillAvailability(trace, unavailable);
            return RuntimeToolAvailability.unavailable("CANVAS_MEDIA_SCOPE_UNAVAILABLE");
        } catch (RuntimeException unknown) {
            if (trace != null) logFixedSkillAvailability(trace, unknown);
            throw unknown;
        }
    }

    @Override
    public RuntimeToolBudgetOffer budgetOffer(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor) {
        if (descriptor != null && IMAGE_GENERATE_WITH_SKILL.equals(descriptor.code())) {
            FixedSkillAvailabilityTrace trace = availabilityTrace(context, descriptor);
            try {
                return availability(context, descriptor, trace).available()
                        ? skillOffer(context, descriptor, trace) : new RuntimeToolBudgetOffer();
            } catch (RuntimeException unavailable) {
                if (trace != null && !trace.logged) logFixedSkillAvailability(trace, unavailable);
                return new RuntimeToolBudgetOffer();
            }
        }
        return availability(context, descriptor).available()
                ? mediaTools.budgetOffer(context, publicDescriptor(descriptor.code())) : new RuntimeToolBudgetOffer();
    }

    private RuntimeToolBudgetOffer skillOffer(RuntimeToolAvailabilityContext context,
                                              RuntimeToolRegistry.Descriptor descriptor,
                                              FixedSkillAvailabilityTrace trace) {
        if (context == null || descriptor == null || !IMAGE_GENERATE_WITH_SKILL.equals(descriptor.code()))
            return new RuntimeToolBudgetOffer();
        if (trace != null) trace.phase = FixedSkillAvailabilityPhase.REQUIRE_PINNED;
        var pinned = imageSkills.requirePinned(context.getRunId(), context.getUserId());
        if (trace != null) {
            trace.skillVersionId = pinned.version().getId();
            trace.configuredCandidateCount = pinned.modelConfiguration().selectableModelCodes().size();
            trace.activeCandidateCount = pinned.activeModelCodes().size();
            trace.phase = FixedSkillAvailabilityPhase.RAW_OFFER;
        }
        RuntimeToolBudgetOffer raw = mediaTools.budgetOffer(context, publicDescriptor(descriptor.code()));
        if (trace != null) {
            trace.rawModelCount = raw.getModelCodes() == null ? -1 : raw.getModelCodes().size();
            trace.rawChoiceCount = raw.getModelChoices() == null ? -1 : raw.getModelChoices().size();
            trace.phase = FixedSkillAvailabilityPhase.FILTER_SELECTED_PAIR;
        }
        List<RuntimeToolBudgetOffer.ModelChoice> choices = raw.getModelChoices().stream()
                .filter(Objects::nonNull)
                .filter(choice -> pinned.activeModelCodes().contains(choice.modelCode()))
                .filter(choice -> pinned.capabilityCode().equals(choice.capabilityCode()))
                .toList();
        if (trace != null) trace.matchingPairCount = choices.size();
        Set<String> offeredModels = choices.stream().map(RuntimeToolBudgetOffer.ModelChoice::modelCode)
                .collect(java.util.stream.Collectors.toSet());
        List<String> modelCodes = pinned.activeModelCodes().stream().filter(offeredModels::contains).toList();
        if (modelCodes.isEmpty()) {
            if (trace != null) {
                trace.reason = "NO_MATCHING_SELECTED_PAIR";
                logFixedSkillAvailability(trace, null);
            }
            return new RuntimeToolBudgetOffer();
        }
        RuntimeToolBudgetOffer result = new RuntimeToolBudgetOffer(
                modelCodes, List.of(pinned.capabilityCode()), List.of("IMAGE"));
        result.setModelChoices(choices);
        return result;
    }

    private FixedSkillAvailabilityTrace availabilityTrace(RuntimeToolAvailabilityContext context,
                                                           RuntimeToolRegistry.Descriptor descriptor) {
        return context != null && descriptor != null
                && IMAGE_GENERATE_WITH_SKILL.equals(descriptor.code())
                && IMAGE_GENERATE_WITH_SKILL.equals(context.getRequiredToolCode())
                && context.isBudgetDiscovery()
                ? new FixedSkillAvailabilityTrace(context.getRunId()) : null;
    }

    private void logFixedSkillAvailability(FixedSkillAvailabilityTrace trace, RuntimeException failure) {
        if (trace == null || trace.logged) return;
        trace.logged = true;
        String reason = failure == null ? safeDiagnosticCode(trace.reason) : safeFailureCode(failure);
        String errorType = safeErrorType(failure);
        log.warn("Canvas fixed image Skill availability, runId={}, toolCode={}, phase={}, reason={}, "
                        + "errorType={}, skillVersionId={}, configuredCandidates={}, activeCandidates={}, "
                        + "rawModels={}, rawChoices={}, matchingPairs={}",
                trace.runId, IMAGE_GENERATE_WITH_SKILL, trace.phase, reason, errorType,
                trace.skillVersionId, trace.configuredCandidateCount, trace.activeCandidateCount,
                trace.rawModelCount, trace.rawChoiceCount, trace.matchingPairCount);
    }

    static String safeFailureCode(RuntimeException failure) {
        if (failure instanceof ServiceException service) {
            String detail = service.getDetailMessage();
            if (detail != null && FIXED_SKILL_FAILURE_CODES.contains(detail)) return detail;
        }
        return "UNCLASSIFIED_EXCEPTION";
    }

    static String safeDiagnosticCode(String value) {
        if ("NO_MATCHING_SELECTED_PAIR".equals(value)
                || "PUBLIC_MEDIA_AVAILABILITY_NULL".equals(value)
                || value != null && FIXED_AVAILABILITY_CODES.contains(value)) return value;
        return "UNCLASSIFIED";
    }

    static String safeErrorType(RuntimeException failure) {
        if (failure == null) return "NONE";
        return failure instanceof ServiceException ? "SERVICE_EXCEPTION" : "RUNTIME_EXCEPTION";
    }

    enum FixedSkillAvailabilityPhase {
        SCOPE_ACCESS,
        REQUIRE_PINNED,
        RAW_OFFER,
        FILTER_SELECTED_PAIR,
        PUBLIC_MEDIA_AVAILABILITY
    }

    private static final class FixedSkillAvailabilityTrace {
        private final Long runId;
        private FixedSkillAvailabilityPhase phase = FixedSkillAvailabilityPhase.SCOPE_ACCESS;
        private String reason;
        private Long skillVersionId;
        private int configuredCandidateCount = -1;
        private int activeCandidateCount = -1;
        private int rawModelCount = -1;
        private int rawChoiceCount = -1;
        private int matchingPairCount = -1;
        private boolean logged;

        private FixedSkillAvailabilityTrace(Long runId) {
            this.runId = runId;
        }
    }

    private RuntimeToolRegistry.Descriptor publicDescriptor(String code) {
        String publicCode = isImageTool(code) ? AssistantMediaRuntimeToolProvider.IMAGE_GENERATE
                : AUDIO_GENERATE.equals(code) ? AssistantMediaRuntimeToolProvider.AUDIO_GENERATE
                : MUSIC_GENERATE.equals(code) ? AssistantMediaRuntimeToolProvider.MUSIC_GENERATE
                : AssistantMediaRuntimeToolProvider.VIDEO_GENERATE;
        return mediaTools.descriptors().stream().filter(item -> publicCode.equals(item.code())).findFirst().orElseThrow();
    }

    @Override
    public com.aid.model.vo.AiModelVO projectModelCatalog(RuntimeToolRegistry.Descriptor descriptor, com.aid.model.vo.AiModelVO model) {
        return descriptor != null && isAudioTool(descriptor.code())
                ? mediaTools.projectModelCatalog(publicDescriptor(descriptor.code()), model) : model;
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (invocation == null || !CODES.contains(invocation.getToolCode())) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
        }
        JSONObject args;
        try {
            args = JSON.parseObject(invocation.getArgumentsJson());
        } catch (RuntimeException invalid) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,
                    invalid);
        }
        if (args == null) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID);
        }
        var target = localValidation(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID,
                () -> target(invocation, args));
        var workspace = localAccess(() -> access.requireWorkspace(
                target.workspaceId(), invocation.getUserId(), "OWNER", "EDITOR"));
        localAccess(() -> {
            access.requireFeature(invocation.getUserId(), target.projectId(), "canvas.generate");
            return null;
        });
        if (!Objects.equals(workspace.getProjectId(), target.projectId())) {
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID);
        }
        localValidation(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID, () -> {
            requireNode(target, physicalMediaType(invocation.getToolCode()));
            return null;
        });
        List<Long> explicitResources = localValidation(
                RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID,
                () -> resourceIds(args));
        var graphInputs = localValidation(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID,
                () -> referenceInputs.resolve(target.projectId(), target.workspaceId(), target.objectId(), explicitResources));
        List<Long> resources = graphInputs.resourceIds();
        if (isImageTool(invocation.getToolCode()) && graphInputs.roles().values().stream().anyMatch(role -> !"REFERENCE".equals(role)))
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID);
        JSONObject preparationResourceEvidence=resources.isEmpty()?null:captureOriginalResourceIdentity(target,resources,quoteResources.requireResources(target.workspaceId(),resources,invocation.getUserId()));
        if (isAudioTool(invocation.getToolCode())) {
            if (!resources.isEmpty()) throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID);
            JSONObject input = new JSONObject(args);
            input.remove("target");
            String field = MUSIC_GENERATE.equals(invocation.getToolCode()) ? "prompt" : "text";
            if (!CanvasAssistantReferenceInputs.textReferences(graphInputs).isEmpty())
                input.put(field, withLinkedText(input.getString(field), graphInputs, target.workspaceId(), invocation.getUserId()));
            return bindOriginalResourceEvidence(invocation, args, target,
                    mediaTools.prepareScopedAudio(invocation, input, target.projectId(), List.of(target.contentRef()), MUSIC_GENERATE.equals(invocation.getToolCode())),
                    preparationResourceEvidence, graphInputs);
        }
        if (IMAGE_GENERATE_WITH_SKILL.equals(invocation.getToolCode())) {
            if (imageSkills == null) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED);
            }
            var resolved = localSkill(() -> imageSkills.resolve(
                    invocation.getRunId(), invocation.getUserId(), args));
            JSONObject resolvedInput = new JSONObject(args);
            resolvedInput.put("modelCode", resolved.modelCode());
            resolvedInput.put("capabilityCode", resolved.capabilityCode());
            resolvedInput.put("prompt", resolved.resolvedPrompt());
            MediaImageGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.image(resolvedInput);
            request.setPrompt(withLinkedText(request.getPrompt(), graphInputs, target.workspaceId(), invocation.getUserId()));
            request.setTaskPromptDigest(resolved.dynamicPrompt());
            localReferences(() -> {
                generation.prepareAssistantImageReferences(target.workspaceId(), invocation.getUserId(),
                        invocation.getIdempotencyKey(), resources, request);
                return null;
            });
            boolean hasImageReference = request.getReferenceImageUrl() != null
                    && !request.getReferenceImageUrl().isBlank();
            if ("image_to_image".equals(resolved.capabilityCode()) && !hasImageReference) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IMAGE_REFERENCE_REQUIRED);
            }
            if ("text_to_image".equals(resolved.capabilityCode()) && hasImageReference) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID);
            }
            RuntimeToolPreparation preparation;
            try {
                preparation = mediaTools.prepareScopedImage(
                        invocation, request, target.projectId(), List.of(target.contentRef()));
            } catch (ServiceException invalid) {
                throw quoteValidation(invalid);
            }
            JSONObject payload;
            try {
                payload = JSON.parseObject(preparation.getPreparedPayloadJson());
            } catch (RuntimeException invalid) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_INVALID,
                        invalid);
            }
            if (payload == null) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_RESULT_INVALID);
            }
            payload.put("skillProvenance", imageSkills.provenance(resolved));
            preparation.setPreparedPayloadJson(payload.toJSONString());
            return bindOriginalResourceEvidence(invocation,args,target,preparation,preparationResourceEvidence,graphInputs);
        }
        if (isImageTool(invocation.getToolCode())) {
            MediaImageGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.image(args);
            request.setPrompt(withLinkedText(request.getPrompt(), graphInputs, target.workspaceId(), invocation.getUserId()));
            localReferences(() -> {
                generation.prepareAssistantImageReferences(target.workspaceId(), invocation.getUserId(),
                        invocation.getIdempotencyKey(), resources, request);
                return null;
            });
            boolean hasImageReference = request.getReferenceImageUrl() != null && !request.getReferenceImageUrl().isBlank();
            if (request.getCapabilityCode() == null || request.getCapabilityCode().isBlank()) {
                request.setCapabilityCode(hasImageReference ? "image_to_image" : "text_to_image");
            }
            if ("image_to_image".equals(request.getCapabilityCode()) && !hasImageReference) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_IMAGE_REFERENCE_REQUIRED);
            }
            try {
                return bindOriginalResourceEvidence(invocation,args,target,mediaTools.prepareScopedImage(invocation, request, target.projectId(), List.of(target.contentRef())),preparationResourceEvidence,graphInputs);
            } catch (ServiceException invalid) {
                throw quoteValidation(invalid);
            }
        }
        MediaVideoGenerateRequest request = com.aid.runtime.tool.RuntimeMediaInputContract.video(args);
        request.setPrompt(withLinkedText(request.getPrompt(), graphInputs, target.workspaceId(), invocation.getUserId()));
        localReferences(() -> {
            boolean explicitFrameRoles = graphInputs.roles().values().stream().anyMatch(role -> !"REFERENCE".equals(role));
            if (explicitFrameRoles) generation.prepareAssistantVideoReferences(target.workspaceId(), invocation.getUserId(),
                    invocation.getIdempotencyKey(), resources, graphInputs.roles(), request);
            else generation.prepareAssistantVideoReferences(target.workspaceId(), invocation.getUserId(),
                    invocation.getIdempotencyKey(), resources, request);
            return null;
        });
        try {
            return bindOriginalResourceEvidence(invocation,args,target,mediaTools.prepareScopedVideo(invocation, request, target.projectId(), List.of(target.contentRef())),preparationResourceEvidence,graphInputs);
        } catch (ServiceException invalid) {
            throw quoteValidation(invalid);
        }
    }

    private RuntimeException quoteValidation(ServiceException error) {
        var details = error.getClientErrorData();
        return RuntimeToolPreparationValidation.mediaQuote(error, details == null ? null : details.getErrorCode());
    }

    private <T> T localAccess(Supplier<T> action) {
        try {
            return action.get();
        } catch (ServiceException invalid) {
            if (!LOCAL_SCOPE_FAILURE_MESSAGES.contains(invalid.getMessage())) {
                throw invalid;
            }
            throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID,
                    invalid);
        }
    }

    private <T> T localReferences(Supplier<T> action) {
        try {
            return action.get();
        } catch (ServiceException invalid) {
            if (LOCAL_SCOPE_FAILURE_MESSAGES.contains(invalid.getMessage())) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SCOPE_INVALID,
                        invalid);
            }
            if (LOCAL_REFERENCE_FAILURE_MESSAGES.contains(invalid.getMessage())) {
                throw rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID,
                        invalid);
            }
            throw invalid;
        }
    }

    private <T> T localSkill(Supplier<T> action) {
        try {
            return action.get();
        } catch (ServiceException invalid) {
            String code = invalid.getDetailMessage();
            RuntimeToolPreparationRejectedException.Reason reason = switch (Objects.toString(code, "")) {
                case "ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE" ->
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_UNAVAILABLE;
                case "ASSISTANT_IMAGE_SKILL_INPUT_INVALID", "ASSISTANT_IMAGE_SKILL_PROMPT_TOO_LARGE" ->
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID;
                case "ASSISTANT_IMAGE_SKILL_RUN_INVALID", "ASSISTANT_IMAGE_SKILL_SELECTION_INVALID",
                     "ASSISTANT_IMAGE_SKILL_VERSION_CHANGED", "ASSISTANT_IMAGE_SKILL_CAPABILITY_INVALID",
                     "ASSISTANT_IMAGE_SKILL_SCHEMA_NOT_READY" ->
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_CONTRACT_CHANGED;
                default -> null;
            };
            if (reason == null) throw invalid;
            throw rejected(reason, invalid);
        }
    }

    private <T> T localValidation(RuntimeToolPreparationRejectedException.Reason reason,
                                  Supplier<T> action) {
        try {
            return action.get();
        } catch (ServiceException invalid) {
            throw rejected(reason, invalid);
        }
    }

    private static RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason) {
        return new RuntimeToolPreparationRejectedException(reason);
    }

    private static RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason, Throwable cause) {
        return new RuntimeToolPreparationRejectedException(reason, cause);
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        throw failure("CANVAS_MEDIA_PREPARED_EXECUTION_REQUIRED");
    }

    @Override
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared, BillingBudgetContext budget) {
        RuntimeToolInvocation invocation = CanvasAssistantToolProvider.invocation(prepared);
        invocation.setArgumentsJson(prepared.getArgumentsJson());
        invocation.setArgumentsDigest(prepared.getArgumentsDigest());
        if (!CODES.contains(invocation.getToolCode()) || prepared.getRunLease() == null) throw failure("CANVAS_MEDIA_TOOL_INVALID");
        var target = CanvasMediaPresentationTarget.restore(invocation, JSON.parseObject(invocation.getArgumentsJson()), prepared.getPreparedPayloadJson());
        String mediaType = physicalMediaType(invocation.getToolCode());
        String logicalType = logicalMediaType(invocation.getToolCode());
        if (IMAGE_GENERATE_WITH_SKILL.equals(invocation.getToolCode())) imageSkills.requirePrepared(prepared);
        mediaTools.requirePreparedMedia(prepared, budget, logicalType);
        if (prepared.getOperationScope() == null || !scopeTargetRefs(target).equals(prepared.getOperationScope().getTargetRefs())
                || !List.of(logicalType).equals(prepared.getOperationScope().getMediaTypes())) throw failure("CANVAS_MEDIA_TARGET_CHANGED");
        String operationId = CanvasGenerationServiceImpl.assistantMediaOperationId(prepared.getRunId(), prepared.getIdempotencyKey());
        TaskBinding existing = canvasMapper.selectTaskBindingByOperation(target.workspaceId(), operationId);
        if (existing != null) {
            if (!Objects.equals(existing.getActorUserId(), prepared.getUserId())
                    || !Objects.equals(existing.getProjectId(), target.projectId())
                    || !Objects.equals(existing.getObjectId(), target.objectId())
                    || !Objects.equals(existing.getInputObjectVersion(), target.objectVersion())
                    || !Objects.equals(existing.getCallId(), prepared.getIdempotencyKey())
                    || !Objects.equals(existing.getRequestHash(), prepared.getArgumentsDigest())
                    || !Objects.equals(existing.getMediaType(), mediaType)) throw failure("CANVAS_MEDIA_INTENT_CHANGED");
            if (existing.getTaskId() != null) {
                return mediaTools.scopedTaskResult(prepared, generation.taskDetail(target.workspaceId(), existing.getTaskId(), prepared.getUserId()));
            }
        }
        var runLease = prepared.getRunLease();
        var lease = new RuntimeScopeAccess.Lease(runLease.getOwner(), runLease.getFencingToken(), runLease.getLeaseUntil());
        RuntimeScopeAccess.Scope scope = existing == null
                ? reserveBeforeMediaEntry(prepared, budget, lease, target)
                : scopeAccess.authorizeExactTarget(prepared.getRunId(), prepared.getUserId(), lease, target.projectId(),
                    target.workspaceId(), target.objectId(), target.objectVersion());
        if (existing != null) scope = new RuntimeScopeAccess.Scope(scope.runId(), scope.userId(), scope.projectId(),
                scope.workspaceId(), scope.objectId(), scope.objectVersion(), scope.leaseOwner(), scope.runtimeFencingToken(),
                scope.leaseUntil(), canvasMapper.selectObjectFence(scope.workspaceId(), scope.objectId()));
        try {
            requireNode(target, mediaType);
            var response = generation.submitAssistantMedia(scope, CanvasAssistantToolProvider.submissionClaim(prepared),
                    mediaType, prepared.getArgumentsDigest(), () -> {
                        var referenceResources = validateBeforeMediaEntry(prepared, budget);
                        if (isImageTool(prepared.getToolCode())) {
                            var managedReferences = managedImageReferences(referenceResources);
                            return mediaTools.submitPreparedScopedImage(prepared, budget, managedReferences,
                                    authorizedImageReferences(referenceResources));
                        }
                        return isAudioTool(prepared.getToolCode()) ? mediaTools.submitPreparedScopedAudio(prepared, budget, MUSIC_GENERATE.equals(prepared.getToolCode()))
                                : mediaTools.submitPreparedScopedVideo(prepared, budget);
                    });
            return mediaTools.scopedTaskResult(prepared, response);
        } finally {
            // Once transferred, release() cannot remove the distinct TASK_PENDING/TASK owner.
            scopeAccess.release(scope);
        }
    }

    @Override
    public SubmissionEntryEvidence inspectSubmissionEntry(RuntimeToolPreparedInvocation prepared,
                                                          BillingBudgetContext budget) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw failure("CANVAS_MEDIA_ENTRY_EVIDENCE_TRANSACTION_REQUIRED");
        if (prepared == null || !CODES.contains(prepared.getToolCode())) return SubmissionEntryEvidence.UNKNOWN;
        var invocation = CanvasAssistantToolProvider.invocation(prepared);
        invocation.setArgumentsJson(prepared.getArgumentsJson());
        var original = CanvasMediaPresentationTarget.restore(invocation, JSON.parseObject(prepared.getArgumentsJson()), prepared.getPreparedPayloadJson());
        String mediaType = logicalMediaType(prepared.getToolCode());
        // Immutable request/budget identity only. Expired quotes and revoked current access do not
        // authorize new work here, and do not prevent closing the old unsubmitted reservation.
        mediaTools.requirePreparedMedia(prepared, budget, mediaType);
        if (prepared.getOperationScope() == null
                || !scopeTargetRefs(original).equals(prepared.getOperationScope().getTargetRefs()))
            return SubmissionEntryEvidence.UNKNOWN;
        String operationId = CanvasGenerationServiceImpl.assistantMediaOperationId(
                prepared.getRunId(), prepared.getIdempotencyKey());
        TaskBinding intent = canvasMapper.selectTaskBindingByOperationForUpdate(original.workspaceId(), operationId);
        // submitAssistantMedia locks this same Runtime graph before committing its durable intent,
        // and never invokes media submission before that commit. Bindings have no delete path.
        // A conflicting call/operation anywhere is a veto. This read does not lock the entire
        // binding table on installations without a standalone call index; Runtime fences first entry.
        var conflicting = canvasMapper.selectAssistantMediaEntryNamespace(operationId, prepared.getIdempotencyKey());
        if (intent != null) return failedTasklessIntentMatches(intent, original, prepared, operationId, conflicting)
                ? SubmissionEntryEvidence.NO_ENTRY_PROVEN : SubmissionEntryEvidence.UNKNOWN;
        return conflicting != null && conflicting.isEmpty()
                ? SubmissionEntryEvidence.NO_ENTRY_PROVEN : SubmissionEntryEvidence.UNKNOWN;
    }

    static boolean failedTasklessIntentMatches(TaskBinding intent, CanvasAssistantContextManifest.TargetRef target,
            RuntimeToolPreparedInvocation prepared, String operationId, List<Long> namespace) {
        // This durable adapter intent precedes media task creation. Every unified media submit
        // commits its task before contacting the provider. FAILED is terminal for this intent,
        // and cannot be resubmitted. The Runtime caller must STILL prove absence of all matching
        // task/call/billing-trace rows and zero wallet movement under its original fences.
        return intent != null && target != null && prepared != null && namespace != null && namespace.size() == 1
                && prepared.getRunId() != null && prepared.getRunId() > 0
                && prepared.getUserId() != null && prepared.getUserId() > 0
                && prepared.getToolCode() != null && CODES.contains(prepared.getToolCode())
                && prepared.getIdempotencyKey() != null && !prepared.getIdempotencyKey().isBlank()
                && prepared.getIdempotencyKey().length() <= 100
                && prepared.getArgumentsDigest() != null && prepared.getArgumentsDigest().matches("[0-9a-f]{64}")
                && Objects.equals(operationId, CanvasGenerationServiceImpl.assistantMediaOperationId(prepared.getRunId(), prepared.getIdempotencyKey()))
                && intent.getId() != null && Objects.equals(namespace.get(0), intent.getId())
                && "FAILED".equals(intent.getStatus()) && intent.getTaskId() == null
                && (intent.getResultUrl() == null || intent.getResultUrl().isBlank())
                && Objects.equals(intent.getProjectId(), target.projectId())
                && Objects.equals(intent.getWorkspaceId(), target.workspaceId())
                && Objects.equals(intent.getObjectId(), target.objectId())
                && Objects.equals(intent.getInputObjectVersion(), target.objectVersion())
                && Objects.equals(intent.getActorUserId(), prepared.getUserId())
                && Objects.equals(intent.getOperationId(), operationId)
                && Objects.equals(intent.getCallId(), prepared.getIdempotencyKey())
                && Objects.equals(intent.getRequestHash(), prepared.getArgumentsDigest())
                && Objects.equals(intent.getMediaType(), physicalMediaType(prepared.getToolCode()));
    }

    private RuntimeScopeAccess.Scope reserveBeforeMediaEntry(RuntimeToolPreparedInvocation prepared,
            BillingBudgetContext budget, RuntimeScopeAccess.Lease lease,
            CanvasAssistantContextManifest.TargetRef target) {
        try {
            return scopeAccess.reserveTarget(prepared.getRunId(), prepared.getUserId(), lease, target.projectId(),
                    target.workspaceId(), target.objectId(), target.objectVersion());
        } catch (RuntimeException notEntered) {
            // No prior Canvas binding was found and this invocation has not entered generation.
            // The common runtime must separately prove initial READY state, current fences and
            // absence of task/financial side effects before releasing the original reservation.
            throw new com.aid.media.admission.MediaSubmissionNotEnteredException(
                    "CANVAS_SCOPE_NOT_ENTERED", notEntered, budget);
        }
    }

    static List<com.aid.assistant.dto.AssistantRequests.ContextRef> scopeTargetRefs(
            CanvasAssistantContextManifest.TargetRef target) {
        var ref = new com.aid.assistant.dto.AssistantRequests.ContextRef();
        ref.setKind("FREE_CANVAS");
        ref.setProjectId(target.projectId());
        ref.setWorkspaceId(target.workspaceId());
        ref.setObjectId(target.objectId());
        ref.setVersion(String.valueOf(target.objectVersion()));
        return List.of(ref);
    }

    private CanvasAssistantContextManifest.TargetRef target(RuntimeToolInvocation invocation, JSONObject args) {
        return CanvasMediaPresentationTarget.prepare(invocation, args);
    }

    private void requireNode(CanvasAssistantContextManifest.TargetRef target, String mediaType) {
        CanvasObject node = canvasMapper.selectCanvasObject(target.workspaceId(), target.objectId());
        JSONObject content;
        try {
            content = node == null ? null : JSON.parseObject(node.getContentJson());
        } catch (RuntimeException invalid) {
            throw failure("CANVAS_MEDIA_NODE_TYPE_OR_VERSION_INVALID");
        }
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), target.objectVersion())
                || content == null || !("AUDIO".equals(mediaType) ? Set.of("VOICE", "AUDIO", "MUSIC").contains(Objects.toString(content.getString("nodeType"), ""))
                : mediaType.equals(content.getString("nodeType")))) throw failure("CANVAS_MEDIA_NODE_TYPE_OR_VERSION_INVALID");
    }

    private List<Long> resourceIds(JSONObject args) {
        var values = args.getJSONArray("resourceIds");
        if (values == null) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String id) || !id.matches("[1-9][0-9]{0,18}")) throw failure("CANVAS_MEDIA_RESOURCE_INVALID");
            try { result.add(Long.valueOf(id)); } catch (NumberFormatException invalid) { throw failure("CANVAS_MEDIA_RESOURCE_INVALID"); }
        }
        return List.copyOf(result);
    }

    private static ServiceException failure(String code) {
        return new ServiceException("画布媒体生成暂不可执行").setDetailMessage(code);
    }
    @jakarta.annotation.Resource private com.aid.canvas.service.CanvasResourceService quoteResources;
    @jakarta.annotation.Resource private CanvasAssistantReferenceInputs referenceInputs;
    @jakarta.annotation.Resource private com.aid.generation.service.GenerationResourceResolver graphTextResources;

    private String withLinkedText(String prompt, CanvasAssistantReferenceInputs.Resolved inputs, Long workspaceId, Long userId) {
        var references = CanvasAssistantReferenceInputs.textReferences(inputs);
        if (references.isEmpty()) return prompt;
        String context = graphTextResources.resolveCanvasTextReferences(workspaceId, references, userId);
        return Objects.toString(prompt, "") + context;
    }

    private List<Long> frozenResourceIds(JSONObject arguments, String preparedPayload,
                                        CanvasAssistantContextManifest.TargetRef selected) {
        List<Long> explicit = resourceIds(arguments);
        var graph = referenceInputs.requireFrozen(JSON.parseObject(preparedPayload), selected.projectId(),
                selected.workspaceId(), selected.objectId(), explicit);
        return graph == null ? explicit : graph.resourceIds();
    }

    @Override
    public void validateQuoteReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        if(source==null || !CODES.contains(source.toolCode()))throw failure("OPERATION_QUOTE_NOT_SUPPORTED");
        JSONObject args=JSON.parseObject(source.argumentsJson());
        RuntimeToolInvocation identity=new RuntimeToolInvocation();
        identity.setRunId(source.runId());identity.setUserId(source.userId());
        identity.setContextManifestJson(source.contextManifestJson());
        var selected=CanvasMediaPresentationTarget.restore(identity,args,source.preparedPayloadJson());
        var workspace=access.requireWorkspace(selected.workspaceId(),source.userId(),"OWNER","EDITOR");
        access.requireFeature(source.userId(),selected.projectId(),"canvas.generate");
        if(!Objects.equals(workspace.getProjectId(),selected.projectId()))throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        requireNode(selected,physicalMediaType(source.toolCode()));
        var scope=JSON.parseObject(source.operationScopeJson(),com.aid.assistant.budget.AssistantBudgetOperationScope.class);
        if(scope==null || !Objects.equals(scope.getTargetRefs(),scopeTargetRefs(selected)))throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        String operationId=CanvasGenerationServiceImpl.assistantMediaOperationId(source.runId(),source.idempotencyKey());
        if(canvasMapper.selectTaskBindingByOperation(selected.workspaceId(),operationId)!=null)
            throw failure("OPERATION_QUOTE_SUBMISSION_UNPROVEN");
        var ids=frozenResourceIds(args, source.preparedPayloadJson(), selected);
        if(ids.size()!=new java.util.HashSet<>(ids).size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        if(!ids.isEmpty()) {
            var current=quoteResources.requireResources(selected.workspaceId(),ids,source.userId());
            if(current==null || current.size()!=ids.size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            JSONObject evidence=quoteResourceEvidence(selected,ids,current,source.userId());
            requireFrozenResourceUrls(source,current);
            JSONObject payload=JSON.parseObject(source.preparedPayloadJson());
            JSONObject original=payload==null?null:payload.getJSONObject("serverResourceEvidence");
            boolean originalProof=sameQuoteEvidence(original,evidence) || quoteManifestResourceProof(source,selected,current);
            if(source.referenceRevalidationJson()!=null) {
                if(!sameQuoteEvidence(JSON.parseObject(source.referenceRevalidationJson()),evidence))
                    throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            } else if(!originalProof) throw new com.aid.runtime.tool.RuntimeToolQuoteReferenceRequiredException(evidence);
        } else if(source.referenceRevalidationJson()!=null)throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        if(IMAGE_GENERATE_WITH_SKILL.equals(source.toolCode())) {
            if(imageSkills==null)throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            var prepared=new RuntimeToolPreparedInvocation();prepared.setRunId(source.runId());prepared.setUserId(source.userId());
            prepared.setToolCode(source.toolCode());prepared.setArgumentsJson(source.argumentsJson());
            prepared.setPreparedPayloadJson(source.preparedPayloadJson());prepared.setOperationScope(scope);
            imageSkills.requirePrepared(prepared);
        }
        mediaTools.validateQuoteReadOnly(source);
    }

    private java.util.Map<String,com.aid.media.dto.AuthorizedImageAssetSnapshot> authorizedImageReferences(
            java.util.List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources) {
        var result = new java.util.LinkedHashMap<String,com.aid.media.dto.AuthorizedImageAssetSnapshot>();
        for (var resource : resources) {
            if (!"IMAGE".equals(resource.getMediaType())) throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            String url = quoteMediaUrls.toFullUrl(resource.getStorageUrl());
            var snapshot = new com.aid.media.dto.AuthorizedImageAssetSnapshot();
            snapshot.setResourceId(resource.getId()); snapshot.setLocator(url);
            snapshot.setFileSize(resource.getFileSize()); snapshot.setMimeType(resource.getMimeType());
            var metadata = JSON.parseObject(resource.getMetadataJson());
            snapshot.setWidth(metadata == null ? null : metadata.getInteger("width"));
            snapshot.setHeight(metadata == null ? null : metadata.getInteger("height"));
            snapshot.setResourceIdentityDigest(CanvasResourceRuntimeScopeAccess.identityHash(resource));
            snapshot.requireValid(url, "参考图");
            if (result.putIfAbsent(url, snapshot) != null) throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        }
        return result;
    }

    @Override
    public RuntimeToolPreparation repricePreparedQuote(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        validateQuoteReadOnly(source);
        if (VIDEO_GENERATE.equals(source.toolCode())) {
            var resources=requirePreparedReferenceResources(source);
            if (!resources.isEmpty() && resources.stream().allMatch(row->"IMAGE".equals(row.getMediaType()))) {
                var images=authorizedImageReferences(resources);
                source=source.withAuthorizedQuoteImages(images);
            }
        }
        return mediaTools.repricePreparedQuote(source);
    }

    @jakarta.annotation.Resource private com.aid.common.aid.oss.util.MediaUrlResolver quoteMediaUrls;
    @jakarta.annotation.Resource private com.aid.media.resolver.ReferenceVideoRecordResolver quoteVideoReferences;

    private boolean sameQuoteEvidence(JSONObject left,JSONObject right) {
        return left!=null && right!=null && Objects.equals(JSON.parseObject(left.toJSONString()),JSON.parseObject(right.toJSONString()));
    }

    private JSONObject quoteResourceEvidence(CanvasAssistantContextManifest.TargetRef selected,List<Long> ids,
            List<com.aid.canvas.domain.CanvasEntities.CanvasResource> current,Long userId) {
        JSONObject evidence=captureOriginalResourceIdentity(selected,ids,current);
        if(current.stream().allMatch(resource->"VIDEO".equals(resource.getMediaType()))) {
            var request=new MediaVideoGenerateRequest();request.setProjectId(selected.projectId());request.setWorkspaceId(selected.workspaceId());
            request.setUserId(userId);request.setReferenceVideoResourceIds(ids);
            quoteVideoReferences.resolveAndApply(request,userId,false);
            var videos=new com.alibaba.fastjson2.JSONArray();
            for(var video:request.getResolvedReferenceVideos()) {
                if(video.getRecordId()==null || video.getDurationMs()==null || video.getDurationMs()<=0
                        || video.getFileSizeBytes()==null || video.getFileSizeBytes()<=0
                        || video.getWidth()==null || video.getWidth()<=0 || video.getHeight()==null || video.getHeight()<=0
                        || video.getFps()==null || video.getFps().signum()<=0 || video.getFormat()==null || video.getFormat().isBlank())
                    throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
                JSONObject row=new JSONObject();row.put("recordId",video.getRecordId());row.put("videoUrl",video.getVideoUrl());
                row.put("durationMs",video.getDurationMs());row.put("fileSizeBytes",video.getFileSizeBytes());
                row.put("width",video.getWidth());row.put("height",video.getHeight());row.put("fps",video.getFps());row.put("format",video.getFormat());videos.add(row);
            }
            if(videos.size()!=ids.size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            evidence.put("referenceVideos",videos);
        }
        return evidence;
    }

    private JSONObject captureOriginalResourceIdentity(CanvasAssistantContextManifest.TargetRef selected,List<Long> ids,
            List<com.aid.canvas.domain.CanvasEntities.CanvasResource> current) {
        if(current==null || current.size()!=ids.size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        JSONObject evidence=new JSONObject();evidence.put("schemaVersion","assistant-operation-resource-evidence/v1");
        var resources=new com.alibaba.fastjson2.JSONArray();
        for(int index=0;index<ids.size();index++) {
            var resource=current.get(index);
            if(resource==null || !Objects.equals(ids.get(index),resource.getId()) || !"ACTIVE".equals(resource.getStatus())
                    || !Objects.equals(resource.getWorkspaceId(),selected.workspaceId())
                    || !Objects.equals(resource.getProjectId(),selected.projectId())
                    || resource.getMediaType()==null || !java.util.Set.of("IMAGE","VIDEO","AUDIO").contains(resource.getMediaType())
                    || resource.getStorageUrl()==null || resource.getStorageUrl().isBlank())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            JSONObject row=new JSONObject();row.put("resourceId",String.valueOf(resource.getId()));
            row.put("identityHash",CanvasResourceRuntimeScopeAccess.identityHash(resource));row.put("mediaType",resource.getMediaType());
            resources.add(row);
        }
        evidence.put("resources",resources);
        return evidence;
    }

    private boolean quoteManifestResourceProof(com.aid.runtime.tool.RuntimeToolQuoteRequest source,
            CanvasAssistantContextManifest.TargetRef selected,List<com.aid.canvas.domain.CanvasEntities.CanvasResource> current) {
        JSONObject manifest=JSON.parseObject(source.contextManifestJson());var refs=manifest==null?null:manifest.getJSONArray("refs");
        if(refs==null || refs.size()>1001)return false;
        for(var resource:current) {
            int matches=0;String hash=CanvasResourceRuntimeScopeAccess.identityHash(resource);
            for(Object item:refs) {
                if(!(item instanceof JSONObject row) || !"CANVAS_RESOURCE".equals(row.getString("kind")))continue;
                JSONObject pin=row.getJSONObject("canonicalRef");
                if(pin!=null && Objects.equals(pin.getLong("resourceId"),resource.getId())
                        && Objects.equals(pin.getLong("workspaceId"),selected.workspaceId())
                        && Objects.equals(pin.getLong("projectId"),selected.projectId())
                        && Objects.equals(pin.getString("contentHash"),hash)
                        && Objects.equals(row.getString("contentRef"),"canvas:resource:"+selected.workspaceId()+":"+resource.getId()+":"+hash))matches++;
            }
            if(matches!=1)return false;
        }
        return true;
    }

    private void requireFrozenResourceUrls(com.aid.runtime.tool.RuntimeToolQuoteRequest source,
            List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources) {
        JSONObject intent=JSON.parseObject(source.billingIntentJson());
        JSONObject request=intent==null?null:JSON.parseObject(intent.getString("normalizedRequestJson"));
        if(request==null)throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        if(isImageTool(source.toolCode())) {
            if(resources.stream().anyMatch(resource->!"IMAGE".equals(resource.getMediaType())))throw failure("OPERATION_QUOTE_INPUT_CHANGED");
            var urls=resources.stream().map(value->quoteMediaUrls.toFullUrl(value.getStorageUrl())).toList();
            var options=request.getJSONObject("options");var original=options==null?null:options.getJSONArray("referenceImages");
            // Derive the expected reference slots with the same contract used by the
            // original quote. Ordinary image-to-image clears the primary slot;
            // edit capabilities keep the primary image separate from other refs.
            var expected=new com.aid.media.dto.MediaImageGenerateRequest();
            expected.setCapabilityCode(request.getString("capabilityCode"));
            expected.setReferenceImageUrl(urls.get(0));
            expected.setOptions(new java.util.LinkedHashMap<>(java.util.Map.of("referenceImages",urls)));
            com.aid.media.util.ReferenceMediaRequestNormalizer.normalize(null,expected,null);
            var expectedRefs=expected.getOptions()==null?null:expected.getOptions().get("referenceImages");
            if(!Objects.equals(original==null?null:original.toJavaList(String.class),expectedRefs)
                    || !Objects.equals(request.getString("referenceImageUrl"),expected.getReferenceImageUrl()))
                throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        } else {
            JSONObject prepared = JSON.parseObject(source.preparedPayloadJson());
            if (prepared != null && prepared.containsKey(CanvasAssistantReferenceInputs.FIELD)) {
                // New preparations bind the authorized resources, graph selections and normalized
                // request in the same signed prepared payload. Reuse that projection, including
                // first/last frames and audio, instead of interpreting those roles a second time.
                // The caller has revalidated the graph and checks current resource identities;
                // the shared media provider additionally verifies the full request digest.
                requireFrozenVideoProjection(prepared, request, resources);
                return;
            }
            // Only originally registered VIDEO refs with complete frozen metadata are repricable.
            if(resources.stream().anyMatch(resource->!"VIDEO".equals(resource.getMediaType())))throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            var ids=request.getJSONArray("referenceVideoResourceIds");var options=request.getJSONObject("options");
            var urls=options==null?null:options.getJSONArray("referenceVideos");
            if(ids==null || urls==null || ids.size()!=resources.size() || urls.size()!=resources.size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            for(int index=0;index<resources.size();index++) {
                var resource=resources.get(index);
                if(!Objects.equals(ids.getLong(index),resource.getId())
                        || !Objects.equals(urls.getString(index),quoteMediaUrls.toFullUrl(resource.getStorageUrl())))
                    throw failure("OPERATION_QUOTE_INPUT_CHANGED");
            }
        }
    }

    static void requireFrozenVideoProjection(JSONObject prepared, JSONObject request) {
        JSONObject graph = prepared.getJSONObject(CanvasAssistantReferenceInputs.FIELD);
        JSONObject resources = prepared.getJSONObject("serverResourceEvidence");
        JSONObject original = JSON.parseObject(prepared.getString("normalizedRequestJson"));
        if (graph == null || !"canvas-graph-input/v1".equals(graph.getString("schemaVersion"))
                || resources == null
                || !"assistant-operation-resource-evidence/v1".equals(resources.getString("schemaVersion"))
                || original == null || request == null || !Objects.equals(original, request))
            throw failure("OPERATION_QUOTE_INPUT_CHANGED");
    }

    static void requireFrozenVideoProjection(JSONObject prepared, JSONObject request,
            List<com.aid.canvas.domain.CanvasEntities.CanvasResource> resources) {
        requireFrozenVideoProjection(prepared, request);
        JSONArray original = prepared.getJSONObject("serverResourceEvidence").getJSONArray("resources");
        if (original == null || original.size() != resources.size()) throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        for (int i = 0; i < resources.size(); i++) {
            var resource = resources.get(i);
            JSONObject row = original.getJSONObject(i);
            // A fresh reference acknowledgement cannot authorize changing the already frozen URL
            // projection. Identity changes require a genuinely new preparation, not repricing.
            if (resource == null || row == null || !Objects.equals(row.getString("resourceId"), String.valueOf(resource.getId()))
                    || !Objects.equals(row.getString("mediaType"), resource.getMediaType())
                    || !Objects.equals(row.getString("identityHash"), CanvasResourceRuntimeScopeAccess.identityHash(resource)))
                throw failure("OPERATION_QUOTE_INPUT_CHANGED");
        }
    }

    private RuntimeToolPreparation bindOriginalResourceEvidence(RuntimeToolInvocation invocation,JSONObject args,
            CanvasAssistantContextManifest.TargetRef selected,RuntimeToolPreparation preparation,JSONObject expectedEvidence,
            CanvasAssistantReferenceInputs.Resolved graphInputs) {
        JSONObject targetPayload=JSON.parseObject(preparation.getPreparedPayloadJson());
        CanvasMediaPresentationTarget.freeze(targetPayload,args,selected);
        targetPayload.put(CanvasAssistantReferenceInputs.FIELD, graphInputs.evidence());
        referenceInputs.requireFrozen(targetPayload, selected.projectId(), selected.workspaceId(), selected.objectId(), resourceIds(args));
        preparation.setPreparedPayloadJson(targetPayload.toJSONString());
        List<Long> ids=graphInputs.resourceIds();if(ids.isEmpty())return preparation;
        var current=quoteResources.requireResources(selected.workspaceId(),ids,invocation.getUserId());
        JSONObject evidence=captureOriginalResourceIdentity(selected,ids,current);
        // Original multimodal preparation keeps its existing sanitize/probe contract. Narrow
        // readonly-requote restrictions must never reject an otherwise legitimate first quote.
        if(!sameQuoteEvidence(expectedEvidence,evidence))throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        JSONObject payload=JSON.parseObject(preparation.getPreparedPayloadJson());
        var trustedVideos=payload.getJSONArray("serverReferenceVideos");
        if(current.stream().allMatch(row->"VIDEO".equals(row.getMediaType())) && trustedVideos!=null && trustedVideos.size()==ids.size()) {
            boolean exact=true;for(int i=0;i<ids.size();i++)if(!Objects.equals(ids.get(i),trustedVideos.getJSONObject(i).getLong("recordId")))exact=false;
            if(exact) {
                try {
                    var currentFull=quoteResourceEvidence(selected,ids,current,invocation.getUserId());
                    if(Objects.equals(JSON.parseArray(trustedVideos.toJSONString()),JSON.parseArray(currentFull.getJSONArray("referenceVideos").toJSONString())))
                        evidence.put("referenceVideos",trustedVideos);
                } catch(ServiceException unavailable) {
                    // Original preparation may legitimately probe missing metadata. It must not
                    // be rejected by the later narrow readonly-requote capability.
                    if(!"OPERATION_QUOTE_REFERENCE_UNAVAILABLE".equals(unavailable.getDetailMessage()))throw unavailable;
                }
            }
        }
        payload.put("serverResourceEvidence",evidence);
        preparation.setPreparedPayloadJson(payload.toJSONString());return preparation;
    }

    @Override
    public void validatePreparedReferencesReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        requirePreparedReferenceResources(source);
    }

    private List<CanvasResource> requirePreparedReferenceResources(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        JSONObject payload=JSON.parseObject(source.preparedPayloadJson());
        JSONObject proof=payload==null?null:payload.getJSONObject("serverResourceEvidence");
        if(proof==null && (payload==null || !payload.containsKey(CanvasAssistantReferenceInputs.FIELD)))return List.of();
        try {
            if(proof!=null && (!"assistant-operation-resource-evidence/v1".equals(proof.getString("schemaVersion"))
                    || !(java.util.Set.of("schemaVersion","resources").equals(proof.keySet())
                         || java.util.Set.of("schemaVersion","resources","referenceVideos").equals(proof.keySet()))))
                throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            JSONObject args=JSON.parseObject(source.argumentsJson());var invocation=new RuntimeToolInvocation();
            invocation.setRunId(source.runId());invocation.setUserId(source.userId());invocation.setContextManifestJson(source.contextManifestJson());
            var selected=CanvasMediaPresentationTarget.restore(invocation,args,source.preparedPayloadJson());var workspace=access.requireWorkspace(selected.workspaceId(),source.userId(),"OWNER","EDITOR");
            access.requireFeature(source.userId(),selected.projectId(),"canvas.generate");
            if(!Objects.equals(workspace.getProjectId(),selected.projectId()))throw failure("OPERATION_QUOTE_INPUT_CHANGED");
            requireNode(selected,physicalMediaType(source.toolCode()));
            var scope=JSON.parseObject(source.operationScopeJson(),com.aid.assistant.budget.AssistantBudgetOperationScope.class);
            if(scope==null || !Objects.equals(scope.getTargetRefs(),scopeTargetRefs(selected)))throw failure("OPERATION_QUOTE_INPUT_CHANGED");
            var ids=frozenResourceIds(args,source.preparedPayloadJson(),selected);
            if(ids.isEmpty() && proof==null)return List.of();
            if(proof==null || ids.isEmpty() || ids.size()!=new java.util.HashSet<>(ids).size())throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            var rows=quoteResources.requireResources(selected.workspaceId(),ids,source.userId());
            var current=captureOriginalResourceIdentity(selected,ids,rows);
            // Arrays are compared as complete normalized JSON, retaining order and every field.
            if(proof.getJSONArray("resources")==null || !Objects.equals(JSON.parseArray(proof.getJSONArray("resources").toJSONString()),
                        JSON.parseArray(current.getJSONArray("resources").toJSONString())))
                throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            if(proof.containsKey("referenceVideos")) {
                var full=quoteResourceEvidence(selected,ids,rows,source.userId());
                if(proof.getJSONArray("referenceVideos")==null || !Objects.equals(JSON.parseArray(proof.getJSONArray("referenceVideos").toJSONString()),
                        JSON.parseArray(full.getJSONArray("referenceVideos").toJSONString())))throw failure("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
            }
            return rows;
        } catch(ServiceException denied) {
            String message=denied.getMessage();
            if(message!=null && java.util.Set.of("项目不存在或无权访问","当前项目角色无权执行该操作","工作区不存在","当前账号未获得该画布功能权益","素材不存在或不属于当前工作区").contains(message))
                throw failure("OPERATION_REFERENCE_ACCESS_DENIED");
            throw denied;
        }
    }

    private static String definiteReferenceFailure(ServiceException failure) {
        var codes=java.util.Set.of("OPERATION_QUOTE_REFERENCE_UNAVAILABLE","OPERATION_QUOTE_INPUT_CHANGED","OPERATION_REFERENCE_ACCESS_DENIED",
                "CANVAS_TARGET_INVALID","CANVAS_TARGET_NOT_AUTHORIZED","CANVAS_REVISION_INVALID","CANVAS_MEDIA_NODE_TYPE_OR_VERSION_INVALID",
                "CANVAS_MANIFEST_MISSING","CANVAS_MANIFEST_INVALID","CANVAS_MANIFEST_REFS_INVALID","CANVAS_MANIFEST_REF_INVALID","CANVAS_CANONICAL_REF_INVALID",
                "CANVAS_TARGET_REF_INVALID","CANVAS_CONTENT_REF_INVALID","CANVAS_PROJECT_INVALID","CANVAS_WORKSPACE_INVALID","CANVAS_ACCESS_INVALID");
        if(failure.getDetailMessage()!=null && codes.contains(failure.getDetailMessage()))return failure.getDetailMessage();
        return failure.getMessage()!=null && codes.contains(failure.getMessage())?failure.getMessage():null;
    }

    private List<CanvasResource> validateBeforeMediaEntry(RuntimeToolPreparedInvocation prepared,BillingBudgetContext budget) {
        var request=new com.aid.runtime.tool.RuntimeToolQuoteRequest(prepared.getRunId(),prepared.getUserId(),prepared.getRuntimeStepId(),
                prepared.getPlanRevision(),prepared.getStableStepId(),prepared.getAttemptNo(),prepared.getOperationKind(),prepared.getToolCode(),
                prepared.getContractVersion(),prepared.getSchemaDigest(),prepared.getArgumentsJson(),prepared.getArgumentsDigest(),
                prepared.getContextManifestJson(),prepared.getApprovedScopeDigest(),prepared.getIdempotencyKey(),JSON.toJSONString(prepared.getOperationScope()),
                prepared.getPreparedPayloadJson(),cn.hutool.crypto.SecureUtil.sha256(prepared.getPreparedPayloadJson()),JSON.toJSONString(prepared.getBillingIntent()),null);
        try {return requirePreparedReferenceResources(request);}
        catch(ServiceException denied) {
            String code=definiteReferenceFailure(denied);if(code==null)throw denied;
            // Called inside the original guarded supplier and before any media service entry.
            // The exception alone never refunds: Runtime still proves exact task absence/fences.
            throw new com.aid.media.admission.MediaSubmissionNotEnteredException(code,denied,budget);
        }
    }

    private Map<String, String> managedImageReferences(List<CanvasResource> resources) {
        Map<String, String> references = new LinkedHashMap<>();
        for (CanvasResource resource : resources) {
            String reference = localImages.referenceIfAvailable(resource);
            if (reference != null) references.put(quoteMediaUrls.toFullUrl(resource.getStorageUrl()), reference);
        }
        return references;
    }
}
