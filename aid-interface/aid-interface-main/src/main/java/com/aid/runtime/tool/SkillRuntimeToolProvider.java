package com.aid.runtime.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.runtime.assistant.AssistantApprovedSkillBindingProofService;
import com.aid.runtime.assistant.NativeAssistantLoopExecutor;
import com.aid.runtime.assistant.NativeAssistantDiscoveryPlanService;
import com.aid.runtime.assistant.NativeAssistantPlanReadPolicy;
import com.aid.runtime.assistant.NativeSkillAuthoringContract;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.aid.runtime.assistant.execution.AssistantCompletedToolScopeVerifier;
import com.aid.runtime.assistant.execution.AssistantPackageReadReceiptService;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.service.SkillAuthoringStorageService;
import com.aid.skill.runtime.SkillDraftProposalReceiptPolicy;
import com.aid.skill.service.SkillPackageResourceLoader;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Controlled, data-only access to published Skill packages and trusted private-draft creation. */
@Component
@RequiredArgsConstructor
public class SkillRuntimeToolProvider implements RuntimeToolProvider {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleRegistry;
    public static final String RESOLVE_VERSION = "aid.skill.resolve-version";
    public static final String READ_RESOURCE = "aid.skill.read-resource";
    public static final String PROPOSE_DRAFT = "aid.skill.propose-draft";
    public static final String PROPOSE_DRAFT_V2 = NativeSkillAuthoringContract.PROPOSE_DRAFT_V2;
    private static final String VERSION = "1.0.0";
    private static final String MANIFEST_SCHEMA = "assistant-context-manifest/v1";
    private static final String AUTHORING_SKILL_CODE = NativeSkillAuthoringContract.SKILL_CODE;
    private static final Map<String, String> AUTHORING_BRANCHES = NativeSkillAuthoringContract.SOURCE_BRANCHES;
    private static final Set<String> READABLE_PATHS = Set.of("SKILL.md", "system_prompt.md");

    private static final String RESOLVE_INPUT = """
            {"type":"object","required":["skillCode"],"additionalProperties":false,"properties":{
              "skillCode":{"type":"string","pattern":"^[a-z][a-z0-9_]{1,63}$"},
              "versionId":{"type":"integer","minimum":1}
            }}""";
    private static final String RESOLVE_OUTPUT = """
            {"type":"object","required":["skillCode","skillVersionId","versionCode","packageDigest","ownerType","visibility","invocationScope","executorType","masterPath","systemPromptPath","resourcePaths"],"properties":{
              "skillCode":{"type":"string"},"skillVersionId":{"type":"integer"},"versionCode":{"type":"string"},
              "packageDigest":{"type":"string"},"ownerType":{"type":"string"},"visibility":{"type":"string"},
              "invocationScope":{"type":"string"},"executorType":{"type":"string"},
              "masterPath":{"type":"string"},"systemPromptPath":{"type":"string"},
              "resourcePaths":{"type":"array","items":{"type":"string"}}
            }}""";
    private static final String READ_INPUT = """
            {"type":"object","required":["skillCode","versionId","path"],"additionalProperties":false,"properties":{
              "skillCode":{"type":"string","pattern":"^[a-z][a-z0-9_]{1,63}$"},
              "versionId":{"type":"integer","minimum":1},
              "path":{"type":"string","pattern":"^(SKILL\\.md|system_prompt\\.md|references/[a-z0-9][a-z0-9._/-]{0,180})$"},
              "masterDigest":{"type":"string","pattern":"^[0-9a-f]{64}$"}
            }}""";
    private static final String READ_OUTPUT = """
            {"type":"object","required":["skillCode","skillVersionId","packageDigest","path","resourceDigest","content"],"properties":{
              "skillCode":{"type":"string"},"skillVersionId":{"type":"integer"},"packageDigest":{"type":"string"},
              "path":{"type":"string"},"resourceDigest":{"type":"string"},"content":{"type":"string"}
            }}""";
    private static final String PROPOSE_INPUT = """
            {"type":"object","required":["skillCode","name","description","sourceMode","skillMarkdown","systemPrompt","inputSchema","outputSchema","defaultModelCode","selectableModelCodes","resources"],"additionalProperties":false,"properties":{
              "skillCode":{"type":"string","pattern":"^[a-z][a-z0-9_]{1,63}$"},
              "name":{"type":"string","minLength":1,"maxLength":100},
              "description":{"type":"string","maxLength":1000},
              "iconUrl":{"type":"string","maxLength":500},
              "sourceMode":{"type":"string","enum":["SCRATCH","CONVERSATION","CANVAS"]},
              "sourceRefs":{"type":"array","maxItems":32,"items":{"type":"object","required":["kind","canonicalRef"],"additionalProperties":false,"properties":{"kind":{"type":"string","enum":["FREE_CANVAS","CANVAS_RESOURCE","FLOW_ENTITY","SCRIPT","DOCUMENT"]},"canonicalRef":{"type":"object"}}}},
              "skillMarkdown":{"type":"string","minLength":1,"maxLength":100000},
              "systemPrompt":{"type":"string","minLength":1,"maxLength":100000},
              "inputSchema":{"type":"object"},"outputSchema":{"type":"object"},"definition":{"type":"object"},
              "defaultModelCode":{"type":"string","minLength":1,"maxLength":100},
              "selectableModelCodes":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"string","minLength":1,"maxLength":100}},
              "maxOutputTokens":{"type":"integer","minimum":1,"maximum":1600000},
              "contextWindowTokens":{"type":"integer","minimum":1024,"maximum":1600000},
              "safetyMarginTokens":{"type":"integer","minimum":0,"maximum":1600000},
              "resources":{"type":"array","maxItems":64,"items":{"type":"object","required":["key","path","content"],"additionalProperties":false,"properties":{
                "key":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,99}$"},
                "path":{"type":"string","pattern":"^references/[a-z0-9][a-z0-9._/-]{0,180}$"},
                "mimeType":{"type":"string","maxLength":100},"content":{"type":"string","minLength":1,"maxLength":102400},"route":{"type":"object"}
              }}}
            }}""";
    // Fastjson BooleanSchema validates only the value type; enum-only selects EnumSchema and keeps published fixed to false.
    private static final String PROPOSE_OUTPUT = """
            {"type":"object","required":["skillId","draftId","draftDigest","visibility","status","published"],"properties":{
              "skillId":{"type":"integer"},"draftId":{"type":"integer"},"draftDigest":{"type":"string"},
              "visibility":{"type":"string","enum":["PRIVATE"]},"status":{"type":"string","enum":["DRAFT"]},"published":{"enum":[false]},
              "sourceMode":{"type":"string"},"authorizedSourceCount":{"type":"integer"},
              "creatorSkillVersionId":{"type":"integer"},"creatorPackageDigest":{"type":"string"},
              "creatorMasterDigest":{"type":"string"}
            }}""";

    private final SkillPackageResourceLoader packages;
    private final SkillAuthoringStorageService authoringStorage;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeAgentVersionMapper agentVersions;
    private final AidRuntimeAgentMapper agents;
    private final AssistantApprovedSkillBindingProofService approvedSkillBindings;
    private final AidRuntimeAgentSkillRefMapper agentSkillRefs;
    private final AidRuntimeRunStepMapper runtimeSteps;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper skillVersions;
    private final AidSkillRunMapper skillRuns;
    private final AidSkillRunStepMapper skillRunSteps;
    private final AssistantCompletedToolScopeVerifier completedToolScopes;
    private final AssistantPackageReadReceiptService packageReadReceipts;
    private final SkillRuntimeEntrypointReadinessService skillReadiness;
    private final RuntimeOperationSubmissionGuard submissionGuard;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(RESOLVE_VERSION, "Resolve Skill version",
                        "Resolve one authorized published Skill to a fixed immutable version. Returns metadata only; read SKILL.md explicitly before reading a relevant reference.",
                        RESOLVE_INPUT, RESOLVE_OUTPUT, "READ", "REQUEST_DIGEST"),
                descriptor(READ_RESOURCE, "Read Skill package resource",
                        "Read one authorized standard Skill package file. Read SKILL.md first; later reads are bound to that verified server receipt. masterDigest is compatibility metadata and never grants access.",
                        READ_INPUT, READ_OUTPUT, "READ", "REQUEST_DIGEST"),
                descriptor(PROPOSE_DRAFT, "Save private Skill draft",
                        "Save a source-authorized proposal as an initial USER PRIVATE draft. This never publishes or activates the Skill. "
                                + NativeSkillAuthoringContract.storageInstruction(),
                        PROPOSE_INPUT, PROPOSE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY"),
                descriptor(PROPOSE_DRAFT_V2, "Save private Skill draft with explicit execution contract",
                        "保存已确认的私人Skill草稿，不发布、不启用。definition及definition.outputKind必填：文字方法明确TEXT，直接图片包明确IMAGE并提供capabilityCode。"
                                + "不能从正文推断或补齐执行类型。普通文字报告仅配置输出样例；需要业务JSON时同时配置字段及合法样例。"
                                + NativeSkillAuthoringContract.storageInstruction(),
                        SkillDraftProposalInputContract.schema(PROPOSE_INPUT), PROPOSE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY"));
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null || context.getUserId() == null || context.getUserId() <= 0) {
            return RuntimeToolAvailability.unavailable("AUTHENTICATED_USER_REQUIRED");
        }
        if (NativeSkillAuthoringContract.isProposalTool(descriptor.code()) && !"UNIVERSAL".equals(context.getModuleCode())) {
            return RuntimeToolAvailability.unavailable("UNIVERSAL_AGENT_REQUIRED");
        }
        if (NativeSkillAuthoringContract.isProposalTool(descriptor.code())
                && (context.getWorkspaceId() == null || context.getWorkspaceId() <= 0)) {
            return RuntimeToolAvailability.unavailable("CANVAS_WORKSPACE_REQUIRED");
        }
        if (!(moduleRegistry == null ? Set.of("UNIVERSAL", "SCREENWRITER", "SPATIAL_PREVIS").contains(context.getModuleCode()) : moduleRegistry.moduleCodes().contains(context.getModuleCode()))) {
            return RuntimeToolAvailability.unavailable("ASSISTANT_MODULE_UNSUPPORTED");
        }
        return RuntimeToolAvailability.enabled();
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return descriptor != null && NativeSkillAuthoringContract.isProposalTool(descriptor.code())
                ? OutputReferenceKind.DURABLE_RECEIPT : OutputReferenceKind.OBSERVATION;
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (invocation != null && READ_RESOURCE.equals(invocation.getToolCode())) {
            JSONObject input = object(invocation.getArgumentsJson());
            String path = input.getString("path");
            if (path == null || !READABLE_PATHS.contains(path) && !safeReference(path)) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_SKILL_RESOURCE_PATH_INVALID);
            }
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getUserId() == null || invocation.getUserId() <= 0) {
            throw new ServiceException("Skill工具调用身份无效");
        }
        JSONObject input = object(invocation.getArgumentsJson());
        return switch (invocation.getToolCode()) {
            case RESOLVE_VERSION -> resolveResult(invocation, input);
            case READ_RESOURCE -> read(invocation, input);
            case PROPOSE_DRAFT, PROPOSE_DRAFT_V2 -> throw new ServiceException("SKILL_DRAFT_PREPARED_EXECUTION_REQUIRED");
            default -> throw new IllegalArgumentException("Unsupported Skill runtime tool");
        };
    }

    private RuntimeToolResult resolveResult(RuntimeToolInvocation invocation, JSONObject input) {
        try {
            return RuntimeToolResult.succeeded(resolve(invocation, input));
        } catch (SkillResolveRejectedException rejected) {
            return failed(rejected.code(), rejected.summary());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext) {
        if (billingBudgetContext != null) {
            throw new ServiceException("SKILL_DRAFT_PREPARED_IDENTITY_INVALID");
        }
        if (!NativeSkillAuthoringContract.isProposalTool(prepared == null ? null : prepared.getToolCode())) {
            return RuntimeToolProvider.super.executePrepared(prepared, null);
        }
        requirePreparedProposal(prepared);
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(submissionClaim(prepared));
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            return durableProposalReceipt(prepared);
        }
        try {
            RuntimeToolInvocation invocation = invocation(prepared);
            return RuntimeToolResult.succeeded(propose(invocation, object(invocation.getArgumentsJson())));
        } catch (MediaSubmissionNotEnteredException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // The new submission claim and the private identity/draft share this transaction. A
            // local failure therefore proves that no draft was durably accepted for this attempt.
            throw new MediaSubmissionNotEnteredException("SKILL_DRAFT_NOT_ENTERED", failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        if (!NativeSkillAuthoringContract.isProposalTool(prepared == null ? null : prepared.getToolCode())) {
            return RuntimeToolProvider.super.reconcilePrepared(prepared);
        }
        requirePreparedProposal(prepared);
        return durableProposalReceipt(prepared);
    }

    private String resolve(RuntimeToolInvocation invocation, JSONObject input) {
        String skillCode = input.getString("skillCode");
        Long versionId = resolveDiscoveryVersion(invocation, skillCode, input.getLong("versionId"));
        AuthorizedAccess access = authorizedAccess(invocation, skillCode, versionId);
        SkillPackageResourceLoader.AuthorizedPackage authorized = access.packages();
        AidSkill skill = authorized.skill();
        AidSkillVersion version = authorized.version();
        JSONObject manifest = object(version.getManifestJson());
        LinkedHashSet<String> resourcePaths = new LinkedHashSet<>();
        JSONArray resources = manifest.getJSONArray("resources");
        if (resources != null) {
            for (Object raw : resources) {
                if (raw instanceof JSONObject item && safeReference(item.getString("path"))) {
                    resourcePaths.add(item.getString("path"));
                }
            }
        }
        JSONObject output = new JSONObject();
        output.put("skillCode", skill.getSkillCode());
        output.put("skillVersionId", version.getId());
        output.put("versionCode", version.getVersionCode());
        output.put("packageDigest", version.getPackageDigest());
        output.put("ownerType", skill.getOwnerType());
        output.put("visibility", skill.getVisibility());
        output.put("invocationScope", version.getInvocationScope());
        output.put("executorType", version.getExecutorType());
        output.put("masterPath", "SKILL.md");
        output.put("systemPromptPath", "system_prompt.md");
        output.put("resourcePaths", List.copyOf(resourcePaths));
        return output.toJSONString();
    }

    /** Resolve only the exact Skill already frozen on this Agent or selected on this run. */
    private Long resolveDiscoveryVersion(RuntimeToolInvocation invocation, String skillCode,
                                         Long requestedVersionId) {
        AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved =
                approvedSkillBindings.requirePersistedApproved(invocation.getRunId(),
                        invocation.getUserId(), invocation.getPlanRevision());
        JSONObject definition = object(approved.plan().getDefinitionJson());
        if (!NativeAssistantDiscoveryPlanService.isDiscovery(definition)) return requestedVersionId;
        if (StrUtil.isBlank(skillCode))
            throw rejectedResolve("SKILL_RESOLUTION_UNAUTHORIZED", "当前运行未授权该Skill");

        List<AidRuntimeAgentSkillRef> fixed = agentSkillRefs.selectList(Wrappers
                .<AidRuntimeAgentSkillRef>lambdaQuery()
                .eq(AidRuntimeAgentSkillRef::getAgentVersionId, approved.run().getVersionId())
                .eq(AidRuntimeAgentSkillRef::getSkillCode, skillCode));
        JSONObject request = object(approved.run().getRequestJson());
        return selectDiscoveryVersion(skillCode, requestedVersionId, fixed,
                request.getJSONObject("_selectedSkill"));
    }

    private static Long selectDiscoveryVersion(String skillCode, Long requestedVersionId,
                                               List<AidRuntimeAgentSkillRef> fixed,
                                               JSONObject selected) {
        Map<Long, String> candidates = new LinkedHashMap<>();
        if (fixed == null) fixed = List.of();
        for (AidRuntimeAgentSkillRef ref : fixed) {
            if (ref == null || !Objects.equals(skillCode, ref.getSkillCode())
                    || ref.getSkillVersionId() == null || ref.getSkillVersionId() <= 0
                    || StrUtil.isBlank(ref.getPackageDigest())
                    || !ref.getPackageDigest().matches("^[0-9a-f]{64}$"))
                throw rejectedResolve("SKILL_RESOLUTION_UNAUTHORIZED", "当前运行的Skill固定身份无效");
            String existing = candidates.putIfAbsent(ref.getSkillVersionId(), ref.getPackageDigest());
            if (existing != null && !Objects.equals(existing, ref.getPackageDigest()))
                throw rejectedResolve("SKILL_RESOLUTION_AMBIGUOUS", "当前运行的Skill固定版本不唯一");
        }

        if (selected != null && Objects.equals(skillCode, selected.getString("code"))
                && supportedSelectedRoute(selected)) {
            Long selectedVersionId = selected.getLong("versionId");
            String selectedDigest = selected.getString("packageDigest");
            if (selectedVersionId == null || selectedVersionId <= 0
                    || StrUtil.isBlank(selectedDigest) || !selectedDigest.matches("^[0-9a-f]{64}$"))
                throw rejectedResolve("SKILL_RESOLUTION_UNAUTHORIZED", "当前运行所选Skill身份无效");
            String existing = candidates.putIfAbsent(selectedVersionId, selectedDigest);
            if (existing != null && !Objects.equals(existing, selectedDigest))
                throw rejectedResolve("SKILL_RESOLUTION_AMBIGUOUS", "当前运行的Skill固定版本不唯一");
        }

        if (requestedVersionId != null)
            candidates.entrySet().removeIf(entry -> !Objects.equals(entry.getKey(), requestedVersionId));
        if (candidates.isEmpty())
            throw rejectedResolve("SKILL_RESOLUTION_UNAUTHORIZED", "当前运行未授权该Skill固定版本");
        if (candidates.size() != 1)
            throw rejectedResolve("SKILL_RESOLUTION_AMBIGUOUS", "当前运行的Skill固定版本不唯一");
        return candidates.keySet().iterator().next();
    }

    private static SkillResolveRejectedException rejectedResolve(String code, String summary) {
        return new SkillResolveRejectedException(code, summary);
    }

    private RuntimeToolResult read(RuntimeToolInvocation invocation, JSONObject input) {
        String skillCode = input.getString("skillCode");
        Long versionId = input.getLong("versionId");
        String path = input.getString("path");
        if (path == null || !READABLE_PATHS.contains(path) && !safeReference(path)) {
            return failed("TOOL_PREPARATION_SKILL_RESOURCE_PATH_INVALID", "Skill资源路径无效");
        }
        AuthorizedAccess access = authorizedAccess(invocation, skillCode, versionId);
        SkillPackageResourceLoader.AuthorizedPackage authorized = access.packages();
        AidSkillVersion version = authorized.version();
        if (!Objects.equals(versionId, version.getId())) throw new ServiceException("Skill版本已变化");
        String master = packages.readAuthorizedResource(skillCode, versionId, "SKILL.md",
                invocation.getUserId(), access.admittedRun());
        String masterDigest = SecureUtil.sha256(master);
        if (!"SKILL.md".equals(path)) {
            final boolean masterRead;
            try {
                masterRead = hasVerifiedMasterRead(invocation, access, skillCode,
                        versionId, version.getPackageDigest(), master, masterDigest);
            } catch (AssistantPackageReadReceiptService.InvalidReceiptException invalid) {
                return failed("PACKAGE_READ_RECEIPT_INVALID", "固定包读取回执无效，请重新开始本次任务");
            }
            if (!masterRead)
                return failed("SKILL_MASTER_READ_REQUIRED", "读取Skill子文件前必须先读取同一固定版本的主文件");
        }
        String content = "SKILL.md".equals(path) ? master
                : packages.readAuthorizedResource(skillCode, versionId, path,
                        invocation.getUserId(), access.admittedRun());
        JSONObject output = new JSONObject();
        output.put("skillCode", skillCode);
        output.put("skillVersionId", version.getId());
        output.put("packageDigest", version.getPackageDigest());
        output.put("path", path);
        output.put("resourceDigest", SecureUtil.sha256(content));
        output.put("content", content);
        return RuntimeToolResult.succeeded(output.toJSONString());
    }

    private boolean hasVerifiedMasterRead(RuntimeToolInvocation invocation, AuthorizedAccess access,
                                          String skillCode, Long versionId, String packageDigest,
                                          String master, String masterDigest) {
        if (invocation.getRuntimeStepId() == null)
            throw new AssistantPackageReadReceiptService.InvalidReceiptException(
                    "PACKAGE_READ_RECEIPT_STEP_INVALID");
        for (AssistantPackageReadReceiptService.VerifiedPackageRead read :
                packageReadReceipts.contextReadsBefore(invocation.getUserId(), access.run(),
                        invocation.getRuntimeStepId())) {
            JSONObject output = read.observation();
            if (READ_RESOURCE.equals(read.toolCode())
                    && Objects.equals(skillCode, output.getString("skillCode"))
                    && Objects.equals(versionId, output.getLong("skillVersionId"))
                    && Objects.equals(packageDigest, output.getString("packageDigest"))
                    && "SKILL.md".equals(output.getString("path"))
                    && Objects.equals(masterDigest, output.getString("resourceDigest"))
                    && Objects.equals(master, output.getString("content"))) return true;
        }
        return false;
    }

    private RuntimeToolResult failed(String errorCode, String safeSummary) {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED");
        result.setErrorCode(errorCode);
        result.setSafeSummary(safeSummary);
        result.setRetryable(false);
        return result;
    }

    private AuthorizedAccess authorizedAccess(RuntimeToolInvocation invocation,
                                              String skillCode, Long versionId) {
        AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved =
                approvedSkillBindings.requirePersistedApproved(invocation.getRunId(),
                        invocation.getUserId(), invocation.getPlanRevision());
        JSONObject approvedDefinition = object(approved.plan().getDefinitionJson());
        if (NativeAssistantDiscoveryPlanService.isDiscovery(approvedDefinition)) {
            if (versionId == null) throw new ServiceException("按需读取必须指定当前Agent固定Skill版本");
            AidRuntimeAgentSkillRef fixed = agentSkillRefs.selectOne(Wrappers
                    .<AidRuntimeAgentSkillRef>lambdaQuery()
                    .eq(AidRuntimeAgentSkillRef::getAgentVersionId, approved.run().getVersionId())
                    .eq(AidRuntimeAgentSkillRef::getSkillCode, skillCode)
                    .eq(AidRuntimeAgentSkillRef::getSkillVersionId, versionId)
                    .last("LIMIT 1"));
            if (fixed == null || StrUtil.isBlank(fixed.getPackageDigest())) {
                return selectedDiscoveryAccess(invocation, approved, skillCode, versionId);
            }
            SkillPackageResourceLoader.AuthorizedPackage authorized = packages.loadAuthorizedVersion(
                    skillCode, versionId, invocation.getUserId(), true);
            if (!Objects.equals(authorized.skill().getSkillCode(), skillCode)
                    || !Objects.equals(authorized.version().getId(), versionId)
                    || !Objects.equals(authorized.version().getPackageDigest(), fixed.getPackageDigest()))
                throw new ServiceException("Skill固定版本运行证明无效");
            return new AuthorizedAccess(authorized, true, approved.run());
        }
        try {
            return new AuthorizedAccess(packages.loadAuthorizedVersion(
                    skillCode, versionId, invocation.getUserId(), false), false, approved.run());
        } catch (RuntimeException ordinaryDenied) {
            if (versionId == null) throw ordinaryDenied;
            String approvedDigest = requireAdmittedToolVersion(invocation, skillCode, versionId);
            SkillPackageResourceLoader.AuthorizedPackage authorized = packages.loadAuthorizedVersion(
                    skillCode, versionId, invocation.getUserId(), true);
            if (!Objects.equals(authorized.skill().getSkillCode(), skillCode)
                    || !Objects.equals(authorized.version().getId(), versionId)
                    || !Objects.equals(authorized.version().getPackageDigest(), approvedDigest)) {
                throw new ServiceException("Skill固定版本运行证明无效");
            }
            return new AuthorizedAccess(authorized, true, approved.run());
        }
    }

    private static boolean supportedSelectedRoute(JSONObject selected) {
        return selected != null && ("TEXT".equals(selected.getString("outputKind"))
                && "SKILL".equals(selected.getString("executionRoute"))
                || "IMAGE".equals(selected.getString("outputKind"))
                && "TOOL_IMAGE".equals(selected.getString("executionRoute")));
    }

    /** Authorizes only the exact accessible Skill frozen on this run. */
    private AuthorizedAccess selectedDiscoveryAccess(
            RuntimeToolInvocation invocation,
            AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved,
            String skillCode,
            Long versionId) {
        AidRuntimeRun run = approved.run();
        JSONObject request = object(run.getRequestJson());
        JSONObject selected = request.getJSONObject("_selectedSkill");
        String selectedVersion = selected == null ? null : selected.getString("versionId");
        String selectedDigest = selected == null ? null : selected.getString("packageDigest");
        if (selected == null || !Objects.equals(skillCode, selected.getString("code"))
                || !Objects.equals(String.valueOf(versionId), selectedVersion)
                || StrUtil.isBlank(selectedDigest) || !selectedDigest.matches("^[0-9a-f]{64}$")
                || !supportedSelectedRoute(selected)) {
            throw new ServiceException("所选Skill固定版本无效");
        }
        SkillPackageResourceLoader.AuthorizedPackage authorized = packages.loadAuthorizedVersion(
                skillCode, versionId, invocation.getUserId(), true);
        AidSkill identity = authorized.skill();
        AidSkillVersion version = authorized.version();
        boolean liveOwnedEntrypoint = identity != null && version != null
                && Objects.equals(identity.getSkillCode(), skillCode)
                && ("USER".equals(identity.getOwnerType())
                    && Objects.equals(identity.getOwnerUserId(), invocation.getUserId())
                    || "IMAGE".equals(selected.getString("outputKind"))
                    && "PLATFORM".equals(identity.getOwnerType())
                    && "PUBLIC".equals(identity.getVisibility())
                    && "PUBLIC".equals(version.getVisibility())
                    && "PUBLISHED".equals(version.getPublishStatus()))
                && ("PRIVATE".equals(identity.getVisibility()) || "PUBLIC".equals(identity.getVisibility()))
                && "ENTRYPOINT".equals(identity.getInvocationScope())
                && "0".equals(identity.getStatus()) && "0".equals(identity.getDelFlag())
                && Objects.equals(version.getId(), versionId)
                && Objects.equals(version.getSkillId(), identity.getId())
                && Objects.equals(version.getPackageDigest(), selectedDigest)
                && ("PRIVATE".equals(version.getVisibility()) || "PUBLIC".equals(version.getVisibility()))
                && ("PRIVATE".equals(version.getPublishStatus())
                || "PUBLISHED".equals(version.getPublishStatus()))
                && "ENTRYPOINT".equals(version.getInvocationScope())
                && "PROMPT".equals(version.getExecutorType())
                && "0".equals(version.getStatus()) && "0".equals(version.getDelFlag());
        if (!liveOwnedEntrypoint) throw new ServiceException("所选Skill当前不可用");
        if ("IMAGE".equals(selected.getString("outputKind"))) {
            if (!com.aid.skill.service.SkillImageExecutionContract.supportsDefinition(version.getDefinitionJson())
                    || !com.aid.skill.service.SkillImageExecutionContract.supportsSchemas(
                            version.getInputSchemaJson(), version.getOutputSchemaJson()))
                throw new ServiceException("所选Skill图片执行契约无效");
        } else {
            skillReadiness.requireReadyMethod(identity, versionId, selectedDigest,
                    invocation.getUserId(), true);
        }
        return new AuthorizedAccess(authorized, true, run);
    }

    /** Prove exact plan admission before crossing the loader's admittedRun trust boundary. */
    private String requireAdmittedToolVersion(RuntimeToolInvocation invocation,
                                               String skillCode, Long versionId) {
        AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved =
                approvedSkillBindings.requirePersistedApproved(invocation.getRunId(),
                        invocation.getUserId(), invocation.getPlanRevision());
        AidRuntimeAgentSkillRef fixed = agentSkillRefs.selectOne(Wrappers
                .<AidRuntimeAgentSkillRef>lambdaQuery()
                .eq(AidRuntimeAgentSkillRef::getAgentVersionId, approved.run().getVersionId())
                .eq(AidRuntimeAgentSkillRef::getSkillCode, skillCode)
                .eq(AidRuntimeAgentSkillRef::getSkillVersionId, versionId)
                .last("LIMIT 1"));
        // The approved read plan binds the exact running Agent version. A fixed Agent Skill ref is
        // therefore the immutable admission proof for selective package reads even though the
        // discovery plan itself intentionally contains only TOOL steps.
        if (fixed != null && StrUtil.isNotBlank(fixed.getPackageDigest())) return fixed.getPackageDigest();
        String packageDigest = approved.proof().packageDigest(skillCode, versionId);
        if (StrUtil.isBlank(packageDigest)) {
            throw new ServiceException("Skill固定版本未被当前批准计划接纳");
        }
        return packageDigest;
    }

    private String propose(RuntimeToolInvocation invocation, JSONObject input) {
        if (!isUniversalRun(invocation)) {
            throw new ServiceException("技能编制仅允许全能助手调用");
        }
        AidRuntimeRun creatorRun = runs.selectById(invocation.getRunId());
        if (creatorRun == null || !Objects.equals(creatorRun.getUserId(), invocation.getUserId())
                || creatorRun.getWorkspaceId() == null || creatorRun.getWorkspaceId() <= 0) {
            throw new ServiceException("技能编制仅允许从画布创建");
        }
        String sourceMode = input.getString("sourceMode");
        JSONArray sourceRefs = input.getJSONArray("sourceRefs");
        CreatorEvidence creator = requireCreatorEvidence(invocation, sourceMode);
        int creatorSnapshotCount = StrUtil.isNotBlank(creator.branchPath()) ? 2 : 1;
        List<SkillAuthoringStorageService.AuthoringSourceSnapshot> authorizedRefs = new ArrayList<>(
                authorizeSources(invocation.getContextManifestJson(), sourceMode, sourceRefs,
                        creatorSnapshotCount));
        JSONObject creatorRef = new JSONObject();
        creatorRef.put("skillCode", creator.skillCode());
        creatorRef.put("skillVersionId", creator.versionId());
        creatorRef.put("packageDigest", creator.packageDigest());
        creatorRef.put("masterDigest", creator.masterDigest());
        authorizedRefs.add(new SkillAuthoringStorageService.AuthoringSourceSnapshot(
                "CREATOR_SKILL_VERSION", creatorRef.toJSONString(), creator.masterDigest()));
        if (StrUtil.isNotBlank(creator.branchPath()) && StrUtil.isNotBlank(creator.branchDigest())) {
            JSONObject branchRef = new JSONObject();
            branchRef.put("skillCode", creator.skillCode());
            branchRef.put("skillVersionId", creator.versionId());
            branchRef.put("packageDigest", creator.packageDigest());
            branchRef.put("path", creator.branchPath());
            branchRef.put("resourceDigest", creator.branchDigest());
            authorizedRefs.add(new SkillAuthoringStorageService.AuthoringSourceSnapshot(
                    "CREATOR_SKILL_BRANCH", branchRef.toJSONString(), creator.branchDigest()));
        }
        if ("CONVERSATION".equals(sourceMode)) {
            AidRuntimeRun sourceRun = runs.selectById(invocation.getRunId());
            if (sourceRun == null || !Objects.equals(sourceRun.getUserId(), invocation.getUserId()))
                throw new ServiceException("对话来源运行不存在");
            JSONObject canonical = new JSONObject();
            canonical.put("runtimeRunId", invocation.getRunId());
            canonical.put("planRevision", invocation.getPlanRevision());
            canonical.put("creatorStepId", invocation.getRuntimeStepId());
            authorizedRefs.add(new SkillAuthoringStorageService.AuthoringSourceSnapshot(
                    "CONVERSATION_RUN", canonical.toJSONString(), SecureUtil.sha256(
                    StrUtil.blankToDefault(sourceRun.getRequestJson(), "") + "|"
                            + StrUtil.blankToDefault(invocation.getContextManifestJson(), ""))));
        }
        authorizedRefs = List.copyOf(authorizedRefs);

        SkillAdminRequests.CreateRequest identity = new SkillAdminRequests.CreateRequest();
        identity.setSkillCode(input.getString("skillCode"));
        identity.setName(input.getString("name"));
        identity.setDescription(input.getString("description"));
        identity.setIconUrl(input.getString("iconUrl"));
        identity.setVisibility("PRIVATE");
        // A user-authored Skill is a directly invocable package. Universal discovers it through
        // the authorized dynamic catalog; it is deliberately not inserted into global Agent bindings.
        identity.setInvocationScope("ENTRYPOINT");
        identity.setCacheToDisk(false);

        SkillPackageAdminRequests.DraftSaveRequest draft = new SkillPackageAdminRequests.DraftSaveRequest();
        draft.setDefaultModelCode(input.getString("defaultModelCode"));
        draft.setModelCode(input.getString("defaultModelCode"));
        draft.setSelectableModelCodes(strings(input.getJSONArray("selectableModelCodes"), 20));
        draft.setSkillMarkdown(input.getString("skillMarkdown"));
        draft.setSystemPrompt(input.getString("systemPrompt"));
        applyAuthoringExecutionContract(input, draft);
        com.aid.skill.service.SkillContractExamples.validate("inputSchemaJson", draft.getInputSchemaJson());
        com.aid.skill.service.SkillContractExamples.validate("outputSchemaJson", draft.getOutputSchemaJson());
        AidRuntimeRun authoringRun = runs.selectById(invocation.getRunId());
        if (authoringRun != null && com.aid.assistant.service.AssistantAutomaticTextExecution.enabled(
                JSON.parseObject(authoringRun.getRequestJson()))) {
            JSONObject definition = JSON.parseObject(draft.getDefinitionJson());
            if ("TEXT".equals(definition.getString("outputKind"))) {
                JSONObject inputContract = JSON.parseObject(draft.getInputSchemaJson());
                JSONObject outputContract = JSON.parseObject(draft.getOutputSchemaJson());
                JSONObject fields = inputContract.getJSONObject("properties");
                if (fields != null && !fields.isEmpty() && !inputContract.containsKey("examples"))
                    throw new ServiceException("生成的 Skill 缺少输入样例");
                if (!outputContract.containsKey("examples"))
                    throw new ServiceException("生成的 Skill 缺少输出样例");
            }
        }
        draft.setMaxOutputTokens(orDefault(input.getInteger("maxOutputTokens"), 8192));
        draft.setContextWindowTokens(orDefault(input.getInteger("contextWindowTokens"), 128000));
        draft.setSafetyMarginTokens(orDefault(input.getInteger("safetyMarginTokens"), 8192));
        draft.setResources(resources(input.getJSONArray("resources")));
        draft.setRelations(List.of());

        String requestKey = proposalRequestKey(invocation, input);
        SkillAuthoringStorageService.AuthoringDraftReceipt receipt = authoringStorage.createPrivateDraft(
                new SkillAuthoringStorageService.AuthoringDraftCommand(invocation.getUserId(),
                        invocation.getRunId(), invocation.getRuntimeStepId(), requestKey,
                        "runtime-skill-creator", identity, draft, sourceMode, authorizedRefs));
        return proposalOutput(receipt, invocation, input).toJSONString();
    }

    private static void applyAuthoringExecutionContract(JSONObject input,
            SkillPackageAdminRequests.DraftSaveRequest draft) {
        JSONObject definition = input.getJSONObject("definition");
        if (definition == null || !Set.of("TEXT", "IMAGE").contains(
                StrUtil.blankToDefault(definition.getString("outputKind"), ""))) {
            throw new ServiceException("definition.outputKind必须明确为TEXT或IMAGE，不能通过提示词推断执行类型");
        }
        if ("IMAGE".equals(definition.getString("outputKind"))) {
            if (!com.aid.skill.service.SkillImageExecutionContract.supportsDefinition(definition.toJSONString())) {
                throw new ServiceException("definition.capabilityCode必须为text_to_image或image_to_image");
            }
            // Transport schemas belong to the platform, not the generated creative instructions.
            draft.setInputSchemaJson(com.aid.skill.service.SkillImageExecutionContract.withExamples(
                    com.aid.skill.service.SkillImageExecutionContract.INPUT_SCHEMA, requireObject(input, "inputSchema")));
            draft.setOutputSchemaJson(com.aid.skill.service.SkillImageExecutionContract.withExamples(
                    com.aid.skill.service.SkillImageExecutionContract.OUTPUT_SCHEMA, requireObject(input, "outputSchema")));
        } else {
            draft.setInputSchemaJson(requireObject(input, "inputSchema").toJSONString(JSONWriter.Feature.WriteMapNullValue));
            draft.setOutputSchemaJson(requireObject(input, "outputSchema").toJSONString(JSONWriter.Feature.WriteMapNullValue));
        }
        draft.setDefinitionJson(definition.toJSONString());
    }

    private RuntimeToolResult durableProposalReceipt(RuntimeToolPreparedInvocation prepared) {
        RuntimeToolInvocation invocation = invocation(prepared);
        JSONObject input = object(invocation.getArgumentsJson());
        String requestKey = proposalRequestKey(invocation, input);
        SkillAuthoringStorageService.AuthoringDraftReceipt receipt =
                authoringStorage.findPrivateDraftReceipt(requestKey, invocation.getUserId(),
                        invocation.getRunId(), invocation.getRuntimeStepId());
        if (receipt == null) {
            return RuntimeToolResult.unknown("SKILL_DRAFT_RECEIPT_NOT_FOUND", "Skill草稿回执待核实");
        }
        return RuntimeToolResult.succeeded(proposalOutput(receipt, invocation, input).toJSONString());
    }

    private JSONObject proposalOutput(SkillAuthoringStorageService.AuthoringDraftReceipt receipt,
                                      RuntimeToolInvocation invocation, JSONObject input) {
        return SkillDraftProposalReceiptPolicy.project(receipt, invocation.getRunId(),
                invocation.getRuntimeStepId(), proposalRequestKey(invocation, input), input);
    }

    private String proposalRequestKey(RuntimeToolInvocation invocation, JSONObject input) {
        return SkillDraftProposalReceiptPolicy.requestKey(invocation.getRunId(),
                invocation.getRuntimeStepId(), invocation.getIdempotencyKey(), input);
    }

    private void requirePreparedProposal(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || prepared.getRunId() == null || prepared.getUserId() == null
                || prepared.getRuntimeStepId() == null || prepared.getPlanRevision() == null
                || StrUtil.isBlank(prepared.getStableStepId())
                || !NativeSkillAuthoringContract.isProposalTool(prepared.getToolCode())
                || StrUtil.isBlank(prepared.getIdempotencyKey())
                || prepared.getApprovalId() == null
                || StrUtil.isBlank(prepared.getArgumentsJson())
                || !Objects.equals(prepared.getArgumentsDigest(),
                SecureUtil.sha256(prepared.getArgumentsJson()))
                || StrUtil.isBlank(prepared.getPreparedPayloadJson())
                || !Objects.equals(prepared.getPreparedPayloadDigest(),
                SecureUtil.sha256(prepared.getPreparedPayloadJson()))
                || !Objects.equals(prepared.getArgumentsJson(), prepared.getPreparedPayloadJson())) {
            throw new ServiceException("SKILL_DRAFT_RECEIPT_IDENTITY_INVALID");
        }
    }

    private RuntimeOperationSubmissionClaim submissionClaim(RuntimeToolPreparedInvocation prepared) {
        RuntimeOperationSubmissionClaim claim = new RuntimeOperationSubmissionClaim();
        claim.setRunId(prepared.getRunId());
        claim.setUserId(prepared.getUserId());
        claim.setStepId(prepared.getRuntimeStepId());
        claim.setApprovalId(prepared.getApprovalId());
        // The claim identifies the outer runtime step, not the approved business operation.
        // SAVE_PRIVATE_SKILL_DRAFT stays in the prepared payload and approved budget scope.
        claim.setOperationKind("ASSISTANT_TOOL");
        claim.setIdempotencyKey(prepared.getIdempotencyKey());
        claim.setRunLease(prepared.getRunLease());
        claim.setStepLease(prepared.getStepLease());
        return claim;
    }

    private RuntimeToolInvocation invocation(RuntimeToolPreparedInvocation prepared) {
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId());
        invocation.setUserId(prepared.getUserId());
        invocation.setRuntimeStepId(prepared.getRuntimeStepId());
        invocation.setPlanRevision(prepared.getPlanRevision());
        invocation.setStableStepId(prepared.getStableStepId());
        invocation.setAttemptNo(prepared.getAttemptNo());
        invocation.setOperationKind(prepared.getOperationKind());
        invocation.setToolCode(prepared.getToolCode());
        invocation.setContractVersion(prepared.getContractVersion());
        invocation.setSchemaDigest(prepared.getSchemaDigest());
        invocation.setArgumentsJson(prepared.getPreparedPayloadJson());
        invocation.setArgumentsDigest(prepared.getPreparedPayloadDigest());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        invocation.setApprovalId(prepared.getApprovalId());
        invocation.setApprovedScopeDigest(prepared.getApprovedScopeDigest());
        invocation.setIdempotencyKey(prepared.getIdempotencyKey());
        invocation.setLease(prepared.getStepLease());
        invocation.setOperationScope(prepared.getOperationScope());
        return invocation;
    }

    private CreatorEvidence requireCreatorEvidence(RuntimeToolInvocation invocation, String sourceMode) {
        AidRuntimeRunStep current = runtimeSteps.selectById(invocation.getRuntimeStepId());
        if (current == null || !Objects.equals(current.getRunId(), invocation.getRunId())
                || !Objects.equals(current.getPlanRevision(), invocation.getPlanRevision())
                || !NativeSkillAuthoringContract.isProposalTool(current.getToolCode())
                || !Objects.equals(current.getToolCode(), invocation.getToolCode())) {
            throw new ServiceException("技能编制步骤身份无效");
        }
        AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved =
                approvedSkillBindings.requirePersistedApproved(invocation.getRunId(),
                        invocation.getUserId(), invocation.getPlanRevision());
        AidRuntimePlan plan = approved.plan();
        JSONObject definition = object(plan.getDefinitionJson());
        JSONArray steps = definition == null ? null : definition.getJSONArray("steps");
        JSONObject planned = null;
        if (steps != null) {
            for (Object raw : steps) {
                if (raw instanceof JSONObject candidate
                        && Objects.equals(candidate.getString("stableStepId"), current.getStableStepId())) {
                    planned = candidate;
                    break;
                }
            }
        }
        JSONArray dependsOn = planned == null ? null : planned.getJSONArray("dependsOn");
        if (dependsOn == null || dependsOn.isEmpty()) {
            throw new ServiceException("保存草稿前必须执行技能编制助手");
        }
        List<String> dependencyIds = dependsOn.toJavaList(String.class);
        AidRuntimeRun sourceRun = runs.selectById(invocation.getRunId());
        AidRuntimeAgentVersion sourceAgentVersion = sourceRun == null ? null
                : agentVersions.selectById(sourceRun.getVersionId());
        JSONObject agentDefinition = sourceAgentVersion == null ? null
                : object(sourceAgentVersion.getDefinitionJson());
        if (sourceRun == null || !Objects.equals(sourceRun.getUserId(), invocation.getUserId())
                || sourceAgentVersion == null || !Objects.equals(sourceRun.getVersionDigest(),
                sourceAgentVersion.getPackageDigest())) {
            throw new ServiceException("技能编制Agent版本无效");
        }
        if (NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(
                agentDefinition.getString("protocolVersion"))) {
            List<String> nativeDependencies = List.copyOf(
                    NativeAssistantPlanReadPolicy.ancestorStepIds(definition, current.getStableStepId()));
            return requireNativeCreatorEvidence(invocation, sourceMode, nativeDependencies, approved, sourceRun);
        }
        List<AidRuntimeRunStep> completed = runtimeSteps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getRunId, invocation.getRunId())
                .eq(AidRuntimeRunStep::getPlanRevision, invocation.getPlanRevision())
                .in(AidRuntimeRunStep::getStableStepId, dependencyIds)
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED").eq(AidRuntimeRunStep::getDelFlag, "0")
                .isNotNull(AidRuntimeRunStep::getSkillVersionId)
                .orderByDesc(AidRuntimeRunStep::getPlanPosition));
        for (AidRuntimeRunStep dependency : completed) {
            try {
                AidSkillVersion frozen = skillVersions.selectById(dependency.getSkillVersionId());
                AidSkill identity = frozen == null ? null : skills.selectById(frozen.getSkillId());
                AidSkillRun child = dependency.getChildRunId() == null ? null
                        : skillRuns.selectById(dependency.getChildRunId());
                String approvedDigest = frozen == null ? null : approved.proof().packageDigest(
                        "aid_skill_creator", frozen.getId());
                if (identity == null || !"aid_skill_creator".equals(identity.getSkillCode())
                        || !Objects.equals(approvedDigest, frozen.getPackageDigest())
                        || child == null || !Objects.equals(child.getUserId(), invocation.getUserId())
                        || !Objects.equals(child.getSkillId(), identity.getId())
                        || !Objects.equals(child.getSkillVersionId(), frozen.getId())
                        || !Objects.equals(child.getSkillConfigHash(), frozen.getPackageDigest())
                        || !"SUCCEEDED".equals(child.getStatus()) || !"METHOD".equals(child.getActionMode())
                        || !"0".equals(child.getDelFlag())) {
                    continue;
                }
                SkillPackageResourceLoader.AuthorizedPackage authorized = packages.loadAuthorizedVersion(
                        "aid_skill_creator", frozen.getId(), invocation.getUserId(), true);
                AidSkillVersion version = authorized.version();
                if (!Objects.equals(authorized.skill().getId(), identity.getId())
                        || !Objects.equals(version.getPackageDigest(), frozen.getPackageDigest())) continue;
                AidSkillRunStep method = skillRunSteps.selectOne(Wrappers.<AidSkillRunStep>lambdaQuery()
                        .eq(AidSkillRunStep::getRunId, child.getId()).eq(AidSkillRunStep::getStepKey, "method")
                        .eq(AidSkillRunStep::getSkillVersionId, version.getId())
                        .eq(AidSkillRunStep::getOrchestrationStatus, "OUTPUT_READY")
                        .eq(AidSkillRunStep::getDelFlag, "0").last("LIMIT 1"));
                JSONObject checkpoint = method == null ? null : JSON.parseObject(method.getCheckpointJson());
                JSONArray resources = checkpoint == null ? null : checkpoint.getJSONArray("resources");
                String master = packages.readAuthorizedResource(
                        "aid_skill_creator", version.getId(), "SKILL.md", invocation.getUserId(), true);
                String expectedMasterDigest = SecureUtil.sha256(master);
                boolean masterRead = resources != null && resources.stream().anyMatch(raw -> {
                    JSONObject resource = JSON.parseObject(JSON.toJSONString(raw));
                    return "__skill_md".equals(resource.getString("resourceKey"))
                            && expectedMasterDigest.equals(resource.getString("digest"));
                });
                if (masterRead) {
                    return new CreatorEvidence("aid_skill_creator", version.getId(),
                            version.getPackageDigest(), expectedMasterDigest, null, null);
                }
            } catch (RuntimeException unavailable) {
                // Continue to any other completed dependency; failure remains closed below.
            }
        }
        throw new ServiceException("保存草稿前必须完成固定版本的技能编制助手");
    }

    private CreatorEvidence requireNativeCreatorEvidence(
            RuntimeToolInvocation invocation, String sourceMode, List<String> dependencyIds,
            AssistantApprovedSkillBindingProofService.PersistedApprovedPlan approved, AidRuntimeRun run) {
        String branchPath = AUTHORING_BRANCHES.get(sourceMode);
        if (branchPath == null) throw new ServiceException("技能编制来源模式无效");
        List<AidRuntimeRunStep> completed = runtimeSteps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getRunId, invocation.getRunId())
                .eq(AidRuntimeRunStep::getPlanRevision, invocation.getPlanRevision())
                .in(AidRuntimeRunStep::getStableStepId, dependencyIds)
                .eq(AidRuntimeRunStep::getStepKind, "TOOL")
                .eq(AidRuntimeRunStep::getToolCode, READ_RESOURCE)
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByAsc(AidRuntimeRunStep::getPlanPosition));
        Map<Long, Map<String, VerifiedRead>> reads = new LinkedHashMap<>();
        for (AidRuntimeRunStep dependency : completed) {
            VerifiedRuntimeToolEffect effect = completedToolScopes.require(
                    invocation.getUserId(), run, dependency);
            JSONObject arguments = object(effect.argumentsJson());
            JSONObject output = object(effect.outputJson());
            String code = arguments.getString("skillCode");
            Long versionId = arguments.getLong("versionId");
            String path = arguments.getString("path");
            if (!AUTHORING_SKILL_CODE.equals(code)
                    || !("SKILL.md".equals(path) || branchPath.equals(path))) continue;
            if (versionId == null || versionId <= 0
                    || !Objects.equals(code, output.getString("skillCode"))
                    || !Objects.equals(versionId, output.getLong("skillVersionId"))
                    || !Objects.equals(path, output.getString("path"))
                    || StrUtil.isBlank(output.getString("packageDigest"))
                    || StrUtil.isBlank(output.getString("content"))
                    || !Objects.equals(output.getString("resourceDigest"),
                    SecureUtil.sha256(output.getString("content")))) {
                throw new ServiceException("技能编制资源回执无效");
            }
            Map<String, VerifiedRead> versionReads = reads.computeIfAbsent(
                    versionId, ignored -> new LinkedHashMap<>());
            VerifiedRead value = new VerifiedRead(dependency.getId(), arguments, output);
            VerifiedRead previous = versionReads.putIfAbsent(path, value);
            if (previous != null && !Objects.equals(previous.output().toJSONString(),
                    output.toJSONString())) {
                throw new ServiceException("技能编制资源回执冲突");
            }
        }
        for (Map.Entry<Long, Map<String, VerifiedRead>> candidate : reads.entrySet()) {
            VerifiedRead masterRead = candidate.getValue().get("SKILL.md");
            VerifiedRead branchRead = candidate.getValue().get(branchPath);
            if (masterRead == null || branchRead == null) continue;
            Long versionId = candidate.getKey();
            AidRuntimeAgentSkillRef fixed = agentSkillRefs.selectOne(Wrappers
                    .<AidRuntimeAgentSkillRef>lambdaQuery()
                    .eq(AidRuntimeAgentSkillRef::getAgentVersionId, approved.run().getVersionId())
                    .eq(AidRuntimeAgentSkillRef::getSkillCode, AUTHORING_SKILL_CODE)
                    .eq(AidRuntimeAgentSkillRef::getSkillVersionId, versionId)
                    .last("LIMIT 1"));
            String approvedDigest = fixed == null ? null : fixed.getPackageDigest();
            String masterDigest = masterRead.output().getString("resourceDigest");
            if (StrUtil.isBlank(approvedDigest)
                    || !Objects.equals(approvedDigest, masterRead.output().getString("packageDigest"))
                    || !Objects.equals(approvedDigest, branchRead.output().getString("packageDigest"))
                    || masterRead.stepId() == null || branchRead.stepId() == null
                    || masterRead.stepId() >= branchRead.stepId()) continue;
            try {
                SkillPackageResourceLoader.AuthorizedPackage authorized = packages.loadAuthorizedVersion(
                        AUTHORING_SKILL_CODE, versionId, invocation.getUserId(), true);
                if (!Objects.equals(authorized.skill().getSkillCode(), AUTHORING_SKILL_CODE)
                        || !Objects.equals(authorized.version().getId(), versionId)
                        || !Objects.equals(authorized.version().getPackageDigest(), approvedDigest)) continue;
                String master = packages.readAuthorizedResource(AUTHORING_SKILL_CODE, versionId,
                        "SKILL.md", invocation.getUserId(), true);
                String branch = packages.readAuthorizedResource(AUTHORING_SKILL_CODE, versionId,
                        branchPath, invocation.getUserId(), true);
                String branchDigest = SecureUtil.sha256(branch);
                if (!Objects.equals(master, masterRead.output().getString("content"))
                        || !Objects.equals(masterDigest, SecureUtil.sha256(master))
                        || !Objects.equals(branch, branchRead.output().getString("content"))
                        || !Objects.equals(branchDigest,
                        branchRead.output().getString("resourceDigest"))) continue;
                return new CreatorEvidence(AUTHORING_SKILL_CODE, versionId, approvedDigest,
                        masterDigest, branchPath, branchDigest);
            } catch (RuntimeException unavailable) {
                // Continue to another exact fixed-version pair. No receipt is accepted on its own.
            }
        }
        throw new ServiceException("保存草稿前必须读取固定版本技能编制主文件和当前来源分支");
    }

    private boolean isUniversalRun(RuntimeToolInvocation invocation) {
        AidRuntimeRun run = invocation.getRunId() == null ? null : runs.selectById(invocation.getRunId());
        if (run == null || !Objects.equals(run.getUserId(), invocation.getUserId())
                || run.getVersionId() == null) {
            return false;
        }
        AidRuntimeAgentVersion version = agentVersions.selectById(run.getVersionId());
        AidRuntimeAgent agent = version == null ? null : agents.selectById(version.getAgentId());
        return agent != null && "universal_canvas_agent".equals(agent.getAgentCode())
                && "0".equals(agent.getStatus()) && "0".equals(agent.getDelFlag())
                && "0".equals(version.getStatus()) && "0".equals(version.getDelFlag());
    }

    private List<SkillAuthoringStorageService.AuthoringSourceSnapshot> authorizeSources(
            String manifestJson, String sourceMode, JSONArray requested, int creatorSnapshotCount) {
        if (!Set.of("SCRATCH", "CONVERSATION", "CANVAS").contains(sourceMode)) {
            throw new ServiceException("Skill编制来源模式无效");
        }
        JSONArray values = requested == null ? new JSONArray() : requested;
        // Storage accepts at most 32 snapshots. Native authoring uses master + branch evidence;
        // restored legacy runs keep their historical single creator snapshot.
        int maximumDeclaredSources = 32 - creatorSnapshotCount
                - ("CONVERSATION".equals(sourceMode) ? 1 : 0);
        if (values.size() > maximumDeclaredSources)
            throw new ServiceException("Skill编制来源引用过多");
        if ("SCRATCH".equals(sourceMode) && !values.isEmpty())
            throw new ServiceException("从零编制不能声明已有来源");
        JSONObject manifest = object(manifestJson);
        if (!MANIFEST_SCHEMA.equals(manifest.getString("schemaVersion"))) {
            throw new ServiceException("Skill编制上下文清单无效");
        }
        JSONArray available = manifest.getJSONArray("refs");
        if (available == null) throw new ServiceException("Skill编制上下文缺少来源授权");
        List<SkillAuthoringStorageService.AuthoringSourceSnapshot> result = new ArrayList<>();
        boolean canvas = false;
        for (Object raw : values) {
            if (!(raw instanceof JSONObject requestedRef)) throw new ServiceException("Skill编制来源引用无效");
            String kind = requestedRef.getString("kind");
            JSONObject canonical = requestedRef.getJSONObject("canonicalRef");
            if (StrUtil.isBlank(kind) || canonical == null) throw new ServiceException("Skill编制来源引用无效");
            JSONObject match = null;
            for (Object candidateRaw : available) {
                if (candidateRaw instanceof JSONObject candidate
                        && Objects.equals(kind, candidate.getString("kind"))
                        && readableAuthoringSource(kind, candidate.getString("access"))
                        && Objects.equals(canonical, candidate.getJSONObject("canonicalRef"))
                        && StrUtil.isNotBlank(candidate.getString("contentRef"))) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) throw new ServiceException("Skill编制来源未获授权");
            canvas |= Set.of("FREE_CANVAS", "CANVAS_RESOURCE").contains(kind);
            result.add(new SkillAuthoringStorageService.AuthoringSourceSnapshot(
                    kind, canonical.toJSONString(), SecureUtil.sha256(match.getString("contentRef"))));
        }
        if ("CANVAS".equals(sourceMode) && !canvas) throw new ServiceException("画布转Skill需要已授权画布来源");
        return List.copyOf(result);
    }

    private static boolean readableAuthoringSource(String kind, String access) {
        return "READ".equals(access) || ("FREE_CANVAS".equals(kind) && "MUTATE".equals(access));
    }

    private List<SkillPackageAdminRequests.ResourceItem> resources(JSONArray values) {
        if (values == null || values.size() > 64) throw new ServiceException("Skill参考资源无效");
        Set<String> keys = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        List<SkillPackageAdminRequests.ResourceItem> result = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof JSONObject source)) throw new ServiceException("Skill参考资源无效");
            String key = source.getString("key");
            String path = source.getString("path");
            if (StrUtil.isBlank(key) || !keys.add(key) || !safeReference(path) || !paths.add(path)) {
                throw new ServiceException("Skill参考资源重复或路径无效");
            }
            SkillPackageAdminRequests.ResourceItem item = new SkillPackageAdminRequests.ResourceItem();
            item.setResourceKey(key);
            item.setFilePath(path);
            item.setResourceType("REFERENCE");
            item.setMimeType(StrUtil.blankToDefault(source.getString("mimeType"), "text/markdown"));
            item.setContent(source.getString("content"));
            JSONObject route = source.getJSONObject("route");
            item.setRouteJson(route == null ? "{}" : route.toJSONString());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private List<String> strings(JSONArray values, int max) {
        if (values == null || values.isEmpty() || values.size() > max) throw new ServiceException("Skill模型配置无效");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object raw : values) {
            String value = raw instanceof String ? ((String) raw).trim() : null;
            if (StrUtil.isBlank(value) || value.length() > 100 || !result.add(value)) {
                throw new ServiceException("Skill模型配置无效");
            }
        }
        return List.copyOf(result);
    }

    private static JSONObject requireObject(JSONObject source, String field) {
        JSONObject value = source.getJSONObject(field);
        if (value == null) throw new ServiceException("Skill结构定义无效");
        return value;
    }

    private JSONObject object(String json) {
        try {
            JSONObject result = JSON.parseObject(json);
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new ServiceException("Skill工具数据无效");
        }
    }

    private boolean safeReference(String path) {
        return path != null && path.matches("references/[a-z0-9][a-z0-9._/-]{0,180}")
                && !path.contains("..") && !path.contains("//");
    }

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                       String input, String output, String sideEffect,
                                                       String idempotency) {
        return new RuntimeToolRegistry.Descriptor(code, VERSION, SecureUtil.sha256(input + "\n" + output),
                name, description, input, output, sideEffect, false, idempotency,
                false, "REPLAY_SAFE", List.of("skill.package"));
    }

    private record AuthorizedAccess(SkillPackageResourceLoader.AuthorizedPackage packages,
                                    boolean admittedRun, AidRuntimeRun run) { }
    private static final class SkillResolveRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String summary;

        private SkillResolveRejectedException(String code, String summary) {
            super(code);
            this.code = code;
            this.summary = summary;
        }

        private String code() { return code; }
        private String summary() { return summary; }
    }
    private record CreatorEvidence(String skillCode, Long versionId, String packageDigest,
                                   String masterDigest, String branchPath, String branchDigest) { }
    private record VerifiedRead(Long stepId, JSONObject arguments, JSONObject output) { }
}
