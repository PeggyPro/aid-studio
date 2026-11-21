package com.aid.media.admission;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingBudgetContext;

/**
 * Business-owned atomic guard for the first durable media-task intent.
 *
 * Implementations run inside the media task creation transaction. A new task must compare the
 * caller's independent run/step fences with the locked business rows and bind task.id before the
 * billing pre-hold is prepared. An already-bound identical task may replay across later lease
 * hand-offs; a different task or ambiguous identity is reconciliation-only.
 */
public interface MediaTaskIntentAdmissionHandler
{
    String supportedBizTaskType();

    MediaTaskIntentAdmissionDecision claimBeforeCommit(AidMediaTask task, BillingBudgetContext context);
}
