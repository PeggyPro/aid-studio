package com.aid.assistant.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolBudgetOffer;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.service.SkillContractExamples;
import com.aid.skill.service.SkillImageExecutionContract;
import com.aid.skill.service.SkillModelConfiguration;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves one selected IMAGE entrypoint into a fixed, server-owned media invocation. */
@Service
@RequiredArgsConstructor
public class AssistantImageSkillInvocationResolver {
    public static final String TOOL_CODE = "canvas.media.generate_skill_image";
    public static final String OUTPUT_KIND = "IMAGE";
    public static final String EXECUTION_ROUTE = "TOOL_IMAGE";
    public static final String STRICT_INPUT_SCHEMA_DIGEST =
            "339e4c18f5bf44a7c56332b3bbd273fd8b33292c3fc2dfd834230a253b137120";
    public static final String STRICT_OUTPUT_SCHEMA_DIGEST =
            "82f97995af4d88a09b78a077e2a5421138023fdd254794519a89d7e33913c48a";
    private static final String PROVENANCE_SCHEMA = "assistant-image-skill-provenance/v1";
    private static final Set<String> CAPABILITIES = Set.of("text_to_image", "image_to_image");
    private static final int MAX_RESOLVED_PROMPT = 100_000;

    private final AidRuntimeRunMapper runs;
    private final AidSkillMapper skills;
    private final SkillPackageResourceLoader packages;
    private final AssistantMediaRuntimeToolProvider mediaTools;

    /** Bulk projection for the assistant directory. Invalid or incomplete versions stay hidden. */
    public List<ImageCatalogEntry> availableCatalogEntries(Long userId) {
        return catalogEntries(userId, null);
    }

    /** Validate only the explicitly selected image Skill, never enumerate unrelated packages. */
    public ImageCatalogEntry findSelection(Long userId, String code) {
        if (userId == null || userId <= 0 || StrUtil.isBlank(code)) return null;
        AidSkill identity = skills.selectOne(new LambdaQueryWrapper<AidSkill>()
                .eq(AidSkill::getSkillCode, code).eq(AidSkill::getStatus, "0")
                .eq(AidSkill::getDelFlag, "0"));
        return findSelection(userId, identity);
    }

    public ImageCatalogEntry findSelection(Long userId, AidSkill identity) {
        if (userId == null || userId <= 0) return null;
        if (identity == null || identity.getCurrentVersionId() == null || !authorizedIdentity(identity, userId)) return null;
        String code = identity.getSkillCode();
        var authorized = packages.loadAuthorizedVersion(identity, identity.getCurrentVersionId(), userId, false);
        AidSkillVersion version = authorized.version();
        if (!catalogIdentityMatches(identity, version, userId) || !promptSchemaReady(version)
                || !SkillModelConfiguration.from(version).isValid()) return null;
        capability(version);
        return new ImageCatalogEntry(code, identity.getName(), identity.getDescription(),
                version.getId(), version.getPackageDigest());
    }

    /** Reject an incompatible explicit choice before a message allocates a run or budget. */
    public void requireComposerSelection(Long userId, com.aid.assistant.vo.AssistantViews.SkillOption selected,
                                         JSONArray choices) {
        if (selected == null || !EXECUTION_ROUTE.equals(selected.getExecutionRoute())
                || choices == null || choices.isEmpty()) return;
        for (Object raw : choices) {
            JSONObject choice = (JSONObject) raw;
            if (!"image".equals(choice.getString("modelType"))) continue;
            var version = packages.loadAuthorizedVersion(selected.getCode(),
                    Long.valueOf(selected.getVersionId()), userId, true).version();
            if (!SkillModelConfiguration.from(version).selectableModelCodes().contains(choice.getString("modelCode")))
                throw new com.aid.assistant.error.AssistantServiceException(422,
                        "ASSISTANT_SELECTED_MODEL_SKILL_MISMATCH",
                        "所选图片模型不在当前Skill的候选范围，请调整模型后发送",
                        Map.of("field", "selectedModels", "skillCode", selected.getCode(),
                                "skillVersionId", selected.getVersionId(), "modelCode", choice.getString("modelCode")));
        }
    }

    private List<ImageCatalogEntry> catalogEntries(Long userId, String codeFilter) {
        if (userId == null || userId <= 0) return List.of();
        Map<String, Set<String>> capabilityModels = capabilityModels(userId);
        if (capabilityModels.isEmpty()) return List.of();
        List<AidSkill> identities = skills.selectList(new LambdaQueryWrapper<AidSkill>()
                .eq(codeFilter != null, AidSkill::getSkillCode, codeFilter)
                .and(scope -> scope
                        .nested(platform -> platform.eq(AidSkill::getOwnerType, "PLATFORM")
                                .eq(AidSkill::getVisibility, "PUBLIC"))
                        .or(owned -> owned.eq(AidSkill::getOwnerType, "USER")
                                .eq(AidSkill::getOwnerUserId, userId)
                                .in(AidSkill::getVisibility, List.of("PRIVATE", "PUBLIC"))))
                .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                .eq(AidSkill::getExecutorType, "PROMPT")
                .eq(AidSkill::getStatus, "0").eq(AidSkill::getDelFlag, "0")
                .isNotNull(AidSkill::getCurrentVersionId).orderByAsc(AidSkill::getSkillCode));
        List<String> codes = identities.stream().map(AidSkill::getSkillCode).toList();
        Map<String, AidSkill> byCode = identities.stream().collect(Collectors.toMap(
                AidSkill::getSkillCode, Function.identity(), (left, right) -> left));
        Set<Long> versionIds = identities.stream().map(AidSkill::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AidSkillVersion> versions = packages.loadVersions(versionIds);
        Map<String, AidSkillVersion> verified = new LinkedHashMap<>();
        for (String code : codes) {
            AidSkill identity = byCode.get(code);
            AidSkillVersion version = identity == null ? null : versions.get(identity.getCurrentVersionId());
            if (!catalogIdentityMatches(identity, version, userId)
                    || !Objects.equals(identity.getCurrentVersionId(), version.getId())
                    || !promptSchemaReady(version)) continue;
            String capability;
            try {
                capability = capability(version);
            } catch (RuntimeException invalidDefinition) {
                continue;
            }
            SkillModelConfiguration configuration = SkillModelConfiguration.from(version);
            Set<String> capable = capabilityModels.getOrDefault(capability, Set.of());
            if (!configuration.isValid()
                    || configuration.selectableModelCodes().stream().noneMatch(capable::contains)) continue;
            verified.put(code, version);
        }
        if (verified.isEmpty()) return List.of();
        List<ImageCatalogEntry> result = new ArrayList<>();
        for (String code : codes) {
            AidSkill identity = byCode.get(code);
            AidSkillVersion version = verified.get(code);
            if (identity == null || version == null) continue;
            SkillModelConfiguration configuration = SkillModelConfiguration.from(version);
            Set<String> capable = capabilityModels.getOrDefault(capability(version), Set.of());
            if (configuration.selectableModelCodes().stream().noneMatch(capable::contains)) continue;
            try {
                packages.verifyVersionCached(code, version);
                result.add(new ImageCatalogEntry(code, identity.getName(), identity.getDescription(),
                        version.getId(), version.getPackageDigest()));
            } catch (RuntimeException unavailable) {
                // Directory projection is fail-closed for a corrupt or incomplete package.
            }
        }
        return List.copyOf(result);
    }

    public PinnedSelection requirePinned(Long runId, Long userId) {
        return requirePinned(runId, userId, null, true);
    }

    private PinnedSelection requirePinned(Long runId, Long userId, String requestedModel, boolean discoverCandidates) {
        AidRuntimeRun run = runId == null ? null : runs.selectById(runId);
        if (run == null || userId == null || !Objects.equals(run.getUserId(), userId)
                || !"0".equals(run.getDelFlag()) || !"ASSISTANT_LOOP".equals(run.getExecutorStrategy())) {
            throw failure("ASSISTANT_IMAGE_SKILL_RUN_INVALID");
        }
        JSONObject request = object(run.getRequestJson(), "ASSISTANT_IMAGE_SKILL_SELECTION_INVALID");
        JSONObject selected = request.getJSONObject("_selectedSkill");
        String code = selected == null ? null : selected.getString("code");
        String versionText = selected == null ? null : selected.getString("versionId");
        String digest = selected == null ? null : selected.getString("packageDigest");
        if (selected == null || !OUTPUT_KIND.equals(selected.getString("outputKind"))
                || !EXECUTION_ROUTE.equals(selected.getString("executionRoute"))
                || code == null || !code.matches("[a-z][a-z0-9_]{1,63}") || versionText == null
                || !versionText.matches("[1-9][0-9]{0,18}")
                || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw failure("ASSISTANT_IMAGE_SKILL_SELECTION_INVALID");
        }
        final Long versionId;
        try {
            versionId = Long.valueOf(versionText);
        } catch (NumberFormatException invalid) {
            throw failure("ASSISTANT_IMAGE_SKILL_SELECTION_INVALID");
        }
        SkillPackageResourceLoader.AuthorizedPackage authorized =
                packages.loadAuthorizedVersion(code, versionId, userId, true);
        AidSkill identity = authorized.skill();
        AidSkillVersion version = authorized.version();
        if (!Objects.equals(version.getPackageDigest(), digest)
                || !catalogIdentityMatches(identity, version, userId) || !promptSchemaReady(version)) {
            throw failure("ASSISTANT_IMAGE_SKILL_VERSION_CHANGED");
        }
        String capability = capability(version);
        SkillModelConfiguration configuration = SkillModelConfiguration.from(version);
        if (!configuration.isValid()) throw failure("ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE");
        String chosen = StrUtil.isBlank(requestedModel) ? configuration.defaultModelCode() : requestedModel.trim();
        if (!configuration.selectableModelCodes().contains(chosen))
            throw failure("ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE");
        List<String> candidates = discoverCandidates ? configuration.selectableModelCodes() : List.of(chosen);
        Set<String> capable = capabilityModels(userId, candidates).getOrDefault(capability, Set.of());
        List<String> activeModels = configuration.selectableModelCodes().stream()
                .filter(capable::contains).toList();
        if (!configuration.isValid() || activeModels.isEmpty()) {
            throw failure("ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE");
        }
        return new PinnedSelection(run, identity, version, capability, configuration,
                List.copyOf(activeModels));
    }

    public Resolution resolve(Long runId, Long userId, JSONObject arguments) {
        String requestedModel = arguments == null ? null : arguments.getString("modelCode");
        PinnedSelection pinned = requirePinned(runId, userId, requestedModel, false);
        JSONObject input = arguments == null ? null : arguments.getJSONObject("skillInput");
        if (input == null) throw failure("ASSISTANT_IMAGE_SKILL_INPUT_INVALID");
        validateInput(pinned.version(), input);
        String dynamicPrompt = StrUtil.trim(input.getString("prompt"));
        if (StrUtil.isBlank(dynamicPrompt) || dynamicPrompt.length() > 65_536) {
            throw failure("ASSISTANT_IMAGE_SKILL_INPUT_INVALID");
        }
        String modelCode = StrUtil.isBlank(requestedModel)
                ? pinned.modelConfiguration().defaultModelCode() : requestedModel.trim();
        if (!pinned.activeModelCodes().contains(modelCode)) {
            throw failure("ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE");
        }
        String master = packages.readAuthorizedResource(pinned.identity().getSkillCode(),
                pinned.version().getId(), "SKILL.md", userId, true);
        String systemPrompt = packages.readAuthorizedResource(pinned.identity().getSkillCode(),
                pinned.version().getId(), "system_prompt.md", userId, true);
        List<SkillPackageResourceLoader.SelectedResource> routed = packages.select(
                pinned.identity().getSkillCode(), pinned.version(), pinned.capabilityCode(), dynamicPrompt);
        StringBuilder prompt = new StringBuilder();
        append(prompt, "【Skill主说明】", master);
        append(prompt, "【系统约束】", systemPrompt);
        if (!routed.isEmpty()) {
            prompt.append("\n【按当前任务命中的参考资源】\n");
            for (SkillPackageResourceLoader.SelectedResource resource : routed) {
                prompt.append("\n[resource:").append(resource.resourceKey()).append("]\n")
                        .append(resource.content());
                if (prompt.length() > MAX_RESOLVED_PROMPT) throw failure("ASSISTANT_IMAGE_SKILL_PROMPT_TOO_LARGE");
            }
        }
        String examples = SkillContractExamples.instruction(
                pinned.version().getInputSchemaJson(), pinned.version().getOutputSchemaJson());
        if (!examples.isBlank()) {
            append(prompt, "【格式说明，仅供理解，不作为画面内容】", examples);
        }
        append(prompt, "【当前任务】", dynamicPrompt);
        if (prompt.length() > MAX_RESOLVED_PROMPT) throw failure("ASSISTANT_IMAGE_SKILL_PROMPT_TOO_LARGE");
        List<ResourceProof> resources = routed.stream().map(value ->
                new ResourceProof(value.resourceKey(), value.digest())).toList();
        String resourceDigest = SecureUtil.sha256(JSON.toJSONString(resources));
        return new Resolution(pinned.identity().getSkillCode(), pinned.version().getId(),
                pinned.version().getVersionCode(), pinned.version().getPackageDigest(),
                pinned.capabilityCode(), modelCode, dynamicPrompt, prompt.toString(),
                SecureUtil.sha256(input.toJSONString()), SecureUtil.sha256(prompt.toString()),
                SecureUtil.sha256(pinned.version().getInputSchemaJson()),
                SecureUtil.sha256(StrUtil.blankToDefault(pinned.version().getOutputSchemaJson(), "")),
                resources, resourceDigest);
    }

    public JSONObject provenance(Resolution value) {
        JSONObject result = new JSONObject();
        result.put("schemaVersion", PROVENANCE_SCHEMA);
        result.put("skillCode", value.skillCode());
        result.put("skillVersionId", String.valueOf(value.skillVersionId()));
        result.put("skillVersionCode", value.skillVersionCode());
        result.put("packageDigest", value.packageDigest());
        result.put("capabilityCode", value.capabilityCode());
        result.put("modelCode", value.modelCode());
        result.put("inputDigest", value.inputDigest());
        result.put("promptDigest", value.promptDigest());
        result.put("inputSchemaDigest", value.inputSchemaDigest());
        result.put("outputSchemaDigest", value.outputSchemaDigest());
        result.put("resources", value.resources());
        result.put("resourceDigest", value.resourceDigest());
        return result;
    }

    /** Execution rechecks the approved snapshot only; it never follows a mutable Skill pointer. */
    public void requirePrepared(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || !TOOL_CODE.equals(prepared.getToolCode())) {
            throw failure("ASSISTANT_IMAGE_SKILL_PREPARED_INVALID");
        }
        JSONObject payload = object(prepared.getPreparedPayloadJson(),
                "ASSISTANT_IMAGE_SKILL_PREPARED_INVALID");
        JSONObject proof = payload.getJSONObject("skillProvenance");
        JSONObject arguments = object(prepared.getArgumentsJson(),
                "ASSISTANT_IMAGE_SKILL_PREPARED_INVALID");
        JSONObject input = arguments.getJSONObject("skillInput");
        AidRuntimeRun run = prepared.getRunId() == null ? null : runs.selectById(prepared.getRunId());
        JSONObject request = run == null ? null : object(run.getRequestJson(),
                "ASSISTANT_IMAGE_SKILL_PREPARED_INVALID");
        JSONObject selected = request == null ? null : request.getJSONObject("_selectedSkill");
        PinnedSelection live = run == null ? null : requirePinned(prepared.getRunId(), prepared.getUserId());
        if (run == null || !Objects.equals(run.getUserId(), prepared.getUserId())
                || !"0".equals(run.getDelFlag()) || proof == null || input == null || selected == null
                || !PROVENANCE_SCHEMA.equals(proof.getString("schemaVersion"))
                || !Objects.equals(selected.getString("code"), proof.getString("skillCode"))
                || !Objects.equals(selected.getString("versionId"), proof.getString("skillVersionId"))
                || !Objects.equals(selected.getString("packageDigest"), proof.getString("packageDigest"))
                || !OUTPUT_KIND.equals(selected.getString("outputKind"))
                || !EXECUTION_ROUTE.equals(selected.getString("executionRoute"))
                || !Objects.equals(SecureUtil.sha256(input.toJSONString()), proof.getString("inputDigest"))
                || !Objects.equals(prepared.getOperationScope().getModelCode(), proof.getString("modelCode"))
                || !Objects.equals(List.of(proof.getString("capabilityCode")),
                prepared.getOperationScope().getCapabilityCodes())
                || live == null || !Objects.equals(live.identity().getSkillCode(), proof.getString("skillCode"))
                || !Objects.equals(live.version().getId(), proof.getLong("skillVersionId"))
                || !Objects.equals(live.version().getPackageDigest(), proof.getString("packageDigest"))
                || !Objects.equals(live.capabilityCode(), proof.getString("capabilityCode"))
                || !live.activeModelCodes().contains(proof.getString("modelCode"))
                || !hex(proof.getString("promptDigest")) || !hex(proof.getString("resourceDigest"))
                || !hex(proof.getString("inputSchemaDigest")) || !hex(proof.getString("outputSchemaDigest"))) {
            throw failure("ASSISTANT_IMAGE_SKILL_PREPARED_INVALID");
        }
    }

    private boolean catalogIdentityMatches(AidSkill identity, AidSkillVersion version, Long userId) {
        return identity != null && version != null && authorizedIdentity(identity, userId)
                && Objects.equals(identity.getId(), version.getSkillId())
                && "ENTRYPOINT".equals(identity.getInvocationScope()) && "PROMPT".equals(identity.getExecutorType())
                && "0".equals(identity.getStatus()) && "0".equals(identity.getDelFlag())
                && ("PRIVATE".equals(version.getVisibility()) || "PUBLIC".equals(version.getVisibility()))
                && ("USER".equals(identity.getOwnerType())
                    || "PUBLIC".equals(version.getVisibility()) && "PUBLISHED".equals(version.getPublishStatus()))
                && "ENTRYPOINT".equals(version.getInvocationScope())
                && ("PRIVATE".equals(version.getPublishStatus()) || "PUBLISHED".equals(version.getPublishStatus()))
                && "PROMPT".equals(version.getExecutorType())
                && OUTPUT_KIND.equals(SkillModelService.outputKind(version))
                && "0".equals(version.getStatus()) && "0".equals(version.getDelFlag())
                && version.getPackageDigest() != null && version.getPackageDigest().matches("[0-9a-f]{64}");
    }

    private boolean authorizedIdentity(AidSkill identity, Long userId) {
        return "PLATFORM".equals(identity.getOwnerType()) && "PUBLIC".equals(identity.getVisibility())
                || "USER".equals(identity.getOwnerType()) && userId != null && userId > 0
                && Objects.equals(identity.getOwnerUserId(), userId)
                && ("PRIVATE".equals(identity.getVisibility()) || "PUBLIC".equals(identity.getVisibility()));
    }

    private Map<String, Set<String>> capabilityModels(Long userId) {
        return capabilityModels(userId, null);
    }

    private Map<String, Set<String>> capabilityModels(Long userId, List<String> candidates) {
        if (userId == null || userId <= 0) return Map.of();
        RuntimeToolAvailabilityContext context = new RuntimeToolAvailabilityContext();
        context.setUserId(userId);
        context.setCandidateModelCodes(candidates);
        RuntimeToolBudgetOffer offer;
        try {
            var descriptor = mediaTools.descriptors().stream()
                    .filter(value -> AssistantMediaRuntimeToolProvider.IMAGE_GENERATE.equals(value.code()))
                    .findFirst().orElseThrow();
            offer = mediaTools.budgetOffer(context, descriptor);
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
        if (offer == null || !List.of("IMAGE").equals(offer.getMediaTypes())
                || offer.getModelChoices() == null) return Map.of();
        Set<String> offeredModels = new LinkedHashSet<>(offer.getModelCodes());
        Set<String> offeredCapabilities = new LinkedHashSet<>(offer.getCapabilityCodes());
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (RuntimeToolBudgetOffer.ModelChoice choice : offer.getModelChoices()) {
            if (choice == null || !offeredModels.contains(choice.modelCode())
                    || !offeredCapabilities.contains(choice.capabilityCode())
                    || !CAPABILITIES.contains(choice.capabilityCode())) continue;
            result.computeIfAbsent(choice.capabilityCode(), ignored -> new LinkedHashSet<>())
                    .add(choice.modelCode());
        }
        result.replaceAll((capability, modelCodes) -> Set.copyOf(modelCodes));
        return Map.copyOf(result);
    }

    private boolean promptSchemaReady(AidSkillVersion version) {
        try {
            if (!hasStrictSchemas(version)) return false;
            JSONObject schema = JSON.parseObject(version.getInputSchemaJson());
            JSONObject properties = schema == null ? null : schema.getJSONObject("properties");
            JSONObject prompt = properties == null ? null : properties.getJSONObject("prompt");
            JSONArray required = schema == null ? null : schema.getJSONArray("required");
            return schema != null && "object".equals(schema.getString("type"))
                    && prompt != null && "string".equals(prompt.getString("type"))
                    && required != null && required.contains("prompt");
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean hasStrictSchemas(AidSkillVersion version) {
        return version != null && SkillImageExecutionContract.supportsSchemas(
                version.getInputSchemaJson(), version.getOutputSchemaJson());
    }

    private void validateInput(AidSkillVersion version, JSONObject input) {
        if (!promptSchemaReady(version)) throw failure("ASSISTANT_IMAGE_SKILL_SCHEMA_NOT_READY");
        try {
            ValidateResult result = JSONSchema.of(JSON.parseObject(version.getInputSchemaJson())).validate(input);
            if (!result.isSuccess()) throw failure("ASSISTANT_IMAGE_SKILL_INPUT_INVALID");
        } catch (RuntimeException invalid) {
            if (invalid instanceof ServiceException service) throw service;
            throw failure("ASSISTANT_IMAGE_SKILL_SCHEMA_NOT_READY");
        }
    }

    private String capability(AidSkillVersion version) {
        try {
            JSONObject definition = JSON.parseObject(version.getDefinitionJson());
            String capability = definition == null ? null : definition.getString("capabilityCode");
            if (!CAPABILITIES.contains(capability)) throw failure("ASSISTANT_IMAGE_SKILL_CAPABILITY_INVALID");
            return capability;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ServiceException service) throw service;
            throw failure("ASSISTANT_IMAGE_SKILL_CAPABILITY_INVALID");
        }
    }

    private JSONObject object(String value, String code) {
        try {
            JSONObject result = JSON.parseObject(value);
            if (result == null) throw failure(code);
            return result;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ServiceException service) throw service;
            throw failure(code);
        }
    }

    private void append(StringBuilder target, String heading, String value) {
        if (StrUtil.isBlank(value)) return;
        target.append(target.isEmpty() ? "" : "\n").append(heading).append('\n').append(value.trim()).append('\n');
    }

    private boolean hex(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private ServiceException failure(String code) {
        return new ServiceException("图片Skill当前不可执行").setDetailMessage(code);
    }

    public record ImageCatalogEntry(String code, String name, String description,
                                    Long versionId, String packageDigest) { }

    public record PinnedSelection(AidRuntimeRun run, AidSkill identity, AidSkillVersion version,
                                  String capabilityCode, SkillModelConfiguration modelConfiguration,
                                  List<String> activeModelCodes) { }

    public record ResourceProof(String resourceKey, String digest) { }

    public record Resolution(String skillCode, Long skillVersionId, String skillVersionCode,
                             String packageDigest, String capabilityCode, String modelCode,
                             String dynamicPrompt, String resolvedPrompt, String inputDigest,
                             String promptDigest, String inputSchemaDigest, String outputSchemaDigest,
                             List<ResourceProof> resources, String resourceDigest) { }
}
