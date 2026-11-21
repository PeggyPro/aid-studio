package com.aid.billing.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** Run预算的只读投影。 */
public final class BillingBudgetViews {

    private BillingBudgetViews() {
    }

    @Data
    public static class AuthorizationView {
        private Long id;
        private Long payerUserId;
        private Long runtimeRunId;
        private BigDecimal approvedTotal;
        private BigDecimal reservedAmount;
        private BigDecimal settledAmount;
        private BigDecimal availableAmount;
        private String currencyUnit;
        private Integer precisionScale;
        private String status;
        private Long revision;
        private Long stateRevision;
        private String policyDigest;
        private Date expiresAt;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    public static class AuthorizationPageView {
        private long total;
        private int pageNum;
        private int pageSize;
        private List<AuthorizationView> rows;
    }

    @Data
    public static class ReservationView {
        private Long id;
        private Long authorizationId;
        private Long authorizationRevision;
        private Long runtimeRunId;
        private Long runtimeStepId;
        private String stableStepKey;
        private Long approvalId;
        private Integer attemptNo;
        private String operationKind;
        private String logicalCallKey;
        private String requestDigest;
        private String quoteId;
        private String quoteDigest;
        private Date quoteExpiresAt;
        private Long mediaTaskId;
        private String billingTraceId;
        private BigDecimal quotedUpperBound;
        private BigDecimal reservedAmount;
        private BigDecimal walletFrozenAmount;
        private BigDecimal settledAmount;
        private BigDecimal releasedAmount;
        private BigDecimal walletRefundedAmount;
        private String state;
        private String settlementKey;
        private Long revision;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    public static class SubmissionClaimResult {
        /** 仅本次从RESERVED推进到INFLIGHT时为true，只有该结果允许首次出站。 */
        private boolean newlyClaimed;
        /** 已处于INFLIGHT时为true；调用方必须进入对账，不能据此重复出站。 */
        private boolean alreadyInflight;
        private ReservationView reservation;
    }

    @Data
    public static class TextQuoteView {
        private com.aid.media.dto.MediaTextGenerateRequest normalizedRequest;
        private String modelCode;
        private Long modelConfigVersion;
        private Integer billingVersion;
        private String meterType;
        private String skuCode;
        private String quoteId;
        private String requestDigest;
        private String quoteDigest;
        private BigDecimal estimatedAmount;
        private BigDecimal quotedUpperBound;
        private Date expiresAt;
        private boolean hardUpperBoundAvailable;
        private String unavailableReason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ImageQuoteView extends QuoteView {
        private com.aid.media.dto.MediaImageGenerateRequest normalizedRequest;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class VideoQuoteView extends QuoteView {
        private com.aid.media.dto.MediaVideoGenerateRequest normalizedRequest;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AudioQuoteView extends QuoteView {
        private com.aid.media.dto.MediaAudioGenerateRequest normalizedRequest;
    }

    /** Common immutable quote projection shared by non-text media request types. */
    @Data
    public static class QuoteView {
        private String modelCode;
        private Long modelConfigVersion;
        private Integer billingVersion;
        private String meterType;
        private String skuCode;
        private String quoteId;
        private String requestDigest;
        private String quoteDigest;
        private BigDecimal estimatedAmount;
        private BigDecimal quotedUpperBound;
        private Date expiresAt;
        private boolean hardUpperBoundAvailable;
        private String unavailableReason;
    }
}
