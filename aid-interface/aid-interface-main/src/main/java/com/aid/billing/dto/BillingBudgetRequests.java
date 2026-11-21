package com.aid.billing.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/** Run预算写操作的类型化请求。 */
public final class BillingBudgetRequests {

    private BillingBudgetRequests() {
    }

    @Data
    public static class AuthorizationQuery {
        private Long authorizationId;
        private Long runtimeRunId;
        private Long payerUserId;
        private String status;
        private Integer pageNum;
        private Integer pageSize;
    }

    @Data
    public static class CreateAuthorizationRequest {
        private Long payerUserId;
        private Long runtimeRunId;
        private BigDecimal approvedTotal;
        private String policyDigest;
        private Date expiresAt;
        private String operator;
    }

    @Data
    public static class ReviseAuthorizationRequest {
        private Long authorizationId;
        private Long payerUserId;
        private Long expectedRevision;
        private BigDecimal approvedTotal;
        private String policyDigest;
        private Date expiresAt;
        private String operator;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RenewAuthorizationRequest extends ReviseAuthorizationRequest {
        private Long runtimeRunId;
        private Long expectedStateRevision;
        /** Expiry read from the policy locked by the renewal transaction. */
        private Date priorPolicyExpiresAt;
    }

    @Data
    public static class ReserveCallRequest {
        private Long authorizationId;
        private Long authorizationRevision;
        private Long payerUserId;
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
        private BigDecimal quotedUpperBound;
        private String operator;
    }

    @Data
    public static class SubmissionClaimRequest {
        private Long authorizationId;
        private Long authorizationRevision;
        private Long reservationId;
        private Long expectedReservationRevision;
        private Long runtimeRunId;
        private Long runtimeStepId;
        private String stableStepKey;
        private Integer attemptNo;
        private String logicalCallKey;
        private Long mediaTaskId;
        private String operator;
    }

    @Data
    public static class ReleaseUnsubmittedReservationRequest {
        private Long authorizationId;
        private Long authorizationRevision;
        private Long reservationId;
        private Long expectedReservationRevision;
        private Long payerUserId;
        private Long runtimeRunId;
        private Long runtimeStepId;
        private String stableStepKey;
        private Long approvalId;
        private Integer attemptNo;
        private String operationKind;
        private String logicalCallKey;
        private String operator;
    }
}
