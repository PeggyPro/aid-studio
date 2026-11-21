package com.aid.assistant.skill;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkillRunStep;
import com.alibaba.fastjson2.JSON;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Validation and stable identity derivation shared by Skill reserve and admission. */
public final class AssistantSkillBudgetIdentity {
    public static final String INVOKE_SOURCE = "ASSISTANT_RUNTIME";
    public static final String BIZ_TASK_TYPE = "SKILL_RUNTIME_STEP";

    private AssistantSkillBudgetIdentity() {
    }

    public static void validate(AssistantSkillBudgetEnvelope value) {
        if (value == null || !positive(value.getAssistantRuntimeRunId())
                || !positive(value.getAssistantParentStepId())
                || !positive(value.getAssistantRuntimeRunFencingToken())
                || !positive(value.getAssistantParentStepFencingToken())
                || blank(value.getAssistantOuterStableStepKey())
                || value.getAssistantOuterStableStepKey().length() > 160
                || value.getAssistantAttemptNo() == null || value.getAssistantAttemptNo() < 0
                || !positive(value.getBillingAuthorizationId())
                || !positive(value.getBillingAuthorizationRevision())
                || !positive(value.getPayerUserId()) || !positive(value.getApprovalId())
                || !positive(value.getSkillVersionId()) || !sha256(value.getPackageDigest())
                || !sha256(value.getApprovedScopeDigest())
                || StrUtil.isBlank(value.getApprovedOperationScopeJson())
                || !sha256(value.getApprovedOperationScopeDigest())
                || !SecureUtil.sha256(value.getApprovedOperationScopeJson())
                .equals(value.getApprovedOperationScopeDigest())) {
            throw new ServiceException("Assistant Skill budget identity is invalid");
        }
        try {
            AssistantBudgetOperationScope scope = JSON.parseObject(
                    value.getApprovedOperationScopeJson(), AssistantBudgetOperationScope.class);
            if (scope == null || blank(scope.getOperationKind()) || scope.getQuotedUpperBound() == null) {
                throw new ServiceException("Assistant Skill approved operation scope is invalid");
            }
        } catch (ServiceException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw new ServiceException("Assistant Skill approved operation scope is invalid");
        }
    }

    public static String stableStepKey(AssistantSkillBudgetEnvelope envelope, AidSkillRunStep step) {
        validate(envelope);
        if (step == null || !positive(step.getId()) || !sha256(step.getStepExecutionId())) {
            throw new ServiceException("Skill step identity is invalid");
        }
        String plain = envelope.getAssistantOuterStableStepKey() + "/skill/" + step.getStepExecutionId();
        return plain.length() <= 128 ? plain
                : "skill:" + SecureUtil.sha256(envelope.getAssistantOuterStableStepKey()
                + "|" + step.getStepExecutionId());
    }

    /** Derive the actual inner Provider call scope from the pinned outer Skill approval. */
    public static AssistantBudgetOperationScope providerScope(AssistantSkillBudgetEnvelope envelope,
                                                               String modelCode, BigDecimal quotedUpperBound) {
        validate(envelope);
        if (blank(modelCode) || quotedUpperBound == null || quotedUpperBound.signum() < 0) {
            throw new ServiceException("Assistant Skill Provider scope is invalid");
        }
        AssistantBudgetOperationScope outer;
        try {
            outer = JSON.parseObject(envelope.getApprovedOperationScopeJson(), AssistantBudgetOperationScope.class);
        } catch (RuntimeException invalid) {
            throw new ServiceException("Assistant Skill approved operation scope is invalid");
        }
        AssistantBudgetOperationScope result = new AssistantBudgetOperationScope();
        result.setOperationKind("SKILL_TEXT");
        result.setModelCode(modelCode);
        result.setCapabilityCodes(List.of("skill.invoke", "text.chat"));
        result.setMediaTypes(List.of("TEXT"));
        result.setTargetRefs(outer.getTargetRefs() == null
                ? List.of() : new ArrayList<>(outer.getTargetRefs()));
        result.setQuotedUpperBound(quotedUpperBound);
        result.setCountsAsIteration(false);
        return result;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
