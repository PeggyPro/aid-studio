package com.aid.media.admission;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.model.BillingSnapshot;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Routes stop cancellation to exactly one authoritative business handler. */
@Service
@RequiredArgsConstructor
public class MediaTaskCancellationAdmissionGate {
    private final List<MediaTaskCancellationAdmissionHandler> handlers;

    public MediaTaskCancellationAdmissionDecision claimCancellation(
            AidMediaTask task, MediaTaskCancellationRequest request) {
        if (task == null || task.getId() == null
                || request == null || !task.getId().equals(request.taskId())
                || request.purpose() == null
                || !BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())
                || StrUtil.isBlank(task.getBillingSnapshotJson())) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_IDENTITY_INCOMPLETE");
        }
        BillingSnapshot snapshot;
        try {
            snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
        } catch (RuntimeException error) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_SNAPSHOT_INVALID");
        }
        if (snapshot == null || !"RUN_AUTHORIZATION".equals(snapshot.getBudgetMode())
                || snapshot.getBillingAuthorizationId() == null
                || snapshot.getBillingReservationId() == null
                || snapshot.getRuntimeRunId() == null || snapshot.getRuntimeStepId() == null) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_SNAPSHOT_INVALID");
        }
        List<MediaTaskCancellationAdmissionHandler> matched = handlers.stream()
                .filter(handler -> task.getBizTaskType().equals(handler.supportedBizTaskType()))
                .toList();
        if (matched.size() != 1) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_HANDLER_CARDINALITY_INVALID");
        }
        MediaTaskCancellationAdmissionDecision decision =
                matched.get(0).claimCancellation(task, snapshot, request);
        if (decision == null) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_DECISION_INVALID");
        }
        return decision;
    }
}
