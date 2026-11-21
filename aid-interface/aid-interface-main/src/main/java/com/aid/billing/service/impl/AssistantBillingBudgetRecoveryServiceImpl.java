package com.aid.billing.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.domain.AidBillingAuthorization;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.enums.BillingReservationState;
import com.aid.billing.mapper.AidBillingAuthorizationMapper;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetRecoveryService;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.billing.service.BillingFacadeService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.TextFailureBillingPolicy;
import com.aid.media.reconciliation.MediaSubmissionReconciliationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Budget recovery is deliberately separate from the legacy wallet-only scanner. Candidate rows come from
 * non-terminal reservations joined to terminal media tasks. Every wallet transition still goes through the
 * existing billing facade and every projection transition through {@link AssistantBillingBudgetService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantBillingBudgetRecoveryServiceImpl implements AssistantBillingBudgetRecoveryService {
    private static final List<String> RECOVERABLE_RESERVATION_STATES = List.of(
            BillingReservationState.RESERVED.name(),
            BillingReservationState.INFLIGHT.name(),
            BillingReservationState.RECONCILING.name());

    private final AidBillingAuthorizationReservationMapper reservationMapper;
    private final AidBillingAuthorizationMapper authorizationMapper;
    private final AidMediaTaskMapper taskMapper;
    private final BillingFacadeService billingFacadeService;
    private final AssistantBillingBudgetService budgetService;
    private final IAccountUpdateService accountUpdateService;
    private final MediaSubmissionReconciliationService submissionReconciliationService;

    @Override
    public int reconcileTerminalMediaTasks(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize <= 0 ? 50 : batchSize, 200));
        List<Long> taskIds = reservationMapper.selectRecoverableMediaTaskIds(
                RECOVERABLE_RESERVATION_STATES, limit);
        int recovered = 0;
        for (Long taskId : taskIds) {
            AidMediaTask task = taskMapper.selectById(taskId);
            if (task == null || !BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())
                    || !terminal(task)) {
                continue;
            }
            try {
                if (reconcile(task)) {
                    recovered++;
                }
            } catch (RuntimeException failure) {
                defer(task, failure.getClass().getSimpleName());
                log.error("Budget media billing recovery failed, taskId={}", taskId, failure);
            }
        }
        return recovered;
    }

    private boolean reconcile(AidMediaTask task) {
        Optional<RecoveryIdentity> identity = recoveryIdentity(task);
        if (identity.isEmpty()) {
            defer(task, "BUDGET_RECOVERY_IDENTITY_INVALID");
            return false;
        }
        AidBillingAuthorizationReservation reservation = identity.get().reservation();
        IAccountUpdateService.WalletLedgerEvidence ledger =
                accountUpdateService.inspectLedger(task.getBillingTraceId());
        if (positive(reservation.getWalletFrozenAmount())) {
            if (!baseLedgerMatches(task, reservation, ledger)) {
                defer(task, "WALLET_LEDGER_IDENTITY_INVALID");
                return false;
            }
        } else if (ledger == null || !ledger.empty()) {
            defer(task, "ZERO_WALLET_LEDGER_NOT_EMPTY");
            return false;
        }
        String billingStatus = task.getBillingStatus();
        if (MediaBillingStatus.SUCCESS.name().equals(billingStatus)) {
            if (!successfulLedgerMatches(task, reservation, ledger)) {
                defer(task, "WALLET_CONSUME_EVIDENCE_REQUIRED");
                return false;
            }
            budgetService.settleFromMediaTask(task, "system");
            return true;
        }
        if (MediaBillingStatus.FAILED.name().equals(billingStatus)) {
            if (!failedLedgerMatches(task, reservation, ledger)) {
                defer(task, "WALLET_REFUND_EVIDENCE_REQUIRED");
                return false;
            }
            budgetService.refundFromMediaTask(task, "system");
            return true;
        }

        if (positive(reservation.getWalletFrozenAmount())
                && !recoverableInProgressLedger(billingStatus, reservation, ledger)) {
            defer(task, "WALLET_LEDGER_STATE_INVALID");
            return false;
        }

        Optional<MediaSubmissionReconciliationService.AuthoritativeTerminalBillingEvidence> evidence =
                submissionReconciliationService.loadTerminalBillingEvidence(task);
        boolean cancelledBeforeProvider = cancellationProof(task);
        if (evidence.isEmpty() && !cancelledBeforeProvider) {
            defer(task, "AUTHORITATIVE_TERMINAL_EVIDENCE_REQUIRED");
            return false;
        }
        Map<String, Object> usage = evidence.map(
                MediaSubmissionReconciliationService.AuthoritativeTerminalBillingEvidence::usageData)
                .orElse(Map.of());

        if (MediaBillingStatus.SETTLING.name().equals(billingStatus)) {
            return billingFacadeService.settleBilling(task, usage);
        }
        if (MediaBillingStatus.REFUNDING.name().equals(billingStatus)) {
            return billingFacadeService.refundBilling(task);
        }
        if (!MediaBillingStatus.FROZEN.name().equals(billingStatus)) {
            defer(task, "BILLING_STATUS_NOT_RECOVERABLE");
            return false;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
            return billingFacadeService.settleBilling(task, usage);
        }
        if (MediaTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return billingFacadeService.refundBilling(task);
        }
        if (MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
            boolean providerCallBillable = "TEXT".equalsIgnoreCase(task.getMediaType())
                    && TextFailureBillingPolicy.shouldSettle(false,
                    evidence.isPresent() || task.getUpstreamAcceptTime() != null,
                    task.getProtocol(), task.getErrorDetailJson(), usage);
            return providerCallBillable
                    ? billingFacadeService.settleBilling(task, usage)
                    : billingFacadeService.refundBilling(task);
        }
        return false;
    }

    private boolean cancellationProof(AidMediaTask task) {
        return MediaTaskStatus.CANCELLED.name().equals(task.getStatus())
                && StrUtil.isBlank(task.getProviderTaskId())
                && task.getUpstreamAcceptTime() == null
                && "RUNTIME_STOPPED_BEFORE_PROVIDER_ENTRY".equals(task.getErrorMessage());
    }

    private Optional<RecoveryIdentity> recoveryIdentity(AidMediaTask task) {
        if (task == null || task.getId() == null || StrUtil.isBlank(task.getBillingSnapshotJson())) {
            return Optional.empty();
        }
        BillingSnapshot snapshot;
        try {
            snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        if (snapshot == null || !BillingBudgetContext.MODE_RUN_AUTHORIZATION.equals(snapshot.getBudgetMode())
                || snapshot.getBillingAuthorizationId() == null
                || snapshot.getBillingAuthorizationRevision() == null
                || snapshot.getBillingReservationId() == null
                || snapshot.getBillingReservationRevision() == null
                || snapshot.getRuntimeRunId() == null || snapshot.getRuntimeStepId() == null
                || StrUtil.isBlank(snapshot.getStableStepKey()) || snapshot.getBillingAttemptNo() == null
                || StrUtil.isBlank(snapshot.getLogicalCallKey()) || StrUtil.isBlank(snapshot.getRequestDigest())
                || StrUtil.isBlank(snapshot.getQuoteId()) || StrUtil.isBlank(snapshot.getQuoteDigest())
                || snapshot.getQuoteExpiresAt() == null || snapshot.getAuthorizedChargeCap() == null) {
            return Optional.empty();
        }
        AidBillingAuthorizationReservation reservation = reservationMapper.selectById(
                snapshot.getBillingReservationId());
        AidBillingAuthorization authorization = authorizationMapper.selectById(
                snapshot.getBillingAuthorizationId());
        if (reservation == null || authorization == null
                || !"0".equals(reservation.getDelFlag()) || !"0".equals(authorization.getDelFlag())
                || !RECOVERABLE_RESERVATION_STATES.contains(reservation.getState())
                || !Objects.equals(reservation.getAuthorizationId(), authorization.getId())
                || !Objects.equals(reservation.getAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(), snapshot.getBillingAuthorizationRevision())
                || reservation.getRevision() == null
                || !validReservationRevision(snapshot.getBillingReservationRevision(), reservation)
                || authorization.getRevision() == null
                || authorization.getRevision() < reservation.getAuthorizationRevision()
                || !Objects.equals(authorization.getRuntimeRunId(), snapshot.getRuntimeRunId())
                || !Objects.equals(authorization.getPayerUserId(), task.getUserId())
                || !Objects.equals(reservation.getMediaTaskId(), task.getId())
                || StrUtil.isBlank(reservation.getBillingTraceId())
                || StrUtil.isBlank(task.getBillingTraceId())
                || !Objects.equals(reservation.getBillingTraceId(), task.getBillingTraceId())
                || !Objects.equals(reservation.getLogicalCallKey(), task.getIdempotencyKey())
                || !Objects.equals(reservation.getLogicalCallKey(), snapshot.getLogicalCallKey())
                || !Objects.equals(reservation.getRuntimeRunId(), snapshot.getRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), snapshot.getRuntimeStepId())
                || !Objects.equals(task.getBizTaskId(), snapshot.getRuntimeStepId())
                || !Objects.equals(reservation.getStableStepKey(), snapshot.getStableStepKey())
                || !Objects.equals(reservation.getAttemptNo(), snapshot.getBillingAttemptNo())
                || !Objects.equals(reservation.getRequestDigest(), snapshot.getRequestDigest())
                || !Objects.equals(task.getRequestHash(), snapshot.getRequestDigest())
                || !Objects.equals(reservation.getQuoteId(), snapshot.getQuoteId())
                || !Objects.equals(reservation.getQuoteDigest(), snapshot.getQuoteDigest())
                || !sameDate(reservation.getQuoteExpiresAt(), snapshot.getQuoteExpiresAt())
                || !sameAmount(reservation.getReservedAmount(), snapshot.getAuthorizedChargeCap())
                || !sameAmount(reservation.getWalletFrozenAmount(), task.getFrozenAmount())) {
            return Optional.empty();
        }
        return Optional.of(new RecoveryIdentity(snapshot, reservation));
    }

    private boolean baseLedgerMatches(AidMediaTask task,
                                      AidBillingAuthorizationReservation reservation,
                                      IAccountUpdateService.WalletLedgerEvidence ledger) {
        return ledger != null && ledger.valid()
                && Objects.equals(task.getUserId(), ledger.userId())
                && sameAmount(reservation.getWalletFrozenAmount(), ledger.frozenAmount());
    }

    private boolean successfulLedgerMatches(AidMediaTask task,
                                            AidBillingAuthorizationReservation reservation,
                                            IAccountUpdateService.WalletLedgerEvidence ledger) {
        BigDecimal frozen = amount(reservation.getWalletFrozenAmount());
        BigDecimal actual = task.getActualCost();
        if (actual == null || actual.signum() < 0 || actual.compareTo(frozen) > 0) return false;
        if (frozen.signum() == 0) return actual.signum() == 0;
        return ledger != null
                && sameAmount(ledger.grossConsumedAmount(), frozen)
                && ledger.settleRefundedAmount().compareTo(ledger.grossConsumedAmount()) <= 0
                && sameAmount(ledger.netConsumedAmount(), actual)
                && zero(ledger.unfrozenAmount())
                && zero(ledger.settleUnfrozenAmount());
    }

    private boolean failedLedgerMatches(AidMediaTask task,
                                        AidBillingAuthorizationReservation reservation,
                                        IAccountUpdateService.WalletLedgerEvidence ledger) {
        BigDecimal frozen = amount(reservation.getWalletFrozenAmount());
        if (frozen.signum() == 0) {
            return task.getActualCost() == null || task.getActualCost().signum() == 0;
        }
        return ledger != null
                && sameAmount(ledger.unfrozenAmount(), frozen)
                && zero(ledger.grossConsumedAmount())
                && zero(ledger.settleRefundedAmount())
                && zero(ledger.settleUnfrozenAmount())
                && zero(ledger.netConsumedAmount());
    }

    private boolean recoverableInProgressLedger(String billingStatus,
                                                AidBillingAuthorizationReservation reservation,
                                                IAccountUpdateService.WalletLedgerEvidence ledger) {
        BigDecimal frozen = amount(reservation.getWalletFrozenAmount());
        if (MediaBillingStatus.FROZEN.name().equals(billingStatus)) {
            return zero(ledger.grossConsumedAmount()) && zero(ledger.settleRefundedAmount())
                    && zero(ledger.unfrozenAmount()) && zero(ledger.settleUnfrozenAmount());
        }
        if (MediaBillingStatus.SETTLING.name().equals(billingStatus)) {
            boolean consumeNotStarted = zero(ledger.grossConsumedAmount())
                    && zero(ledger.settleRefundedAmount());
            boolean consumeCommitted = sameAmount(ledger.grossConsumedAmount(), frozen)
                    && ledger.settleRefundedAmount().compareTo(frozen) <= 0;
            return (consumeNotStarted || consumeCommitted)
                    && zero(ledger.unfrozenAmount()) && zero(ledger.settleUnfrozenAmount());
        }
        if (MediaBillingStatus.REFUNDING.name().equals(billingStatus)) {
            return zero(ledger.grossConsumedAmount()) && zero(ledger.settleRefundedAmount())
                    && zero(ledger.settleUnfrozenAmount())
                    && (zero(ledger.unfrozenAmount()) || sameAmount(ledger.unfrozenAmount(), frozen));
        }
        return false;
    }

    private boolean validReservationRevision(Long snapshotRevision,
                                             AidBillingAuthorizationReservation reservation) {
        if (snapshotRevision == null || snapshotRevision != 2L) return false;
        long current = reservation.getRevision();
        return BillingReservationState.RESERVED.name().equals(reservation.getState())
                ? current == snapshotRevision
                : BillingReservationState.INFLIGHT.name().equals(reservation.getState())
                    ? current == snapshotRevision + 1L
                    : BillingReservationState.RECONCILING.name().equals(reservation.getState())
                        && (current == snapshotRevision + 1L || current == snapshotRevision + 2L);
    }

    private static boolean sameDate(Date left, Date right) {
        return left != null && right != null && left.getTime() == right.getTime();
    }

    private static boolean sameAmount(java.math.BigDecimal left, java.math.BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean positive(java.math.BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    private static boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record RecoveryIdentity(BillingSnapshot snapshot,
                                    AidBillingAuthorizationReservation reservation) { }

    private boolean terminal(AidMediaTask task) {
        return MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus())
                || MediaTaskStatus.CANCELLED.name().equals(task.getStatus());
    }

    private void defer(AidMediaTask task, String reason) {
        int attempts = Math.max(task.getRetryCount() == null ? 0 : task.getRetryCount(), 0) + 1;
        long delaySeconds = Math.min(3600L, 30L << Math.min(attempts - 1, 7));
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, task.getId());
        update.eq(AidMediaTask::getStatus, task.getStatus());
        update.eq(AidMediaTask::getBillingStatus, task.getBillingStatus());
        update.setSql("retry_count = COALESCE(retry_count, 0) + 1");
        update.set(AidMediaTask::getNextPollTime,
                new Date(System.currentTimeMillis() + delaySeconds * 1000L));
        taskMapper.update(null, update);
        log.warn("Budget media billing recovery deferred, taskId={}, reason={}, delaySeconds={}",
                task.getId(), reason, delaySeconds);
    }
}
