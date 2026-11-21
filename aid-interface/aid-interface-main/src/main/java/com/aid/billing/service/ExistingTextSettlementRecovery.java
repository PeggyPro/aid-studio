package com.aid.billing.service;

import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidBalanceLogMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.mapper.MediaExecutionPermitMapper;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.enums.TextSettleStatus;
import com.aid.common.exception.ServiceException;
import com.aid.media.reconciliation.SynchronousExecutionFailureEvidence;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Rebuild projections inside the existing terminal transaction. Does not mutate wallet entries. */
@Service
@RequiredArgsConstructor
public class ExistingTextSettlementRecovery {
    private final AidMediaTaskMapper tasks;
    private final AidBalanceLogMapper logs;
    private final MediaExecutionPermitMapper permits;
    private final IAccountUpdateService accounts;
    private final AssistantBillingBudgetService budgets;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean recover(AidMediaTask original, AidMediaTask terminal) {
        if (original == null || terminal == null || !"TEXT".equals(original.getMediaType())
                || !"PENDING".equals(original.getStatus()) || !"FAILED".equals(terminal.getStatus())
                || !"FROZEN".equals(original.getBillingStatus()) || !"FROZEN".equals(terminal.getBillingStatus())
                || original.getActualCost() != null || original.getBillingTraceId() == null
                || original.getResponseJson() != null && !original.getResponseJson().isBlank()
                || original.getResultText() != null && !original.getResultText().isBlank()
                || !Objects.equals(original.getId(), terminal.getId())
                || !Objects.equals(original.getUserId(), terminal.getUserId())
                || !Objects.equals(original.getBillingTraceId(), terminal.getBillingTraceId())
                || !Objects.equals(original.getExecutionPermitToken(), terminal.getExecutionPermitToken())
                || SynchronousExecutionFailureEvidence.capture(original,
                    permits.selectByToken(original.getExecutionPermitToken())) == null) return false;
        // Only lock the ledger when this is a recovery of an existing settlement. The ordinary
        // refund inserts an unfreeze row in a separate transaction and must not inherit a gap lock.
        var ledger = accounts.inspectLedger(original.getBillingTraceId());
        if (!SettledTextLedgerEvidence.matches(original, ledger)) return false;
        var entries = logs.selectList(Wrappers.<AidBalanceLog>lambdaQuery()
                .eq(AidBalanceLog::getRelatedId, original.getBillingTraceId()).last("LIMIT 16 FOR UPDATE"));
        var amounts = java.util.Map.of("freeze", ledger.frozenAmount().negate(),
                "consume", ledger.grossConsumedAmount().negate(), "settle_refund", ledger.settleRefundedAmount());
        if (entries.size() != amounts.size() || entries.stream().map(AidBalanceLog::getChangeType).distinct().count() != amounts.size()
                || entries.stream().anyMatch(entry -> entry.getChangeType() == null
                    || !Objects.equals(entry.getUserId(), ledger.userId()) || !"0".equals(entry.getDelFlag())
                    || !amounts.containsKey(entry.getChangeType()) || entry.getAmount() == null
                    || amounts.get(entry.getChangeType()).compareTo(entry.getAmount()) != 0))
            throw new ServiceException("原结算流水已变化，请重新核对");
        if (tasks.selectCount(Wrappers.<AidMediaTask>lambdaQuery()
                .eq(AidMediaTask::getBillingTraceId, original.getBillingTraceId())) != 1L)
            throw new ServiceException("原结算流水关联不唯一，不能恢复");
        BillingSnapshot snapshot = JSONUtil.toBean(terminal.getBillingSnapshotJson(), BillingSnapshot.class);
        if (snapshot == null) throw new ServiceException("原结算快照缺失");
        snapshot.setActualAmount(ledger.netConsumedAmount());
        snapshot.setRefundAmount(ledger.settleRefundedAmount());
        snapshot.setTextSettleDone(true);
        snapshot.setSettlementRecoverySource(SettledTextLedgerEvidence.SOURCE);
        snapshot.setSettlementRecoveryLedgerDigest(SettledTextLedgerEvidence.digest(ledger));
        terminal.setActualCost(ledger.netConsumedAmount());
        terminal.setBillingStatus("SUCCESS");
        terminal.setTextSettleStatus(TextSettleStatus.DONE);
        terminal.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        int changed = tasks.update(null, Wrappers.<AidMediaTask>lambdaUpdate()
                .eq(AidMediaTask::getId, terminal.getId()).eq(AidMediaTask::getStatus, "FAILED")
                .eq(AidMediaTask::getBillingStatus, "FROZEN").isNull(AidMediaTask::getActualCost)
                .eq(AidMediaTask::getBillingTraceId, original.getBillingTraceId())
                .set(AidMediaTask::getActualCost, terminal.getActualCost())
                .set(AidMediaTask::getBillingStatus, "SUCCESS")
                .set(AidMediaTask::getTextSettleStatus, TextSettleStatus.DONE)
                .set(AidMediaTask::getBillingSnapshotJson, terminal.getBillingSnapshotJson()));
        if (changed != 1) throw new ServiceException("原结算恢复状态已变化");
        budgets.recoverSettledTextFromLedger(terminal, ledger, "system");
        return true;
    }
}
