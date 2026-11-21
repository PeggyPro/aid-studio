package com.aid.media.admission;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Selects the unique business guard for first media-task persistence without importing Runtime. */
@Service
@RequiredArgsConstructor
public class MediaTaskIntentAdmissionGate
{
    private final List<MediaTaskIntentAdmissionHandler> handlers;

    public void claimNewBeforeCommit(AidMediaTask task, BillingBudgetContext context)
    {
        MediaTaskIntentAdmissionDecision decision = decide(task, context);
        if (decision != null && decision != MediaTaskIntentAdmissionDecision.PERSIST_NEW)
            throw new MediaTaskIntentConflictException();
    }

    public void assertExistingReplay(AidMediaTask task, BillingBudgetContext context)
    {
        MediaTaskIntentAdmissionDecision decision = decide(task, context);
        if (decision != null && decision != MediaTaskIntentAdmissionDecision.REPLAY_EXISTING)
            throw new MediaTaskIntentConflictException();
    }

    private MediaTaskIntentAdmissionDecision decide(AidMediaTask task, BillingBudgetContext context)
    {
        boolean assistantTask = task != null
                && BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType());
        boolean budgetMode = context != null && context.isRunAuthorizationMode();
        if (!assistantTask && !budgetMode) return null;
        if (!assistantTask || !budgetMode || task.getId() == null
                || context.getRuntimeRunId() == null || context.getRuntimeStepId() == null
                || context.getRuntimeRunFencingToken() == null
                || context.getRuntimeStepFencingToken() == null)
            throw new ServiceException("MEDIA_TASK_INTENT_IDENTITY_INCOMPLETE");
        List<MediaTaskIntentAdmissionHandler> matched = handlers.stream()
                .filter(handler -> task.getBizTaskType().equals(handler.supportedBizTaskType()))
                .toList();
        if (matched.size() != 1)
            throw new ServiceException("MEDIA_TASK_INTENT_HANDLER_CARDINALITY_INVALID");
        MediaTaskIntentAdmissionDecision decision = matched.get(0).claimBeforeCommit(task, context);
        if (decision == null) throw new ServiceException("MEDIA_TASK_INTENT_DECISION_INVALID");
        return decision;
    }
}
