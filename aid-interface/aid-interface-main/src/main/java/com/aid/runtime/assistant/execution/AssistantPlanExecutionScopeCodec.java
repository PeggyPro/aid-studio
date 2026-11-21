package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.budget.AssistantBudgetOperationAdmission;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantPlanExecutionPlanResolver.BoundOperation;
import com.aid.runtime.tool.RuntimeToolBillingIntent;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Canonical durable receipt for one child operation. */
@Component
final class AssistantPlanExecutionScopeCodec
{
    private static final String SCHEMA = "assistant-operation-scope/v1";

    Encoded encode(Long mainAgentStepId,
                   AssistantPlanExecutionDecision decision,
                   BoundOperation bound,
                   AssistantRuntimeContextManifestService.ResolvedManifest manifest,
                   AssistantBudgetOperationScope operationScope,
                   AssistantBudgetOperationAdmission admission,
                   RuntimeToolPreparedInvocation prepared)
    {
        return encode(mainAgentStepId, decision, bound, manifest, operationScope, admission, prepared, null);
    }

    Encoded encode(Long mainAgentStepId, AssistantPlanExecutionDecision decision, BoundOperation bound,
                   AssistantRuntimeContextManifestService.ResolvedManifest manifest,
                   AssistantBudgetOperationScope operationScope, AssistantBudgetOperationAdmission admission,
                   RuntimeToolPreparedInvocation prepared, JSONObject conversationMemory)
    {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA);
        JSONObject decisionNode = new JSONObject();
        decisionNode.put("mainAgentStepId", mainAgentStepId);
        decisionNode.put("decisionId", decision.decisionId());
        decisionNode.put("invocationId", decision.invocationId());
        decisionNode.put("observedRunRevision", decision.observedRunRevision());
        decisionNode.put("observedPlanRevision", decision.observedPlanRevision());
        root.put("decision", decisionNode);

        JSONObject plan = new JSONObject();
        plan.put("definitionDigest", bound.planDefinitionDigest());
        plan.put("stableStepId", decision.stableStepId());
        plan.put("position", bound.planPosition());
        plan.put("operationKind", bound.operationKind());
        plan.put("executorKind", bound.executorKind());
        plan.put("bindingCode", bound.bindingCode());
        plan.put("bindingVersion", bound.bindingVersion());
        plan.put("retryClass", bound.retryClass());
        plan.put("approvalScope", bound.approvalScope());
        plan.put("planStep", JSON.parseObject(bound.planStepJson()));
        root.put("plan", plan);

        JSONObject context = new JSONObject();
        context.put("digest", manifest.digest());
        context.put("manifestJson", manifest.manifestJson());
        context.put("targetRefs", JSON.parseArray(manifest.targetRefsJson()));
        if (!Objects.equals(manifest.executionManifestJson(), manifest.manifestJson())) {
            require(Objects.equals(manifest.executionDigest(),
                    SecureUtil.sha256(manifest.executionManifestJson())), "OPERATION_EXECUTION_CONTEXT_CHANGED");
            JSONObject execution = new JSONObject();
            execution.put("digest", manifest.executionDigest());
            execution.put("manifestJson", manifest.executionManifestJson());
            execution.put("targetRefs", JSON.parseArray(manifest.executionTargetRefsJson()));
            context.put("execution", execution);
        }
        root.put("context", context);
        root.put("operationScope", JSON.parseObject(JSON.toJSONString(operationScope)));

        JSONObject policy = new JSONObject();
        policy.put("authorizationId", admission.authorizationId());
        policy.put("authorizationRevision", admission.authorizationRevision());
        policy.put("revision", admission.policyRevision());
        policy.put("mode", admission.policyMode());
        policy.put("scopeDigest", admission.scopeDigest());
        root.put("policy", policy);

        if (decision.isTool())
        {
            require(prepared != null, "TOOL_PREPARATION_MISSING");
            JSONObject tool = new JSONObject();
            tool.put("schemaDigest", bound.schemaDigest());
            tool.put("sideEffectLevel", bound.sideEffectLevel());
            tool.put("billable", bound.billable());
            tool.put("argumentsJson", prepared.getArgumentsJson());
            tool.put("argumentsDigest", prepared.getArgumentsDigest());
            tool.put("preparedPayloadJson", prepared.getPreparedPayloadJson());
            tool.put("preparedPayloadDigest", prepared.getPreparedPayloadDigest());
            if (prepared.getBillingIntent() != null)
                tool.put("billingIntent", JSON.parseObject(JSON.toJSONString(prepared.getBillingIntent())));
            root.put("tool", tool);
        }
        else
        {
            JSONObject skill = new JSONObject();
            skill.put("skillVersionId", decision.skillVersionId());
            skill.put("packageDigest", decision.packageDigest());
            skill.put("methodInputContractVersion", 6);
            String input = AssistantPlanExecutionJson.canonical(decision.arguments());
            skill.put("inputJson", input);
            skill.put("inputDigest", SecureUtil.sha256(input));
            if (conversationMemory != null) {
                com.aid.assistant.skill.AssistantFixedSkillInputAdapter.requireMemoryContext(conversationMemory);
                String memoryJson = AssistantPlanExecutionJson.canonical(conversationMemory);
                skill.put("conversationMemoryJson", memoryJson);
                skill.put("conversationMemoryDigest", SecureUtil.sha256(memoryJson));
            }
            root.put("skill", skill);
        }
        String json = AssistantPlanExecutionJson.canonical(root);
        return new Encoded(json, SecureUtil.sha256(json));
    }

    Persisted decode(String value)
    {
        try
        {
            JSONObject root = JSON.parseObject(value);
            require(root != null && SCHEMA.equals(root.getString("schemaVersion")), "OPERATION_SCOPE_INVALID");
            require(Objects.equals(value, AssistantPlanExecutionJson.canonical(root)), "OPERATION_SCOPE_NOT_CANONICAL");
            if(root.containsKey(AssistantOperationQuoteLineage.FIELD))
                AssistantOperationQuoteLineage.requireShape(root.getJSONObject(AssistantOperationQuoteLineage.FIELD));
            JSONObject decision = required(root, "decision");
            JSONObject plan = required(root, "plan");
            JSONObject context = required(root, "context");
            JSONObject policy = required(root, "policy");
            AssistantBudgetOperationScope operationScope = root.getObject(
                    "operationScope", AssistantBudgetOperationScope.class);
            require(operationScope != null, "OPERATION_SCOPE_INVALID");
            JSONObject tool = root.getJSONObject("tool");
            JSONObject skill = root.getJSONObject("skill");
            require((tool == null) != (skill == null), "OPERATION_SCOPE_INVALID");
            require(StrUtil.isNotBlank(context.getString("manifestJson"))
                    && Objects.equals(context.getString("digest"),
                    SecureUtil.sha256(context.getString("manifestJson"))), "OPERATION_CONTEXT_CHANGED");
            JSONObject execution = context.getJSONObject("execution");
            if (execution != null) {
                require(StrUtil.isNotBlank(execution.getString("manifestJson"))
                        && Objects.equals(execution.getString("digest"),
                        SecureUtil.sha256(execution.getString("manifestJson")))
                        && execution.getJSONArray("targetRefs") != null,
                        "OPERATION_EXECUTION_CONTEXT_CHANGED");
            }
            if (tool != null)
            {
                require(StrUtil.isNotBlank(tool.getString("argumentsJson"))
                        && Objects.equals(tool.getString("argumentsDigest"),
                        SecureUtil.sha256(tool.getString("argumentsJson")))
                        && StrUtil.isNotBlank(tool.getString("preparedPayloadJson"))
                        && Objects.equals(tool.getString("preparedPayloadDigest"),
                        SecureUtil.sha256(tool.getString("preparedPayloadJson"))),
                        "TOOL_PREPARATION_CHANGED");
            }
            else
            {
                require(!skill.containsKey("methodInputContractVersion")
                        || skill.get("methodInputContractVersion") instanceof Integer revision && revision == 6,
                        "SKILL_INPUT_CONTRACT_INVALID");
                require(StrUtil.isNotBlank(skill.getString("inputJson"))
                        && Objects.equals(skill.getString("inputDigest"),
                        SecureUtil.sha256(skill.getString("inputJson"))), "SKILL_INPUT_CHANGED");
                if (skill.containsKey("conversationMemoryJson") || skill.containsKey("conversationMemoryDigest")) {
                    String memoryJson = skill.getString("conversationMemoryJson");
                    require(StrUtil.isNotBlank(memoryJson) && Objects.equals(SecureUtil.sha256(memoryJson),
                            skill.getString("conversationMemoryDigest")), "SKILL_MEMORY_CHANGED");
                    com.aid.assistant.skill.AssistantFixedSkillInputAdapter.requireMemoryContext(JSON.parseObject(memoryJson));
                }
            }
            RuntimeToolBillingIntent billingIntent = tool == null || tool.getJSONObject("billingIntent") == null
                    ? null : tool.getJSONObject("billingIntent").toJavaObject(RuntimeToolBillingIntent.class);
            return new Persisted(value, SecureUtil.sha256(value), decision, plan, context, policy,
                    operationScope, tool, skill, billingIntent);
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw new ServiceException("OPERATION_SCOPE_INVALID");
        }
    }

    void requireIdentity(Persisted persisted, Long mainAgentStepId,
                         AssistantPlanExecutionDecision decision,
                         BoundOperation bound, String contextDigest)
    {
        require(Objects.equals(persisted.decision().getLong("mainAgentStepId"), mainAgentStepId)
                && Objects.equals(persisted.decision().getString("decisionId"), decision.decisionId())
                && Objects.equals(persisted.decision().getString("invocationId"), decision.invocationId())
                && Objects.equals(persisted.decision().getLong("observedRunRevision"), decision.observedRunRevision())
                && Objects.equals(persisted.decision().getLong("observedPlanRevision"), decision.observedPlanRevision())
                && Objects.equals(persisted.plan().getString("stableStepId"), decision.stableStepId())
                && Objects.equals(persisted.plan().getString("definitionDigest"), bound.planDefinitionDigest())
                && Objects.equals(persisted.plan().getString("operationKind"), bound.operationKind())
                && Objects.equals(persisted.plan().getString("executorKind"), bound.executorKind())
                && Objects.equals(persisted.plan().getString("bindingCode"), bound.bindingCode())
                && Objects.equals(persisted.plan().getString("bindingVersion"), bound.bindingVersion())
                && Objects.equals(persisted.context().getString("digest"), contextDigest)
                && (persisted.tool() == null || Objects.equals(persisted.tool().getString("schemaDigest"),
                bound.schemaDigest()) && Objects.equals(persisted.tool().getString("sideEffectLevel"),
                bound.sideEffectLevel()) && Objects.equals(persisted.tool().getBoolean("billable"),
                bound.billable()))
                && (persisted.skill() == null || Objects.equals(persisted.skill().getLong("skillVersionId"),
                bound.skillVersionId()) && Objects.equals(persisted.skill().getString("packageDigest"),
                bound.packageDigest())),
                "OPERATION_SCOPE_CHANGED");
    }

    private JSONObject required(JSONObject root, String name)
    {
        JSONObject value = root.getJSONObject(name);
        require(value != null, "OPERATION_SCOPE_INVALID");
        return value;
    }

    private void require(boolean condition, String code)
    {
        if (!condition) throw new ServiceException(code);
    }

    record Encoded(String json, String digest) { }

    record Persisted(String json,
                     String digest,
                     JSONObject decision,
                     JSONObject plan,
                     JSONObject context,
                     JSONObject policy,
                     AssistantBudgetOperationScope operationScope,
                     JSONObject tool,
                     JSONObject skill,
                     RuntimeToolBillingIntent billingIntent)
    {
        String executionManifestJson() {
            JSONObject execution = context.getJSONObject("execution");
            return execution == null ? context.getString("manifestJson") : execution.getString("manifestJson");
        }
        boolean isTool() { return tool != null; }
        boolean billable() { return tool != null && Boolean.TRUE.equals(tool.getBoolean("billable")); }
        String requestDigest()
        {
            if (billingIntent != null) return billingIntent.getRequestDigest();
            if (tool != null) return tool.getString("preparedPayloadDigest");
            return skill.getString("inputDigest");
        }
        String quoteId() { return billingIntent == null ? null : billingIntent.getQuoteId(); }
        String quoteDigest() { return billingIntent == null ? null : billingIntent.getQuoteDigest(); }
        java.util.Date quoteExpiresAt()
        {
            return billingIntent == null ? null : billingIntent.getQuoteExpiresAt();
        }
        java.math.BigDecimal quotedUpperBound()
        {
            return billingIntent == null ? java.math.BigDecimal.ZERO : billingIntent.getQuotedUpperBound();
        }
        String preparedPayloadJson() { return tool == null ? null : tool.getString("preparedPayloadJson"); }
        String preparedPayloadDigest() { return tool == null ? null : tool.getString("preparedPayloadDigest"); }
        String argumentsJson() { return tool == null ? null : tool.getString("argumentsJson"); }
        String argumentsDigest() { return tool == null ? null : tool.getString("argumentsDigest"); }
    }
}
