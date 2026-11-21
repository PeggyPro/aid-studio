package com.aid.media.admission;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Provider出站前选择唯一业务认领器，并执行预算最终准入。 */
@Service
@RequiredArgsConstructor
public class MediaSubmissionAdmissionGate {

    private final List<MediaSubmissionAdmissionHandler> handlers;
    private final AssistantBillingBudgetService billingBudgetService;

    public void admitBeforeProvider(AidMediaTask task) {
        BillingSnapshot snapshot = parseSnapshot(task);
        boolean assistantTask = task != null
                && BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType());
        boolean budgetMode = snapshot != null
                && BillingBudgetContext.MODE_RUN_AUTHORIZATION.equals(snapshot.getBudgetMode());
        if (!assistantTask && !budgetMode) {
            return;
        }
        if (!assistantTask || !budgetMode) {
            throw new ServiceException("预算准入上下文缺失");
        }

        MediaSubmissionAdmissionHandler handler = requireUniqueHandler(task);
        MediaSubmissionAdmissionDecision decision = handler.claimBeforeProvider(task, snapshot);
        if (decision == MediaSubmissionAdmissionDecision.DEFER_UNTIL_RESUME) {
            throw new MediaSubmissionDeferredException();
        }
        if (decision != MediaSubmissionAdmissionDecision.PROCEED_NEW_CLAIM) {
            markOutcomeUnknown(task);
            throw new ProviderSubmissionOutcomeUnknownException();
        }
        billingBudgetService.assertSubmissionAdmitted(task);
    }

    /** Provider请求可能已被受理时，将预算推进到保留占用的对账态。 */
    public void markOutcomeUnknown(AidMediaTask task) {
        billingBudgetService.markSubmissionUnknown(task, "system");
        BillingSnapshot snapshot = parseSnapshot(task);
        boolean assistantTask = task != null
                && BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType());
        boolean budgetMode = snapshot != null
                && BillingBudgetContext.MODE_RUN_AUTHORIZATION.equals(snapshot.getBudgetMode());
        if (!assistantTask && !budgetMode) {
            return;
        }
        if (!assistantTask || !budgetMode) {
            throw new ServiceException("budget admission identity is incomplete");
        }
        requireUniqueHandler(task).markReconciliationRequired(task, snapshot);
    }

    /** 任务行事务回滚后，钱包已由调用方幂等退回时，独立释放预算投影。 */
    public void releaseAfterOrphanRefund(AidMediaTask task) {
        billingBudgetService.releaseAfterOrphanRefund(task, "system");
    }

    private MediaSubmissionAdmissionHandler requireUniqueHandler(AidMediaTask task) {
        List<MediaSubmissionAdmissionHandler> matched = handlers.stream()
                .filter(handler -> task.getBizTaskType().equals(handler.supportedBizTaskType()))
                .toList();
        if (matched.size() != 1) {
            throw new ServiceException("budget admission handler cardinality is invalid");
        }
        return matched.get(0);
    }

    private BillingSnapshot parseSnapshot(AidMediaTask task) {
        if (task == null || StrUtil.isBlank(task.getBillingSnapshotJson())) {
            return null;
        }
        try {
            return JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
        } catch (Exception error) {
            throw new ServiceException("计费快照无效");
        }
    }
}
