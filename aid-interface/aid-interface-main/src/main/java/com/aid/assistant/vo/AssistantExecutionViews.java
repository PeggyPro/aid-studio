package com.aid.assistant.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** User-safe quote and approval projections. */
public final class AssistantExecutionViews
{
    private AssistantExecutionViews()
    {
    }

    @Data
    public static class TextQuote
    {
        private String quoteId;
        private String modelCode;
        private String estimatedAmount;
        private String quotedUpperBound;
        private String currencyUnit;
        private int precisionScale;
        private String expiresAt;
        private boolean hardUpperBoundAvailable;
        private RunBudgetScope runBudgetScope;
    }

    @Data
    public static class PlanningApproval
    {
        private String approvalId;
        private long approvalRevision;
        private String runId;
        private long runRevision;
        private String scopeKind;
        private String status;
        private String nextAction;
        private TextQuote quote;
        private long lastEventSeq;
    }

    @Data
    public static class ApprovalConfirmed
    {
        private String approvalId;
        private long approvalRevision;
        private String authorizationId;
        private long authorizationRevision;
        private String runId;
        private long runRevision;
        private String status;
        private String nextAction;
        private long lastEventSeq;
    }

    @Data
    public static class PlanConfirmed
    {
        private String runId;
        private long runRevision;
        private long planRevision;
        private String planStatus;
        private String runStatus;
        private String nextAction;
        private long lastEventSeq;
    }

    @Data
    public static class OperationApprovalConfirmed
    {
        private String runId;
        private long runRevision;
        private String approvalId;
        private long approvalRevision;
        private String stepId;
        private String reservationId;
        private String status;
        private String nextAction;
        private long lastEventSeq;
    }

    @Data
    public static class BudgetPolicy
    {
        private String runId;
        private long runRevision;
        private long policyRevision;
        private String authorizationId;
        private long authorizationRevision;
        private String mode;
        private String status;
        private String absoluteMaxCharge;
        private String perCallMaxCharge;
        private String reserved;
        private String settled;
        private String remaining;
        private String currencyUnit;
        private int precisionScale;
        private int maxIterations;
        private int usedIterations;
        private int maxOperations;
        private int usedOperations;
        private String expiresAt;
        private List<String> allowedToolCodes = new ArrayList<>();
        private List<String> allowedModelCodes = new ArrayList<>();
        private List<String> allowedCapabilityCodes = new ArrayList<>();
        private List<String> allowedMediaTypes = new ArrayList<>();
        private Object targetRefs;
        private List<String> automaticForbiddenOperationKinds = new ArrayList<>();
        private String scopeDigest;
        private String scopeState;
        private boolean canRenew;
        private String renewBlockReason;
        private boolean renewalNeedsConfirmation;
        private RunBudgetScope availableRunBudgetScope;
        private long lastEventSeq;
    }

    @Data
    public static class RunBudgetScope
    {
        private String scopeDigest;
        private List<String> availableModes = new ArrayList<>();
        private List<String> allowedToolCodes = new ArrayList<>();
        private List<String> allowedModelCodes = new ArrayList<>();
        private List<String> allowedCapabilityCodes = new ArrayList<>();
        private List<String> allowedMediaTypes = new ArrayList<>();
        private Object targetRefs;
        private List<String> automaticForbiddenOperationKinds = new ArrayList<>();
        private long maxTtlSeconds;
        private int maxIterations;
        private int maxOperations;
        private int amountScale;
        /** Server-configured bounded policy for the free-canvas conversation entry. */
        private ConversationBudget conversationBudget;
    }

    @Data
    public static class ConversationBudget
    {
        private String absoluteMaxCharge;
        private String perCallMaxCharge;
        private int maxIterations;
        private int maxOperations;
        private long ttlSeconds;
    }

    @Data
    public static class BudgetRevision
    {
        private long runRevision;
        private BudgetPolicy budgetPolicy;
        private List<String> invalidatedApprovalIds = new ArrayList<>();
        private boolean renewedExpired;
    }
}
