package com.aid.billing.service;

import com.aid.aid.domain.media.AidMediaTask;
import java.util.Objects;

/** Evidence of a completed historical consume-and-difference-refund, never token usage. */
public final class SettledTextLedgerEvidence {
    public static final String SOURCE = "EXISTING_TEXT_WALLET_SETTLEMENT";
    private SettledTextLedgerEvidence() { }

    public static boolean matches(AidMediaTask task, IAccountUpdateService.WalletLedgerEvidence ledger) {
        return task != null && "TEXT".equals(task.getMediaType())
                && task.getFrozenAmount() != null && task.getFrozenAmount().signum() > 0
                && ledger != null && ledger.valid()
                && Objects.equals(task.getUserId(), ledger.userId())
                && Objects.equals(task.getBillingTraceId(), ledger.traceId())
                && task.getFrozenAmount().compareTo(ledger.frozenAmount()) == 0
                && ledger.grossConsumedAmount().compareTo(ledger.frozenAmount()) == 0
                && ledger.settleRefundedAmount().signum() > 0
                && ledger.netConsumedAmount().signum() > 0
                && ledger.netConsumedAmount().add(ledger.settleRefundedAmount()).compareTo(ledger.frozenAmount()) == 0
                && ledger.unfrozenAmount().signum() == 0 && ledger.settleUnfrozenAmount().signum() == 0;
    }

    public static String digest(IAccountUpdateService.WalletLedgerEvidence ledger) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(ledger.traceId() + ":" + ledger.userId()
                + ":" + ledger.frozenAmount().toPlainString() + ":" + ledger.grossConsumedAmount().toPlainString()
                + ":" + ledger.settleRefundedAmount().toPlainString() + ":" + ledger.netConsumedAmount().toPlainString());
    }
}
