package com.aid.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** User commands for the quote and approval boundaries. */
public final class AssistantExecutionRequests
{
    private static final String KEY_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}";

    private AssistantExecutionRequests()
    {
    }

    @Data
    public static class PlanningQuote
    {
        @NotNull @Positive private Long runId;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
    }

    @Data
    public static class ApprovalConfirm
    {
        @NotNull @Positive private Long approvalId;
        @NotNull @Min(0) private Long expectedApprovalRevision;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
        @Valid private RunBudgetPolicy runBudget;
        /** Explicit consent to the first newly quoted execution after an expired authorization was renewed. */
        private Boolean confirmRenewedExecution;
    }

    @Data
    public static class ApprovalDetail
    {
        @NotNull @Positive private Long runId;
    }

    @Data
    public static class PlanConfirm
    {
        @NotNull @Positive private Long runId;
        @NotNull @Min(1) private Long planRevision;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String definitionDigest;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
    }

    @Data
    public static class OperationApprovalConfirm
    {
        @NotNull @Positive private Long runId;
        @NotNull @Positive private Long approvalId;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotNull @Min(0) private Long expectedApprovalRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
        private Boolean confirmRenewedExecution;
    }

    @Data
    public static class OperationQuote
    {
        @NotNull @Positive private Long runId;
        @NotNull @Positive private Long approvalId;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotNull @Min(0) private Long expectedApprovalRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
        @Pattern(regexp = KEY_PATTERN) private String referenceRevalidationToken;
    }

    @Data
    public static class RunBudgetPolicy
    {
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String scopeDigest;
        @NotBlank private String mode;
        @NotNull @DecimalMin("0.0000") private BigDecimal absoluteMaxCharge;
        @NotNull @DecimalMin("0.0000") private BigDecimal perCallMaxCharge;
        @NotBlank private String expiresAt;
        @NotNull @Min(1) private Integer maxIterations;
        @NotNull @Min(1) private Integer maxOperations;
        @NotNull @Size(max = 200) private List<@NotBlank String> allowedToolCodes = new ArrayList<>();
        @NotNull @Size(max = 100) private List<@NotBlank String> allowedModelCodes = new ArrayList<>();
        @NotNull @Size(max = 100) private List<@NotBlank String> allowedCapabilityCodes = new ArrayList<>();
        @NotNull @Size(max = 20) private List<@NotBlank String> allowedMediaTypes = new ArrayList<>();
        @NotNull @Valid private List<AssistantRequests.ContextRef> targetRefs = new ArrayList<>();
        @NotNull @Size(max = 100) private List<@NotBlank String> automaticForbiddenOperationKinds = new ArrayList<>();
    }

    @Data
    public static class BudgetDetail
    {
        @NotNull @Positive private Long runId;
    }

    @Data
    public static class BudgetRevise
    {
        @NotNull @Positive private Long runId;
        @NotNull @Min(0) private Long expectedRunRevision;
        @NotNull @Min(0) private Long expectedPolicyRevision;
        @NotNull @Min(0) private Long expectedAuthorizationRevision;
        @NotBlank @Pattern(regexp = KEY_PATTERN) private String clientRequestId;
        @NotNull @Valid private RunBudgetPolicy runBudget;
        /** Only an explicit true requests renewal of an expired authorization. */
        private Boolean renewExpired;
    }
}
