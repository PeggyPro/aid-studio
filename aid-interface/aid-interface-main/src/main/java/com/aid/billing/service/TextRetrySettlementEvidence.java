package com.aid.billing.service;

import com.aid.aid.domain.media.AidMediaTask;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/** A retry purchases a new call only after the previous wallet trace is closed. */
public final class TextRetrySettlementEvidence {
    private TextRetrySettlementEvidence() { }

    public static boolean closed(AidMediaTask task, IAccountUpdateService.WalletLedgerEvidence ledger) {
        if (task == null || !"TEXT".equals(task.getMediaType())
                || !Set.of("FAILED", "SUCCEEDED").contains(Objects.toString(task.getStatus(), ""))) return false;
        BigDecimal frozen = zero(task.getFrozenAmount());
        BigDecimal cost = zero(task.getActualCost());
        if (frozen.signum() < 0 || cost.signum() < 0) return false;
        if (frozen.signum() == 0)
            return cost.signum() == 0 && (task.getBillingStatus() == null
                    || Set.of("SUCCESS", "FAILED").contains(task.getBillingStatus()))
                    && (ledger == null || ledger.empty());
        if (!Set.of("SUCCESS", "FAILED").contains(Objects.toString(task.getBillingStatus(), ""))
                || ledger == null || !ledger.valid()
                || !Objects.equals(task.getUserId(), ledger.userId())
                || task.getBillingTraceId() == null
                || !Objects.equals(task.getBillingTraceId(), ledger.traceId())) return false;
        return frozen.compareTo(ledger.frozenAmount()) == 0
                && frozen.compareTo(ledger.grossConsumedAmount().add(ledger.unfrozenAmount())
                        .add(ledger.settleUnfrozenAmount())) == 0
                && cost.compareTo(ledger.netConsumedAmount()) == 0;
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
