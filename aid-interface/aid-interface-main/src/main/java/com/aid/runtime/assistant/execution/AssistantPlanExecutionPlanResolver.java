package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.runtime.assistant.AssistantApprovedSkillBindingProofService;
import com.aid.runtime.assistant.NativeAssistantDiscoveryPlanService;
import com.aid.runtime.assistant.NativeAssistantLoopExecutor;
import com.aid.runtime.assistant.SelectedSkillToolRoutePolicy;
import com.aid.runtime.assistant.SpatialPrevisGuidancePolicy;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves one model decision against the immutable, approved plan and published bindings. */
@Component
final class AssistantPlanExecutionPlanResolver
{
    private static final int MAX_SKILL_INPUT_BYTES = 100_000;

    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeAgentSkillRefMapper skillRefs;
    @Resource private AidRuntimeAgentToolRefMapper toolRefs;
    @Resource private RuntimeToolRegistry tools;
    @Resource private AidSkillMapper skills;
    @Resource private SkillRuntimeEntrypointReadinessService skillReadiness;
    @Resource private AssistantApprovedSkillBindingProofService approvedSkillBindings;
    @Resource private AssistantPlanInputArtifactResolver inputArtifacts;
    @Resource private AssistantImageSkillInvocationResolver imageSkills;

    BoundOperation resolve(AidRuntimeRun run, AidRuntimePlan plan, AssistantPlanExecutionDecision decision)
    { return resolve(run,plan,decision,true); }

    /** Restore the exact accepted operation; admission of a new output is not rerun. */
    BoundOperation resolveAccepted(AidRuntimeRun run, AidRuntimePlan plan, AssistantPlanExecutionDecision decision)
    { return resolve(run,plan,decision,false); }

    private BoundOperation resolve(AidRuntimeRun run, AidRuntimePlan plan, AssistantPlanExecutionDecision decision, boolean outputAdmission)
    {
        require(run != null && plan != null && decision != null, "EXECUTION_CONTEXT_INVALID");
        require("APPROVED".equals(plan.getStatus()) && "0".equals(plan.getDelFlag()), "PLAN_NOT_APPROVED");
        require(Objects.equals(plan.getRunId(), run.getId())
                && Objects.equals(plan.getRevision(), run.getPlanRevision())
                && Objects.equals(plan.getRevision(), decision.observedPlanRevision())
                && Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                && Objects.equals(plan.getInputContextDigest(), run.getContextDigest())
                && Objects.equals(plan.getProtocolVersion(), decision.protocolVersion()), "PLAN_IDENTITY_CHANGED");
        require(StrUtil.isNotBlank(plan.getDefinitionJson())
                && Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson())),
                "PLAN_DIGEST_CHANGED");
        requireSpatialGuidanceAdmission(run, plan, decision);

        JSONObject definition = object(plan.getDefinitionJson(), "PLAN_DEFINITION_INVALID");
        JSONArray values = definition.getJSONArray("steps");
        require(values != null && !values.isEmpty() && values.size() <= 100, "PLAN_DEFINITION_INVALID");
        requireSelectedImageRoute(run, plan, definition, values);
        JSONObject selected = null;
        for (int index = 0; index < values.size(); index++)
        {
            JSONObject candidate = values.getJSONObject(index);
            if (candidate != null && Objects.equals(candidate.getString("stableStepId"), decision.stableStepId()))
            {
                require(selected == null, "PLAN_STEP_DUPLICATE");
                selected = candidate;
            }
        }
        require(selected != null, "PLAN_STEP_NOT_FOUND");
        String executorKind = selected.getString("executorKind");
        require(Objects.equals(executorKind, decision.action()), "PLAN_EXECUTOR_CHANGED");
        String operationKind = text(selected, "operationKind", 64, "PLAN_OPERATION_INVALID");
        String bindingCode = text(selected, "bindingCode", 100, "PLAN_BINDING_INVALID");
        String bindingVersion = text(selected, "bindingVersion", 100, "PLAN_BINDING_INVALID");
        String retryClass = text(selected, "retryClass", 64, "PLAN_RETRY_CLASS_INVALID");
        String approvalScope = text(selected, "approvalScope", 64, "PLAN_APPROVAL_SCOPE_INVALID");
        Integer position = selected.getInteger("position");
        require(position != null && position > 0, "PLAN_POSITION_INVALID");
        Integer maxAttempts = selected.getInteger("maxAttempts");
        boolean reusableSafeRead = NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(plan.getProtocolVersion())
                && decision.isTool()
                && NativeAssistantDiscoveryPlanService.isSafeRead(bindingCode, definition);
        require(maxAttempts != null && maxAttempts >= 1 && maxAttempts <= 10
                && decision.attemptNo() <= (reusableSafeRead ? 1_000 : maxAttempts),
                "PLAN_ATTEMPT_EXCEEDED");
        requireAttemptHistory(run.getId(), plan.getRevision(), decision.stableStepId(),
                decision.attemptNo(), maxAttempts, retryClass, reusableSafeRead);
        AssistantPlanInputArtifactResolver.Resolution inputResolution = inputArtifacts.resolve(run, plan, selected);
        require(inputResolution.inputsResolved(), "PLAN_INPUT_BINDINGS_UNRESOLVED");
        List<String> plannedInputs = inputResolution.inputArtifactRefs();
        require(Objects.equals(plannedInputs, decision.inputArtifactRefs()), "PLAN_INPUTS_CHANGED");
        requireDependencies(run.getId(), plan.getRevision(), strings(selected.getJSONArray("dependsOn"),
                "PLAN_DEPENDENCIES_INVALID"));

        if (decision.isTool())
        {
            require(Objects.equals(bindingCode, decision.bindingCode())
                    && Objects.equals(bindingVersion, decision.bindingVersion()), "TOOL_BINDING_CHANGED");
            AidRuntimeAgentToolRef published = toolRefs.selectOne(
                    new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                            .eq(AidRuntimeAgentToolRef::getAgentVersionId, plan.getAgentVersionId())
                            .eq(AidRuntimeAgentToolRef::getToolCode, bindingCode).last("LIMIT 1"));
            RuntimeToolRegistry.Descriptor descriptor = tools.require(bindingCode);
            require(published != null
                    && Objects.equals(published.getContractVersion(), bindingVersion)
                    && Objects.equals(published.getSchemaDigest(), descriptor.schemaDigest())
                    && Objects.equals(descriptor.contractVersion(), bindingVersion), "TOOL_BINDING_CHANGED");
            tools.validateArguments(bindingCode, bindingVersion, decision.arguments());
            if (outputAdmission && (descriptor.billable() || tools.planningOutputReferenceKind(bindingCode)==RuntimeToolProvider.OutputReferenceKind.RUNTIME_ARTIFACT))
                requireDeclaredArtifactOutput(selected, decision.arguments(), "creative.text.submit".equals(bindingCode),
                        descriptor.billable() ? "PLAN_BILLABLE_OUTPUT_INVALID" : "PLAN_ARTIFACT_OUTPUT_INVALID");
            return new BoundOperation(plan.getDefinitionDigest(), operationKind, executorKind, position, maxAttempts, retryClass, approvalScope,
                    bindingCode, bindingVersion, descriptor.schemaDigest(), descriptor.sideEffectLevel(),
                    descriptor.billable(), descriptor.requiredCapabilities(), null, null, selected.toJSONString());
        }

        require(decision.isSkill() && Objects.equals(bindingCode, decision.bindingCode())
                && Objects.equals(bindingVersion, String.valueOf(decision.skillVersionId())),
                "SKILL_BINDING_CHANGED");
        AssistantApprovedSkillBindingProofService.ApprovedPlanProof approvedProof =
                approvedSkillBindings.requireApproved(run, plan);
        require(approvedProof.allowsSkill(bindingCode, decision.skillVersionId())
                        && Objects.equals(approvedProof.packageDigest(bindingCode, decision.skillVersionId()),
                        decision.packageDigest()),
                "SKILL_BINDING_CHANGED");
        AidRuntimeAgentSkillRef published = skillRefs.selectOne(
                new LambdaQueryWrapper<AidRuntimeAgentSkillRef>()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, plan.getAgentVersionId())
                        .eq(AidRuntimeAgentSkillRef::getSkillCode, bindingCode)
                        .eq(AidRuntimeAgentSkillRef::getSkillVersionId, decision.skillVersionId()).last("LIMIT 1"));
        AidSkillVersion skillVersion;
        if (published != null)
        {
            require(Objects.equals(published.getPackageDigest(), decision.packageDigest()),
                    "SKILL_BINDING_CHANGED");
            AidSkill identity = skills.selectById(published.getSkillId());
            require(identity != null && Objects.equals(identity.getId(), published.getSkillId())
                            && Objects.equals(identity.getSkillCode(), bindingCode),
                    "SKILL_BINDING_CHANGED");
            SkillRuntimeEntrypointReadinessService.ReadyEntrypoint ready;
            try
            {
                ready = skillReadiness.requireReadyMethod(identity, decision.skillVersionId(),
                        decision.packageDigest());
            }
            catch (RuntimeException ordinaryUnavailable)
            {
                ready = skillReadiness.requireReadyMethod(identity, decision.skillVersionId(),
                        decision.packageDigest(), run.getUserId(), true);
            }
            skillVersion = ready == null ? null : ready.rootVersion();
            require(skillVersion != null && "PROMPT".equals(skillVersion.getExecutorType())
                            && "INTERNAL".equals(skillVersion.getInvocationScope())
                            && "TEXT".equalsIgnoreCase(SkillModelService.outputKind(skillVersion))
                            && Objects.equals(skillVersion.getId(), decision.skillVersionId())
                            && Objects.equals(skillVersion.getPackageDigest(), decision.packageDigest()),
                    "SKILL_METHOD_ONLY_REQUIRED");
        }
        else
        {
            AssistantApprovedSkillBindingProofService.ApprovedUserSkillBinding approved =
                    approvedProof.userBinding(decision.stableStepId());
            require(approved != null
                    && Objects.equals(approved.skillCode(), bindingCode)
                    && Objects.equals(approved.skillVersionId(), decision.skillVersionId())
                    && Objects.equals(approved.packageDigest(), decision.packageDigest()),
                    "SKILL_BINDING_CHANGED");
            skillVersion = approved.version();
            require(skillVersion != null && "ENTRYPOINT".equals(skillVersion.getInvocationScope()),
                    "SKILL_METHOD_ONLY_REQUIRED");
        }
        requireSkillInput(skillVersion, decision.arguments());
        return new BoundOperation(plan.getDefinitionDigest(), operationKind, executorKind, position, maxAttempts, retryClass, approvalScope,
                bindingCode, bindingVersion, null, "SKILL", false, List.of("skill.invoke"),
                decision.skillVersionId(), decision.packageDigest(), selected.toJSONString());
    }

    private void requireSkillInput(AidSkillVersion skillVersion, JSONObject input)
    {
        require(skillVersion != null && input != null, "SKILL_INPUT_SCHEMA_UNAVAILABLE");
        require(input.toJSONString(JSONWriter.Feature.WriteMapNullValue)
                        .getBytes(StandardCharsets.UTF_8).length <= MAX_SKILL_INPUT_BYTES,
                "SKILL_INPUT_TOO_LARGE");
        JSONSchema schema;
        try
        {
            JSONObject definition = com.aid.skill.service.SkillMethodInputContract.objectParameters(
                    skillVersion.getInputSchemaJson());
            schema = JSONSchema.of(definition);
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException("SKILL_INPUT_SCHEMA_UNAVAILABLE");
        }
        ValidateResult result;
        try
        {
            result = schema.validate(input);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("SKILL_INPUT_SCHEMA_UNAVAILABLE");
        }
        require(result != null && result.isSuccess(), "SKILL_INPUT_SCHEMA_MISMATCH");
    }

    /** Rechecks the fixed guidance boundary for new, approved, resumed, and replayed decisions. */
    private void requireSpatialGuidanceAdmission(AidRuntimeRun run, AidRuntimePlan plan,
                                                  AssistantPlanExecutionDecision decision)
    {
        AidAssistantSession session = sessions.selectById(run.getSessionId());
        if (session == null || !SpatialPrevisGuidancePolicy.appliesToModule(session.getModuleCode())) return;
        require(Objects.equals(session.getUserId(), run.getUserId()) && "0".equals(session.getDelFlag()),
                "SPATIAL_GUIDANCE_SCOPE_INVALID");
        List<AidRuntimeAgentSkillRef> publishedSkills = skillRefs.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentSkillRef>()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, plan.getAgentVersionId()));
        List<AidRuntimeAgentToolRef> publishedTools = toolRefs.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, plan.getAgentVersionId()));
        try
        {
            SpatialPrevisGuidancePolicy.requirePublishedBindings(publishedSkills, publishedTools);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException("SPATIAL_GUIDANCE_SCOPE_INVALID");
        }
        if (decision.isTool())
        {
            RuntimeToolRegistry.Descriptor descriptor = tools.require(decision.bindingCode());
            require(SpatialPrevisGuidancePolicy.allowsReadTool(descriptor.code(),
                    descriptor.sideEffectLevel(), descriptor.billable()),
                    "SPATIAL_GUIDANCE_OPERATION_FORBIDDEN");
            return;
        }
        require(decision.isSkill() && SpatialPrevisGuidancePolicy.SKILL_CODE.equals(decision.bindingCode()),
                "SPATIAL_GUIDANCE_OPERATION_FORBIDDEN");
    }

    private void requireSelectedImageRoute(AidRuntimeRun run, AidRuntimePlan plan,
                                           JSONObject definition, JSONArray planSteps)
    {
        SelectedSkillToolRoutePolicy.Requirement requirement =
                SelectedSkillToolRoutePolicy.resolveRequest(run.getRequestJson());
        if (!requirement.active()) return;
        boolean verifiedDiscovery = SelectedSkillToolRoutePolicy.isVerifiedServerDiscovery(
                plan, definition, tools::require);
        if (verifiedDiscovery)
        {
            SelectedSkillToolRoutePolicy.requirePlan(requirement, false,
                    planSteps, true, tools::billingMediaType);
            return;
        }
        AidRuntimeAgentToolRef published = toolRefs.selectOne(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, plan.getAgentVersionId())
                        .eq(AidRuntimeAgentToolRef::getToolCode,
                                AssistantImageSkillInvocationResolver.TOOL_CODE)
                        .last("LIMIT 1"));
        RuntimeToolRegistry.Descriptor descriptor = null;
        RuntimeToolProvider.BillingMediaType mediaType = null;
        if (tools.supports(AssistantImageSkillInvocationResolver.TOOL_CODE))
        {
            descriptor = tools.require(AssistantImageSkillInvocationResolver.TOOL_CODE);
            mediaType = tools.billingMediaType(AssistantImageSkillInvocationResolver.TOOL_CODE);
        }
        boolean requiredToolAvailable = SelectedSkillToolRoutePolicy.matchesRequiredToolBinding(
                published, descriptor, mediaType);
        SelectedSkillToolRoutePolicy.requirePlan(requirement, requiredToolAvailable,
                planSteps, verifiedDiscovery, tools::billingMediaType);
        if (requirement.active() && requirement.valid() && !verifiedDiscovery)
        {
            try { imageSkills.requirePinned(run.getId(), run.getUserId()); }
            catch (ServiceException unavailable)
            {
                String code = SelectedSkillToolRoutePolicy.ownedRejectionCode(unavailable);
                if (code == null) throw unavailable;
                throw new ServiceException(code);
            }
        }
    }

    private void requireDeclaredArtifactOutput(JSONObject step, JSONObject arguments, boolean creativeText, String outputError)
    {
        JSONArray outputIds = step.getJSONArray("outputLogicalArtifactIds");
        require(outputIds != null && outputIds.size() == 1 && outputIds.get(0) instanceof String
                && outputIds.getString(0).matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"),
                outputError);
        if (creativeText) require(arguments != null
                && Objects.equals(outputIds.getString(0), arguments.getString("logicalArtifactId")),
                "PLAN_BILLABLE_OUTPUT_CHANGED");
    }

    private void requireDependencies(Long runId, Long planRevision, List<String> dependencies)
    {
        for (String dependency : dependencies)
        {
            AidRuntimeRunStep completed = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                    .eq(AidRuntimeRunStep::getRunId, runId)
                    .eq(AidRuntimeRunStep::getPlanRevision, planRevision)
                    .eq(AidRuntimeRunStep::getStableStepId, dependency)
                    .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                    .eq(AidRuntimeRunStep::getDelFlag, "0")
                    .orderByDesc(AidRuntimeRunStep::getAttemptNo).orderByDesc(AidRuntimeRunStep::getId)
                    .last("LIMIT 1"));
            require(completed != null, "PLAN_DEPENDENCY_INCOMPLETE");
        }
    }

    private void requireAttemptHistory(Long runId, Long planRevision, String stableStepId,
                                       int attemptNo, int maxAttempts, String retryClass,
                                       boolean reusableSafeRead)
    {
        if (attemptNo == 1) return;
        AidRuntimeRunStep previous = steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getPlanRevision, planRevision)
                .eq(AidRuntimeRunStep::getStableStepId, stableStepId)
                .eq(AidRuntimeRunStep::getAttemptNo, attemptNo - 1)
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("LIMIT 1"));
        require(previous != null
                        && (List.of("FAILED", "FAILED_PRETASK", "CANCELLED_PRETASK")
                        .contains(previous.getStatus())
                        || reusableSafeRead && "SUCCEEDED".equals(previous.getStatus()))
                        && Objects.equals(previous.getRetryClass(), retryClass),
                "PLAN_RETRY_NOT_ALLOWED");
        if (reusableSafeRead && List.of("FAILED", "FAILED_PRETASK", "CANCELLED_PRETASK")
                .contains(previous.getStatus()))
        {
            List<AidRuntimeRunStep> history = steps.selectList(
                    new LambdaQueryWrapper<AidRuntimeRunStep>()
                            .eq(AidRuntimeRunStep::getRunId, runId)
                            .eq(AidRuntimeRunStep::getPlanRevision, planRevision)
                            .eq(AidRuntimeRunStep::getStableStepId, stableStepId)
                            .lt(AidRuntimeRunStep::getAttemptNo, attemptNo)
                            .eq(AidRuntimeRunStep::getDelFlag, "0")
                            .orderByDesc(AidRuntimeRunStep::getAttemptNo)
                            .orderByDesc(AidRuntimeRunStep::getId)
                            .last("LIMIT 11"));
            int expectedAttempt = attemptNo - 1;
            int failures = 0;
            for (AidRuntimeRunStep row : history)
            {
                require(Objects.equals(row.getAttemptNo(), expectedAttempt--)
                                && Objects.equals(row.getRetryClass(), retryClass),
                        "PLAN_RETRY_NOT_ALLOWED");
                if ("SUCCEEDED".equals(row.getStatus())) break;
                require(List.of("FAILED", "FAILED_PRETASK", "CANCELLED_PRETASK")
                        .contains(row.getStatus()), "PLAN_RETRY_NOT_ALLOWED");
                failures++;
            }
            require(failures > 0 && failures < maxAttempts, "PLAN_ATTEMPT_EXCEEDED");
        }
    }

    private JSONObject object(String value, String code)
    {
        try
        {
            JSONObject result = JSON.parseObject(value);
            require(result != null, code);
            return result;
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException(code);
        }
    }

    private List<String> strings(JSONArray values, String code)
    {
        if (values == null) return List.of();
        require(values.size() <= 100, code);
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values)
        {
            require(value instanceof String text && StrUtil.isNotBlank(text) && text.length() <= 500, code);
            result.add((String) value);
        }
        return List.copyOf(result);
    }

    private String text(JSONObject source, String field, int max, String code)
    {
        String value = source.getString(field);
        require(StrUtil.isNotBlank(value) && value.length() <= max, code);
        return value;
    }

    private void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    record BoundOperation(String planDefinitionDigest,
                          String operationKind,
                          String executorKind,
                          int planPosition,
                          int maxAttempts,
                          String retryClass,
                          String approvalScope,
                          String bindingCode,
                          String bindingVersion,
                          String schemaDigest,
                          String sideEffectLevel,
                          boolean billable,
                          List<String> capabilityCodes,
                          Long skillVersionId,
                          String packageDigest,
                          String planStepJson)
    {
        BoundOperation
        {
            capabilityCodes = capabilityCodes == null ? List.of() : List.copyOf(capabilityCodes);
        }
    }
}
