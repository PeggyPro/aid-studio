package com.aid.runtime.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.aid.oss.util.MediaPayloadUrlNormalizer;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.canvas.service.CanvasRuntimeReservationService;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.service.GenerationInvocationContext;
import com.aid.generation.service.GenerationResourceResolver;
import com.aid.generation.service.IGenericGenerationFacade;
import com.aid.generation.vo.GenerationResponse;
import com.aid.generation.vo.GenerationTaskView;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.service.TaskCompletionService;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeAgentDraft;
import com.aid.runtime.domain.AidRuntimeTestGrant;
import com.aid.runtime.dto.GenericRuntimeRequests;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.legacy.service.LegacyRuntimeAgentAdminService;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeAgentDraftMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillDraft;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillDraftMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillRuntimeCapabilities;
import com.aid.skill.service.SkillModelConfiguration;
import com.aid.skill.service.ISkillPackageAdminService;
import com.aid.skill.service.ISkillInvocationService;
import com.aid.skill.service.SkillContractExamples;
import com.aid.skill.service.SkillMethodInputContract;
import com.aid.skill.service.SkillMethodOutputContract;
import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded Agent/Skill execution using the existing generation, queue and billing facade. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenericRuntimeService {
    private static final String NORMAL = "0";
    private static final String LEGACY_AGENT_FAMILY = LegacyRuntimeAgentRequests.LEGACY_PLAN;
    private static final String RUNTIME_BIZ_TASK_TYPE = "RUNTIME_RUN";
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final AidSkillMapper skills;
    private final AidRuntimeAgentMapper agents;
    private final AidRuntimeAgentVersionMapper agentVersions;
    private final AidRuntimeAgentDraftMapper agentDrafts;
    private final AidSkillDraftMapper skillDrafts;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final AidMediaTaskMapper mediaTasks;
    private final AidRuntimeAgentSkillRefMapper skillRefs;
    private final SkillModelService modelService;
    private final SkillPackageResourceLoader resourceLoader;
    private final ISkillPackageAdminService skillPackages;
    private final ISkillInvocationService legacySkillRuntime;
    private final LegacyRuntimeAgentAdminService agentAdmin;
    private final RuntimeTestGrantService testGrants;
    private final RuntimeToolRegistry tools;
    private final IGenericGenerationFacade generation;
    private final TaskCompletionService taskCompletion;
    private final TransactionTemplate transactions;
    private final RedissonClient redisson;
    private final CanvasRuntimeReservationService reservations;
    private final CanvasAccessService canvasAccess;
    private final GenerationResourceResolver resourceResolver;
    private final MediaPayloadUrlNormalizer mediaPayloadUrlNormalizer;
    private final com.aid.billing.service.IAccountUpdateService retryAccounts;

    public JSONObject invoke(GenericRuntimeRequests.Invoke request, Long userId, String actor) {
        JSONObject delegated = invokeDelegatedIfConfigured(request, userId, actor);
        if (delegated != null) return delegated;
        return invokePrepared(request, userId, actor, null, null, null,
                request.getClientRequestId());
    }

    private JSONObject invokeDelegatedIfConfigured(GenericRuntimeRequests.Invoke request,
                                                    Long userId, String actor) {
        if (!"AGENT".equals(request.getMemberType())) return null;
        AidRuntimeAgent agent = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .eq(AidRuntimeAgent::getAgentCode, request.getMemberCode())
                .eq(AidRuntimeAgent::getAgentFamily, LEGACY_AGENT_FAMILY)
                .eq(AidRuntimeAgent::getDelFlag, NORMAL).last("limit 1"));
        if (agent == null || !NORMAL.equals(agent.getStatus())
                || !("PUBLIC".equals(agent.getVisibility())
                || Objects.equals(agent.getOwnerUserId(), userId)))
            throw new ServiceException("Agent 不可用");
        AidRuntimeAgentVersion version = agentVersions.selectById(agent.getCurrentVersionId());
        if (version == null || !NORMAL.equals(version.getStatus())
                || !NORMAL.equals(version.getDelFlag())) throw new ServiceException("Agent 版本不可用");
        requireLegacyAgentVersion(agent, version);
        LegacyRuntimeAgentRequests.DraftPayload definition = legacyDefinition(version);
        if (!"SKILL_ORCHESTRATOR".equals(definition.getExecutorType())) return null;
        if (request.getProjectId() == null || request.getCanvasNode() != null)
            throw new ServiceException("委托编排需提供项目 ID，当前不接受画布节点占用");
        AidRuntimeAgentSkillRef pinned = skillRefs.selectOne(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                .eq(AidRuntimeAgentSkillRef::getAgentVersionId, version.getId())
                .eq(AidRuntimeAgentSkillRef::getSkillCode, definition.getDelegatedSkillCode())
                .last("limit 1"));
        if (pinned == null) throw new ServiceException("Agent 固定 Skill 依赖缺失", 409);
        AidSkillVersion pinnedVersion = resourceLoader.loadVersion(pinned.getSkillVersionId());
        if (pinnedVersion == null || !NORMAL.equals(pinnedVersion.getStatus())
                || !NORMAL.equals(pinnedVersion.getDelFlag())
                || !Objects.equals(pinnedVersion.getSkillId(), pinned.getSkillId())
                || !Objects.equals(pinnedVersion.getPackageDigest(), pinned.getPackageDigest()))
            throw new ServiceException("Agent pinned Skill digest mismatch", 409);
        String selectedModel = StrUtil.blankToDefault(request.getModelCode(), definition.getDefaultModelCode());
        if (!definition.getSelectableModelCodes().contains(selectedModel)
                || !modelService.activeModels(List.of(selectedModel), "TEXT").containsKey(selectedModel))
            throw new ServiceException("Agent 模型不可用或不在可选范围");
        SkillInvocationRequests.InvokeRequest delegated = new SkillInvocationRequests.InvokeRequest();
        delegated.setSkillCode(definition.getDelegatedSkillCode());
        delegated.setModelCode(selectedModel);
        delegated.setIdempotencyKey("agent-" + SecureUtil.sha256(agent.getId() + ":"
                + request.getClientRequestId()).substring(0, 48));
        delegated.setProjectId(request.getProjectId());
        delegated.setEpisodeId(request.getEpisodeId());
        delegated.setPrompt(request.getPrompt());
        Map<String, Object> options = request.getParameters();
        delegated.setOperation(option(options, "operation", "AUTO"));
        delegated.setQualityMode(option(options, "qualityMode", "AUTO"));
        delegated.setStyle(option(options, "style", null));
        delegated.setGenre(option(options, "genre", null));
        delegated.setLanguage(option(options, "language", null));
        delegated.setTargetDurationSeconds(integerOption(options, "targetDurationSeconds"));
        delegated.setParentRunId(longOption(options, "parentRunId"));
        Object references = options == null ? null : options.get("skillReferences");
        if (references != null) {
            JSONArray raw = JSON.parseArray(JSON.toJSONString(references));
            if (raw.size() > 20) throw new ServiceException("剧本参考内容超过 20 项");
            delegated.setReferences(raw.toJavaList(SkillInvocationRequests.ReferenceItem.class));
        }
        ISkillInvocationService.LegacyFixedVersionResult result = legacySkillRuntime.invokeLegacyFixedVersion(delegated,
                pinned.getSkillVersionId(), pinned.getPackageDigest(),
                ISkillInvocationService.LegacyFixedVersionAuthority.publishedAgent(
                        agent.getId(), version.getId()), userId, actor);
        return delegatedResponse(result, request.getClientRequestId(), agent.getAgentCode(),
                agent.getId(), version.getId(), null);
    }

    private static String option(Map<String, Object> options, String name, String fallback) {
        Object value = options == null ? null : options.get(name);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new ServiceException("编排参数 " + name + " 类型错误");
        return text;
    }

    private static Integer integerOption(Map<String, Object> options, String name) {
        Object value = options == null ? null : options.get(name);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new ServiceException("编排参数 " + name + " 类型错误");
        return number.intValue();
    }

    private static Long longOption(Map<String, Object> options, String name) {
        Object value = options == null ? null : options.get(name);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new ServiceException("编排参数 " + name + " 类型错误");
        return number.longValue();
    }

    public JSONObject tryDraft(GenericRuntimeRequests.DraftTryRun input, String memberType,
                               Long adminUserId, String actor) {
        AidRuntimeTestGrant grant = testGrants.peek(input.getGrantId());
        if (!Objects.equals(grant.getAdminUserId(), adminUserId)
                || !Objects.equals(grant.getMemberType(), memberType)
                || !Objects.equals(grant.getDraftDigest(), input.getDraftDigest()))
            throw new ServiceException("草稿试运行授权不匹配", 403);
        Member member;
        String code;
        if ("SKILL".equals(memberType)) {
            AidSkillDraft draft = skillDrafts.selectById(input.getDraftId());
            if (draft == null || !"EDITING".equals(draft.getStatus())
                    || !Objects.equals(draft.getOwnerUserId(), adminUserId)
                    || !Objects.equals(draft.getDraftDigest(), input.getDraftDigest()))
                throw new ServiceException("Skill 草稿已变化", 409);
            AidSkill identity = skills.selectById(draft.getSkillId());
            if (identity == null || !NORMAL.equals(identity.getDelFlag()))
                throw new ServiceException("Skill 不存在");
            SkillPackageAdminRequests.PackagePayload body = JSON.parseObject(draft.getDraftJson(),
                    SkillPackageAdminRequests.PackagePayload.class);
            if (!Boolean.TRUE.equals(skillPackages.validateDraft(body).getValid()))
                throw new ServiceException("Skill 草稿校验失败", 409);
            AidSkillVersion preview = new AidSkillVersion();
            preview.setId(draft.getId());
            preview.setSkillId(identity.getId());
            preview.setModelCode(StrUtil.blankToDefault(body.getDefaultModelCode(), body.getModelCode()));
            preview.setModelConfigJson(SkillModelConfiguration.normalized(preview.getModelCode(),
                    body.getSelectableModelCodes(), true).toJson());
            preview.setDefinitionJson(body.getDefinitionJson());
            preview.setSystemPrompt(body.getSystemPrompt());
            preview.setInputSchemaJson(body.getInputSchemaJson());
            preview.setOutputSchemaJson(body.getOutputSchemaJson());
            preview.setPackageDigest(draft.getDraftDigest());
            StringBuilder instruction = new StringBuilder(body.getSystemPrompt());
            int selected = 0;
            for (SkillPackageAdminRequests.ResourceItem resource : body.getResources()) {
                if (selected++ >= 4) break;
                if (instruction.length() + resource.getContent().length() > 100000)
                    throw new ServiceException("试运行参考资源超出上下文上限");
                instruction.append("\n\n参考资源 ").append(resource.getResourceKey()).append(":\n")
                        .append(resource.getContent());
            }
            member = new Member("SKILL", identity.getId(), draft.getId(), draft.getDraftDigest(),
                    instruction.toString(), SkillModelService.outputKind(preview), false,
                    1, 1, 600, 128000, List.of(defaultStep()), Map.of(), preview);
            code = identity.getSkillCode();
        } else if ("AGENT".equals(memberType)) {
            AidRuntimeAgentDraft draft = agentDrafts.selectById(input.getDraftId());
            if (draft == null || !"EDITING".equals(draft.getStatus())
                    || !Objects.equals(draft.getOwnerUserId(), adminUserId)
                    || !Objects.equals(draft.getDraftDigest(), input.getDraftDigest()))
                throw new ServiceException("Agent 草稿已变化", 409);
            AidRuntimeAgent identity = agents.selectById(draft.getAgentId());
            if (identity == null || !NORMAL.equals(identity.getDelFlag())
                    || !LEGACY_AGENT_FAMILY.equals(identity.getAgentFamily()))
                throw new ServiceException("Agent 不存在");
            LegacyRuntimeAgentRequests.DraftPayload body = JSON.parseObject(draft.getDraftJson(),
                    LegacyRuntimeAgentRequests.DraftPayload.class);
            if (!Boolean.TRUE.equals(agentAdmin.validate(body).getBoolean("valid")))
                throw new ServiceException("Agent 草稿校验失败", 409);
            if ("SKILL_ORCHESTRATOR".equals(body.getExecutorType()))
                return tryDelegatedDraft(input, grant, identity, draft, body, actor);
            AidRuntimeAgentVersion preview = new AidRuntimeAgentVersion();
            preview.setId(draft.getId());
            preview.setAgentId(identity.getId());
            preview.setInstruction(body.getInstruction());
            preview.setDefinitionJson(JSON.toJSONString(body));
            preview.setPackageDigest(draft.getDraftDigest());
            Map<String, Long> pinned = new LinkedHashMap<>();
            if (!body.getAllowedSkillCodes().isEmpty()) {
                List<AidSkill> children = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .in(AidSkill::getSkillCode, body.getAllowedSkillCodes())
                        .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL));
                for (AidSkill child : children) {
                    if (child.getCurrentVersionId() != null
                            && ("PUBLIC".equals(child.getVisibility())
                            || Objects.equals(child.getOwnerUserId(), adminUserId)))
                        pinned.put(child.getSkillCode(), child.getCurrentVersionId());
                }
                if (pinned.size() != new LinkedHashSet<>(body.getAllowedSkillCodes()).size())
                    throw new ServiceException("Agent 依赖的 Skill 不可用", 409);
            }
            member = new Member("AGENT", identity.getId(), draft.getId(), draft.getDraftDigest(),
                    body.getInstruction(), body.getOutputKind(), Boolean.TRUE.equals(body.getClarificationEnabled()),
                    body.getMaxSteps(), body.getMaxTaskCount(), body.getMaxDurationSeconds(),
                    body.getMaxEstimatedTokens(), body.getSteps().isEmpty() ? List.of(defaultStep()) : body.getSteps(),
                    pinned, preview);
            code = identity.getAgentCode();
        } else throw new ServiceException("成员类型错误");
        if (!Objects.equals(grant.getMemberId(), member.id()))
            throw new ServiceException("授权目标不匹配", 403);
        JSONObject flat = JSON.parseObject(JSON.toJSONString(input));
        flat.put("memberType", memberType);
        flat.put("memberCode", code);
        flat.put("clientRequestId", "trial-" + SecureUtil.sha256(input.getGrantId() + ":"
                + input.getClientRequestId()).substring(0, 48));
        return invokePrepared(flat.toJavaObject(GenericRuntimeRequests.Invoke.class),
                grant.getUserId(), actor, member, grant.getId(), adminUserId,
                input.getClientRequestId());
    }

    private JSONObject tryDelegatedDraft(GenericRuntimeRequests.DraftTryRun input,
                                         AidRuntimeTestGrant grant, AidRuntimeAgent identity,
                                         AidRuntimeAgentDraft draft,
                                         LegacyRuntimeAgentRequests.DraftPayload body, String actor) {
        if (!Objects.equals(grant.getMemberId(), identity.getId()))
            throw new ServiceException("试运行授权目标不匹配", 403);
        if (input.getProjectId() == null || input.getCanvasNode() != null)
            throw new ServiceException("委托编排试运行须指定项目，暂不接受画布节点占用");
        canvasAccess.requireRole(input.getProjectId(), grant.getUserId(), "OWNER", "EDITOR");
        AidSkill skill = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getSkillCode, body.getDelegatedSkillCode())
                .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL).last("limit 1"));
        AidSkillVersion selected = skill == null || skill.getCurrentVersionId() == null ? null
                : resourceLoader.loadVersion(skill.getCurrentVersionId());
        if (selected == null || !"ORCHESTRATOR".equals(selected.getExecutorType())
                || !NORMAL.equals(selected.getStatus()) || !NORMAL.equals(selected.getDelFlag()))
            throw new ServiceException("委托 Skill 固定版本不可用", 409);
        if (!("PUBLIC".equals(skill.getVisibility())
                || Objects.equals(skill.getOwnerUserId(), grant.getAdminUserId())))
            throw new ServiceException("Agent 无权委托此 Skill", 403);
        String chosenModel = StrUtil.blankToDefault(input.getModelCode(), body.getDefaultModelCode());
        if (!body.getSelectableModelCodes().contains(chosenModel)
                || !modelService.activeModels(List.of(chosenModel), "TEXT").containsKey(chosenModel))
            throw new ServiceException("委托模型不在 Agent 可选范围", 409);
        modelService.resolve(selected, chosenModel);
        SkillInvocationRequests.InvokeRequest delegated = new SkillInvocationRequests.InvokeRequest();
        delegated.setSkillCode(skill.getSkillCode());
        delegated.setModelCode(chosenModel);
        delegated.setIdempotencyKey("agent-trial-" + SecureUtil.sha256(identity.getId() + ":"
                + grant.getId() + ":" + input.getClientRequestId()).substring(0, 43));
        delegated.setProjectId(input.getProjectId());
        delegated.setEpisodeId(input.getEpisodeId());
        delegated.setPrompt(input.getPrompt());
        Map<String, Object> options = input.getParameters();
        delegated.setOperation(option(options, "operation", "AUTO"));
        delegated.setQualityMode(option(options, "qualityMode", "AUTO"));
        delegated.setStyle(option(options, "style", null));
        delegated.setGenre(option(options, "genre", null));
        delegated.setLanguage(option(options, "language", null));
        delegated.setTargetDurationSeconds(integerOption(options, "targetDurationSeconds"));
        delegated.setParentRunId(longOption(options, "parentRunId"));
        Object references = options == null ? null : options.get("skillReferences");
        if (references != null) {
            JSONArray raw = JSON.parseArray(JSON.toJSONString(references));
            if (raw.size() > 20) throw new ServiceException("剧本参考内容超过 20 项");
            delegated.setReferences(raw.toJavaList(SkillInvocationRequests.ReferenceItem.class));
        }
        ISkillInvocationService.LegacyFixedVersionResult result = legacySkillRuntime.invokeLegacyFixedVersion(delegated,
                selected.getId(), selected.getPackageDigest(),
                ISkillInvocationService.LegacyFixedVersionAuthority.draftTrial(
                        identity.getId(), draft.getId(), draft.getDraftDigest(), grant.getId(),
                        grant.getAdminUserId(), input.getClientRequestId()), grant.getUserId(), actor);
        return delegatedResponse(result, input.getClientRequestId(), identity.getAgentCode(),
                identity.getId(), draft.getId(), grant.getId());
    }

    private JSONObject delegatedResponse(ISkillInvocationService.LegacyFixedVersionResult result,
                                         String requestClientRequestId, String memberCode,
                                         Long memberId, Long versionId, Long trialGrantId) {
        JSONObject response = result.view() == null ? new JSONObject()
                : JSON.parseObject(JSON.toJSONString(result.view()));
        response.put("runType", "SKILL_ORCHESTRATOR");
        response.put("accepted", true);
        response.put("recoveryRequired", result.recoveryRequired());
        response.put("legacyRunId", JsonSafeId.value(result.runId()));
        response.put("runId", JsonSafeId.value(result.runId()));
        response.put("requestClientRequestId", requestClientRequestId);
        response.put("memberType", "AGENT");
        response.put("memberCode", memberCode);
        response.put("memberId", JsonSafeId.value(memberId));
        response.put("versionId", JsonSafeId.value(versionId));
        if (trialGrantId != null)
            response.put("trialGrantId", JsonSafeId.value(trialGrantId));
        return response;
    }

    private JSONObject invokePrepared(GenericRuntimeRequests.Invoke request, Long userId, String actor,
                                      Member prepared, Long grantId, Long adminUserId,
                                      String requestClientRequestId) {
        JSONObject requestBody = JSON.parseObject(JSON.toJSONString(request));
        if (request.getInput() != null)
            requestBody.put("input", JSON.parseObject(JSON.toJSONString(request.getInput(),
                    com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue)));
        if (requestBody.getJSONObject("canvasNode") != null) {
            requestBody.getJSONObject("canvasNode").remove("lockToken");
            requestBody.getJSONObject("canvasNode").remove("editSessionId");
        }
        String inputJson;
        try { inputJson = CANONICAL_JSON.writeValueAsString(requestBody); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new ServiceException("运行输入格式无效");
        }
        if (inputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024)
            throw new ServiceException("运行输入超过 256KB 上限");
        String inputDigest = SecureUtil.sha256(inputJson + "|" + userId);
        Long stableId = prepared == null ? stableMemberId(request.getMemberType(), request.getMemberCode())
                : prepared.id();
        AidRuntimeRun prior = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getUserId, userId)
                .eq(AidRuntimeRun::getMemberType, request.getMemberType())
                .eq(AidRuntimeRun::getMemberId, stableId)
                .eq(AidRuntimeRun::getClientRequestId, request.getClientRequestId()).last("limit 1"));
        if (prior != null) {
            if (!Objects.equals(prior.getTrialGrantId(), grantId))
                throw new ServiceException("请求标识已用于其他运行", 409);
            if (!Objects.equals(prior.getRequestDigest(), inputDigest))
                throw new ServiceException("请求标识已用于不同内容", 409);
            return acceptedGenericRun(prior.getId(), userId, requestClientRequestId);
        }
        Member member = prepared == null ? resolve(request.getMemberType(), request.getMemberCode(), userId)
                : prepared;
        if (request.getWorkspaceId() == null && request.getCanvasNode() != null)
            throw new ServiceException("画布节点调用必须提供工作区");
        if (request.getWorkspaceId() != null) {
            var workspace = canvasAccess.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
            if (request.getProjectId() != null
                    && !Objects.equals(request.getProjectId(), workspace.getProjectId()))
                throw new ServiceException("工作区与项目不一致");
        } else if (request.getProjectId() != null) {
            canvasAccess.requireRole(request.getProjectId(), userId, "OWNER", "EDITOR");
        }
        resourceResolver.resolveProjectScope(request.getWorkspaceId(), request.getProjectId(),
                request.getEpisodeId(), userId);
        resourceResolver.resolve(request.getWorkspaceId(), request.getReferences(), userId);
        if (StrUtil.isBlank(request.getPrompt()) && !member.clarificationEnabled()
                && StrUtil.isBlank(member.instruction()))
            throw new ServiceException("提示词不能为空");
        if (!"TEXT".equals(member.outputKind()) && StrUtil.isBlank(request.getCapabilityCode()))
            throw new ServiceException("请选择模型目录返回的能力编码");
        String chosenModel = chooseModel(member, request.getModelCode());
        JSONObject frozen = new JSONObject();
        frozen.put("request", requestBody);
        frozen.put("instruction", member.instruction());
        frozen.put("steps", JSON.toJSON(member.steps()));
        frozen.put("childVersions", JSON.toJSON(member.childVersions()));
        frozen.put("maxSteps", member.maxSteps());
        frozen.put("maxTaskCount", member.maxTaskCount());
        frozen.put("maxDurationSeconds", member.maxDurationSeconds());
        frozen.put("maxEstimatedTokens", member.maxEstimatedTokens());
        frozen.put("outputKind", member.outputKind());
        if (member.version() instanceof AidSkillVersion version && "TEXT".equals(member.outputKind())) {
            JSONObject contract = textSkillContract(version);
            JSONObject businessInput = skillInput(contract, request, request.getPrompt());
            frozen.put("skillTextContract", contract);
            frozen.put("skillBusinessInput", businessInput.toJSONString(
                    com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        }
        AidRuntimeRun run = transactions.execute(status -> {
            AidRuntimeTestGrant grant = grantId == null ? null : testGrants.require(grantId,
                    adminUserId, member.type(), member.id(), member.digest(), request.getClientRequestId());
            AidRuntimeRun existing = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getUserId, userId)
                    .eq(AidRuntimeRun::getMemberType, member.type())
                    .eq(AidRuntimeRun::getMemberId, member.id())
                    .eq(AidRuntimeRun::getClientRequestId, request.getClientRequestId()).last("limit 1 FOR UPDATE"));
            if (existing != null) {
                if (!Objects.equals(existing.getTrialGrantId(), grantId))
                    throw new ServiceException("请求标识已用于其他运行", 409);
                if (!Objects.equals(existing.getRequestDigest(), inputDigest))
                    throw new ServiceException("请求标识已用于不同内容", 409);
                return existing;
            }
            AidRuntimeRun created = new AidRuntimeRun();
            created.setUserId(userId);
            created.setMemberType(member.type());
            created.setMemberId(member.id());
            created.setVersionId(member.versionId());
            created.setVersionDigest(member.digest());
            created.setModelCode(chosenModel);
            created.setOutputKind(member.outputKind());
            created.setProjectId(request.getProjectId());
            created.setWorkspaceId(request.getWorkspaceId());
            created.setNodeObjectId(request.getCanvasNode() == null ? null : request.getCanvasNode().getObjectId());
            created.setNodeVersion(request.getCanvasNode() == null ? null : request.getCanvasNode().getExpectedVersion());
            created.setClientRequestId(request.getClientRequestId());
            created.setRequestDigest(inputDigest);
            created.setRequestJson(frozen.toJSONString());
            created.setTrialGrantId(grantId);
            created.setEstimatedTokensUsed(0);
            created.setStatus(StrUtil.isBlank(request.getPrompt()) && member.clarificationEnabled()
                    ? "WAITING_INPUT" : "CREATED");
            created.setDelFlag(NORMAL);
            created.setCreateBy(actor);
            created.setCreateTime(new Date());
            created.setUpdateBy(actor);
            created.setUpdateTime(new Date());
            try { runs.insert(created); }
            catch (DuplicateKeyException error) {
                AidRuntimeRun winner = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                        .eq(AidRuntimeRun::getUserId, userId)
                        .eq(AidRuntimeRun::getMemberType, member.type())
                        .eq(AidRuntimeRun::getMemberId, member.id())
                        .eq(AidRuntimeRun::getClientRequestId, request.getClientRequestId()).last("limit 1 LOCK IN SHARE MODE"));
                if (winner == null) {
                    if (request.getCanvasNode() != null) {
                        AidRuntimeRun occupant = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                                .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId)
                                .eq(AidRuntimeRun::getWorkspaceId, request.getWorkspaceId())
                                .eq(AidRuntimeRun::getNodeObjectId, request.getCanvasNode().getObjectId())
                                .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                                .eq(AidRuntimeRun::getDelFlag, NORMAL).last("limit 1 LOCK IN SHARE MODE"));
                        if (occupant != null) {
                            ApiErrorDetails detail = new ApiErrorDetails();
                            detail.setErrorCode("RUNTIME_NODE_OCCUPIED");
                            detail.setField("canvasNode.objectId");
                            detail.setReason("节点已有未结束的 Agent/Skill 运行");
                            detail.setRetryable(true);
                            if (Objects.equals(occupant.getUserId(), userId))
                                detail.setSuppliedValue(String.valueOf(occupant.getId()));
                            throw new ServiceException("节点运行中，请恢复现有运行", 409)
                                    .setClientErrorData(detail);
                        }
                    }
                    throw new ServiceException("运行创建发生并发冲突，请重试", 409);
                }
                if (!Objects.equals(winner.getRequestDigest(), inputDigest))
                    throw new ServiceException("请求标识已用于不同内容", 409);
                return winner;
            }
            if (request.getCanvasNode() != null) {
                CanvasRuntimeReservationService.Reservation reservation = reservations.reserve(
                        created.getId(), request.getWorkspaceId(), request.getCanvasNode().getObjectId(),
                        request.getCanvasNode().getExpectedVersion(),
                        request.getCanvasNode().getEditSessionId(),
                        request.getCanvasNode().getLockToken(), userId);
                if (request.getProjectId() != null
                        && !Objects.equals(request.getProjectId(), reservation.projectId()))
                    throw new ServiceException("工作区与项目不一致");
                created.setProjectId(reservation.projectId());
                runs.updateById(created);
            }
            if (grant != null) testGrants.consume(grant, request.getClientRequestId(), created.getId());
            return created;
        });
        return acceptedGenericRun(run.getId(), userId, requestClientRequestId);
    }

    private JSONObject acceptedGenericRun(Long runId, Long userId, String requestClientRequestId) {
        AidRuntimeRun accepted = requireRun(runId, userId);
        requireContentAccess(accepted, userId, false);
        try {
            if (!"WAITING_INPUT".equals(accepted.getStatus()) && !TERMINAL.contains(accepted.getStatus()))
                advance(accepted.getId());
            JSONObject view = runView(accepted.getId(), userId, false);
            view.put("runType", "GENERIC_RUNTIME");
            view.put("accepted", true);
            view.put("recoveryRequired", false);
            view.put("requestClientRequestId", requestClientRequestId);
            return view;
        } catch (RuntimeException error) {
            AidRuntimeRun current = requireRun(accepted.getId(), userId);
            requireContentAccess(current, userId, false);
            log.warn("Generic Runtime accepted; complete projection requires replay, runId={}, errorType={}",
                    current.getId(), error.getClass().getSimpleName());
            JSONObject receipt = new JSONObject();
            receipt.put("runType", "GENERIC_RUNTIME");
            receipt.put("accepted", true);
            receipt.put("recoveryRequired", true);
            receipt.put("id", JsonSafeId.value(current.getId()));
            receipt.put("clientRequestId", current.getClientRequestId());
            receipt.put("requestClientRequestId", requestClientRequestId);
            return receipt;
        }
    }

    public JSONObject clarify(GenericRuntimeRequests.Clarify request, Long userId, String actor) {
        AidRuntimeRun run = requireRun(request.getRunId(), userId);
        requireContentAccess(run, userId, true);
        String digest = SecureUtil.sha256(request.getAnswer());
        transactions.executeWithoutResult(status -> {
            AidRuntimeRun locked = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getId, run.getId()).last("FOR UPDATE"));
            if (locked.getClarificationKey() != null) {
                if (!Objects.equals(locked.getClarificationKey(), request.getResponseKey())
                        || !Objects.equals(locked.getClarificationDigest(), digest))
                    throw new ServiceException("澄清回答已变化");
                return;
            }
            if (!"WAITING_INPUT".equals(locked.getStatus())) throw new ServiceException("运行不等待澄清");
            JSONObject snapshot = JSON.parseObject(locked.getRequestJson());
            JSONObject input = snapshot.getJSONObject("request");
            input.put("prompt", request.getAnswer());
            locked.setRequestJson(snapshot.toJSONString());
            locked.setClarificationKey(request.getResponseKey());
            locked.setClarificationDigest(digest);
            locked.setStatus("CREATED");
            locked.setUpdateBy(actor);
            locked.setUpdateTime(new Date());
            runs.updateById(locked);
        });
        advance(run.getId());
        return runView(run.getId(), userId, false);
    }

    public JSONObject run(Long runId, Long userId) {
        AidRuntimeRun owned = requireRun(runId, userId);
        requireContentAccess(owned, userId, false);
        advance(runId);
        return runView(runId, userId, false);
    }

    public JSONObject cancel(Long runId, Long userId, String actor) {
        AidRuntimeRun owned = requireRun(runId, userId);
        requireContentAccess(owned, userId, true);
        RLock lock = redisson.getLock("runtime:run:" + runId);
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS))
                throw new ServiceException("运行正在推进，请稍后重试", 409);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException("取消已中断");
        }
        try {
            CancelDecision decision = transactions.execute(status -> cancelLocked(runId, userId, actor));
            if (decision != null && decision.blockedMessage() != null)
                throw new ServiceException(decision.blockedMessage(), 409);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
        return runView(runId, userId, false);
    }

    private CancelDecision cancelLocked(Long runId, Long userId, String actor) {
        SubmissionEvidence evidence = lockSubmissionEvidence(runId);
        AidRuntimeRun current = evidence.run();
        if (TERMINAL.contains(current.getStatus())) return CancelDecision.completed();
        AidRuntimeRunStep step = evidence.step();
        if (step != null && TERMINAL.contains(step.getStatus()))
            return CancelDecision.blocked("当前步骤终态正在收口，请先刷新运行状态");

        AidMediaTask task = evidence.task();
        if (task != null) {
            String canceled = taskCompletion.cancelQueuedRuntimeTask(task.getId(), userId, runId);
            if (!"CANCELLED".equals(canceled) && !"ALREADY_CANCELLED".equals(canceled))
                return CancelDecision.blocked("媒体任务已提交，须等待原任务权威终态");
        } else if (step != null && submissionStateUncertain(step)) {
            return CancelDecision.blocked("媒体任务提交结果待权威对账，暂不能取消");
        }

        Date now = new Date();
        if (step != null) {
            step.setStatus("CANCELED");
            step.setErrorMessage("用户取消运行");
            step.setUpdateTime(now);
            steps.updateById(step);
        }
        current.setMediaTaskId(task == null ? null : task.getId());
        current.setStatus("CANCELED");
        current.setErrorMessage("用户取消运行");
        current.setUpdateBy(actor);
        current.setUpdateTime(now);
        updateRunWithMediaProjection(current);
        if (current.getNodeObjectId() != null) reservations.release(runId);
        return CancelDecision.completed();
    }

    public JSONObject adminRun(Long runId, String expectedMemberType) {
        AidRuntimeRun run = runs.selectById(runId);
        if (run == null) throw new ServiceException("运行不存在");
        requireGenericRuntimeRun(run);
        if (expectedMemberType != null && !Objects.equals(expectedMemberType, run.getMemberType()))
            throw new ServiceException("RUNTIME_MEMBER_TYPE_MISMATCH", 409);
        advance(runId);
        return runView(runId, run.getUserId(), true);
    }

    public JSONObject adminRun(Long runId) {
        return adminRun(runId, null);
    }

    public Page<AidRuntimeRun> adminSkillRuns(GenericRuntimeRequests.SkillRunPage request) {
        GenericRuntimeRequests.SkillRunPage source = request == null
                ? new GenericRuntimeRequests.SkillRunPage() : request;
        return runs.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidRuntimeRun>lambdaQuery()
                        .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId,
                                AidRuntimeRun::getMemberId, AidRuntimeRun::getVersionId,
                                AidRuntimeRun::getTrialGrantId, AidRuntimeRun::getModelCode,
                                AidRuntimeRun::getOutputKind, AidRuntimeRun::getMediaTaskId,
                                AidRuntimeRun::getStatus, AidRuntimeRun::getErrorMessage,
                                AidRuntimeRun::getCreateTime)
                        .eq(AidRuntimeRun::getMemberType, "SKILL")
                        .isNull(AidRuntimeRun::getSessionId)
                        .isNotNull(AidRuntimeRun::getMemberId)
                        .isNotNull(AidRuntimeRun::getVersionId)
                        .eq(source.getSkillId() != null, AidRuntimeRun::getMemberId, source.getSkillId())
                        .eq(source.getUserId() != null, AidRuntimeRun::getUserId, source.getUserId())
                        .eq(source.getStatus() != null, AidRuntimeRun::getStatus, source.getStatus())
                        .eq(AidRuntimeRun::getDelFlag, NORMAL).orderByDesc(AidRuntimeRun::getId));
    }

    public Page<AidRuntimeRun> history(GenericRuntimeRequests.History request, Long userId) {
        GenericRuntimeRequests.History source = request == null ? new GenericRuntimeRequests.History() : request;
        if (source.getWorkspaceId() != null)
            canvasAccess.requireWorkspace(source.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        if (StrUtil.isNotBlank(source.getNodeObjectId()) && source.getWorkspaceId() == null)
            throw new ServiceException("按节点查询运行历史须指定工作区");
        Long memberId = null;
        if (StrUtil.isNotBlank(source.getMemberCode())) {
            if (StrUtil.isBlank(source.getMemberType())) throw new ServiceException("成员类型必填");
            memberId = resolve(source.getMemberType(), source.getMemberCode(), userId).id();
        }
        return runs.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidRuntimeRun>lambdaQuery()
                        .select(AidRuntimeRun::getId, AidRuntimeRun::getMemberType,
                                AidRuntimeRun::getMemberId, AidRuntimeRun::getVersionId,
                                AidRuntimeRun::getModelCode, AidRuntimeRun::getOutputKind,
                                AidRuntimeRun::getWorkspaceId, AidRuntimeRun::getNodeObjectId,
                                AidRuntimeRun::getMediaTaskId, AidRuntimeRun::getStatus,
                                AidRuntimeRun::getCreateTime, AidRuntimeRun::getUpdateTime)
                        .eq(AidRuntimeRun::getUserId, userId)
                        .isNull(AidRuntimeRun::getSessionId)
                        .in(AidRuntimeRun::getMemberType, "SKILL", "AGENT")
                        .isNotNull(AidRuntimeRun::getMemberId)
                        .isNotNull(AidRuntimeRun::getVersionId)
                        .apply("(member_type='SKILL' OR EXISTS (SELECT 1 FROM aid_runtime_agent a "
                                + "WHERE a.id=aid_runtime_run.member_id "
                                + "AND a.agent_family='LEGACY_PLAN' AND a.del_flag='0'))")
                        .eq(StrUtil.isNotBlank(source.getMemberType()), AidRuntimeRun::getMemberType, source.getMemberType())
                        .eq(memberId != null, AidRuntimeRun::getMemberId, memberId)
                        .eq(source.getWorkspaceId() != null, AidRuntimeRun::getWorkspaceId, source.getWorkspaceId())
                        .isNull(source.getWorkspaceId() == null, AidRuntimeRun::getWorkspaceId)
                        .eq(StrUtil.isNotBlank(source.getNodeObjectId()), AidRuntimeRun::getNodeObjectId,
                                source.getNodeObjectId())
                        .eq(AidRuntimeRun::getDelFlag, NORMAL)
                        .orderByDesc(AidRuntimeRun::getId));
    }

    public void recover() {
        Date before = new Date(System.currentTimeMillis() - 15000L);
        List<AidRuntimeRun> pending = runs.selectList(Wrappers.<AidRuntimeRun>lambdaQuery()
                .select(AidRuntimeRun::getId)
                .isNull(AidRuntimeRun::getSessionId)
                .in(AidRuntimeRun::getMemberType, "SKILL", "AGENT")
                .isNotNull(AidRuntimeRun::getMemberId)
                .isNotNull(AidRuntimeRun::getVersionId)
                .apply("(member_type='SKILL' OR EXISTS (SELECT 1 FROM aid_runtime_agent a "
                        + "WHERE a.id=aid_runtime_run.member_id "
                        + "AND a.agent_family='LEGACY_PLAN' AND a.del_flag='0'))")
                .in(AidRuntimeRun::getStatus, "CREATED", "RUNNING", "WAITING_INPUT")
                .lt(AidRuntimeRun::getUpdateTime, before)
                .eq(AidRuntimeRun::getDelFlag, NORMAL)
                .orderByAsc(AidRuntimeRun::getUpdateTime).last("limit 100"));
        for (AidRuntimeRun run : pending) {
            try { advance(run.getId()); }
            catch (RuntimeException error) {
                log.warn("Runtime 恢复待重试, runId={}, errorType={}", run.getId(),
                        error.getClass().getSimpleName());
            }
        }
    }

    private void advance(Long runId) {
        requireGenericRuntimeRun(runs.selectById(runId));
        RLock lock = redisson.getLock("runtime:run:" + runId);
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS)) return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException("运行恢复被中断");
        }
        try {
            advanceLocked(runId);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void advanceLocked(Long runId) {
        for (int guard = 0; guard < 10; guard++) {
            SubmissionEvidence evidence = reconcileSubmission(runId);
            AidRuntimeRun run = evidence.run();
            if (run == null || TERMINAL.contains(run.getStatus())) return;
            if (run.getNextRetryAt() != null && run.getNextRetryAt().after(new Date())) return;
            JSONObject limits = JSON.parseObject(run.getRequestJson());
            if (com.aid.runtime.assistant.AssistantModelRecovery.pending(run.getRequestJson())
                    && (Boolean.TRUE.equals(run.getStopRequested())
                    || System.currentTimeMillis() - run.getCreateTime().getTime()
                    > limits.getLongValue("maxDurationSeconds") * 1000L)
                    && !submissionStateUncertain(evidence.step())) {
                fail(run, "恢复已停止或运行时限已到");
                return;
            }
            if (recoverableUnsubmittedToolFailure(evidence)) {
                propagateUnsubmittedToolFailure(runId);
                return;
            }
            if (evidence.step() != null && evidence.task() != null) {
                if (!TERMINAL.contains(evidence.step().getStatus())) {
                    if (refreshStep(run, evidence.step())) continue;
                    return;
                }
                if ("FAILED".equals(evidence.step().getStatus())) {
                    if (recoverTextAttempt(run, evidence.step(), evidence.task(), null)) return;
                    failFromAuthoritativeTask(run, evidence.step(), evidence.task().getId(),
                            StrUtil.blankToDefault(evidence.task().getErrorMessage(), "任务失败"));
                    return;
                }
                if ("CANCELED".equals(evidence.step().getStatus())) {
                    cancelFromAuthoritativeTask(run, evidence.step(), evidence.task().getId(),
                            StrUtil.blankToDefault(evidence.task().getErrorMessage(), "媒体任务已取消"));
                    return;
                }
            }
            JSONObject frozen;
            List<LegacyRuntimeAgentRequests.Step> plan;
            try {
                frozen = JSON.parseObject(run.getRequestJson());
                JSONArray planned = frozen.getJSONArray("steps");
                plan = planned == null || planned.isEmpty()
                        ? List.of(defaultStep()) : planned.toJavaList(LegacyRuntimeAgentRequests.Step.class);
            } catch (RuntimeException error) {
                failBeforeSubmission(run, null, error);
                return;
            }
            List<AidRuntimeRunStep> history = steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getRunId, runId)
                    .eq(AidRuntimeRunStep::getDelFlag, NORMAL).orderByAsc(AidRuntimeRunStep::getStepIndex));
            if (history.size() >= plan.size()) {
                AidRuntimeRunStep last = history.get(history.size() - 1);
                if ("SUCCEEDED".equals(last.getStatus())) {
                    finish(run, last.getOutputJson());
                    return;
                }
            }
            if (run.getNodeObjectId() != null) {
                try {
                    reservations.assertReserved(run.getId(), run.getUserId());
                    reservations.renew(run.getId());
                } catch (ServiceException revoked) {
                    if (evidence.step() != null && submissionStateUncertain(evidence.step())) {
                        log.warn("Runtime 提交待对账且画布授权已失效, runId={}, step={}",
                                run.getId(), evidence.step().getStepIndex());
                        return;
                    }
                    fail(run, "画布运行授权已失效；已提交的媒体任务仍按原任务结算");
                    return;
                }
            }
            boolean expired = System.currentTimeMillis() - run.getCreateTime().getTime()
                    > frozen.getLongValue("maxDurationSeconds") * 1000L;
            if (expired) {
                if (evidence.step() != null && submissionStateUncertain(evidence.step())) return;
                fail(run, "运行时限已到");
                return;
            }
            if ("WAITING_INPUT".equals(run.getStatus())) {
                runs.update(null, Wrappers.<AidRuntimeRun>lambdaUpdate()
                        .eq(AidRuntimeRun::getId, runId)
                        .eq(AidRuntimeRun::getStatus, "WAITING_INPUT")
                        .set(AidRuntimeRun::getUpdateTime, new Date()));
                return;
            }
            int index = history.size();
            if (index > 0 && index < plan.size()
                    && !"TEXT".equals(resultMediaType(history.get(index - 1).getOutputJson()))) {
                fail(run, "媒体产物只能作为最终步骤；请显式选择工作区资源后重试");
                return;
            }
            String previous = index == 0 ? null : outputText(history.get(index - 1).getOutputJson());
            if (index > 0 && !"SUCCEEDED".equals(history.get(index - 1).getStatus())) {
                if (refreshStep(run, history.get(index - 1))) continue;
                return;
            }
            if (index >= plan.size()) return;
            if (index >= frozen.getIntValue("maxSteps")
                    || System.currentTimeMillis() - run.getCreateTime().getTime()
                    > frozen.getLongValue("maxDurationSeconds") * 1000L) {
                fail(run, "执行预算已用尽");
                return;
            }
            LegacyRuntimeAgentRequests.Step next = plan.get(index);
            String prompt;
            try {
                prompt = stepPrompt(frozen, next, previous);
            } catch (RuntimeException error) {
                failBeforeSubmission(run, index, error);
                return;
            }
            if (!"TOOL".equals(next.getKind())
                    && history.stream().filter(item -> !"TOOL".equals(item.getStepKind())).count()
                    >= frozen.getIntValue("maxTaskCount")) {
                fail(run, "模型任务预算已用尽");
                return;
            }
            if ((long) run.getEstimatedTokensUsed() + (prompt.length() + 3L) / 4L
                    > frozen.getIntValue("maxEstimatedTokens")) {
                fail(run, "上下文预算已用尽");
                return;
            }
            AidRuntimeRunStep step;
            try {
                step = createStep(run, next, index, prompt, frozen);
            } catch (RuntimeException error) {
                failBeforeSubmission(run, index, error);
                return;
            }
            if ("TOOL".equals(next.getKind())) {
                try {
                    String output = tools.execute(next.getToolCode(), previous == null ? prompt : previous);
                    completeStep(step, JSON.toJSONString(Map.of("textContent", output)), null);
                } catch (RuntimeException ignored) {
                    fail(run, "Tool 执行失败");
                    return;
                }
                continue;
            }
            submitStep(run, step, next, frozen, prompt);
            AidRuntimeRunStep submitted = steps.selectById(step.getId());
            if (!"SUCCEEDED".equals(submitted.getStatus())) return;
        }
    }

    private AidRuntimeRunStep createStep(AidRuntimeRun run, LegacyRuntimeAgentRequests.Step plan,
                                         int index, String prompt, JSONObject frozen) {
        String stableStepId = StrUtil.blankToDefault(plan.getStepKey(), "legacy:" + index);
        AidRuntimeRunStep step = new AidRuntimeRunStep();
        step.setRunId(run.getId());
        step.setStepIndex(index);
        step.setStepKey(stableStepId);
        step.setStepKind(plan.getKind());
        step.setStableStepId(stableStepId);
        step.setPlanRevision(run.getPlanRevision() == null ? 0L : run.getPlanRevision());
        step.setPlanPosition(index);
        step.setAttemptNo(1);
        step.setOperationKind("GENERIC_" + plan.getKind());
        step.setIdempotencyKey(SecureUtil.sha256(run.getId() + ":" + stableStepId + ":1"));
        step.setToolCode(plan.getToolCode());
        if ("SKILL".equals(plan.getKind())) {
            JSONObject versionIds = frozen.getJSONObject("childVersions");
            step.setSkillVersionId(versionIds == null ? null : versionIds.getLong(plan.getSkillCode()));
            if (step.getSkillVersionId() == null) throw new ServiceException("Skill 固定版本缺失");
        }
        step.setInputDigest(SecureUtil.sha256(prompt));
        step.setProviderAcceptanceState("NOT_SUBMITTED");
        step.setFencingToken(0L);
        step.setStatus("CREATED");
        step.setDelFlag(NORMAL);
        step.setCreateBy(run.getCreateBy());
        step.setCreateTime(new Date());
        step.setUpdateBy(run.getCreateBy());
        step.setUpdateTime(new Date());
        transactions.executeWithoutResult(status -> {
            AidRuntimeRun latest = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getId, run.getId()).last("FOR UPDATE"));
            if (latest == null || TERMINAL.contains(latest.getStatus()))
                throw new ServiceException("运行不可继续");
            try { steps.insert(step); }
            catch (DuplicateKeyException error) {
                throw new ServiceException("运行步骤已被并发创建");
            }
            runs.update(null, Wrappers.<AidRuntimeRun>lambdaUpdate()
                    .eq(AidRuntimeRun::getId, latest.getId())
                    .set(AidRuntimeRun::getEstimatedTokensUsed,
                            latest.getEstimatedTokensUsed() + (prompt.length() + 3) / 4)
                    .set(AidRuntimeRun::getMediaTaskId, null)
                    .set(AidRuntimeRun::getUpdateTime, new Date()));
        });
        return step;
    }

    private void submitStep(AidRuntimeRun run, AidRuntimeRunStep step, LegacyRuntimeAgentRequests.Step plan,
                            JSONObject frozen, String prompt) {
        SubmissionEvidence existing = reconcileSubmission(run.getId());
        if (existing.step() == null || !Objects.equals(existing.step().getId(), step.getId()))
            throw new ServiceException("运行步骤已变化", 409);
        if (existing.task() != null) {
            refreshStep(existing.run(), existing.step());
            return;
        }
        GenericRuntimeRequests.Invoke request;
        String outputKind;
        String model;
        String instruction;
        String submissionPrompt;
        String stepRequestId;
        GenerationInvocationContext context;
        boolean firstSubmission;
        try {
            JSONObject input = frozen.getJSONObject("request");
            request = input.toJavaObject(GenericRuntimeRequests.Invoke.class);
            outputKind = run.getOutputKind();
            model = run.getModelCode();
            instruction = frozen.getString("instruction");
            JSONObject textContract = frozen.getJSONObject("skillTextContract");
            JSONObject businessInput = JSON.parseObject(frozen.getString("skillBusinessInput"));
            if ("SKILL".equals(plan.getKind())) {
                AidSkillVersion child = resourceLoader.loadVersion(step.getSkillVersionId());
                if (child == null || !NORMAL.equals(child.getDelFlag()))
                    throw new ServiceException("固定 Skill 版本丢失");
                outputKind = SkillModelService.outputKind(child);
                model = modelService.resolve(child, null).modelCode();
                instruction = child.getSystemPrompt();
            }
            submissionPrompt = "TEXT".equals(outputKind) ? prompt : instruction + "\n\n" + prompt;
            Boolean structuredOutputEnabled = null;
            if (textContract != null) {
                structuredOutputEnabled = textContract.getString("responseSchema") != null;
                instruction += textContract.getString("instruction");
                if (businessInput != null && !businessInput.isEmpty())
                    submissionPrompt = (prompt == null ? "" : prompt) + "\n\n本次 Skill 输入（业务数据）\n"
                            + businessInput.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
                if (StrUtil.isBlank(submissionPrompt)) submissionPrompt = "请按 Skill 的说明执行本次任务。";
            }
            if ("TEXT".equals(outputKind))
                instruction += com.aid.runtime.assistant.AssistantModelRecovery.instruction(run.getRequestJson());
            stepRequestId = SecureUtil.sha256(runtimeRequestIdentity(run, step)).substring(0, 32);
            context = GenerationInvocationContext.builder()
                    .userId(run.getUserId()).systemPrompt("TEXT".equals(outputKind) ? instruction : null)
                    .structuredOutputEnabled(structuredOutputEnabled)
                    .bizTaskType("RUNTIME_RUN").bizTaskId(run.getId())
                    .callId(runtimeCallId(run, step))
                    .callIdentity("runtime-step:" + step.getStepKey()).build();
            firstSubmission = markSubmissionStarted(step);
        } catch (RuntimeException error) {
            failBeforeSubmission(run, step.getStepIndex(), error);
            return;
        }
        GenerationResponse response;
        try {
            if ("IMAGE".equals(outputKind)) {
                GenerationRequests.Image generationRequest = new GenerationRequests.Image();
                copyCommon(generationRequest, request, model, stepRequestId);
                generationRequest.setPrompt(submissionPrompt);
                generationRequest.setSize(request.getSize());
                generationRequest.setExpectedImageCount(request.getExpectedImageCount());
                generationRequest.setReferences(request.getReferences());
                response = generation.generateImage(generationRequest, context);
            } else if ("VIDEO".equals(outputKind)) {
                GenerationRequests.Video generationRequest = new GenerationRequests.Video();
                copyCommon(generationRequest, request, model, stepRequestId);
                generationRequest.setPrompt(submissionPrompt);
                generationRequest.setDurationSeconds(request.getDurationSeconds());
                generationRequest.setAspectRatio(request.getAspectRatio());
                generationRequest.setAudio(request.getAudio());
                generationRequest.setBgm(request.getBgm());
                generationRequest.setAudioType(request.getAudioType());
                generationRequest.setVoiceId(request.getVoiceId());
                generationRequest.setReferences(request.getReferences());
                response = generation.generateVideo(generationRequest, context);
            } else if ("TEXT".equals(outputKind)) {
                GenerationRequests.Text generationRequest = new GenerationRequests.Text();
                copyCommon(generationRequest, request, model, stepRequestId);
                generationRequest.setPrompt(submissionPrompt);
                generationRequest.setResultFormat(request.getResultFormat());
                generationRequest.setReferences(request.getReferences());
                response = generation.generateText(generationRequest, context);
            } else throw new ServiceException("输出类型不可用");
            if (response == null || response.getTask() == null || response.getTask().getTaskId() == null)
                throw new ServiceException("模型任务未返回编号");
            GenerationTaskView task = response.getTask();
            long taskId = Long.parseLong(String.valueOf(task.getTaskId()));
            updateSubmitted(run, step, taskId, task);
        } catch (RuntimeException error) {
            if (error instanceof ServiceException serviceError
                    && serviceError.getCode() != null
                    && Set.of(400, 401, 403, 404, 422).contains(serviceError.getCode())) {
                failRejectedSubmission(run, step, serviceError, firstSubmission);
                return;
            }
            // The media task may have committed while the response was lost. Retrying the
            // same client request key is safe; terminal failure is decided after recovery.
            log.warn("Runtime 步骤提交待恢复, runId={}, step={}, errorType={}",
                    run.getId(), step.getStepIndex(), error.getClass().getSimpleName());
            throw error;
        }
    }

    private static JSONObject textSkillContract(AidSkillVersion version) {
        String definition = version.getDefinitionJson();
        String output = version.getOutputSchemaJson();
        String input = version.getInputSchemaJson();
        String inputInstruction = SkillContractExamples.inputContractInstruction(input);
        int revision = SkillMethodOutputContract.CURRENT_REVISION;
        return JSONObject.of("revision", revision, "input", input,
                "definition", definition, "output", output,
                "responseSchema", SkillMethodOutputContract.providerSchema(definition, output, revision),
                "instruction", inputInstruction + SkillMethodOutputContract.instruction(definition, output)
                        + SkillContractExamples.instruction(input, output));
    }

    private static JSONObject skillInput(JSONObject contract, GenericRuntimeRequests.Invoke request, String prompt) {
        JSONObject schema = SkillMethodInputContract.objectParameters(contract.getString("input"));
        JSONObject fields = schema.getJSONObject("properties");
        JSONObject input = new JSONObject();
        if (request.getInput() != null) {
            input.putAll(request.getInput());
        } else {
            if (fields.containsKey("prompt") && StrUtil.isNotBlank(prompt)) input.put("prompt", prompt);
            if (request.getParameters() != null)
                fields.keySet().forEach(field -> {
                    if (!input.containsKey(field) && request.getParameters().containsKey(field))
                        input.put(field, request.getParameters().get(field));
                });
        }
        if (!com.alibaba.fastjson2.schema.JSONSchema.of(schema).validate(input).isSuccess())
            throw new ServiceException("Skill 输入未通过契约校验", 422);
        return input;
    }

    private static void copyCommon(GenerationRequests.Base target, GenericRuntimeRequests.Invoke input,
                                   String model, String stepRequestId) {
        target.setModelName(model);
        target.setCapabilityCode(input.getCapabilityCode());
        target.setProjectId(input.getProjectId());
        target.setEpisodeId(input.getEpisodeId());
        target.setWorkspaceId(input.getWorkspaceId());
        target.setClientRequestId(stepRequestId);
        target.setSession(input.getSession());
        // Parent runtime reservation owns the canvas node until the whole run ends.
        target.setCanvasNode(null);
        target.setParameters(input.getParameters());
    }

    private void updateSubmitted(AidRuntimeRun run, AidRuntimeRunStep step,
                                 Long taskId, GenerationTaskView task) {
        String mediaStatus = taskStatus(task.getStatus());
        boolean checkResult = "SUCCEEDED".equals(mediaStatus)
                && JSON.parseObject(run.getRequestJson()).containsKey("skillTextContract");
        String next = checkResult ? "RUNNING" : mediaStatus;
        transactions.executeWithoutResult(status -> {
            AidRuntimeRun current = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getId, run.getId()).last("FOR UPDATE"));
            AidRuntimeRunStep locked = steps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getId, step.getId()).last("FOR UPDATE"));
            if (current == null || TERMINAL.contains(current.getStatus()) || locked == null
                    || TERMINAL.contains(locked.getStatus()))
                throw new ServiceException("运行步骤不可绑定媒体任务", 409);
            if (locked.getMediaTaskId() != null && !Objects.equals(locked.getMediaTaskId(), taskId))
                throw new ServiceException("运行步骤关联了不同任务");
            AidMediaTask durable = mediaTasks.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                    .eq(AidMediaTask::getId, taskId).last("FOR UPDATE"));
            requireTaskIdentity(current, locked, durable);
            if (TERMINAL.contains(next) && !Objects.equals(next, taskStatus(durable.getStatus())))
                throw new ServiceException("媒体任务终态已变化", 409);
            locked.setMediaTaskId(taskId);
            locked.setProviderAcceptanceState("ACCEPTED");
            locked.setStatus(next);
            if (TERMINAL.contains(next)) locked.setOutputJson(JSON.toJSONString(task));
            locked.setUpdateTime(new Date());
            steps.updateById(locked);
            current.setMediaTaskId(taskId);
            current.setStatus("RUNNING");
            if ("SUCCEEDED".equals(next)) {
                current.setRequestJson(com.aid.runtime.assistant.AssistantModelRecovery.clear(current.getRequestJson()));
                current.setNextRetryAt(null);
            }
            current.setUpdateTime(new Date());
            runs.updateById(current);
        });
        if (run.getNodeObjectId() != null) {
            try {
                reservations.renew(run.getId());
            } catch (RuntimeException revoked) {
                log.warn("Runtime 媒体任务已绑定但画布占用续期失败, runId={}, step={}, taskId={}",
                        run.getId(), step.getStepIndex(), taskId);
            }
        }
        if ("FAILED".equals(next)) {
            if (!recoverTextAttempt(run, step, mediaTasks.selectById(taskId), null))
                failFromAuthoritativeTask(run, step, taskId,
                        StrUtil.blankToDefault(task.getErrorMessage(), "任务失败"));
        }
        else if ("CANCELED".equals(next))
            cancelFromAuthoritativeTask(run, step, taskId,
                    StrUtil.blankToDefault(task.getErrorMessage(), "媒体任务已取消"));
    }

    private boolean markSubmissionStarted(AidRuntimeRunStep step) {
        Boolean first = transactions.execute(status -> {
            AidRuntimeRunStep locked = steps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getId, step.getId()).last("FOR UPDATE"));
            if (locked == null || TERMINAL.contains(locked.getStatus()))
                throw new ServiceException("运行步骤不可提交");
            if ("NOT_SUBMITTED".equals(locked.getProviderAcceptanceState())) {
                locked.setProviderAcceptanceState("SUBMITTING");
                locked.setUpdateTime(new Date());
                steps.updateById(locked);
                return true;
            } else if (!"SUBMITTING".equals(locked.getProviderAcceptanceState())) {
                throw new ServiceException("运行步骤提交状态异常");
            }
            return false;
        });
        return Boolean.TRUE.equals(first);
    }

    private SubmissionEvidence reconcileSubmission(Long runId) {
        return transactions.execute(status -> lockSubmissionEvidence(runId));
    }

    private SubmissionEvidence lockSubmissionEvidence(Long runId) {
        AidRuntimeRun current = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getId, runId).last("FOR UPDATE"));
        if (current == null) throw new ServiceException("运行不存在");
        AidRuntimeRunStep step = steps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, NORMAL)
                .orderByDesc(AidRuntimeRunStep::getStepIndex).last("LIMIT 1 FOR UPDATE"));
        AidMediaTask task = null;
        if (step != null && step.getMediaTaskId() != null) {
            task = mediaTasks.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                    .eq(AidMediaTask::getId, step.getMediaTaskId()).last("FOR UPDATE"));
            if (task == null) throw new ServiceException("运行步骤媒体任务丢失", 409);
        } else if (step != null && !"TOOL".equals(step.getStepKind())) {
            task = mediaTasks.selectByAssistantOperationForUpdate(current.getUserId(),
                    RUNTIME_BIZ_TASK_TYPE, current.getId(), runtimeCallId(current, step));
        }
        if (task != null) requireTaskIdentity(current, step, task);

        Long projection = step == null ? null : step.getMediaTaskId();
        boolean active = step != null && !TERMINAL.contains(step.getStatus());
        if (active && task != null) {
            if (step.getMediaTaskId() == null) step.setMediaTaskId(task.getId());
            step.setProviderAcceptanceState("ACCEPTED");
            if ("CREATED".equals(step.getStatus()) || "QUEUED".equals(step.getStatus()))
                step.setStatus("RUNNING");
            step.setUpdateTime(new Date());
            steps.updateById(step);
            projection = task.getId();
        } else if (active) {
            if (step.getProviderAcceptanceState() == null) {
                step.setProviderAcceptanceState("NOT_SUBMITTED");
                step.setUpdateTime(new Date());
                steps.updateById(step);
            }
            projection = null;
        } else if (step != null && task != null && step.getMediaTaskId() == null) {
            throw new ServiceException("终态步骤缺少媒体任务绑定", 409);
        }
        if (!active && step != null && task != null
                && !Objects.equals(step.getStatus(), taskStatus(task.getStatus()))
                && !hasRejectedSkillResult(current, step, task))
            throw new ServiceException("运行步骤与媒体任务终态不一致", 409);
        boolean markRunning = active && task != null && !TERMINAL.contains(current.getStatus())
                && !"RUNNING".equals(current.getStatus());
        if (!Objects.equals(current.getMediaTaskId(), projection) || markRunning) {
            runs.update(null, Wrappers.<AidRuntimeRun>lambdaUpdate()
                    .eq(AidRuntimeRun::getId, current.getId())
                    .set(AidRuntimeRun::getMediaTaskId, projection)
                    .set(markRunning, AidRuntimeRun::getStatus, "RUNNING")
                    .set(AidRuntimeRun::getUpdateTime, new Date()));
            current.setMediaTaskId(projection);
            if (markRunning) current.setStatus("RUNNING");
        }
        return new SubmissionEvidence(current, step, task);
    }

    private static boolean hasRejectedSkillResult(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task) {
        if (!"FAILED".equals(run.getStatus()) || !"FAILED".equals(step.getStatus())
                || !"SUCCEEDED".equals(task.getStatus()) || !"TEXT".equals(task.getMediaType())
                || !JSON.parseObject(run.getRequestJson()).containsKey("skillTextContract"))
            return false;
        JSONObject result = JSON.parseObject(step.getOutputJson());
        return result != null && "SKILL_OUTPUT_CONTRACT_REJECTED".equals(result.getString("failureCode"));
    }

    private void requireTaskIdentity(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task) {
        if (task == null || !Objects.equals(task.getUserId(), run.getUserId())
                || !RUNTIME_BIZ_TASK_TYPE.equals(task.getBizTaskType())
                || !Objects.equals(task.getBizTaskId(), run.getId())
                || !Objects.equals(task.getIdempotencyKey(), runtimeCallId(run, step)))
            throw new ServiceException("运行与媒体任务身份不匹配", 409);
    }

    private static String runtimeCallId(AidRuntimeRun run, AidRuntimeRunStep step) {
        if (step.getStepIndex() == null) throw new ServiceException("运行步骤坐标缺失", 409);
        return "runtime:" + runtimeRequestIdentity(run, step);
    }

    private static String runtimeRequestIdentity(AidRuntimeRun run, AidRuntimeRunStep step) {
        int attempt = step.getAttemptNo() == null ? 1 : step.getAttemptNo();
        return run.getId() + ":" + step.getStepIndex() + (attempt <= 1 ? "" : ":retry:" + (attempt - 1));
    }

    private static boolean submissionStateUncertain(AidRuntimeRunStep step) {
        return step != null && !TERMINAL.contains(step.getStatus())
                && ("SUBMITTING".equals(step.getProviderAcceptanceState())
                || "ACCEPTED".equals(step.getProviderAcceptanceState())
                || "UNKNOWN".equals(step.getProviderAcceptanceState()));
    }

    private static boolean canFailUnsubmittedStep(SubmissionEvidence evidence) {
        return evidence.step() != null && !TERMINAL.contains(evidence.step().getStatus())
                && evidence.task() == null
                && "NOT_SUBMITTED".equals(evidence.step().getProviderAcceptanceState());
    }

    private void updateRunWithMediaProjection(AidRuntimeRun run) {
        runs.updateById(run);
        if (run.getMediaTaskId() == null) {
            runs.update(null, Wrappers.<AidRuntimeRun>lambdaUpdate()
                    .eq(AidRuntimeRun::getId, run.getId())
                    .set(AidRuntimeRun::getMediaTaskId, null));
        }
    }

    private void failBeforeSubmission(AidRuntimeRun run, Integer index, RuntimeException error) {
        try {
            transactions.executeWithoutResult(status -> {
                SubmissionEvidence evidence = lockSubmissionEvidence(run.getId());
                AidRuntimeRun currentRun = evidence.run();
                AidRuntimeRunStep current = evidence.step();
                if (currentRun == null || TERMINAL.contains(currentRun.getStatus())) return;
                if (current != null && !TERMINAL.contains(current.getStatus())
                        && !canFailUnsubmittedStep(evidence))
                    throw new ServiceException("运行步骤提交结果待权威恢复", 409);

                boolean sameStep = current != null && index != null
                        && Objects.equals(current.getStepIndex(), index);
                boolean nextAfterSuccess = index != null && (current == null && index == 0
                        || current != null && "SUCCEEDED".equals(current.getStatus())
                        && current.getStepIndex() != null && current.getStepIndex() + 1 == index);
                if (index != null && !sameStep && !nextAfterSuccess)
                    throw new ServiceException("运行步骤已变化", 409);
                if (sameStep && TERMINAL.contains(current.getStatus()))
                    throw new ServiceException("运行步骤终态已变化", 409);

                Date now = new Date();
                if (canFailUnsubmittedStep(evidence) && (index == null || sameStep)) {
                    current.setStatus("FAILED");
                    current.setErrorMessage("运行准备失败");
                    current.setUpdateTime(now);
                    steps.updateById(current);
                }
                currentRun.setStatus("FAILED");
                currentRun.setErrorMessage("运行准备失败");
                currentRun.setUpdateTime(now);
                runs.updateById(currentRun);
                if (currentRun.getNodeObjectId() != null) reservations.release(currentRun.getId());
            });
            log.warn("Runtime 步骤准备失败, runId={}, step={}, errorType={}",
                    run.getId(), index, error.getClass().getSimpleName());
        } catch (ServiceException guarded) {
            log.warn("Runtime 步骤准备结果待恢复或步骤已变化, runId={}, step={}, errorType={}",
                    run.getId(), index, error.getClass().getSimpleName());
            throw guarded;
        }
    }

    private void failRejectedSubmission(AidRuntimeRun run, AidRuntimeRunStep step,
                                        ServiceException error, boolean firstSubmission) {
        SubmissionEvidence evidence = reconcileSubmission(run.getId());
        AidRuntimeRunStep current = evidence.step();
        if (current == null || !Objects.equals(current.getId(), step.getId()))
            throw new ServiceException("运行步骤已变化", 409);
        if (evidence.task() != null || !firstSubmission) {
            log.warn("Runtime 步骤拒绝结果待恢复, runId={}, step={}, errorCode={}",
                    run.getId(), step.getStepIndex(), error.getCode());
            throw new ServiceException("运行步骤提交结果待权威恢复", 409);
        }
        failProvenSubmissionRejection(run.getId(), current.getId(), error);
    }

    private boolean refreshStep(AidRuntimeRun run, AidRuntimeRunStep step) {
        SubmissionEvidence evidence = reconcileSubmission(run.getId());
        if (evidence.step() == null || !Objects.equals(evidence.step().getId(), step.getId()))
            throw new ServiceException("运行步骤已变化", 409);
        run = evidence.run();
        step = evidence.step();
        if (evidence.task() == null) {
            if (TERMINAL.contains(step.getStatus())) return false;
            if ("ACCEPTED".equals(step.getProviderAcceptanceState())
                    || "UNKNOWN".equals(step.getProviderAcceptanceState())) {
                log.warn("Runtime 步骤缺少权威媒体任务，保持待对账, runId={}, step={}",
                        run.getId(), step.getStepIndex());
                return false;
            }
            JSONObject frozen;
            LegacyRuntimeAgentRequests.Step configured;
            try {
                frozen = JSON.parseObject(run.getRequestJson());
                JSONArray planned = frozen.getJSONArray("steps");
                List<LegacyRuntimeAgentRequests.Step> plan = planned == null || planned.isEmpty()
                        ? List.of(defaultStep()) : planned.toJavaList(LegacyRuntimeAgentRequests.Step.class);
                configured = plan.get(step.getStepIndex());
            } catch (RuntimeException error) {
                failBeforeSubmission(run, step.getStepIndex(), error);
                return true;
            }
            List<AidRuntimeRunStep> previous = steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getRunId, run.getId())
                    .lt(AidRuntimeRunStep::getStepIndex, step.getStepIndex())
                    .orderByDesc(AidRuntimeRunStep::getStepIndex).last("limit 1"));
            String prior = previous.isEmpty() ? null : outputText(previous.get(0).getOutputJson());
            String prompt;
            try {
                prompt = stepPrompt(frozen, configured, prior);
            } catch (RuntimeException error) {
                failBeforeSubmission(run, step.getStepIndex(), error);
                return true;
            }
            if ("TOOL".equals(configured.getKind())) {
                if (!"NOT_SUBMITTED".equals(step.getProviderAcceptanceState())) {
                    log.warn("Runtime Tool 步骤提交状态异常，保持待人工核对, runId={}, step={}",
                            run.getId(), step.getStepIndex());
                    return false;
                }
                try {
                    String output = tools.execute(configured.getToolCode(), prior == null ? prompt : prior);
                    completeStep(step, JSON.toJSONString(Map.of("textContent", output)), null);
                } catch (RuntimeException ignored) {
                    fail(run, "Tool 执行失败");
                }
                return true;
            }
            submitStep(run, step, configured, frozen, prompt);
            return true;
        }
        GenerationTaskView task = generation.task(evidence.task().getId(),
                GenerationInvocationContext.user(run.getUserId()));
        if (task == null) return false;
        String status = taskStatus(task.getStatus());
        if (!TERMINAL.contains(status)) return false;
        if ("SUCCEEDED".equals(status)) {
            JSONObject result = JSON.parseObject(JSON.toJSONString(task));
            JSONObject contract = JSON.parseObject(run.getRequestJson()).getJSONObject("skillTextContract");
            if (contract != null && "TEXT".equals(task.getMediaType())) {
                try {
                    Object businessResult = SkillMethodOutputContract.validatedResult(
                            contract.getString("definition"), contract.getString("output"), task.getTextContent(),
                            contract.getIntValue("revision"));
                    if (businessResult instanceof JSONObject) result.put("result", businessResult);
                } catch (IllegalArgumentException invalid) {
                    String message = StrUtil.isBlank(task.getTextContent())
                            ? "模型未返回正文" : "Skill 输出未通过契约校验";
                    if (recoverTextAttempt(run, step, evidence.task(), StrUtil.isBlank(task.getTextContent())
                            ? "MODEL_OUTPUT_EMPTY" : "AGENT_OUTPUT_CONTRACT_INVALID")) return false;
                    failFromAuthoritativeTask(run, step, evidence.task().getId(), message, "SUCCEEDED");
                    return false;
                }
            }
            completeStep(step, result.toJSONString(), evidence.task().getId());
            return true;
        }
        if ("CANCELED".equals(status)) {
            cancelFromAuthoritativeTask(run, step, evidence.task().getId(),
                    StrUtil.blankToDefault(task.getErrorMessage(), "媒体任务已取消"));
            return false;
        }
        if (recoverTextAttempt(run, step, evidence.task(), null)) return false;
        failFromAuthoritativeTask(run, step, evidence.task().getId(),
                StrUtil.blankToDefault(task.getErrorMessage(), "任务失败"));
        return false;
    }

    /** New attempts retain the original task/usage and never replay a tool or unknown receipt. */
    private boolean recoverTextAttempt(AidRuntimeRun run, AidRuntimeRunStep step,
                                        AidMediaTask expectedTask, String outputReason) {
        if (expectedTask == null || step == null || "TOOL".equals(step.getStepKind())) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(run.getId());
            AidRuntimeRun current = evidence.run();
            AidRuntimeRunStep source = evidence.step();
            AidMediaTask task = evidence.task();
            if (TERMINAL.contains(current.getStatus()) || Boolean.TRUE.equals(current.getStopRequested())
                    || source == null || !Objects.equals(source.getId(), step.getId())) return false;
            if (task == null || !Objects.equals(expectedTask.getId(), task.getId())) return true;
            if (!"TEXT".equals(task.getMediaType()) || source.getOutputArtifactRef() != null
                    && !source.getOutputArtifactRef().isBlank() && !"[]".equals(source.getOutputArtifactRef())) return false;
            String reason = "FAILED".equals(task.getStatus())
                    ? com.aid.runtime.assistant.AssistantModelRecovery.failedTaskReason(task.getErrorDetailJson())
                    : "SUCCEEDED".equals(task.getStatus()) ? outputReason : null;
            String request = com.aid.runtime.assistant.AssistantModelRecovery.schedule(
                    current.getRequestJson(), source.getId(), reason);
            if (request == null) return false;
            JSONObject frozen = JSON.parseObject(request);
            if (System.currentTimeMillis() - current.getCreateTime().getTime()
                    > frozen.getLongValue("maxDurationSeconds") * 1000L) return false;
            var ledger = task.getBillingTraceId() == null ? null : retryAccounts.inspectLedger(task.getBillingTraceId());
            if (ledger != null && !ledger.valid() && !ledger.empty()) return false;
            if (!com.aid.billing.service.TextRetrySettlementEvidence.closed(task, ledger)) return true;
            int attempt = source.getAttemptNo() == null ? 1 : source.getAttemptNo();
            if (attempt > com.aid.runtime.assistant.AssistantModelRecovery.MAX_RETRIES) return false;
            List<AidRuntimeRunStep> previous = steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                    .eq(AidRuntimeRunStep::getRunId, run.getId())
                    .lt(AidRuntimeRunStep::getStepIndex, source.getStepIndex())
                    .orderByDesc(AidRuntimeRunStep::getStepIndex).last("LIMIT 1"));
            JSONArray configured = frozen.getJSONArray("steps");
            LegacyRuntimeAgentRequests.Step plan = configured == null || configured.isEmpty() ? defaultStep()
                    : configured.toJavaList(LegacyRuntimeAgentRequests.Step.class).get(source.getStepIndex());
            String prompt = stepPrompt(frozen, plan, previous.isEmpty() ? null : outputText(previous.get(0).getOutputJson()));
            long extra = (prompt.length() + 3L) / 4L;
            if ((long) current.getEstimatedTokensUsed() + extra > frozen.getLongValue("maxEstimatedTokens")) return false;
            source.setAttemptNo(attempt + 1);
            source.setIdempotencyKey(SecureUtil.sha256(run.getId() + ":" + source.getStableStepId() + ":" + (attempt + 1)));
            source.setStatus("CREATED");
            source.setProviderAcceptanceState("NOT_SUBMITTED");
            source.setUpdateTime(new Date());
            steps.updateById(source);
            steps.update(null, Wrappers.<AidRuntimeRunStep>lambdaUpdate().eq(AidRuntimeRunStep::getId, source.getId())
                    .set(AidRuntimeRunStep::getMediaTaskId, null).set(AidRuntimeRunStep::getOutputJson, null)
                    .set(AidRuntimeRunStep::getErrorMessage, null));
            current.setRequestJson(request);
            current.setStatus("RUNNING");
            current.setMediaTaskId(null);
            current.setNextRetryAt(new Date(System.currentTimeMillis() + (1000L << (attempt - 1))));
            current.setEstimatedTokensUsed((int) (current.getEstimatedTokensUsed() + extra));
            current.setUpdateTime(new Date());
            updateRunWithMediaProjection(current);
            return true;
        }));
    }

    private void completeStep(AidRuntimeRunStep step, String outputJson, Long mediaTaskId) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(step.getRunId());
            AidRuntimeRunStep locked = evidence.step();
            if (locked == null || "SUCCEEDED".equals(locked.getStatus())) return;
            if (!Objects.equals(locked.getId(), step.getId()))
                throw new ServiceException("运行步骤已变化", 409);
            if (TERMINAL.contains(locked.getStatus()))
                throw new ServiceException("运行步骤终态已变化", 409);
            if (mediaTaskId != null && locked.getMediaTaskId() != null
                    && !Objects.equals(locked.getMediaTaskId(), mediaTaskId))
                throw new ServiceException("运行步骤任务编号已变化");
            if (mediaTaskId != null && (evidence.task() == null
                    || !Objects.equals(evidence.task().getId(), mediaTaskId)))
                throw new ServiceException("运行步骤权威任务已变化", 409);
            locked.setMediaTaskId(mediaTaskId);
            if (mediaTaskId != null) locked.setProviderAcceptanceState("ACCEPTED");
            locked.setStatus("SUCCEEDED");
            locked.setOutputJson(outputJson);
            locked.setUpdateTime(new Date());
            steps.updateById(locked);
            AidRuntimeRun current = evidence.run();
            if (com.aid.runtime.assistant.AssistantModelRecovery.pending(current.getRequestJson())) {
                current.setRequestJson(com.aid.runtime.assistant.AssistantModelRecovery.clear(current.getRequestJson()));
                current.setNextRetryAt(null);
                current.setUpdateTime(new Date());
                runs.updateById(current);
            }
        });
    }

    private void finish(AidRuntimeRun run, String output) {
        transactions.executeWithoutResult(status -> {
            AidRuntimeRun locked = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .eq(AidRuntimeRun::getId, run.getId()).last("FOR UPDATE"));
            if (locked == null || TERMINAL.contains(locked.getStatus())) return;
            JSONObject result = JSON.parseObject(output);
            if ("TEXT".equals(locked.getOutputKind())) {
                String format = JSON.parseObject(locked.getRequestJson())
                        .getJSONObject("request").getString("resultFormat");
                result.put("textFormat", StrUtil.blankToDefault(format, "TEXT"));
            }
            locked.setResultJson(result.toJSONString());
            locked.setStatus("SUCCEEDED");
            locked.setUpdateTime(new Date());
            runs.updateById(locked);
            if (locked.getNodeObjectId() != null) reservations.release(locked.getId());
        });
    }

    private void fail(AidRuntimeRun run, String message) {
        fail(run, message, null);
    }

    private void fail(AidRuntimeRun run, String message, ApiErrorDetails details) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(run.getId());
            AidRuntimeRun locked = evidence.run();
            if (locked == null || TERMINAL.contains(locked.getStatus())) return;
            AidRuntimeRunStep current = evidence.step();
            if (current != null && !TERMINAL.contains(current.getStatus())
                    && !canFailUnsubmittedStep(evidence)) {
                log.warn("Runtime 父运行失败收口被权威提交证据阻止, runId={}, step={}",
                        run.getId(), current.getStepIndex());
                return;
            }
            Date now = new Date();
            if (canFailUnsubmittedStep(evidence)) {
                current.setStatus("FAILED");
                current.setErrorMessage(limit(message, 1000));
                current.setUpdateTime(now);
                steps.updateById(current);
            }
            locked.setStatus("FAILED");
            locked.setErrorMessage(limit(message, 1000));
            if (details != null) locked.setResultJson(JSON.toJSONString(Map.of("runtimeError", details)));
            locked.setUpdateTime(now);
            runs.updateById(locked);
            if (locked.getNodeObjectId() != null) reservations.release(locked.getId());
        });
    }

    private static boolean recoverableUnsubmittedToolFailure(SubmissionEvidence evidence) {
        return evidence.run() != null && !TERMINAL.contains(evidence.run().getStatus())
                && evidence.step() != null && "TOOL".equals(evidence.step().getStepKind())
                && "FAILED".equals(evidence.step().getStatus())
                && evidence.step().getMediaTaskId() == null && evidence.task() == null
                && "NOT_SUBMITTED".equals(evidence.step().getProviderAcceptanceState());
    }

    private void propagateUnsubmittedToolFailure(Long runId) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(runId);
            AidRuntimeRun run = evidence.run();
            if (run == null || TERMINAL.contains(run.getStatus())) return;
            if (!recoverableUnsubmittedToolFailure(evidence))
                throw new ServiceException("Tool 运行终态已变化", 409);
            run.setStatus("FAILED");
            run.setErrorMessage("Tool 执行失败");
            run.setUpdateTime(new Date());
            runs.updateById(run);
            if (run.getNodeObjectId() != null) reservations.release(runId);
        });
    }

    private void failProvenSubmissionRejection(Long runId, Long stepId, ServiceException error) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(runId);
            AidRuntimeRunStep step = evidence.step();
            if (step == null || !Objects.equals(step.getId(), stepId)
                    || TERMINAL.contains(step.getStatus())) return;
            if (evidence.task() != null || !"SUBMITTING".equals(step.getProviderAcceptanceState()))
                throw new ServiceException("运行步骤提交结果待权威恢复", 409);
            Date now = new Date();
            step.setStatus("FAILED");
            step.setErrorMessage(limit(error.getMessage(), 1000));
            step.setUpdateTime(now);
            steps.updateById(step);
            AidRuntimeRun run = evidence.run();
            run.setStatus("FAILED");
            run.setErrorMessage(limit(error.getMessage(), 1000));
            if (error.getClientErrorData() != null)
                run.setResultJson(JSON.toJSONString(Map.of("runtimeError", error.getClientErrorData())));
            run.setUpdateTime(now);
            runs.updateById(run);
            if (run.getNodeObjectId() != null) reservations.release(runId);
        });
    }

    private void failFromAuthoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step,
                                           Long taskId, String message) {
        failFromAuthoritativeTask(run, step, taskId, message, "FAILED");
    }

    private void failFromAuthoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step,
                                           Long taskId, String message, String mediaStatus) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(run.getId());
            if (evidence.step() == null || !Objects.equals(evidence.step().getId(), step.getId())
                    || evidence.task() == null || !Objects.equals(evidence.task().getId(), taskId))
                throw new ServiceException("运行步骤权威任务已变化", 409);
            if (!mediaStatus.equals(evidence.task().getStatus()))
                throw new ServiceException("媒体任务终态已变化", 409);
            if (TERMINAL.contains(evidence.step().getStatus())
                    && !"FAILED".equals(evidence.step().getStatus()))
                throw new ServiceException("运行步骤终态已变化", 409);
            Date now = new Date();
            evidence.step().setStatus("FAILED");
            evidence.step().setErrorMessage(limit(message, 1000));
            if ("SUCCEEDED".equals(mediaStatus))
                evidence.step().setOutputJson(JSONObject.of("failureCode", "SKILL_OUTPUT_CONTRACT_REJECTED").toJSONString());
            evidence.step().setUpdateTime(now);
            steps.updateById(evidence.step());
            if (!TERMINAL.contains(evidence.run().getStatus())) {
                evidence.run().setMediaTaskId(taskId);
                evidence.run().setStatus("FAILED");
                evidence.run().setErrorMessage(limit(message, 1000));
                evidence.run().setUpdateTime(now);
                runs.updateById(evidence.run());
                if (evidence.run().getNodeObjectId() != null) reservations.release(run.getId());
            }
        });
    }

    private void cancelFromAuthoritativeTask(AidRuntimeRun run, AidRuntimeRunStep step,
                                             Long taskId, String message) {
        transactions.executeWithoutResult(status -> {
            SubmissionEvidence evidence = lockSubmissionEvidence(run.getId());
            if (evidence.step() == null || !Objects.equals(evidence.step().getId(), step.getId())
                    || evidence.task() == null || !Objects.equals(evidence.task().getId(), taskId))
                throw new ServiceException("运行步骤权威任务已变化", 409);
            if (!isMediaCanceled(evidence.task().getStatus()))
                throw new ServiceException("媒体任务终态已变化", 409);
            if (TERMINAL.contains(evidence.step().getStatus())
                    && !"CANCELED".equals(evidence.step().getStatus()))
                throw new ServiceException("运行步骤终态已变化", 409);
            Date now = new Date();
            evidence.step().setStatus("CANCELED");
            evidence.step().setErrorMessage(limit(message, 1000));
            evidence.step().setUpdateTime(now);
            steps.updateById(evidence.step());
            if (!TERMINAL.contains(evidence.run().getStatus())) {
                evidence.run().setMediaTaskId(taskId);
                evidence.run().setStatus("CANCELED");
                evidence.run().setErrorMessage(limit(message, 1000));
                evidence.run().setUpdateTime(now);
                runs.updateById(evidence.run());
                if (evidence.run().getNodeObjectId() != null) reservations.release(run.getId());
            }
        });
    }

    private JSONObject runView(Long runId, Long userId, boolean admin) {
        AidRuntimeRun run = requireRun(runId, userId);
        if (!admin) requireContentAccess(run, userId, false);
        JSONObject view = JSON.parseObject(JSON.toJSONString(run));
        view.put("id", JsonSafeId.value(run.getId()));
        view.put("userId", JsonSafeId.value(run.getUserId()));
        view.put("memberId", JsonSafeId.value(run.getMemberId()));
        view.put("versionId", JsonSafeId.value(run.getVersionId()));
        if (run.getMediaTaskId() != null)
            view.put("mediaTaskId", JsonSafeId.value(run.getMediaTaskId()));
        if (run.getTrialGrantId() != null)
            view.put("trialGrantId", JsonSafeId.value(run.getTrialGrantId()));
        view.remove("requestJson");
        view.remove("requestDigest");
        view.remove("clarificationDigest");
        view.remove("clarificationKey");
        try {
            JSONObject request = JSON.parseObject(run.getRequestJson()).getJSONObject("request");
            JSONObject preview = new JSONObject();
            preview.put("prompt", limit(request.getString("prompt"), 2000));
            preview.put("modelCode", request.getString("modelCode"));
            preview.put("capabilityCode", request.getString("capabilityCode"));
            preview.put("referenceCount", request.getJSONArray("references") == null ? 0
                    : request.getJSONArray("references").size());
            preview.put("size", request.getString("size"));
            preview.put("durationSeconds", request.getInteger("durationSeconds"));
            view.put("inputPreview", preview);
        } catch (RuntimeException ignored) {
            view.put("inputPreview", new JSONObject());
        }
        if (run.getResultJson() != null) {
            Object result = JSON.parse(run.getResultJson());
            if ("FAILED".equals(run.getStatus()) && result instanceof JSONObject error
                    && error.containsKey("runtimeError"))
                view.put("errorDetails", error.get("runtimeError"));
            else view.put("resultPreview", mediaPayloadUrlNormalizer.normalize(result));
        }
        view.remove("resultJson");
        List<AidRuntimeRunStep> row = steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getStepIndex,
                        AidRuntimeRunStep::getStepKey, AidRuntimeRunStep::getStepKind,
                        AidRuntimeRunStep::getSkillVersionId, AidRuntimeRunStep::getToolCode,
                        AidRuntimeRunStep::getAttemptNo,
                        AidRuntimeRunStep::getMediaTaskId, AidRuntimeRunStep::getStatus,
                        AidRuntimeRunStep::getErrorMessage, AidRuntimeRunStep::getUpdateTime)
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, NORMAL)
                .orderByAsc(AidRuntimeRunStep::getStepIndex));
        view.remove("mediaTaskId");
        view.remove("task");
        Long currentMediaTaskId = row.isEmpty() ? null : row.get(row.size() - 1).getMediaTaskId();
        if (currentMediaTaskId != null)
            view.put("mediaTaskId", JsonSafeId.value(currentMediaTaskId));
        BigDecimal actualCost = BigDecimal.ZERO;
        Set<String> billingStatuses = new LinkedHashSet<>();
        List<JSONObject> projectedSteps = new ArrayList<>();
        for (AidRuntimeRunStep item : row) {
            JSONObject projected = JSON.parseObject(JSON.toJSONString(item));
            projected.put("id", JsonSafeId.value(item.getId()));
            if (item.getMediaTaskId() != null) {
                projected.put("mediaTaskId", JsonSafeId.value(item.getMediaTaskId()));
                GenerationTaskView task = generation.task(item.getMediaTaskId(),
                        GenerationInvocationContext.user(run.getUserId()));
                projected.put("actualCost", task.getActualCost());
                projected.put("billingStatus", task.getBillingStatus());
            }
            List<JSONObject> attempts = new ArrayList<>();
            int count = item.getAttemptNo() == null ? 1 : item.getAttemptNo();
            if (count < 1 || count > com.aid.runtime.assistant.AssistantModelRecovery.MAX_RETRIES + 1)
                throw new ServiceException("运行重试次数无效", 409);
            for (int attempt = 1; attempt <= count; attempt++) {
                AidRuntimeRunStep coordinate = new AidRuntimeRunStep();
                coordinate.setStepIndex(item.getStepIndex());
                coordinate.setAttemptNo(attempt);
                AidMediaTask receipt = mediaTasks.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getUserId, run.getUserId())
                        .eq(AidMediaTask::getBizTaskType, RUNTIME_BIZ_TASK_TYPE)
                        .eq(AidMediaTask::getBizTaskId, runId)
                        .eq(AidMediaTask::getIdempotencyKey, runtimeCallId(run, coordinate)).last("LIMIT 1"));
                if (receipt == null) continue;
                attempts.add(JSONObject.of("attempt", attempt, "taskId", JsonSafeId.value(receipt.getId()),
                        "status", receipt.getStatus(), "actualCost", receipt.getActualCost(),
                        "billingStatus", receipt.getBillingStatus()));
                if (receipt.getActualCost() != null) actualCost = actualCost.add(receipt.getActualCost());
                if (StrUtil.isNotBlank(receipt.getBillingStatus()) && (attempt == count
                        || !"SUCCEEDED".equals(run.getStatus())
                        || !Set.of("SUCCESS","FAILED").contains(receipt.getBillingStatus())))
                    billingStatuses.add(receipt.getBillingStatus());
            }
            projected.put("attempts", attempts);
            projectedSteps.add(projected);
        }
        view.put("steps", projectedSteps);
        view.put("actualCost", actualCost);
        String billingStatus = aggregateBillingStatus(billingStatuses);
        if (billingStatus != null) view.put("billingStatus", billingStatus);
        if (currentMediaTaskId != null)
            view.put("task", generation.task(currentMediaTaskId,
                    GenerationInvocationContext.user(run.getUserId())));
        return view;
    }

    /**
     * Read-only run projection over the existing media-task billing states. An unsettled state wins
     * over terminal states. Successful recovered runs summarize the current attempts, retaining
     * closed historical refunds in attempts rather than presenting them as a current billing failure.
     * Other mixed runs retain FAILED when a tracked task failed/refunded. Per-step states remain the
     * authoritative detail for mixed runs. This method never performs billing or settlement work.
     */
    private static String aggregateBillingStatus(Set<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        for (MediaBillingStatus status : List.of(MediaBillingStatus.REFUNDING,
                MediaBillingStatus.SETTLING, MediaBillingStatus.FROZEN, MediaBillingStatus.INIT,
                MediaBillingStatus.FAILED)) {
            if (statuses.contains(status.name())) return status.name();
        }
        return statuses.size() == 1 && statuses.contains(MediaBillingStatus.SUCCESS.name())
                ? MediaBillingStatus.SUCCESS.name() : null;
    }

    private AidRuntimeRun requireRun(Long runId, Long userId) {
        AidRuntimeRun run = runs.selectById(runId);
        if (run == null || !NORMAL.equals(run.getDelFlag()) || !Objects.equals(run.getUserId(), userId))
            throw new ServiceException("运行不存在");
        requireGenericRuntimeRun(run);
        return run;
    }

    private void requireGenericRuntimeRun(AidRuntimeRun run) {
        if (run == null || !NORMAL.equals(run.getDelFlag()) || run.getSessionId() != null || run.getMemberId() == null
                || run.getVersionId() == null)
            throw new ServiceException("RUNTIME_FAMILY_MISMATCH", 409);
        if ("SKILL".equals(run.getMemberType())) return;
        if (!"AGENT".equals(run.getMemberType()))
            throw new ServiceException("RUNTIME_FAMILY_MISMATCH", 409);
        if (run.getTrialGrantId() != null) {
            requireAgentTrialRuntimeRun(run);
            return;
        }
        AidRuntimeAgent agent = agents.selectById(run.getMemberId());
        AidRuntimeAgentVersion version = agentVersions.selectById(run.getVersionId());
        requireLegacyAgentVersion(agent, version);
    }

    private void requireAgentTrialRuntimeRun(AidRuntimeRun run) {
        AidRuntimeAgent agent = agents.selectById(run.getMemberId());
        AidRuntimeAgentDraft draft = agentDrafts.selectById(run.getVersionId());
        AidRuntimeTestGrant grant = testGrants.peek(run.getTrialGrantId());
        if (agent == null || draft == null || !NORMAL.equals(agent.getDelFlag())
                || !LEGACY_AGENT_FAMILY.equals(agent.getAgentFamily())
                || !Objects.equals(draft.getAgentId(), agent.getId())
                || !Objects.equals(draft.getOwnerUserId(), grant.getAdminUserId())
                || !"CONSUMED".equals(grant.getStatus())
                || !Objects.equals(grant.getRunId(), run.getId())
                || !Objects.equals(grant.getUserId(), run.getUserId())
                || !Objects.equals(grant.getMemberType(), run.getMemberType())
                || !Objects.equals(grant.getMemberId(), run.getMemberId())
                || !Objects.equals(grant.getDraftDigest(), run.getVersionDigest())
                || !Objects.equals(grant.getClientRequestId(), run.getClientRequestId()))
            throw new ServiceException("RUNTIME_TRIAL_AUTHORITY_MISMATCH", 409);
    }

    private void requireLegacyAgentVersion(AidRuntimeAgent agent, AidRuntimeAgentVersion version) {
        if (agent == null || !NORMAL.equals(agent.getDelFlag())
                || !LEGACY_AGENT_FAMILY.equals(agent.getAgentFamily())
                || version == null || !NORMAL.equals(version.getDelFlag())
                || !Objects.equals(version.getAgentId(), agent.getId()))
            throw new ServiceException("AGENT_FAMILY_MISMATCH", 409);
        legacyDefinition(version);
    }

    private LegacyRuntimeAgentRequests.DraftPayload legacyDefinition(AidRuntimeAgentVersion version) {
        return agentAdmin.definition(version);
    }

    private void requireContentAccess(AidRuntimeRun run, Long userId, boolean editing) {
        String[] roles = editing ? new String[] {"OWNER", "EDITOR"}
                : new String[] {"OWNER", "EDITOR", "VIEWER"};
        if (run.getWorkspaceId() != null)
            canvasAccess.requireWorkspace(run.getWorkspaceId(), userId, roles);
        else if (run.getProjectId() != null)
            canvasAccess.requireRole(run.getProjectId(), userId, roles);
    }

    private Long stableMemberId(String type, String code) {
        if ("SKILL".equals(type)) {
            if (SkillRuntimeCapabilities.SCREENPLAY.equals(code))
                throw new ServiceException("旧剧本Skill入口已停止新调用，请使用编剧主Agent");
            AidSkill skill = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                    .select(AidSkill::getId).eq(AidSkill::getSkillCode, code).last("limit 1"));
            if (skill == null) throw new ServiceException("Skill 不存在");
            return skill.getId();
        }
        if ("AGENT".equals(type)) {
            AidRuntimeAgent agent = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                    .select(AidRuntimeAgent::getId).eq(AidRuntimeAgent::getAgentCode, code)
                    .eq(AidRuntimeAgent::getAgentFamily, LEGACY_AGENT_FAMILY).last("limit 1"));
            if (agent == null) throw new ServiceException("Agent 不存在");
            return agent.getId();
        }
        throw new ServiceException("成员类型错误");
    }

    private Member resolve(String type, String code, Long userId) {
        if ("SKILL".equals(type)) {
            AidSkill skill = skills.selectOne(Wrappers.<AidSkill>lambdaQuery()
                    .eq(AidSkill::getSkillCode, code).eq(AidSkill::getDelFlag, NORMAL).last("limit 1"));
            if (skill == null || !NORMAL.equals(skill.getStatus())
                    || !"ENTRYPOINT".equals(skill.getInvocationScope())
                    || !("PUBLIC".equals(skill.getVisibility())
                    || Objects.equals(skill.getOwnerUserId(), userId)))
                throw new ServiceException("Skill 不可用");
            SkillPackageResourceLoader.AuthorizedPackage authorized = resourceLoader.loadAuthorizedVersion(
                    skill.getSkillCode(), skill.getCurrentVersionId(), userId, false);
            skill = authorized.skill();
            AidSkillVersion version = authorized.version();
            if (version == null || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                    || !Objects.equals(version.getSkillId(), skill.getId())
                    || !"PROMPT".equals(version.getExecutorType()))
                throw new ServiceException("Skill 可执行版本不可用");
            String output = SkillModelService.outputKind(version);
            if (!Set.of("TEXT", "IMAGE", "VIDEO").contains(output))
                throw new ServiceException("Skill 输出类型不可用");
            String master = resourceLoader.readAuthorizedResource(skill.getSkillCode(), version.getId(),
                    "SKILL.md", userId, false);
            String systemPrompt = resourceLoader.readAuthorizedResource(skill.getSkillCode(), version.getId(),
                    "system_prompt.md", userId, false);
            StringBuilder instruction = new StringBuilder(master).append("\n\n").append(systemPrompt);
            for (SkillPackageResourceLoader.SelectedResource resource :
                    resourceLoader.select(skill.getSkillCode(), version, "", "")) {
                if (instruction.length() + resource.content().length() > 100000)
                    throw new ServiceException("Skill 参考资源超过上下文上限");
                instruction.append("\n\n参考资源 ").append(resource.resourceKey()).append(":\n")
                        .append(resource.content());
            }
            return new Member("SKILL", skill.getId(), version.getId(), version.getPackageDigest(),
                    instruction.toString(), output, false, 1, 1, 600, 128000,
                    List.of(defaultStep()), Map.of(), version);
        }
        if (!"AGENT".equals(type)) throw new ServiceException("成员类型错误");
        AidRuntimeAgent agent = agents.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .eq(AidRuntimeAgent::getAgentCode, code)
                .eq(AidRuntimeAgent::getAgentFamily, LEGACY_AGENT_FAMILY)
                .eq(AidRuntimeAgent::getDelFlag, NORMAL).last("limit 1"));
        if (agent == null || !NORMAL.equals(agent.getStatus())
                || !("PUBLIC".equals(agent.getVisibility())
                || Objects.equals(agent.getOwnerUserId(), userId)))
            throw new ServiceException("Agent 不可用");
        AidRuntimeAgentVersion version = agentVersions.selectById(agent.getCurrentVersionId());
        if (version == null || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                || !Objects.equals(version.getAgentId(), agent.getId()))
            throw new ServiceException("Agent 可执行版本不可用");
        requireLegacyAgentVersion(agent, version);
        LegacyRuntimeAgentRequests.DraftPayload definition;
        try { definition = JSON.parseObject(version.getDefinitionJson(), LegacyRuntimeAgentRequests.DraftPayload.class); }
        catch (RuntimeException error) { throw new ServiceException("Agent 定义损坏"); }
        Map<String, Long> childVersions = new LinkedHashMap<>();
        List<AidRuntimeAgentSkillRef> pinned = skillRefs.selectList(
                Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, version.getId()));
        if (pinned.size() != new LinkedHashSet<>(definition.getAllowedSkillCodes()).size())
            throw new ServiceException("Agent 固定 Skill 版本缺失");
        Set<Long> pinnedVersionIds = new LinkedHashSet<>();
        for (AidRuntimeAgentSkillRef ref : pinned) pinnedVersionIds.add(ref.getSkillVersionId());
        Map<Long, AidSkillVersion> pinnedVersions = resourceLoader.loadVersions(pinnedVersionIds);
        for (AidRuntimeAgentSkillRef ref : pinned) {
            AidSkill child = skills.selectById(ref.getSkillId());
            AidSkillVersion childVersion = pinnedVersions.get(ref.getSkillVersionId());
            if (child == null || !NORMAL.equals(child.getStatus())
                    || !NORMAL.equals(child.getDelFlag())
                    || !Objects.equals(child.getSkillCode(), ref.getSkillCode())
                    || childVersion == null || !NORMAL.equals(childVersion.getStatus())
                    || !NORMAL.equals(childVersion.getDelFlag())
                    || !Objects.equals(childVersion.getSkillId(), child.getId())
                    || !Objects.equals(childVersion.getPackageDigest(), ref.getPackageDigest())
                    || !("PUBLIC".equals(child.getVisibility())
                    || Objects.equals(child.getOwnerUserId(), userId)))
                throw new ServiceException("Agent 依赖的 Skill 不可用");
            childVersions.put(ref.getSkillCode(), ref.getSkillVersionId());
        }
        List<LegacyRuntimeAgentRequests.Step> configured = definition.getSteps();
        return new Member("AGENT", agent.getId(), version.getId(), version.getPackageDigest(),
                version.getInstruction(), definition.getOutputKind(), Boolean.TRUE.equals(definition.getClarificationEnabled()),
                definition.getMaxSteps(), definition.getMaxTaskCount(), definition.getMaxDurationSeconds(),
                definition.getMaxEstimatedTokens(), configured.isEmpty() ? List.of(defaultStep()) : configured,
                childVersions, version);
    }

    private String chooseModel(Member member, String requested) {
        if ("SKILL".equals(member.type()))
            return modelService.resolve((AidSkillVersion) member.version(), requested).modelCode();
        LegacyRuntimeAgentRequests.DraftPayload definition = legacyDefinition(
                (AidRuntimeAgentVersion) member.version());
        String chosen = StrUtil.isBlank(requested) ? definition.getDefaultModelCode() : requested;
        if (!definition.getSelectableModelCodes().contains(chosen)
                || !modelService.activeModels(List.of(chosen), definition.getOutputKind()).containsKey(chosen))
            throw new ServiceException("Agent 模型不可用或不在可选范围");
        return chosen;
    }

    private static LegacyRuntimeAgentRequests.Step defaultStep() {
        LegacyRuntimeAgentRequests.Step step = new LegacyRuntimeAgentRequests.Step();
        step.setStepKey("generate");
        step.setKind("MODEL");
        return step;
    }

    private static String stepPrompt(JSONObject frozen, LegacyRuntimeAgentRequests.Step step, String prior) {
        JSONObject input = frozen.getJSONObject("request");
        String base = StrUtil.blankToDefault(input.getString("prompt"), "");
        StringBuilder text = new StringBuilder();
        if (StrUtil.isNotBlank(step.getInstruction())) text.append(step.getInstruction()).append("\n\n");
        text.append(base);
        if (StrUtil.isNotBlank(prior)) text.append("\n\n上一步输出：\n").append(prior);
        if (text.length() > 100000) throw new ServiceException("运行上下文超过上限");
        return text.toString();
    }

    private static String outputText(String outputJson) {
        if (StrUtil.isBlank(outputJson)) return null;
        try {
            JSONObject output = JSON.parseObject(outputJson);
            if (!"TEXT".equals(StrUtil.blankToDefault(output.getString("mediaType"), "TEXT"))) return null;
            return output.getString("textContent");
        } catch (RuntimeException error) { return null; }
    }

    private static String resultMediaType(String outputJson) {
        if (StrUtil.isBlank(outputJson)) return "TEXT";
        try { return StrUtil.blankToDefault(JSON.parseObject(outputJson).getString("mediaType"), "TEXT"); }
        catch (RuntimeException error) { return "INVALID"; }
    }

    private static String taskStatus(String status) {
        if ("SUCCEEDED".equals(status)) return "SUCCEEDED";
        if ("FAILED".equals(status)) return "FAILED";
        if (isMediaCanceled(status)) return "CANCELED";
        return "RUNNING";
    }

    private static boolean isMediaCanceled(String status) {
        return "CANCELLED".equals(status) || "CANCELED".equals(status);
    }

    private static String limit(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private record Member(String type, Long id, Long versionId, String digest, String instruction,
                          String outputKind, boolean clarificationEnabled, int maxSteps,
                          int maxTaskCount, int maxDurationSeconds, int maxEstimatedTokens,
                          List<LegacyRuntimeAgentRequests.Step> steps, Map<String, Long> childVersions,
                          Object version) { }

    private record SubmissionEvidence(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task) { }

    private record CancelDecision(String blockedMessage) {
        private static CancelDecision completed() { return new CancelDecision(null); }
        private static CancelDecision blocked(String message) { return new CancelDecision(message); }
    }
}
