package com.aid.billing.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests.AuthorizationQuery;
import com.aid.billing.dto.BillingBudgetRequests.CreateAuthorizationRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReserveCallRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReviseAuthorizationRequest;
import com.aid.billing.dto.BillingBudgetRequests.RenewAuthorizationRequest;
import com.aid.billing.dto.BillingBudgetRequests.SubmissionClaimRequest;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationView;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationPageView;
import com.aid.billing.dto.BillingBudgetViews.ReservationView;
import com.aid.billing.dto.BillingBudgetViews.SubmissionClaimResult;
import com.aid.billing.model.BillingSnapshot;

import java.math.BigDecimal;
import java.util.List;

/** 两类助手共用的Run总预算服务。 */
public interface AssistantBillingBudgetService {

    AuthorizationView createAuthorization(CreateAuthorizationRequest request);

    AuthorizationView reviseAuthorization(ReviseAuthorizationRequest request);

    default AuthorizationView renewExpiredAuthorization(RenewAuthorizationRequest request) {
        throw new UnsupportedOperationException("续授权暂不可用");
    }

    default boolean hasUnresolvedReservations(Long authorizationId) { return true; }

    ReservationView reserveCall(ReserveCallRequest request);

    /** Secures the exact text call at the wallet without spending the media authorization. */
    ReservationView reserveWalletTextCall(ReserveCallRequest request);

    /** 必须加入调用方已有事务，使Runtime、节点占用与预算认领共同提交或回滚。 */
    SubmissionClaimResult claimSubmission(SubmissionClaimRequest request);

    /** Release only after a trusted guard proves the runtime child failed before task creation. */
    ReservationView releaseUnsubmittedReservation(ReleaseUnsubmittedReservationRequest request);

    AuthorizationView getAuthorization(Long authorizationId);

    AuthorizationView getAuthorizationByRunId(Long runtimeRunId);

    AuthorizationPageView listAuthorizations(AuthorizationQuery query);

    List<ReservationView> listReservations(Long authorizationId);

    /** 钱包冻结前只读核验预留，并把稳定trace与授权上限写入任务快照。 */
    ReservationView verifyPreHold(BillingBudgetContext context, AidMediaTask task,
                                  BigDecimal calculatedPreHold, BillingSnapshot snapshot);

    /** 钱包冻结成功后，在媒体任务事务内绑定task并推进到RESERVED。 */
    ReservationView markReserved(BillingBudgetContext context, AidMediaTask task, String operator);

    /** 预冻结校验或冻结链路失败时，以原trace补偿钱包并释放预算占用。 */
    void compensatePreHoldFailure(BillingBudgetContext context, AidMediaTask task,
                                  BigDecimal calculatedPreHold, String operator);

    /** Provider边界前的权威准入检查；旧任务快照无预算模式时直接兼容通过。 */
    void assertSubmissionAdmitted(AidMediaTask task);

    /** Locks the existing authorization/reservation; only RESERVED proves no Provider claim. */
    boolean lockUnsubmittedCancellation(AidMediaTask task);

    /** 上游受理结果未知时保留全部预算占用。 */
    void markSubmissionUnknown(AidMediaTask task, String operator);

    /** 既有账户结算完成后更新授权投影。 */
    void settleFromMediaTask(AidMediaTask task, String operator);

    /** Restore only the budget projection of an already committed text wallet settlement. */
    default void recoverSettledTextFromLedger(AidMediaTask task,
            IAccountUpdateService.WalletLedgerEvidence ledger, String operator) {
        throw new UnsupportedOperationException("已结算账本恢复不可用");
    }

    /** 既有账户退款完成后更新授权投影。 */
    void refundFromMediaTask(AidMediaTask task, String operator);

    /** Release a durable reservation after an orphan-task wallet refund is proven committed. */
    void releaseAfterOrphanRefund(AidMediaTask task, String operator);

    /** 历史已结算文本失败退回前核验并锁定原预算，不重新开放授权。 */
    void verifySettledTextRefund(AidMediaTask task, BigDecimal originalAmount);

    /** 原钱包流水退回完成后校正已结算预算投影；原消费及退回流水均保留。 */
    void correctSettledTextRefund(AidMediaTask task, BigDecimal originalAmount,
                                  IAccountUpdateService.WalletLedgerEvidence ledger, String operator);
}
