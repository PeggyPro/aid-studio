package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Enforces the server-owned Tool route for a fixed selected Skill. */
public final class SelectedSkillToolRoutePolicy
{
    public static final String METADATA_FIELD = "selectedSkillToolRoute";
    public static final String SCHEMA_VERSION = "assistant-selected-skill-tool-route/v1";
    public static final String SELECTION_INVALID = "ASSISTANT_IMAGE_SKILL_SELECTION_INVALID";
    public static final String TOOL_DOWNGRADE = "ASSISTANT_IMAGE_SKILL_TOOL_DOWNGRADE";
    public static final String TOOL_REQUIRED = "ASSISTANT_IMAGE_SKILL_TOOL_REQUIRED";

    private static final String STATUS_READY = "READY";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    private static final String STATUS_INVALID = "INVALID";
    private static final int MAX_PLAN_STEPS = 100;
    private static final Set<String> METADATA_FIELDS = Set.of(
            "schemaVersion", "status", "requiredToolCode", "selectedSkillCode",
            "selectedSkillVersionId", "selectedPackageDigest", "selectedOutputKind",
            "selectedExecutionRoute", "instruction");
    private static final Set<String> OWNED_REJECTION_CODES = Set.of(
            SELECTION_INVALID, TOOL_DOWNGRADE, TOOL_REQUIRED,
            "ASSISTANT_IMAGE_SKILL_RUN_INVALID",
            "ASSISTANT_IMAGE_SKILL_VERSION_CHANGED",
            "ASSISTANT_IMAGE_SKILL_CAPABILITY_INVALID",
            "ASSISTANT_IMAGE_SKILL_MODEL_UNAVAILABLE");
    private static final String INSTRUCTION =
            "The selected IMAGE Skill is fixed to requiredToolCode. Generic IMAGE generation "
                    + "is not an equivalent route. Non-billable node creation and authorized READ "
                    + "tools may still be used, but the execution plan must generate the image "
                    + "through the fixed Tool and its live schema.";

    private SelectedSkillToolRoutePolicy() { }

    public static Requirement resolveRequest(String requestJson)
    {
        final JSONObject request;
        try
        {
            request = JSON.parseObject(requestJson);
        }
        catch (RuntimeException invalid)
        {
            throw new ServiceException(SELECTION_INVALID);
        }
        if (request == null) throw new ServiceException(SELECTION_INVALID);
        Object selected = request.get("_selectedSkill");
        if (selected == null) return Requirement.inactive();
        if (!(selected instanceof JSONObject object)) throw new ServiceException(SELECTION_INVALID);
        return resolve(object);
    }

    public static Requirement resolve(JSONObject selected)
    {
        if (selected == null) return Requirement.inactive();
        String outputKind = selected.getString("outputKind");
        String executionRoute = selected.getString("executionRoute");
        boolean ordinaryText = "TEXT".equals(outputKind) && "SKILL".equals(executionRoute);
        if (ordinaryText) return Requirement.inactive();
        boolean imageDeclared = AssistantImageSkillInvocationResolver.OUTPUT_KIND.equals(outputKind)
                || AssistantImageSkillInvocationResolver.EXECUTION_ROUTE.equals(executionRoute);

        String code = safeCode(selected.getString("code"));
        String versionId = safePositiveId(selected.getString("versionId"));
        String packageDigest = safeDigest(selected.getString("packageDigest"));
        boolean valid = imageDeclared && code != null && versionId != null && packageDigest != null
                && AssistantImageSkillInvocationResolver.OUTPUT_KIND.equals(outputKind)
                && AssistantImageSkillInvocationResolver.EXECUTION_ROUTE.equals(executionRoute);
        return new Requirement(valid ? State.REQUIRED : State.INVALID, code, versionId,
                packageDigest,
                AssistantImageSkillInvocationResolver.OUTPUT_KIND.equals(outputKind) ? outputKind : null,
                AssistantImageSkillInvocationResolver.EXECUTION_ROUTE.equals(executionRoute)
                        ? executionRoute : null);
    }

    public static JSONObject metadata(Requirement requirement, boolean requiredToolAvailable)
    {
        if (requirement == null || !requirement.active()) return null;
        JSONObject result = new JSONObject();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("status", requirement.valid()
                ? requiredToolAvailable ? STATUS_READY : STATUS_UNAVAILABLE
                : STATUS_INVALID);
        result.put("requiredToolCode", AssistantImageSkillInvocationResolver.TOOL_CODE);
        if (requirement.skillCode() != null) result.put("selectedSkillCode", requirement.skillCode());
        if (requirement.skillVersionId() != null)
            result.put("selectedSkillVersionId", requirement.skillVersionId());
        if (requirement.packageDigest() != null)
            result.put("selectedPackageDigest", requirement.packageDigest());
        if (requirement.outputKind() != null) result.put("selectedOutputKind", requirement.outputKind());
        if (requirement.executionRoute() != null)
            result.put("selectedExecutionRoute", requirement.executionRoute());
        result.put("instruction", INSTRUCTION);
        return result;
    }

    public static void validateProjectedMetadata(JSONObject request)
    {
        JSONObject turn = request == null ? null : request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONObject selected = original == null ? null : original.getJSONObject("selectedSkill");
        Requirement requirement = resolve(selected);
        JSONObject limits = request == null ? null : request.getJSONObject("contextLimits");
        JSONObject metadata = limits == null ? null : limits.getJSONObject(METADATA_FIELD);
        if (!requirement.active())
        {
            if (metadata != null) throw new ServiceException("原生固定路由元数据无效");
            return;
        }
        if (metadata == null || !METADATA_FIELDS.containsAll(metadata.keySet())
                || !metadata.keySet().containsAll(requiredMetadataFields(requirement)))
            throw new ServiceException("原生固定路由元数据无效");
        String status = metadata.getString("status");
        boolean available = STATUS_READY.equals(status);
        if (requirement.valid())
        {
            if (!available && !STATUS_UNAVAILABLE.equals(status))
                throw new ServiceException("原生固定路由元数据无效");
        }
        else if (!STATUS_INVALID.equals(status))
        {
            throw new ServiceException("原生固定路由元数据无效");
        }
        JSONObject expected = metadata(requirement, available);
        if (!Objects.equals(expected, metadata))
            throw new ServiceException("原生固定路由元数据已变化");
    }

    public static boolean planningReady(JSONObject request)
    {
        validateProjectedMetadata(request);
        JSONObject limits = request.getJSONObject("contextLimits");
        JSONObject metadata = limits == null ? null : limits.getJSONObject(METADATA_FIELD);
        return metadata == null || STATUS_READY.equals(metadata.getString("status"));
    }

    public static String plannerInstruction(JSONObject request)
    {
        validateProjectedMetadata(request);
        JSONObject limits = request.getJSONObject("contextLimits");
        JSONObject metadata = limits == null ? null : limits.getJSONObject(METADATA_FIELD);
        return metadata == null ? "" : " " + INSTRUCTION;
    }

    public static boolean includePlannerTool(Requirement requirement,
                                             boolean requiredToolAvailable,
                                             String toolCode,
                                             RuntimeToolProvider.BillingMediaType mediaType)
    {
        if (requirement == null || !requirement.active()
                || mediaType != RuntimeToolProvider.BillingMediaType.IMAGE) return true;
        return requirement.valid() && requiredToolAvailable
                && AssistantImageSkillInvocationResolver.TOOL_CODE.equals(toolCode);
    }

    public static String ownedRejectionCode(ServiceException failure)
    {
        if (failure == null) return null;
        String detail = StrUtil.trim(failure.getDetailMessage());
        if (detail != null && OWNED_REJECTION_CODES.contains(detail)) return detail;
        String message = StrUtil.trim(failure.getMessage());
        return message != null && OWNED_REJECTION_CODES.contains(message) ? message : null;
    }

    public static boolean matchesRequiredToolBinding(AidRuntimeAgentToolRef published,
                                                     RuntimeToolRegistry.Descriptor descriptor,
                                                     RuntimeToolProvider.BillingMediaType mediaType)
    {
        return published != null && descriptor != null
                && AssistantImageSkillInvocationResolver.TOOL_CODE.equals(published.getToolCode())
                && Objects.equals(published.getContractVersion(), descriptor.contractVersion())
                && Objects.equals(published.getSchemaDigest(), descriptor.schemaDigest())
                && RuntimeToolProvider.BillingMediaType.IMAGE == mediaType;
    }

    public static void requirePlan(Requirement requirement,
                                   boolean requiredToolAvailable,
                                   JSONArray planSteps,
                                   boolean verifiedServerDiscovery,
                                   Function<String, RuntimeToolProvider.BillingMediaType> mediaClassifier)
    {
        if (requirement == null || !requirement.active() || verifiedServerDiscovery) return;
        require(requirement.valid(), SELECTION_INVALID);
        require(requiredToolAvailable, TOOL_REQUIRED);
        require(planSteps != null && !planSteps.isEmpty() && planSteps.size() <= MAX_PLAN_STEPS,
                TOOL_REQUIRED);
        boolean routed = false;
        for (int index = 0; index < planSteps.size(); index++)
        {
            JSONObject step = planSteps.getJSONObject(index);
            if (step == null || !"TOOL".equals(step.getString("executorKind"))) continue;
            String code = step.getString("bindingCode");
            RuntimeToolProvider.BillingMediaType mediaType = classify(mediaClassifier, code);
            if (mediaType != RuntimeToolProvider.BillingMediaType.IMAGE) continue;
            require(AssistantImageSkillInvocationResolver.TOOL_CODE.equals(code), TOOL_DOWNGRADE);
            routed = true;
        }
        require(routed, TOOL_REQUIRED);
    }

    public static boolean isVerifiedServerDiscovery(AidRuntimePlan plan,
                                                     JSONObject definition,
                                                     Function<String, RuntimeToolRegistry.Descriptor> descriptorResolver)
    {
        if (plan == null || definition == null || descriptorResolver == null
                || !Objects.equals(0, plan.getPlannerAttemptNo()) || plan.getMediaTaskId() != null
                || !NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(plan.getProtocolVersion())
                || !NativeAssistantDiscoveryPlanService.isDiscovery(definition)) return false;
        JSONArray steps = definition.getJSONArray("steps");
        if (steps == null || steps.isEmpty() || steps.size() > MAX_PLAN_STEPS) return false;
        for (int index = 0; index < steps.size(); index++)
        {
            JSONObject step = steps.getJSONObject(index);
            String code = step == null ? null : step.getString("bindingCode");
            if (step == null || !"TOOL".equals(step.getString("executorKind"))
                    || !NativeAssistantDiscoveryPlanService.isSafeRead(code, definition)) return false;
            RuntimeToolRegistry.Descriptor descriptor;
            try { descriptor = descriptorResolver.apply(code); }
            catch (RuntimeException unavailable) { return false; }
            if (descriptor == null || !"READ".equals(descriptor.sideEffectLevel())
                    || descriptor.billable()) return false;
        }
        return true;
    }

    private static RuntimeToolProvider.BillingMediaType classify(
            Function<String, RuntimeToolProvider.BillingMediaType> classifier, String code)
    {
        if (classifier == null || StrUtil.isBlank(code)) return null;
        return classifier.apply(code);
    }

    private static Set<String> requiredMetadataFields(Requirement requirement)
    {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(Set.of(
                "schemaVersion", "status", "requiredToolCode", "instruction"));
        if (requirement.skillCode() != null) result.add("selectedSkillCode");
        if (requirement.skillVersionId() != null) result.add("selectedSkillVersionId");
        if (requirement.packageDigest() != null) result.add("selectedPackageDigest");
        if (requirement.outputKind() != null) result.add("selectedOutputKind");
        if (requirement.executionRoute() != null) result.add("selectedExecutionRoute");
        return Set.copyOf(result);
    }

    private static String safeCode(String value)
    {
        return value != null && value.matches("^[A-Za-z][A-Za-z0-9_.-]{0,99}$") ? value : null;
    }

    private static String safePositiveId(String value)
    {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) return null;
        try { return Long.parseLong(value) > 0L ? value : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String safeDigest(String value)
    {
        return value != null && value.matches("^[0-9a-f]{64}$") ? value : null;
    }

    private static void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    public enum State { INACTIVE, REQUIRED, INVALID }

    public record Requirement(State state, String skillCode, String skillVersionId,
                              String packageDigest, String outputKind, String executionRoute)
    {
        static Requirement inactive()
        {
            return new Requirement(State.INACTIVE, null, null, null, null, null);
        }

        public boolean active()
        {
            return state != State.INACTIVE;
        }

        public boolean valid()
        {
            return state == State.REQUIRED;
        }
    }
}
