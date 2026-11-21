package com.aid.assistant.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Redacted projections returned by the Assistant governance console. */
public final class AssistantAdminViews
{
    private AssistantAdminViews()
    {
    }

    @Data
    public static class PageView<T>
    {
        private List<T> items = new ArrayList<>();
        private long total;
        private int pageNum;
        private int pageSize;
    }

    @Data
    public static class RunSummary
    {
        private Long id;
        private Long sessionId;
        private Long turnId;
        private Long userId;
        private String moduleCode;
        private String mode;
        private String executorStrategy;
        private String status;
        private Long stateRevision;
        private Long planRevision;
        private String pauseReason;
        private String resumeStatus;
        private Boolean stopRequested;
        private String nextAction;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date nextRetryAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class EventSummary
    {
        private Long id;
        private Long seq;
        private String type;
        private String entityId;
        private Long entityRevision;
        private String causationId;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
    }

    @Data
    public static class RunDetail
    {
        private RunSummary run;
        private List<EventSummary> recentEvents = new ArrayList<>();
        private List<InputRequestSummary> inputRequests = new ArrayList<>();
    }

    @Data
    public static class ToolCatalogItem
    {
        private String code;
        private String contractVersion;
        private String schemaDigest;
        private int schemaVersion;
        private String name;
        private String description;
        private String inputSchemaSummary;
        private String outputSchemaSummary;
        private String requiredPermission;
        private List<String> objectScopes = new ArrayList<>();
        private String sideEffectLevel;
        private boolean billable;
        private String idempotencyLevel;
        private boolean cancellable;
        private String recoveryPolicy;
        private List<String> requiredCapabilities = new ArrayList<>();
        private boolean enabled;
        private String disabledReason;
        private String implementationVersion;
    }

    @Data
    public static class BudgetAuthorizationSummary
    {
        private Long id;
        private Long runId;
        private Long payerUserId;
        private Long revision;
        private BigDecimal approvedTotal;
        private BigDecimal reservedAmount;
        private BigDecimal settledAmount;
        private String currencyUnit;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date expiresAt;
        private String policyDigest;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class BudgetReservationSummary
    {
        private Long id;
        private Long authorizationRevision;
        private String logicalCallKey;
        private Integer attemptNo;
        private String operationKind;
        private Long approvalId;
        private Long runtimeStepId;
        private Long mediaTaskId;
        private String billingTraceId;
        private String requestDigest;
        private String quoteDigest;
        private BigDecimal quotedUpperBound;
        private BigDecimal reservedAmount;
        private BigDecimal walletFrozenAmount;
        private BigDecimal settledAmount;
        private BigDecimal releasedAmount;
        private BigDecimal walletRefundedAmount;
        private String state;
        private String settlementKey;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class BudgetAuthorizationDetail
    {
        private BudgetAuthorizationSummary authorization;
        private List<BudgetReservationSummary> reservations = new ArrayList<>();
    }

    @Data
    public static class InputRequestSummary
    {
        private Long id;
        private Long runId;
        private Long userId;
        private String requestKey;
        private Integer roundNo;
        private String schemaDigest;
        private String contextDigest;
        private Long planRevision;
        private String status;
        private int questionCount;
        private int answeredCount;
        private int unansweredCount;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date suspendedAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date expiresAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class InputQuestionTrace
    {
        private String questionId;
        private String kind;
        private boolean required;
        private String answerState;
        private long draftRevision;
        private int selectedCount;
        private int freeTextLength;
        private String freeTextPreviewRedacted;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class InputRequestDetail
    {
        private InputRequestSummary request;
        private List<InputQuestionTrace> questions = new ArrayList<>();
    }
}
