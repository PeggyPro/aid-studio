package com.aid.billing.service;

import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.enums.TextSettleStatus;
import com.aid.billing.model.BillingSnapshot;
import com.aid.common.exception.ServiceException;
import com.aid.media.provider.ProviderUsageSupport;
import com.aid.media.provider.TextFailureBillingPolicy;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Objects;

/** 修复有明确失败证据且没有真实用量的历史结算；所有资金动作复用原账户流水。 */
@Service
@RequiredArgsConstructor
public class TextFailureRefundService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AidMediaTaskMapper tasks;
    private final IAccountUpdateService accounts;
    private final AssistantBillingBudgetService budgets;
    private final PlatformTransactionManager transactionManager;

    public record Preview(String taskId, String traceId, BigDecimal originalAmount,
                          BigDecimal alreadyRefunded, BigDecimal remainingAmount) {}

    public Preview preview(Long taskId) {
        AidMediaTask task = tasks.selectById(taskId);
        eligible(task);
        IAccountUpdateService.WalletLedgerEvidence ledger = ledger(task.getBillingTraceId());
        if (ledger == null) throw new ServiceException("原消费流水不可核验");
        validateLedger(task, ledger, ledger.grossConsumedAmount());
        return view(task, ledger);
    }

    @Transactional(rollbackFor = Exception.class)
    public Preview refund(Long taskId, String expectedTrace, BigDecimal expectedAmount, String operator) {
        AidMediaTask task = tasks.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                .eq(AidMediaTask::getId, taskId).last("FOR UPDATE"));
        BillingSnapshot snapshot = eligible(task);
        if (!Objects.equals(task.getBillingTraceId(), expectedTrace) || expectedAmount == null
                || expectedAmount.signum() <= 0 || expectedAmount.scale() > 4) {
            throw new ServiceException("原消费流水或退款金额不匹配");
        }
        IAccountUpdateService.WalletLedgerEvidence before = ledger(expectedTrace);
        validateLedger(task, before, expectedAmount);
        budgets.verifySettledTextRefund(task, expectedAmount);
        if (before.netConsumedAmount().signum() == 0
                && expectedAmount.compareTo(Objects.requireNonNullElse(snapshot.getFailedTextRefundOriginalAmount(), BigDecimal.ZERO)) == 0
                && task.getActualCost() != null && task.getActualCost().signum() == 0) {
            budgets.correctSettledTextRefund(task, expectedAmount, before, operator);
            return view(task, before);
        }
        if (task.getActualCost() == null || task.getActualCost().compareTo(expectedAmount) != 0) {
            throw new ServiceException("任务原结算金额与消费流水不一致");
        }
        // settleRefund 使用原 trace + settle_refund 幂等键；不得从已消耗的冻结额再次解冻。
        accounts.settleRefund(task.getUserId(), expectedAmount, expectedTrace,
                "text_failed_no_usage_refund", "文本明确失败无用量按原消费流水退回");
        IAccountUpdateService.WalletLedgerEvidence after = ledger(expectedTrace);
        validateLedger(task, after, expectedAmount);
        if (after.netConsumedAmount().signum() != 0) throw new ServiceException("原流水退回尚未核实");
        budgets.correctSettledTextRefund(task, expectedAmount, after, operator);
        snapshot.setFailedTextRefundOriginalAmount(expectedAmount);
        snapshot.setFailedTextRefundOperator(operator);
        snapshot.setFailedTextRefundTime(LocalDateTime.now().toString());
        snapshot.setActualAmount(BigDecimal.ZERO);
        snapshot.setRefundAmount(task.getFrozenAmount());
        snapshot.setTextSettleDone(true);
        task.setActualCost(BigDecimal.ZERO);
        task.setTextSettleStatus(TextSettleStatus.DONE);
        task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        task.setUpdateBy(operator);
        task.setUpdateTime(new Date());
        tasks.updateById(task);
        return view(task, after);
    }

    private BillingSnapshot eligible(AidMediaTask task) {
        if (task == null || !"TEXT".equalsIgnoreCase(task.getMediaType())
                || !"FAILED".equals(task.getStatus()) || !"SUCCESS".equals(task.getBillingStatus())
                || task.getUserId() == null || task.getUserId() <= 0 || task.getBillingTraceId() == null) {
            throw new ServiceException("仅支持已结算的文本明确失败任务");
        }
        try {
            BillingSnapshot snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
            var error = JSON.readTree(task.getErrorDetailJson());
            int status = error.path("providerHttpStatus").asInt(0);
            String errorCode = error.path("errorCode").asText();
            boolean confirmedFailure = status >= 400 && status <= 599
                    || "TASK_INTERRUPTED".equals(errorCode)
                    || "PROVIDER_BALANCE_INSUFFICIENT".equals(errorCode);
            if (!confirmedFailure || snapshot == null
                    || SettledTextLedgerEvidence.SOURCE.equals(snapshot.getSettlementRecoverySource())
                    || Boolean.TRUE.equals(snapshot.getHasAnyProviderUsage())
                    || Boolean.TRUE.equals(snapshot.getProviderUsageCaptured())
                    || snapshot.getActualInputTokens() != null || snapshot.getActualOutputTokens() != null
                    || "USAGE_OBSERVED".equals(error.path("providerCallOutcome").asText())
                    || ProviderUsageSupport.hasAnyProviderUsage(TextFailureBillingPolicy.observedUsage(task.getErrorDetailJson()))
                    || task.getResponseJson() != null && ProviderUsageSupport.hasAnyProviderUsage(
                    TokenDanceResponseMapper.usage(JSON.readTree(task.getResponseJson())))) {
                throw new ServiceException("缺少明确失败证据或存在真实用量，不能全额退回");
            }
            return snapshot;
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("失败或计费证据不可核验"); }
    }

    private IAccountUpdateService.WalletLedgerEvidence ledger(String trace) {
        // 钱包动作独立提交后须读新事务，避免外层快照或 MyBatis 缓存误读旧余额流水。
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        return read.execute(status -> accounts.inspectLedger(trace));
    }

    private void validateLedger(AidMediaTask task, IAccountUpdateService.WalletLedgerEvidence ledger,
                                BigDecimal amount) {
        if (ledger == null || !ledger.valid() || amount == null || amount.signum() <= 0
                || !Objects.equals(task.getUserId(), ledger.userId())
                || !Objects.equals(task.getBillingTraceId(), ledger.traceId())
                || ledger.grossConsumedAmount().compareTo(amount) != 0
                || ledger.frozenAmount().compareTo(amount) != 0
                || task.getFrozenAmount() == null || task.getFrozenAmount().compareTo(amount) != 0
                || ledger.unfrozenAmount().signum() != 0 || ledger.settleUnfrozenAmount().signum() != 0
                || !(ledger.settleRefundedAmount().signum() == 0 || ledger.settleRefundedAmount().compareTo(amount) == 0)) {
            throw new ServiceException("原账本金额、归属或退回状态不一致");
        }
    }

    private Preview view(AidMediaTask task, IAccountUpdateService.WalletLedgerEvidence ledger) {
        return new Preview(String.valueOf(task.getId()), task.getBillingTraceId(),
                ledger.grossConsumedAmount(), ledger.settleRefundedAmount(), ledger.netConsumedAmount());
    }
}
